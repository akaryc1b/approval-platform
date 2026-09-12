import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createServer, request as httpsRequest } from 'node:https';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { runInNewContext } from 'node:vm';
import test from 'node:test';
import { createEvaluationSessions, EvaluationError, startEvaluationExpiryWorker, validEvaluationToken }
  from '../product-readiness/online-demo/evaluation-sessions.mjs';
import { evaluationBusinessRoute, maximumBusinessBody }
  from '../product-readiness/online-demo/evaluation-business-request.mjs';

const source = readFileSync(new URL('../product-readiness/online-demo/evaluation-http.mjs', import.meta.url), 'utf8');
const actors = ['demo-employee', 'demo-manager', 'demo-finance-reviewer', 'demo-finance-approver-a', 'demo-finance-approver-b'];
const scenario = { schemaVersion: 1, tenant: { id: 'demo-purchase-payment' },
  directory: { users: actors.map(id => ({ id, displayName: id, roleCodes: [] })) },
  assigneeRules: { initiatorUserId: { value: actors[0] } },
  expectedWorkflow: actors.slice(1).map(id => ({ actorIds: [id] })) };
const statusPath = '/evaluation/session';
const businessPath = '/api/approval/tasks/pending?limit=20&offset=0';
const token = () => randomBytes(32).toString('base64url');

// Execute the exact HTTP module body with static-page/asset interfaces as fixtures and
// a controlled rate clock. TLS, requests, routing and the session controller are real.
// The business transport and reset adapter are fixtures, not Docker/PostgreSQL evidence.
async function fixture(t, workflow = true) {
  const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-budget-test-'));
  const keyPath = resolve(directory, 'key.pem'); const certPath = resolve(directory, 'cert.pem');
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '1',
    '-keyout', keyPath, '-out', certPath, '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'],
  { stdio: 'ignore', timeout: 10000 });
  const calls = []; let clock = 1000; let rateTime = 0;
  const controller = createEvaluationSessions({ scenario, slotIds: ['slot-a', 'slot-b'],
    clock: () => clock, sessionTtlMs: 1_800_000,
    resetSlot: async ({ slotId, resetNonce }) => ({ slotId, resetNonce, generation: randomBytes(16).toString('hex'), clean: true }),
    ...(workflow ? { dispatchBusiness: async input => {
      calls.push({ slotId: input.slotId, generation: input.generation, actorId: input.actorId });
      return { status: 200, headers: { 'content-type': 'application/json' }, body: Buffer.from('{"items":[]}') };
    } } : {}),
  });
  await controller.reset('slot-a'); await controller.reset('slot-b');
  const enter = () => controller.redeem(controller.issueInvitation().invitation);
  const a = enter(); const b = enter();
  const context = { Buffer, URL, AbortController, setTimeout, clearTimeout, createServer,
    performance: { now: () => rateTime }, EvaluationError, startEvaluationExpiryWorker, validEvaluationToken,
    evaluationBusinessRoute, maximumBusinessBody,
    isEvaluationApplicationAssets: () => false, evaluationAsset: () => null,
    evaluationCsp: "default-src 'none'", evaluationPage: '<!doctype html><title>fixture</title>' };
  const module = source.replace(/^import .* from '[^']+';\n/gmu, '').replace(/^export /gmu, '');
  runInNewContext(module + '\nglobalThis.factory = createEvaluationRequestHandler; globalThis.cookieName = evaluationCookie;', context);
  let handler;
  const cert = readFileSync(certPath);
  const server = createServer({ key: readFileSync(keyPath), cert, minVersion: 'TLSv1.2' }, (req, res) => handler(req, res));
  await new Promise((done, fail) => { server.once('error', fail); server.listen(0, '127.0.0.1', done); });
  const origin = 'https://localhost:' + server.address().port;
  handler = context.factory({ controller, origin });
  t.after(async () => { controller.disable(); server.closeAllConnections(); await new Promise(done => server.close(done));
    rmSync(directory, { recursive: true, force: true }); });
  async function call(session, path = statusPath, method = 'GET', body, headers = {}) {
    const bytes = body === undefined ? Buffer.alloc(0) : Buffer.from(JSON.stringify(body));
    return new Promise((done, fail) => {
      const req = httpsRequest(origin + path, { method, ca: cert, rejectUnauthorized: true, agent: false,
        lookup: (_host, options, callback) => options.all ? callback(null, [{ address: '127.0.0.1', family: 4 }]) : callback(null, '127.0.0.1', 4),
        headers: { Origin: origin, 'Content-Length': String(bytes.length),
          ...(session ? { Cookie: context.cookieName + '=' + session.token } : {}),
          ...(session?.session.csrfToken ? { 'X-Evaluation-CSRF': session.session.csrfToken } : {}),
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers } }, res => {
        const chunks = []; res.on('data', part => chunks.push(part)); res.once('error', fail);
        res.once('end', () => done({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString('utf8') }));
      });
      req.once('error', fail); req.setTimeout(3000, () => req.destroy(new Error('test request timeout'))); req.end(bytes);
    });
  }
  return { controller, a, b, call, calls, enter, origin,
    advanceRate: ms => { rateTime += ms; }, expire: () => { clock += 1_800_000; } };
}
const expect = async (work, status) => { const response = await work; assert.equal(response.status, status); return response; };

