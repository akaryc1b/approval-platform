import {
  approvalCommandHeaders,
  approvalOperationId,
  approvalRequest,
} from '#/api/approval/transport';

export type CalendarStatus = 'ACTIVE' | 'ARCHIVED' | 'DRAFT' | 'INACTIVE' | 'PUBLISHED';
export type PolicyStatus = CalendarStatus;
export type SlaStatus = 'ACTIVE' | 'PAUSED' | 'TERMINAL';
export type SlaTargetType = 'COLLABORATION_PARTICIPANT' | 'PROCESS' | 'TASK';
export type SlaDurationMode = 'NATURAL_TIME' | 'WORKING_TIME';
export type AutomaticAction = 'AUTO_APPROVE' | 'AUTO_REJECT' | 'AUTO_TRANSFER' | 'NONE';
export type EscalationTargetType = 'DEPARTMENT_ADMIN' | 'MANAGER' | 'ROLE' | 'USER';

export interface WorkingInterval {
  end: string;
  start: string;
}

export interface DayOverride {
  intervals: WorkingInterval[];
  working: boolean;
}

export interface CalendarIdentity {
  activeVersion?: number;
  calendarId: string;
  calendarKey: string;
  createdAt: string;
  createdBy: string;
  displayName: string;
  status: CalendarStatus;
  tenantId: string;
  timeZone: string;
  updatedAt: string;
  version: number;
}

export interface CalendarPage {
  items: CalendarIdentity[];
  limit: number;
  offset: number;
  total: number;
}

export interface CalendarSnapshot {
  calendarId: string;
  calendarVersion: number;
  contentHash: string;
  overrides: Record<string, DayOverride>;
  tenantId: string;
  weeklySchedule: Partial<Record<string, WorkingInterval[]>>;
  zoneId: string;
}

export interface CalendarVersion {
  calendarId: string;
  calendarVersion: number;
  createdAt: string;
  effectiveFrom?: string;
  effectiveTo?: string;
  immutable: boolean;
  publishedAt?: string;
  publishedBy?: string;
  snapshot: CalendarSnapshot;
  status: CalendarStatus;
  tenantId: string;
  updatedAt: string;
}

export interface CalendarVersionInput {
  effectiveFrom?: string;
  effectiveTo?: string;
  expectedIdentityVersion: number;
  overrides: Record<string, DayOverride>;
  timeZone: string;
  weeklySchedule: Partial<Record<string, WorkingInterval[]>>;
}

export interface SlaPolicyIdentity {
  activeVersion?: number;
  createdAt: string;
  createdBy: string;
  displayName: string;
  policyId: string;
  policyKey: string;
  status: PolicyStatus;
  tenantId: string;
  updatedAt: string;
  version: number;
}

export interface SlaPolicyPage {
  items: SlaPolicyIdentity[];
  limit: number;
  offset: number;
  total: number;
}

export interface SlaPolicyVersion {
  automaticAction: AutomaticAction;
  calendarId?: string;
  calendarVersion?: number;
  contentHash: string;
  createdAt: string;
  definitionKey: string;
  duration: number | string;
  durationMode: SlaDurationMode;
  escalationTarget?: string;
  escalationTargetType?: EscalationTargetType;
  firstReminderOffset?: number | string;
  immutable: boolean;
  maximumReminderCount: number;
  naturalTimePauses: boolean;
  overdueOffset?: number | string;
  policyId: string;
  policyVersion: number;
  publishedAt?: string;
  publishedBy?: string;
  releaseVersion?: number;
  repeatReminderInterval?: number | string;
  status: PolicyStatus;
  targetType: SlaTargetType;
  taskDefinitionKey?: string;
  tenantId: string;
  updatedAt: string;
}

export interface SlaPolicyVersionInput {
  automaticAction: AutomaticAction;
  calendarId?: string;
  calendarVersion?: number;
  definitionKey: string;
  duration: string;
  durationMode: SlaDurationMode;
  escalationTarget?: string;
  escalationTargetType?: EscalationTargetType;
  expectedIdentityVersion: number;
  firstReminderOffset?: string;
  maximumReminderCount: number;
  naturalTimePauses: boolean;
  overdueOffset?: string;
  releaseVersion?: number;
  repeatReminderInterval?: string;
  targetType: SlaTargetType;
  taskDefinitionKey?: string;
}

