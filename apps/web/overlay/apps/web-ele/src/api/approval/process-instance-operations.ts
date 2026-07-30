import { approvalRequest } from '#/api/approval/transport';

export type MigrationPlanStatus =
  | 'AUTHORIZED'
  | 'CANCELLED'
  | 'CONSUMED'
  | 'EXPIRED'
  | 'PROPOSED';

export type MigrationAggregateStatus =
  | 'BOUNDED_EXECUTION_IN_PROGRESS'
  | 'CANARY_IN_PROGRESS'
  | 'CANARY_PENDING'
  | 'COMPLETED_SUCCEEDED'
  | 'COMPLETED_WITH_TERMINAL_FAILURE'
  | 'INVALID_OR_INCOMPLETE_EVIDENCE'
  | 'NOT_STARTED'
  | 'PARTIALLY_COMPLETED'
  | 'PAUSED'
  | 'TERMINAL_FAILURE_PRESENT'
  | 'UNRESOLVED';

export type MigrationFailureClass =
  | 'AMBIGUOUS_UNKNOWN'
  | 'BINDING_CONFLICT'
  | 'ENGINE_REJECTED'
  | 'NONE'
  | 'PRE_DISPATCH_REJECTED'
  | 'RETRYABLE_FAILURE'
  | 'STALE_AUTHORITY'
  | 'TERMINAL_FAILURE'
  | 'UNCLASSIFIED'
  | 'VERIFICATION_MISMATCH';

export type MigrationReconciliationState =
  | 'MANUAL_REVIEW_REQUIRED'
  | 'NONE'
  | 'OPEN'
  | 'RESOLVED_SOURCE'
  | 'RESOLVED_TERMINAL';

export interface MigrationOperationsSummary {
  activePlans: number;
  completedPlans: number;
  consumedPlans: number;
  killSwitchObservedPlans: number;
  latestAggregatedAt?: string;
  observedAt: string;
  pausedPlans: number;
  tenantId: string;
  totalPlans: number;
  unresolvedPlans: number;
}

export interface MigrationPlanItem {
  aggregateRevision?: number;
  aggregateStatus?: MigrationAggregateStatus;
  canaryStatus?: string;
  completedAt?: string;
  completionStatus?: MigrationAggregateStatus;
  consumedAt?: string;
  createdAt: string;
  definitionKey: string;
  exactSuccessCount: number;
  intentId?: string;
  intentStatus?: string;
  killSwitchObserved: boolean;
  latestAggregatedAt?: string;
  orchestrationStatus?: string;
  pauseReason: string;
  paused: boolean;
  planHash: string;
  planId: string;
  planStatus: MigrationPlanStatus;
  selectedInstanceCount: number;
  sourceReleaseVersion: number;
  targetReleaseVersion: number;
  terminalFailedCount: number;
  terminalOutcome?: string;
  unresolvedCount: number;
}

export interface MigrationPlanPage {
  hasMore: boolean;
  items: MigrationPlanItem[];
  limit: number;
  offset: number;
  total: number;
}

export interface MigrationPlanDetail {
  aggregateHash?: string;
  auditReference: string;
  completionEvidenceHash?: string;
  inputEvidenceHash?: string;
  plan: MigrationPlanItem;
  predecessorHash?: string;
  requestId: string;
  sourcePackageHash: string;
  targetPackageHash: string;
  traceId?: string;
}

export interface MigrationInstanceItem {
  approvalInstanceId: string;
  attemptId?: string;
  attemptNumber?: number;
  attemptRevision?: number;
  attemptStatus?: string;
  bindingConflict: boolean;
  canary: boolean;
  engineOutcome?: string;
  exactCompletion: boolean;
  latestEvidenceAt?: string;
  latestEvidenceHash?: string;
  reconciliationDisposition?: string;
  reconciliationStatus?: string;
  selectedInstanceEvidenceHash: string;
  sequenceNo: number;
  verificationClassification?: string;
}

