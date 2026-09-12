import assert from 'node:assert/strict';
import { randomBytes, createHash } from 'node:crypto';
import { once } from 'node:events';
import { performance } from 'node:perf_hooks';
import { createServer as httpServer, request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';
import { createEvaluationSessions, evaluationActors, startEvaluationExpiryWorker, validEvaluationToken }
  from '../product-readiness/online-demo/evaluation-sessions.mjs';
import { createEvaluationHttpsServer, createEvaluationRequestHandler }
  from '../product-readiness/online-demo/evaluation-http.mjs';
import { evaluationAsset, evaluationPage, evaluationCsp } from '../product-readiness/online-demo/evaluation-page.mjs';

const scenario = JSON.parse(readFileSync(new URL('../../config/demo/purchase-payment-golden-path.json', import.meta.url)));
const randomToken = () => randomBytes(32).toString('base64url');
const delay = ms => new Promise(done => setTimeout(done, ms));
const ack = ({ slotId, resetNonce }) => ({ slotId, resetNonce,
  generation: createHash('sha256').update(resetNonce).digest('hex').slice(0, 32), clean: true });
function fixture(t, options = {}) {
  const root = mkdtempSync(resolve(tmpdir(), 'evaluation-session-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  let time = 1000;
  const calls = [];
  // Filesystem-only reset fixture. This is NOT a PostgreSQL/Redis evaluator adapter.
  const driver = async input => {
    const path = resolve(root, input.slotId);
    rmSync(path, { recursive: true, force: true }); mkdirSync(path);
    return ack(input);
  };
  let reset = driver;
  const controller = createEvaluationSessions({ scenario, slotIds: ['slot-a', 'slot-b'],
    clock: () => time, sessionTtlMs: 1000, invitationTtlMs: 5000, resetTimeoutMs: 2000,
    resetSlot: input => { calls.push(input); return reset(input); }, ...options });
  return { root, controller, calls, driver, advance: ms => { time += ms; },
    setTime: value => { time = value; }, setDriver: value => { reset = value; },
    ready: () => Promise.all(['slot-a', 'slot-b'].map(id => controller.reset(id))),
    enter: () => controller.redeem(controller.issueInvitation().invitation) };
}

test('business actors come from the existing canonical scenario and exclude the seed administrator', () => {
  const value = evaluationActors(scenario);
  assert.equal(value.tenantId, scenario.tenant.id);
  assert.equal(value.initialActor, scenario.assigneeRules.initiatorUserId.value);
  assert.deepEqual(value.actors.map(actor => actor.id), ['demo-employee', 'demo-manager',
    'demo-finance-reviewer', 'demo-finance-approver-a', 'demo-finance-approver-b']);
  const changed = structuredClone(scenario); changed.expectedWorkflow[0].actorIds = ['demo-admin'];
  assert.throws(() => evaluationActors(changed), /PRIVILEGED/u);
  changed.expectedWorkflow[0].actorIds = ['not-in-directory'];
  assert.throws(() => evaluationActors(changed), /UNKNOWN/u);
});
for (const options of [{ slotIds: [] }, { slotIds: ['same', 'same'] }, { slotIds: ['a', 'b', 'c'] },
  { slotIds: ['../outside'] }, { resetSlot: undefined }, { sessionTtlMs: 1_800_001 },
  { invitationTtlMs: 900_001 }, { resetTimeoutMs: 60_001 }, { clock: null }]) {
  test(`rejects unbounded or incomplete configuration ${JSON.stringify(options)}`, t => {
    assert.throws(() => fixture(t, options), /INVALID_CONFIGURATION/u);
  });
}
test('every fresh controller begins quarantined and has no successful-default reset adapter', async t => {
  const f = fixture(t); const invite = f.controller.issueInvitation().invitation;
  assert.ok(f.controller.snapshot().slots.every(slot => slot.state === 'QUARANTINED'));
  assert.throws(() => f.controller.redeem(invite), /NO_EVALUATION_SLOT/u);
  await f.ready(); assert.equal(f.controller.redeem(invite).session.businessAccess, 'NOT_CONNECTED');
  const restarted = fixture(t); assert.throws(() => restarted.controller.status(invite), /SESSION_REQUIRED/u);
  assert.ok(restarted.controller.snapshot().slots.every(slot => slot.state === 'QUARANTINED'));
});
test('single-use invitation has exactly one concurrent redemption winner', async t => {
  const f = fixture(t); await f.ready();
  const invite = f.controller.issueInvitation().invitation;
  const results = await Promise.allSettled(Array.from({ length: 8 }, () => Promise.resolve().then(() => f.controller.redeem(invite))));
  assert.equal(results.filter(value => value.status === 'fulfilled').length, 1);
  assert.equal(f.controller.snapshot().metrics.admitted, 1);
});
test('two independent sessions fill the bounded pool without consuming a waiting invitation', async t => {
  const f = fixture(t); await f.ready(); const a = f.enter(); const b = f.enter();
  assert.notEqual(a.token, b.token); assert.notEqual(a.session.csrfToken, b.session.csrfToken);
  const waiting = f.controller.issueInvitation().invitation;
  assert.throws(() => f.controller.redeem(waiting), /NO_EVALUATION_SLOT/u);
  await f.controller.end(a.token, a.session.csrfToken);
  assert.equal(f.controller.redeem(waiting).session.actorId, 'demo-employee');
  assert.equal(f.controller.status(b.token).actorId, 'demo-employee');
});
test('trusted business bindings are session, actor, generation and slot fenced', async t => {
  const f = fixture(t); await f.ready(); const a = f.enter(); const b = f.enter();
  const bindingA = f.controller.businessBinding(a.token);
  const bindingB = f.controller.businessBinding(b.token);
  assert.deepEqual(Object.keys(bindingA).sort(), ['actorId', 'expiresAt', 'generation',
    'sessionRevision', 'slotId', 'tenantId']);
  assert.equal(bindingA.slotId, 'slot-a'); assert.equal(bindingB.slotId, 'slot-b');
  assert.notEqual(bindingA.generation, bindingB.generation);
  assert.equal(bindingA.tenantId, scenario.tenant.id);
  assert.equal(bindingA.actorId, 'demo-employee');
  assert.equal(Object.isFrozen(bindingA), true);

  const changed = f.controller.changeActor(a.token, a.session.csrfToken, 'demo-manager');
  assert.throws(() => f.controller.businessBinding(a.token), /SESSION_REQUIRED/u);
  const changedBinding = f.controller.businessBinding(changed.token);
  assert.equal(changedBinding.slotId, bindingA.slotId);
  assert.equal(changedBinding.generation, bindingA.generation);
  assert.equal(changedBinding.actorId, 'demo-manager');

  await f.controller.reset('slot-a');
  assert.throws(() => f.controller.businessBinding(changed.token), /SESSION_REQUIRED/u);
  assert.deepEqual(f.controller.businessBinding(b.token), bindingB);
  const replacement = f.enter();
  assert.notEqual(f.controller.businessBinding(replacement.token).generation, bindingA.generation);
});
test('one evaluator cannot change or end another session using its own CSRF proof', async t => {
  const f = fixture(t); await f.ready(); const a = f.enter(); const b = f.enter();
  assert.throws(() => f.controller.changeActor(a.token, b.session.csrfToken, 'demo-manager'), /CSRF/u);
  assert.throws(() => f.controller.end(b.token, a.session.csrfToken), /CSRF/u);
  assert.equal(f.controller.status(a.token).actorId, 'demo-employee');
  assert.equal(f.controller.status(b.token).actorId, 'demo-employee');
});
test('actor changes rotate both proofs, revoke the old token and do not extend absolute TTL', async t => {
  const f = fixture(t); await f.ready(); const first = f.enter(); f.advance(500);
  const next = f.controller.changeActor(first.token, first.session.csrfToken, 'demo-manager');
  assert.notEqual(first.token, next.token); assert.notEqual(first.session.csrfToken, next.session.csrfToken);
  assert.throws(() => f.controller.status(first.token), /SESSION_REQUIRED/u);
  assert.throws(() => f.controller.changeActor(next.token, next.session.csrfToken, 'demo-admin'), /ACTOR_REJECTED/u);
  assert.equal(f.controller.status(next.token).actorId, 'demo-manager');
  f.advance(500); assert.throws(() => f.controller.status(next.token), /SESSION_REQUIRED/u);
});
test('reset invalidates access before awaiting the adapter and preserves the other slot fixture', async t => {
  const f = fixture(t); await f.ready(); const a = f.enter(); const b = f.enter();
  writeFileSync(resolve(f.root, 'slot-a/data'), 'first-evaluator');
  writeFileSync(resolve(f.root, 'slot-b/data'), 'second-evaluator');
  let finish; f.setDriver(input => new Promise(done => { finish = () => done(f.driver(input)); }));
  const reset = f.controller.end(a.token, a.session.csrfToken); await delay(0);
  assert.throws(() => f.controller.status(a.token), /SESSION_REQUIRED/u);
  assert.equal(f.controller.status(b.token).actorId, 'demo-employee');
  finish(); await reset;
  assert.equal(existsSync(resolve(f.root, 'slot-a/data')), false);
  assert.equal(readFileSync(resolve(f.root, 'slot-b/data'), 'utf8'), 'second-evaluator');
});
for (const variant of ['false', 'wrong-slot', 'wrong-nonce', 'extra-field', 'undefined', 'throw']) {
  test(`unconfirmed reset quarantines the slot and redacts adapter errors: ${variant}`, async t => {
    const f = fixture(t); await f.ready(); const a = f.enter();
    const privateDiagnostic = randomToken();
    f.setDriver(input => {
      if (variant === 'throw') throw new Error(privateDiagnostic);
      if (variant === 'undefined') return undefined;
      const value = ack(input);
      if (variant === 'false') value.clean = false;
      if (variant === 'wrong-slot') value.slotId = 'slot-b';
      if (variant === 'wrong-nonce') value.resetNonce = randomToken();
      if (variant === 'extra-field') value.diagnostic = privateDiagnostic;
      return value;
    });
    await assert.rejects(f.controller.end(a.token, a.session.csrfToken), error => error.code === 'RESET_FAILED' && !error.message.includes(privateDiagnostic));
    assert.equal(f.controller.snapshot().slots[0].state, 'QUARANTINED');
    assert.equal(JSON.stringify(f.controller.snapshot()).includes(privateDiagnostic), false);
  });
}
test('timed-out adapter cannot release or overlap a slot even when it ignores cancellation', async t => {
  const f = fixture(t, { resetTimeoutMs: 15 }); let late; let input;
  f.setDriver(value => { input = value; return new Promise(done => { late = done; }); });
  await assert.rejects(f.controller.reset('slot-a'), /RESET_FAILED/u);
  assert.equal(input.signal.aborted, true);
  await assert.rejects(f.controller.reset('slot-a'), /RESET_IN_PROGRESS/u);
  late(ack(input)); await delay(0);
  assert.equal(f.controller.snapshot().slots[0].state, 'QUARANTINED');
  f.setDriver(f.driver); await f.controller.reset('slot-a');
  assert.equal(f.controller.snapshot().slots[0].state, 'READY');
});
test('a synchronously blocked adapter cannot bypass its elapsed-time bound', async t => {
  const f = fixture(t, { resetTimeoutMs: 10 });
  f.setDriver(input => { const until = performance.now() + 25; while (performance.now() < until) {} return ack(input); });
  await assert.rejects(f.controller.reset('slot-a'), /RESET_FAILED/u);
  assert.equal(f.controller.snapshot().slots[0].state, 'QUARANTINED');
});
test('operator reset revokes only the selected active session', async t => {
  const f = fixture(t); await f.ready(); const a = f.enter(); const b = f.enter();
  await f.controller.reset('slot-b');
  assert.throws(() => f.controller.status(b.token), /SESSION_REQUIRED/u);
  assert.equal(f.controller.status(a.token).actorId, 'demo-employee');
  await assert.rejects(f.controller.reset('unknown'), /UNKNOWN_SLOT/u);
});
test('expiry denies access before background work, resets once, and does not touch a later session', async t => {
  const f = fixture(t); await f.ready(); const a = f.enter(); f.advance(500); const b = f.enter();
  f.advance(500); assert.throws(() => f.controller.status(a.token), /SESSION_REQUIRED/u);
  assert.deepEqual(await f.controller.sweep(), { attempted: 1, failed: 0 });
  assert.deepEqual(await f.controller.sweep(), { attempted: 0, failed: 0 });
  assert.equal(f.controller.status(b.token).actorId, 'demo-employee');
});
test('scheduled sweep performs expiry reset and can be stopped', async t => {
  const f = fixture(t); await f.ready(); f.enter();
  const stop = startEvaluationExpiryWorker(f.controller, 10); t.after(stop);
  f.advance(1000);
  const deadline = performance.now() + 2000;
  while (f.controller.snapshot().metrics.expired === 0 && performance.now() < deadline) await delay(10);
  await stop();
  assert.equal(f.controller.snapshot().metrics.expired, 1);
  assert.equal(f.controller.snapshot().slots[0].state, 'READY');
  const count = f.calls.length; await delay(20); assert.equal(f.calls.length, count);
});
test('failed automatic reset is quarantined rather than retried in an unbounded loop', async t => {
  const f = fixture(t); await f.ready(); f.enter(); f.advance(1000);
  f.setDriver(() => { throw new Error('adapter failure'); });
  assert.deepEqual(await f.controller.sweep(), { attempted: 1, failed: 1 });
  assert.deepEqual(await f.controller.sweep(), { attempted: 0, failed: 0 });
  assert.equal(f.controller.snapshot().slots[0].state, 'QUARANTINED');
});
test('disabled controller fences pending reset and invalidates every credential', async t => {
  const f = fixture(t); await f.ready(); const a = f.enter(); let complete; let input;
  f.setDriver(value => { input = value; return new Promise(done => { complete = done; }); });
  const pending = f.controller.reset('slot-b'); await delay(0); f.controller.disable();
  assert.equal(input.signal.aborted, true); complete(ack(input)); await assert.rejects(pending, /RESET_FAILED/u);
  assert.throws(() => f.controller.status(a.token), /DISABLED/u);
  assert.throws(() => f.controller.issueInvitation(), /DISABLED/u);
  assert.ok(f.controller.snapshot().slots.every(slot => slot.state === 'QUARANTINED'));
});
for (const time of [999, NaN, Infinity, -1]) {
  test(`invalid monotonic clock permanently disables admission: ${time}`, async t => {
    const f = fixture(t); await f.ready(); const a = f.enter(); f.setTime(time);
    assert.throws(() => f.controller.status(a.token), /CLOCK_INVALID/u);
    f.setTime(2000); assert.throws(() => f.controller.status(a.token), /DISABLED/u);
  });
}
test('invitations expire, can be revoked and are capped without retaining bearer material in diagnostics', async t => {
  const f = fixture(t); await f.ready(); const first = f.controller.issueInvitation().invitation;
  assert.equal(f.controller.revokeInvitation(first), true);
  assert.throws(() => f.controller.redeem(first), /INVITATION_REJECTED/u);
  const invites = Array.from({ length: 16 }, () => f.controller.issueInvitation().invitation);
  assert.throws(() => f.controller.issueInvitation(), /INVITATION_LIMIT/u);
  const diagnostic = JSON.stringify(f.controller.snapshot());
  for (const value of invites) assert.equal(diagnostic.includes(value), false);
  f.advance(5000); assert.throws(() => f.controller.redeem(invites[0]), /INVITATION_REJECTED/u);
  assert.equal(f.controller.snapshot().invitations, 0);
});
test('global redemption budget is bounded without trusting browser IP headers', async t => {
  const f = fixture(t, { invitationTtlMs: 300_000 }); await f.ready();
  const invite = f.controller.issueInvitation().invitation;
  for (let index = 0; index < 30; index += 1) assert.throws(() => f.controller.redeem(randomToken()), /INVITATION_REJECTED/u);
  assert.throws(() => f.controller.redeem(invite), /INVITATION_RATE_LIMIT/u);
  f.advance(60_000); assert.equal(f.controller.redeem(invite).session.actorId, 'demo-employee');
});
test('returned status cannot mutate internal actors or extend a session', async t => {
  const f = fixture(t); await f.ready(); const a = f.enter();
  a.session.actorId = 'demo-admin'; a.session.actors[0].id = 'demo-admin'; a.session.expiresInSeconds = 999999;
  const current = f.controller.status(a.token);
  assert.equal(current.actorId, 'demo-employee'); assert.equal(current.actors[0].id, 'demo-employee');
  assert.equal(current.expiresInSeconds, 1);
  assert.equal(validEvaluationToken(a.token), true);
  for (const value of [null, '', `${a.token}=`, `${a.token}\n`, '../escape']) assert.equal(validEvaluationToken(value), false);
});

async function tlsFixture(t) {
  const f = fixture(t, { sessionTtlMs: 60_000 }); await f.ready();
  const keyPath = resolve(f.root, 'key.pem'); const certPath = resolve(f.root, 'cert.pem');
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes',
    '-keyout', keyPath, '-out', certPath, '-days', '1', '-subj', '/CN=evaluation.example.invalid',
    '-addext', 'subjectAltName=DNS:evaluation.example.invalid'], { stdio: 'pipe', timeout: 10_000 });
  const origin = 'https://evaluation.example.invalid'; const ca = readFileSync(certPath);
  const server = createEvaluationHttpsServer({ key: readFileSync(keyPath), cert: ca, controller: f.controller, origin });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => new Promise(done => { server.closeAllConnections(); server.close(done); }));
  const call = (path, { body, headers = {}, method = body === undefined ? 'GET' : 'POST', raw } = {}) => new Promise((done, fail) => {
    const payload = raw ?? (body === undefined ? undefined : JSON.stringify(body));
    const request = httpsRequest({ hostname: '127.0.0.1', port: server.address().port,
      servername: 'evaluation.example.invalid', ca, path, method, agent: false, timeout: 5000,
      headers: { Host: 'evaluation.example.invalid', ...(method === 'POST' ? { Origin: origin,
        'Content-Type': 'application/json' } : {}), ...headers } }, response => {
      let text = ''; response.on('data', bytes => { text += bytes; });
      response.on('end', () => done({ status: response.statusCode, headers: response.headers, text,
        json: () => JSON.parse(text), cookie: response.headers['set-cookie']?.[0]?.split(';')[0] }));
    });
    request.on('error', fail); request.on('timeout', () => request.destroy(new Error('test request timeout')));
    request.end(payload);
  });
  const enter = () => call('/evaluation/invitations/redeem', { body: { invitation: f.controller.issueInvitation().invitation } });
  return { ...f, server, call, enter, origin, ca };
}

