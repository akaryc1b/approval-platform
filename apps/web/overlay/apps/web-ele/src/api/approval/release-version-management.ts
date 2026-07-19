import type {
  ApprovalDefinition,
  ApprovalDesignDraft,
  ApprovalPreflightReport,
} from './process-design';

import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export const APPROVAL_TRANSFER_MAX_FILE_BYTES = 2 * 1024 * 1024;

export type ApprovalReleaseDeploymentStatus = 'DEPLOYED' | 'FAILED' | 'PENDING';
export type ApprovalDiffChangeType = 'ADDED' | 'MODIFIED' | 'REMOVED' | 'REORDERED';
export type ApprovalDiffImpact = 'HIGH' | 'LOW' | 'MEDIUM';
export type ApprovalArtifactType = 'APPROVAL_DSL' | 'APPROVAL_RELEASE_PACKAGE';
export type ApprovalDiffSubjectType =
  | 'BPMN'
  | 'COMPILER'
  | 'CONDITION_ROUTE'
  | 'DEFINITION'
  | 'FORM_PACKAGE'
  | 'NODE'
  | 'PARALLEL_BRANCH'
  | 'RELEASE_PACKAGE'
  | 'UI_PERMISSIONS';

export interface ApprovalVersionPage {
  hasMore: boolean;
  limit: number;
  offset: number;
  total: number;
}

export interface ApprovalDefinitionVersionSummary {
  definitionHash: string;
  definitionVersion: number;
  formPackageHash: string;
  formPackageVersion: number;
  publishedAt: string;
  publishedBy: string;
  sourceDraftId: string;
}

export interface ApprovalDefinitionVersionDetail {
  contentHash: string;
  definition: ApprovalDefinition;
  definitionKey: string;
  formPackageHash: string;
  formPackageVersion: number;
  publishedAt: string;
  publishedBy: string;
  sourceDraftId: string;
  tenantId: string;
  version: number;
}

export interface ApprovalReleaseDeploymentSummary {
  attemptCount: number;
  deployedAt?: string;
  deploymentRecordId: string;
  engineDefinitionId?: string;
  engineDeploymentId?: string;
  engineVersion?: number;
  lastErrorCode?: string;
  lastErrorMessage?: string;
  requestedBy: string;
  status: ApprovalReleaseDeploymentStatus;
  updatedAt: string;
}

export interface ApprovalReleaseVersionSummary {
  bpmnHash: string;
  compiledArtifactHash: string;
  compilerVersion: string;
  currentDeployed: boolean;
  currentEffective: boolean;
  definitionHash: string;
  definitionVersion: number;
  deployment?: ApprovalReleaseDeploymentSummary;
  deploymentMetadataHash: string;
  formPackageHash: string;
  formPackageVersion: number;
  formSchemaHash: string;
  formSchemaVersion: number;
  packageHash: string;
  publishedAt: string;
  publishedBy: string;
  releaseVersion: number;
  sourceDraftId: string;
  uiSchemaHash: string;
  uiSchemaVersion: number;
}

export interface ApprovalReleasePackageDetail {
  bpmnArtifact: string;
  bpmnHash: string;
  bpmnResourceName: string;
  compiledArtifactHash: string;
  compilerVersion: string;
  definitionHash: string;
  definitionKey: string;
  definitionVersion: number;
  deploymentMetadataHash: string;
  dmnArtifact?: string;
  dmnHash?: string;
  formHash: string;
  formPackageHash: string;
  formPackageVersion: number;
  formVersion: number;
  packageHash: string;
  publishedAt: string;
  publishedBy: string;
  releaseVersion: number;
  sourceDraftId: string;
  tenantId: string;
  uiSchemaHash: string;
  uiSchemaVersion: number;
}

export interface ApprovalVersionCenter {
  currentDeployedReleaseVersion?: number;
  currentEffectiveReleaseVersion?: number;
  definitionKey: string;
  definitionPage: ApprovalVersionPage;
  definitionVersions: ApprovalDefinitionVersionSummary[];
  latestDefinitionVersion?: number;
  latestPublishedReleaseVersion?: number;
  releasePage: ApprovalVersionPage;
  releaseVersions: ApprovalReleaseVersionSummary[];
  tenantId: string;
}

export interface ApprovalStructuralDiffChange {
  after?: unknown;
  before?: unknown;
  changeType: ApprovalDiffChangeType;
  impact: ApprovalDiffImpact;
  path: string;
  subjectId: string;
  subjectType: ApprovalDiffSubjectType;
}

export interface ApprovalStructuralDiffResult {
  changes: ApprovalStructuralDiffChange[];
  definitionKey: string;
  fromDefinitionVersion: number;
  fromReleaseVersion?: number;
  toDefinitionVersion: number;
  toReleaseVersion?: number;
}

export interface ApprovalDslTransferPayload {
  definition: ApprovalDefinition;
}

export interface ApprovalReleaseTransferPayload {
  bpmnArtifact: string;
  bpmnHash: string;
  bpmnResourceName: string;
  compiledArtifactHash: string;
  compilerVersion: string;
  definition: ApprovalDefinition;
  deploymentMetadataHash: string;
  dmnArtifact?: string;
  dmnHash?: string;
  formSchemaHash: string;
  formSchemaVersion: number;
  releasePackageHash: string;
  uiSchemaHash: string;
  uiSchemaVersion: number;
}

