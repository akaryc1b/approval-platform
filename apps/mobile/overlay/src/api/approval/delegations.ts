import type { IdentityReference } from '@/api/approval/identities'

import {
  mobileApprovalMutationHeaders,
  mobileApprovalRequest,
} from '@/api/approval/transport'

export type DelegationScope = 'ALL' | 'DEFINITION'
export type DelegationStatus = 'ACTIVE' | 'REVOKED'
export type AssignmentStatus = 'ACTIVE' | 'CANCELED' | 'COMPLETED' | 'SUPERSEDED'

export interface DelegationRule {
  createdAt: string
  createdBy: string
  definitionKey?: string
  delegateId: string
  principalId: string
  reason: string
  revokeReason?: string
  revokedAt?: string
  revokedBy?: string
  ruleId: string
  scope: DelegationScope
  status: DelegationStatus
  tenantId: string
  validFrom: string
  validUntil: string
  version: number
}

export interface DelegatedTaskAssignment {
  assignedAt: string
  assignmentId: string
  canceledAt?: string
  completedAt?: string
  completedBy?: string
  definitionKey: string
  delegateAssigneeId: string
  delegationRuleId: string
  delegationScope: DelegationScope
  engineInstanceId: string
  engineTaskId: string
  principalAssigneeId: string
  status: AssignmentStatus
  supersededAssigneeId?: string
  supersededAt?: string
  taskDefinitionKey: string
  tenantId: string
  version: number
}

export interface CreateDelegationPayload {
  connectorKey: string
  definitionKey?: string
  delegateIdentity: IdentityReference
  reason: string
  scope: DelegationScope
  validFrom: string
  validUntil: string
}

export function findDelegationRules(includeRevoked = false) {
  return mobileApprovalRequest<DelegationRule[]>(
    `/approval/delegations?includeRevoked=${includeRevoked}`,
  )
}

export function createDelegationRule(payload: CreateDelegationPayload) {
  return mobileApprovalRequest<DelegationRule>('/approval/delegations', {
    data: payload,
    header: mobileApprovalMutationHeaders('mobile-delegation-create'),
    method: 'POST',
  })
}

export function revokeDelegationRule(ruleId: string, reason: string) {
  return mobileApprovalRequest<DelegationRule>(
    `/approval/delegations/${encodeURIComponent(ruleId)}/revoke`,
    {
      data: { reason: reason.trim() },
      header: mobileApprovalMutationHeaders('mobile-delegation-revoke'),
      method: 'POST',
    },
  )
}

export function findTaskDelegation(taskId: string) {
  return mobileApprovalRequest<DelegatedTaskAssignment | undefined>(
    `/approval/tasks/${encodeURIComponent(taskId)}/delegation`,
  )
}