test('actual certificate-verified HTTPS implements invitation entry and isolated control sessions', async t => {
  const f = await tlsFixture(t);
  const page = await f.call('/evaluation'); assert.equal(page.status, 200);
  assert.match(page.text, /非生产试用环境/u); assert.match(page.text, /业务入口尚未连接/u);
  assert.equal(page.headers['content-security-policy'], evaluationCsp);
  assert.equal(page.headers['referrer-policy'], 'no-referrer');
  assert.equal(f.server.headersTimeout, 5000); assert.equal(f.server.maxConnections, 16);
  const a = await f.enter(); const b = await f.enter();
  assert.equal(a.status, 201); assert.equal(b.status, 201); assert.notEqual(a.cookie, b.cookie);
  const fullCookie = a.headers['set-cookie'][0];
  for (const text of ['Secure', 'HttpOnly', 'SameSite=Strict', 'Path=/']) assert.ok(fullCookie.includes(text));
  assert.equal(fullCookie.includes('Domain='), false);
  assert.equal(a.text.includes(a.cookie.split('=')[1]), false);
  assert.equal(a.json().businessAccess, 'NOT_CONNECTED');
  assert.equal('slotId' in a.json(), false);
  const status = await f.call('/evaluation/session', { headers: { Cookie: a.cookie } });
  assert.equal(status.status, 200); assert.equal(status.json().actorId, 'demo-employee');
  const stolenCsrf = await f.call('/evaluation/session/actor', { body: { actorId: 'demo-manager' },
    headers: { Cookie: a.cookie, 'X-Evaluation-CSRF': b.json().csrfToken } });
  assert.equal(stolenCsrf.status, 403);
  const admin = await f.call('/evaluation/session/actor', { body: { actorId: 'demo-admin' },
    headers: { Cookie: a.cookie, 'X-Evaluation-CSRF': a.json().csrfToken } });
  assert.equal(admin.status, 403);
  const rotated = await f.call('/evaluation/session/actor', { body: { actorId: 'demo-manager' },
    headers: { Cookie: a.cookie, 'X-Evaluation-CSRF': a.json().csrfToken } });
  assert.equal(rotated.status, 200); assert.notEqual(rotated.cookie, a.cookie);
  assert.equal((await f.call('/evaluation/session', { headers: { Cookie: a.cookie } })).status, 401);
  const end = await f.call('/evaluation/session/end', { body: {}, headers: {
    Cookie: rotated.cookie, 'X-Evaluation-CSRF': rotated.json().csrfToken } });
  assert.equal(end.status, 204); assert.match(end.headers['set-cookie'][0], /Max-Age=0/u);
  assert.equal((await f.call('/evaluation/session', { headers: { Cookie: b.cookie } })).status, 200);
});

