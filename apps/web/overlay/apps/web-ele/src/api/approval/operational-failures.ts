import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export type FailureCategory =
  | 'BUSINESS_OUTBOX'
  | 'CONSISTENCY_CHECK'
  | 'NOTIFICATION_DELIVERY';
export type FailureKind = 'BUSINESS_CALLBACK' | 'CONNECTOR' | 'EMAIL' | 'INTERNAL';
export type ReplayOutcome = 'REJECTED' | 'REPLAYED';

export interface OperationalFailure {
  aggregateId?: string;
  aggregateType?: string;
  attemptCount: number;
  category: FailureCategory;
  connectorKey?: string;
  createdAt: string;
  failureKind: FailureKind;
  lastErrorCode?: string;
  lastErrorMessage?: string;
  maxAttempts?: number;
  nextAttemptAt?: string;
  recipientId?: string;
  replayable: boolean;
  responsibility: string;
  sourceId: string;
  status: string;
  updatedAt: string;
}

export interface OperationalFailureAttempt {
  attemptId: string;
  attemptNumber: number;
  completedAt: string;
  errorCode?: string;
  errorMessage?: string;
  nextAttemptAt?: string;
  providerReference?: string;
  responseCode?: number;
  retryable: boolean;
  startedAt: string;
  successful: boolean;
  workerId?: string;
}

export interface OperationalFailurePage {
  hasMore: boolean;
  items: OperationalFailure[];
  limit: number;
  offset: number;
  total: number;
}

export interface ReplayItem {
  category: FailureCategory;
  sourceId: string;
}

export interface ReplayItemResult extends ReplayItem {
  message: string;
  occurredAt: string;
  outcome: ReplayOutcome;
  replacementSourceId?: string;
}

export interface BatchReplayResult {
  completedAt: string;
  items: ReplayItemResult[];
  rejected: number;
  replayed: number;
}

interface ApiErrorPayload {
  code?: string;
  error?: string;
  message?: string;
}

function operationId(prefix: string) {
  const randomId = globalThis.crypto?.randomUUID?.() ??
    `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}-${randomId}`;
}

function joinUrl(baseUrl: string, path: string) {
  return `${baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
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

function runtimeHeaders(init?: HeadersInit) {
  const runtime = getApprovalRuntimeConfig();
  const headers = new Headers(init);
  headers.set('Accept', 'application/json');
  headers.set(
    'X-Approval-Trusted-Permissions',
    [
      'approval.management.operational-failure.read',
      'approval.management.operational-failure.replay',
    ].join(','),
  );
  headers.set('X-Operator-Id', runtime.operatorId);
  headers.set('X-Tenant-Id', runtime.tenantId);
  return headers;
}

async function request<T>(path: string, init: RequestInit = {}) {
  const runtime = getApprovalRuntimeConfig();
  const response = await fetch(joinUrl(runtime.apiBaseUrl, path), {
    ...init,
    credentials: 'same-origin',
    headers: runtimeHeaders(init.headers),
  });
  if (!response.ok) throw new Error(await parseError(response));
  return (await response.json()) as T;
}

function commandHeaders(prefix: string) {
  const requestId = operationId(`${prefix}-request`);
  return {
    'Content-Type': 'application/json',
    'Idempotency-Key': operationId(prefix),
    'X-Request-Id': requestId,
    'X-Trace-Id': requestId,
  };
}

export function findOperationalFailures(
  category: FailureCategory | undefined,
  failureKind: FailureKind | undefined,
  connectorKey: string,
  limit: number,
  offset: number,
) {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (category) query.set('category', category);
  if (failureKind) query.set('failureKind', failureKind);
  if (connectorKey.trim()) query.set('connectorKey', connectorKey.trim());
  return request<OperationalFailurePage>(
    `/approval/management/operational-failures?${query.toString()}`,
  );
}

export function findOperationalFailureAttempts(
  category: FailureCategory,
  sourceId: string,
) {
  return request<OperationalFailureAttempt[]>(
    `/approval/management/operational-failures/${category}/${encodeURIComponent(sourceId)}/attempts`,
  );
}

export function replayOperationalFailure(item: ReplayItem) {
  return request<ReplayItemResult>(
    `/approval/management/operational-failures/${item.category}/${encodeURIComponent(item.sourceId)}/replay`,
    { headers: commandHeaders('web-operational-replay'), method: 'POST' },
  );
}

export function replayOperationalFailureBatch(items: ReplayItem[]) {
  return request<BatchReplayResult>(
    '/approval/management/operational-failures/replay-batch',
    {
      body: JSON.stringify({ items }),
      headers: commandHeaders('web-operational-replay-batch'),
      method: 'POST',
    },
  );
}
