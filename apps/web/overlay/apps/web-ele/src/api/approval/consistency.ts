import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export type ConsistencyCheckStatus = 'COMPLETED' | 'FAILED' | 'RUNNING';
export type ConsistencyCheckType =
  | 'ATTACHMENT_REFERENCE'
  | 'AUDIT_BUSINESS_EVIDENCE'
  | 'COLLABORATION_POLICY'
  | 'COMMENT_REVISION'
  | 'DELEGATION_EVIDENCE'
  | 'HANDOVER_EVIDENCE'
  | 'INSTANCE_TASK_STATE'
  | 'NOTIFICATION_DELIVERY';
export type ConsistencySeverity = 'CRITICAL' | 'ERROR' | 'WARNING';

export interface ConsistencyCheck {
  checkId: string;
  completedAt?: string;
  errorCode?: string;
  errorMessage?: string;
  findingCount: number;
  requestId: string;
  requestedBy: string;
  scope: 'TENANT';
  startedAt: string;
  status: ConsistencyCheckStatus;
  tenantId: string;
  traceId?: string;
  version: number;
}

export interface ConsistencyFinding {
  aggregateId: string;
  aggregateType: string;
  checkId: string;
  checkType: ConsistencyCheckType;
  details: Record<string, string>;
  detectedAt: string;
  findingId: string;
  severity: ConsistencySeverity;
  suggestedAction: string;
}

export interface ConsistencyCheckPage {
  hasMore: boolean;
  items: ConsistencyCheck[];
  limit: number;
  offset: number;
  total: number;
}

export interface ConsistencyFindingPage {
  hasMore: boolean;
  items: ConsistencyFinding[];
  limit: number;
  offset: number;
  total: number;
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
    'approval.management.consistency.read,approval.management.consistency.run',
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
    'Idempotency-Key': operationId(prefix),
    'X-Request-Id': requestId,
    'X-Trace-Id': requestId,
  };
}

export function runConsistencyCheck() {
  return request<ConsistencyCheck>('/approval/management/consistency/checks', {
    headers: commandHeaders('web-consistency-check'),
    method: 'POST',
  });
}

export function findConsistencyChecks(
  status: ConsistencyCheckStatus | undefined,
  limit: number,
  offset: number,
) {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (status) query.set('status', status);
  return request<ConsistencyCheckPage>(
    `/approval/management/consistency/checks?${query.toString()}`,
  );
}

export function findConsistencyFindings(
  checkId: string,
  checkType: ConsistencyCheckType | undefined,
  severity: ConsistencySeverity | undefined,
  limit: number,
  offset: number,
) {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (checkType) query.set('checkType', checkType);
  if (severity) query.set('severity', severity);
  return request<ConsistencyFindingPage>(
    `/approval/management/consistency/checks/${encodeURIComponent(checkId)}/findings?${query.toString()}`,
  );
}
