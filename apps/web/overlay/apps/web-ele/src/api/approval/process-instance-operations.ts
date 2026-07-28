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

export interface MigrationPlanFilters {
  aggregateStatus?: MigrationAggregateStatus;
  definitionKey?: string;
  limit?: number;
  offset?: number;
  paused?: boolean;
  planStatus?: MigrationPlanStatus;
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
