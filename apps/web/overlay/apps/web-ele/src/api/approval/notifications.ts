import { approvalRequest } from '#/api/approval/transport';

export type NotificationChannel = 'CONNECTOR' | 'EMAIL' | 'IN_APP';
export type NotificationEventType =
  | 'APPROVAL_COMPLETED'
  | 'APPROVAL_REJECTED'
  | 'AUTOMATIC_DELEGATION'
  | 'COMMENT_MENTION'
  | 'EMPLOYEE_HANDOVER'
  | 'TASK_ASSIGNED'
  | 'TASK_COLLABORATION_ASSIGNED'
  | 'TASK_COLLABORATION_RESULT';
export type NotificationStatus =
  | 'DEAD_LETTER'
  | 'DELIVERED'
  | 'PENDING'
  | 'PROCESSING'
  | 'RETRY';

export interface NotificationPreference {
  channel: NotificationChannel;
  enabled: boolean;
  eventType: NotificationEventType;
}

export interface NotificationPreferenceBundle {
  createdAt: string;
  digestEnabled: boolean;
  emergencyBypass: boolean;
  preferences: NotificationPreference[];
  quietHoursEnabled: boolean;
  quietHoursEnd?: string;
  quietHoursStart?: string;
  tenantId: string;
  timezone: string;
  updatedAt: string;
  userId: string;
  version: number;
}

export interface NotificationIntent {
  aggregateId: string;
  aggregateType: string;
  attemptCount: number;
  body: string;
  businessEventKey: string;
  channel: NotificationChannel;
  createdAt: string;
  deliveredAt?: string;
  eventType: NotificationEventType;
  instanceId?: string;
  intentId: string;
  lastErrorCode?: string;
  lastErrorMessage?: string;
  maxAttempts: number;
  metadata: Record<string, string>;
  nextAttemptAt: string;
  readAt?: string;
  recipientId: string;
  senderId: string;
  status: NotificationStatus;
  taskId?: string;
  title: string;
  updatedAt: string;
  urgent: boolean;
  version: number;
}

export interface NotificationHistoryPage {
  hasMore: boolean;
  items: NotificationIntent[];
  limit: number;
  offset: number;
  total: number;
}

export interface NotificationDeliveryAttempt {
  attemptId: string;
  attemptNumber: number;
  completedAt: string;
  errorCode?: string;
  errorMessage?: string;
  intentId: string;
  providerMessageId?: string;
  retryable: boolean;
  startedAt: string;
  successful: boolean;
  tenantId: string;
}

export function findNotificationPreferences() {
  return approvalRequest<NotificationPreferenceBundle>('/approval/notifications/preferences');
}

export function updateNotificationPreferences(bundle: NotificationPreferenceBundle) {
  return approvalRequest<NotificationPreferenceBundle>('/approval/notifications/preferences', {
    body: JSON.stringify({
      digestEnabled: bundle.digestEnabled,
      emergencyBypass: bundle.emergencyBypass,
      expectedVersion: bundle.version,
      preferences: bundle.preferences,
      quietHoursEnabled: bundle.quietHoursEnabled,
      quietHoursEnd: bundle.quietHoursEnabled ? bundle.quietHoursEnd : null,
      quietHoursStart: bundle.quietHoursEnabled ? bundle.quietHoursStart : null,
      timezone: bundle.timezone,
    }),
    method: 'PUT',
  });
}

export function findNotificationHistory(unreadOnly: boolean, limit: number, offset: number) {
  const query = new URLSearchParams({
    limit: String(limit),
    offset: String(offset),
    unreadOnly: String(unreadOnly),
  });
  return approvalRequest<NotificationHistoryPage>(
    `/approval/notifications?${query.toString()}`,
  );
}

export function findNotificationUnreadCount() {
  return approvalRequest<{ unread: number }>('/approval/notifications/unread-count');
}

export function markNotificationRead(intentId: string) {
  return approvalRequest<NotificationIntent>(
    `/approval/notifications/${encodeURIComponent(intentId)}/read`,
    { method: 'POST' },
  );
}

export function markAllNotificationsRead() {
  return approvalRequest<{ readAt: string; updatedNotifications: number }>(
    '/approval/notifications/read-all',
    { method: 'POST' },
  );
}

export function findNotificationAttempts(intentId: string) {
  return approvalRequest<NotificationDeliveryAttempt[]>(
    `/approval/notifications/${encodeURIComponent(intentId)}/attempts`,
  );
}

export function replayNotification(intentId: string) {
  return approvalRequest<NotificationIntent>(
    `/approval/notifications/${encodeURIComponent(intentId)}/replay`,
    { method: 'POST' },
  );
}
