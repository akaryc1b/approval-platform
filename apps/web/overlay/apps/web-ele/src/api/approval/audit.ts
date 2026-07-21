import {
  approvalCommandHeaders,
  approvalFetch,
  approvalRequest,
  approvalRequestWithTrace,
} from '#/api/approval/transport';

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
  return approvalRequest<AuditPage>(`/approval/management/audit?${query.toString()}`);
}

export function verifyAuditIntegrity(filters: Pick<AuditFilters, 'occurredFrom' | 'occurredTo'>) {
  return approvalRequestWithTrace<AuditIntegrityReport>(
    '/approval/management/audit/integrity/verify',
    {
      body: JSON.stringify(filters),
      headers: approvalCommandHeaders('web-audit-verify'),
      method: 'POST',
    },
  );
}

export async function exportAuditEvents(
  filters: AuditFilters,
  format: AuditExportFormat,
  maxRecords: number,
) {
  const headers = approvalCommandHeaders('web-audit-export');
  const response = await approvalFetch('/approval/management/audit/export', {
    body: JSON.stringify({ ...filters, format, maxRecords }),
    headers,
    method: 'POST',
  });
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
  return response.headers.get('X-Request-Id') || headers['X-Request-Id'];
}
