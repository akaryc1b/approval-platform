import type {
  ApprovalIdentityCandidate,
  IdentityReference,
} from '#/api/approval/identities';

import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export type HandoverStatus = 'ACTIVE' | 'REVOKED';

export interface PrincipalHandover {
  connectorKey: string;
  createdAt: string;
  createdBy: string;
  handoverId: string;
  principalId: string;
  principalIdentity: IdentityReference;
  reason: string;
  revokeReason?: string;
  revokedAt?: string;
  revokedBy?: string;
  status: HandoverStatus;
  successorId: string;
  successorIdentity: IdentityReference;
  tenantId: string;
  version: number;
}

export interface CreateHandoverResult {
  handover: PrincipalHandover;
  transferredTaskCount: number;
  transferredTaskIds: string[];
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
  headers.set('X-Approval-Trusted-Permissions', 'approval.management.transfer');
  headers.set('X-Operator-Id', runtime.operatorId);
  headers.set('X-Tenant-Id', runtime.tenantId);
  return headers;
}

async function request<T>(path: string, init: RequestInit = {}) {
  const runtime = getApprovalRuntimeConfig();
  const headers = runtimeHeaders(init.headers);
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const response = await fetch(joinUrl(runtime.apiBaseUrl, path), {
    ...init,
    credentials: 'same-origin',
    headers,
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

export function createEmployeeHandover(
  principal: ApprovalIdentityCandidate,
  successor: ApprovalIdentityCandidate,
  reason: string,
) {
  const runtime = getApprovalRuntimeConfig();
  return request<CreateHandoverResult>('/approval/handovers', {
    body: JSON.stringify({
      connectorKey: runtime.connector,
      principalIdentity: principal.reference,
      reason: reason.trim(),
      successorIdentity: successor.reference,
    }),
    headers: commandHeaders('web-handover-create'),
    method: 'POST',
  });
}

export function findEmployeeHandovers(
  principalId: string,
  includeRevoked = false,
) {
  const query = new URLSearchParams({
    includeRevoked: String(includeRevoked),
    principalId,
  });
  return request<PrincipalHandover[]>(`/approval/handovers?${query.toString()}`);
}

export function revokeEmployeeHandover(handoverId: string, reason: string) {
  return request<PrincipalHandover>(
    `/approval/handovers/${encodeURIComponent(handoverId)}/revoke`,
    {
      body: JSON.stringify({ reason: reason.trim() }),
      headers: commandHeaders('web-handover-revoke'),
      method: 'POST',
    },
  );
}
