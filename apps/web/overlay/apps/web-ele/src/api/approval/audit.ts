import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export interface AuditRecord {
  action: string;
  aggregateId: string;
  aggregateType: string;
  attributes: Record<string, string>;
  currentHash: string;
  eventId: string;
  occurredAt: string;
  operatorId: string;
  payloadHash: string;
  previousHash: string;
  requestId: string;
  schemaName: string;
  schemaVersion: number;
  tenantId: string;
  tenantSequence: number;
  traceId?: string;
}

export interface AuditPage {
  hasMore: boolean;
  items: AuditRecord[];
  limit: number;
  offset: number;
  total: number;
}

export interface AuditFilters {
  action?: string;
  aggregateId?: string;
  aggregateType?: string;
  occurredFrom: string;
  occurredTo: string;
  operatorId?: string;
  requestId?: string;
  traceId?: string;
}

export interface AuditIntegrityReport {
  assuranceStatement: string;
  chainStateHash: string;
  chainStateSequence: number;
  checkedCount: number;
  failureCode?: string;
  firstInvalidEventId?: string;
  firstInvalidSequence?: number;
  occurredFrom: string;
  occurredTo: string;
  valid: boolean;
  verifiedAt: string;
}

export type AuditExportFormat = 'CSV' | 'JSON';

interface ApiErrorPayload {
  code?: string;
  error?: string;
  message?: string;
}

function operationId(prefix: string) {
  const randomId = globalThis.crypto?.randomUUID?.() ??
    `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}-${randomId}`;
}

function joinUrl(baseUrl: string, path: string) {
  return `${baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
}

async function parseError(response: Response) {
  let payload: ApiErrorPayload | undefined;
  try {
    payload = (await response.json()) as ApiErrorPayload;
  } catch {
    payload = undefined;
  }
  return payload?.message || payload?.error || payload?.code ||
    `请求失败（${response.status}）`;
}

function runtimeHeaders(init?: HeadersInit) {
  const runtime = getApprovalRuntimeConfig();
  const headers = new Headers(init);
  headers.set('Accept', 'application/json');
  headers.set(
    'X-Approval-Trusted-Permissions',
    [
      'approval.management.audit.read',
      'approval.management.audit.export',
      'approval.management.audit.verify',
    ].join(','),
  );
  headers.set('X-Operator-Id', runtime.operatorId);
  headers.set('X-Tenant-Id', runtime.tenantId);
  return headers;
}

function commandHeaders(prefix: string) {
  const requestId = operationId(`${prefix}-request`);
  return {
    'Idempotency-Key': operationId(prefix),
    'X-Request-Id': requestId,
    'X-Trace-Id': requestId,
  };
}

async function request<T>(path: string, init: RequestInit = {}) {
  const runtime = getApprovalRuntimeConfig();
  const headers = runtimeHeaders(init.headers);
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const response = await fetch(joinUrl(runtime.apiBaseUrl, path), {
    ...init,
    credentials: 'same-origin',
    headers,
  });
  if (!response.ok) throw new Error(await parseError(response));
  return (await response.json()) as T;
}

function filterQuery(filters: AuditFilters) {
  const query = new URLSearchParams({
    occurredFrom: filters.occurredFrom,
    occurredTo: filters.occurredTo,
  });
  const optional: Array<[string, string | undefined]> = [
    ['operatorId', filters.operatorId],
    ['action', filters.action],
    ['aggregateType', filters.aggregateType],
    ['aggregateId', filters.aggregateId],
    ['requestId', filters.requestId],
    ['traceId', filters.traceId],
  ];
  optional.forEach(([key, value]) => {
    const normalized = value?.trim();
    if (normalized) query.set(key, normalized);
  });
  return query;
}

export function findAuditEvents(filters: AuditFilters, limit: number, offset: number) {
  const query = filterQuery(filters);
  query.set('limit', String(limit));
  query.set('offset', String(offset));
  return request<AuditPage>(`/approval/management/audit?${query.toString()}`);
}

export function verifyAuditIntegrity(filters: Pick<AuditFilters, 'occurredFrom' | 'occurredTo'>) {
  return request<AuditIntegrityReport>('/approval/management/audit/integrity/verify', {
    body: JSON.stringify(filters),
    headers: commandHeaders('web-audit-verify'),
    method: 'POST',
  });
}

export async function exportAuditEvents(
  filters: AuditFilters,
  format: AuditExportFormat,
  maxRecords: number,
) {
  const runtime = getApprovalRuntimeConfig();
  const requestId = operationId('web-audit-export-request');
  const response = await fetch(
    joinUrl(runtime.apiBaseUrl, '/approval/management/audit/export'),
    {
      body: JSON.stringify({ ...filters, format, maxRecords }),
      credentials: 'same-origin',
      headers: runtimeHeaders({
        'Content-Type': 'application/json',
        'Idempotency-Key': operationId('web-audit-export'),
        'X-Request-Id': requestId,
        'X-Trace-Id': requestId,
      }),
      method: 'POST',
    },
  );
  if (!response.ok) throw new Error(await parseError(response));
  const blob = await response.blob();
  const contentDisposition = response.headers.get('Content-Disposition');
  const match = contentDisposition?.match(/filename="?([^";]+)"?/i);
  const fileName = match?.[1] || `approval-audit.${format.toLowerCase()}`;
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  anchor.click();
  URL.revokeObjectURL(url);
}
