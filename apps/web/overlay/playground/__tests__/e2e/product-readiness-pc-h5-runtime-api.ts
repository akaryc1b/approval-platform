import { createHash } from 'node:crypto';
import { readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import type {
  APIRequestContext,
  Page,
  Response,
} from '@playwright/test';
import { expect } from '@playwright/test';

export const assignmentSource =
  'config/demo/purchase-payment-golden-path.json';
const repositoryRoot = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '../../../../../..',
);
const governedScenario = JSON.parse(readFileSync(
  resolve(repositoryRoot, assignmentSource),
  'utf8',
)) as {
  tenant: { id: string };
  request: { businessKey: string };
  assigneeRules: { initiatorUserId: { value: string } };
  expectedWorkflow: Array<{
    actorIds: string[];
    client: string;
    taskDefinitionKey: string;
  }>;
};

function governedStage(taskDefinitionKey: string) {
  const matches = governedScenario.expectedWorkflow.filter(stage =>
    stage.taskDefinitionKey === taskDefinitionKey);
  if (matches.length !== 1) {
    throw new Error(
      `governed scenario must contain exactly one ${taskDefinitionKey} stage`,
    );
  }
  return matches[0];
}

function governedSingleActor(taskDefinitionKey: string) {
  const stage = governedStage(taskDefinitionKey);
  if (stage.actorIds.length !== 1) {
    throw new Error(`${taskDefinitionKey} must have exactly one governed actor`);
  }
  return stage.actorIds[0];
}

const managerStage = governedStage('managerApproval');
const financeReviewStage = governedStage('financeReview');
const financeCountersignStage = governedStage('financeCountersign');
const paymentConfirmationStage = governedStage('paymentConfirmation');

if (managerStage.client !== 'pc'
  || financeReviewStage.client !== 'h5'
  || financeCountersignStage.client !== 'h5'
  || paymentConfirmationStage.client !== 'wechat') {
  throw new Error('governed scenario client handoff is invalid');
}
if (financeCountersignStage.actorIds.length !== 2) {
  throw new Error('financeCountersign must have exactly two governed actors');
}

export const tenantId = governedScenario.tenant.id;
export const businessKey = governedScenario.request.businessKey;
export const authoritativeTaskKeys = {
  managerApproval: managerStage.taskDefinitionKey,
  financeReview: financeReviewStage.taskDefinitionKey,
  financeCountersign: financeCountersignStage.taskDefinitionKey,
  paymentConfirmation: paymentConfirmationStage.taskDefinitionKey,
} as const;
export const authoritativeActors = {
  initiator: governedScenario.assigneeRules.initiatorUserId.value,
  managerApproval: governedSingleActor(authoritativeTaskKeys.managerApproval),
  financeReview: governedSingleActor(authoritativeTaskKeys.financeReview),
  financeCountersign: [...financeCountersignStage.actorIds] as [string, string],
  paymentConfirmation: governedSingleActor(
    authoritativeTaskKeys.paymentConfirmation,
  ),
} as const;
if (authoritativeActors.initiator !== authoritativeActors.paymentConfirmation) {
  throw new Error('payment confirmation actor must equal the governed initiator');
}

export const backendOrigin = requiredEnvironment(
  'APPROVAL_DEMO_BACKEND_ORIGIN',
);
export const evidenceDirectory = requiredEnvironment(
  'APPROVAL_DEMO_EVIDENCE_DIR',
);
export const pcUrl = requiredEnvironment('APPROVAL_DEMO_PC_URL');
const h5BaseUrl = requiredEnvironment('APPROVAL_DEMO_H5_URL');

const approvalProxyPrefix = '/approval-api';
const pendingPath = '/api/approval/tasks/pending';
const pollIntervalMs = 500;
const pollTimeoutMs = 30_000;
let readSequence = 0;

export interface PendingTask {
  businessKey: string;
  instanceId: string;
  taskDefinitionKey: string;
  taskId: string;
}

export interface StartedInstance {
  businessKey: string;
  currentTaskDefinitionKey?: string;
  instanceId: string;
  status: string;
}

export interface TaskActionResult {
  activeTasks: PendingTask[];
  completedAt: string;
  completedTaskId: string;
  instanceId: string;
  instanceStatus: 'COMPLETED' | 'RUNNING';
}

export interface TimelineItem {
  action: string;
  eventId: string;
  operatorId: string;
  requestId: string;
}

interface PendingResponseExpectation {
  actorId: string;
  processInstanceId?: string;
  taskDefinitionKey: string;
}

function requiredEnvironment(name: string) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function delay(milliseconds: number) {
  return new Promise(resolvePromise => setTimeout(resolvePromise, milliseconds));
}

export function exactApprovalApiPath(url: string, expectedPath: string) {
  try {
    const directPath = new URL(url).pathname === expectedPath;
    const proxiedPath = new URL(url).pathname
      === `${approvalProxyPrefix}${expectedPath}`;
    return directPath || proxiedPath;
  } catch {
    return false;
  }
}

