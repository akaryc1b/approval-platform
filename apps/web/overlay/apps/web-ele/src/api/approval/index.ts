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

export interface ApproveTaskResult {
  completedAt: string;
  instanceId: string;
  status: 'COMPLETED' | 'RUNNING';
  taskId: string;
}

interface ApiErrorPayload {
  code?: string;
  error?: string;
  message?: string;
}

interface PendingTaskParameters {
  keyword?: string;
  limit: number;
  offset: number;
}

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

export function findPendingTasks(parameters: PendingTaskParameters) {
  const query = new URLSearchParams({
    limit: String(parameters.limit),
    offset: String(parameters.offset),
  });
  const keyword = parameters.keyword?.trim();
  if (keyword) {
    query.set('keyword', keyword);
  }
  return approvalRequest<PendingTaskPage>(
    `/approval/tasks/pending?${query.toString()}`,
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

export function approveTask(taskId: string, comment: string) {
  const requestId = operationId('web-approve-request');
  return approvalRequest<ApproveTaskResult>(
    `/approval/tasks/${encodeURIComponent(taskId)}/approve`,
    {
      body: JSON.stringify({ comment: comment.trim() || null }),
      headers: {
        'Idempotency-Key': operationId('web-approve'),
        'X-Request-Id': requestId,
        'X-Trace-Id': requestId,
      },
      method: 'POST',
    },
  );
}
