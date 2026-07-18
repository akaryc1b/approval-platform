import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export interface PendingTaskItem {
  amount: number;
  businessKey: string;
  definitionKey: string;
  initiatorId: string;
  instanceId: string;
  purchaseOrderReference: string;
  supplier: string;
  taskCreatedAt: string;
  taskDefinitionKey: string;
  taskId: string;
  taskName: string;
  taskUpdatedAt: string;
}

export interface PendingTaskPage {
  hasMore: boolean;
  items: PendingTaskItem[];
  limit: number;
  offset: number;
  total: number;
}

export interface TransferCandidate {
  displayName: string;
  userId: string;
}

export interface PendingTaskDetails {
  amount: number;
  attachmentIds: string[];
  businessKey: string;
  compilerVersion: string;
  contentHash: string;
  definitionKey: string;
  definitionVersion: number;
  formKey: string;
  formVersion: number;
  initiatorId: string;
  instanceCreatedAt: string;
  instanceId: string;
  instanceUpdatedAt: string;
  purchaseOrderReference: string;
  supplier: string;
  taskCreatedAt: string;
  taskDefinitionKey: string;
  taskId: string;
  taskName: string;
  taskUpdatedAt: string;
  transferCandidates: TransferCandidate[];
}

export interface StartedInstanceItem {
  amount: number;
  attachmentIds: string[];
  businessKey: string;
  createdAt: string;
  currentTaskDefinitionKey?: string;
  currentTaskName?: string;
  definitionKey: string;
  initiatorId: string;
  instanceId: string;
  purchaseOrderReference: string;
  status: 'COMPLETED' | 'REJECTED' | 'RUNNING' | 'WITHDRAWN';
  supplier: string;
  updatedAt: string;
  withdrawable: boolean;
}

export interface StartedInstancePage {
  hasMore: boolean;
  items: StartedInstanceItem[];
  limit: number;
  offset: number;
  total: number;
}

export interface ProcessedTaskItem {
  amount: number;
  businessKey: string;
  completedAt: string;
  definitionKey: string;
  initiatorId: string;
  instanceId: string;
  instanceStatus: 'COMPLETED' | 'REJECTED' | 'RUNNING' | 'WITHDRAWN';
  purchaseOrderReference: string;
  retrievable: boolean;
  supplier: string;
  taskDefinitionKey: string;
  taskId: string;
  taskName: string;
}

export interface ProcessedTaskPage {
  hasMore: boolean;
  items: ProcessedTaskItem[];
  limit: number;
  offset: number;
  total: number;
}

export interface ApprovalTimelineItem {
  action: string;
  aggregateId: string;
  aggregateType: string;
  attributes: Record<string, string>;
  eventId: string;
  occurredAt: string;
  operatorId: string;
  requestId: string;
  traceId?: string;
}

export interface ApprovalTimeline {
  instanceId: string;
  items: ApprovalTimelineItem[];
}

export interface TaskActionResult {
  activeTasks: PendingTaskItem[];
  completedAt: string;
  completedTaskId: string;
  instanceId: string;
  instanceStatus: 'COMPLETED' | 'RUNNING';
}

export interface TransferResult {
  instanceId: string;
  previousAssigneeId: string;
  targetAssigneeId: string;
  taskId: string;
  transferredAt: string;
}

export interface WithdrawResult {
  instanceId: string;
  instanceStatus: 'WITHDRAWN';
  withdrawnAt: string;
}

export interface RetrieveResult {
  activeTasks: PendingTaskItem[];
  completedTaskId: string;
  instanceId: string;
  retrievedAt: string;
}

interface PageParameters {
  keyword?: string;
  limit: number;
  offset: number;
}

interface ApiErrorPayload {
  code?: string;
  error?: string;
  message?: string;
}

type TaskAction = 'approve' | 'reject' | 'resubmit';

