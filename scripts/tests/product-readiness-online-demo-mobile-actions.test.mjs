import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { createContext, runInContext } from 'node:vm';
import test from 'node:test';

const vue = readFileSync(new URL('../../apps/mobile/overlay/src/pages/task/detail.vue', import.meta.url), 'utf8');
const task = { taskId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', instanceId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  taskDefinitionKey: 'financeReview', transferCandidates: [{ userId: 'reviewer-b', displayName: 'Reviewer B' }] };
const tick = () => new Promise(done => setImmediate(done));
// Execute the real page script; Vue/Uni interfaces and business responses are fixtures.
// Template/CSS assertions are regression contracts, not a browser layout or business acceptance.
function fixture(evaluation = true, writeFailure = false) {
  const reads = []; const writes = []; const dialogs = []; const messages = []; const timers = [];
  const context = createContext({
    ref: value => ({ value }), computed: get => ({ get value() { return get(); } }),
    defineOptions() {}, definePage() {}, onLoad() {}, approvalEvaluationEnabled: () => evaluation,
    findPendingTask: async id => { reads.push(['task', id]); return task; },
    findApprovalTimeline: async id => { reads.push(['timeline', id]); return { items: [] }; },
    findTaskFormRuntime: async id => { reads.push(['form', id]); return { values: { amount: 12500 }, requiredFields: { amount: true } }; },
    findTaskDelegation: async id => { reads.push(['delegation', id]); return undefined; },
    findParticipantTaskSla: async id => { reads.push(['sla', id]); return undefined; },
    approveTask: async (...args) => { writes.push(args); if (writeFailure) throw new Error('uncertain result'); },
    rejectTask: async () => assert.fail('unexpected rejection'), transferTask: async () => assert.fail('unexpected transfer'),
    resubmitFormTask: async () => assert.fail('unexpected resubmission'),
    setTimeout: callback => { timers.push(callback); return timers.length; },
    uni: { showModal: options => dialogs.push(options), showToast: options => messages.push(options), navigateBack() {} },
  });
  const script = stripTypeScriptTypes(vue.match(/<script lang="ts" setup>([\s\S]*?)<\/script>/u)[1])
    .replace(/^import[\s\S]*?from ['"][^'"]+['"][;]?\s*/gmu, '');
  runInContext(script + '\nglobalThis.h = { loadDetails, submitApproval, taskId, details, opinion, loading, loadError, submitting };', context);
  context.h.taskId.value = task.taskId;
  return { h: context.h, context, reads, writes, dialogs, messages, timers };
}

for (const evaluation of [true, false]) for (const revisionTask of [true, false]) {
  test(`H5 assistance mount is bounded: evaluation=${evaluation}, revision=${revisionTask}`, () => {
    const condition = vue.match(/<ApprovalAssistancePanel v-if="([^"]+)"/u)?.[1];
    assert.ok(condition);
    assert.equal(runInContext(condition, createContext({ evaluation, revisionTask })), !evaluation && !revisionTask);
  });
}
test('the deployment switch comes from the existing session module, not query parameters or browser storage', () => {
  assert.match(vue, /import \{ approvalEvaluationEnabled \} from '@\/platform\/approval\/evaluation-session'/u);
  assert.match(vue, /const evaluation = approvalEvaluationEnabled\(\)/u);
  assert.doesNotMatch(vue, /localStorage|sessionStorage|query\?\.evaluation|X-Tenant-Id|X-Operator-Id/u);
});
test('every footer control fills a shrinkable grid cell without the component default minimum width', () => {
  const footer = vue.slice(vue.indexOf('<view class="action-bar">'), vue.indexOf('</template>\n\n<style'));
  const buttons = [...footer.matchAll(/<wd-button\b[^>]*>/gu)].map(([tag]) => tag);
  assert.equal(buttons.length, 5);
  for (const tag of buttons) {
    assert.match(tag, /\bblock\b/u);
    assert.match(tag, /:custom-style="actionButtonStyle"/u);
  }
  const style = runInContext('actionButtonStyle', fixture().context);
  assert.match(style, /width:\s*100%/u); assert.match(style, /min-width:\s*0(?:;|$)/u);
  assert.match(style, /height:\s*44px/u);
  const css = vue.split('<style scoped>')[1];
  assert.match(css, /\.action-bar\s*\{[^}]*display:\s*grid;[^}]*grid-template-columns:\s*minmax\(0, 1fr\) minmax\(0, 3fr\)/u);
  assert.match(css, /\.action-group\s*\{[^}]*display:\s*grid;[^}]*grid-auto-flow:\s*column;[^}]*grid-auto-columns:\s*minmax\(0, 1fr\)/u);
  assert.match(css, /\.action-group\s*\{[^}]*min-width:\s*0/u);
  assert.doesNotMatch(css, /\.action-(?:bar|group)\s*\{[^}]*overflow:\s*hidden/u);
});
test('footer retains all original commands, labels and loading gates rather than hiding the approval', () => {
  for (const [handler, label] of [['submitApproval', '同意'], ['submitRejection', '驳回'],
    ['submitTransfer', '转办'], ['submitResubmission', '重新提交']]) {
    const tag = vue.match(new RegExp(`<wd-button\\b[^>]*@click="${handler}"[^>]*>${label}</wd-button>`, 'u'))?.[0];
    assert.ok(tag, handler); assert.match(tag, /:disabled="!details \|\| loading"/u);
    assert.match(tag, /:loading="submitting"/u);
  }
});
for (const evaluation of [true, false]) {
  test(`existing H5 detail still reads its task/form and tolerates no visible SLA: evaluation=${evaluation}`, async () => {
    const f = fixture(evaluation); await f.h.loadDetails();
    assert.equal(f.h.loadError.value, ''); assert.equal(f.h.loading.value, false);
    assert.equal(f.h.details.value.taskId, task.taskId); assert.equal(f.reads.length, 5);
    assert.equal(f.writes.length, 0); assert.equal(f.dialogs.length, 0);
  });
  test(`the existing confirmation sends one approval with the same task and opinion: evaluation=${evaluation}`, async () => {
    const f = fixture(evaluation); await f.h.loadDetails(); f.h.opinion.value = 'finance checked';
    const work = f.h.submitApproval(); await tick();
    assert.equal(f.dialogs.length, 1); assert.equal(f.writes.length, 0);
    assert.equal(f.dialogs[0].confirmText, '确认同意'); f.dialogs[0].success({ confirm: true }); await work;
    assert.deepEqual(f.writes, [[task.taskId, 'finance checked']]); assert.equal(f.timers.length, 1);
    assert.equal(f.h.submitting.value, false); assert.equal(f.messages[0].icon, 'success');
  });
}
test('canceling the existing confirmation sends no approval and schedules no navigation', async () => {
  const f = fixture(); await f.h.loadDetails(); const work = f.h.submitApproval(); await tick();
  f.dialogs[0].success({ confirm: false }); await work;
  assert.equal(f.writes.length, 0); assert.equal(f.timers.length, 0); assert.equal(f.messages.length, 0);
});
test('a rejected write is neither retried nor represented as successful navigation', async () => {
  const f = fixture(true, true); await f.h.loadDetails(); const work = f.h.submitApproval(); await tick();
  f.dialogs[0].success({ confirm: true }); await work;
  assert.equal(f.writes.length, 1); assert.equal(f.timers.length, 0);
  assert.equal(f.messages.length, 1); assert.equal(f.messages[0].icon, 'none');
});