export interface MigrationInstancePage {
  hasMore: boolean;
  items: MigrationInstanceItem[];
  limit: number;
  offset: number;
  planId: string;
  total: number;
}

export interface MigrationPlanDiagnostics {
  aggregateHash?: string;
  aggregateRevision?: number;
  aggregateStatus: string;
  aggregatedAt?: string;
  ambiguousUnknownCount: number;
  bindingConflictCount: number;
  blockedStaleCount: number;
  canaryInstanceId?: string;
  canaryRecordedAt?: string;
  canaryStatus: string;
  claimedCount: number;
  completedAt?: string;
  completionEvidenceHash?: string;
  completionStatus?: string;
  dispatchAllowed?: boolean;
  engineRequestedCount: number;
  exactSuccessCount: number;
  intentId?: string;
  intentStatus?: string;
  killSwitchExpectedRevision?: number;
  killSwitchObservedAt?: string;
  killSwitchObservedRevision?: number;
  killSwitchStatus: string;
  latestOrchestrationEvent?: string;
  latestOrchestrationEventAt?: string;
  manualReviewCount: number;
  observedAt: string;
  orchestrationBatchAttemptCount?: number;
  orchestrationPauseReason?: string;
  orchestrationPhase?: string;
  orchestrationRequestedLimit?: number;
  orchestrationRunRevision?: number;
  orchestrationStartedAt?: string;
  orchestrationStatus: string;
  pendingCount: number;
  planId: string;
  planStatus: string;
  provisionedAttemptCount: number;
  reconcilingCount: number;
  selectedCount: number;
  terminalFailedCount: number;
  unknownCount: number;
  unresolvedCount: number;
  verifyingCount: number;
}

export interface MigrationDiagnosticInstanceItem {
  approvalInstanceId: string;
  attemptId?: string;
  attemptNumber?: number;
  attemptRevision?: number;
  attemptStatus: string;
  bindingEvidenceHash?: string;
  bindingResult: string;
  bindingRevision?: number;
  canary: boolean;
  completionEvidenceHash?: string;
  engineDisposition?: string;
  engineStableCode?: string;
  failureClass: MigrationFailureClass;
  fencingLeaseUntil?: string;
  fencingOwnerReference?: string;
  fencingRevision?: number;
  fencingStatus: string;
  latestEvidenceAt?: string;
  latestEvidenceHash?: string;
  leaseOwnerReference?: string;
  leaseUntil?: string;
  ownershipRevision?: number;
  ownershipStatus: string;
  reconciliationAt?: string;
  reconciliationDisposition?: string;
  reconciliationEvidenceHash?: string;
  reconciliationState: MigrationReconciliationState;
  selectedInstanceEvidenceHash: string;
  sequenceNo: number;
  verificationAt?: string;
  verificationClassification?: string;
  verificationEvidenceHash?: string;
  verificationReadSucceeded?: boolean;
  verificationTruncated?: boolean;
}

export interface MigrationDiagnosticInstancePage {
  hasMore: boolean;
  items: MigrationDiagnosticInstanceItem[];
  page: number;
  pageSize: number;
  planId: string;
  total: number;
  totalPages: number;
}

export interface MigrationTimelineEvent {
  evidenceHash?: string;
  happenedAt: string;
  order: number;
  stage: string;
  state: string;
}

export interface MigrationInstanceDiagnostics {
  instance: MigrationDiagnosticInstanceItem;
  observedAt: string;
  timeline: MigrationTimelineEvent[];
}

export interface MigrationPlanFilters {
  aggregateStatus?: MigrationAggregateStatus;
  definitionKey?: string;
  limit?: number;
  offset?: number;
  paused?: boolean;
  planStatus?: MigrationPlanStatus;
}

export interface MigrationDiagnosticFilters {
  failureClass?: MigrationFailureClass;
  from?: string;
  instanceId?: string;
  page?: number;
  pageSize?: number;
  reconciliationState?: MigrationReconciliationState;
  sort?: 'LATEST_EVIDENCE_ASC' | 'LATEST_EVIDENCE_DESC' | 'SEQUENCE_ASC';
  status?: string;
  to?: string;
}

