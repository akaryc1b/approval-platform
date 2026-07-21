import {
  approvalCommandHeaders,
  approvalRequest,
  approvalRequestWithTrace,
} from '#/api/approval/transport';

export type FailureCategory =
  | 'BUSINESS_OUTBOX'
  | 'CONSISTENCY_CHECK'
  | 'NOTIFICATION_DELIVERY';
export type FailureKind = 'BUSINESS_CALLBACK' | 'CONNECTOR' | 'EMAIL' | 'INTERNAL';
export type ReplayOutcome = 'REJECTED' | 'REPLAYED';

export interface OperationalFailure {
  aggregateId?: string;
  aggregateType?: string;
  attemptCount: number;
  category: FailureCategory;
  connectorKey?: string;
  createdAt: string;
  failureKind: FailureKind;
  lastErrorCode?: string;
  lastErrorMessage?: string;
  maxAttempts?: number;
  nextAttemptAt?: string;
  recipientId?: string;
  replayable: boolean;
  responsibility: string;
  sourceId: string;
  status: string;
  updatedAt: string;
}

export interface OperationalFailureAttempt {
  attemptId: string;
  attemptNumber: number;
  completedAt: string;
  errorCode?: string;
  errorMessage?: string;
  nextAttemptAt?: string;
  providerReference?: string;
  responseCode?: number;
  retryable: boolean;
  startedAt: string;
  successful: boolean;
  workerId?: string;
}

export interface OperationalFailurePage {
  hasMore: boolean;
  items: OperationalFailure[];
  limit: number;
  offset: number;
  total: number;
}

export interface ReplayItem {
  category: FailureCategory;
  sourceId: string;
}

export interface ReplayItemResult extends ReplayItem {
  message: string;
  occurredAt: string;
  outcome: ReplayOutcome;
  replacementSourceId?: string;
  requestId?: string;
}

export interface BatchReplayResult {
  completedAt: string;
  items: ReplayItemResult[];
  rejected: number;
  replayed: number;
  requestId: string;
}

export function findOperationalFailures(
  category: FailureCategory | undefined,
  failureKind: FailureKind | undefined,
  connectorKey: string,
  limit: number,
  offset: number,
) {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (category) query.set('category', category);
  if (failureKind) query.set('failureKind', failureKind);
  if (connectorKey.trim()) query.set('connectorKey', connectorKey.trim());
  return approvalRequest<OperationalFailurePage>(
    `/approval/management/operational-failures?${query.toString()}`,
  );
}

export function findOperationalFailureAttempts(
  category: FailureCategory,
  sourceId: string,
) {
  return approvalRequest<OperationalFailureAttempt[]>(
    `/approval/management/operational-failures/${category}/${encodeURIComponent(sourceId)}/attempts`,
  );
}

export async function replayOperationalFailure(item: ReplayItem) {
  const result = await approvalRequestWithTrace<ReplayItemResult>(
    `/approval/management/operational-failures/${item.category}/${encodeURIComponent(item.sourceId)}/replay`,
    {
      headers: approvalCommandHeaders('web-operational-replay'),
      method: 'POST',
    },
  );
  return {
    ...result.data,
    message: `${result.data.message} · requestId=${result.requestId}`,
    requestId: result.requestId,
  };
}

export async function replayOperationalFailureBatch(items: ReplayItem[]) {
  const result = await approvalRequestWithTrace<Omit<BatchReplayResult, 'requestId'>>(
    '/approval/management/operational-failures/replay-batch',
    {
      body: JSON.stringify({ items }),
      headers: approvalCommandHeaders('web-operational-replay-batch'),
      method: 'POST',
    },
  );
  return { ...result.data, requestId: result.requestId };
}
