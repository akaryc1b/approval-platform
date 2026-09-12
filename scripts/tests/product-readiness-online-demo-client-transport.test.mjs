import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { stripTypeScriptTypes } from 'node:module';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const root = resolve(import.meta.dirname, '../..');
const sources = { pc: 'apps/web/overlay/apps/web-ele/src', h5: 'apps/mobile/overlay/src' };
const temporary = mkdtempSync(resolve(tmpdir(), 'evaluation-browser-transport-'));
after(() => rmSync(temporary, { recursive: true, force: true }));
let sequence = 0;
const ids = ['demo-employee', 'demo-manager', 'demo-finance-reviewer', 'demo-finance-approver-a', 'demo-finance-approver-b'];
const payload = (overrides = {}) => ({ actorId: ids[0], actors: ids.map(id => ({ id, displayName: id })),
  csrfToken: 'a'.repeat(43), expiresInSeconds: 60,
  businessAccess: 'PURCHASE_PAYMENT_WORKFLOW', scope: 'DISPOSABLE_EVALUATION_CONTROL_PLANE', ...overrides });
const response = (body, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers });

// This is the bundler's env/alias substitution plus Node's native type erasure,
// not a reimplementation of either TypeScript client. No npm/network dependency.
async function compile(kind = 'pc', env = { VITE_APPROVAL_ONLINE_EVALUATION: 'true' }) {
  const directory = resolve(temporary, String(++sequence)); mkdirSync(directory);
  for (const name of ['evaluation-session', 'runtime', 'transport', ...(kind === 'h5' ? ['evaluation-attachments', 'comments'] : [])]) {
    const part = ['transport', 'comments'].includes(name) ? 'api/approval/' : 'platform/approval/';
    let source = readFileSync(resolve(root, sources[kind], part + name + '.ts'), 'utf8');
    source = source.replaceAll('import.meta.env', '(' + JSON.stringify(env) + ')')
      .replace(/['"](?:#|@)\/platform\/approval\/runtime['"]/gu, "'./runtime.mjs'")
      .replace(/['"](?:#|@)\/platform\/approval\/evaluation-session['"]/gu, "'./evaluation-session.mjs'")
      .replace(/['"]@\/api\/approval\/transport['"]/gu, "'./transport.mjs'")
      .replace(/['"]@\/platform\/approval\/evaluation-attachments['"]/gu, "'./evaluation-attachments.mjs'")
      .replace("'./evaluation-session'", "'./evaluation-session.mjs'")
      .replace("'./local-demo'", "'./local-demo.mjs'");
    writeFileSync(resolve(directory, name + '.mjs'), stripTypeScriptTypes(source));
  }
  // The unrelated development identity adapter is a test boundary, not hosted auth.
  writeFileSync(resolve(directory, 'local-demo.mjs'), `export const approvalLocalDemoEnabled=()=>${env.VITE_APPROVAL_LOCAL_DEMO === 'true'};
export const requireApprovalLocalDemoTenant=v=>v;
export const resolveApprovalLocalDemoOperatorId=v=>v;
`);
  return { ...(kind === 'h5' ? { attachments: await import(pathToFileURL(resolve(directory, 'evaluation-attachments.mjs'))),
      comments: await import(pathToFileURL(resolve(directory, 'comments.mjs'))) } : {}),
    client: await import(pathToFileURL(resolve(directory, 'evaluation-session.mjs'))),
    transport: await import(pathToFileURL(resolve(directory, 'transport.mjs'))),
    runtime: await import(pathToFileURL(resolve(directory, 'runtime.mjs'))) };
}
function fixture(module, t, options = {}) {
  let time = 1000; let session = payload(); let behavior = async () => response({ items: [], total: 0 });
  const calls = []; const invalidated = [];
  const client = module.createEvaluationBrowserSession({ origin: 'https://evaluation.example.invalid',
    now: () => time, onInvalidated: code => invalidated.push(code), ...options,
    fetch: async (path, init) => {
      calls.push({ path, init });
      if (path === '/evaluation/session') return response(session);
      return behavior(path, init);
    } });
  t.after(() => client.dispose());
  return { client, calls, invalidated, setSession: value => { session = value; },
    setBehavior: value => { behavior = value; }, advance: value => { time += value; } };
}
const module = (await compile()).client;
const flush = () => new Promise(done => setImmediate(done));
const approval = () => ({ method: 'POST', headers: { 'Idempotency-Key': 'test-approve' }, body: '{"comment":null}' });
const path = '/approval/tasks/11111111-1111-4111-8111-111111111111/approve';

test('PC and H5 ship identical session protocol logic rather than divergent cookie rules', () => {
  assert.equal(readFileSync(resolve(root, sources.pc, 'platform/approval/evaluation-session.ts'), 'utf8'),
    readFileSync(resolve(root, sources.h5, 'platform/approval/evaluation-session.ts'), 'utf8'));
});
test('initialization is single-flight and public metadata never exposes CSRF or routing credentials', async t => {
  const f = fixture(module, t);
  await Promise.all([f.client.initialize(), f.client.initialize()]);
  assert.equal(f.calls.length, 1);
  assert.deepEqual(Object.keys(f.client.view()).sort(), ['actorId', 'actors', 'expiresInSeconds']);
  const view = f.client.view(); view.actors[0].id = 'demo-admin';
  assert.equal(f.client.view().actors[0].id, 'demo-employee');
});
test('metadata read before initialization rejects without making later initialization impossible', async t => {
  const f = fixture(module, t);
  assert.throws(() => f.client.view(), /试用会话/u);
  await f.client.initialize(); assert.equal(f.client.view().actorId, 'demo-employee');
});
test('business reads include same-origin cookies/CSRF, with live-session checks before and after the buffered body', async t => {
  const f = fixture(module, t);
  const result = await f.client.fetch('/approval/tasks/pending?limit=10&offset=0');
  assert.deepEqual(await result.json(), { items: [], total: 0 });
  assert.deepEqual(f.calls.map(c => c.path), ['/evaluation/session', '/evaluation/session',
    '/api/approval/tasks/pending?limit=10&offset=0', '/evaluation/session']);
  const init = f.calls[2].init;
  assert.equal(init.headers.get('X-Evaluation-CSRF'), 'a'.repeat(43));
  assert.equal(init.credentials, 'same-origin'); assert.equal(init.mode, 'same-origin');
  assert.equal(init.redirect, 'error'); assert.equal(init.cache, 'no-store');
  assert.equal(init.headers.has('X-Tenant-Id'), false); assert.equal(init.headers.has('X-Operator-Id'), false);
});
for (const target of ['https://outside.invalid/api/approval/tasks/pending', '//outside.invalid/approval',
  '/approval/../actuator/env', '/approval/%2e%2e/env', '/approval/tasks/pending#fragment', '/approval/\\outside']) {
  test(`untrusted destination is rejected before any fetch: ${target}`, async t => {
    const f = fixture(module, t); await assert.rejects(f.client.fetch(target)); assert.equal(f.calls.length, 0);
  });
}
for (const header of ['Authorization', 'Cookie', 'X-Tenant-Id', 'X-Operator-Id', 'X-Evaluation-CSRF',
  'X-Evaluation-Read-Ticket', 'X-Evaluation-Business-Ticket', 'X-Evaluation-Slot']) {
  test(`client cannot inject identity or destination headers: ${header}`, async t => {
    const f = fixture(module, t);
    await assert.rejects(f.client.fetch('/approval/tasks/pending', { headers: { [header]: 'untrusted' } }));
    assert.equal(f.calls.length, 0);
  });
}
for (const changes of [{ businessAccess: 'SIGNED_PENDING_READ' }, { businessAccess: 'NOT_CONNECTED' },
  { actorId: 'demo-admin' }, { csrfToken: 'bad' }, { expiresInSeconds: 1801 }, { generation: 'not-public' }]) {
  test(`invalid or read-only session cannot initialize workflow requests: ${Object.keys(changes)[0]}=${Object.values(changes)[0]}`, async t => {
    const f = fixture(module, t); f.setSession(payload(changes));
    await assert.rejects(f.client.fetch('/approval/tasks/pending'));
    assert.equal(f.calls.some(c => c.path.startsWith('/api')), false);
  });
}
test('unchanged polling cannot extend the first observed deadline', async t => {
  const f = fixture(module, t); await f.client.initialize();
  f.advance(58_000); await f.client.fetch('/approval/tasks/pending');
  f.advance(1000); const count = f.calls.length;
  await assert.rejects(f.client.fetch('/approval/tasks/pending'));
  assert.equal(f.calls.length, count); assert.equal(f.invalidated.length, 1);
});
test('a different cookie role or replacement session is not silently adopted by an old page', async t => {
  const f = fixture(module, t); await f.client.initialize();
  f.setSession(payload({ actorId: 'demo-manager', csrfToken: 'b'.repeat(43) }));
  await assert.rejects(f.client.fetch(path, approval()), e => e.code === 'EVALUATION_SESSION_CHANGED');
  assert.equal(f.calls.some(c => c.path.startsWith('/api')), false);
});
test('role change while a response body is loading prevents that response reaching the old page', async t => {
  const f = fixture(module, t); let finish; let started;
  const began = new Promise(done => { started = done; });
  f.setBehavior(async () => { started(); await new Promise(done => { finish = done; }); return response({ secret: 'old-role' }); });
  const pending = f.client.fetch('/approval/tasks/pending'); await began;
  f.setSession(payload({ csrfToken: 'b'.repeat(43) })); finish();
  await assert.rejects(pending, e => e.code === 'EVALUATION_SESSION_CHANGED');
});
test('parallel workbench reads are bounded and serialized below the server concurrency ceiling', async t => {
  const f = fixture(module, t); let active = 0; let maximum = 0;
  f.setBehavior(async () => { active += 1; maximum = Math.max(maximum, active); await flush(); active -= 1; return response([]); });
  await Promise.all(['/tasks/pending', '/tasks/processed', '/instances/started'].map(p => f.client.fetch('/approval' + p)));
  assert.equal(maximum, 1);
});
test('duplicate queued writes reject rather than replaying an approval, and preserve its idempotency key/body', async t => {
  const f = fixture(module, t); let finish; let started;
  const began = new Promise(done => { started = done; });
  f.setBehavior(async () => { started(); await new Promise(done => { finish = done; }); return response({ ok: true }); });
  const first = f.client.fetch(path, approval()); await began;
  await assert.rejects(f.client.fetch(path, approval()), e => e.status === 409);
  const sent = f.calls.find(c => c.path.startsWith('/api')).init;
  assert.equal(sent.headers.get('Idempotency-Key'), 'test-approve'); assert.equal(sent.body, '{"comment":null}');
  finish(); await first; assert.equal(f.calls.filter(c => c.path.startsWith('/api')).length, 1);
});
test('multipart uploads preserve file bytes and let fetch choose the actual framing', async t => {
  const f = fixture(module, t); const form = new FormData();
  form.append('file', new File(['invoice'], 'invoice.txt', { type: 'text/plain' }));
  await f.client.fetch('/approval/attachments', { method: 'POST', body: form,
    headers: { 'Idempotency-Key': 'upload-1', 'Content-Type': 'application/json' } });
  const sent = f.calls.find(c => c.path.startsWith('/api')).init;
  assert.equal(sent.headers.has('Content-Type'), false);
  assert.equal(await sent.body.get('file').text(), 'invoice');
});
test('an uncertain mutation is non-retryable and locks the page even if a later server read looks healthy', async t => {
  const f = fixture(module, t); f.setBehavior(async () => { throw new Error('connection lost'); });
  await assert.rejects(f.client.fetch(path, approval()), e => e.code === 'EVALUATION_RESULT_UNCERTAIN' && !e.retryable);
  const count = f.calls.length;
  await assert.rejects(f.client.fetch(path, approval())); assert.equal(f.calls.length, count);
});
test('backend 5xx cannot be turned into an apparently successful retriable write', async t => {
  const f = fixture(module, t); f.setBehavior(async () => response({ error: 'failed' }, 500));
  await assert.rejects(f.client.fetch(path, approval()), e => e.code === 'EVALUATION_RESULT_UNCERTAIN' && e.status === 503);
});
test('caller cancellation before dispatch does not send or mark an unattempted mutation as uncertain', async t => {
  const f = fixture(module, t); const abort = new AbortController(); abort.abort();
  await assert.rejects(f.client.fetch(path, { ...approval(), signal: abort.signal }), e => e.code === 'EVALUATION_REQUEST_CANCELLED');
  assert.equal(f.calls.some(c => c.path.startsWith('/api')), false);
});
test('response streaming cannot keep a timed-out write alive forever or return a partial success', async t => {
  const f = fixture(module, t, { requestTimeoutMs: 25 }); let cancelled = false;
  f.setBehavior(async () => new Response(new ReadableStream({ cancel() { cancelled = true; } })));
  await assert.rejects(f.client.fetch(path, approval()), e => e.code === 'EVALUATION_RESULT_UNCERTAIN');
  await flush(); assert.equal(cancelled, true);
});
test('clock regression disables the page instead of extending session ownership', async t => {
  const f = fixture(module, t); await f.client.initialize(); f.advance(-1);
  await assert.rejects(f.client.fetch('/approval/tasks/pending'), e => e.code === 'EVALUATION_CLOCK_INVALID');
});

for (const kind of ['pc', 'h5']) {
  test(`${kind} existing transport actually enters the session path and ignores baked admin metadata`, async t => {
    const loaded = await compile(kind, { VITE_APPROVAL_ONLINE_EVALUATION: 'true',
      VITE_APPROVAL_TENANT_ID: 'untrusted', VITE_APPROVAL_OPERATOR_ID: 'demo-admin', VITE_APPROVAL_API_URL: 'https://outside.invalid' });
    const old = globalThis.window; const calls = [];
    globalThis.window = { location: { origin: 'https://evaluation.example.invalid', replace() {} },
      fetch: async (target, init) => { calls.push({ target, init }); return target === '/evaluation/session' ? response(payload()) : response({ items: [] }); } };
    t.after(() => { if (old === undefined) delete globalThis.window; else globalThis.window = old; });
    const data = kind === 'pc' ? await loaded.transport.approvalRequest('/approval/tasks/pending')
      : await loaded.transport.mobileApprovalRequest('/approval/tasks/pending');
    assert.deepEqual(data, { items: [] });
    const sent = calls.find(c => c.target.startsWith('/api'));
    assert.equal(sent.target, '/api/approval/tasks/pending'); assert.equal(sent.init.headers.has('X-Operator-Id'), false);
    assert.equal(loaded.runtime.getApprovalRuntimeConfig().operatorId, 'demo-employee');
    assert.equal(loaded.runtime.getApprovalRuntimeConfig().localDemo, false);
  });
  test(`${kind} existing error contract preserves uncertain-result semantics without automatic retry`, async t => {
    const loaded = await compile(kind); const old = globalThis.window; let writes = 0;
    globalThis.window = { location: { origin: 'https://evaluation.example.invalid', replace() {} },
      fetch: async (target) => { if (target === '/evaluation/session') return response(payload()); writes += 1; throw new Error('lost response'); } };
    t.after(() => { if (old === undefined) delete globalThis.window; else globalThis.window = old; });
    const request = () => kind === 'pc' ? loaded.transport.approvalRequest(path, approval())
      : loaded.transport.mobileApprovalRequest(path, { method: 'POST', header: { 'Idempotency-Key': 'test' }, data: { comment: null } });
    await assert.rejects(request(), e => e.code === 'EVALUATION_RESULT_UNCERTAIN' && e.retryable === false && e.status === 503);
    await assert.rejects(request()); assert.equal(writes, 1);
  });
}

test('non-evaluation PC transport keeps its existing principal-authenticated path', async t => {
  const loaded = await compile('pc', { VITE_APPROVAL_API_URL: '/api', VITE_APPROVAL_TENANT_ID: 'normal', VITE_APPROVAL_OPERATOR_ID: 'user' });
  const old = globalThis.fetch; const calls = [];
  globalThis.fetch = async (target, init) => { calls.push({ target, init }); return response({ ok: true }); };
  t.after(() => { globalThis.fetch = old; });
  await loaded.transport.approvalRequest('/approval/tasks/pending');
  assert.equal(calls.length, 1); assert.equal(calls[0].target, '/api/approval/tasks/pending');
  assert.equal(calls[0].init.headers.has('X-Evaluation-CSRF'), false);
});
test('non-evaluation mobile transport keeps uni.request and its legacy platform handling', async t => {
  const loaded = await compile('h5', { VITE_APPROVAL_API_URL: '/api', VITE_APPROVAL_TENANT_ID: 'normal', VITE_APPROVAL_OPERATOR_ID: 'user' });
  const old = globalThis.uni; const calls = [];
  globalThis.uni = { getSystemInfoSync: () => ({ uniPlatform: 'mp-weixin' }), request: options => {
    calls.push(options); options.success({ statusCode: 200, data: { ok: true }, header: {} }); } };
  t.after(() => { if (old === undefined) delete globalThis.uni; else globalThis.uni = old; });
  assert.deepEqual(await loaded.transport.mobileApprovalRequest('/approval/tasks/pending'), { ok: true });
  assert.equal(calls.length, 1); assert.equal(calls[0].header['X-Evaluation-CSRF'], undefined);
});
test('evaluation mode cannot fall back to UniApp headers when there is no browser', async t => {
  const loaded = await compile('h5'); const old = globalThis.window; delete globalThis.window;
  t.after(() => { if (old !== undefined) globalThis.window = old; });
  await assert.rejects(loaded.transport.mobileApprovalRequest('/approval/tasks/pending'), e => e.code === 'EVALUATION_BROWSER_REQUIRED');
});
test('conflicting online and local-header switches are rejected', async () => {
  const loaded = await compile('pc', { VITE_APPROVAL_ONLINE_EVALUATION: 'true', VITE_APPROVAL_LOCAL_DEMO: 'true' });
  assert.throws(() => loaded.client.approvalEvaluationEnabled(), e => e.code === 'EVALUATION_PROFILE_CONFLICT');
});

// Real TLS and the actual HTTPS/session/normalization modules below; the
// downstream approval application, persistence, containers and payment are NOT real here.
async function httpsGateway(t) {
  const { execFileSync } = await import('node:child_process');
  const { once } = await import('node:events');
  const { request } = await import('node:https');
  const { randomBytes } = await import('node:crypto');
  const { createEvaluationSessions } = await import('../product-readiness/online-demo/evaluation-sessions.mjs');
  const { createEvaluationHttpsServer } = await import('../product-readiness/online-demo/evaluation-http.mjs');
  const directory = resolve(temporary, 'tls-' + (++sequence)); mkdirSync(directory);
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes', '-days', '1',
    '-subj', '/CN=evaluation.example.invalid', '-addext', 'subjectAltName=DNS:evaluation.example.invalid',
    '-keyout', resolve(directory, 'key.pem'), '-out', resolve(directory, 'cert.pem')], { stdio: 'pipe', timeout: 10_000 });
  const ca = readFileSync(resolve(directory, 'cert.pem'));
  const scenario = { schemaVersion: 1, tenant: { id: 'demo-purchase-payment' },
    directory: { users: ids.map(id => ({ id, displayName: id, roleCodes: ['EMPLOYEE'] })) },
    assigneeRules: { initiatorUserId: { value: ids[0] } }, expectedWorkflow: [{ actorIds: ids.slice(1) }] };
  const dispatched = [];
  let respond = input => ({ status: 200, headers: { 'content-type': 'application/json' },
    body: Buffer.from(JSON.stringify({ actorId: input.actorId, matched: true })) });
  const controller = createEvaluationSessions({ scenario, slotIds: ['slot-a', 'slot-b'],
    resetSlot: async ({ slotId, resetNonce }) => ({ slotId, resetNonce, generation: randomBytes(16).toString('hex'), clean: true }),
    dispatchBusiness: async input => { dispatched.push(input); return respond(input); } });
  await controller.reset('slot-a'); await controller.reset('slot-b');
  const origin = 'https://evaluation.example.invalid';
  const server = createEvaluationHttpsServer({ controller, origin, cert: ca, key: readFileSync(resolve(directory, 'key.pem')) });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => new Promise(done => { server.closeAllConnections(); server.close(done); }));
  function browserNetwork() {
    let cookie = '';
    async function exchange(path, init = {}) {
      const encoded = new Request(new URL(path, origin), init);
      const body = encoded.body ? Buffer.from(await encoded.arrayBuffer()) : undefined;
      const headers = Object.fromEntries(encoded.headers); headers.Host = 'evaluation.example.invalid';
      if (cookie) headers.Cookie = cookie;
      if (encoded.method === 'POST') { headers.Origin = origin; headers['Content-Length'] = String(body?.length || 0); }
      return new Promise((done, fail) => {
        const req = request({ hostname: '127.0.0.1', port: server.address().port, servername: 'evaluation.example.invalid',
          ca, agent: false, path, method: encoded.method, headers, signal: init.signal, timeout: 5000 }, res => {
          const chunks = []; res.on('data', bytes => chunks.push(bytes));
          res.on('error', fail);
          res.on('end', () => {
            const setCookie = res.headers['set-cookie']?.[0];
            if (setCookie) cookie = setCookie.split(';')[0];
            const resultHeaders = new Headers();
            for (const [name, value] of Object.entries(res.headers)) if (typeof value === 'string') resultHeaders.set(name, value);
            done(new Response(res.statusCode === 204 ? null : Buffer.concat(chunks), { status: res.statusCode, headers: resultHeaders }));
          });
        });
        req.on('error', fail); req.on('timeout', () => req.destroy(new Error('test TLS timeout'))); req.end(body);
      });
    }
    async function enter() {
      const result = await exchange('/evaluation/invitations/redeem', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ invitation: controller.issueInvitation().invitation }) });
      assert.equal(result.status, 201); return result.json();
    }
    return { exchange, enter };
  }
  return { controller, server, origin, dispatched, browserNetwork, setResponse: fn => { respond = fn; } };
}

test('existing PC and H5 transports reach different real HTTPS sessions without browser identity headers', async t => {
  const f = await httpsGateway(t); const a = f.browserNetwork(); const b = f.browserNetwork();
  const sessionA = await a.enter(); await b.enter();
  const pc = await compile('pc'); const h5 = await compile('h5');
  const old = globalThis.window; t.after(() => { if (old === undefined) delete globalThis.window; else globalThis.window = old; });
  globalThis.window = { location: { origin: f.origin, replace() {} }, fetch: a.exchange };
  await pc.transport.approvalRequest('/approval/tasks/pending?limit=10&offset=0');
  globalThis.window = { location: { origin: f.origin, replace() {} }, fetch: b.exchange };
  await h5.transport.mobileApprovalRequest('/approval/tasks/pending?limit=10&offset=0');
  assert.deepEqual(f.dispatched.map(d => d.slotId), ['slot-a', 'slot-b']);
  assert.ok(f.dispatched.every(d => d.actorId === 'demo-employee'));
  const changed = await a.exchange('/evaluation/session/actor', { method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Evaluation-CSRF': sessionA.csrfToken }, body: '{"actorId":"demo-manager"}' });
  assert.equal(changed.status, 200);
  await assert.rejects(pc.transport.approvalRequest('/approval/tasks/pending'), e => e.code === 'EVALUATION_SESSION_CHANGED');
  await h5.transport.mobileApprovalRequest('/approval/tasks/pending');
  assert.deepEqual(f.dispatched.map(d => d.slotId), ['slot-a', 'slot-b', 'slot-b']);
});
test('actual HTTPS gateway receives browser-framed multipart file upload from the existing PC fetch function', async t => {
  const f = await httpsGateway(t); const network = f.browserNetwork(); await network.enter();
  const pc = await compile('pc'); const old = globalThis.window;
  globalThis.window = { location: { origin: f.origin, replace() {} }, fetch: network.exchange };
  t.after(() => { if (old === undefined) delete globalThis.window; else globalThis.window = old; });
  const form = new FormData(); form.append('file', new File(['evaluation fixture only'], 'invoice.txt', { type: 'text/plain' }));
  const result = await pc.transport.approvalFetch('/approval/attachments', { method: 'POST', body: form,
    headers: pc.transport.approvalCommandHeaders('test-upload') });
  assert.equal(result.status, 200); assert.equal(f.dispatched.length, 1);
  assert.equal(f.dispatched[0].request.route.name, 'upload');
  assert.equal(f.dispatched[0].request.ticketContentType, 'multipart/form-data');
  assert.match(f.dispatched[0].request.bodySha256, /^[0-9a-f]{64}$/u);
});

for (const [name, type, data] of [['invoice.txt', 'text/plain', 'fixture'], ['quote.pdf', 'application/pdf', '%PDF-fixture'],
  ['drawing.png', 'image/png', 'fixture'], ['photo.jpg', 'image/jpeg', 'fixture']]) {
  test(`H5 native File keeps its exact name/type/bytes in multipart: ${name}`, async () => {
    const loaded = await compile('h5'); const file = new File([data], name, { type });
    const form = loaded.attachments.evaluationAttachmentForm(file);
    assert.deepEqual([...form.keys()], ['file']); assert.equal(form.get('file').name, name);
    assert.equal(form.get('file').type, type); assert.equal(await form.get('file').text(), data);
  });
}
for (const file of ['https://other.invalid/private', 'blob:https://evaluation.example.invalid/unknown',
  new File(['x'], '../escape.txt', { type: 'text/plain' }), new File(['x'], 'a..b.txt', { type: 'text/plain' }),
  new File(['x'], 'file.html', { type: 'text/html' }), new File([], 'empty.txt', { type: 'text/plain' }),
  new File(['x'], 'wrong.pdf', { type: 'text/plain' }), new File([new Uint8Array(1_048_576)], 'large.txt', { type: 'text/plain' })]) {
  test(`H5 upload refuses non-native/invalid file before sending it: ${typeof file === 'string' ? file : file.name}`, async () => {
    const loaded = await compile('h5'); assert.throws(() => loaded.attachments.evaluationAttachmentForm(file));
  });
}
test('H5 legacy temporary-path upload cannot silently bypass the evaluation session', async t => {
  const loaded = await compile('h5'); const old = globalThis.uni;
  globalThis.uni = { uploadFile() { assert.fail('no unauthenticated fallback'); } };
  t.after(() => { if (old === undefined) delete globalThis.uni; else globalThis.uni = old; });
  await assert.rejects(loaded.comments.uploadApprovalAttachment('/temporary/invoice.txt'), e => e.code === 'EVALUATION_NATIVE_FILE_REQUIRED');
});
test('H5 existing attachment API uses verified TLS, cookie/CSRF and real multipart; persistence is substituted', async t => {
  const f = await httpsGateway(t); const network = f.browserNetwork(); await network.enter();
  const loaded = await compile('h5'); const old = globalThis.window;
  globalThis.window = { location: { origin: f.origin, replace() {} }, fetch: network.exchange };
  t.after(() => { if (old === undefined) delete globalThis.window; else globalThis.window = old; });
  const file = new File(['H5 invoice fixture'], 'invoice.txt', { type: 'text/plain' });
  f.setResponse(input => ({ status: 201, headers: { 'content-type': 'application/json' }, body: Buffer.from(JSON.stringify({
    attachmentId: '22222222-2222-4222-8222-222222222222', fileName: file.name,
    sizeBytes: file.size, contentType: file.type, uploaderId: input.actorId,
  })) }));
  const result = await loaded.comments.uploadApprovalAttachment(file);
  assert.equal(result.fileName, 'invoice.txt'); assert.equal(f.dispatched[0].slotId, 'slot-a');
  const incoming = f.dispatched[0].request;
  assert.equal(incoming.route.name, 'upload');
  const form = await new Response(incoming.body, { headers: { 'Content-Type': incoming.contentType } }).formData();
  assert.equal(await form.get('file').text(), 'H5 invoice fixture');
  f.setResponse(() => ({ status: 200, headers: { 'content-type': 'text/plain' }, body: Buffer.from('download fixture') }));
  const blob = await loaded.attachments.downloadEvaluationAttachment(result.attachmentId);
  assert.equal(await blob.text(), 'download fixture');
  f.setResponse(() => ({ status: 404, headers: { 'content-type': 'application/json' }, body: Buffer.from('{}') }));
  await assert.rejects(loaded.attachments.downloadEvaluationAttachment(result.attachmentId), e => e.status === 404);
});
test('H5 native picker cleans cancellation, rejects too many files and cannot outlive its session (DOM fixture)', async t => {
  const loaded = await compile('h5'); const oldWindow = globalThis.window; const oldDocument = globalThis.document;
  let input; let clicked = 0; let removed = 0;
  class Input extends EventTarget { files = []; click() { clicked += 1; } remove() { removed += 1; } }
  const window = new EventTarget(); Object.assign(window, {
    location: { origin: 'https://evaluation.example.invalid', replace() {} }, fetch: async () => response(payload()),
  });
  globalThis.window = window; globalThis.document = { createElement() { input = new Input(); return input; }, body: { append() {} } };
  t.after(() => { if (oldWindow === undefined) delete globalThis.window; else globalThis.window = oldWindow;
    if (oldDocument === undefined) delete globalThis.document; else globalThis.document = oldDocument; });
  await loaded.client.getEvaluationBrowserSession().initialize();
  const cancelled = loaded.attachments.chooseEvaluationAttachmentFiles(1); input.dispatchEvent(new Event('cancel'));
  assert.deepEqual(await cancelled, []); assert.equal(clicked, 1); assert.equal(removed, 1);
  const chosen = loaded.attachments.chooseEvaluationAttachmentFiles(1); const file = new File(['x'], 'a.txt', { type: 'text/plain' });
  input.files = [file]; input.dispatchEvent(new Event('change')); assert.deepEqual(await chosen, [file]);
  const tooMany = loaded.attachments.chooseEvaluationAttachmentFiles(1); input.files = [file, file]; input.dispatchEvent(new Event('change'));
  await assert.rejects(tooMany); assert.equal(removed, 3);
  const expired = loaded.attachments.chooseEvaluationAttachmentFiles(1); loaded.client.getEvaluationBrowserSession().dispose();
  input.files = [file]; input.dispatchEvent(new Event('change')); await assert.rejects(expired); assert.equal(removed, 4);
});
test('existing form button uses native File objects only in evaluation mode and keeps ordinary picker', () => {
  const source = readFileSync(resolve(root, sources.h5, 'components/approval/ApprovalFormRenderer.vue'), 'utf8');
  assert.match(source, /chooseEvaluationAttachmentFiles\(available\)/u);
  assert.match(source, /await uploadApprovalAttachment\(file\)/u);
  assert.match(source, /uni\.chooseMessageFile/u);
  assert.match(source, /await uploadApprovalAttachment\(file\.path\)/u);
  assert.match(source, /finally \{ uploading\.value = false \}/u);
});

const { withExactBackendBuildReuse } = await import('../product-readiness/purchase-payment-e2e/build-reuse.mjs');
const reuseVariable = 'APPROVAL_DEMO_CAPACITY_REUSE_BUILD';
for (const previous of [undefined, 'true', 'false']) {
  test(`CI build reuse is scoped and preserves caller opt-out: ${previous}`, async t => {
    const before = process.env[reuseVariable];
    t.after(() => { if (before === undefined) delete process.env[reuseVariable]; else process.env[reuseVariable] = before; });
    if (previous === undefined) delete process.env[reuseVariable]; else process.env[reuseVariable] = previous;
    const result = await withExactBackendBuildReuse(async () => { assert.equal(process.env[reuseVariable], previous ?? 'true'); return 42; });
    assert.equal(result, 42); assert.equal(process.env[reuseVariable], previous);
    await assert.rejects(withExactBackendBuildReuse(async () => { throw new Error('expected runtime failure'); }), /expected runtime failure/u);
    assert.equal(process.env[reuseVariable], previous);
  });
}
test('CI reuse rejects nested scopes and invalid setting without invoking another run', async t => {
  const before = process.env[reuseVariable];
  t.after(() => { if (before === undefined) delete process.env[reuseVariable]; else process.env[reuseVariable] = before; });
  process.env[reuseVariable] = 'unexpected';
  await assert.rejects(withExactBackendBuildReuse(() => assert.fail('must not execute')), /SETTING_INVALID/u);
  delete process.env[reuseVariable];
  await withExactBackendBuildReuse(async () => {
    await assert.rejects(withExactBackendBuildReuse(() => assert.fail('must not execute')), /SCOPE_INVALID/u);
  });
  assert.equal(process.env[reuseVariable], undefined);
});
test('CI entry retains both clean-data E2E executions and selects reuse only after CI scope', () => {
  const source = readFileSync(resolve(root, 'scripts/product-readiness/purchase-payment-e2e.mjs'), 'utf8');
  assert.match(source, /if \(!shouldRunInCi\(\)\) return;\s*\/\/[^\n]+\n\s*await withExactBackendBuildReuse/u);
  assert.match(source, /await execute\(true\);[\s\S]*?rmSync\(pcH5OutputDirectory,[\s\S]*?await execute\(false\);/u);
  assert.equal((source.match(/await execute\(false\)/gu) || []).length, 2);
});

// These are actual page-controller executions with a DOM fixture, not visual browser acceptance.
for (const mode of ['NOT_CONNECTED', 'SIGNED_PENDING_READ', 'PURCHASE_PAYMENT_WORKFLOW', 'UNKNOWN_MODE']) {
  test(`landing page accepts explicit modes without inventing a PC/H5 URL: ${mode}`, async t => {
    const { mountEvaluationPage } = await import('../product-readiness/online-demo/page/entry.mjs');
    class Element extends EventTarget {
      hidden = false; disabled = false; textContent = ''; value = ''; children = [];
      setAttribute() {} replaceChildren() { this.children = []; } append(v) { this.children.push(v); }
      querySelector() { return button; }
    }
    const button = new Element(); const elements = new Map();
    for (const id of ['login', 'session', 'message', 'actor', 'invitation', 'end', 'refresh', 'expiry', 'controls', 'pending', 'pending-tasks', 'business-notice']) elements.set('#' + id, new Element());
    const document = new EventTarget(); document.hidden = false;
    document.querySelector = selector => elements.get(selector);
    document.createElement = () => new Element();
    const paths = [];
    const dispose = mountEvaluationPage({ document, now: () => 1000, setInterval: () => 1, clearInterval() {},
      fetch: async path => { paths.push(path); return response(payload({ businessAccess: mode })); } });
    t.after(dispose); await flush(); await flush();
    assert.equal(elements.get('#session').hidden, mode === 'UNKNOWN_MODE');
    assert.equal(elements.get('#pending').hidden, mode !== 'SIGNED_PENDING_READ');
    assert.deepEqual(paths, ['/evaluation/session']);
    if (mode === 'PURCHASE_PAYMENT_WORKFLOW') {
      assert.match(elements.get('#business-notice').textContent, /接口已连接/u);
      assert.match(elements.get('#business-notice').textContent, /页面入口尚未提供/u);
      assert.equal(elements.get('#end').disabled, false);
    }
  });
}
