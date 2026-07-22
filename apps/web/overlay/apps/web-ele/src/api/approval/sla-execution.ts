import {
  approvalCommandHeaders,
  approvalOperationId,
  approvalRequest,
} from '#/api/approval/transport';

export type SlaExecutionAction =
  | 'AUTOMATIC_ACTION'
  | 'ESCALATION'
  | 'OVERDUE'
  | 'REMINDER';
export type SlaExecutionStatus =
  | 'CANCELLED'
  | 'CLAIMED'
  | 'DEAD'
  | 'READY'
  | 'RETRY_WAIT'
  | 'SUCCEEDED';
export type SlaAttemptResult =
  | 'PERMANENT_FAILURE'
  | 'RETRYABLE_FAILURE'
  | 'SUCCEEDED';

export interface SlaExecutionIntent {
  actionSequence: number;
  actionType: SlaExecutionAction;
  approvalInstanceId: string;
  attemptCount: number;
  availableAt: string;
  calendarId?: string;
  calendarVersion?: number;
  cancelledAt?: string;
  collaborationParticipantId?: string;
  completedAt?: string;
  createdAt: string;
  deadAt?: string;
  idempotencyKey: string;
  intentId: string;
  lastErrorCode?: string;
  lastErrorSummary?: string;
  leaseOwner?: string;
  leaseUntil?: string;
  maxAttempts: number;
  nextAttemptAt: string;
  payload: Record<string, unknown>;
  policyId: string;
  policyVersion: number;
  requestId: string;
  responsibleUserId: string;
  scheduledAt: string;
  slaInstanceId: string;
  sourceIntentId?: string;
  status: SlaExecutionStatus;
  taskId?: string;
  tenantId: string;
  traceId?: string;
  updatedAt: string;
  version: number;
}

export interface SlaExecutionAttempt {
  attemptId: string;
  attemptNumber: number;
  claimedAt: string;
  errorCode?: string;
  errorSummary?: string;
  finishedAt: string;
  intentId: string;
  requestId: string;
  result: SlaAttemptResult;
  startedAt: string;
  tenantId: string;
  traceId?: string;
  workerId: string;
}

export interface SlaExecutionIntentPage {
  hasMore: boolean;
  items: SlaExecutionIntent[];
  limit: number;
  offset: number;
  total: number;
}

export interface SlaExecutionQueueSummary {
  cancelled: number;
  claimed: number;
  dead: number;
  expiredLeases: number;
  ready: number;
  retryWait: number;
  succeeded: number;
}

export interface SlaExecutionIntentDetail {
  attempts: SlaExecutionAttempt[];
  intent: SlaExecutionIntent;
}

export interface SlaExecutionReplayEvidence {
  auditChainReference: string;
  newIntentId: string;
  originalErrorCode?: string;
  originalErrorSummary?: string;
  originalIntentId: string;
  replayId: string;
  replayIdempotencyKey: string;
  replayReason: string;
  requestId: string;
  requestedAt: string;
  requestedBy: string;
  tenantId: string;
  traceId?: string;
}

export interface SlaExecutionReplayResult {
  evidence: SlaExecutionReplayEvidence;
  intent: SlaExecutionIntent;
  replayedExistingRequest: boolean;
}

export interface SlaExecutionFilters {
  actionTypes?: SlaExecutionAction[];
  limit?: number;
  offset?: number;
  requestId?: string;
  responsibleUserId?: string;
  scheduledFrom?: string;
  scheduledTo?: string;
  statuses?: SlaExecutionStatus[];
}

export interface GovernedSlaExecutionOperation {
  idempotencyKey?: string;
  reason: string;
}

function governedHeaders(operation: GovernedSlaExecutionOperation) {
  const reason = operation.reason.trim();
  if (reason.length < 8 || reason.length > 512) {
    throw new Error('重放原因需为 8–512 个字符');
  }
  const headers = approvalCommandHeaders('web-sla-execution-replay');
  headers['Idempotency-Key'] = operation.idempotencyKey?.trim()
    || approvalOperationId('web-sla-execution-replay');
  return {
    ...headers,
    'X-Approval-Operation-Reason': reason,
  };
}

export function findSlaExecutionSummary() {
  return approvalRequest<SlaExecutionQueueSummary>(
    '/approval/management/sla/executions/summary',
  );
}

export function findSlaExecutionIntents(filters: SlaExecutionFilters = {}) {
  const query = new URLSearchParams({
    limit: String(filters.limit ?? 50),
    offset: String(filters.offset ?? 0),
  });
  for (const status of filters.statuses ?? []) query.append('statuses', status);
  for (const action of filters.actionTypes ?? []) query.append('actionTypes', action);
  if (filters.scheduledFrom) query.set('scheduledFrom', filters.scheduledFrom);
  if (filters.scheduledTo) query.set('scheduledTo', filters.scheduledTo);
  if (filters.requestId?.trim()) query.set('requestId', filters.requestId.trim());
  if (filters.responsibleUserId?.trim()) {
    query.set('responsibleUserId', filters.responsibleUserId.trim());
  }
  return approvalRequest<SlaExecutionIntentPage>(
    `/approval/management/sla/executions?${query.toString()}`,
  );
}

export function findSlaExecutionIntent(intentId: string) {
  return approvalRequest<SlaExecutionIntentDetail>(
    `/approval/management/sla/executions/${encodeURIComponent(intentId)}`,
  );
}

export function replaySlaExecutionIntent(
  intentId: string,
  operation: GovernedSlaExecutionOperation,
) {
  return approvalRequest<SlaExecutionReplayResult>(
    `/approval/management/sla/executions/${encodeURIComponent(intentId)}/replay`,
    {
      headers: governedHeaders(operation),
      method: 'POST',
    },
  );
}
