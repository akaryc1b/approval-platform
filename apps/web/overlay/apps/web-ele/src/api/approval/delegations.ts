import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export type DelegationScope = 'ALL' | 'DEFINITION';
export type DelegationStatus = 'ACTIVE' | 'REVOKED';
export type AssignmentStatus = 'ACTIVE' | 'CANCELED' | 'COMPLETED' | 'SUPERSEDED';

export interface DelegationRule {
  createdAt: string;
  createdBy: string;
  definitionKey?: string;
  delegateId: string;
  principalId: string;
  reason: string;
  revokeReason?: string;
  revokedAt?: string;
  revokedBy?: string;
  ruleId: string;
  scope: DelegationScope;
  status: DelegationStatus;
  tenantId: string;
  validFrom: string;
  validUntil: string;
  version: number;
}

export interface DelegatedTaskAssignment {
  assignedAt: string;
  assignmentId: string;
  canceledAt?: string;
  completedAt?: string;
  completedBy?: string;
  definitionKey: string;
  delegateAssigneeId: string;
  delegationRuleId: string;
  delegationScope: DelegationScope;
  engineInstanceId: string;
  engineTaskId: string;
  principalAssigneeId: string;
  status: AssignmentStatus;
  supersededAssigneeId?: string;
  supersededAt?: string;
  taskDefinitionKey: string;
  tenantId: string;
  version: number;
}

export interface CreateDelegationPayload {
  definitionKey?: string;
  delegateId: string;
  reason: string;
  scope: DelegationScope;
  validFrom: string;
  validUntil: string;
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

async function request<T>(path: string, init: RequestInit = {}) {
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
  if (response.status === 204) return undefined as T;
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

export function findDelegationRules(includeRevoked = false) {
  return request<DelegationRule[]>(
    `/approval/delegations?includeRevoked=${includeRevoked}`,
  );
}

export function createDelegationRule(payload: CreateDelegationPayload) {
  return request<DelegationRule>('/approval/delegations', {
    body: JSON.stringify(payload),
    headers: commandHeaders('web-delegation-create'),
    method: 'POST',
  });
}

export function revokeDelegationRule(ruleId: string, reason: string) {
  return request<DelegationRule>(
    `/approval/delegations/${encodeURIComponent(ruleId)}/revoke`,
    {
      body: JSON.stringify({ reason: reason.trim() }),
      headers: commandHeaders('web-delegation-revoke'),
      method: 'POST',
    },
  );
}

export function findTaskDelegation(taskId: string) {
  return request<DelegatedTaskAssignment | undefined>(
    `/approval/tasks/${encodeURIComponent(taskId)}/delegation`,
  );
}
