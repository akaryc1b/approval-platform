import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export type ApprovalEffectiveReleaseAction = 'ACTIVATE' | 'ROLLBACK';

export interface ApprovalEffectiveRelease {
  activatedAt: string;
  activatedBy: string;
  bpmnHash: string;
  changeReason: string;
  compiledArtifactHash: string;
  compilerVersion: string;
  definitionHash: string;
  definitionKey: string;
  definitionVersion: number;
  deploymentMetadataHash: string;
  effectiveReleaseVersion: number;
  engineDefinitionId: string;
  engineDeploymentId: string;
  engineVersion: number;
  formPackageHash: string;
  formPackageVersion: number;
  formSchemaHash: string;
  formSchemaVersion: number;
  previousReleaseVersion?: number;
  releasePackageHash: string;
  requestId: string;
  revision: number;
  status: 'ACTIVE';
  tenantId: string;
  traceId?: string;
  uiSchemaHash: string;
  uiSchemaVersion: number;
}

export interface ApprovalEffectiveReleaseActivation {
  action: ApprovalEffectiveReleaseAction;
  activatedAt: string;
  activatedBy: string;
  activationId: string;
  changeReason: string;
  compilerVersion: string;
  definitionKey: string;
  definitionVersion: number;
  engineDefinitionId: string;
  engineDeploymentId: string;
  engineVersion: number;
  formPackageVersion: number;
  previousReleaseVersion?: number;
  releasePackageHash: string;
  releaseVersion: number;
  requestId: string;
  revision: number;
  tenantId: string;
  traceId?: string;
}

export interface ApprovalEffectiveReleaseHistoryPage {
  hasMore: boolean;
  items: ApprovalEffectiveReleaseActivation[];
  limit: number;
  offset: number;
  total: number;
}

export interface ApprovalEffectiveReleaseActivationResult {
  activation?: ApprovalEffectiveReleaseActivation;
  effectiveRelease: ApprovalEffectiveRelease;
  replayedExistingActivation: boolean;
}

interface ApiErrorPayload {
  code?: string;
  error?: string;
  message?: string;
}

export class ApprovalEffectiveReleaseApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
    this.name = 'ApprovalEffectiveReleaseApiError';
  }
}

function operationId(prefix: string) {
  const value = globalThis.crypto?.randomUUID?.() ??
    `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}-${value}`;
}

function writeHeaders(action: string) {
  const requestId = operationId(`web-${action}-request`);
  return {
    'Idempotency-Key': operationId(`web-${action}`),
    'X-Request-Id': requestId,
    'X-Trace-Id': requestId,
  };
}

async function request<T>(path: string, init: RequestInit = {}) {
  const runtime = getApprovalRuntimeConfig();
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  headers.set('X-Operator-Id', runtime.operatorId);
  headers.set('X-Tenant-Id', runtime.tenantId);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${runtime.apiBaseUrl}${path}`, {
    ...init,
    credentials: 'same-origin',
    headers,
  });
  if (!response.ok) {
    let payload: ApiErrorPayload | undefined;
    try {
      payload = (await response.json()) as ApiErrorPayload;
    } catch {
      payload = undefined;
    }
    throw new ApprovalEffectiveReleaseApiError(
      payload?.message || payload?.error || payload?.code ||
        `请求失败（${response.status}）`,
      response.status,
      payload?.code,
    );
  }
  return (await response.json()) as T;
}

export function findApprovalEffectiveRelease(definitionKey: string) {
  return request<ApprovalEffectiveRelease>(
    `/approval/version-management/${encodeURIComponent(definitionKey)}/effective`,
  );
}

export function findApprovalEffectiveReleaseHistory(
  definitionKey: string,
  limit = 100,
  offset = 0,
) {
  const query = new URLSearchParams({
    limit: String(limit),
    offset: String(offset),
  });
  return request<ApprovalEffectiveReleaseHistoryPage>(
    `/approval/version-management/${encodeURIComponent(definitionKey)}/effective/history?${query}`,
  );
}

function changeApprovalEffectiveRelease(
  action: 'activate' | 'rollback',
  definitionKey: string,
  releaseVersion: number,
  expectedRevision: number,
  reason: string,
) {
  return request<ApprovalEffectiveReleaseActivationResult>(
    `/approval/version-management/${encodeURIComponent(definitionKey)}/releases/${releaseVersion}/${action}`,
    {
      body: JSON.stringify({ expectedRevision, reason }),
      headers: writeHeaders(`approval-release-${action}`),
      method: 'POST',
    },
  );
}

export function activateApprovalRelease(
  definitionKey: string,
  releaseVersion: number,
  expectedRevision: number,
  reason: string,
) {
  return changeApprovalEffectiveRelease(
    'activate',
    definitionKey,
    releaseVersion,
    expectedRevision,
    reason,
  );
}

export function rollbackApprovalRelease(
  definitionKey: string,
  releaseVersion: number,
  expectedRevision: number,
  reason: string,
) {
  return changeApprovalEffectiveRelease(
    'rollback',
    definitionKey,
    releaseVersion,
    expectedRevision,
    reason,
  );
}
