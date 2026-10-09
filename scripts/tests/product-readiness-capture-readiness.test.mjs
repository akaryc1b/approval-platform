// Behavioral regressions include the independent review's fake-clock/deferred probes.
import assert from 'node:assert/strict';
import { existsSync, lstatSync, mkdtempSync, readFileSync, readdirSync, renameSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import { captureBudget, testCaptureBudget } from '../../apps/web/overlay/playground/__tests__/e2e/product-readiness-capture-budget.ts';
import { captureScreenshot, observeCaptureFailures, observeSurface, publishCaptureReceipt, validateSurfaceWitness } from '../../apps/web/overlay/playground/__tests__/e2e/product-readiness-capture.ts';
import { relevantChangeSet } from '../product-readiness/pc-h5-runtime/ci-scope.mjs';

await test('child deadline cannot extend parent when monotonic clock advances between observations', () => {
  let reads = 0;
  let time = 0;
  const now = () => ++reads === 3 ? (time = 50) : time;
  const owner = captureBudget(100, now);
  const child = owner.limit(100);
  time = 100;
  assert.throws(() => owner.remaining(), /expired/);
  assert.throws(() => child.remaining(), /expired/);
});
await test('late resolved operation must fail even before timer callback executes', async () => {
  let time = 0;
  const owner = captureBudget(10, () => time);
  await assert.rejects(owner.run(async () => { time = 11; return 'late result'; }), /deadline|expired/);
});
await test('local cap must be checked after operation resolves', async () => {
  let time = 0;
  const owner = captureBudget(100, () => time);
  await assert.rejects(owner.run(async () => { time = 11; return 'late result'; }, 10), /deadline|expired/);
});
await test('invalid or exhausted budgets never call work', async () => {
  for (const value of [0, -1, Infinity, NaN]) assert.throws(() => captureBudget(value));
  let time = 0;
  const owner = captureBudget(10, () => time);
  time = 10;
  let called = false;
  await assert.rejects(owner.run(async () => { called = true; }), /expired/);
  assert.equal(called, false);
});
await test('sequential child caps share elapsed parent duration', () => {
  let time = 0;
  const owner = captureBudget(100, () => time);
  time = 70;
  const first = owner.limit(50);
  assert.equal(first.remaining(), 30);
  time = 90;
  assert.equal(owner.limit(50).remaining(), 10);
});
await test('completion at the exact owner or local deadline is rejected', async () => {
  for (const [duration, cap, end] of [[10, undefined, 10], [100, 10, 10]]) {
    let time = 0;
    const owner = captureBudget(duration, () => time);
    await assert.rejects(owner.run(async () => { time = end; return 'boundary'; }, cap), /deadline|expired/);
  }
});
await test('sub-millisecond remainder is rejected before downstream timeout', async () => {
  let time = 0;
  const owner = captureBudget(10, () => time);
  time = 9.5;
  let called = false;
  await assert.rejects(owner.run(async () => { called = true; }), /expired/);
  assert.equal(called, false);
});
await test('nested child cannot extend immediate parent local cap', () => {
  let time = 0;
  const owner = captureBudget(100, () => time);
  const first = owner.limit(20);
  time = 10;
  const second = first.limit(100);
  assert.equal(second.remaining(), 10);
  time = 20;
  assert.throws(() => second.remaining(), /expired/);
});

function element(attrs = {}, children = {}) {
  return {
    attrs, children, parentElement: null, textContent: 'BUSINESS-1', isConnected: true,
    get attributes() { return Object.entries(this.attrs).map(([name, value]) => ({ name, value })); },
    getAttribute(name) { return this.attrs[name] ?? null; },
    querySelector(selector) { return this.children[selector] ?? null; },
    querySelectorAll(selector) { return this.children[selector] ?? []; },
    getBoundingClientRect() { return { x: 0, y: 0, width: 640, height: 480, right: 640, bottom: 480 }; },
    getAnimations() { return []; }, closest() { return null; },
  };
}
function fixture(kind = 'detail', prepare = () => {}) {
  const identity = { 'data-task-id': 'task-1', 'data-instance-id': 'instance-1', 'data-business-key': 'BUSINESS-1' };
  const assistance = element({ 'data-loading': 'false', 'data-error': '', 'data-snapshot-task-id': 'task-1', 'data-availability': 'AVAILABLE', 'data-assistance-generation': '1', 'data-assistance-loaded-generation': '1', 'data-snapshot-use-case': 'SUMMARY', 'data-selected-use-case': 'SUMMARY' }, { '.snapshot': element() });
  const task = element(identity);
  const attrs = kind === 'detail' ? { ...identity, 'data-detail-loading': 'false', 'data-detail-error': '', 'data-form-loaded': 'true', 'data-timeline-loaded': 'true', 'data-detail-generation': '1', 'data-detail-loaded-generation': '1' } : {
    'data-active-tab': 'pending', 'data-list-loading': 'false', 'data-counts-loading': 'false', 'data-list-error': '', 'data-counts-error': '', 'data-list-context-valid': 'true',
    'data-list-generation': '1', 'data-list-loaded-generation': '1', 'data-counts-generation': '1', 'data-counts-loaded-generation': '1', 'data-refresh-generation': '1', 'data-refresh-completed-generation': '1',
  };
  const root = element(attrs, { '.el-timeline-item': element(), '.renderer': element(), '[data-testid="approval-assistance"]': assistance, '[data-task-id]': [task], '.el-input__inner': element(), '.el-tabs__item.is-active': element() });
  let currentRoot = root, nextId = 1;
  const frames = new Map(), timers = new Map();
  const original = {};
  const mocks = {
    document: { fonts: { ready: Promise.resolve(), status: 'loaded' }, images: [], documentElement: { scrollWidth: 640 }, querySelectorAll() { return currentRoot ? [currentRoot] : []; }, querySelector() { return null; } },
    performance: { now: () => 0 },
    location: { href: 'https://example.test/tasks' }, innerWidth: 800, innerHeight: 600,
    getComputedStyle: node => node.style ?? { display: 'block', visibility: 'visible', opacity: '1' },
    requestAnimationFrame: callback => { const id = nextId++; frames.set(id, callback); return id; },
    cancelAnimationFrame: id => frames.delete(id),
    setTimeout: callback => { const id = nextId++; timers.set(id, callback); return id; }, clearTimeout: id => timers.delete(id),
  };
  for (const [key, value] of Object.entries(mocks)) { original[key] = Object.getOwnPropertyDescriptor(globalThis, key); Object.defineProperty(globalThis, key, { value, configurable: true, writable: true }); }
  prepare({ root, task, mocks, assistance });
  let status = 'pending', result, failure;
  const observed = observeSurface({ client: mocks.client || 'pc', kind, url: mocks.location.href, businessKey: 'BUSINESS-1', taskId: 'task-1', instanceId: 'instance-1', timeout: 100 });
  observed.then(value => { status = 'fulfilled'; result = value; }, error => { status = 'rejected'; failure = error; });
  return { root, task, mocks, setRoot(value) { currentRoot = value; }, async step(count = 1) { for (let i = 0; i < count; i++) { await Promise.resolve(); for (const [id, fn] of [...frames]) { frames.delete(id); fn(); } await Promise.resolve(); } }, status: () => status, result: () => result, failure: () => failure,
    async close() { for (const fn of timers.values()) fn(); await Promise.resolve(); for (const [key, descriptor] of Object.entries(original)) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key]; } },
  };
}
async function check(name, kind, probe, prepare) {
  await test(name, async () => {
    const f = fixture(kind, prepare);
    try { await probe(f); }
    finally { await f.close(); }
  });
}
await check('fully loaded naturally stable surface resolves', 'detail', async f => { await f.step(4); assert.equal(f.status(), 'fulfilled', f.failure()?.message); });
await check('navigation after matched route cancels observation', 'detail', async f => { await f.step(); f.mocks.location.href = 'https://example.test/other'; await f.step(); assert.equal(f.status(), 'rejected'); });
await check('surface disappearance during capture cancels rather than accepting reopened surface', 'detail', async f => { await f.step(); f.setRoot(null); await f.step(); f.setRoot(f.root); await f.step(4); assert.equal(f.status(), 'rejected'); });
await check('replaced surface with same task during loading cancels observation', 'detail', async f => { f.root.attrs['data-detail-loading'] = 'true'; await f.step(); const replacement = element({ ...f.root.attrs, 'data-detail-loading': 'false' }, f.root.children); f.setRoot(replacement); await f.step(4); assert.equal(f.status(), 'rejected'); });
await check('refresh generation replacement during loading cancels observation', 'list', async f => { f.root.attrs['data-list-loading'] = 'true'; await f.step(); Object.assign(f.root.attrs, { 'data-list-loading': 'false', 'data-refresh-generation': '2', 'data-refresh-completed-generation': '2', 'data-list-generation': '2', 'data-list-loaded-generation': '2', 'data-counts-generation': '2', 'data-counts-loaded-generation': '2' }); await f.step(4); assert.equal(f.status(), 'rejected'); });
await check('unfinished paused finite transition must not certify ready', 'detail', async f => { f.root.getAnimations = () => [{ pending: false, playState: 'paused', effect: { getComputedTiming: () => ({ endTime: 100 }) } }]; await f.step(4); assert.notEqual(f.status(), 'fulfilled'); });
await check('witness rejects navigation after readiness before capture completion', 'detail', async f => { await f.step(4); assert.equal(f.status(), 'fulfilled', f.failure()?.message); f.mocks.location.href = 'https://example.test/other'; assert.throws(() => validateSurfaceWitness(f.result()), /changed/); });
await check('witness rejects reload or changed loading state after readiness', 'detail', async f => { await f.step(4); assert.equal(f.status(), 'fulfilled', f.failure()?.message); f.root.attrs['data-detail-loading'] = 'true'; assert.throws(() => validateSurfaceWitness(f.result()), /changed/); });
await check('witness rejects removed original node after readiness', 'detail', async f => { await f.step(4); assert.equal(f.status(), 'fulfilled', f.failure()?.message); f.root.isConnected = false; assert.throws(() => validateSurfaceWitness(f.result()), /changed/); });
await check('witness rejects newly visible unloaded image after readiness', 'detail', async f => { await f.step(4); assert.equal(f.status(), 'fulfilled', f.failure()?.message); f.mocks.document.images.push(Object.assign(element(), { complete: false, naturalWidth: 0, naturalHeight: 0, currentSrc: 'https://example.test/new.png' })); assert.throws(() => validateSurfaceWitness(f.result()), /assets|image|changed/); });
await check('same-task detail generation change while awaiting readiness cancels', 'detail', async f => { f.root.attrs['data-detail-loading'] = 'true'; await f.step(); Object.assign(f.root.attrs, { 'data-detail-loading': 'false', 'data-detail-generation': '2', 'data-detail-loaded-generation': '2' }); await f.step(4); assert.equal(f.status(), 'rejected'); });
await check('same-task assistance generation change while awaiting readiness cancels', 'detail', async f => { const a = f.root.children['[data-testid="approval-assistance"]']; a.attrs['data-loading'] = 'true'; await f.step(); Object.assign(a.attrs, { 'data-loading': 'false', 'data-assistance-generation': '2', 'data-assistance-loaded-generation': '2' }); await f.step(4); assert.equal(f.status(), 'rejected'); });
await check('standalone counts generation change while awaiting readiness cancels', 'list', async f => { f.root.attrs['data-counts-loading'] = 'true'; await f.step(); Object.assign(f.root.attrs, { 'data-counts-loading': 'false', 'data-counts-generation': '2', 'data-counts-loaded-generation': '2' }); await f.step(4); assert.equal(f.status(), 'rejected'); });
await check('running finite transition waits then naturally completes', 'detail', async f => { const animation = { pending: false, playState: 'running', effect: { getComputedTiming: () => ({ endTime: 100 }) } }; f.root.getAnimations = () => [animation]; await f.step(4); assert.equal(f.status(), 'pending'); animation.playState = 'finished'; await f.step(4); assert.equal(f.status(), 'fulfilled', f.failure()?.message); });
await check('zero-duration finished transition can become ready', 'detail', async f => { f.root.getAnimations = () => [{ pending: false, playState: 'finished', effect: { getComputedTiming: () => ({ endTime: 0 }) } }]; await f.step(4); assert.equal(f.status(), 'fulfilled', f.failure()?.message); });
await check('unrelated infinite animation does not consume finite settlement', 'detail', async f => { f.root.getAnimations = () => [{ pending: false, playState: 'running', effect: { getComputedTiming: () => ({ endTime: Infinity }) } }]; await f.step(4); assert.equal(f.status(), 'fulfilled', f.failure()?.message); });
await check('pending visible image decode blocks readiness until resolved', 'detail', async f => { const decoded = Promise.withResolvers(); f.mocks.document.images.push(Object.assign(element(), { complete: true, naturalWidth: 64, naturalHeight: 64, currentSrc: 'https://example.test/required.png', decode: () => decoded.promise })); await f.step(4); assert.equal(f.status(), 'pending'); decoded.resolve(); await f.step(4); assert.equal(f.status(), 'fulfilled', f.failure()?.message); });
await check('failed visible image fails readiness', 'detail', async f => { f.mocks.document.images.push(Object.assign(element(), { complete: true, naturalWidth: 0, naturalHeight: 0, currentSrc: 'https://example.test/broken.png' })); await f.step(); assert.equal(f.status(), 'rejected'); });
await check('font loading prevents readiness until actual loaded status', 'detail', async f => { f.mocks.document.fonts.status = 'loading'; await f.step(4); assert.equal(f.status(), 'pending'); f.mocks.document.fonts.status = 'loaded'; await f.step(4); assert.equal(f.status(), 'fulfilled', f.failure()?.message); });

