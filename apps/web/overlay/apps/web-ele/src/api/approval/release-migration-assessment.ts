import {
  approvalCommandHeaders,
  approvalRequest,
} from '#/api/approval/transport';

export type ReleaseMigrationAssessmentStatus =
  | 'BLOCKED'
  | 'NO_IN_FLIGHT'
  | 'PARTIAL'
  | 'READY';

export type ReleaseMigrationInstanceDecision =
  | 'BLOCKED'
  | 'ELIGIBLE'
  | 'TERMINAL_SKIPPED';

export type ReleaseMigrationFindingSeverity = 'BLOCKER' | 'WARNING';

export type ReleaseLifecycleState =
  | 'ACTIVE'
  | 'DEPRECATED'
  | 'DRAFT'
  | 'PUBLISHED'
  | 'RETIRED';

export type ReleaseMigrationInstanceStatus =
  | 'COMPLETED'
  | 'REJECTED'
  | 'RUNNING'
  | 'WITHDRAWN';

export interface ReleaseMigrationDryRunRequest {
  limit?: number;
  offset?: number;
  targetReleaseVersion: number;
}

export interface ReleaseMigrationAssessmentFinding {
  code: string;
  message: string;
  severity: ReleaseMigrationFindingSeverity;
  taskDefinitionKey?: null | string;
}

export interface ReleaseMigrationAssessmentInstance {
  activeTaskDefinitionKeys: string[];
  approvalInstanceId: string;
  bindingEvidenceHash: string;
  businessKey: string;
  decision: ReleaseMigrationInstanceDecision;
  engineInstanceId: string;
  findings: ReleaseMigrationAssessmentFinding[];
  instanceStatus: ReleaseMigrationInstanceStatus;
}

export interface ReleaseMigrationAssessment {
  assessedAt: string;
  assessmentId: string;
  blockedCount: number;
  complete: boolean;
  definitionKey: string;
  detectOnly: boolean;
  eligibleCount: number;
  globalFindings: ReleaseMigrationAssessmentFinding[];
  hasMore: boolean;
  highImpactCount: number;
  instances: ReleaseMigrationAssessmentInstance[];
  limit: number;
  offset: number;
  reportHash: string;
  runningCount: number;
  sourceLifecycleState: ReleaseLifecycleState;
  sourcePackageHash: string;
  sourceReleaseVersion: number;
  status: ReleaseMigrationAssessmentStatus;
  targetLifecycleState: ReleaseLifecycleState;
  targetPackageHash: string;
  targetReleaseVersion: number;
  tenantId: string;
  terminalCount: number;
  total: number;
}

interface ReleaseMigrationAssessmentWireResponse {
  assessedAt: string;
  assessmentId: string;
  blockedCount: number;
  complete: boolean;
  definitionKey: string;
  detectOnly: boolean;
  eligibleCount: number;
  globalFindings: ReleaseMigrationAssessmentFinding[];
  hasMore: boolean;
  highImpactChangeCount: number;
  instances: ReleaseMigrationAssessmentInstance[];
  limit: number;
  offset: number;
  reportHash: string;
  runningCount: number;
  sourceLifecycleState: ReleaseLifecycleState;
  sourceReleasePackageHash: string;
  sourceReleaseVersion: number;
  status: ReleaseMigrationAssessmentStatus;
  targetLifecycleState: ReleaseLifecycleState;
  targetReleasePackageHash: string;
  targetReleaseVersion: number;
  tenantId: string;
  terminalCount: number;
  totalBindingCount: number;
}

function assertPositiveInteger(value: number, name: string) {
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new Error(`${name} 必须为正整数`);
  }
}

function assertPaging(limit: number | undefined, offset: number | undefined) {
  if (limit !== undefined && (!Number.isSafeInteger(limit) || limit < 1 || limit > 100)) {
    throw new Error('每页数量必须为 1–100 的整数');
  }
  if (offset !== undefined && (!Number.isSafeInteger(offset) || offset < 0)) {
    throw new Error('偏移量必须为非负整数');
  }
}

function normalizeReason(value: string) {
  const normalized = value.trim().normalize('NFKC');
  const codePoints = Array.from(normalized);
  if (codePoints.length < 8 || codePoints.length > 512) {
    throw new Error('操作原因需为 8–512 个 Unicode 字符');
  }
  for (const character of codePoints) {
    const codePoint = character.codePointAt(0) ?? 0;
    const isControl = codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f);
    const isUnsupportedSeparator = codePoint === 0x2028 || codePoint === 0x2029;
    const isSurrogate = codePoint >= 0xd800 && codePoint <= 0xdfff;
    if (isControl || isUnsupportedSeparator || isSurrogate) {
      throw new Error('操作原因不能包含控制字符或不受支持的分隔符');
    }
  }
  return normalized;
}

function requestBody(request: ReleaseMigrationDryRunRequest) {
  assertPositiveInteger(request.targetReleaseVersion, '目标发布版本');
  assertPaging(request.limit, request.offset);
  const body: ReleaseMigrationDryRunRequest = {
    targetReleaseVersion: request.targetReleaseVersion,
  };
  if (request.limit !== undefined) body.limit = request.limit;
  if (request.offset !== undefined) body.offset = request.offset;
  return body;
}

function normalizeResponse(
  response: ReleaseMigrationAssessmentWireResponse,
): ReleaseMigrationAssessment {
  return {
    assessedAt: response.assessedAt,
    assessmentId: response.assessmentId,
    blockedCount: response.blockedCount,
    complete: response.complete,
    definitionKey: response.definitionKey,
    detectOnly: response.detectOnly,
    eligibleCount: response.eligibleCount,
    globalFindings: response.globalFindings,
    hasMore: response.hasMore,
    highImpactCount: response.highImpactChangeCount,
    instances: response.instances,
    limit: response.limit,
    offset: response.offset,
    reportHash: response.reportHash,
    runningCount: response.runningCount,
    sourceLifecycleState: response.sourceLifecycleState,
    sourcePackageHash: response.sourceReleasePackageHash,
    sourceReleaseVersion: response.sourceReleaseVersion,
    status: response.status,
    targetLifecycleState: response.targetLifecycleState,
    targetPackageHash: response.targetReleasePackageHash,
    targetReleaseVersion: response.targetReleaseVersion,
    tenantId: response.tenantId,
    terminalCount: response.terminalCount,
    total: response.totalBindingCount,
  };
}

export async function runReleaseMigrationDryRun(
  definitionKey: string,
  sourceReleaseVersion: number,
  request: ReleaseMigrationDryRunRequest,
  reason: string,
) {
  const normalizedDefinitionKey = definitionKey.trim();
  if (!normalizedDefinitionKey) throw new Error('流程定义 Key 不能为空');
  assertPositiveInteger(sourceReleaseVersion, '源发布版本');

  const response = await approvalRequest<ReleaseMigrationAssessmentWireResponse>(
    `/approval/version-management/${encodeURIComponent(normalizedDefinitionKey)}/releases/${sourceReleaseVersion}/migration-dry-run`,
    {
      body: JSON.stringify(requestBody(request)),
      headers: {
        ...approvalCommandHeaders('approval-release-migration-assessment'),
        'X-Approval-Operation-Reason': normalizeReason(reason),
      },
      method: 'POST',
    },
  );
  return normalizeResponse(response);
}
