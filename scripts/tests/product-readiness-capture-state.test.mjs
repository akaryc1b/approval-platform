import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { resolve } from 'node:path';
import { setImmediate } from 'node:timers/promises';
import test from 'node:test';
import { runInNewContext } from 'node:vm';

const root = resolve(import.meta.dirname, '../..');
const clients = [
  {
    name: 'PC',
    list: 'apps/web/overlay/apps/web-ele/src/views/approval/workbench/index.vue',
    detail: 'apps/web/overlay/apps/web-ele/src/views/approval/workbench/index.vue',
    assistance: 'apps/web/overlay/apps/web-ele/src/components/approval/ApprovalAssistancePanel.vue',
    tab: 'activeTab',
    refresh: 'refreshWorkbench',
  },
  {
    name: 'H5',
    list: 'apps/mobile/overlay/src/pages/task/list.vue',
    detail: 'apps/mobile/overlay/src/pages/task/detail.vue',
    assistance: 'apps/mobile/overlay/src/components/approval/ApprovalAssistancePanel.vue',
    tab: 'activeMode',
    refresh: 'refreshAll',
  },
];

// Execute the actual setup logic, replacing only Vue lifecycle/reactivity and API
// dependencies. Attribute expressions come from the actual template, not a copy
// of the capture contract. No client package installation is needed by this test.
function component(filename, dependencies = {}) {
  const source = readFileSync(resolve(root, filename), 'utf8');
  const script = source.match(/<script[^>]*>([\s\S]*?)<\/script>/u)?.[1];
  assert.ok(script, `missing setup script in ${filename}`);
  const withoutImports = script.replace(/^import[\s\S]*?from ['"][^'"]+['"];?\r?\n/gmu, '');
  const names = [...withoutImports.matchAll(/^(?:const|(?:async )?function) (\w+)/gmu)]
    .map(match => match[1]);
  const bindings = runInNewContext(
    `${stripTypeScriptTypes(withoutImports)}\n;({ ${names.join(', ')} })`,
    {
      ref: value => ({ value }),
      computed: getter => ({ get value() { return getter(); } }),
      watch: () => {},
      onMounted: () => {},
      onShow: () => {},
      onLoad: () => {},
      defineOptions: () => {},
      definePage: () => {},
      defineProps: () => ({ taskId: '' }),
      ...dependencies,
    },
  );
  return {
    bindings,
    attributes(testId) {
      const marker = source.indexOf(`data-testid="${testId}"`);
      assert.ok(marker >= 0, `missing ${testId} in ${filename}`);
      const start = source.lastIndexOf('<', marker);
      let quote = '';
      let end = start + 1;
      for (; end < source.length; end += 1) {
        const char = source[end];
        if (quote) {
          if (char === quote) quote = '';
        } else if (char === '"' || char === "'") {
          quote = char;
        } else if (char === '>') {
          break;
        }
      }
      const values = Object.fromEntries(Object.entries(bindings).map(([key, value]) => [
        key,
        value && typeof value === 'object' && Object.hasOwn(value, 'value') ? value.value : value,
      ]));
      return Object.fromEntries([...source.slice(start, end).matchAll(
        /(?:^|\s)(:?)(data-[a-z-]+)="([^"]*)"/gu,
      )].map(([, bound, key, expression]) => [
        key,
        String(bound ? runInNewContext(expression, values) : expression),
      ]));
    },
  };
}

function deferredApi() {
  const calls = [];
  const dependencies = {};
  for (const method of [
    'findPendingTasks', 'findProcessedTasks', 'findStartedInstances',
    'findPendingTask', 'findApprovalTimeline', 'findTaskDelegation',
    'findTaskFormRuntime', 'findParticipantTaskSla', 'findApprovalAssistance',
  ]) {
    dependencies[method] = (...args) => {
      const deferred = Promise.withResolvers();
      calls.push({ method, args, ...deferred });
      return deferred.promise;
    };
  }
  return { calls, dependencies };
}