await check('task text cannot pass before actual bootstrap node removal', 'list', async f => {
  const loader = element();
  f.mocks.document.querySelector = () => loader;
  await f.step(5);
  assert.equal(f.status(), 'pending');
  f.mocks.document.querySelector = () => null;
  await f.step(4);
  assert.equal(f.status(), 'fulfilled', f.failure()?.message);
});
await check('finite enter animation and translucent ancestors must finish naturally', 'detail', async f => {
  const animation = { pending: false, playState: 'running', effect: { getComputedTiming: () => ({ endTime: 100 }) } };
  f.root.getAnimations = () => [animation];
  f.root.style = { display: 'block', visibility: 'visible', opacity: '0.5' };
  await f.step(4);
  assert.equal(f.status(), 'pending');
  animation.playState = 'finished';
  await f.step(4);
  assert.equal(f.status(), 'pending');
  f.root.style.opacity = '1';
  await f.step(4);
  assert.equal(f.status(), 'fulfilled', f.failure()?.message);
});
await check('unrelated infinite animation does not prevent natural geometry completion', 'detail', async f => {
  f.root.getAnimations = () => [{ pending: false, playState: 'running', effect: { getComputedTiming: () => ({ endTime: Infinity }) } }];
  await f.step(4);
  assert.equal(f.status(), 'fulfilled', f.failure()?.message);
});
await check('deferred document fonts prevent early ready evidence', 'detail', async f => {
  await f.step(4);
  assert.equal(f.status(), 'pending');
  f.mocks.document.fonts.release();
  await f.step(4);
  assert.equal(f.status(), 'fulfilled', f.failure()?.message);
}, ({ mocks }) => {
  const ready = Promise.withResolvers();
  mocks.document.fonts = { ready: ready.promise, status: 'loading', release() { this.status = 'loaded'; ready.resolve(); } };
});
await check('visible images must actually decode and keep their original source', 'detail', async f => {
  const decode = Promise.withResolvers();
  const image = Object.assign(element(), { complete: true, naturalWidth: 20, naturalHeight: 20, currentSrc: 'asset.png', decode: () => decode.promise });
  f.mocks.document.images.push(image);
  await f.step(4);
  assert.equal(f.status(), 'pending');
  decode.resolve();
  await f.step(4);
  assert.equal(f.status(), 'fulfilled', f.failure()?.message);
  image.currentSrc = 'replacement.png';
  assert.throws(() => validateSurfaceWitness(f.result()), /assets/u);
});
await check('required image decode rejection fails with bounded cleanup', 'detail', async f => {
  f.mocks.document.images.push(Object.assign(element(), { complete: true, naturalWidth: 20, naturalHeight: 20, currentSrc: 'asset.png', decode: async () => { throw new Error('bad image'); } }));
  await f.step(4);
  assert.equal(f.status(), 'rejected');
  assert.match(f.failure().message, /decode failed/u);
});
await check('standalone counts reload invalidates the current list observation', 'list', async f => {
  f.root.attrs['data-counts-loading'] = 'true';
  await f.step();
  Object.assign(f.root.attrs, { 'data-counts-loading': 'false', 'data-counts-generation': '2', 'data-counts-loaded-generation': '2' });
  await f.step(4);
  assert.equal(f.status(), 'rejected');
});
await check('stale same-task assistance use case never certifies the selected panel', 'detail', async f => {
  const assistance = f.root.children['[data-testid="approval-assistance"]'];
  assistance.attrs['data-selected-use-case'] = 'RISK_REVIEW';
  await f.step(4);
  assert.equal(f.status(), 'pending');
});

