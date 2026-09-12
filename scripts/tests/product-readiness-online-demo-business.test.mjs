import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test, { after } from 'node:test';
import { createEvaluationSessions } from '../product-readiness/online-demo/evaluation-sessions.mjs';
import { evaluationBusinessRoute, createEvaluationBusinessSigner, normalizeEvaluationBusinessRequest,
  evaluationUploadDigest } from '../product-readiness/online-demo/evaluation-business-request.mjs';

const root = resolve(import.meta.dirname, '../..');
const scenario = JSON.parse(readFileSync(resolve(root, 'config/demo/purchase-payment-golden-path.json')));
const generation = 'a'.repeat(32);
const taskId = '00000000-0000-4000-8000-000000000001';
const approve = () => ({ method: 'POST', target: `/api/approval/tasks/${taskId}/approve`,
  contentType: 'application/json', idempotencyKey: 'approve-1', body: Buffer.from('{"comment":"同意"}') });
const read = () => ({ method: 'GET', target: '/api/approval/tasks/pending?limit=20&offset=0',
  contentType: '', idempotencyKey: '', body: Buffer.alloc(0) });
const classes = mkdtempSync(resolve(tmpdir(), 'evaluation-business-java-'));
after(() => rmSync(classes, { recursive: true, force: true }));
const harness = `import java.util.*; import java.time.*; import java.io.*;
import io.github.akaryc1b.approval.security.OnlineEvaluationBusinessTicket;
public class EvaluationBusinessHarness {
 public static void main(String[] a) throws Exception {
  if(a[0].equals("route")){for(String line:new BufferedReader(new InputStreamReader(System.in)).lines().toList()) {
   var f=line.split("\\t",-1);try{System.out.println(OnlineEvaluationBusinessTicket.route(f[0],f[1]));}catch(Exception e){System.out.println("DENIED");}}return;}
  if(a[0].equals("upload")){System.out.println(OnlineEvaluationBusinessTicket.uploadDigest(a[1],a[2],Base64.getDecoder().decode(a[3])));return;}
  var v=new OnlineEvaluationBusinessTicket(a[0],a[1],Set.of("demo-employee","demo-manager","demo-finance-reviewer","demo-finance-approver-a","demo-finance-approver-b"),Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC));
  for(String line:new BufferedReader(new InputStreamReader(System.in)).lines().toList()){
   var f=line.split("\\t",-1);try{System.out.println(v.verify(f[0],f[1],f[2],f[3],f[4],f[5],f[6]).actorId());}catch(Exception e){System.out.println("DENIED");}}
 }
}`;
writeFileSync(resolve(classes, 'EvaluationBusinessHarness.java'), harness);
const build = spawnSync('javac', ['--release', '17', '-d', classes,
  resolve(root, 'apps/server/src/main/java/io/github/akaryc1b/approval/security/OnlineEvaluationBusinessTicket.java'),
  resolve(classes, 'EvaluationBusinessHarness.java')], { encoding: 'utf8', timeout: 15_000 });
assert.equal(build.status, 0, build.stderr);
function java(args, lines = []) {
  const run = spawnSync('java', ['-cp', classes, 'EvaluationBusinessHarness', ...args], {
    input: lines.join('\n') + '\n', encoding: 'utf8', timeout: 5000,
  });
  assert.equal(run.status, 0, run.stderr); return run.stdout.trim().split('\n');
}
function wire(proof, request, overrides = {}) {
  const values = { proof, method: request.method, target: request.target, contentType: request.ticketContentType,
    bodySha256: request.bodySha256, idempotencyKey: request.idempotencyKey, requestId: 'evaluation-test', ...overrides };
  return Object.values(values).join('\t');
}
const normalize = (r, actor = 'demo-manager') => normalizeEvaluationBusinessRequest(r, scenario, actor);