function page(total = 7) {
  return { total, items: [], hasMore: false, limit: 20, offset: 0 };
}

function listAttributes(view) {
  return view.attributes('approval-task-list');
}

function task(taskId) {
  return { taskId, instanceId: `instance-${taskId}`, businessKey: `business-${taskId}` };
}

for (const client of clients) {
  test(`${client.name} list keeps the whole refresh incomplete while overview counts are delayed`, async () => {
    const api = deferredApi();
    const view = component(client.list, api.dependencies);
    const initial = listAttributes(view);
    assert.equal(initial['data-list-loaded-generation'], '0');
    assert.equal(initial['data-counts-loaded-generation'], '0');
    assert.equal(initial['data-list-context-valid'], 'false');
    const pending = view.bindings[client.refresh]();
    assert.equal(api.calls.length, 4, 'one active page and three overview reads');
    assert.equal(listAttributes(view)['data-refresh-generation'], '1');
    assert.equal(listAttributes(view)['data-counts-loading'], 'true');
    api.calls[0].resolve(page(9));
    await setImmediate();
    const pageOnly = listAttributes(view);
    assert.equal(pageOnly['data-list-loading'], 'false');
    assert.equal(pageOnly['data-list-loaded-generation'], '1');
    assert.equal(pageOnly['data-list-context-valid'], 'true');
    assert.equal(pageOnly['data-counts-loading'], 'true');
    assert.equal(pageOnly['data-counts-loaded-generation'], '0');
    assert.equal(pageOnly['data-refresh-completed-generation'], '0');
    api.calls.slice(1).forEach((call, index) => call.resolve(page(10 + index)));
    await pending;
    const complete = listAttributes(view);
    assert.equal(complete['data-counts-loading'], 'false');
    assert.equal(complete['data-counts-error'], '');
    assert.equal(complete['data-counts-generation'], complete['data-counts-loaded-generation']);
    assert.equal(complete['data-refresh-generation'], complete['data-refresh-completed-generation']);
    assert.equal(complete['data-pending-total'], '10');
    assert.equal(complete['data-processed-total'], '11');
  });

  test(`${client.name} rejected overview reads stay observable while existing partial totals still update`, async () => {
    const api = deferredApi();
    const view = component(client.list, api.dependencies);
    const first = view.bindings[client.refresh]();
    api.calls.forEach(call => call.resolve(page(5)));
    await first;
    const second = view.bindings[client.refresh]();
    const loading = listAttributes(view);
    assert.equal(loading['data-counts-loaded-generation'], '0');
    assert.equal(loading['data-list-loaded-generation'], '0');
    assert.equal(loading['data-refresh-completed-generation'], '0');
    api.calls[4].resolve(page(8));
    api.calls[5].resolve(page(12));
    api.calls[6].reject(new Error('overview unavailable'));
    api.calls[7].resolve(page(14));
    await second;
    const result = listAttributes(view);
    assert.equal(result['data-counts-loading'], 'false');
    assert.equal(result['data-counts-error'], 'processed');
    assert.equal(result['data-counts-loaded-generation'], '0');
    assert.equal(result['data-counts-generation'], '2');
    assert.equal(result['data-refresh-completed-generation'], '2');
    assert.equal(result['data-pending-total'], '12');
    assert.equal(result['data-processed-total'], '5');
    assert.equal(result['data-started-total'], '14');
    assert.equal(result['data-list-error'], '');
  });

  for (const firstToComplete of ['old', 'new']) {
    for (const context of ['same', 'newer tab']) {
      test(`${client.name} overlapping ${context} refreshes fail closed when ${firstToComplete} completes first`, async () => {
        const api = deferredApi();
        const view = component(client.list, api.dependencies);
        const older = view.bindings[client.refresh]();
        if (context === 'newer tab') view.bindings[client.tab].value = 'processed';
        const newer = view.bindings[client.refresh]();
        assert.equal(api.calls.length, 8);
        const early = firstToComplete === 'old' ? 0 : 4;
        const late = firstToComplete === 'old' ? 4 : 0;
        api.calls.slice(early, early + 4).forEach(call => call.resolve(page(20 + early)));
        await (firstToComplete === 'old' ? older : newer);
        const overlapping = listAttributes(view);
        assert.equal(overlapping['data-list-loading'], 'true');
        assert.equal(overlapping['data-counts-loading'], 'true');
        api.calls.slice(late, late + 4).forEach(call => call.resolve(page(20 + late)));
        await (firstToComplete === 'old' ? newer : older);
        const settled = listAttributes(view);
        assert.equal(settled['data-list-loading'], 'false');
        assert.equal(settled['data-counts-loading'], 'false');
        assert.equal(settled['data-list-generation'], '2');
        assert.equal(settled['data-counts-generation'], '2');
        assert.equal(settled['data-refresh-generation'], '2');
        const lastGeneration = firstToComplete === 'old' ? '2' : '1';
        assert.equal(settled['data-list-loaded-generation'], lastGeneration);
        assert.equal(settled['data-counts-loaded-generation'], lastGeneration);
        assert.equal(settled['data-refresh-completed-generation'], lastGeneration);
        assert.equal(settled['data-list-context-valid'],
          String(context === 'same' || firstToComplete === 'old'));
      });
    }
  }

  test(`${client.name} list context invalidates on unsatisfied page/query changes and failed reload`, async () => {
    const api = deferredApi();
    const view = component(client.list, api.dependencies);
    const first = view.bindings.loadActivePage();
    api.calls[0].resolve(page());
    await first;
    assert.equal(listAttributes(view)['data-list-context-valid'], 'true');
    view.bindings.currentPage.value = 2;
    assert.equal(listAttributes(view)['data-list-context-valid'], 'false');
    view.bindings.currentPage.value = 1;
    view.bindings.keyword.value = 'changed query';
    assert.equal(listAttributes(view)['data-list-context-valid'], 'false');
    const reload = view.bindings.loadActivePage();
    assert.equal(listAttributes(view)['data-list-loaded-generation'], '0');
    api.calls[1].reject(new Error('page unavailable'));
    await reload;
    const result = listAttributes(view);
    assert.notEqual(result['data-list-error'], '');
    assert.equal(result['data-list-loading'], 'false');
    assert.equal(result['data-list-loaded-generation'], '0');
    assert.equal(result['data-list-context-valid'], 'false');
  });

  test(`${client.name} detail exposes the loaded task only after its form and timeline settle`, async () => {
    const api = deferredApi();
    const view = component(client.detail, api.dependencies);
    const selected = task('task-a');
    let pending;
    if (client.name === 'PC') pending = view.bindings.openTask(selected);
    else {
      view.bindings.taskId.value = selected.taskId;
      pending = view.bindings.loadDetails();
    }
    assert.equal(view.attributes('approval-task-detail')['data-detail-loading'], 'true');
    api.calls.find(call => call.method === 'findPendingTask').resolve(selected);
    await setImmediate();
    api.calls.find(call => call.method === 'findApprovalTimeline').resolve({ instanceId: selected.instanceId, items: [] });
    api.calls.find(call => call.method === 'findTaskDelegation').resolve(undefined);
    api.calls.find(call => call.method === 'findParticipantTaskSla')?.resolve(undefined);
    await setImmediate();
    const beforeForm = view.attributes('approval-task-detail');
    assert.equal(beforeForm['data-detail-loading'], 'true');
    assert.equal(beforeForm['data-form-loaded'], 'false');
    api.calls.find(call => call.method === 'findTaskFormRuntime').resolve({ values: {} });
    await pending;
    const loaded = view.attributes('approval-task-detail');
    assert.equal(loaded['data-task-id'], selected.taskId);
    assert.equal(loaded['data-instance-id'], selected.instanceId);
    assert.equal(loaded['data-business-key'], selected.businessKey);
    assert.equal(loaded['data-detail-loading'], 'false');
    assert.equal(loaded['data-detail-error'], '');
    assert.equal(loaded['data-form-loaded'], 'true');
    assert.equal(loaded['data-timeline-loaded'], 'true');
  });

  test(`${client.name} same-task detail reload invalidates its generation before restoring identical task attributes`, async () => {
    const api = deferredApi();
    const view = component(client.detail, api.dependencies);
    const selected = task('task-a');
    for (const generation of [1, 2]) {
      const start = api.calls.length;
      let pending;
      if (client.name === 'PC') pending = view.bindings.openTask(selected);
      else {
        view.bindings.taskId.value = selected.taskId;
        pending = view.bindings.loadDetails();
      }
      const loading = view.attributes('approval-task-detail');
      assert.equal(loading['data-detail-generation'], String(generation));
      assert.equal(loading['data-detail-loaded-generation'], '0');
      assert.equal(loading['data-detail-loading'], 'true');
      api.calls.slice(start).find(call => call.method === 'findPendingTask').resolve(selected);
      await setImmediate();
      api.calls.slice(start).find(call => call.method === 'findApprovalTimeline').resolve({ instanceId: selected.instanceId, items: [] });
      api.calls.slice(start).find(call => call.method === 'findTaskDelegation').resolve(undefined);
      api.calls.slice(start).find(call => call.method === 'findParticipantTaskSla')?.resolve(undefined);
      await setImmediate();
      api.calls.slice(start).find(call => call.method === 'findTaskFormRuntime').resolve({ values: {} });
      await pending;
      const loaded = view.attributes('approval-task-detail');
      assert.equal(loaded['data-detail-loaded-generation'], String(generation));
      assert.equal(loaded['data-detail-loading'], 'false');
      assert.equal(loaded['data-task-id'], selected.taskId);
      assert.equal(loaded['data-instance-id'], selected.instanceId);
      assert.equal(loaded['data-business-key'], selected.businessKey);
    }
  });

  test(`${client.name} detail never reports a failed form read as loaded`, async () => {
    const api = deferredApi();
    const view = component(client.detail, api.dependencies);
    const selected = task('task-a');
    let pending;
    if (client.name === 'PC') pending = view.bindings.openTask(selected);
    else {
      view.bindings.taskId.value = selected.taskId;
      pending = view.bindings.loadDetails();
    }
    api.calls.find(call => call.method === 'findPendingTask').resolve(selected);
    await setImmediate();
    api.calls.find(call => call.method === 'findApprovalTimeline').resolve({ instanceId: selected.instanceId, items: [] });
    api.calls.find(call => call.method === 'findTaskDelegation').resolve(undefined);
    api.calls.find(call => call.method === 'findParticipantTaskSla')?.resolve(undefined);
    await setImmediate();
    api.calls.find(call => call.method === 'findTaskFormRuntime').reject(new Error('form unavailable'));
    await pending;
    const result = view.attributes('approval-task-detail');
    assert.equal(result['data-detail-loading'], 'false');
    assert.equal(result['data-form-loaded'], 'false');
    if (client.name === 'PC') {
      assert.equal(result['data-detail-error'], '', 'existing optional-form failure remains tolerated');
      assert.equal(result['data-task-id'], selected.taskId);
    } else {
      assert.notEqual(result['data-detail-error'], '');
    }
  });

  for (const firstToComplete of ['old', 'new']) {
    test(`${client.name} detail tracks overlapping task reads when ${firstToComplete} completes first`, async () => {
      const api = deferredApi();
      const view = component(client.detail, api.dependencies);
      const tasks = [task('task-a'), task('task-b')];
      const pending = tasks.map((selected) => {
        if (client.name === 'PC') return view.bindings.openTask(selected);
        view.bindings.taskId.value = selected.taskId;
        return view.bindings.loadDetails();
      });
      async function settle(index) {
        const selected = tasks[index];
        const call = (method, id) => api.calls.find(entry => entry.method === method && entry.args[0] === id);
        call('findPendingTask', selected.taskId).resolve(selected);
        await setImmediate();
        call('findApprovalTimeline', selected.instanceId).resolve({ instanceId: selected.instanceId, items: [] });
        call('findTaskDelegation', selected.taskId).resolve(undefined);
        call('findParticipantTaskSla', selected.taskId)?.resolve(undefined);
        await setImmediate();
        call('findTaskFormRuntime', selected.taskId).resolve({ values: {} });
        await pending[index];
      }
      const early = firstToComplete === 'old' ? 0 : 1;
      const late = firstToComplete === 'old' ? 1 : 0;
      await settle(early);
      const overlapping = view.attributes('approval-task-detail');
      assert.equal(overlapping['data-detail-loading'], 'true');
      assert.equal(overlapping['data-task-id'], tasks[early].taskId);
      await settle(late);
      const settled = view.attributes('approval-task-detail');
      assert.equal(settled['data-detail-loading'], 'false');
      assert.equal(settled['data-detail-generation'], '2');
      assert.equal(settled['data-detail-loaded-generation'], String(late + 1));
      assert.equal(settled['data-task-id'], tasks[late].taskId);
      assert.equal(settled['data-instance-id'], tasks[late].instanceId);
      assert.equal(settled['data-business-key'], tasks[late].businessKey);
      assert.equal(settled['data-timeline-loaded'], 'true');
      assert.equal(settled['data-form-loaded'], String(client.name === 'PC' || late === 1));
    });

    test(`${client.name} same-task assistance use-case reads expose stale snapshots when ${firstToComplete} completes first`, async () => {
      const api = deferredApi();
      const props = { taskId: 'task-a' };
      const view = component(client.assistance, { ...api.dependencies, defineProps: () => props });
      const pending = [view.bindings.loadAssistance()];
      pending.push(view.bindings.selectUseCase('RISK_REVIEW'));
      const loading = view.attributes('approval-assistance');
      assert.equal(loading['data-assistance-generation'], '2');
      assert.equal(loading['data-assistance-loaded-generation'], '0');
      assert.equal(loading['data-selected-use-case'], 'RISK_REVIEW');
      const snapshots = ['SUMMARY', 'RISK_REVIEW'].map(requestedUseCase => ({
        taskSnapshot: task('task-a'), availability: 'AVAILABLE', requestedUseCase,
      }));
      const early = firstToComplete === 'old' ? 0 : 1;
      const late = firstToComplete === 'old' ? 1 : 0;
      api.calls[early].resolve(snapshots[early]);
      await pending[early];
      const overlapping = view.attributes('approval-assistance');
      assert.equal(overlapping['data-loading'], 'true');
      assert.equal(overlapping['data-assistance-loaded-generation'], String(early + 1));
      api.calls[late].resolve(snapshots[late]);
      await pending[late];
      const settled = view.attributes('approval-assistance');
      assert.equal(settled['data-loading'], 'false');
      assert.equal(settled['data-snapshot-task-id'], 'task-a');
      assert.equal(settled['data-assistance-generation'], '2');
      assert.equal(settled['data-assistance-loaded-generation'], String(late + 1));
      assert.equal(settled['data-selected-use-case'], 'RISK_REVIEW');
      assert.equal(settled['data-snapshot-use-case'], snapshots[late].requestedUseCase);
      const reload = view.bindings.loadAssistance();
      const reloading = view.attributes('approval-assistance');
      assert.equal(reloading['data-assistance-generation'], '3');
      assert.equal(reloading['data-assistance-loaded-generation'], '0');
      assert.equal(reloading['data-loading'], 'true');
      api.calls[2].resolve(snapshots[1]);
      await reload;
      const current = view.attributes('approval-assistance');
      assert.equal(current['data-assistance-loaded-generation'], '3');
      assert.equal(current['data-snapshot-use-case'], current['data-selected-use-case']);
    });

    test(`${client.name} assistance exposes actual snapshots when ${firstToComplete} task read completes first`, async () => {
      const api = deferredApi();
      const props = { taskId: 'task-a' };
      const view = component(client.assistance, { ...api.dependencies, defineProps: () => props });
      const pending = [view.bindings.loadAssistance()];
      props.taskId = 'task-b';
      pending.push(view.bindings.loadAssistance());
      const snapshots = [
        { taskSnapshot: task('task-a'), availability: 'PROVIDER_NOT_CONFIGURED', requestedUseCase: 'SUMMARY' },
        { taskSnapshot: task('task-b'), availability: 'AVAILABLE', requestedUseCase: 'SUMMARY' },
      ];
      const early = firstToComplete === 'old' ? 0 : 1;
      const late = firstToComplete === 'old' ? 1 : 0;
      api.calls[early].resolve(snapshots[early]);
      await pending[early];
      const overlapping = view.attributes('approval-assistance');
      assert.equal(overlapping['data-loading'], 'true');
      assert.equal(overlapping['data-snapshot-task-id'], snapshots[early].taskSnapshot.taskId);
      api.calls[late].resolve(snapshots[late]);
      await pending[late];
      const settled = view.attributes('approval-assistance');
      assert.equal(settled['data-loading'], 'false');
      assert.equal(settled['data-task-id'], snapshots[late].taskSnapshot.taskId);
      assert.equal(settled['data-snapshot-task-id'], snapshots[late].taskSnapshot.taskId);
      assert.equal(settled['data-instance-id'], snapshots[late].taskSnapshot.instanceId);
      assert.equal(settled['data-availability'], snapshots[late].availability);
      if (late === 0) {
        assert.notEqual(settled['data-snapshot-task-id'], props.taskId, 'stale response must not borrow the new prop identity');
      }
      const failed = view.bindings.loadAssistance();
      api.calls[2].reject(new Error('assistance unavailable'));
      await failed;
      const error = view.attributes('approval-assistance');
      assert.equal(error['data-loading'], 'false');
      assert.equal(error['data-snapshot-task-id'], '');
      assert.equal(error['data-availability'], '');
      assert.notEqual(error['data-error'], '');
    });
  }
}

