import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';
import { mountEvaluationPage } from '../product-readiness/online-demo/page/entry.mjs';

// DOM and fetch are controlled here; real HTTPS is tested in the importing suite.
class Element {
  value = '';
  disabled = false;
  hidden = false;
  textContent = '';
  children = [];
  listeners = new Map();
  attributes = new Map();
  addEventListener(name, fn) { this.listeners.set(name, fn); }
  removeEventListener(name, fn) { if (this.listeners.get(name) === fn) this.listeners.delete(name); }
  setAttribute(name, value) { this.attributes.set(name, value); }
  replaceChildren() { this.children = []; }
  append(child) { this.children.push(child); }
  emit(name) { this.listeners.get(name)?.({ preventDefault() {}, target: this }); }
}
const flush = () => new Promise(done => setImmediate(done));
function view(overrides = {}) {
  return { actorId: 'demo-employee', csrfToken: 'a'.repeat(43),
    actors: [{ id: 'demo-employee', displayName: '申请人' }, { id: 'demo-manager', displayName: '经理' }],
    expiresInSeconds: 60, businessAccess: 'NOT_CONNECTED',
    scope: 'DISPOSABLE_EVALUATION_CONTROL_PLANE', ...overrides };
}
function ui(t) {
  const elements = new Map(['login', 'session', 'message', 'actor', 'invitation', 'submit',
    'end', 'refresh', 'expiry', 'controls'].map(id => [id, new Element()]));
  const get = id => elements.get(id);
  get('login').querySelector = () => get('submit');
  const document = new Element();
  document.querySelector = selector => get(selector.slice(1));
  document.createElement = () => new Element();
  const calls = []; let time = 1000; let tick; let stopped = false;
  const dispose = mountEvaluationPage({ document, now: () => time,
    setInterval: fn => { tick = fn; return 1; }, clearInterval: () => { stopped = true; },
    fetch: (path, options) => new Promise((done, reject) => {
      calls.push({ path, options, done, reject });
      options.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
    }) });
  t.after(dispose);
  async function respond(status, body, index = calls.length - 1) {
    calls[index].done({ status, ok: status >= 200 && status < 300, json: async () => body });
    await flush();
  }
  const advance = ms => { time += ms; tick(); };
  return { get, document, calls, dispose, respond, advance, stopped: () => stopped,
    login: async () => {
      await respond(401, { error: 'SESSION_REQUIRED' });
      get('invitation').value = 'i'.repeat(43); get('login').emit('submit');
      await respond(201, view());
    } };
}

