import type { IdentityReference } from '#/api/approval/identities';

import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export type CollaborationMode = 'ALL' | 'ANY';
export type CollaborationStatus = 'ACTIVE' | 'CANCELED' | 'REJECTED' | 'SATISFIED';
export type ParticipantDecision = 'APPROVED' | 'REJECTED';
export type ParticipantStatus =
  | 'APPROVED'
  | 'CANCELED'
  | 'PENDING'
  | 'REJECTED'
  | 'REMOVED';

export interface CollaborationParticipant {
  addedAt: string;
  addedBy: string;
  canceledAt?: string;
  decidedAt?: string;
  decisionComment?: string;
  identity: IdentityReference;
  participantId: string;
  participantUserId: string;
  policyId: string;
  removalReason?: string;
  removedAt?: string;
  removedBy?: string;
  status: ParticipantStatus;
  tenantId: string;
  version: number;
}

export interface TaskCollaboration {
  createdAt: string;
  createdBy: string;
  definitionKey: string;
  engineInstanceId: string;
  engineTaskId: string;
  instanceId: string;
  mode: CollaborationMode;
  ownerAssigneeId: string;
  participants: CollaborationParticipant[];
  policyId: string;
  reason: string;
  status: CollaborationStatus;
  taskDefinitionKey: string;
  taskId: string;
  taskName: string;
  tenantId: string;
  terminalAt?: string;
  terminalBy?: string;
  terminalReason?: string;
  version: number;
}

export interface PendingCollaborationTask {
  addedAt: string;
  definitionKey: string;
  instanceId: string;
  mode: CollaborationMode;
  ownerAssigneeId: string;
  participantId: string;
  policyId: string;
  reason: string;
  taskDefinitionKey: string;
  taskId: string;
  taskName: string;
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

async function request<T>(path: string, init: RequestInit = {}, allowNotFound = false) {
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
  if (allowNotFound && response.status === 404) return undefined as T;
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

export function findTaskCollaboration(taskId: string) {
  return request<TaskCollaboration | undefined>(
    `/approval/tasks/${encodeURIComponent(taskId)}/collaboration`,
    {},
    true,
  );
}

export function findPendingCollaborationTasks(limit = 100) {
  return request<PendingCollaborationTask[]>(
    `/approval/collaboration/tasks/pending?limit=${encodeURIComponent(String(limit))}`,
  );
}

export function createTaskCollaboration(
  taskId: string,
  mode: CollaborationMode,
  participants: IdentityReference[],
  reason: string,
) {
  const runtime = getApprovalRuntimeConfig();
  return request<TaskCollaboration>(
    `/approval/tasks/${encodeURIComponent(taskId)}/collaboration`,
    {
      body: JSON.stringify({
        connectorKey: runtime.connector,
        mode,
        participants,
        reason: reason.trim(),
      }),
      headers: commandHeaders('web-task-collaboration-create'),
      method: 'POST',
    },
  );
}

export function removeTaskCollaborator(participantId: string, reason: string) {
  return request<TaskCollaboration>(
    `/approval/collaboration/participants/${encodeURIComponent(participantId)}/remove`,
    {
      body: JSON.stringify({ reason: reason.trim() }),
      headers: commandHeaders('web-task-collaboration-remove'),
      method: 'POST',
    },
  );
}

export function decideTaskCollaboration(
  participantId: string,
  decision: ParticipantDecision,
  comment: string,
) {
  return request<TaskCollaboration>(
    `/approval/collaboration/participants/${encodeURIComponent(participantId)}/decide`,
    {
      body: JSON.stringify({ comment: comment.trim(), decision }),
      headers: commandHeaders('web-task-collaboration-decide'),
      method: 'POST',
    },
  );
}