test('real Node command signature is accepted once by the actual Java verifier', async () => {
  const signer = createEvaluationBusinessSigner(scenario, generation, () => 1000);
  const req = await normalize(approve()); const proof = signer.issue('demo-manager', req, 'evaluation-test');
  assert.deepEqual(java([signer.publicKeyBase64, generation], [wire(proof, req), wire(proof, req)]), ['demo-manager', 'DENIED']);
});
for (const [field, changed] of Object.entries({ method: 'GET', target: `/api/approval/tasks/${taskId.replace(/1$/u, '2')}/approve`,
  contentType: 'text/plain', bodySha256: '0'.repeat(64), idempotencyKey: 'different-key', requestId: 'different-request' })) {
  test(`the signature rejects a changed ${field}`, async () => {
    const signer = createEvaluationBusinessSigner(scenario, generation, () => 1000); const req = await normalize(approve());
    const proof = signer.issue('demo-manager', req, 'evaluation-test');
    assert.deepEqual(java([signer.publicKeyBase64, generation], [wire(proof, req, { [field]: changed })]), ['DENIED']);
  });
}
test('proof cannot be used in a replacement or another evaluator environment', async () => {
  const a = createEvaluationBusinessSigner(scenario, generation, () => 1000);
  const b = createEvaluationBusinessSigner(scenario, 'b'.repeat(32), () => 1000);
  const req = await normalize(read()); const proof = a.issue('demo-manager', req, 'evaluation-test');
  assert.deepEqual(java([b.publicKeyBase64, 'b'.repeat(32)], [wire(proof, req)]), ['DENIED']);
  assert.deepEqual(java([a.publicKeyBase64, 'b'.repeat(32)], [wire(proof, req)]), ['DENIED']);
});
test('unsigned, expired, future, retired and privileged signing is rejected', async () => {
  const req = await normalize(approve());
  for (const time of [1001, -1, NaN]) {
    const s = createEvaluationBusinessSigner(scenario, generation, () => time);
    if (time === 1001) assert.deepEqual(java([s.publicKeyBase64, generation], [wire(s.issue('demo-manager', req, 'evaluation-test'), req)]), ['DENIED']);
    else assert.throws(() => s.issue('demo-manager', req, 'evaluation-test'));
  }
  const s = createEvaluationBusinessSigner(scenario, generation, () => 1000);
  assert.throws(() => s.issue('demo-admin', req, 'evaluation-test'));
  s.disable(); assert.throws(() => s.issue('demo-manager', req, 'evaluation-test'));
});
test('Node and Java agree on actual product routes and reject ambiguous or administrative targets', () => {
  const good = [['GET', '/api/approval/tasks/pending'], ['GET', '/api/approval/tasks/pending?limit=20&offset=0'],
    ['GET', `/api/approval/attachments/${taskId}/content`], ['GET', `/api/approval/instances/${taskId}/form-snapshot`],
    ['POST', `/api/approval/tasks/${taskId}/approve`], ['POST', '/api/approval/forms/purchase-payment/versions/1/submissions'],
    ['POST', '/api/approval/attachments']];
  const bad = [['POST', '/api/approval/instances/purchase-payment'], ['GET', '/api/approval/tasks/pending?limit=21'], ['GET', '/api/approval/tasks/pending?limit=20&limit=20'],
    ['GET', '/api/approval/tasks/pending?offset=-1'], ['GET', '/api/approval/tasks/pending?keyword=%ZZ'],
    ['GET', '/api/approval/tasks/pending?x=1'], ['GET', '/api/approval/tasks/pending?limit=20&'],
    ['GET', '/api/approval/tasks/pending?keyword'], ['POST', '/api/approval/definitions/purchase-payment/publish'],
    ['POST', '/payment-sandbox/v1/events'], ['GET', 'https://wrong.invalid'], ['GET', '/api/approval/../admin'],
    ['GET', '/api/approval/tasks%2fpending'], ['GET', '/api/approval//tasks/pending'], ['GET', '/api/approval/tasks/pending;bad'],
    ['POST', `/api/approval/tasks/${taskId}/transfer`], ['DELETE', '/api/approval/tasks/pending']];
  for (const [method, path] of good) assert.doesNotThrow(() => evaluationBusinessRoute(method, path));
  for (const [method, path] of bad) assert.throws(() => evaluationBusinessRoute(method, path));
  const actual = java(['route'], [...good, ...bad].map(pair => pair.join('\t')));
  assert.equal(actual.slice(0, good.length).includes('DENIED'), false);
  assert.ok(actual.slice(good.length).every(value => value === 'DENIED'));
});
test('multipart uses the real parser and Java agrees on the canonical file fingerprint', async () => {
  const form = new FormData(); const bytes = Buffer.from('%PDF-1.4\nfixture\n%%EOF');
  form.append('file', new Blob([bytes], { type: 'application/pdf' }), 'invoice.pdf');
  const response = new Response(form); const req = await normalizeEvaluationBusinessRequest({ method: 'POST',
    target: '/api/approval/attachments', contentType: response.headers.get('content-type'),
    idempotencyKey: 'upload-1', body: Buffer.from(await response.arrayBuffer()) }, scenario, 'demo-employee');
  assert.equal(req.bodySha256, evaluationUploadDigest('invoice.pdf', 'application/pdf', bytes));
  assert.deepEqual(java(['upload', 'invoice.pdf', 'application/pdf', bytes.toString('base64')]), [req.bodySha256]);
  assert.throws(() => evaluationUploadDigest('invoice.svg', 'image/svg+xml', bytes));
  assert.throws(() => evaluationUploadDigest('../invoice.pdf', 'application/pdf', bytes));
  assert.throws(() => evaluationUploadDigest('invoice.pdf', 'application/pdf', Buffer.from('not pdf')));
});
test('extra multipart parts, wrong actors, oversized data and administrative start rules are denied', async () => {
  const form = new FormData(); form.append('file', new Blob(['text'], { type: 'text/plain' }), 'data.txt'); form.append('tenantId', 'other');
  const response = new Response(form);
  await assert.rejects(normalizeEvaluationBusinessRequest({ method: 'POST', target: '/api/approval/attachments',
    contentType: response.headers.get('content-type'), idempotencyKey: 'upload-1', body: Buffer.from(await response.arrayBuffer()) }, scenario, 'demo-employee'));
  await assert.rejects(normalize({ ...approve(), body: Buffer.alloc(65_537) }));
  const start = { businessKey: scenario.request.businessKey, values: { amount: Number(scenario.request.amount), supplier: scenario.request.supplier, purchaseOrderReference: scenario.request.purchaseOrderReference, attachments: [taskId] }, startParameters: structuredClone(scenario.assigneeRules) };
  const req = { ...approve(), target: '/api/approval/forms/purchase-payment/versions/1/submissions', body: Buffer.from(JSON.stringify(start)) };
  await assert.rejects(normalize(req));
  await assert.doesNotReject(normalize(req, 'demo-employee'));
  start.startParameters.initiatorUserId.value = 'demo-admin';
  await assert.rejects(normalize({ ...req, body: Buffer.from(JSON.stringify(start)) }, 'demo-employee'));
});
function setup(send) {
  const controller = createEvaluationSessions({ scenario, slotIds: ['slot-a', 'slot-b'],
    resetSlot: async ({ slotId, resetNonce }) => ({ slotId, resetNonce, generation: Buffer.from(resetNonce, 'base64url').toString('hex').slice(0, 32), clean: true }), dispatchBusiness: send });
  const enter = () => controller.redeem(controller.issueInvitation().invitation);
  return { controller, enter };
}
const ok = () => ({ status: 200, headers: { 'content-type': 'application/json' }, body: Buffer.from('{}') });
const tick = () => new Promise(done => setImmediate(done));
test('two sessions dispatch only to their own server-selected slot and actor', async t => {
  const calls = []; const f = setup(async call => { calls.push(call); return ok(); }); t.after(() => f.controller.disable());
  await f.controller.reset('slot-a'); await f.controller.reset('slot-b');
  const a = f.enter(); const b = f.enter();
  await f.controller.dispatchBusiness(a.token, a.session.csrfToken, read());
  await f.controller.dispatchBusiness(b.token, b.session.csrfToken, read());
  assert.deepEqual(calls.map(c => [c.slotId, c.actorId]), [['slot-a', 'demo-employee'], ['slot-b', 'demo-employee']]);
  await assert.rejects(f.controller.dispatchBusiness(a.token, a.session.csrfToken, { ...read(), slotId: 'slot-b' }));
  assert.equal(calls.length, 2);
});
test('a pending command prevents role rotation and duplicate commands until actual completion', async t => {
  let done; const f = setup(() => new Promise(resolve => { done = resolve; })); t.after(() => f.controller.disable());
  await f.controller.reset('slot-a'); const a = f.enter();
  const pending = f.controller.dispatchBusiness(a.token, a.session.csrfToken, approve()); await tick();
  assert.throws(() => f.controller.changeActor(a.token, a.session.csrfToken, 'demo-manager'), /WRITE_IN_PROGRESS/u);
  await assert.rejects(f.controller.dispatchBusiness(a.token, a.session.csrfToken, approve()), /WRITE_IN_PROGRESS/u);
  done(ok()); await pending;
  const rotated = f.controller.changeActor(a.token, a.session.csrfToken, 'demo-manager');
  assert.notEqual(rotated.token, a.token);
});
test('an uncertain command remains blocked even if the transport promise settles; verified reset releases it', async t => {
  const f = setup(async () => { throw new Error('do not expose diagnostic'); }); t.after(() => f.controller.disable());
  await f.controller.reset('slot-a'); const a = f.enter();
  await assert.rejects(f.controller.dispatchBusiness(a.token, a.session.csrfToken, approve()), /BUSINESS_REQUEST_FAILED/u);
  assert.throws(() => f.controller.changeActor(a.token, a.session.csrfToken, 'demo-manager'), /RESET_REQUIRED/u);
  await assert.rejects(f.controller.dispatchBusiness(a.token, a.session.csrfToken, approve()), /RESET_REQUIRED/u);
  await f.controller.end(a.token, a.session.csrfToken);
  assert.throws(() => f.controller.status(a.token), /SESSION_REQUIRED/u);
  assert.equal(f.enter().session.businessAccess, 'PURCHASE_PAYMENT_WORKFLOW');
});
test('reset fences late responses without treating cancellation as transaction rollback', async t => {
  let done; const f = setup(() => new Promise(resolve => { done = resolve; })); t.after(() => f.controller.disable());
  await f.controller.reset('slot-a'); const a = f.enter();
  const pending = f.controller.dispatchBusiness(a.token, a.session.csrfToken, approve());
  const failure = assert.rejects(pending, /UNCERTAIN|REVOKED|SESSION_REQUIRED/u);
  await tick(); await f.controller.end(a.token, a.session.csrfToken);
  const b = f.enter(); done(ok()); await failure; await tick();
  assert.doesNotThrow(() => f.controller.status(b.token));
});

