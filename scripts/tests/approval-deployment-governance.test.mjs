import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import vm from 'node:vm';
import test from 'node:test';
const root = '../../apps/web/overlay/apps/web-ele/src/';
const api = readFileSync(new URL(`${root}api/approval/release-version-management.ts`, import.meta.url), 'utf8');
const view = readFileSync(new URL(`${root}views/approval/versions/index.vue`, import.meta.url), 'utf8');
function extract(source, name) {
  const start = source.indexOf(`export function ${name}(`), end = source.indexOf('\nexport ', start + 1);
  assert.ok(start >= 0); return source.slice(start, end < 0 ? undefined : end).replace(/^export /, '');
}
function harness({ cancelReason = false, cancelConfirm = false, deployable = true } = {}) {
  const calls = [], prompts = [], errors = []; let counter = 0;
  const report = { deployable, errors: [], warnings: [], generatedHashes: { bpmnHash: 'bpmn' }, preflightHash: 'preflight', deploymentTarget: 'flowable-primary' };
  const context = vm.createContext({
    approvalCommandHeaders: () => ({ 'Idempotency-Key': `command-${++counter}` }),
    approvalRequest: async (path, options) => { calls.push({ path, ...options }); return path.includes('/preflight/') ? report : { replayedExistingDeployment: false }; },
    center: { value: { definitionKey: 'isolated' } }, operationLoading: { value: false },
    ElMessageBox: {
      async prompt(...args) { prompts.push(args); if (cancelReason) throw 'cancel'; return { value: '  Ａccepted deployment reason  ' }; },
      async confirm() { if (cancelConfirm) throw 'cancel'; },
    },
    ElMessage: { error: value => errors.push(value), success() {} }, message: error => error.message ?? String(error), shortHash: value => value, load: async () => {},
  });
  const start = view.indexOf('async function deploy('), end = view.indexOf('\nasync function openCopy(', start);
  vm.runInContext(stripTypeScriptTypes([
    extract(api, 'approvalDeploymentReason'), extract(api, 'preflightApprovalDeployment'), extract(api, 'deployApprovalReleasePackage'), view.slice(start, end),
  ].join('\n')), context);
  return { context, calls, prompts, errors };
}
test('deployment APIs reject missing/short/control reasons without issuing commands', () => {
  const { context: api, calls } = harness();
  for (const reason of [undefined, '', 'short', 'x'.repeat(513), 'valid reason\ncontrol', '😀'.repeat(8), 'valid reason\ud800']) {
    assert.throws(() => api.preflightApprovalDeployment({}, reason));
    assert.throws(() => api.deployApprovalReleasePackage('isolated', 1, {}, reason));
  }
  assert.equal(calls.length, 0);
});
test('deployment prompts once and binds normalized reason to separate idempotent preflight and deploy', async () => {
  const h = harness(); await h.context.deploy({ releaseVersion: 1, packageHash: 'package' });
  assert.equal(h.prompts.length, 1); assert.equal(h.calls.length, 2);
  for (const call of h.calls) {
    assert.equal(call.method, 'POST');
    assert.equal(call.headers['X-Approval-Operation-Reason'], 'Accepted deployment reason');
    assert.ok(call.headers['Idempotency-Key']);
  }
  assert.notEqual(h.calls[0].headers['Idempotency-Key'], h.calls[1].headers['Idempotency-Key']);
  assert.equal(JSON.parse(h.calls[1].body).preflightHash, 'preflight');
  assert.equal(h.context.operationLoading.value, false);
});
test('canceling deployment reason issues no request and restores loading', async () => {
  const h = harness({ cancelReason: true }); await h.context.deploy({ releaseVersion: 1 });
  assert.equal(h.calls.length, 0); assert.equal(h.errors.length, 0); assert.equal(h.context.operationLoading.value, false);
});
test('canceling preflight confirmation never deploys', async () => {
  const h = harness({ cancelConfirm: true }); await h.context.deploy({ releaseVersion: 1 });
  assert.equal(h.calls.length, 1); assert.match(h.calls[0].path, /preflight/); assert.equal(h.context.operationLoading.value, false);
});
test('blocking preflight never deploys and preserves server gate', async () => {
  const h = harness({ deployable: false }); await h.context.deploy({ releaseVersion: 1 });
  assert.equal(h.calls.length, 1); assert.equal(h.errors.length, 1); assert.equal(h.context.operationLoading.value, false);
});
