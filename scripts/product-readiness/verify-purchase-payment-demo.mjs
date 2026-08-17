#!/usr/bin/env node

import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const args = new Set(process.argv.slice(2));
const allowed = new Set(['--help', '--json']);
const unknown = [...args].filter((arg) => !allowed.has(arg));

if (args.has('--help')) {
  console.log(`Usage: node scripts/product-readiness/verify-purchase-payment-demo.mjs [--json]

Validates the deterministic repository contract for the purchase-payment demo.
It does not start the application, seed a runtime database, call a Connector,
or prove PURCHASE_APPROVAL_E2E_PASSED.`);
  process.exit(0);
}
if (unknown.length > 0) {
  console.error(`Unknown option(s): ${unknown.join(', ')}`);
  process.exit(2);
}

function readJson(relativePath) {
  const path = resolve(root, relativePath);
  assert.equal(existsSync(path), true, `missing ${relativePath}`);
  return JSON.parse(readFileSync(path, 'utf8'));
}

function readText(relativePath) {
  const path = resolve(root, relativePath);
  assert.equal(existsSync(path), true, `missing ${relativePath}`);
  return readFileSync(path, 'utf8');
}

function identityByKey(manifest, key) {
  const identity = manifest.identities.find((candidate) => candidate.key === key);
  assert.ok(identity, `missing identity ${key}`);
  return identity;
}

const manifest = readJson('examples/purchase-payment/demo-manifest.json');
const form = readJson('examples/purchase-payment/form.json');
const processDefinition = readJson('examples/purchase-payment/process.json');
const startRequest = readJson('examples/purchase-payment/start-request.json');
const controller = readText(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/PurchasePaymentController.java',
);

assert.equal(manifest.schemaVersion, '1.0');
assert.equal(manifest.claim, 'PURCHASE_PAYMENT_DEMO_CONTRACT');
assert.equal(manifest.runtimeAcceptance, 'PURCHASE_APPROVAL_E2E_NOT_EXECUTED');
assert.match(manifest.tenant.tenantId, /^demo-[a-z0-9-]+$/u);

assert.equal(form.formKey, manifest.process.formKey);
assert.equal(form.version, manifest.process.formVersion);
const requiredFields = new Set(
  form.fields.filter((field) => field.required === true).map((field) => field.key),
);
assert.deepEqual(
  [...requiredFields].sort(),
  ['amount', 'attachments', 'purchaseOrderReference', 'supplier'],
);

assert.equal(processDefinition.definitionKey, manifest.process.definitionKey);
assert.equal(processDefinition.version, manifest.process.version);
assert.equal(processDefinition.startNodeId, 'start');
const nodes = new Map(processDefinition.nodes.map((node) => [node.id, node]));
assert.equal(nodes.get('start')?.next, 'managerApproval');
assert.equal(nodes.get('managerApproval')?.assignee?.resolver, 'INITIATOR_MANAGER');
assert.equal(nodes.get('managerApproval')?.rejectNext, 'initiatorRevision');
assert.equal(nodes.get('financeReview')?.assignee?.variable, 'financeReviewer');
assert.equal(nodes.get('financeCountersign')?.assignee?.variable, 'financeApprovers');
assert.equal(nodes.get('financeCountersign')?.mode, 'ALL');
assert.equal(nodes.get('financeCountersign')?.rejectNext, 'initiatorRevision');
assert.equal(nodes.get('initiatorRevision')?.type, 'HANDLE');
assert.equal(nodes.get('initiatorRevision')?.next, 'managerApproval');
assert.equal(nodes.get('end')?.type, 'END');

const amountRoute = nodes.get('amountCondition')?.routes?.[0];
assert.equal(amountRoute?.condition?.field, 'amount');
assert.equal(amountRoute?.condition?.operator, 'GREATER_THAN_OR_EQUAL');
assert.equal(amountRoute?.condition?.value, manifest.process.highValueThreshold);
assert.equal(amountRoute?.next, 'financeReview');
assert.equal(nodes.get('amountCondition')?.defaultNext, 'financeCountersign');
assert.ok(
  manifest.business.amount >= manifest.process.highValueThreshold,
  'demo request must exercise the high-value finance-review route',
);

for (const field of [
  'businessKey',
  'amount',
  'supplier',
  'purchaseOrderReference',
  'attachmentIds',
]) {
  assert.deepEqual(startRequest[field], manifest.business[field], `start request drift: ${field}`);
}
assert.ok(startRequest.attachmentIds.length >= 1);