function joinUrl(baseUrl: string, path: string) {
  return `${baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
}

function operationId(prefix: string) {
  const randomId = globalThis.crypto?.randomUUID?.() ??
    `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}-${randomId}`;
}

async function parseError(response: Response) {
  let payload: ApiErrorPayload | undefined;
  try {
    payload = (await response.json()) as ApiErrorPayload;
  } catch {
    payload = undefined;
  }
  return payload?.message || payload?.error || payload?.code ||
    `请求失败（${response.status}）`;
}

async function approvalRequest<T>(path: string, init: RequestInit = {}) {
  const runtime = getApprovalRuntimeConfig();
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  headers.set('X-Operator-Id', runtime.operatorId);
  headers.set('X-Tenant-Id', runtime.tenantId);
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  const response = await fetch(joinUrl(runtime.apiBaseUrl, path), {
    ...init,
    credentials: 'same-origin',
    headers,
  });
  if (!response.ok) {
    throw new Error(await parseError(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

function collaborationHeaders(action: string) {
  const requestId = operationId(`web-${action}-request`);
  return {
    'Idempotency-Key': operationId(`web-${action}`),
    'X-Request-Id': requestId,
    'X-Trace-Id': requestId,
  };
}

function pageQuery(parameters: PageParameters) {
  const query = new URLSearchParams({
    limit: String(parameters.limit),
    offset: String(parameters.offset),
  });
  const keyword = parameters.keyword?.trim();
  if (keyword) {
    query.set('keyword', keyword);
  }
  return query.toString();
}

export function findPendingTasks(parameters: PageParameters) {
  return approvalRequest<PendingTaskPage>(
    `/approval/tasks/pending?${pageQuery(parameters)}`,
  );
}

export function findStartedInstances(parameters: PageParameters) {
  return approvalRequest<StartedInstancePage>(
    `/approval/instances/started?${pageQuery(parameters)}`,
  );
}

export function findProcessedTasks(parameters: PageParameters) {
  return approvalRequest<ProcessedTaskPage>(
    `/approval/tasks/processed?${pageQuery(parameters)}`,
  );
}

export function findPendingTask(taskId: string) {
  return approvalRequest<PendingTaskDetails>(
    `/approval/tasks/pending/${encodeURIComponent(taskId)}`,
  );
}

export function findApprovalTimeline(instanceId: string) {
  return approvalRequest<ApprovalTimeline>(
    `/approval/instances/${encodeURIComponent(instanceId)}/timeline`,
  );
}

function submitTaskAction(taskId: string, action: TaskAction, comment: string) {
  return approvalRequest<TaskActionResult>(
    `/approval/tasks/${encodeURIComponent(taskId)}/${action}`,
    {
      body: JSON.stringify({ comment: comment.trim() || null }),
      headers: collaborationHeaders(action),
      method: 'POST',
    },
  );
}

export function approveTask(taskId: string, comment: string) {
  return submitTaskAction(taskId, 'approve', comment);
}

export function rejectTask(taskId: string, comment: string) {
  return submitTaskAction(taskId, 'reject', comment);
}

export function resubmitTask(taskId: string, comment: string) {
  return submitTaskAction(taskId, 'resubmit', comment);
}

export function transferTask(
  taskId: string,
  targetUserId: string,
  comment: string,
) {
  return approvalRequest<TransferResult>(
    `/approval/tasks/${encodeURIComponent(taskId)}/transfer`,
    {
      body: JSON.stringify({
        comment: comment.trim(),
        targetUserId,
      }),
      headers: collaborationHeaders('transfer'),
      method: 'POST',
    },
  );
}

export function withdrawInstance(instanceId: string, comment: string) {
  return approvalRequest<WithdrawResult>(
    `/approval/instances/${encodeURIComponent(instanceId)}/withdraw`,
    {
      body: JSON.stringify({ comment: comment.trim() || null }),
      headers: collaborationHeaders('withdraw'),
      method: 'POST',
    },
  );
}

export function retrieveTask(taskId: string, comment: string) {
  return approvalRequest<RetrieveResult>(
    `/approval/tasks/${encodeURIComponent(taskId)}/retrieve`,
    {
      body: JSON.stringify({ comment: comment.trim() || null }),
      headers: collaborationHeaders('retrieve'),
      method: 'POST',
    },
  );
}
