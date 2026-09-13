import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { mountEvaluationPage } from '../product-readiness/online-demo/page/entry.mjs';

const flush = () => new Promise(done => setImmediate(done));
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject }; };
const session = { actorId: 'demo-employee', actors: [{ id: 'demo-employee', displayName: '申请人' },
  { id: 'demo-manager', displayName: '经理' }], csrfToken: 'a'.repeat(43), expiresInSeconds: 60,
  businessAccess: 'NOT_CONNECTED', scope: 'DISPOSABLE_EVALUATION_CONTROL_PLANE' };
class Element extends EventTarget {
  value = ''; hidden = false; disabled = false; textContent = ''; children = []; attributes = new Map();
  setAttribute(name, value) { this.attributes.set(name, value); }
  replaceChildren() { this.children = []; } append(value) { this.children.push(value); }
}
// Actual page module and native Response loaders. DOM, fetch and controlled completion
// are fixtures: no claim of Chromium/CDP, network, Docker or business reset execution.
function fixture(t, { rejected = false, malformed = false, failedReset = false } = {}) {
  const elements = new Map(['login', 'session', 'message', 'actor', 'invitation', 'end', 'refresh',
    'expiry', 'controls', 'pending', 'pending-tasks', 'business-notice', 'applications', 'application-links', 'submit']
    .map(id => [id, new Element()]));
  const get = id => elements.get(id); get('login').querySelector = () => get('submit');
  const document = new EventTarget(); document.hidden = false;
  document.querySelector = name => get(name.slice(1)); document.createElement = () => new Element();
  const completion = deferred(); const calls = []; let loads = 0; let endSignal;
  const dispose = mountEvaluationPage({ document, now: () => 1000, setInterval: () => 1, clearInterval() {},
    fetch: async (path, options) => {
      calls.push(path);
      if (path === '/evaluation/session') return new Response(JSON.stringify(session), { status: 200 });
      assert.equal(path, '/evaluation/session/end'); endSignal = options.signal;
      assert.equal(options.method, 'POST'); assert.equal(options.body, '{}');
      assert.equal(options.headers['X-Evaluation-CSRF'], session.csrfToken);
      if (failedReset) return new Response('{"error":"RESET_FAILED"}', { status: 503 });
      const response = new Response(null, { status: 204 }); const consume = response.arrayBuffer.bind(response);
      response.arrayBuffer = async () => { loads++; await completion.promise;
        if (rejected) throw new Error('interrupted completion');
        return malformed ? new ArrayBuffer(1) : consume(); };
      return response;
    } });
  t.after(() => { dispose(); completion.resolve(); });
  return { get, calls, completion, dispose, loads: () => loads, signal: () => endSignal,
    async end() { await flush(); get('end').dispatchEvent(new Event('click')); await flush(); } };
}

test('204 reset acknowledgement uses the native loader and stays busy until completion', async t => {
  const f = fixture(t); await f.end();
  assert.equal(f.loads(), 1); assert.equal(f.get('session').hidden, false);
  assert.equal(f.get('end').disabled, true); assert.doesNotMatch(f.get('message').textContent, /会话已结束/u);
  f.get('end').dispatchEvent(new Event('click')); assert.equal(f.calls.length, 2);
  f.completion.resolve(); await flush();
  assert.equal(f.get('session').hidden, true); assert.equal(f.get('refresh').hidden, true);
  assert.equal(f.get('message').textContent, '会话已结束。'); assert.equal(f.signal().aborted, false);
});
for (const options of [{ rejected: true }, { malformed: true }]) {
  test(`incomplete or invalid empty response cannot report a reset success: ${Object.keys(options)[0]}`, async t => {
    const f = fixture(t, options); await f.end(); f.completion.resolve(); await flush();
    assert.equal(f.loads(), 1); assert.equal(f.get('refresh').hidden, false);
    assert.doesNotMatch(f.get('message').textContent, /会话已结束|重置成功/u);
    f.get('end').dispatchEvent(new Event('click')); await flush(); assert.equal(f.calls.length, 2);
  });
}
test('disposing the page during completion prevents a late successful response from changing it', async t => {
  const f = fixture(t); await f.end(); const message = f.get('message').textContent;
  f.dispose(); assert.equal(f.signal().aborted, true); f.completion.resolve(); await flush();
  assert.equal(f.get('message').textContent, message); assert.equal(f.calls.length, 2);
});
test('typed reset failure still uses its JSON error and never retries the end operation', async t => {
  const f = fixture(t, { failedReset: true }); await f.end();
  assert.equal(f.loads(), 0); assert.match(f.get('message').textContent, /重置未完成/u);
  assert.doesNotMatch(f.get('message').textContent, /会话已结束/u); assert.equal(f.calls.length, 2);
});
test('the landing script remains integrity-pinned together with the unchanged style pin', () => {
  const root = new URL('../product-readiness/online-demo/page/', import.meta.url);
  const script = readFileSync(new URL('entry.mjs', root)); const html = readFileSync(new URL('index.html', root), 'utf8');
  assert.ok(html.includes('integrity="sha256-' + createHash('sha256').update(script).digest('base64') + '"'));
  assert.ok(html.includes('sha256-lUllTKG+CmL8+P+Iuk4chNIR9hLB0DbmN1aC4uRvPXE='));
});
