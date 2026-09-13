import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { createContext, runInContext } from 'node:vm';
import test from 'node:test';

const vue = readFileSync(new URL('../../apps/web/overlay/apps/web-ele/src/views/approval/workbench/index.vue', import.meta.url), 'utf8');
const task = { taskId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', instanceId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  taskDefinitionKey: 'managerApproval', businessKey: 'EVAL-A', transferCandidates: [] };
const tick = () => new Promise(done => setImmediate(done));
const deferred = () => { let resolve, reject; const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject }; };
// Execute the exact SFC script with deferred API calls and controlled Vue/Element interfaces.
// This reproduces action timing, not a Vue DOM mount, real approval backend or browser E2E.
function fixture(evaluation = true) {
  const details = deferred(); const form = deferred(); const dialogs = []; const writes = []; const messages = [];
  const list = async () => ({ items: [], total: 0, hasMore: false, limit: 10, offset: 0 });
  const context = createContext({
    ref: value => ({ value }), computed: get => ({ get value() { return get(); } }), onMounted() {}, watch() {},
    approvalEvaluationEnabled: () => evaluation,
    findPendingTask: () => details.promise, findApprovalTimeline: async () => ({ items: [] }),
    findTaskDelegation: async () => undefined, findTaskFormRuntime: () => form.promise,
    findPendingTasks: list, findProcessedTasks: list, findStartedInstances: list,
    approveTask: async (...args) => { writes.push(args); },
    rejectTask: async () => assert.fail('unexpected rejection'),
    transferTask: async () => assert.fail('unexpected transfer'),
    resubmitTask: async () => assert.fail('unexpected resubmission'), resubmitFormTask: async () => assert.fail('unexpected resubmission'),
    ElMessage: { error: message => messages.push(message), warning: message => messages.push(message), success: message => messages.push(message) },
    ElMessageBox: { confirm: (...args) => { const dialog = deferred(); dialogs.push({ ...dialog, args }); return dialog.promise; } },
  });
  const script = stripTypeScriptTypes(vue.match(/<script lang="ts" setup>([\s\S]*?)<\/script>/u)[1])
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];\s*/gmu, '');
  runInContext(script + '\nglobalThis.h = { openTask, submitApproval, submitRejection, submitTransfer, submitResubmission, detailLoading, detailError, selectedTask, drawerOpen, submitting, approvalComment, get unavailable() { return typeof drawerActionUnavailable === "undefined" ? false : drawerActionUnavailable.value; } };', context);
  const h = context.h;
  return { h, context, details, form, dialogs, writes, messages,
    async ready() { const work = h.openTask(task); details.resolve(task); form.resolve({ values: {} }); await work; } };
}

test('no drawer, loading detail or failed detail cannot expose enabled approval actions', async () => {
  const f = fixture(); assert.equal(f.h.unavailable, true);
  const opening = f.h.openTask(task); assert.equal(f.h.detailLoading.value, true); assert.equal(f.h.unavailable, true);
  await f.h.submitApproval(); assert.equal(f.dialogs.length, 0);
  f.details.reject(new Error('details unavailable')); await opening;
  assert.equal(f.h.detailLoading.value, false); assert.ok(f.h.detailError.value); assert.equal(f.h.unavailable, true);
  await f.h.submitApproval(); assert.equal(f.dialogs.length, 0); assert.equal(f.writes.length, 0);
});
test('selected task alone is insufficient while its form is still loading', async () => {
  const f = fixture(); const opening = f.h.openTask(task); f.details.resolve(task); await tick();
  assert.equal(f.h.selectedTask.value.taskId, task.taskId); assert.equal(f.h.detailLoading.value, true);
  assert.equal(f.h.unavailable, true); await f.h.submitApproval(); assert.equal(f.dialogs.length, 0);
  f.form.resolve({ values: {} }); await opening; assert.equal(f.h.unavailable, false);
});
test('loaded approval confirmation is single-flight and uses the captured task once', async () => {
  const f = fixture(); await f.ready(); f.h.approvalComment.value = 'approved by evaluator';
  const first = f.h.submitApproval(); await tick(); assert.equal(f.dialogs.length, 1);
  assert.equal(f.h.submitting.value, true); assert.equal(f.h.unavailable, true);
  const second = f.h.submitApproval(); await tick(); assert.equal(f.dialogs.length, 1);
  assert.equal(f.writes.length, 0); f.dialogs[0].resolve(); await Promise.all([first, second]);
  assert.equal(f.writes.length, 1); assert.deepEqual(f.writes[0], [task.taskId, 'approved by evaluator']);
  assert.equal(f.h.submitting.value, false); assert.equal(f.h.drawerOpen.value, false);
});
test('canceling confirmation releases the busy state and performs no command', async () => {
  const f = fixture(); await f.ready(); const work = f.h.submitApproval(); await tick();
  f.dialogs[0].reject(new Error('cancel')); await work;
  assert.equal(f.writes.length, 0); assert.equal(f.h.submitting.value, false); assert.equal(f.h.unavailable, false);
});
for (const change of ['closed', 'replaced', 'loading']) {
  test(`confirmation cannot dispatch a stale task after drawer becomes ${change}`, async () => {
    const f = fixture(); await f.ready(); const work = f.h.submitApproval(); await tick();
    if (change === 'closed') f.h.drawerOpen.value = false;
    if (change === 'replaced') f.h.selectedTask.value = { ...task, taskId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' };
    if (change === 'loading') f.h.detailLoading.value = true;
    f.dialogs[0].resolve(); await work; assert.equal(f.writes.length, 0); assert.equal(f.h.submitting.value, false);
  });
}
test('the visible footer connects all four commands to the readiness gate', () => {
  const buttons = [...vue.matchAll(/<ElButton\b[^>]*@click="(submitApproval|submitRejection|submitTransfer|submitResubmission)"[^>]*>/gu)];
  assert.equal(buttons.length, 4);
  for (const [tag] of buttons) assert.match(tag, /:disabled="drawerActionUnavailable"/u);
});
test('programmatic actions also reject loading state even after a task was selected', async () => {
  const f = fixture(); await f.ready(); f.h.detailLoading.value = true; f.h.approvalComment.value = 'a reason';
  const work = Promise.all([f.h.submitApproval(), f.h.submitRejection(), f.h.submitTransfer(), f.h.submitResubmission()]);
  await tick(); const count = f.dialogs.length;
  f.dialogs.forEach(dialog => dialog.reject(new Error('cancel test dialog'))); await work;
  assert.equal(count, 0); assert.equal(f.writes.length, 0);
});
test('unavailable AI is not mounted in evaluation; ordinary non-revision behavior remains', () => {
  const condition = vue.match(/<ApprovalAssistancePanel v-if="([^"]+)"/u)[1];
  for (const evaluation of [true, false]) for (const revisionTask of [true, false]) {
    assert.equal(runInContext(condition, createContext({ evaluation, revisionTask })), !evaluation && !revisionTask);
  }
});
