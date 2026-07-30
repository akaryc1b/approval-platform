import { mobileApprovalRequest } from '@/api/approval/transport'

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
  | 'VERIFICATION_MISMATCH'

export type MigrationReconciliationState =
  | 'MANUAL_REVIEW_REQUIRED'
  | 'NONE'
  | 'OPEN'
  | 'RESOLVED_SOURCE'
  | 'RESOLVED_TERMINAL'

export interface MigrationOperationsSummary {
  activePlans: number
  completedPlans: number
  consumedPlans: number
  killSwitchObservedPlans: number
  latestAggregatedAt?: string
  observedAt: string
  pausedPlans: number
  tenantId: string
  totalPlans: number
  unresolvedPlans: number
}

export interface MigrationPlanItem {
  aggregateRevision?: number
  aggregateStatus?: string
  canaryStatus?: string
  completedAt?: string
  completionStatus?: string
  consumedAt?: string
  createdAt: string
  definitionKey: string
  exactSuccessCount: number
  intentId?: string
  intentStatus?: string
  killSwitchObserved: boolean
  latestAggregatedAt?: string
  orchestrationStatus?: string
  pauseReason: string
  paused: boolean
  planHash: string
  planId: string
  planStatus: string
  selectedInstanceCount: number
  sourceReleaseVersion: number
  targetReleaseVersion: number
  terminalFailedCount: number
  terminalOutcome?: string
  unresolvedCount: number
}

export interface MigrationPlanPage {
  hasMore: boolean
  items: MigrationPlanItem[]
  limit: number
  offset: number
  total: number
}

export interface MigrationInstanceItem {
  approvalInstanceId: string
  attemptNumber?: number
  attemptStatus?: string
  bindingConflict: boolean
  canary: boolean
  exactCompletion: boolean
  latestEvidenceAt?: string
  reconciliationStatus?: string
  sequenceNo: number
  verificationClassification?: string
}

export interface MigrationInstancePage {
  hasMore: boolean
  items: MigrationInstanceItem[]
  limit: number
  offset: number
  planId: string
  total: number
}

export interface MigrationPlanDiagnostics {
  aggregateStatus: string
  ambiguousUnknownCount: number
  bindingConflictCount: number
  canaryInstanceId?: string
  canaryStatus: string
  dispatchAllowed?: boolean
  exactSuccessCount: number
  killSwitchStatus: string
  latestOrchestrationEvent?: string
  manualReviewCount: number
  observedAt: string
  orchestrationPauseReason?: string
  orchestrationPhase?: string
  orchestrationRequestedLimit?: number
  orchestrationStatus: string
  planId: string
  planStatus: string
  reconcilingCount: number
  selectedCount: number
  terminalFailedCount: number
  unknownCount: number
  unresolvedCount: number
}

export interface MigrationDiagnosticInstanceItem {
  approvalInstanceId: string
  attemptNumber?: number
  attemptStatus: string
  bindingResult: string
  canary: boolean
  engineStableCode?: string
  failureClass: MigrationFailureClass
  fencingLeaseUntil?: string
  fencingOwnerReference?: string
  fencingRevision?: number
  fencingStatus: string
  latestEvidenceAt?: string
  leaseOwnerReference?: string
  leaseUntil?: string
  reconciliationAt?: string
  reconciliationDisposition?: string
  reconciliationState: MigrationReconciliationState
  sequenceNo: number
  verificationAt?: string
  verificationClassification?: string
}

export interface MigrationDiagnosticInstancePage {
  hasMore: boolean
  items: MigrationDiagnosticInstanceItem[]
  page: number
  pageSize: number
  planId: string
  total: number
  totalPages: number
}

export interface MigrationTimelineEvent {
  evidenceHash?: string
  happenedAt: string
  order: number
  stage: string
  state: string
}

export interface MigrationInstanceDiagnostics {
  instance: MigrationDiagnosticInstanceItem
  observedAt: string
  timeline: MigrationTimelineEvent[]
}

export interface MigrationDiagnosticFilters {
  failureClass?: MigrationFailureClass
  page?: number
  pageSize?: number
  reconciliationState?: MigrationReconciliationState
  sort?: 'LATEST_EVIDENCE_DESC' | 'SEQUENCE_ASC'
  status?: string
}

function boundedPaging(limit: number, offset: number) {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 200) {
    throw new Error('每页数量必须为 1–200 的整数')
  }
  if (!Number.isSafeInteger(offset) || offset < 0) {
    throw new Error('偏移量必须为非负整数')
  }
  return { limit, offset }
}

function boundedDiagnosticPaging(page = 1, pageSize = 20) {
  if (!Number.isSafeInteger(page) || page < 1 || page > 10_000) {
    throw new Error('页码必须为 1–10000 的整数')
  }
  if (!Number.isSafeInteger(pageSize) || pageSize < 1 || pageSize > 100) {
    throw new Error('每页数量必须为 1–100 的整数')
  }
  return { page, pageSize }
}

export function findMigrationOperationsSummary() {
  return mobileApprovalRequest<MigrationOperationsSummary>(
    '/approval/mobile/process-instance-operations/summary',
  )
}

export function findMigrationOperationPlans(limit = 50, offset = 0) {
  const bounded = boundedPaging(limit, offset)
  const query = new URLSearchParams({
    limit: String(bounded.limit),
    offset: String(bounded.offset),
  })
  return mobileApprovalRequest<MigrationPlanPage>(
    `/approval/mobile/process-instance-operations/plans?${query.toString()}`,
  )
}

export function findMigrationOperationInstances(planId: string, limit = 200, offset = 0) {
  const bounded = boundedPaging(limit, offset)
  const query = new URLSearchParams({
    limit: String(bounded.limit),
    offset: String(bounded.offset),
  })
  return mobileApprovalRequest<MigrationInstancePage>(
    `/approval/mobile/process-instance-operations/plans/${encodeURIComponent(planId)}/instances?${query.toString()}`,
  )
}

export function findMigrationPlanDiagnostics(planId: string) {
  return mobileApprovalRequest<MigrationPlanDiagnostics>(
    `/approval/mobile/process-instance-operations/plans/${encodeURIComponent(planId)}/diagnostics`,
  )
}

export function findMigrationDiagnosticInstances(
  planId: string,
  filters: MigrationDiagnosticFilters = {},
) {
  const paging = boundedDiagnosticPaging(filters.page, filters.pageSize)
  const query = new URLSearchParams({
    page: String(paging.page),
    pageSize: String(paging.pageSize),
    sort: filters.sort ?? 'LATEST_EVIDENCE_DESC',
  })
  if (filters.status) query.set('status', filters.status)
  if (filters.failureClass) query.set('failureClass', filters.failureClass)
  if (filters.reconciliationState) {
    query.set('reconciliationState', filters.reconciliationState)
  }
  return mobileApprovalRequest<MigrationDiagnosticInstancePage>(
    `/approval/mobile/process-instance-operations/plans/${encodeURIComponent(planId)}/diagnostics/instances?${query.toString()}`,
  )
}

export function findMigrationInstanceDiagnostics(planId: string, instanceId: string) {
  return mobileApprovalRequest<MigrationInstanceDiagnostics>(
    `/approval/mobile/process-instance-operations/plans/${encodeURIComponent(planId)}/instances/${encodeURIComponent(instanceId)}/diagnostics`,
  )
}
