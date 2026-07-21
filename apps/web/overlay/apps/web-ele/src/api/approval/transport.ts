import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export interface ApprovalApiErrorPayload {
  code?: string;
  error?: string;
  message?: string;
  occurredAt?: string;
  requestId?: string;
  retryable?: boolean;
}

export interface ApprovalTracedResponse<T> {
  data: T;
  requestId: string;
}

export class ApprovalApiError extends Error {
  readonly code: string;
  readonly occurredAt?: string;
  readonly requestId?: string;
  readonly retryable: boolean;
  readonly status: number;

  constructor(status: number, payload: ApprovalApiErrorPayload, responseRequestId?: string) {
    const code = payload.code || payload.error || 'APPROVAL_REQUEST_FAILED';
    const requestId = payload.requestId || responseRequestId;
    const message = payload.message || payload.error || `请求失败（${status}）`;
    const details = [message, `[${code}]`, requestId ? `requestId=${requestId}` : undefined]
      .filter(Boolean)
      .join(' · ');
    super(details);
    this.name = 'ApprovalApiError';
    this.code = code;
    this.occurredAt = payload.occurredAt;
    this.requestId = requestId;
    this.retryable = payload.retryable === true;
    this.status = status;
  }
}

export function approvalOperationId(prefix: string) {
  const randomId = globalThis.crypto?.randomUUID?.() ??
    `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}-${randomId}`;
}

export function approvalCommandHeaders(prefix: string) {
  const requestId = approvalOperationId(`${prefix}-request`);
  return {
    'Idempotency-Key': approvalOperationId(prefix),
    'X-Request-Id': requestId,
    'X-Trace-Id': requestId,
  };
}

export function approvalUrl(path: string) {
  const { apiBaseUrl } = getApprovalRuntimeConfig();
  return `${apiBaseUrl}${path.startsWith('/') ? path : `/${path}`}`;
}

async function errorPayload(response: Response): Promise<ApprovalApiErrorPayload> {
  try {
    const payload = (await response.json()) as ApprovalApiErrorPayload;
    return payload && typeof payload === 'object' ? payload : {};
  } catch {
    return {};
  }
}

function prepareHeaders(init: RequestInit) {
  const runtime = getApprovalRuntimeConfig();
  const headers = new Headers(init.headers);
  const requestId = headers.get('X-Request-Id') || approvalOperationId('web-approval-request');
  headers.set('Accept', 'application/json');
  headers.set('X-Operator-Id', runtime.operatorId);
  headers.set('X-Request-Id', requestId);
  headers.set('X-Tenant-Id', runtime.tenantId);
  if (!headers.has('X-Trace-Id')) headers.set('X-Trace-Id', requestId);
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  return { headers, requestId };
}

export async function approvalFetch(path: string, init: RequestInit = {}) {
  const { headers, requestId } = prepareHeaders(init);
  let response: Response;
  try {
    response = await fetch(approvalUrl(path), {
      ...init,
      credentials: 'same-origin',
      headers,
    });
  } catch (error) {
    throw new ApprovalApiError(0, {
      code: 'APPROVAL_NETWORK_ERROR',
      message: error instanceof Error ? error.message : '审批请求网络失败',
      requestId,
      retryable: true,
    });
  }
  if (!response.ok) {
    throw new ApprovalApiError(
      response.status,
      await errorPayload(response),
      response.headers.get('X-Request-Id') || requestId,
    );
  }
  return response;
}

export async function approvalRequestWithTrace<T>(path: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers);
  const requestId = headers.get('X-Request-Id') || approvalOperationId('web-approval-request');
  headers.set('X-Request-Id', requestId);
  if (!headers.has('X-Trace-Id')) headers.set('X-Trace-Id', requestId);
  const response = await approvalFetch(path, { ...init, headers });
  const data = response.status === 204
    ? undefined as T
    : await response.json() as T;
  return {
    data,
    requestId: response.headers.get('X-Request-Id') || requestId,
  } satisfies ApprovalTracedResponse<T>;
}

export async function approvalRequest<T>(path: string, init: RequestInit = {}) {
  return (await approvalRequestWithTrace<T>(path, init)).data;
}
