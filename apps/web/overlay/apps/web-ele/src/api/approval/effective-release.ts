import {
  approvalCommandHeaders,
  approvalRequest,
} from '#/api/approval/transport';

export { ApprovalApiError as ApprovalEffectiveReleaseApiError } from '#/api/approval/transport';

export type ApprovalEffectiveReleaseAction = 'ACTIVATE' | 'ROLLBACK';
export type ApprovalProcessReleaseLifecycleState =
  | 'ACTIVE'
  | 'DEPRECATED'
  | 'PUBLISHED'
  | 'RETIRED';
export type ApprovalReleaseLifecycleAction =
  | ApprovalEffectiveReleaseAction
  | 'DEPRECATE'
  | 'RETIRE';

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

export interface ApprovalProcessReleaseLifecycle {
  activatedAt?: null | string;
  definitionKey: string;
  deprecatedAt?: null | string;
  lastTransitionAt: string;
  lastTransitionBy: string;
  lastTransitionReason: string;
  lifecycleState: ApprovalProcessReleaseLifecycleState;
  publishedAt: string;
  publishedBy: string;
  releasePackageHash: string;
  releaseVersion: number;
  retiredAt?: null | string;
  revision: number;
}

export interface ApprovalProcessReleaseLifecyclePage {
  hasMore: boolean;
  items: ApprovalProcessReleaseLifecycle[];
  limit: number;
  offset: number;
  total: number;
}

export interface ApprovalProcessReleaseDispositionResult {
  lifecycle: ApprovalProcessReleaseLifecycle;
  replayedExistingDisposition: boolean;
  runtimeUsageCount: number;
}

export function findApprovalEffectiveRelease(definitionKey: string) {
  return approvalRequest<ApprovalEffectiveRelease>(
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
  return approvalRequest<ApprovalEffectiveReleaseHistoryPage>(
    `/approval/version-management/${encodeURIComponent(definitionKey)}/effective/history?${query}`,
  );
}

export function findApprovalProcessReleaseLifecycles(
  definitionKey: string,
  limit = 100,
  offset = 0,
) {
  const query = new URLSearchParams({
    limit: String(limit),
    offset: String(offset),
  });
  return approvalRequest<ApprovalProcessReleaseLifecyclePage>(
    `/approval/version-management/${encodeURIComponent(definitionKey)}/release-lifecycle?${query}`,
  );
}

function changeApprovalEffectiveRelease(
  action: 'activate' | 'rollback',
  definitionKey: string,
  releaseVersion: number,
  expectedRevision: number,
  reason: string,
) {
  const operationReason = approvalOperationReason(reason);
  return approvalRequest<ApprovalEffectiveReleaseActivationResult>(
    `/approval/version-management/${encodeURIComponent(definitionKey)}/releases/${releaseVersion}/${action}`,
    {
      body: JSON.stringify({ expectedRevision }),
      headers: {
        ...approvalCommandHeaders(`approval-release-${action}`),
        'X-Approval-Operation-Reason': operationReason,
      },
      method: 'POST',
    },
  );
}

function changeApprovalProcessReleaseDisposition(
  action: 'deprecate' | 'retire',
  definitionKey: string,
  releaseVersion: number,
  expectedRevision: number,
  reason: string,
) {
  const operationReason = approvalOperationReason(reason);
  return approvalRequest<ApprovalProcessReleaseDispositionResult>(
    `/approval/version-management/${encodeURIComponent(definitionKey)}/releases/${releaseVersion}/${action}`,
    {
      body: JSON.stringify({ expectedRevision }),
      headers: {
        ...approvalCommandHeaders(`approval-release-${action}`),
        'X-Approval-Operation-Reason': operationReason,
      },
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

export function deprecateApprovalRelease(
  definitionKey: string,
  releaseVersion: number,
  expectedRevision: number,
  reason: string,
) {
  return changeApprovalProcessReleaseDisposition(
    'deprecate',
    definitionKey,
    releaseVersion,
    expectedRevision,
    reason,
  );
}

export function retireApprovalRelease(
  definitionKey: string,
  releaseVersion: number,
  expectedRevision: number,
  reason: string,
) {
  return changeApprovalProcessReleaseDisposition(
    'retire',
    definitionKey,
    releaseVersion,
    expectedRevision,
    reason,
  );
}

function approvalOperationReason(reason: string) {
  const normalized = reason.normalize('NFKC').trim();
  const length = Array.from(normalized).length;
  if (length < 8 || length > 512) {
    throw new Error('操作原因必须包含 8–512 个字符');
  }
  if (/[\u0000-\u001F\u007F-\u009F\u2028\u2029]/u.test(normalized)) {
    throw new Error('操作原因包含不支持的控制字符');
  }
  return normalized;
}
