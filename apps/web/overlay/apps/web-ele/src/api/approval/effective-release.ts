import {
  approvalCommandHeaders,
  approvalRequest,
} from '#/api/approval/transport';

export { ApprovalApiError as ApprovalEffectiveReleaseApiError } from '#/api/approval/transport';

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

function changeApprovalEffectiveRelease(
  action: 'activate' | 'rollback',
  definitionKey: string,
  releaseVersion: number,
  expectedRevision: number,
  reason: string,
) {
  return approvalRequest<ApprovalEffectiveReleaseActivationResult>(
    `/approval/version-management/${encodeURIComponent(definitionKey)}/releases/${releaseVersion}/${action}`,
    {
      body: JSON.stringify({ expectedRevision, reason }),
      headers: approvalCommandHeaders(`approval-release-${action}`),
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