export interface ApprovalArtifactTransferEnvelope {
  artifactType: ApprovalArtifactType;
  definitionHash: string;
  definitionKey: string;
  definitionVersion: number;
  envelopeHash: string;
  exportedAt: string;
  format: 'APPROVAL_DSL_EXPORT_V1' | 'APPROVAL_RELEASE_PACKAGE_EXPORT_V1';
  formatVersion: number;
  formPackageHash: string;
  formPackageVersion: number;
  payload: ApprovalDslTransferPayload | ApprovalReleaseTransferPayload;
  payloadHash: string;
  releaseVersion?: number;
}

export interface ApprovalArtifactImportInput {
  envelope: ApprovalArtifactTransferEnvelope;
  targetDefinitionKey: string;
  targetDefinitionVersion: number;
  targetFormPackageVersion: number;
  targetName: string;
}

export interface ApprovalArtifactImportResult {
  definitionKey: string;
  definitionVersion: number;
  draftId: string;
  formPackageVersion: number;
  revision: number;
  sourceEnvelopeHash: string;
  sourcePayloadHash: string;
  status: 'DRAFT';
}

interface ApprovalReleaseDeployment {
  attemptCount: number;
  createdAt: string;
  deployedAt?: string;
  deploymentRecordId: string;
  definitionKey: string;
  engineDefinitionId?: string;
  engineDeploymentId?: string;
  engineVersion?: number;
  lastErrorCode?: string;
  lastErrorMessage?: string;
  releasePackageHash: string;
  releaseVersion: number;
  requestedBy: string;
  status: ApprovalReleaseDeploymentStatus;
  tenantId: string;
  updatedAt: string;
}

interface ApprovalDeploymentResult {
  deployment: ApprovalReleaseDeployment;
  replayedExistingDeployment: boolean;
}

export interface ApprovalDeploymentPreflightInput {
  definitionKey: string;
  deploymentTarget: string;
  releaseVersion: number;
}

export interface ApprovalReleaseDeploymentInput {
  acknowledgedWarningCodes: string[];
  deploymentTarget: string;
  preflightHash: string;
}

interface ApiErrorPayload {
  code?: string;
  error?: string;
  message?: string;
}

export class ApprovalVersionManagementApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
    this.name = 'ApprovalVersionManagementApiError';
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
    throw new ApprovalVersionManagementApiError(
      payload?.message || payload?.error || payload?.code ||
        `请求失败（${response.status}）`,
      response.status,
      payload?.code,
    );
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export function findApprovalVersionCenter(
  definitionKey: string,
  limit = 50,
  offset = 0,
) {
  const query = new URLSearchParams({
    limit: String(limit),
    offset: String(offset),
  });
  return request<ApprovalVersionCenter>(
    `/approval/version-management/${encodeURIComponent(definitionKey)}?${query}`,
  );
}

export function findApprovalDefinitionVersion(
  definitionKey: string,
  definitionVersion: number,
) {
  return request<ApprovalDefinitionVersionDetail>(
    `/approval/definition-versions/${encodeURIComponent(definitionKey)}/${definitionVersion}`,
  );
}

export function findApprovalReleasePackage(
  definitionKey: string,
  releaseVersion: number,
) {
  return request<ApprovalReleasePackageDetail>(
    `/approval/release-packages/${encodeURIComponent(definitionKey)}/${releaseVersion}`,
  );
}

export function exportApprovalDefinitionVersion(
  definitionKey: string,
  definitionVersion: number,
) {
  return request<ApprovalArtifactTransferEnvelope>(
    `/approval/artifact-transfer/definition-exports/${encodeURIComponent(definitionKey)}/${definitionVersion}`,
  );
}

export function exportApprovalReleaseVersion(
  definitionKey: string,
  releaseVersion: number,
) {
  return request<ApprovalArtifactTransferEnvelope>(
    `/approval/artifact-transfer/release-exports/${encodeURIComponent(definitionKey)}/${releaseVersion}`,
  );
}

export function importApprovalArtifact(input: ApprovalArtifactImportInput) {
  return request<ApprovalArtifactImportResult>('/approval/artifact-transfer/imports', {
    body: JSON.stringify(input),
    headers: writeHeaders('approval-artifact-import'),
    method: 'POST',
  });
}

export function diffApprovalDefinitionVersions(
  definitionKey: string,
  fromVersion: number,
  toVersion: number,
) {
  const query = new URLSearchParams({
    fromVersion: String(fromVersion),
    toVersion: String(toVersion),
  });
  return request<ApprovalStructuralDiffResult>(
    `/approval/version-management/${encodeURIComponent(definitionKey)}/definition-diff?${query}`,
  );
}

export function diffApprovalReleaseVersions(
  definitionKey: string,
  fromReleaseVersion: number,
  toReleaseVersion: number,
) {
  const query = new URLSearchParams({
    fromReleaseVersion: String(fromReleaseVersion),
    toReleaseVersion: String(toReleaseVersion),
  });
  return request<ApprovalStructuralDiffResult>(
    `/approval/version-management/${encodeURIComponent(definitionKey)}/release-diff?${query}`,
  );
}

export function preflightApprovalDeployment(input: ApprovalDeploymentPreflightInput) {
  return request<ApprovalPreflightReport>('/approval/preflight/deployment', {
    body: JSON.stringify(input),
    method: 'POST',
  });
}

export function deployApprovalReleasePackage(
  definitionKey: string,
  releaseVersion: number,
  input: ApprovalReleaseDeploymentInput,
) {
  return request<ApprovalDeploymentResult>(
    `/approval/release-packages/${encodeURIComponent(definitionKey)}/${releaseVersion}/deployment`,
    {
      body: JSON.stringify(input),
      headers: writeHeaders('approval-release-deploy'),
      method: 'POST',
    },
  );
}

export type { ApprovalDesignDraft };