test('actual HTTPS rejects replay, CSRF/origin/header abuse, oversized bodies and all business/admin routes', async t => {
  const f = await tlsFixture(t); const invite = f.controller.issueInvitation().invitation;
  const first = await f.call('/evaluation/invitations/redeem', { body: { invitation: invite } });
  assert.equal(first.status, 201);
  assert.equal((await f.call('/evaluation/invitations/redeem', { body: { invitation: invite } })).status, 401);
  const nextInvite = f.controller.issueInvitation().invitation;
  assert.equal((await f.call('/evaluation/invitations/redeem', { body: { invitation: invite } })).status, 401);
  assert.equal((await f.call('/evaluation/invitations/redeem', { body: { invitation: nextInvite }, headers: { Cookie: first.cookie } })).status, 409);
  for (const Origin of ['https://other.example.invalid', 'null', '']) {
    const value = await f.call('/evaluation/invitations/redeem', { body: { invitation: nextInvite }, headers: { Origin } });
    assert.equal(value.status, 403);
  }
  for (const name of ['X-Tenant-Id', 'X-Operator-Id', 'X-Approval-Trusted-Permissions', 'Authorization']) {
    assert.equal((await f.call('/evaluation/session', { headers: { Cookie: first.cookie, [name]: 'untrusted' } })).status, 403);
  }
  assert.equal((await f.call('/evaluation/session', { headers: { Cookie: first.cookie, Host: 'other.example.invalid' } })).status, 403);
  assert.equal((await f.call('/evaluation/session', { headers: { Cookie: `${first.cookie}; ${first.cookie}` } })).status, 400);
  assert.equal((await f.call('/evaluation/invitations/redeem', { body: { invitation: nextInvite, tenantId: 'untrusted' } })).status, 400);
  assert.equal((await f.call('/evaluation/invitations/redeem', { raw: 'x'.repeat(513), method: 'POST' })).status, 413);
  assert.equal((await f.call('/evaluation/invitations/redeem', { raw: '{', method: 'POST' })).status, 400);
  assert.equal((await f.call('/evaluation/invitations/redeem', { body: {}, headers: { 'Content-Type': 'text/plain' } })).status, 415);
  for (const path of ['/api/approval/tasks/pending', '/evaluation/admin/reset', '/actuator/env',
    '/evaluation/session?token=untrusted', '/payment-sandbox', '/evaluation/slots/slot-b/reset']) {
    assert.equal((await f.call(path, { headers: { Cookie: first.cookie } })).status, 404, path);
  }
  const current = await f.call('/evaluation/session', { headers: { Cookie: first.cookie } });
  f.setDriver(() => { throw new Error(nextInvite); });
  const failed = await f.call('/evaluation/session/end', { body: {}, headers: { Cookie: first.cookie, 'X-Evaluation-CSRF': current.json().csrfToken } });
  assert.equal(failed.status, 503); assert.equal(failed.text.includes(nextInvite), false);
  assert.match(failed.headers['set-cookie'][0], /Max-Age=0/u);
  assert.equal((await f.call('/evaluation/session', { headers: { Cookie: first.cookie } })).status, 401);
});