const operatorIds = manifest.identities.map((identity) => identity.operatorId);
assert.equal(new Set(operatorIds).size, operatorIds.length, 'operator IDs must be unique');
const externalIds = manifest.identities.map(
  (identity) => `${identity.externalId.source}:${identity.externalId.objectType}:${identity.externalId.value}`,
);
assert.equal(new Set(externalIds).size, externalIds.length, 'external IDs must be unique');

const requester = identityByKey(manifest, 'requester');
const manager = identityByKey(manifest, 'manager');
const financeReviewer = identityByKey(manifest, 'financeReviewer');
const financeApproverA = identityByKey(manifest, 'financeApproverA');
const financeApproverB = identityByKey(manifest, 'financeApproverB');
identityByKey(manifest, 'administrator');

assert.deepEqual(startRequest.assigneeRules.initiatorUserId, requester.externalId);
assert.equal(startRequest.assigneeRules.connectorKey, manifest.organization.connectorKey);
assert.equal(
  startRequest.assigneeRules.financeReviewerRoleCode,
  Object.keys(manifest.organization.roleMembers)[0],
);
assert.equal(
  startRequest.assigneeRules.financeApproverPositionCode,
  Object.keys(manifest.organization.positionMembers)[0],
);
assert.equal(
  startRequest.assigneeRules.maximumFinanceApprovers,
  manifest.organization.maximumFinanceApprovers,
);
assert.equal(manifest.organization.managerByUser[requester.operatorId], manager.operatorId);
assert.deepEqual(
  manifest.organization.roleMembers['finance-reviewer'],
  [financeReviewer.operatorId],
);
assert.deepEqual(
  manifest.organization.positionMembers['finance-countersigner'],
  [financeApproverA.operatorId, financeApproverB.operatorId],
);

assert.deepEqual(
  manifest.process.expectedHappyPath,
  ['managerApproval', 'financeReview', 'financeCountersign', 'end'],
);
assert.equal(manifest.process.completionEventType, 'purchase-payment.completed.v1');

for (const [name, path] of Object.entries(manifest.api)) {
  if (name === 'basePath') continue;
  assert.equal(
    controller.includes(`"${path}"`),
    true,
    `PurchasePaymentController missing ${name} endpoint ${path}`,
  );
}

assert.deepEqual(manifest.evidence, {
  repositoryContract: 'CONTRACT_DEFINED',
  backendStarted: 'NOT_YET_EXECUTED',
  runtimeSeedLoaded: 'NOT_YET_EXECUTED',
  purchaseApprovalE2E: 'NOT_YET_EXECUTED',
  paymentSandboxE2E: 'NOT_YET_EXECUTED',
  pcRuntime: 'NOT_YET_EXECUTED',
  h5Runtime: 'NOT_YET_EXECUTED',
  wechatRuntime: 'NOT_YET_EXECUTED',
});
assert.deepEqual(manifest.safety, {
  productionCredentials: false,
  customerData: false,
  authorizationBypass: false,
  productionConnectorEnabled: false,
  productionPaymentClaim: false,
});

const report = {
  claim: 'PURCHASE_PAYMENT_DEMO_CONTRACT_PASSED',
  scenarioId: manifest.scenarioId,
  tenantId: manifest.tenant.tenantId,
  businessKey: manifest.business.businessKey,
  amount: manifest.business.amount,
  identityCount: manifest.identities.length,
  happyPath: manifest.process.expectedHappyPath,
  runtimeAcceptance: 'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
  paymentSandboxAcceptance: 'PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED',
  crossClientAcceptance: 'CROSS_CLIENT_RUNTIME_NOT_EXECUTED',
};

if (args.has('--json')) {
  console.log(JSON.stringify(report, null, 2));
} else {
  console.log('PURCHASE_PAYMENT_DEMO_CONTRACT_PASSED');
  console.log(`SCENARIO_ID=${report.scenarioId}`);
  console.log(`TENANT_ID=${report.tenantId}`);
  console.log(`BUSINESS_KEY=${report.businessKey}`);
  console.log(`IDENTITY_COUNT=${report.identityCount}`);
  console.log('PURCHASE_APPROVAL_E2E_NOT_EXECUTED');
  console.log('PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED');
  console.log('CROSS_CLIENT_RUNTIME_NOT_EXECUTED');
}
