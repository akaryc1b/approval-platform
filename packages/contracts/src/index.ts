export type TenantId = string;
export type UserId = string;
export type RequestId = string;
export type IdempotencyKey = string;

export interface RequestContext {
  tenantId: TenantId;
  operatorId: UserId;
  requestId: RequestId;
  idempotencyKey?: IdempotencyKey;
  traceId?: string;
}

export type ApprovalTaskStatus =
  | 'PENDING'
  | 'COMPLETED'
  | 'REJECTED'
  | 'CANCELED'
  | 'SUSPENDED';

export interface ApprovalTaskSummary {
  id: string;
  tenantId: TenantId;
  processInstanceId: string;
  title: string;
  status: ApprovalTaskStatus;
  assigneeId?: UserId;
  createdAt: string;
}