export interface SlaInstance {
  accumulatedPausedDuration: number | string;
  approvalInstanceId: string;
  calendarId?: string;
  calendarVersion?: number;
  collaborationParticipantId?: string;
  createdAt: string;
  definitionKey: string;
  dueAt: string;
  lastActionSequence: number;
  nextReminderAt?: string;
  originalResponsibleUserId: string;
  overdueAt: string;
  pausedAt?: string;
  pauseReason?: string;
  policyId: string;
  policyVersion: number;
  requestId: string;
  responsibleUserId: string;
  slaInstanceId: string;
  startedAt: string;
  status: SlaStatus;
  targetType: SlaTargetType;
  taskDefinitionKey?: string;
  taskId?: string;
  terminalAt?: string;
  terminalReason?: string;
  timeZone: string;
  traceId?: string;
  updatedAt: string;
  version: number;
}

export interface SlaInstancePage {
  items: SlaInstance[];
  limit: number;
  offset: number;
  total: number;
}

export interface ResponsibilityChange {
  changedAt: string;
  changedBy: string;
  newResponsibleUserId: string;
  previousResponsibleUserId: string;
  reason: string;
  requestId: string;
  responsibilityChangeId: string;
  slaInstanceId: string;
  source: string;
  tenantId: string;
  traceId?: string;
}

export interface GovernedOperation {
  idempotencyKey?: string;
  reason: string;
}

export interface SlaInstanceFilters {
  dueAfter?: string;
  dueBefore?: string;
  limit?: number;
  offset?: number;
  requestId?: string;
  responsibleUserId?: string;
  status?: SlaStatus;
}

function governedHeaders(operation: GovernedOperation, prefix: string) {
  const reason = operation.reason.trim();
  if (reason.length < 8 || reason.length > 512) {
    throw new Error('操作原因需为 8–512 个字符');
  }
  const headers = approvalCommandHeaders(`web-sla-${prefix}`);
  headers['Idempotency-Key'] = operation.idempotencyKey?.trim()
    || approvalOperationId(`web-sla-${prefix}`);
  return {
    ...headers,
    'X-Approval-Operation-Reason': reason,
  };
}

function pageQuery(limit = 50, offset = 0) {
  return new URLSearchParams({ limit: String(limit), offset: String(offset) }).toString();
}

export function findCalendars(limit = 50, offset = 0) {
  return approvalRequest<CalendarPage>(
    `/approval/management/sla/calendars?${pageQuery(limit, offset)}`,
  );
}

export function findCalendarVersion(calendarId: string, version: number) {
  return approvalRequest<CalendarVersion>(
    `/approval/management/sla/calendars/${encodeURIComponent(calendarId)}/versions/${version}`,
  );
}

export function createCalendar(
  input: { calendarKey: string; displayName: string; timeZone: string },
  governance: GovernedOperation,
) {
  return approvalRequest<CalendarIdentity>('/approval/management/sla/calendars', {
    body: JSON.stringify(input),
    headers: governedHeaders(governance, 'calendar-create'),
    method: 'POST',
  });
}

export function saveCalendarVersion(
  calendarId: string,
  version: number,
  input: CalendarVersionInput,
  governance: GovernedOperation,
) {
  return approvalRequest<CalendarVersion>(
    `/approval/management/sla/calendars/${encodeURIComponent(calendarId)}/versions/${version}`,
    {
      body: JSON.stringify(input),
      headers: governedHeaders(governance, 'calendar-save'),
      method: 'PUT',
    },
  );
}

export function publishCalendarVersion(
  calendar: CalendarIdentity,
  version: number,
  governance: GovernedOperation,
) {
  return approvalRequest<CalendarVersion>(
    `/approval/management/sla/calendars/${encodeURIComponent(calendar.calendarId)}/versions/${version}/publish`,
    {
      body: JSON.stringify({ expectedIdentityVersion: calendar.version }),
      headers: governedHeaders(governance, 'calendar-publish'),
      method: 'POST',
    },
  );
}

export function activateCalendarVersion(
  calendar: CalendarIdentity,
  version: number,
  governance: GovernedOperation,
) {
  return approvalRequest<CalendarIdentity>(
    `/approval/management/sla/calendars/${encodeURIComponent(calendar.calendarId)}/versions/${version}/activate`,
    {
      body: JSON.stringify({ expectedIdentityVersion: calendar.version }),
      headers: governedHeaders(governance, 'calendar-activate'),
      method: 'POST',
    },
  );
}

export function findPolicies(limit = 50, offset = 0) {
  return approvalRequest<SlaPolicyPage>(
    `/approval/management/sla/policies?${pageQuery(limit, offset)}`,
  );
}

