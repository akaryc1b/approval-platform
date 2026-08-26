import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import {
  evidencePath,
  outputDirectory,
  repositoryRoot,
} from './contract.mjs';

const assignmentSource = 'config/demo/purchase-payment-golden-path.json';
const scenario = JSON.parse(readFileSync(
  resolve(repositoryRoot, assignmentSource),
  'utf8',
));
const governedStages = new Map(
  scenario.expectedWorkflow.map(stage => [stage.taskDefinitionKey, stage]),
);

function governedStage(taskDefinitionKey) {
  const stage = governedStages.get(taskDefinitionKey);
  if (!stage) throw new Error(`missing governed stage ${taskDefinitionKey}`);
  return stage;
}

const managerStage = governedStage('managerApproval');
const financeReviewStage = governedStage('financeReview');
const countersignStage = governedStage('financeCountersign');
const paymentStage = governedStage('paymentConfirmation');
if (managerStage.actorIds.length !== 1
  || financeReviewStage.actorIds.length !== 1
  || countersignStage.actorIds.length !== 2
  || paymentStage.actorIds.length !== 1
  || paymentStage.actorIds[0] !== scenario.assigneeRules.initiatorUserId.value
  || paymentStage.client !== 'wechat') {
  throw new Error('governed PC/H5 to WeChat handoff is invalid');
}

const expectedSteps = [
  {
    actorId: managerStage.actorIds[0],
    client: managerStage.client,
    instanceStatus: 'RUNNING',
    taskDefinitionKey: managerStage.taskDefinitionKey,
  },
  {
    actorId: financeReviewStage.actorIds[0],
    client: financeReviewStage.client,
    instanceStatus: 'RUNNING',
    taskDefinitionKey: financeReviewStage.taskDefinitionKey,
  },
  ...countersignStage.actorIds.map(actorId => ({
    actorId,
    client: countersignStage.client,
    instanceStatus: 'RUNNING',
    taskDefinitionKey: countersignStage.taskDefinitionKey,
  })),
];

const expectedScreenshots = [
  'pc-manager-before.png',
  'pc-manager-after.png',
  'h5-finance-before.png',
  'h5-finance-after.png',
  'h5-countersign-a-before.png',
  'h5-countersign-a-after.png',
  'h5-countersign-b-before.png',
  'h5-countersign-b-after.png',
];

const requiredNonClaims = [
  'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
  'WECHAT_MINI_PROGRAM_RUNTIME_NOT_EXECUTED',
  'PC_H5_WECHAT_RUNTIME_NOT_EXECUTED',
  'BROWSER_COMPATIBILITY_NOT_VERIFIED',
  'ACCESSIBILITY_NOT_VERIFIED',
  'PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED',
  'PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED',
  'QUICK_START_10_MINUTES_NOT_EXECUTED',
];

