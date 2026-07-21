import {
  approvalCommandHeaders,
  approvalRequest,
} from '#/api/approval/transport';

export type ConsistencyCheckStatus = 'COMPLETED' | 'FAILED' | 'RUNNING';
export type ConsistencyCheckType =
  | 'ATTACHMENT_REFERENCE'
  | 'AUDIT_BUSINESS_EVIDENCE'
  | 'COLLABORATION_POLICY'
  | 'COMMENT_REVISION'
  | 'DELEGATION_EVIDENCE'
  | 'HANDOVER_EVIDENCE'
  | 'INSTANCE_TASK_STATE'
  | 'NOTIFICATION_DELIVERY';
export type ConsistencySeverity = 'CRITICAL' | 'ERROR' | 'WARNING';

export interface ConsistencyCheck {
  checkId: string;
  completedAt?: string;
  errorCode?: string;
  errorMessage?: string;
  findingCount: number;
  requestId: string;
  requestedBy: string;
  scope: 'TENANT';
  startedAt: string;
  status: ConsistencyCheckStatus;
  tenantId: string;
  traceId?: string;
  version: number;
}

export interface ConsistencyFinding {
  aggregateId: string;
  aggregateType: string;
  checkId: string;
  checkType: ConsistencyCheckType;
  details: Record<string, string>;
  detectedAt: string;
  findingId: string;
  severity: ConsistencySeverity;
  suggestedAction: string;
}

export interface ConsistencyCheckPage {
  hasMore: boolean;
  items: ConsistencyCheck[];
  limit: number;
  offset: number;
  total: number;
}

export interface ConsistencyFindingPage {
  hasMore: boolean;
  items: ConsistencyFinding[];
  limit: number;
  offset: number;
  total: number;
}

export function runConsistencyCheck() {
  return approvalRequest<ConsistencyCheck>('/approval/management/consistency/checks', {
    headers: approvalCommandHeaders('web-consistency-check'),
    method: 'POST',
  });
}

export function findConsistencyChecks(
  status: ConsistencyCheckStatus | undefined,
  limit: number,
  offset: number,
) {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (status) query.set('status', status);
  return approvalRequest<ConsistencyCheckPage>(
    `/approval/management/consistency/checks?${query.toString()}`,
  );
}

export function findConsistencyFindings(
  checkId: string,
  checkType: ConsistencyCheckType | undefined,
  severity: ConsistencySeverity | undefined,
  limit: number,
  offset: number,
) {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (checkType) query.set('checkType', checkType);
  if (severity) query.set('severity', severity);
  return approvalRequest<ConsistencyFindingPage>(
    `/approval/management/consistency/checks/${encodeURIComponent(checkId)}/findings?${query.toString()}`,
  );
}