await test('expired screenshot cannot write a success file after its deferred protocol result', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'capture-late-'));
  const target = join(directory, 'result.png');
  const nativeSetTimeout = globalThis.setTimeout;
  const nativeClearTimeout = globalThis.clearTimeout;
  const timers = new Map();
  let id = 0;
  let resolveScreenshot;
  let time = 0;
  globalThis.setTimeout = callback => { timers.set(++id, callback); return id; };
  globalThis.clearTimeout = handle => timers.delete(handle);
  try {
    const page = { screenshot: options => {
      assert.equal(options.path, undefined);
      assert.ok(options.timeout > 0);
      return new Promise(resolve => { resolveScreenshot = resolve; });
    } };
    const pending = captureScreenshot(page, captureBudget(10, () => time), target, { assertCurrent: async () => {} });
    for (let turn = 0; turn < 20 && !resolveScreenshot; turn++) await Promise.resolve();
    assert.ok(resolveScreenshot);
    time = 11;
    for (const callback of [...timers.values()]) callback();
    await assert.rejects(pending, /deadline|expired/u);
    resolveScreenshot(Buffer.from('fixture'));
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(existsSync(target), false);
    assert.equal(existsSync(`${target}.capture.tmp`), false);
  } finally {
    globalThis.setTimeout = nativeSetTimeout;
    globalThis.clearTimeout = nativeClearTimeout;
    rmSync(directory, { force: true, recursive: true });
  }
});