test('two workflow sessions retain pre/post verification without spending the business request budget', async t => {
  const f = await fixture(t);
  for (const session of [f.a, f.b]) for (let i = 0; i < 70; i++) {
    await expect(f.call(session), 200);
    await expect(f.call(session, businessPath), 200);
    await expect(f.call(session), 200);
  }
  assert.equal(f.calls.length, 140);
  assert.equal(f.calls.filter(item => item.slotId === 'slot-a').length, 70);
  assert.equal(f.calls.filter(item => item.slotId === 'slot-b').length, 70);
});

test('status reads remain capped per trusted slot and A cannot consume B status capacity', async t => {
  const f = await fixture(t);
  for (let i = 0; i < 240; i++) await expect(f.call(f.a), 200);
  const limited = await expect(f.call(f.a), 429); assert.equal(limited.headers['retry-after'], '60');
  assert.equal(JSON.parse(limited.body).error, 'REQUEST_LIMIT');
  await expect(f.call(f.b), 200); await expect(f.call(f.b, businessPath), 200);
  assert.equal(f.calls.length, 1);
});

test('rotating credentials or replacing a generation cannot refill the slot budget', async t => {
  const f = await fixture(t);
  for (let i = 0; i < 239; i++) await expect(f.call(f.a), 200);
  const changed = f.controller.changeActor(f.a.token, f.a.session.csrfToken, 'demo-manager');
  await expect(f.call(f.a), 401); await expect(f.call(changed), 200); await expect(f.call(changed), 429);
  const binding = f.controller.businessBinding(changed.token);
  await expect(f.call(changed, '/evaluation/session/end', 'POST', {}), 204);
  const replacement = f.enter(); assert.notEqual(f.controller.businessBinding(replacement.token).generation, binding.generation);
  await expect(f.call(changed), 401); await expect(f.call(replacement), 429); await expect(f.call(f.b), 200);
});

test('invalid random cookies stay in the original general budget, never allocating trusted slots', async t => {
  const f = await fixture(t);
  for (let i = 0; i < 240; i++) await expect(f.call({ token: token(), session: {} }), 401);
  await expect(f.call({ token: token(), session: {} }), 429);
  await expect(f.call(f.a), 200); await expect(f.call(f.b), 200);
  assert.equal(f.calls.length, 0);
});

test('business requests keep their original global cap after separate session checks', async t => {
  const f = await fixture(t);
  for (let i = 0; i < 240; i++) {
    const session = i % 2 ? f.a : f.b;
    await expect(f.call(session), 200); await expect(f.call(session, businessPath), 200);
  }
  await expect(f.call(f.a, businessPath), 429); assert.equal(f.calls.length, 240);
  await expect(f.call(f.a), 200); await expect(f.call(f.b), 200);
});

test('only the exact authenticated workflow GET gets a status budget', async t => {
  const f = await fixture(t);
  for (const path of ['/evaluation/session?x=1', '/evaluation/session/', '/evaluation/%73ession']) {
    await expect(f.call(f.a, path), 404);
  }
  await expect(f.call(f.a, statusPath, 'POST', {}), 404);
  for (let i = 0; i < 236; i++) await expect(f.call(null, '/not-found'), 404);
  await expect(f.call(f.a, '/not-found'), 429); await expect(f.call(f.a), 200);
});

test('authenticated status budget does not bypass origin, body, duplicate-cookie or asserted-identity checks', async t => {
  const f = await fixture(t);
  await expect(f.call(f.a, statusPath, 'GET', undefined, { Origin: 'https://other.invalid' }), 403);
  await expect(f.call(f.a, statusPath, 'GET', undefined, { 'X-Tenant-Id': 'other' }), 403);
  await expect(f.call(f.a, statusPath, 'GET', undefined, { 'X-Evaluation-Slot': 'slot-b' }), 403);
  await expect(f.call(f.a, statusPath, 'GET', undefined, { Cookie: '__Host-approval-evaluation=' + f.a.token + '; __Host-approval-evaluation=' + f.b.token }), 400);
  await expect(f.call(f.a, statusPath, 'GET', { injected: true }), 400);
  await expect(f.call(f.a), 200); assert.equal(f.calls.length, 0);
});

test('expiry revokes the status allowance and the response cannot expose private binding fields', async t => {
  const f = await fixture(t);
  const first = await expect(f.call(f.a), 200);
  assert.deepEqual(Object.keys(JSON.parse(first.body)).sort(), ['actorId', 'actors', 'businessAccess', 'csrfToken', 'expiresInSeconds', 'scope']);
  f.expire(); await expect(f.call(f.a), 401); assert.equal(f.calls.length, 0);
});

test('original control-only mode remains capped at 120 total requests', async t => {
  const f = await fixture(t, false);
  for (let i = 0; i < 120; i++) await expect(f.call(f.a), 200);
  await expect(f.call(f.a), 429); await expect(f.call(f.b), 429);
});

test('the monotonic window resets both slot allowances and the original general counter', async t => {
  const f = await fixture(t);
  for (let i = 0; i < 240; i++) { await expect(f.call(f.a), 200); await expect(f.call(null, '/not-found'), 404); }
  await expect(f.call(f.a), 429); await expect(f.call(null, '/not-found'), 429);
  f.advanceRate(59999); await expect(f.call(f.a), 429);
  f.advanceRate(1); await expect(f.call(f.a), 200); await expect(f.call(null, '/not-found'), 404);
});
