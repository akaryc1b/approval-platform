import type {
  ApprovalIdentityCandidate,
  IdentityReference,
} from '#/api/approval/identities';

import {
  approvalCommandHeaders,
  approvalRequest,
  approvalRequestWithTrace,
} from '#/api/approval/transport';
import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export type HandoverStatus = 'ACTIVE' | 'REVOKED';

export interface PrincipalHandover {
  connectorKey: string;
  createdAt: string;
  createdBy: string;
  handoverId: string;
  operationRequestId?: string;
  principalId: string;
  principalIdentity: IdentityReference;
  reason: string;
  revokeReason?: string;
  revokedAt?: string;
  revokedBy?: string;
  status: HandoverStatus;
  successorId: string;
  successorIdentity: IdentityReference;
  tenantId: string;
  version: number;
}

export interface CreateHandoverResult {
  handover: PrincipalHandover;
  operationRequestId: string;
  transferredTaskCount: number;
  transferredTaskIds: string[];
}

export async function createEmployeeHandover(
  principal: ApprovalIdentityCandidate,
  successor: ApprovalIdentityCandidate,
  reason: string,
) {
  const runtime = getApprovalRuntimeConfig();
  const result = await approvalRequestWithTrace<Omit<CreateHandoverResult, 'operationRequestId'>>(
    '/approval/handovers',
    {
      body: JSON.stringify({
        connectorKey: runtime.connector,
        principalIdentity: principal.reference,
        reason: reason.trim(),
        successorIdentity: successor.reference,
      }),
      headers: approvalCommandHeaders('web-handover-create'),
      method: 'POST',
    },
  );
  return { ...result.data, operationRequestId: result.requestId };
}

export function findEmployeeHandovers(
  principalId: string,
  includeRevoked = false,
) {
  const query = new URLSearchParams({
    includeRevoked: String(includeRevoked),
    principalId,
  });
  return approvalRequest<PrincipalHandover[]>(`/approval/handovers?${query.toString()}`);
}

export async function revokeEmployeeHandover(handoverId: string, reason: string) {
  const result = await approvalRequestWithTrace<PrincipalHandover>(
    `/approval/handovers/${encodeURIComponent(handoverId)}/revoke`,
    {
      body: JSON.stringify({ reason: reason.trim() }),
      headers: approvalCommandHeaders('web-handover-revoke'),
      method: 'POST',
    },
  );
  return { ...result.data, operationRequestId: result.requestId };
}