function sha256File(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

function nonEmpty(value) {
  return typeof value === 'string' && value.trim().length > 0;
}

function verifyStep(step, expected, instanceId) {
  if (step?.actorId !== expected.actorId
    || step?.client !== expected.client
    || step?.taskDefinitionKey !== expected.taskDefinitionKey
    || !nonEmpty(step?.taskId)) {
    throw new Error(`runtime step identity mismatch for ${expected.actorId}`);
  }
  if (step.request?.operatorId !== expected.actorId
    || step.request?.tenantId !== scenario.tenant.id
    || !nonEmpty(step.request?.requestId)
    || !nonEmpty(step.request?.traceId)) {
    throw new Error(`runtime request identity mismatch for ${expected.actorId}`);
  }
  if (step.result?.completedTaskId !== step.taskId
    || step.result?.instanceId !== instanceId
    || step.result?.instanceStatus !== expected.instanceStatus) {
    throw new Error(`runtime action result mismatch for ${expected.actorId}`);
  }
  if (!nonEmpty(step.auditEventId)
    || step.auditRequestId !== step.request.requestId) {
    throw new Error(`runtime audit evidence mismatch for ${expected.actorId}`);
  }
}

export function verifyEvidence() {
  if (!existsSync(evidencePath)) {
    throw new Error('PC/H5 runtime evidence file was not created');
  }
  const evidence = JSON.parse(readFileSync(evidencePath, 'utf8'));
  if (evidence.schemaVersion !== 1
    || evidence.evidenceKind !== 'PC_H5_BROWSER_APPROVAL_HANDOFF_V1'
    || evidence.claim !== 'PC_H5_APPROVAL_HANDOFF_PASSED') {
    throw new Error('runtime evidence identity is invalid');
  }
  if (evidence.businessKey !== scenario.request.businessKey
    || evidence.tenantId !== scenario.tenant.id
    || evidence.instanceOrigin !== 'DETERMINISTIC_BACKEND_SEED') {
    throw new Error('runtime evidence does not match the deterministic scenario');
  }
  if (!nonEmpty(evidence.instanceId)
    || evidence.steps?.length !== expectedSteps.length) {
    throw new Error('runtime evidence does not retain all four browser approval actions');
  }

  evidence.steps.forEach((step, index) => {
    verifyStep(step, expectedSteps[index], evidence.instanceId);
  });
  const processedTaskIds = evidence.steps.map(step => step.taskId);
  if (new Set(processedTaskIds).size !== processedTaskIds.length) {
    throw new Error('runtime processed approval task IDs must all be distinct');
  }

  const countersignTaskIds = evidence.steps
    .filter(step => step.taskDefinitionKey === countersignStage.taskDefinitionKey)
    .map(step => step.taskId)
    .sort();
  if (evidence.countersignStage?.taskDefinitionKey
      !== countersignStage.taskDefinitionKey
    || evidence.countersignStage?.actorIds?.join(',')
      !== countersignStage.actorIds.join(',')
    || evidence.countersignStage?.taskIds?.slice().sort().join(',')
      !== countersignTaskIds.join(',')) {
    throw new Error('runtime evidence did not retain both processed countersign tasks');
  }

  const paymentHandoff = evidence.paymentHandoff;
  if (paymentHandoff?.client !== paymentStage.client
    || paymentHandoff?.actorId !== paymentStage.actorIds[0]
    || paymentHandoff?.taskDefinitionKey !== paymentStage.taskDefinitionKey
    || !nonEmpty(paymentHandoff?.taskId)) {
    throw new Error('runtime evidence did not retain the governed payment handoff');
  }
  if (processedTaskIds.includes(paymentHandoff.taskId)) {
    throw new Error('payment confirmation task ID must be independent');
  }
  if (evidence.finalState?.instanceId !== evidence.instanceId
    || evidence.finalState?.status !== 'RUNNING'
    || evidence.finalState?.currentTaskDefinitionKey
      !== paymentStage.taskDefinitionKey) {
    throw new Error('runtime evidence did not retain the awaiting-payment state');
  }
  if (evidence.assignmentEvidence?.source !== assignmentSource) {
    throw new Error('runtime evidence lost its authoritative assignment source');
  }

  const screenshotNames = (evidence.screenshots || [])
    .map(value => value.file)
    .sort();
  if (screenshotNames.join(',') !== expectedScreenshots.slice().sort().join(',')) {
    throw new Error('runtime evidence does not retain all required screenshots');
  }
  for (const screenshot of evidence.screenshots) {
    const absolute = resolve(outputDirectory, screenshot.file);
    if (!existsSync(absolute)
      || sha256File(absolute) !== screenshot.sha256) {
      throw new Error(`screenshot digest mismatch: ${screenshot.file}`);
    }
  }
  for (const nonClaim of requiredNonClaims) {
    if (!evidence.nonClaims?.includes(nonClaim)) {
      throw new Error(`runtime evidence lost non-claim: ${nonClaim}`);
    }
  }
  return evidence;
}
