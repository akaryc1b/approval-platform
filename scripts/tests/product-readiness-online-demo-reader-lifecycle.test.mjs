import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import test from 'node:test';

const clients = ['mobile/overlay/src', 'web/overlay/apps/web-ele/src'];
const actors = ['demo-employee', 'demo-manager', 'demo-finance-reviewer', 'demo-finance-approver-a', 'demo-finance-approver-b'];
const payload = { actorId: actors[0], actors: actors.map(id => ({ id, displayName: id })),
  csrfToken: 'a'.repeat(43), expiresInSeconds: 300,
  scope: 'DISPOSABLE_EVALUATION_CONTROL_PLANE', businessAccess: 'PURCHASE_PAYMENT_WORKFLOW' };
const request = '/approval/forms/purchase-payment/versions/1/runtime';
const encoder = new TextEncoder();
function observed(response) {
  const stats = { reads: 0, cancellations: 0, releases: 0 };
  const getReader = response.body.getReader.bind(response.body);
  response.body.getReader = () => {
    const reader = getReader();
    return {
      read() { stats.reads++; return reader.read(); },
      cancel() { stats.cancellations++; return reader.cancel(); },
      releaseLock() { stats.releases++; reader.releaseLock(); },
    };
  };
  return { response, stats };
}
for (const client of clients) {
  const source = readFileSync(new URL(`../../apps/${client}/platform/approval/evaluation-session.ts`, import.meta.url), 'utf8');
  const actual = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(source)).toString('base64'));
  function fixture(t, response, timeout = 1000) {
    const observations = []; let businessRequests = 0;
    const session = actual.createEvaluationBrowserSession({ origin: 'https://evaluation.invalid',
      requestTimeoutMs: timeout, now: () => 1000,
      fetch: async path => {
        const current = observed(path === '/evaluation/session'
          ? new Response(JSON.stringify(payload)) : response);
        if (path !== '/evaluation/session') businessRequests++;
        observations.push(current.stats); return current.response;
      },
    });
    t.after(session.dispose);
    return { session, observations, businessRequests: () => businessRequests };
  }
  test(`${client}: completed session and business bodies release without cancellation`, async t => {
    const f = fixture(t, new Response(JSON.stringify({ definition: { fields: [] } })));
    assert.deepEqual(await (await f.session.fetch(request)).json(), { definition: { fields: [] } });
    assert.equal(f.businessRequests(), 1);
    assert.equal(f.observations.length, 4);
    for (const stats of f.observations) {
      assert.ok(stats.reads >= 2); assert.equal(stats.cancellations, 0); assert.equal(stats.releases, 1);
    }
  });
  test(`${client}: over-limit bodies still cancel and cannot become accepted results`, async t => {
    const f = fixture(t, new Response('x'.repeat(2_097_153)));
    await assert.rejects(f.session.fetch(request), error => error.code === 'EVALUATION_RESPONSE_LIMIT');
    assert.equal(f.businessRequests(), 1);
    const rejected = f.observations[2];
    assert.equal(rejected.cancellations, 1); assert.equal(rejected.releases, 1);
  });
  test(`${client}: interrupted body remains failed, canceled and released without retry`, async t => {
    const broken = new Response(new ReadableStream({ start(controller) { controller.error(new Error('test-stream-failure')); } }));
    const f = fixture(t, broken);
    await assert.rejects(f.session.fetch(request), error => error.code === 'EVALUATION_REQUEST_UNAVAILABLE');
    assert.equal(f.businessRequests(), 1);
    assert.equal(f.observations[2].cancellations, 1); assert.equal(f.observations[2].releases, 1);
    await assert.rejects(f.session.fetch(request)); assert.equal(f.businessRequests(), 1);
  });
  test(`${client}: a stalled write retains cancellation and uncertain-result fencing`, async t => {
    const stalled = new Response(new ReadableStream({ start(controller) { controller.enqueue(encoder.encode('{')); } }));
    const f = fixture(t, stalled, 30);
    await assert.rejects(f.session.fetch('/approval/tasks/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/approve', {
      method: 'POST', headers: { 'Idempotency-Key': 'test-once' }, body: '{"comment":null}',
    }), error => error.code === 'EVALUATION_RESULT_UNCERTAIN');
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(f.businessRequests(), 1);
    assert.ok(f.observations[2].cancellations >= 1); assert.equal(f.observations[2].releases, 1);
    await assert.rejects(f.session.fetch(request)); assert.equal(f.businessRequests(), 1);
  });
}