test('plain HTTP cannot spoof TLS using forwarded headers', async t => {
  const f = fixture(t); const server = httpServer(createEvaluationRequestHandler({ controller: f.controller, origin: 'https://evaluation.example.invalid' }));
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => new Promise(done => { server.closeAllConnections(); server.close(done); }));
  const result = await new Promise((done, fail) => {
    const request = httpRequest({ host: '127.0.0.1', port: server.address().port, path: '/evaluation',
      headers: { Host: 'evaluation.example.invalid', 'X-Forwarded-Proto': 'https', Forwarded: 'proto=https' } }, response => {
      response.resume(); response.on('end', () => done(response.statusCode));
    }); request.on('error', fail); request.end();
  });
  assert.equal(result, 403);
});
test('landing CSP pins the external script and both resource integrity values without browser storage', () => {
  const script = evaluationAsset('/evaluation/assets/entry.mjs').body;
  const style = evaluationAsset('/evaluation/assets/entry.css').body;
  for (const source of [script, style]) {
    const hash = createHash('sha256').update(source).digest('base64');
    assert.ok(evaluationPage.includes('integrity="sha256-' + hash + '"'));
  }
  assert.ok(evaluationCsp.includes(createHash('sha256').update(script).digest('base64')));
  assert.match(evaluationCsp, /style-src 'self'; style-src-attr 'none'/u);
  assert.doesNotMatch(evaluationPage + script, /localStorage|sessionStorage|innerHTML|document\.cookie|location\.search/u);
  assert.doesNotMatch(evaluationPage, /<style>|<script>/u);
  assert.doesNotMatch(evaluationCsp, /unsafe-inline|unsafe-eval/u);
  assert.match(evaluationPage, /aria-live="polite"/u);
});