function receiptFixture(probe) {
  const directory = mkdtempSync(join(tmpdir(), 'capture-receipt-'));
  const target = join(directory, 'result.json');
  const temporary = `${target}.tmp`;
  let time = 0;
  let onRead = () => {};
  const budget = captureBudget(10, () => { onRead(); return time; });
  try { probe({ directory, target, temporary, budget, setTime: value => { time = value; }, onRead: fn => { onRead = fn; } }); }
  finally { rmSync(directory, { force: true, recursive: true }); }
}
for (const atomic of [false, true]) {
  for (const completion of [9, 10, 11]) {
    await test(`${atomic ? 'renamed' : 'direct'} receipt completion at ${completion} obeys absolute deadline without timer delivery`, () => receiptFixture(f => {
      const write = () => {
        writeFileSync(atomic ? f.temporary : f.target, '{"status":"PASSED"}');
        if (atomic) renameSync(f.temporary, f.target);
        f.setTime(completion);
      };
      const publish = () => publishCaptureReceipt(f.budget, f.target, write, atomic ? f.temporary : undefined);
      if (completion < 10) {
        publish();
        assert.equal(readFileSync(f.target, 'utf8'), '{"status":"PASSED"}');
      } else {
        assert.throws(publish, /expired/u);
        assert.equal(existsSync(f.target), false);
      }
      assert.equal(existsSync(f.temporary), false);
    }));
  }
}
await test('expired receipt owner rejects before serialization or writing', () => receiptFixture(f => {
  f.setTime(10);
  let invoked = false;
  assert.throws(() => publishCaptureReceipt(f.budget, f.target, () => { invoked = true; }), /expired/u);
  assert.equal(invoked, false);
  assert.deepEqual(readdirSync(f.directory), []);
}));
await test('serialization crossing deadline cannot leave a success receipt', () => receiptFixture(f => {
  const value = { toJSON() { f.setTime(10); return { status: 'PASSED' }; } };
  assert.throws(() => publishCaptureReceipt(f.budget, f.target, () => {
    writeFileSync(f.target, JSON.stringify(value));
  }), /expired/u);
  assert.deepEqual(readdirSync(f.directory), []);
}));
for (const failedAt of ['partial-write', 'before-rename', 'after-rename']) {
  await test(`receipt ${failedAt} failure removes only its newly created output`, () => receiptFixture(f => {
    const historical = join(f.directory, 'older.json');
    writeFileSync(historical, 'retained historical evidence');
    assert.throws(() => publishCaptureReceipt(f.budget, f.target, () => {
      writeFileSync(f.temporary, failedAt === 'partial-write' ? '{"status":' : '{"status":"PASSED"}');
      if (failedAt === 'after-rename') renameSync(f.temporary, f.target);
      throw new Error('write/rename fixture failure');
    }, f.temporary), /fixture failure/u);
    assert.deepEqual(readdirSync(f.directory), ['older.json']);
    assert.equal(readFileSync(historical, 'utf8'), 'retained historical evidence');
  }));
}
for (const existing of ['target', 'temporary', 'dangling-target', 'dangling-temporary']) {
  await test(`pre-existing ${existing} receipt artifact is never overwritten or deleted`, () => receiptFixture(f => {
    const path = existing.includes('temporary') ? f.temporary : f.target;
    if (existing.startsWith('dangling')) symlinkSync(join(f.directory, 'missing'), path);
    else writeFileSync(path, 'historical evidence');
    const before = lstatSync(path, { bigint: true });
    let invoked = false;
    assert.throws(() => publishCaptureReceipt(f.budget, f.target, () => { invoked = true; }, f.temporary), /already exists/u);
    assert.equal(invoked, false);
    assert.equal(lstatSync(path, { bigint: true }).ino, before.ino);
    if (!existing.startsWith('dangling')) assert.equal(readFileSync(path, 'utf8'), 'historical evidence');
  }));
}
await test('expiry cleanup does not remove a replacement with a different file identity', () => receiptFixture(f => {
  assert.throws(() => publishCaptureReceipt(f.budget, f.target, () => {
    writeFileSync(f.target, '{"status":"PASSED"}');
    const replacement = join(f.directory, 'replacement.json');
    writeFileSync(replacement, 'independently replaced evidence');
    f.onRead(() => {
      f.onRead(() => {});
      renameSync(replacement, f.target);
      f.setTime(10);
    });
  }), /expired/u);
  assert.equal(readFileSync(f.target, 'utf8'), 'independently replaced evidence');
}));
await test('receipt callback cannot return a thenable and certify synchronous completion', () => receiptFixture(f => {
  assert.throws(() => publishCaptureReceipt(f.budget, f.target, () => {
    writeFileSync(f.target, '{"status":"PASSED"}');
    return { then() {} };
  }), /synchronously/u);
  assert.deepEqual(readdirSync(f.directory), []);
}));
await test('all four producers guard their original success writers and atomic temporary paths', () => {
  for (const name of ['quick-start-ready', 'browser-accessibility', 'pc-h5-runtime', 'h5-payment-runtime']) {
    const source = readFileSync(resolve(import.meta.dirname, `../../apps/web/overlay/playground/__tests__/e2e/product-readiness-${name}.spec.ts`), 'utf8');
    const begin = source.indexOf('publishCaptureReceipt(budget, receiptPath, () => {');
    assert.ok(begin > 0, name);
    const publication = source.slice(begin, source.indexOf('  } finally {', begin));
    assert.match(publication, /write(?:FileSync|Evidence)\(/u);
    if (name === 'pc-h5-runtime' || name === 'h5-payment-runtime') assert.ok(publication.includes('}, `${receiptPath}.tmp`);'));
  }
});

await test('new helpers and regressions select runtime CI without a root script alias', () => {
  for (const file of [
    'apps/web/overlay/playground/__tests__/e2e/product-readiness-capture.ts',
    'apps/web/overlay/playground/__tests__/e2e/product-readiness-capture-budget.ts',
    'scripts/tests/product-readiness-capture-readiness.test.mjs',
    'scripts/tests/product-readiness-capture-state.test.mjs',
    'scripts/product-readiness/pc-h5-runtime/capture-evidence.mjs',
    'apps/web/overlay/apps/web-ele/src/components/approval/ApprovalAssistancePanel.vue',
  ]) assert.equal(relevantChangeSet([file]), true, file);
});

await test('module failures are observed, never suppressed or reclassified as ready', () => {
  const handlers = new Map();
  const page = { on: (event, fn) => handlers.set(event, fn), off: event => handlers.delete(event) };
  const errors = observeCaptureFailures(page);
  handlers.get('response')({ status: () => 404, request: () => ({ resourceType: () => 'fetch' }) });
  errors.assert(); // The optional SLA fetch is still retained by native tracing.
  handlers.get('response')({ status: () => 504, request: () => ({ resourceType: () => 'script' }) });
  assert.throws(() => errors.assert(), /failed script/u);
  handlers.get('response')({ status: () => 200, request: () => ({ resourceType: () => 'script' }) });
  assert.throws(() => errors.assert(), /failed script/u);
  errors.dispose();
  assert.equal(handlers.size, 0);
});

function h5Detail({ root, mocks }) {
  mocks.client = 'h5';
  const text = element();
  text.style = { display: 'inline-block', visibility: 'visible', opacity: '1' };
  const tag = element({}, { '.wd-tag__text': text });
  tag.classList = { contains: () => false };
  tag.style = { display: 'inline-block', visibility: 'visible', opacity: '1', backgroundColor: 'rgb(0, 1, 2)', borderTopStyle: 'none', borderTopWidth: '0px' };
  const content = element();
  content.style = { display: 'flex', visibility: 'visible', opacity: '1', alignItems: 'center' };
  const button = element({}, { '.wd-button__content': content });
  Object.assign(root.children, { '.wd-tag': tag, 'uni-button.wd-button': [button], '.timeline-item': element(), '.form-card': element() });
  root.children['[data-testid="approval-assistance"]'].children['.snapshot-grid'] = element();
}
await check('the real non-plain H5 detail tag style does not require a nonexistent border', 'detail', async f => {
  await f.step(4);
  assert.equal(f.status(), 'fulfilled', f.failure()?.message);
}, h5Detail);
await check('hidden Wot button content cannot certify rendered styles', 'detail', async f => {
  const button = f.root.children['uni-button.wd-button'][0];
  button.style = { display: 'none', visibility: 'visible', opacity: '1' };
  await f.step(4);
  assert.equal(f.status(), 'pending');
}, h5Detail);

await test('test and outer stage time already spent is never reset at capture setup', () => {
  const dateNow = Date.now;
  const original = Object.getOwnPropertyDescriptor(globalThis, 'performance');
  let time = 50;
  Date.now = () => 1_000;
  Object.defineProperty(globalThis, 'performance', { value: { now: () => time }, configurable: true });
  try {
    const owner = testCaptureBudget({ timeout: 100 }, 0, '1010');
    assert.equal(owner.remaining(), 10);
    time = 60;
    assert.throws(() => owner.remaining(), /expired/u);
    assert.throws(() => testCaptureBudget({ timeout: 0 }, 0, '2000'));
    assert.throws(() => testCaptureBudget({ timeout: 100 }, 0, 'invalid'));
  } finally {
    Date.now = dateNow;
    Object.defineProperty(globalThis, 'performance', original);
  }
});

const ui = readFileSync(resolve(import.meta.dirname, '../../apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-runtime-ui.ts'), 'utf8');
const triggerSource = ui.slice(ui.indexOf('async function triggerApproval('), ui.indexOf('export async function ensurePcLogin'));
async function flush() { for (let turn = 0; turn < 20; turn++) await Promise.resolve(); }
function acknowledgment({ stale = false, deferVisibility = false } = {}) {
  let visible = stale;
  let captures = 0;
  let reads = 0;
  const appeared = Promise.withResolvers();
  const response = Promise.withResolvers();
  const click = Promise.withResolvers();
  const visibility = Promise.withResolvers();
  response.promise.catch(() => {});
  const success = { isVisible: async () => ++reads > 1 && deferVisibility ? visibility.promise : visible };
  const confirmation = { click: () => { visible = true; appeared.resolve(); return click.promise; } };
  const page = { locator: () => ({ getByText: () => success }), waitForResponse: () => response.promise };
  const expect = node => ({ toBeVisible: () => node === confirmation || visible ? Promise.resolve() : appeared.promise });
  const trigger = runInNewContext(`${stripTypeScriptTypes(triggerSource)}\ntriggerApproval`, { expect, exactApprovalResponse: async () => true });
  const pending = trigger(page, confirmation, {}, { remaining: () => 100, run: fn => fn(100) }, async () => { captures++; });
  pending.catch(() => {});
  return { pending, response, click, visibility, captures: () => captures, reads: () => reads };
}
await test('an earlier identical toast cannot acknowledge a new action', async () => {
  const action = acknowledgment({ stale: true });
  await assert.rejects(action.pending, /prior approval acknowledgment/u);
  assert.equal(action.captures(), 0);
});
await test('failed action cancels acknowledgment while its visibility check is deferred', async () => {
  const action = acknowledgment({ deferVisibility: true });
  await flush();
  assert.ok(action.reads() > 1);
  action.response.reject(new Error('response failed'));
  await assert.rejects(action.pending, /response failed/u);
  action.visibility.resolve(true);
  action.click.resolve();
  await flush();
  assert.equal(action.captures(), 0);
});