test('real verified HTTPS carries the opaque cookie and CSRF into command dispatch, never client authority', async t => {
  const { createEvaluationHttpsServer } = await import('../product-readiness/online-demo/evaluation-http.mjs');
  const { once } = await import('node:events');
  const { request: httpsRequest } = await import('node:https');
  const tls = mkdtempSync(resolve(tmpdir(), 'business-tls-'));
  t.after(() => rmSync(tls, { recursive: true, force: true }));
  const generated = spawnSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes', '-days', '1',
    '-keyout', resolve(tls, 'key.pem'), '-out', resolve(tls, 'cert.pem'), '-subj', '/CN=evaluation.example.invalid',
    '-addext', 'subjectAltName=DNS:evaluation.example.invalid'], { timeout: 10_000, encoding: 'utf8' });
  assert.equal(generated.status, 0, generated.stderr);
  const calls = []; const f = setup(async call => { calls.push(call); return ok(); });
  await f.controller.reset('slot-a'); await f.controller.reset('slot-b');
  const cert = readFileSync(resolve(tls, 'cert.pem'));
  const server = createEvaluationHttpsServer({ controller: f.controller, key: readFileSync(resolve(tls, 'key.pem')),
    cert, origin: 'https://evaluation.example.invalid' });
  t.after(async () => { f.controller.disable(); server.closeAllConnections(); await new Promise(done => server.close(done)); });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  function request(path, body, extra = {}) {
    const bytes = Buffer.from(body === undefined ? '' : JSON.stringify(body));
    return new Promise((resolveRequest, rejectRequest) => {
      const request = httpsRequest({ hostname: '127.0.0.1', port: server.address().port,
        servername: 'evaluation.example.invalid', ca: cert, agent: false, method: body === undefined ? 'GET' : 'POST', path,
        headers: { Host: 'evaluation.example.invalid', Origin: 'https://evaluation.example.invalid',
          ...(body === undefined ? {} : { 'Content-Type': 'application/json', 'Content-Length': bytes.length }), ...extra } }, response => {
        const parts = []; response.on('data', p => parts.push(p)); response.on('error', rejectRequest);
        response.on('end', () => resolveRequest({ status: response.statusCode, headers: response.headers, body: Buffer.concat(parts).toString() }));
      }); request.on('error', rejectRequest); request.end(bytes);
    });
  }
  const enter = async () => {
    const answer = await request('/evaluation/invitations/redeem', f.controller.issueInvitation());
    assert.equal(answer.status, 400); // Internal invitation metadata must not silently pass exact-body validation.
    const grant = f.controller.issueInvitation();
    const accepted = await request('/evaluation/invitations/redeem', { invitation: grant.invitation });
    assert.equal(accepted.status, 201); assert.match(accepted.headers['set-cookie'][0], /Secure; HttpOnly; SameSite=Strict/u);
    return { Cookie: accepted.headers['set-cookie'][0].split(';')[0], 'X-Evaluation-CSRF': JSON.parse(accepted.body).csrfToken,
      'Idempotency-Key': 'https-command-1' };
  };
  const a = await enter(); const b = await enter();
  const path = `/api/approval/tasks/${taskId}/approve`;
  assert.equal((await request(path, { comment: '同意' }, a)).status, 200);
  assert.equal((await request(path, { comment: '同意' }, b)).status, 200);
  assert.deepEqual(calls.map(c => c.slotId), ['slot-a', 'slot-b']);
  assert.equal(calls[0].request.body.toString(), '{"comment":"同意"}');
  for (const headers of [{ ...a, 'X-Operator-Id': 'demo-admin' }, { ...a, 'X-Evaluation-Business-Ticket': 'forged' },
    { ...a, Origin: 'https://cross-site.invalid' }, { ...a, 'X-Evaluation-CSRF': 'wrong' }]) {
    assert.ok([400, 401, 403].includes((await request(path, { comment: 'bad' }, headers)).status));
  }
  assert.equal(calls.length, 2);
});