function h5Actors() {
  return [
    authoritativeActors.financeReview,
    ...authoritativeActors.financeCountersign,
  ] as const;
}

export function h5UrlForActor(actorId: string) {
  if (!h5Actors().includes(actorId as ReturnType<typeof h5Actors>[number])) {
    throw new Error(`unsupported H5 runtime actor: ${actorId}`);
  }
  const url = new URL(h5BaseUrl);
  const hashPath = (url.hash || '#/pages/task/list').split('?')[0]
    || '#/pages/task/list';
  url.searchParams.set('demoOperator', actorId);
  url.hash = hashPath;
  return url.toString();
}

export function selectedHeaders(response: Response) {
  const headers = response.request().headers();
  return {
    requestId: headers['x-request-id'],
    traceId: headers['x-trace-id'],
    tenantId: headers['x-tenant-id'],
    operatorId: headers['x-operator-id'],
  };
}

export function pageItems(payload: unknown): PendingTask[] {
  const direct = payload as { items?: PendingTask[] };
  if (Array.isArray(direct?.items)) return direct.items;
  const wrapped = payload as { data?: { items?: PendingTask[] } };
  if (Array.isArray(wrapped?.data?.items)) return wrapped.data.items;
  throw new Error('pending-task response does not contain an items array');
}

function matchingTask(
  tasks: PendingTask[],
  expectation: PendingResponseExpectation,
) {
  return tasks.find(task =>
    task.businessKey === businessKey
      && task.taskDefinitionKey === expectation.taskDefinitionKey
      && (!expectation.processInstanceId
        || task.instanceId === expectation.processInstanceId));
}

export async function pendingResponse(
  page: Page,
  expectation: PendingResponseExpectation,
) {
  const response = await page.waitForResponse(async candidate => {
    const request = candidate.request();
    if (request.method() !== 'GET'
      || !exactApprovalApiPath(candidate.url(), pendingPath)
      || candidate.status() !== 200) {
      return false;
    }
    const headers = request.headers();
    if (headers['x-tenant-id'] !== tenantId
      || headers['x-operator-id'] !== expectation.actorId) {
      return false;
    }
    try {
      return Boolean(matchingTask(
        pageItems(await candidate.json()),
        expectation,
      ));
    } catch {
      return false;
    }
  }, { timeout: 60_000 });
  const task = matchingTask(pageItems(await response.json()), expectation);
  if (!task) {
    throw new Error(
      `${expectation.actorId} response lost ${businessKey}/`
        + `${expectation.taskDefinitionKey}/`
        + `${expectation.processInstanceId ?? '<any-instance>'}`,
    );
  }
  return { response, task };
}

function unwrapObject(payload: unknown) {
  const wrapped = payload as { data?: unknown };
  if (wrapped?.data && typeof wrapped.data === 'object') return wrapped.data;
  return payload;
}

export async function taskActionResult(response: Response) {
  const candidate = unwrapObject(await response.json()) as Partial<TaskActionResult>;
  if (!Array.isArray(candidate.activeTasks)
    || typeof candidate.completedAt !== 'string'
    || typeof candidate.completedTaskId !== 'string'
    || typeof candidate.instanceId !== 'string'
    || (candidate.instanceStatus !== 'RUNNING'
      && candidate.instanceStatus !== 'COMPLETED')) {
    throw new Error(
      `approval response does not match TaskActionResult: ${JSON.stringify(candidate)}`,
    );
  }
  return candidate as TaskActionResult;
}

function demoHeaders(actorId: string, suffix: string) {
  readSequence += 1;
  const requestId = `pc-h5-runtime-${suffix}-${readSequence}`;
  return {
    Accept: 'application/json',
    'Cache-Control': 'no-store',
    'X-Operator-Id': actorId,
    'X-Request-Id': requestId,
    'X-Tenant-Id': tenantId,
    'X-Trace-Id': requestId,
  };
}

export async function pendingForActor(
  request: APIRequestContext,
  actorId: string,
) {
  const query = new URLSearchParams({
    keyword: businessKey,
    limit: '20',
    offset: '0',
  });
  const response = await request.get(
    `${backendOrigin}${pendingPath}?${query}`,
    { headers: demoHeaders(actorId, `${actorId}-pending`) },
  );
  expect(response.status()).toBe(200);
  return pageItems(await response.json())
    .filter(item => item.businessKey === businessKey);
}

export async function pendingAssignments(
  request: APIRequestContext,
  actorIds: readonly string[],
) {
  const values = await Promise.all(actorIds.map(async actorId => ({
    actorId,
    tasks: await pendingForActor(request, actorId),
  })));
  return Object.fromEntries(values.map(value => [value.actorId, value.tasks]));
}