test('initial status is checked before enabling invitation submission', async t => {
  const f = ui(t);
  assert.equal(f.get('submit').disabled, true);
  assert.equal(f.calls.length, 1); assert.equal(f.calls[0].options.method, 'GET');
  f.get('login').emit('submit'); assert.equal(f.calls.length, 1);
  await f.respond(401, { error: 'SESSION_REQUIRED' });
  assert.equal(f.get('submit').disabled, false); assert.equal(f.get('session').hidden, true);
});
test('invitation submission is single-flight and clears the input before the response', async t => {
  const f = ui(t); await f.respond(401, { error: 'SESSION_REQUIRED' });
  f.get('invitation').value = 'i'.repeat(43); f.get('login').emit('submit'); f.get('login').emit('submit');
  assert.equal(f.calls.length, 2); assert.equal(f.get('invitation').value, '');
  assert.equal(f.get('submit').disabled, true); assert.equal(f.get('controls').attributes.get('aria-busy'), 'true');
  assert.equal(f.calls[1].options.redirect, 'error');
  await f.respond(201, view());
  assert.equal(f.get('login').hidden, true); assert.equal(f.get('session').hidden, false);
  assert.equal(f.get('controls').attributes.get('aria-busy'), 'false');
});
test('actual returned actor names are assigned only as text nodes', async t => {
  const f = ui(t); const data = view();
  data.actors[1].displayName = '<img src=x onerror=alert(1)>';
  await f.respond(200, data);
  assert.equal(f.get('actor').children[1].textContent, data.actors[1].displayName);
  assert.equal(f.get('actor').children[1].children.length, 0);
});
test('absolute countdown expires locally without sending another request', async t => {
  const f = ui(t); await f.login();
  f.advance(60_000); await flush();
  assert.equal(f.calls.length, 2); assert.equal(f.get('session').hidden, true);
  assert.equal(f.get('actor').children.length, 0); assert.match(f.get('message').textContent, /到期/u);
});
test('polling is bounded, hidden pages do not poll, and reads do not extend the observed deadline', async t => {
  const f = ui(t); await f.login();
  f.advance(19_999); assert.equal(f.calls.length, 2);
  f.document.hidden = true; f.advance(1); assert.equal(f.calls.length, 2);
  f.document.hidden = false; f.document.emit('visibilitychange'); assert.equal(f.calls.length, 3);
  await f.respond(200, view({ expiresInSeconds: 60 }));
  f.advance(40_000); assert.equal(f.get('session').hidden, true);
  assert.equal(f.calls.length, 3);
});
test('a delayed read cannot revive the view after its local deadline', async t => {
  const f = ui(t); await f.login();
  f.advance(20_000); assert.equal(f.calls.length, 3);
  f.advance(40_000); assert.equal(f.get('session').hidden, true);
  await f.respond(200, view()); assert.equal(f.get('session').hidden, true);
  assert.equal(f.get('actor').children.length, 0);
});
test('role changes rotate CSRF in the next action while serializing all controls', async t => {
  const f = ui(t); await f.login();
  f.get('actor').value = 'demo-manager'; f.get('actor').emit('change'); f.get('end').emit('click');
  assert.equal(f.calls.length, 3); assert.equal(f.get('end').disabled, true);
  assert.equal(f.calls[2].options.headers['X-Evaluation-CSRF'], 'a'.repeat(43));
  await f.respond(200, view({ actorId: 'demo-manager', csrfToken: 'b'.repeat(43) }));
  f.get('end').emit('click'); assert.equal(f.calls[3].options.headers['X-Evaluation-CSRF'], 'b'.repeat(43));
  await f.respond(204, null); assert.equal(f.get('session').hidden, true);
});
test('role changes do not extend the existing countdown', async t => {
  const f = ui(t); await f.login(); f.advance(10_000);
  f.get('actor').value = 'demo-manager'; f.get('actor').emit('change');
  await f.respond(200, view({ actorId: 'demo-manager' }));
  f.advance(50_000); assert.equal(f.get('session').hidden, true);
});
test('an explicitly rejected role change restores the last confirmed actor', async t => {
  const f = ui(t); await f.login();
  f.get('actor').value = 'demo-admin'; f.get('actor').emit('change');
  await f.respond(403, { error: 'ACTOR_REJECTED' });
  assert.equal(f.get('actor').value, 'demo-employee'); assert.equal(f.get('session').hidden, false);
});
for (const error of ['CSRF_REJECTED', 'SESSION_ALREADY_ACTIVE', 'UNKNOWN_PRIVATE_MESSAGE']) {
  test(`uncertain result requires a read instead of replaying a write: ${error}`, async t => {
    const f = ui(t); await f.login(); f.get('actor').emit('change');
    await f.respond(403, { error });
    assert.equal(f.get('session').hidden, true); assert.equal(f.get('submit').disabled, true);
    assert.equal(f.get('refresh').hidden, false); assert.equal(f.get('message').textContent.includes(error), false);
    f.get('refresh').emit('click'); assert.equal(f.calls.at(-1).options.method, 'GET');
    await f.respond(200, view({ actorId: 'demo-manager' }));
    assert.equal(f.get('actor').value, 'demo-manager');
    assert.equal(f.calls.filter(item => item.options.method === 'POST').length, 2);
  });
}
test('a failed end request does not falsely claim that credentials or data were cleaned', async t => {
  const f = ui(t); await f.login(); f.get('end').emit('click');
  f.calls.at(-1).reject(new Error('network')); await flush();
  assert.equal(f.get('session').hidden, true); assert.equal(f.get('refresh').hidden, false);
  assert.doesNotMatch(f.get('message').textContent, /会话已结束|已撤销/u);
  f.advance(60_000); assert.equal(f.calls.length, 3);
});
test('typed reset failure clears the UI but does not claim successful reset', async t => {
  const f = ui(t); await f.login(); f.get('end').emit('click');
  await f.respond(503, { error: 'RESET_FAILED' });
  assert.equal(f.get('session').hidden, true); assert.equal(f.get('submit').disabled, false);
  assert.match(f.get('message').textContent, /重置未完成/u);
});
for (const change of [{ expiresInSeconds: 1801 }, { csrfToken: 'invalid' }, { actors: [] },
  { actorId: 'demo-admin' }, { businessAccess: 'ENABLED' }, { scope: 'production' }]) {
  test(`unrecognized session payload cannot become an active UI: ${Object.keys(change)[0]}`, async t => {
    const f = ui(t); await f.respond(200, view(change));
    assert.equal(f.get('session').hidden, true); assert.equal(f.get('refresh').hidden, false);
  });
}
test('dispose cancels pending read and removes polling and UI listeners', async t => {
  const f = ui(t); f.dispose(); await flush();
  assert.equal(f.calls[0].options.signal.aborted, true); assert.equal(f.stopped(), true);
  assert.equal(f.get('login').listeners.size, 0); assert.equal(f.document.listeners.size, 0);
});
test('the page loader refuses mixed HTML and resource revisions before serving anything', t => {
  const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-assets-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const source = new URL('../product-readiness/online-demo/', import.meta.url);
  cpSync(new URL('page', source), resolve(directory, 'page'), { recursive: true });
  cpSync(new URL('evaluation-page.mjs', source), resolve(directory, 'evaluation-page.mjs'));
  for (const name of ['entry.mjs', 'entry.css']) {
    const path = resolve(directory, 'page', name); const original = readFileSync(path, 'utf8');
    writeFileSync(path, original + '\n/* changed bytes */\n');
    const run = spawnSync(process.execPath, [resolve(directory, 'evaluation-page.mjs')], { encoding: 'utf8', timeout: 3000 });
    assert.notEqual(run.status, 0); assert.match(run.stderr, /integrity mismatch/u);
    writeFileSync(path, original);
  }
});