test('PC detail exposes mismatched task/form responses without rewriting their existing completion order', async () => {
  const api = deferredApi();
  const view = component(clients[0].detail, api.dependencies);
  const tasks = [task('task-a'), task('task-b')];
  const pending = tasks.map(selected => view.bindings.openTask(selected));
  for (const selected of tasks) {
    api.calls.find(call => call.method === 'findPendingTask' && call.args[0] === selected.taskId).resolve(selected);
    api.calls.find(call => call.method === 'findApprovalTimeline' && call.args[0] === selected.instanceId).resolve({ instanceId: selected.instanceId, items: [] });
    api.calls.find(call => call.method === 'findTaskDelegation' && call.args[0] === selected.taskId).resolve(undefined);
    await setImmediate();
  }
  api.calls.find(call => call.method === 'findTaskFormRuntime' && call.args[0] === 'task-b').resolve({ values: {} });
  await pending[1];
  assert.equal(view.attributes('approval-task-detail')['data-detail-loading'], 'true');
  api.calls.find(call => call.method === 'findTaskFormRuntime' && call.args[0] === 'task-a').resolve({ values: {} });
  await pending[0];
  const result = view.attributes('approval-task-detail');
  assert.equal(result['data-detail-loading'], 'false');
  assert.equal(result['data-task-id'], 'task-b');
  assert.equal(result['data-timeline-loaded'], 'true');
  assert.equal(result['data-form-loaded'], 'false');
});
