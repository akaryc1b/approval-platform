import { approvalRequest } from '@/api/approval/transport'

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

export function findMigrationOperationsSummary() {
  return approvalRequest<MigrationOperationsSummary>(
    '/approval/management/process-instance-operations/summary',
  )
}

export function findMigrationOperationPlans(limit = 50, offset = 0) {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 200) {
    throw new Error('每页数量必须为 1–200 的整数')
  }
  if (!Number.isSafeInteger(offset) || offset < 0) {
    throw new Error('偏移量必须为非负整数')
  }
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) })
  return approvalRequest<MigrationPlanPage>(
    `/approval/management/process-instance-operations/plans?${query.toString()}`,
  )
}

export function findMigrationOperationInstances(planId: string, limit = 200, offset = 0) {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) })
  return approvalRequest<MigrationInstancePage>(
    `/approval/management/process-instance-operations/plans/${encodeURIComponent(planId)}/instances?${query.toString()}`,
  )
}
