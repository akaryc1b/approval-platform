import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { createContext, runInContext } from 'node:vm';
import test from 'node:test';
import { normalizeEvaluationBusinessRequest } from '../product-readiness/online-demo/evaluation-business-request.mjs';

const root = new URL('../../', import.meta.url);
const scenario = JSON.parse(readFileSync(new URL('config/demo/purchase-payment-golden-path.json', root)));
const helperSource = readFileSync(new URL('apps/mobile/overlay/src/platform/approval/evaluation-purchase.ts', root), 'utf8');
const helper = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(helperSource)).toString('base64'));
const vue = readFileSync(new URL('apps/mobile/overlay/src/pages/initiate/form.vue', root), 'utf8');
const attachment = '00000000-0000-4000-8000-000000000001';
const values = () => ({ ...helper.evaluationPurchaseDefaults(), amount: '12500.00',
  purchaseOrderReference: 'PO-TEST-123', attachments: [attachment] });
const payload = (v = values(), actor = 'demo-employee', key = 'FORM-TEST-123') => helper.prepareEvaluationPurchase(key, v, actor);
const normalize = (data, actor = 'demo-employee') => normalizeEvaluationBusinessRequest({
  method: 'POST', target: '/api/approval/forms/purchase-payment/versions/1/submissions',
  contentType: 'application/json', idempotencyKey: 'evaluation-form-test', body: Buffer.from(JSON.stringify(data)),
}, scenario, actor);

test('H5 form emits the existing exact canonical submission and decimal values pass the real gateway validator', async () => {
  const p = payload();
  assert.deepEqual(p.startParameters, scenario.assigneeRules);
  assert.equal(p.values.amount, Number(scenario.request.amount));
  assert.equal(p.values.supplier, scenario.request.supplier);
  assert.equal(helper.evaluationPurchaseForm.formKey, 'purchase-payment');
  assert.equal(helper.evaluationPurchaseForm.version, 1);
  assert.equal((await normalize(p)).route.name, 'form-start');
});
for (const amount of [12500, '12500', '12500.0', '12500.00']) {
  test(`valid native money representation ${JSON.stringify(amount)} produces the same amount`, async () => {
    const p = payload({ ...values(), amount });
    assert.equal(p.values.amount, 12500); await normalize(p);
  });
}
for (const amount of ['', null, undefined, {}, [], NaN, Infinity, ' 12500 ', '1.25e4', '0x30d4', '012500', '12500.000', 12501]) {
  test(`invalid or noncanonical native amount is rejected: ${String(amount)}`, () => {
    assert.throws(() => payload({ ...values(), amount }));
  });
}
test('only the applicant and bounded canonical IDs, supplier and real uploaded attachment IDs are accepted', () => {
  for (const actor of ['demo-admin', 'demo-manager', '', undefined]) assert.throws(() => payload(values(), actor === undefined ? null : actor));
  for (const attachments of [[], [attachment, attachment], ['temporary/path'], ['file-id'], new Array(5).fill(attachment), null]) {
    assert.throws(() => payload({ ...values(), attachments }));
  }
  for (const key of ['', '/admin', 'x'.repeat(129)]) assert.throws(() => payload(values(), 'demo-employee', key));
  assert.throws(() => payload({ ...values(), supplier: 'external supplier' }));
  assert.throws(() => payload({ ...values(), purchaseOrderReference: '../outside' }));
});
test('new defaults and prepared bodies cannot share mutable attachment or routing state', () => {
  const first = helper.evaluationPurchaseDefaults(); first.attachments.push(attachment);
  assert.deepEqual(helper.evaluationPurchaseDefaults().attachments, []);
  const v = values(); const p = payload(v); v.attachments.push('changed');
  assert.deepEqual(p.values.attachments, [attachment]);
  p.startParameters.initiatorUserId.objectType = 'USER';
  assert.deepEqual(payload().startParameters, scenario.assigneeRules);
});
test('bypassing the form helper still cannot grant altered actor or routing authority at the server', async () => {
  for (const mutate of [p => { p.startParameters.connectorKey = 'standalone'; },
    p => { p.startParameters.initiatorUserId.objectType = 'USER'; },
    p => { p.startParameters.maximumFinanceApprovers = 20; },
    p => { p.values.amount = '12500.00'; }]) {
    const p = payload(); mutate(p); await assert.rejects(normalize(p));
  }
  await assert.rejects(normalize(payload(), 'demo-manager'));
});