test('actual HTTPS serves only the two immutable page assets and keeps identity and route denials', async t => {
  const f = await tlsFixture(t);
  for (const path of ['/evaluation/assets/entry.mjs', '/evaluation/assets/entry.css']) {
    const result = await f.call(path);
    assert.equal(result.status, 200);
    assert.equal(result.text, evaluationAsset(path).body);
    assert.equal(result.headers['content-type'], evaluationAsset(path).type);
    assert.equal(result.headers['cache-control'], 'no-store');
    assert.equal(result.headers['x-content-type-options'], 'nosniff');
    assert.equal(result.headers['content-security-policy'], evaluationCsp);
    assert.equal(result.headers['set-cookie'], undefined);
    assert.equal((await f.call(path, { method: 'POST', body: {} })).status, 404);
    assert.equal((await f.call(path, { headers: { Origin: 'https://other.example.invalid' } })).status, 403);
    assert.equal((await f.call(path, { headers: { 'X-Tenant-Id': 'untrusted' } })).status, 403);
  }
  for (const path of ['/evaluation/assets/', '/evaluation/assets/entry.mjs?invitation=untrusted',
    '/evaluation/assets/../evaluation-sessions.mjs', '/evaluation/assets/%2e%2e/evaluation-http.mjs',
    '/evaluation/assets/entry.mjs.map', '/evaluation/assets/.env', '/evaluation/assets/not-found.css']) {
    assert.equal((await f.call(path)).status, 404, path);
    assert.equal(evaluationAsset(path), null);
  }
});

