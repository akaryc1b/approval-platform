import type { IdentityReference } from '#/api/approval/identities';

import {
  ApprovalApiError,
  approvalCommandHeaders,
  approvalRequest,
} from '#/api/approval/transport';
import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export type CollaborationMode = 'ALL' | 'ANY' | 'VOTE' | 'WEIGHTED';
export type CollaborationStatus = 'ACTIVE' | 'CANCELED' | 'REJECTED' | 'SATISFIED';
export type ParticipantDecision = 'APPROVED' | 'REJECTED';
export type ParticipantStatus =
  | 'APPROVED'
  | 'CANCELED'
  | 'PENDING'
  | 'REJECTED'
  | 'REMOVED';

export interface CollaborationParticipantInput extends IdentityReference {
  weight?: number;
}

export interface CollaborationParticipant {
  addedAt: string;
  addedBy: string;
  canceledAt?: string;
  decidedAt?: string;
  decisionComment?: string;
  identity: IdentityReference;
  participantId: string;
  participantUserId: string;
  policyId: string;
  removalReason?: string;
  removedAt?: string;
  removedBy?: string;
  status: ParticipantStatus;
  tenantId: string;
  version: number;
  weight: number;
}

export interface CollaborationProgress {
  approvedCount: number;
  approvedWeight: number;
  eligibleParticipantCount: number;
  maximumReachableApprovalCount: number;
  maximumReachableApprovalWeight: number;
  pendingCount: number;
  pendingWeight: number;
  rejectedCount: number;
  rejectedWeight: number;
  totalWeight: number;
}

export interface TaskCollaboration {
  approvalThreshold?: number;
  approvalWeightThreshold?: number;
  createdAt: string;
  createdBy: string;
  definitionKey: string;
  engineInstanceId: string;
  engineTaskId: string;
  instanceId: string;
  mode: CollaborationMode;
  ownerAssigneeId: string;
  participants: CollaborationParticipant[];
  policyId: string;
  progress: CollaborationProgress;
  reason: string;
  status: CollaborationStatus;
  taskDefinitionKey: string;
  taskId: string;
  taskName: string;
  tenantId: string;
  terminalAt?: string;
  terminalBy?: string;
  terminalReason?: string;
  version: number;
}

export interface PendingCollaborationTask {
  addedAt: string;
  approvalThreshold?: number;
  approvalWeightThreshold?: number;
  definitionKey: string;
  instanceId: string;
  mode: CollaborationMode;
  ownerAssigneeId: string;
  participantId: string;
  participantWeight: number;
  policyId: string;
  progress: CollaborationProgress;
  reason: string;
  taskDefinitionKey: string;
  taskId: string;
  taskName: string;
}

export async function findTaskCollaboration(taskId: string) {
  try {
    return await approvalRequest<TaskCollaboration>(
      `/approval/tasks/${encodeURIComponent(taskId)}/collaboration`,
    );
  } catch (error) {
    if (error instanceof ApprovalApiError && error.status === 404) return undefined;
    throw error;
  }
}

export function findPendingCollaborationTasks(limit = 100) {
  return approvalRequest<PendingCollaborationTask[]>(
    `/approval/collaboration/tasks/pending?limit=${encodeURIComponent(String(limit))}`,
  );
}

export function createTaskCollaboration(
  taskId: string,
  mode: CollaborationMode,
  participants: CollaborationParticipantInput[],
  reason: string,
  approvalThreshold?: number,
  approvalWeightThreshold?: number,
) {
  const runtime = getApprovalRuntimeConfig();
  return approvalRequest<TaskCollaboration>(
    `/approval/tasks/${encodeURIComponent(taskId)}/collaboration`,
    {
      body: JSON.stringify({
        approvalThreshold,
        approvalWeightThreshold,
        connectorKey: runtime.connector,
        mode,
        participants,
        reason: reason.trim(),
      }),
      headers: approvalCommandHeaders('web-task-collaboration-create'),
      method: 'POST',
    },
  );
}

export function addTaskCollaborators(
  taskId: string,
  participants: CollaborationParticipantInput[],
  reason: string,
) {
  const runtime = getApprovalRuntimeConfig();
  return approvalRequest<TaskCollaboration>(
    `/approval/tasks/${encodeURIComponent(taskId)}/collaboration/participants`,
    {
      body: JSON.stringify({
        connectorKey: runtime.connector,
        participants,
        reason: reason.trim(),
      }),
      headers: approvalCommandHeaders('web-task-collaboration-add'),
      method: 'POST',
    },
  );
}

export function removeTaskCollaborator(participantId: string, reason: string) {
  return approvalRequest<TaskCollaboration>(
    `/approval/collaboration/participants/${encodeURIComponent(participantId)}/remove`,
    {
      body: JSON.stringify({ reason: reason.trim() }),
      headers: approvalCommandHeaders('web-task-collaboration-remove'),
      method: 'POST',
    },
  );
}

export function decideTaskCollaboration(
  participantId: string,
  decision: ParticipantDecision,
  comment: string,
) {
  return approvalRequest<TaskCollaboration>(
    `/approval/collaboration/participants/${encodeURIComponent(participantId)}/decide`,
    {
      body: JSON.stringify({ comment: comment.trim(), decision }),
      headers: approvalCommandHeaders('web-task-collaboration-decide'),
      method: 'POST',
    },
  );
}
