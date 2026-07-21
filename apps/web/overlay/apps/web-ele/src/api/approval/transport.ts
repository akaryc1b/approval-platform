import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export interface ApprovalApiErrorPayload {
  code?: string;
  error?: string;
  message?: string;
  occurredAt?: string;
  requestId?: string;
  retryable?: boolean;
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

export async function approvalFetch(path: string, init: RequestInit = {}) {
  const runtime = getApprovalRuntimeConfig();
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  headers.set('X-Operator-Id', runtime.operatorId);
  headers.set('X-Tenant-Id', runtime.tenantId);
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  const response = await fetch(approvalUrl(path), {
    ...init,
    credentials: 'same-origin',
    headers,
  });
  if (!response.ok) {
    throw new ApprovalApiError(
      response.status,
      await errorPayload(response),
      response.headers.get('X-Request-Id') || undefined,
    );
  }
  return response;
}

export async function approvalRequest<T>(path: string, init: RequestInit = {}) {
  const response = await approvalFetch(path, init);
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}