test('a throwing clock disables admission instead of silently resuming', t => {
  let fail = false;
  const f = fixture(t, { clock: () => { if (fail) throw new Error('clock unavailable'); return 1000; } });
  f.controller.issueInvitation(); fail = true;
  assert.throws(() => f.controller.issueInvitation(), /CLOCK_INVALID/u);
  fail = false; assert.throws(() => f.controller.issueInvitation(), /DISABLED/u);
});
test('duplicate Origin, missing CSRF and cross-site reads do not mutate a live HTTPS session', async t => {
  const f = await tlsFixture(t); const a = await f.enter();
  const duplicate = await f.call('/evaluation/session/actor', { body: { actorId: 'demo-manager' },
    headers: { Cookie: a.cookie, Origin: [f.origin, f.origin], 'X-Evaluation-CSRF': a.json().csrfToken } });
  assert.equal(duplicate.status, 400);
  const missing = await f.call('/evaluation/session/actor', { body: { actorId: 'demo-manager' }, headers: { Cookie: a.cookie } });
  assert.equal(missing.status, 403);
  const cross = await f.call('/evaluation/session', { headers: { Cookie: a.cookie, 'Sec-Fetch-Site': 'cross-site' } });
  assert.equal(cross.status, 403);
  assert.equal((await f.call('/evaluation/session', { headers: { Cookie: a.cookie } })).json().actorId, 'demo-employee');
});
test('HTTPS admission budget rejects request 121 without an unbounded IP-keyed map', async t => {
  const f = await tlsFixture(t);
  for (let index = 0; index < 120; index += 1) assert.equal((await f.call('/evaluation')).status, 200);
  const limited = await f.call('/evaluation');
  assert.equal(limited.status, 429); assert.equal(limited.headers['retry-after'], '60');
});
test('incomplete HTTPS JSON times out without consuming an invitation', { timeout: 10_000 }, async t => {
  const f = await tlsFixture(t); const invite = f.controller.issueInvitation().invitation;
  const result = await new Promise((done, fail) => {
    const request = httpsRequest({ hostname: '127.0.0.1', port: f.server.address().port,
      servername: 'evaluation.example.invalid', ca: f.ca, path: '/evaluation/invitations/redeem',
      method: 'POST', agent: false, headers: { Host: 'evaluation.example.invalid',
        Origin: f.origin, 'Content-Type': 'application/json' } }, response => {
      response.resume(); response.on('end', () => done(response.statusCode));
    });
    request.on('error', fail); request.write('{');
    t.after(() => request.destroy());
  });
  assert.equal(result, 408); assert.equal(f.controller.snapshot().invitations, 1);
  assert.equal((await f.call('/evaluation/invitations/redeem', { body: { invitation: invite } })).status, 201);
});
test('closing the HTTPS server stops admission and invalidates outstanding sessions', async t => {
  const f = await tlsFixture(t); const a = await f.enter();
  await new Promise(done => f.server.close(done));
  assert.equal(f.controller.snapshot().disabled, true);
  assert.throws(() => f.controller.status(a.cookie.split('=')[1]), /DISABLED/u);
});

import './product-readiness-online-demo-page.test.mjs';
