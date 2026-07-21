import type { IdentityReference } from '@/api/approval/identities'

import {
  mobileApprovalMutationHeaders,
  mobileApprovalRequest,
} from '@/api/approval/transport'
import { getApprovalRuntimeConfig } from '@/platform/approval/runtime'

export type CollaborationMode = 'ALL' | 'ANY' | 'VOTE' | 'WEIGHTED'
export type CollaborationStatus = 'ACTIVE' | 'CANCELED' | 'REJECTED' | 'SATISFIED'
export type ParticipantDecision = 'APPROVED' | 'REJECTED'
export type ParticipantStatus =
  | 'APPROVED'
  | 'CANCELED'
  | 'PENDING'
  | 'REJECTED'
  | 'REMOVED'

export interface CollaborationParticipantInput extends IdentityReference {
  weight?: number
}

export interface CollaborationParticipant {
  addedAt: string
  addedBy: string
  canceledAt?: string
  decidedAt?: string
  decisionComment?: string
  identity: IdentityReference
  participantId: string
  participantUserId: string
  policyId: string
  removalReason?: string
  removedAt?: string
  removedBy?: string
  status: ParticipantStatus
  tenantId: string
  version: number
  weight: number
}

export interface CollaborationProgress {
  approvedCount: number
  approvedWeight: number
  eligibleParticipantCount: number
  maximumReachableApprovalCount: number
  maximumReachableApprovalWeight: number
  pendingCount: number
  pendingWeight: number
  rejectedCount: number
  rejectedWeight: number
  totalWeight: number
}

export interface TaskCollaboration {
  approvalThreshold?: number
  approvalWeightThreshold?: number
  createdAt: string
  createdBy: string
  definitionKey: string
  engineInstanceId: string
  engineTaskId: string
  instanceId: string
  mode: CollaborationMode
  ownerAssigneeId: string
  participants: CollaborationParticipant[]
  policyId: string
  progress: CollaborationProgress
  reason: string
  status: CollaborationStatus
  taskDefinitionKey: string
  taskId: string
  taskName: string
  tenantId: string
  terminalAt?: string
  terminalBy?: string
  terminalReason?: string
  version: number
}

export interface PendingCollaborationTask {
  addedAt: string
  approvalThreshold?: number
  approvalWeightThreshold?: number
  definitionKey: string
  instanceId: string
  mode: CollaborationMode
  ownerAssigneeId: string
  participantId: string
  participantWeight: number
  policyId: string
  progress: CollaborationProgress
  reason: string
  taskDefinitionKey: string
  taskId: string
  taskName: string
}

export function findTaskCollaboration(taskId: string) {
  return mobileApprovalRequest<TaskCollaboration | undefined>(
    `/approval/tasks/${encodeURIComponent(taskId)}/collaboration`,
    { allowNotFound: true },
  )
}

export function findPendingCollaborationTasks(limit = 100) {
  return mobileApprovalRequest<PendingCollaborationTask[]>(
    `/approval/collaboration/tasks/pending?limit=${encodeURIComponent(String(limit))}`,
  )
}

export function createTaskCollaboration(
  taskId: string,
  mode: CollaborationMode,
  participants: CollaborationParticipantInput[],
  reason: string,
  approvalThreshold?: number,
  approvalWeightThreshold?: number,
) {
  const runtime = getApprovalRuntimeConfig()
  return mobileApprovalRequest<TaskCollaboration>(
    `/approval/tasks/${encodeURIComponent(taskId)}/collaboration`,
    {
      data: {
        approvalThreshold,
        approvalWeightThreshold,
        connectorKey: runtime.connectorKey,
        mode,
        participants,
        reason: reason.trim(),
      },
      header: mobileApprovalMutationHeaders('mobile-task-collaboration-create'),
      method: 'POST',
    },
  )
}

export function addTaskCollaborators(
  taskId: string,
  participants: CollaborationParticipantInput[],
  reason: string,
) {
  const runtime = getApprovalRuntimeConfig()
  return mobileApprovalRequest<TaskCollaboration>(
    `/approval/tasks/${encodeURIComponent(taskId)}/collaboration/participants`,
    {
      data: {
        connectorKey: runtime.connectorKey,
        participants,
        reason: reason.trim(),
      },
      header: mobileApprovalMutationHeaders('mobile-task-collaboration-add'),
      method: 'POST',
    },
  )
}

export function removeTaskCollaborator(participantId: string, reason: string) {
  return mobileApprovalRequest<TaskCollaboration>(
    `/approval/collaboration/participants/${encodeURIComponent(participantId)}/remove`,
    {
      data: { reason: reason.trim() },
      header: mobileApprovalMutationHeaders('mobile-task-collaboration-remove'),
      method: 'POST',
    },
  )
}

export function decideTaskCollaboration(
  participantId: string,
  decision: ParticipantDecision,
  comment: string,
) {
  return mobileApprovalRequest<TaskCollaboration>(
    `/approval/collaboration/participants/${encodeURIComponent(participantId)}/decide`,
    {
      data: { comment: comment.trim(), decision },
      header: mobileApprovalMutationHeaders('mobile-task-collaboration-decide'),
      method: 'POST',
    },
  )
}
