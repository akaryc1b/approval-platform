import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import test from 'node:test';
import { mountEvaluationPage } from '../product-readiness/online-demo/page/entry.mjs';
import { evaluationApplicationPaths } from '../product-readiness/online-demo/evaluation-applications.mjs';
import { EvaluationCdp } from '../product-readiness/online-demo/evaluation-browser.mjs';
import { evaluationBusinessRoute } from '../product-readiness/online-demo/evaluation-business-request.mjs';

const flush = () => new Promise(done => setImmediate(done));
const ids = ['demo-employee', 'demo-manager', 'demo-finance-reviewer', 'demo-finance-approver-a', 'demo-finance-approver-b'];
const payload = (overrides = {}) => ({ actorId: ids[0], actors: ids.map(id => ({ id, displayName: id })), csrfToken: 'a'.repeat(43),
  expiresInSeconds: 60, businessAccess: 'PURCHASE_PAYMENT_WORKFLOW', scope: 'DISPOSABLE_EVALUATION_CONTROL_PLANE', ...overrides });
class Element extends EventTarget {
  hidden = false; disabled = false; value = ''; textContent = ''; children = [];
  setAttribute() {} replaceChildren() { this.children = []; } append(value) { this.children.push(value); }
}
function pageFixture(t, view = payload(), response = evaluationApplicationPaths) {
  const elements = new Map();
  for (const name of ['login', 'session', 'message', 'actor', 'invitation', 'end', 'refresh', 'expiry', 'controls', 'pending', 'pending-tasks', 'business-notice', 'applications', 'application-links']) elements.set(name, new Element());
  elements.get('login').querySelector = () => new Element();
  const document = new EventTarget(); document.hidden = false;
  document.querySelector = name => elements.get(name.slice(1)); document.createElement = () => new Element();
  let time = 1000; let tick; const paths = [];
  const dispose = mountEvaluationPage({ document, now: () => time, setInterval: fn => { tick = fn; return 1; }, clearInterval() {},
    fetch: async path => { paths.push(path); return { status: 200, ok: true, json: async () => path === '/evaluation/applications' ? response : view }; } });
  t.after(dispose);
  return { elements, paths, dispose, advance(ms) { time += ms; tick(); }, setView(next) { view = next; } };
}
test('entry links appear only after the actual registry has been checked; requests contain no actor or destination', async t => {
  const f = pageFixture(t); await flush();
  assert.equal(f.elements.get('application-links').hidden, true); assert.deepEqual(f.paths, ['/evaluation/session']);
  f.elements.get('applications').dispatchEvent(new Event('click')); await flush();
  const links = f.elements.get('application-links'); assert.equal(links.hidden, false); assert.equal(links.children.length, 3);
  assert.deepEqual(new Set(links.children.map(link => link.href)), new Set(Object.values(evaluationApplicationPaths)));
  assert.deepEqual(f.paths, ['/evaluation/session', '/evaluation/applications']);
  f.advance(60000); await flush(); assert.equal(links.hidden, true); assert.equal(links.children.length, 0);
});
for (const bad of [{ ...evaluationApplicationPaths, pc: 'https://outside.invalid/' },
  { ...evaluationApplicationPaths, purchase: 'javascript:alert(1)' },
  { ...evaluationApplicationPaths, extra: '/admin' }, {}]) {
  test('rejects unrecognized or externally directed application metadata', async t => {
    const f = pageFixture(t, payload(), bad); await flush(); f.elements.get('applications').dispatchEvent(new Event('click')); await flush();
    assert.equal(f.elements.get('application-links').hidden, true); assert.equal(f.elements.get('application-links').children.length, 0);
  });
}
test('role change hides old application links and only the applicant receives an initiation link', async t => {
  const f = pageFixture(t); await flush(); f.elements.get('applications').dispatchEvent(new Event('click')); await flush();
  f.setView(payload({ actorId: 'demo-manager', csrfToken: 'b'.repeat(43) }));
  f.elements.get('actor').value = 'demo-manager'; f.elements.get('actor').dispatchEvent(new Event('change')); await flush();
  assert.equal(f.elements.get('application-links').hidden, true);
  f.elements.get('applications').dispatchEvent(new Event('click')); await flush();
  assert.equal(f.elements.get('application-links').children.length, 2);
  assert.equal(f.elements.get('application-links').children.some(a => a.href === evaluationApplicationPaths.purchase), false);
});
test('disconnected and read-only sessions cannot expose workflow page entry', async t => {
  for (const businessAccess of ['NOT_CONNECTED', 'SIGNED_PENDING_READ']) {
    const f = pageFixture(t, payload({ businessAccess })); await flush();
    assert.equal(f.elements.get('applications').hidden, true);
    f.elements.get('applications').dispatchEvent(new Event('click')); await flush();
    assert.deepEqual(f.paths, ['/evaluation/session']); f.dispose();
  }
});
for (const client of ['mobile/overlay/src', 'web/overlay/apps/web-ele/src']) {
  test(`${client}: live application verification revokes an old role instead of silently reinitializing`, async t => {
    const source = readFileSync(new URL(`../../apps/${client}/platform/approval/evaluation-session.ts`, import.meta.url), 'utf8');
    const actual = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(source)).toString('base64'));
    let view = payload(); let invalidations = 0; let calls = 0;
    const session = actual.createEvaluationBrowserSession({ origin: 'https://evaluation.invalid', now: () => 1000,
      onInvalidated: () => { invalidations++; }, fetch: async () => { calls++; return new Response(JSON.stringify(view)); } });
    t.after(session.dispose); await session.initialize(); await session.verify(); assert.equal(calls, 2);
    view = payload({ actorId: 'demo-manager', csrfToken: 'b'.repeat(43) });
    await assert.rejects(session.verify()); assert.equal(invalidations, 1); assert.throws(session.view);
    const before = calls; await assert.rejects(session.initialize()); assert.equal(calls, before);
  });
}
test('H5 navigation is restricted to existing business pages and leaves normal-mode routing installed', async () => {
  const source = readFileSync(new URL('../../apps/mobile/overlay/src/platform/approval/evaluation-navigation.ts', import.meta.url), 'utf8');
  const isolated = source.replace(/import[^\n]+from '\.\/evaluation-session'/u,
    'const approvalEvaluationEnabled=()=>false; const getEvaluationBrowserSession=()=>({verify:async()=>{},view:()=>({})});');
  const module = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(isolated)).toString('base64'));
  for (const path of ['/pages/task/list', '/pages/task/list?tab=started', '/pages/task/detail?taskId=known', '/pages/initiate/form?formKey=purchase-payment&version=1']) assert.equal(module.evaluationPageAllowed(path), true);
  for (const path of ['/pages/profile/index', '/pages/operations/index', '/pages/task/list/../admin', '//outside', 'https://outside.invalid', '/pages/task/%6cist', '/pages/task/list#admin', undefined]) assert.equal(module.evaluationPageAllowed(path), false);
  const main = readFileSync(new URL('../../apps/mobile/overlay/src/main.ts', import.meta.url), 'utf8');
  assert.match(main, /approvalEvaluationEnabled\(\) \? evaluationRouteInterceptor : routeInterceptor/u);
  const bootstrap = readFileSync(new URL('../../apps/web/overlay/apps/web-ele/src/bootstrap.ts', import.meta.url), 'utf8');
  assert.ok(bootstrap.indexOf('getEvaluationBrowserSession().initialize()') < bootstrap.indexOf("app.mount('#app')"));
  assert.match(bootstrap, /: await import\('\.\/router'\)/u);
});
test('task-page prerequisites remain exact reads, not delegation or SLA management permissions', () => {
  const id = '11111111-1111-4111-8111-111111111111';
  for (const suffix of ['delegation', 'sla']) {
    assert.equal(evaluationBusinessRoute('GET', `/api/approval/tasks/${id}/${suffix}`).write, false);
    assert.throws(() => evaluationBusinessRoute('POST', `/api/approval/tasks/${id}/${suffix}`));
    assert.throws(() => evaluationBusinessRoute('GET', `/api/approval/tasks/${id}/${suffix}/admin`));
  }
});
class Socket extends EventTarget { messages = []; send(value) { this.messages.push(JSON.parse(value)); } close() { this.dispatchEvent(new Event('close')); } }
test('CDP replies remain request/session-bound, errors sanitized, and disconnect rejects pending work', async () => {
  const socket = new Socket(); const signal = new AbortController(); const cdp = new EvaluationCdp(socket, signal.signal);
  const pending = cdp.send('Runtime.evaluate', { expression: '1' }, 'isolated-context');
  assert.equal(socket.messages[0].sessionId, 'isolated-context');
  socket.dispatchEvent(new MessageEvent('message', { data: JSON.stringify({ id: 1, result: { value: 1 } }) }));
  assert.deepEqual(await pending, { value: 1 });
  const rejected = cdp.send('Page.navigate');
  socket.dispatchEvent(new MessageEvent('message', { data: JSON.stringify({ id: 2, error: { message: 'private token' } }) }));
  await assert.rejects(rejected, /BROWSER_PROTOCOL_REJECTED/u);
  const aborted = cdp.send('Runtime.evaluate'); signal.abort(); await assert.rejects(aborted, /BROWSER_PROTOCOL_CLOSED/u);
  assert.equal(cdp.pending.size, 0); await assert.rejects(cdp.send('Page.navigate'), /BROWSER_PROTOCOL_CLOSED/u);
});
test('browser driver keeps sandbox and scoped TLS, uses two contexts, never substitutes successful business responses', () => {
  const source = readFileSync(new URL('../product-readiness/online-demo/evaluation-browser.mjs', import.meta.url), 'utf8');
  assert.match(source, /Target\.createBrowserContext/u); assert.match(source, /for \(const label of \['A', 'B'\]\)/u);
  assert.match(source, /Input\.dispatchMouseEvent/u); assert.match(source, /DOM\.setFileInputFiles/u);
  assert.match(source, /ignore-certificate-errors-spki-list=/u);
  assert.doesNotMatch(source, /--no-sandbox|--ignore-certificate-errors['"]|Fetch\.enable|Fetch\.fulfillRequest|Network\.setRequestInterception/u);
  assert.match(source, /Page\.captureScreenshot/u); assert.match(source, /credentialsRetained: false/u);
  const runner = readFileSync(new URL('../product-readiness/online-demo/evaluation-business-rehearsal.mjs', import.meta.url), 'utf8');
  assert.match(runner, /await interactions\.uploadAndStart/u); assert.match(runner, /await interactions\.approve/u); assert.match(runner, /await interactions\.end/u);
  assert.match(runner, /receipt\.resetA = requireEvaluationBusinessReset/u); assert.match(runner, /receipt\.resetB = requireEvaluationBusinessReset/u);
});
