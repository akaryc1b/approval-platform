import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { createContext, runInContext } from 'node:vm';
import test from 'node:test';

const root = new URL('../../', import.meta.url);
const vue = readFileSync(new URL('apps/mobile/overlay/src/pages/task/list.vue', root), 'utf8');
const source = stripTypeScriptTypes(vue.match(/<script lang="ts" setup>([\s\S]*?)<\/script>/u)[1])
  .replace(/^import\s*\{[\s\S]*?\}\s*from\s*'@\/api\/approval'\s*$/gmu, '');

// Execute the actual page script; only Vue/Uni hooks and API transports are fixtures.
// This is not a browser, backend, database or payment acceptance test.
function page() {
  const hooks = {}; const calls = [];
  let failActive = false;
  const query = mode => async parameters => {
    calls.push({ mode, ...parameters });
    if (failActive && parameters.limit === 20) throw new Error('read unavailable');
    const items = mode === 'started' ? [{ instanceId: 'test-instance', businessKey: 'TEST-PURCHASE' }] : [];
    return { items, total: items.length, hasMore: false, limit: parameters.limit, offset: parameters.offset };
  };
  const context = createContext({
    Error,
    ref: value => ({ value }),
    computed: get => ({ get value() { return get(); } }),
    defineOptions() {}, definePage() {},
    onLoad: callback => { assert.equal(hooks.load, undefined); hooks.load = callback; },
    onShow: callback => { assert.equal(hooks.show, undefined); hooks.show = callback; },
    findPendingTasks: query('pending'), findProcessedTasks: query('processed'), findStartedInstances: query('started'),
    retrieveTask: () => assert.fail('navigation must not write a task'),
    withdrawInstance: () => assert.fail('navigation must not write an instance'),
    uni: { navigateTo() {}, pageScrollTo() {}, showToast() {}, showModal: () => assert.fail('unexpected dialog') },
  });
  runInContext(source + '\nglobalThis.state = {activeMode, currentPage, keyword, loading, loadError, '
    + 'activeTotal, activeItemCount, startedPage, switchMode, refreshAll};', context);
  return { state: context.state, calls, enter: query => hooks.load?.(query),
    show: () => hooks.show(), failActive: () => { failActive = true; } };
}

for (const mode of ['pending', 'processed', 'started']) {
  test(`H5 direct entry selects ${mode} before the first page request`, async () => {
    const p = page(); p.enter({ tab: mode });
    assert.equal(p.state.activeMode.value, mode);
    assert.equal(p.calls.length, 0, 'onLoad must not duplicate the onShow refresh');
    await p.show();
    assert.deepEqual(p.calls.filter(call => call.limit === 20), [{ mode, keyword: '', limit: 20, offset: 0 }]);
    assert.equal(p.calls.length, 4, 'one selected page and three existing count reads');
    assert.equal(p.state.loading.value, false);
  });
}

for (const [label, value] of Object.entries({ absent: undefined, null: null, empty: '', wrongCase: 'Started',
  administrative: 'admin', path: '../started', query: 'started&role=admin', array: ['started'], object: {},
  prototype: '__proto__' })) {
  test(`unrecognized tab ${label} retains the normal pending entry`, async () => {
    const p = page(); p.enter({ tab: value, actorId: 'demo-admin', tenantId: 'other', slotId: 'slot-b' });
    assert.equal(p.state.activeMode.value, 'pending');
    await p.show(); assert.equal(p.calls.filter(call => call.limit === 20)[0].mode, 'pending');
  });
}

test('a task-center entry without any query stays pending', async () => {
  const p = page(); p.enter(); await p.show();
  assert.equal(p.state.activeMode.value, 'pending');
});

test('tab input is compared literally without invoking user coercion', () => {
  const p = page();
  p.enter({ tab: { toString() { assert.fail('must not coerce a non-string route value'); } } });
  assert.equal(p.state.activeMode.value, 'pending');
});

test('returning after purchase shows the started records instead of an empty pending list', async () => {
  const p = page(); p.enter({ tab: 'started' }); await p.show();
  assert.equal(p.state.activeMode.value, 'started');
  assert.equal(p.state.activeTotal.value, 1);
  assert.equal(p.state.activeItemCount.value, 1);
  assert.equal(p.state.startedPage.value.items[0].businessKey, 'TEST-PURCHASE');
});

test('subsequent onShow refreshes preserve the user-selected tab rather than reapplying entry parameters', async () => {
  const p = page(); p.enter({ tab: 'started' }); await p.show();
  await p.state.switchMode('processed'); p.calls.length = 0;
  await p.show();
  assert.equal(p.state.activeMode.value, 'processed');
  assert.equal(p.calls.filter(call => call.limit === 20)[0].mode, 'processed');
});

test('a failed started-page read remains a visible error, with no fallback to another list', async () => {
  const p = page(); p.enter({ tab: 'started' }); p.failActive(); await p.show();
  assert.equal(p.state.activeMode.value, 'started');
  assert.equal(p.state.loadError.value, 'read unavailable');
  assert.equal(p.state.loading.value, false);
  assert.equal(p.state.activeItemCount.value, 0);
  assert.deepEqual(p.calls.filter(call => call.limit === 20).map(call => call.mode), ['started']);
});