function boundedPaging(limit = 50, offset = 0) {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 200) {
    throw new Error('每页数量必须为 1–200 的整数');
  }
  if (!Number.isSafeInteger(offset) || offset < 0) {
    throw new Error('偏移量必须为非负整数');
  }
  return { limit, offset };
}

function boundedDiagnosticPaging(page = 1, pageSize = 50) {
  if (!Number.isSafeInteger(page) || page < 1 || page > 10_000) {
    throw new Error('页码必须为 1–10000 的整数');
  }
  if (!Number.isSafeInteger(pageSize) || pageSize < 1 || pageSize > 100) {
    throw new Error('每页数量必须为 1–100 的整数');
  }
  return { page, pageSize };
}

export function findMigrationOperationsSummary() {
  return approvalRequest<MigrationOperationsSummary>(
    '/approval/management/process-instance-operations/summary',
  );
}

export function findMigrationOperationPlans(filters: MigrationPlanFilters = {}) {
  const { limit, offset } = boundedPaging(filters.limit, filters.offset);
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (filters.definitionKey?.trim()) {
    query.set('definitionKey', filters.definitionKey.trim());
  }
  if (filters.planStatus) query.set('planStatus', filters.planStatus);
  if (filters.aggregateStatus) query.set('aggregateStatus', filters.aggregateStatus);
  if (filters.paused !== undefined) query.set('paused', String(filters.paused));
  return approvalRequest<MigrationPlanPage>(
    `/approval/management/process-instance-operations/plans?${query.toString()}`,
  );
}

export function findMigrationOperationPlan(planId: string) {
  return approvalRequest<MigrationPlanDetail>(
    `/approval/management/process-instance-operations/plans/${encodeURIComponent(planId)}`,
  );
}

export function findMigrationOperationInstances(
  planId: string,
  limit = 100,
  offset = 0,
) {
  const bounded = boundedPaging(limit, offset);
  const query = new URLSearchParams({
    limit: String(bounded.limit),
    offset: String(bounded.offset),
  });
  return approvalRequest<MigrationInstancePage>(
    `/approval/management/process-instance-operations/plans/${encodeURIComponent(planId)}/instances?${query.toString()}`,
  );
}

export function findMigrationPlanDiagnostics(planId: string) {
  return approvalRequest<MigrationPlanDiagnostics>(
    `/approval/management/process-instance-operations/plans/${encodeURIComponent(planId)}/diagnostics`,
  );
}

export function findMigrationDiagnosticInstances(
  planId: string,
  filters: MigrationDiagnosticFilters = {},
) {
  const paging = boundedDiagnosticPaging(filters.page, filters.pageSize);
  const query = new URLSearchParams({
    page: String(paging.page),
    pageSize: String(paging.pageSize),
    sort: filters.sort ?? 'SEQUENCE_ASC',
  });
  if (filters.status) query.set('status', filters.status);
  if (filters.instanceId) query.set('instanceId', filters.instanceId);
  if (filters.failureClass) query.set('failureClass', filters.failureClass);
  if (filters.reconciliationState) {
    query.set('reconciliationState', filters.reconciliationState);
  }
  if (filters.from) query.set('from', filters.from);
  if (filters.to) query.set('to', filters.to);
  return approvalRequest<MigrationDiagnosticInstancePage>(
    `/approval/management/process-instance-operations/plans/${encodeURIComponent(planId)}/diagnostics/instances?${query.toString()}`,
  );
}

export function findMigrationInstanceDiagnostics(planId: string, instanceId: string) {
  return approvalRequest<MigrationInstanceDiagnostics>(
    `/approval/management/process-instance-operations/plans/${encodeURIComponent(planId)}/instances/${encodeURIComponent(instanceId)}/diagnostics`,
  );
}
