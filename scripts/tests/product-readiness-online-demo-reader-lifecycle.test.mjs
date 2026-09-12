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
const flush = () => new Promise(resolve => setImmediate(resolve));

// Native Response/ReadableStream lifecycle; HTTP and business services remain fixtures.
function observed(text, { stalled = false, broken = false, chunkSize = 257 } = {}) {
  const stats = { pipes: 0, cancellations: 0, closed: false, errored: false };
  const bytes = encoder.encode(text); let offset = 0;
  const stream = new ReadableStream({
    pull(controller) {
      if (offset < bytes.length) {
        const end = Math.min(bytes.length, offset + chunkSize);
        controller.enqueue(bytes.slice(offset, end)); offset = end;
      } else if (broken) { stats.errored = true; controller.error(new Error('test-stream-failure')); }
      else if (!stalled) { stats.closed = true; controller.close(); }
    },
    cancel() { stats.cancellations++; },
  }, { highWaterMark: 0 });
  const pipeTo = stream.pipeTo.bind(stream);
  stream.pipeTo = (destination, options) => { stats.pipes++; return pipeTo(destination, options); };
  return { response: new Response(stream), stats, stream };
}
for (const client of clients) {
  const source = readFileSync(new URL(`../../apps/${client}/platform/approval/evaluation-session.ts`, import.meta.url), 'utf8');
  const actual = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(source)).toString('base64'));
  function fixture(t, business, timeout = 1000) {
    const observations = []; let businessRequests = 0;
    const session = actual.createEvaluationBrowserSession({ origin: 'https://evaluation.invalid',
      requestTimeoutMs: timeout, now: () => 1000,
      fetch: async path => {
        const current = path === '/evaluation/session' ? observed(JSON.stringify(payload)) : business;
        if (path !== '/evaluation/session') businessRequests++;
        observations.push(current); return current.response;
      },
    });
    t.after(session.dispose);
    return { session, observations, businessRequests: () => businessRequests };
  }
  test(`${client}: complete session/business native pipes release without source cancellation`, async t => {
    const expected = { definition: { fields: ['采购金额', '€'] } };
    const f = fixture(t, observed(JSON.stringify(expected), { chunkSize: 1 }));
    assert.deepEqual(await (await f.session.fetch(request)).json(), expected);
    assert.equal(f.businessRequests(), 1); assert.equal(f.observations.length, 4);
    for (const { stats, stream } of f.observations) {
      assert.equal(stats.pipes, 1); assert.equal(stats.cancellations, 0);
      assert.equal(stats.closed, true); assert.equal(stream.locked, false);
    }
    f.session.dispose();
    assert.ok(f.observations.every(item => item.stats.cancellations === 0));
  });
  test(`${client}: oversized bodies cancel the native source and remain rejected`, async t => {
    const business = observed('x'.repeat(2_097_153), { chunkSize: 65_536, stalled: true });
    const f = fixture(t, business);
    await assert.rejects(f.session.fetch(request), error => error.code === 'EVALUATION_RESPONSE_LIMIT');
    assert.equal(f.businessRequests(), 1); assert.equal(business.stats.cancellations, 1);
    assert.equal(business.stream.locked, false); assert.equal(business.stats.closed, false);
  });
  test(`${client}: an error after the last JSON byte cannot become an accepted response`, async t => {
    const business = observed('{"ok":true}', { broken: true }); const f = fixture(t, business);
    await assert.rejects(f.session.fetch(request), error => error.code === 'EVALUATION_REQUEST_UNAVAILABLE');
    assert.equal(business.stats.errored, true); assert.equal(business.stream.locked, false);
    await assert.rejects(f.session.fetch(request)); assert.equal(f.businessRequests(), 1);
  });
  test(`${client}: a stalled write is canceled, remains uncertain and is never retried`, async t => {
    const business = observed('{', { stalled: true }); const f = fixture(t, business, 30);
    await assert.rejects(f.session.fetch('/approval/tasks/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/approve', {
      method: 'POST', headers: { 'Idempotency-Key': 'test-once' }, body: '{"comment":null}',
    }), error => error.code === 'EVALUATION_RESULT_UNCERTAIN');
    await flush();
    assert.equal(f.businessRequests(), 1); assert.equal(business.stats.cancellations, 1);
    assert.equal(business.stream.locked, false);
    await assert.rejects(f.session.fetch(request)); assert.equal(f.businessRequests(), 1);
  });
  test(`${client}: valid JSON bytes are not accepted until the source closes`, async t => {
    const business = observed('{"ok":true}', { stalled: true }); const f = fixture(t, business, 30);
    await assert.rejects(f.session.fetch(request), error => error.code === 'EVALUATION_REQUEST_CANCELLED');
    await flush(); assert.equal(business.stats.closed, false);
    assert.equal(business.stats.cancellations, 1); assert.equal(business.stream.locked, false);
    assert.equal(f.businessRequests(), 1);
  });
  test(`${client}: exactly the response limit is accepted without truncation`, async t => {
    const text = 'x'.repeat(2_097_152); const business = observed(text, { chunkSize: 65_536 });
    const f = fixture(t, business);
    assert.equal(await (await f.session.fetch(request)).text(), text);
    assert.equal(business.stats.cancellations, 0); assert.equal(business.stream.locked, false);
  });
}
