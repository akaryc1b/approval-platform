import { execFileSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { Agent, request as httpsRequest } from 'node:https';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { createServer } from 'node:net';
import { createEvaluationReadRuntime } from './evaluation-read-runtime.mjs';
import { evaluationCookie } from './evaluation-http.mjs';
import { evaluationStableBusinessDigest, requireEvaluationBusinessReset } from './evaluation-payment-evidence.mjs';

const required = (value, code) => { if (!value) throw new Error(code); };
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const sha = value => createHash('sha256').update(value).digest('hex');
async function reservePort() {
  const server = createServer();
  await new Promise((done, fail) => { server.once('error', fail); server.listen(0, '127.0.0.1', done); });
  const port = server.address().port;
  await new Promise(done => server.close(done));
  // The later listen may lose the port race; fail, never kill another listener.
  return port;
}

/** Exact-source real Docker/HTTPS/business rehearsal, not a browser/UI acceptance test.
 * No platform tables are written directly: upload, start and decisions all use the real APIs.
 * The only SQL is read-only evidence. Reset is owned whole-stack replacement.
 */
export async function executeEvaluationBusinessRehearsal({ smoke, directory, scenario, maximumMs = 360_000 }, {
  run, createRuntime = createEvaluationReadRuntime,
} = {}) {
  required(smoke?.status === 'LOCAL_IMAGE_STARTUP_SMOKE_PASSED' && smoke.cleanup?.status === 'PASSED'
    && smoke.build?.status === 'LOCAL_IMAGES_BUILT_NOT_RUNTIME_ACCEPTED'
    && JSON.stringify(smoke.source) === JSON.stringify(smoke.build.source), 'EXACT_SOURCE_SMOKE_REQUIRED');
  required(Number.isInteger(maximumMs) && maximumMs >= 1000 && maximumMs <= 360000, 'BUSINESS_REHEARSAL_BUDGET');
  const started = performance.now(); const stop = new AbortController();
  const timer = setTimeout(() => stop.abort(), maximumMs);
  let runtime; let agent; let tlsDirectory; let failed = false; let time = 1000;
  const receipt = { schemaVersion: 1, kind: 'EVALUATION_BUSINESS_API_REHEARSAL', source: smoke.source,
    status: 'RUNNING', phase: 'INITIALIZE', checks: [], cleanup: null,
    nonClaims: ['PC_H5_BROWSER_E2E_NOT_EXECUTED', 'PUBLIC_URL_NOT_PUBLISHED',
      'REAL_PAYMENT_NOT_USED', 'HOST_GATEWAY_EGRESS_NOT_VERIFIED', 'WALL_CLOCK_EXPIRY_NOT_MEASURED'] };
  const save = () => writeFileSync(resolve(directory, 'evaluation-business-rehearsal.json'),
    `${JSON.stringify(receipt, null, 2)}\n`, { mode: 0o600 });
  const checkTime = () => required(!stop.signal.aborted && performance.now() - started < maximumMs, 'BUSINESS_REHEARSAL_DEADLINE');
  let origin;
  async function call(session, method, path, body = Buffer.alloc(0), type = '', expected = [200]) {
    checkTime();
    const headers = { Host: new URL(origin).host, Origin: origin, 'Content-Length': String(body.length) };
    if (session?.cookie) headers.Cookie = session.cookie;
    if (session?.view?.csrfToken) headers['X-Evaluation-CSRF'] = session.view.csrfToken;
    if (type) headers['Content-Type'] = type;
    if (method === 'POST' && path.startsWith('/api/')) headers['Idempotency-Key'] = `evaluation-${randomBytes(16).toString('hex')}`;
    const result = await new Promise((done, fail) => {
      const request = httpsRequest(origin + path, { method, headers, agent, rejectUnauthorized: true,
        lookup: (host, options, callback) => options.all ? callback(null, [{ address: '127.0.0.1', family: 4 }]) : callback(null, '127.0.0.1', 4), signal: stop.signal }, response => {
        const parts = []; let bytes = 0;
        response.on('data', part => { bytes += part.length; if (bytes > 2097152) request.destroy(new Error('RESPONSE_LIMIT')); else parts.push(part); });
        response.once('end', () => done({ status: response.statusCode, headers: response.headers, body: Buffer.concat(parts) }));
        response.once('error', fail);
      });
      request.once('error', () => fail(new Error('HTTPS_BUSINESS_REQUEST_FAILED')));
      request.setTimeout(path.endsWith('/end') ? 65000 : 12000, () => request.destroy(new Error('REQUEST_TIMEOUT')));
      request.end(body);
    });
    checkTime(); required(expected.includes(result.status), 'UNEXPECTED_BUSINESS_HTTP_STATUS');
    const cookie = result.headers['set-cookie']?.find(value => value.startsWith(`${evaluationCookie}=`));
    if (cookie && session) session.cookie = cookie.split(';')[0];
    return result;
  }
  async function json(session, method, path, data, expected = [200]) {
    const result = await call(session, method, path, data === undefined ? Buffer.alloc(0) : Buffer.from(JSON.stringify(data)),
      data === undefined ? '' : 'application/json', expected);
    return result.body.length ? JSON.parse(result.body.toString('utf8')) : null;
  }
  async function enter() {
    const session = { cookie: '', view: null };
    session.view = await json(session, 'POST', '/evaluation/invitations/redeem',
      { invitation: runtime.controller.issueInvitation().invitation }, [201]);
    required(session.view.businessAccess === 'PURCHASE_PAYMENT_WORKFLOW', 'BUSINESS_SESSION_NOT_CONNECTED');
    return session;
  }
  async function actor(session, id) {
    session.view = await json(session, 'POST', '/evaluation/session/actor', { actorId: id });
    required(session.view.actorId === id, 'ACTOR_SWITCH_FAILED');
  }
  async function uploadAndStart(session, label) {
    const content = Buffer.from(`Non-production evaluator ${label} invoice ${randomBytes(16).toString('hex')}\n`);
    const boundary = `Evaluation${randomBytes(16).toString('hex')}`;
    const body = Buffer.concat([Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="evaluation-${label}.txt"\r\nContent-Type: text/plain\r\n\r\n`),
      content, Buffer.from(`\r\n--${boundary}--\r\n`)]);
    const result = await call(session, 'POST', '/api/approval/attachments', body, `multipart/form-data; boundary=${boundary}`);
    const attachment = JSON.parse(result.body.toString('utf8'));
    required(uuid.test(attachment.attachmentId || '') && attachment.sha256 === sha(content), 'REAL_ATTACHMENT_UPLOAD_FAILED');
    const token = randomBytes(8).toString('hex'); const businessKey = `EVAL-${label}-${token}`;
    const started = await json(session, 'POST', '/api/approval/forms/purchase-payment/versions/1/submissions', {
      businessKey, values: { amount: Number(scenario.request.amount), supplier: scenario.request.supplier,
        purchaseOrderReference: `PO-EVAL-${label}-${token}`, attachments: [attachment.attachmentId] },
      startParameters: scenario.assigneeRules,
    });
    required(uuid.test(started.instanceId || ''), 'REAL_INSTANCE_START_FAILED');
    const downloaded = await call(session, 'GET', `/api/approval/attachments/${attachment.attachmentId}/content`);
    required(downloaded.body.equals(content), 'ATTACHMENT_CONTENT_MISMATCH');
    return { instanceId: started.instanceId, attachmentId: attachment.attachmentId,
      attachmentSha256: attachment.sha256, businessKey };
  }
  async function complete(session, instance) {
    for (const step of scenario.expectedWorkflow) for (const id of step.actorIds) {
      await actor(session, id);
      const page = await json(session, 'GET', '/api/approval/tasks/pending?limit=20&offset=0');
      const tasks = page.items.filter(task => task.instanceId === instance.instanceId && task.taskDefinitionKey === step.taskDefinitionKey);
      required(tasks.length === 1 && uuid.test(tasks[0].taskId), 'EXACT_CURRENT_TASK_REQUIRED');
      const result = await json(session, 'POST', `/api/approval/tasks/${tasks[0].taskId}/approve`, { comment: 'Evaluation approved' });
      required(result.instanceId === instance.instanceId, 'APPROVAL_INSTANCE_CHANGED');
    }
    const result = await json(session, 'GET', `/api/approval/instances/${instance.instanceId}`);
    // Existing InstanceDetails wraps the real projection. Do not accept invented response shapes.
    required(result.instance?.status === 'COMPLETED', 'INSTANCE_NOT_COMPLETED');
    const timeline = await json(session, 'GET', `/api/approval/instances/${instance.instanceId}/timeline`);
    required(timeline.instanceId === instance.instanceId && Array.isArray(timeline.items) && timeline.items.length > 0, 'AUDIT_TIMELINE_REQUIRED');
    receipt.checks.push({ check: 'REAL_API_APPROVAL_COMPLETED', instanceId: instance.instanceId,
      decisions: scenario.expectedWorkflow.reduce((sum, step) => sum + step.actorIds.length, 0), auditEvents: timeline.items.length });
  }
  async function pending(slotId, instanceId) {
    const deadline = performance.now() + 15000;
    for (;;) {
      checkTime(); const state = await runtime.payment.snapshot(slotId, stop.signal);
      if (state.outbox.some(row => row.aggregateId === instanceId && row.status === 'PENDING'
          && row.lastError?.startsWith('HTTP 503: payment sandbox unavailable') && row.attempts >= 1)) return state;
      required(performance.now() < deadline, 'OUTBOX_UNAVAILABLE_NOT_OBSERVED');
      await new Promise(done => setTimeout(done, 250));
    }
  }
  save();
  try {
    tlsDirectory = mkdtempSync(resolve(tmpdir(), 'approval-evaluation-tls-')); chmodSync(tlsDirectory, 0o700);
    const keyPath = resolve(tlsDirectory, 'key.pem'); const certPath = resolve(tlsDirectory, 'cert.pem');
    execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes', '-days', '1',
      '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1', '-keyout', keyPath, '-out', certPath],
    { timeout: 10000, stdio: 'ignore', shell: false }); chmodSync(keyPath, 0o600);
    const key = readFileSync(keyPath); const cert = readFileSync(certPath);
    const port = await reservePort(); origin = `https://localhost:${port}`;
    runtime = await createRuntime({ source: smoke.source, backendImage: smoke.build.images.find(image => image.component === 'backend'),
      infrastructure: smoke.infrastructure, archiveSha256: smoke.build.archiveSha256, scenario,
      record: value => writeFileSync(resolve(directory, 'evaluation-business-resources.json'), `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 }),
      key, cert, origin, signal: stop.signal, maximumLifetimeMs: maximumMs, sessionTtlMs: 600000,
      clock: () => time, run, workflow: true });
    await new Promise((done, fail) => { runtime.server.once('error', fail); runtime.server.listen(port, '127.0.0.1', done); });
    agent = new Agent({ ca: cert, rejectUnauthorized: true, keepAlive: false });
    const a = await enter(); const b = await enter();
    required(a.cookie !== b.cookie, 'INDEPENDENT_COOKIES_REQUIRED');
    receipt.phase = 'UPLOAD_START_CROSS_ACCESS'; save();
    const instanceA = await uploadAndStart(a, 'A'); const instanceB = await uploadAndStart(b, 'B');
    receipt.created = { a: instanceA, b: instanceB };
    for (const [session, other] of [[a, instanceB], [b, instanceA]]) {
      await call(session, 'GET', `/api/approval/instances/${other.instanceId}`, undefined, '', [404]);
      await call(session, 'GET', `/api/approval/attachments/${other.attachmentId}/content`, undefined, '', [404]);
    }
    await actor(b, 'demo-manager');
    const bTasks = await json(b, 'GET', '/api/approval/tasks/pending');
    const bTask = bTasks.items.find(task => task.instanceId === instanceB.instanceId);
    required(bTask && uuid.test(bTask.taskId), 'OTHER_REAL_TASK_REQUIRED');
    await actor(a, 'demo-manager');
    await json(a, 'POST', `/api/approval/tasks/${bTask.taskId}/approve`, { comment: null }, [400, 403, 404, 409]);
    receipt.checks.push({ check: 'CROSS_SESSION_BUSINESS_IDS_REJECTED', instances: 2, attachments: 2, taskWrite: 1 });
    receipt.phase = 'COMPLETE_A_PAYMENT'; save(); await complete(a, instanceA); await pending('slot-a', instanceA.instanceId);
    receipt.paymentA = await runtime.payment.recover('slot-a', stop.signal);
    const beforeA = await runtime.payment.snapshot('slot-a', stop.signal); const beforeB = await runtime.payment.snapshot('slot-b', stop.signal);
    const beforeBResources = runtime.snapshot().resources.slots.find(slot => slot.slotId === 'slot-b');
    const oldA = { cookie: a.cookie, view: a.view };
    receipt.phase = 'RESET_A_PRESERVE_B'; save();
    await call(a, 'POST', '/evaluation/session/end', Buffer.from('{}'), 'application/json', [204]);
    await call(oldA, 'GET', '/evaluation/session', undefined, '', [401]);
    const afterA = await runtime.payment.snapshot('slot-a', stop.signal); const afterB = await runtime.payment.snapshot('slot-b', stop.signal);
    required(evaluationStableBusinessDigest(beforeB) === evaluationStableBusinessDigest(afterB)
      && JSON.stringify(beforeBResources) === JSON.stringify(runtime.snapshot().resources.slots.find(slot => slot.slotId === 'slot-b')),
    'OTHER_EVALUATOR_CHANGED_BY_RESET');
    receipt.resetA = requireEvaluationBusinessReset(beforeA, afterA, instanceA.instanceId, instanceA.attachmentId);
    time = 2000; const replacement = await enter();
    await call(replacement, 'GET', `/api/approval/instances/${instanceA.instanceId}`, undefined, '', [404]);
    await call(replacement, 'GET', `/api/approval/attachments/${instanceA.attachmentId}/content`, undefined, '', [404]);
    required((await json(b, 'GET', '/evaluation/session')).actorId === b.view.actorId, 'OTHER_COOKIE_REVOKED');
    receipt.phase = 'CONTINUE_B_PAYMENT'; save(); await complete(b, instanceB); await pending('slot-b', instanceB.instanceId);
    receipt.paymentB = await runtime.payment.recover('slot-b', stop.signal);
    const finalB = await runtime.payment.snapshot('slot-b', stop.signal); const replacementA = await runtime.payment.snapshot('slot-a', stop.signal);
    receipt.phase = 'CONTROLLED_EXPIRY_B'; save(); time = 601000;
    const swept = await runtime.controller.sweep(); required(swept.attempted === 1 && swept.failed === 0, 'BUSINESS_EXPIRY_RESET_FAILED');
    receipt.resetB = requireEvaluationBusinessReset(finalB, await runtime.payment.snapshot('slot-b', stop.signal), instanceB.instanceId, instanceB.attachmentId);
    required(evaluationStableBusinessDigest(replacementA) === evaluationStableBusinessDigest(await runtime.payment.snapshot('slot-a', stop.signal)),
      'EXPIRY_CHANGED_REPLACEMENT');
    await call(b, 'GET', '/evaluation/session', undefined, '', [401]);
    await json(replacement, 'GET', '/evaluation/session');
    receipt.checksPassed = true;
  } catch {
    failed = true; receipt.failure = 'EVALUATION_BUSINESS_API_REHEARSAL_FAILED';
  } finally {
    clearTimeout(timer); agent?.destroy();
    try { receipt.cleanup = runtime ? await runtime.dispose() : { status: 'NOT_CREATED' }; }
    catch { receipt.cleanup = { status: 'FAILED' }; }
    if (tlsDirectory) { try { rmSync(tlsDirectory, { recursive: true, force: true }); } catch { receipt.cleanup.status = 'FAILED'; } }
    receipt.elapsedMs = Math.round(performance.now() - started);
    receipt.status = !failed && receipt.checksPassed && receipt.cleanup.status === 'PASSED'
      ? 'TWO_SESSION_REAL_BUSINESS_API_RESET_PASSED' : 'FAILED'; save();
  }
  if (receipt.status === 'FAILED') throw new Error('EVALUATION_BUSINESS_API_REHEARSAL_FAILED');
  return receipt;
}