export async function waitForPendingForActor(
  request: APIRequestContext,
  expectation: PendingResponseExpectation,
  timeoutMs = pollTimeoutMs,
) {
  const deadline = Date.now() + timeoutMs;
  let lastTasks: PendingTask[] = [];
  while (Date.now() < deadline) {
    lastTasks = await pendingForActor(request, expectation.actorId);
    const matches = lastTasks.filter(task =>
      task.businessKey === businessKey
        && task.taskDefinitionKey === expectation.taskDefinitionKey
        && (!expectation.processInstanceId
          || task.instanceId === expectation.processInstanceId));
    if (matches.length === 1) return matches[0];
    if (matches.length > 1) {
      throw new Error(
        `multiple ${expectation.taskDefinitionKey} tasks for `
          + `${expectation.actorId}: ${JSON.stringify(matches)}`,
      );
    }
    await delay(pollIntervalMs);
  }
  throw new Error(
    `timed out waiting for real pending task: ${JSON.stringify({
      tenantId,
      businessKey,
      actorId: expectation.actorId,
      taskDefinitionKey: expectation.taskDefinitionKey,
      processInstanceId: expectation.processInstanceId ?? null,
      lastTasks,
    })}`,
  );
}

export async function waitForPendingTaskToDisappear(
  request: APIRequestContext,
  actorId: string,
  taskId: string,
  timeoutMs = pollTimeoutMs,
) {
  const deadline = Date.now() + timeoutMs;
  let lastTasks: PendingTask[] = [];
  while (Date.now() < deadline) {
    lastTasks = await pendingForActor(request, actorId);
    if (!lastTasks.some(task => task.taskId === taskId)) return lastTasks;
    await delay(pollIntervalMs);
  }
  throw new Error(
    `timed out waiting for pending task to disappear: ${JSON.stringify({
      tenantId,
      businessKey,
      actorId,
      taskId,
      lastTasks,
    })}`,
  );
}

export async function timeline(
  request: APIRequestContext,
  instanceId: string,
) {
  const response = await request.get(
    `${backendOrigin}/api/approval/instances/${instanceId}/timeline`,
    { headers: demoHeaders(authoritativeActors.initiator, 'timeline') },
  );
  expect(response.status()).toBe(200);
  const payload = await response.json() as { items?: TimelineItem[] };
  return payload.items || [];
}

export async function startedInstance(request: APIRequestContext) {
  const query = new URLSearchParams({
    keyword: businessKey,
    limit: '20',
    offset: '0',
  });
  const response = await request.get(
    `${backendOrigin}/api/approval/instances/started?${query}`,
    { headers: demoHeaders(authoritativeActors.initiator, 'started') },
  );
  expect(response.status()).toBe(200);
  const payload = await response.json() as { items?: StartedInstance[] };
  const match = (payload.items || [])
    .find(item => item.businessKey === businessKey);
  if (!match) throw new Error(`started instance ${businessKey} is missing`);
  return match;
}

export async function waitForStartedInstance(
  request: APIRequestContext,
  processInstanceId: string,
  taskDefinitionKey: string,
  timeoutMs = pollTimeoutMs,
) {
  const deadline = Date.now() + timeoutMs;
  let lastInstance: StartedInstance | null = null;
  while (Date.now() < deadline) {
    lastInstance = await startedInstance(request);
    if (lastInstance.instanceId === processInstanceId
      && lastInstance.currentTaskDefinitionKey === taskDefinitionKey
      && lastInstance.status === 'RUNNING') {
      return lastInstance;
    }
    await delay(pollIntervalMs);
  }
  throw new Error(
    `timed out waiting for process state: ${JSON.stringify({
      tenantId,
      businessKey,
      processInstanceId,
      taskDefinitionKey,
      lastInstance,
    })}`,
  );
}

export async function waitForCompletedInstance(
  request: APIRequestContext,
  processInstanceId: string,
  timeoutMs = pollTimeoutMs,
) {
  const deadline = Date.now() + timeoutMs;
  let lastInstance: StartedInstance | null = null;
  while (Date.now() < deadline) {
    lastInstance = await startedInstance(request);
    if (lastInstance.instanceId === processInstanceId
      && !lastInstance.currentTaskDefinitionKey
      && lastInstance.status === 'COMPLETED') {
      return lastInstance;
    }
    await delay(pollIntervalMs);
  }
  throw new Error(
    `timed out waiting for completed process state: ${JSON.stringify({
      tenantId,
      businessKey,
      processInstanceId,
      lastInstance,
    })}`,
  );
}

export function screenshotEvidence(file: string) {
  const absolute = resolve(evidenceDirectory, file);
  return {
    file,
    sha256: createHash('sha256')
      .update(readFileSync(absolute))
      .digest('hex'),
  };
}

export function writeEvidence(value: unknown) {
  const target = resolve(
    evidenceDirectory,
    'pc-h5-runtime-evidence.json',
  );
  const temporary = `${target}.tmp`;
  writeFileSync(
    temporary,
    `${JSON.stringify(value, null, 2)}\n`,
    { encoding: 'utf8', mode: 0o600 },
  );
  renameSync(temporary, target);
}