// Execute the real SFC script with controlled Vue/Uni interfaces, not a second implementation.
// This does not claim Vue compilation, a browser picker or actual PostgreSQL business execution.
function page(evaluation = true) {
  const calls = []; const dialogs = [];
  let initialized = false; let actor = 'demo-employee'; let resolveInitialization;
  const initialization = new Promise(done => { resolveInitialization = () => { initialized = true; done(); }; });
  const session = { initialize: () => { calls.push('initialize'); return initialization; },
    view: () => { assert.ok(initialized); return { actorId: actor }; } };
  const context = createContext({
    ...helper, ref: value => ({ value }), defineOptions() {}, definePage() {}, onLoad() {},
    approvalEvaluationEnabled: () => evaluation,
    getEvaluationBrowserSession: () => { assert.ok(evaluation); return session; },
    getApprovalRuntimeConfig: () => { calls.push('normal-runtime'); return { connector: 'generic-rest', operatorId: 'normal-operator' }; },
    findStartFormRuntime: async (formKey, version) => {
      if (evaluation) assert.ok(initialized);
      calls.push('form-read');
      return { definition: { formKey, version }, values: {}, fieldPermissions: {}, requiredFields: {}, uiSchema: {} };
    },
    submitForm: async (formKey, version, businessKey, values, startParameters) => {
      const body = JSON.parse(JSON.stringify({ businessKey, values, startParameters }));
      if (evaluation) await normalize(body);
      calls.push({ formKey, version, body }); return { instanceId: attachment };
    },
    uni: { showModal: options => dialogs.push(options), showToast: options => calls.push({ toast: options.title }),
      navigateTo: options => calls.push({ navigate: options.url }), navigateBack() {} },
  });
  const script = stripTypeScriptTypes(vue.match(/<script lang="ts" setup>([\s\S]*?)<\/script>/u)[1])
    .replace(/^import .*$/gmu, '');
  runInContext(script + '\nglobalThis.h = {loadForm, submitDynamicForm, formKey, version, renderer, formValues, businessKey, submitting, errorText};', context);
  const h = context.h; h.formKey.value = 'purchase-payment'; h.version.value = 1;
  h.renderer.value = { validate: () => '' };
  return { h, calls, dialogs, ready: resolveInitialization, actor: value => { actor = value; } };
}
const tick = () => new Promise(done => setImmediate(done));
test('evaluation page initializes its session before runtime reads and template loading', async () => {
  const p = page(); assert.deepEqual(p.calls, []);
  const loading = p.h.loadForm(); assert.deepEqual(p.calls, ['initialize']);
  p.ready(); await loading;
  assert.deepEqual(p.calls, ['initialize', 'form-read']);
  assert.equal(p.h.formValues.value.amount, 12500);
  assert.equal(p.h.formValues.value.supplier, scenario.request.supplier);
});
test('wrong role or wrong template cannot load the evaluation form', async () => {
  const p = page(); p.actor('demo-manager'); p.ready(); await p.h.loadForm();
  assert.equal(p.calls.includes('form-read'), false); assert.ok(p.h.errorText.value);
  const other = page(); other.h.formKey.value = 'other'; other.ready(); await other.h.loadForm();
  assert.deepEqual(other.calls, []); assert.ok(other.h.errorText.value);
});
test('the actual page submits the canonical payload once; confirmation and cancellation are single-flight', async () => {
  const p = page(); p.ready(); await p.h.loadForm(); p.h.formValues.value = values();
  const work = p.h.submitDynamicForm(); await tick();
  assert.equal(p.dialogs.length, 1); assert.equal(p.h.submitting.value, true);
  await p.h.submitDynamicForm(); assert.equal(p.dialogs.length, 1);
  assert.equal(p.calls.some(call => call?.body), false);
  p.dialogs.shift().success({ confirm: true }); await tick();
  assert.equal(p.calls.filter(call => call?.body).length, 1);
  assert.deepEqual(p.calls.find(call => call?.body).body.startParameters, scenario.assigneeRules);
  p.dialogs.shift().success(); await work;
  assert.equal(p.h.submitting.value, false);
  const cancelled = p.h.submitDynamicForm(); await tick(); p.dialogs.shift().success({ confirm: false }); await cancelled;
  assert.equal(p.h.submitting.value, false); assert.equal(p.calls.filter(call => call?.body).length, 1);
});
test('non-evaluation form retains its original runtime, uppercase USER and configurable twenty-person limit', async () => {
  const p = page(false); await p.h.loadForm(); p.h.formValues.value = values();
  const work = p.h.submitDynamicForm(); await tick(); p.dialogs.shift().success({ confirm: true }); await tick();
  const submitted = p.calls.find(call => call?.body).body;
  assert.equal(submitted.startParameters.connectorKey, 'generic-rest');
  assert.equal(submitted.startParameters.initiatorUserId.objectType, 'USER');
  assert.equal(submitted.startParameters.initiatorUserId.value, 'normal-operator');
  assert.equal(submitted.startParameters.maximumFinanceApprovers, 20);
  p.dialogs.shift().success(); await work; assert.equal(p.calls.includes('initialize'), false);
});
test('routing controls are hidden only in evaluation mode; the existing renderer and form API remain', () => {
  assert.match(vue, /<view v-if="!evaluation" class="routing-card">/u);
  assert.match(vue, /<ApprovalFormRenderer/u);
  assert.match(vue, /await submitForm\(/u);
  assert.doesNotMatch(vue, /localStorage|sessionStorage|X-Tenant-Id|X-Operator-Id|fetch\(/u);
});

test('invalid re-entry clears the prior form so a stale template cannot still submit', async () => {
  const p = page(); p.ready(); await p.h.loadForm(); p.h.formValues.value = values();
  p.h.formKey.value = ''; await p.h.loadForm(); await p.h.submitDynamicForm();
  assert.equal(p.dialogs.length, 0); assert.equal(p.calls.some(call => call?.body), false);
});
test('client validation failure releases the single-flight state without a confirmation or mutation', async () => {
  const p = page(); p.ready(); await p.h.loadForm(); p.h.formValues.value = { ...values(), amount: 'invalid' };
  await p.h.submitDynamicForm(); assert.equal(p.h.submitting.value, false);
  assert.equal(p.dialogs.length, 0); assert.equal(p.calls.some(call => call?.body), false);
  assert.ok(p.calls.some(call => call?.toast));
});