export function findPolicyVersion(policyId: string, version: number) {
  return approvalRequest<SlaPolicyVersion>(
    `/approval/management/sla/policies/${encodeURIComponent(policyId)}/versions/${version}`,
  );
}

export function createPolicy(
  input: { displayName: string; policyKey: string },
  governance: GovernedOperation,
) {
  return approvalRequest<SlaPolicyIdentity>('/approval/management/sla/policies', {
    body: JSON.stringify(input),
    headers: governedHeaders(governance, 'policy-create'),
    method: 'POST',
  });
}

export function savePolicyVersion(
  policyId: string,
  version: number,
  input: SlaPolicyVersionInput,
  governance: GovernedOperation,
) {
  return approvalRequest<SlaPolicyVersion>(
    `/approval/management/sla/policies/${encodeURIComponent(policyId)}/versions/${version}`,
    {
      body: JSON.stringify(input),
      headers: governedHeaders(governance, 'policy-save'),
      method: 'PUT',
    },
  );
}

export function publishPolicyVersion(
  policy: SlaPolicyIdentity,
  version: number,
  governance: GovernedOperation,
) {
  return approvalRequest<SlaPolicyVersion>(
    `/approval/management/sla/policies/${encodeURIComponent(policy.policyId)}/versions/${version}/publish`,
    {
      body: JSON.stringify({ expectedIdentityVersion: policy.version }),
      headers: governedHeaders(governance, 'policy-publish'),
      method: 'POST',
    },
  );
}

export function activatePolicyVersion(
  policy: SlaPolicyIdentity,
  version: number,
  governance: GovernedOperation,
) {
  return approvalRequest<SlaPolicyIdentity>(
    `/approval/management/sla/policies/${encodeURIComponent(policy.policyId)}/versions/${version}/activate`,
    {
      body: JSON.stringify({ expectedIdentityVersion: policy.version }),
      headers: governedHeaders(governance, 'policy-activate'),
      method: 'POST',
    },
  );
}

export function findSlaInstances(filters: SlaInstanceFilters = {}) {
  const query = new URLSearchParams({
    limit: String(filters.limit ?? 50),
    offset: String(filters.offset ?? 0),
  });
  if (filters.status) query.set('status', filters.status);
  if (filters.responsibleUserId?.trim()) {
    query.set('responsibleUserId', filters.responsibleUserId.trim());
  }
  if (filters.requestId?.trim()) query.set('requestId', filters.requestId.trim());
  if (filters.dueBefore) query.set('dueBefore', filters.dueBefore);
  if (filters.dueAfter) query.set('dueAfter', filters.dueAfter);
  return approvalRequest<SlaInstancePage>(
    `/approval/management/sla/instances?${query.toString()}`,
  );
}

export function findUpcomingSla(dueBefore: string, limit = 50, offset = 0) {
  const query = new URLSearchParams({
    dueBefore,
    limit: String(limit),
    offset: String(offset),
  });
  return approvalRequest<SlaInstancePage>(
    `/approval/management/sla/instances/upcoming?${query.toString()}`,
  );
}

export function findOverdueSla(limit = 50, offset = 0) {
  return approvalRequest<SlaInstancePage>(
    `/approval/management/sla/instances/overdue?${pageQuery(limit, offset)}`,
  );
}

export function findResponsibilityChanges(slaInstanceId: string, limit = 50) {
  return approvalRequest<ResponsibilityChange[]>(
    `/approval/management/sla/instances/${encodeURIComponent(slaInstanceId)}/responsibility-changes?limit=${limit}`,
  );
}

export function pauseSla(instance: SlaInstance, governance: GovernedOperation) {
  return approvalRequest<SlaInstance>(
    `/approval/management/sla/instances/${encodeURIComponent(instance.slaInstanceId)}/pause`,
    {
      body: JSON.stringify({
        expectedVersion: instance.version,
        reason: governance.reason.trim(),
      }),
      headers: governedHeaders(governance, 'instance-pause'),
      method: 'POST',
    },
  );
}

export function resumeSla(instance: SlaInstance, governance: GovernedOperation) {
  return approvalRequest<SlaInstance>(
    `/approval/management/sla/instances/${encodeURIComponent(instance.slaInstanceId)}/resume`,
    {
      body: JSON.stringify({ expectedVersion: instance.version }),
      headers: governedHeaders(governance, 'instance-resume'),
      method: 'POST',
    },
  );
}