test('actual loopback HTTP transport carries signed bytes and refuses redirect or internal exception leakage', async t => {
  const { createServer } = await import('node:http');
  const { once } = await import('node:events');
  const { fetchEvaluationBusinessResponse } = await import('../product-readiness/online-demo/evaluation-business-transport.mjs');
  let status = 200; let observed;
  const server = createServer(async (request, response) => {
    const bytes = []; for await (const part of request) bytes.push(part);
    observed = { headers: request.headers, body: Buffer.concat(bytes).toString() };
    response.statusCode = status; response.setHeader('Set-Cookie', 'must-not-forward=1');
    response.setHeader('Content-Type', 'application/json');
    if (status === 302) response.setHeader('Location', 'http://127.0.0.1:1/never-follow');
    response.end(status >= 500 ? '{"stack":"private exception"}' : '{"ok":true}');
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => { server.closeAllConnections(); await new Promise(done => server.close(done)); });
  const signer = createEvaluationBusinessSigner(scenario, generation, () => 1000);
  const request = await normalize(approve());
  const input = { method: request.method, target: request.target, contentType: request.contentType,
    idempotencyKey: request.idempotencyKey, bodyBase64: request.body.toString('base64'), requestId: 'evaluation-test',
    ticket: signer.issue('demo-manager', request, 'evaluation-test') };
  const result = await fetchEvaluationBusinessResponse(input, server.address().port);
  assert.equal(result.status, 200); assert.equal(result.headers['set-cookie'], undefined);
  assert.equal(observed.body, request.body.toString()); assert.equal(observed.headers['x-evaluation-business-ticket'], input.ticket);
  status = 500;
  const failed = await fetchEvaluationBusinessResponse(input, server.address().port);
  assert.doesNotMatch(Buffer.from(failed.bodyBase64, 'base64').toString(), /private exception|stack/u);
  status = 302; await assert.rejects(fetchEvaluationBusinessResponse(input, server.address().port));
});


test('a backend 5xx for a write locks commands and role rotation until reset', async t => {
  const f = setup(async () => ({ status: 500, headers: {}, body: Buffer.from('{}') }));
  t.after(() => f.controller.disable()); await f.controller.reset('slot-a'); const a = f.enter();
  await assert.rejects(f.controller.dispatchBusiness(a.token, a.session.csrfToken, approve()));
  assert.throws(() => f.controller.changeActor(a.token, a.session.csrfToken, 'demo-manager'), /RESET_REQUIRED/u);
});
