import {
  mobileApprovalMutationHeaders,
  mobileApprovalRequest,
} from '@/api/approval/transport'

export type NotificationChannel = 'CONNECTOR' | 'EMAIL' | 'IN_APP'
export type NotificationEventType =
  | 'APPROVAL_COMPLETED'
  | 'APPROVAL_REJECTED'
  | 'AUTOMATIC_DELEGATION'
  | 'COMMENT_MENTION'
  | 'EMPLOYEE_HANDOVER'
  | 'TASK_ASSIGNED'
  | 'TASK_COLLABORATION_ASSIGNED'
  | 'TASK_COLLABORATION_RESULT'
export type NotificationStatus = 'DEAD_LETTER' | 'DELIVERED' | 'PENDING' | 'PROCESSING' | 'RETRY'

export interface NotificationPreference {
  channel: NotificationChannel
  enabled: boolean
  eventType: NotificationEventType
}

export interface NotificationPreferenceBundle {
  createdAt: string
  digestEnabled: boolean
  emergencyBypass: boolean
  preferences: NotificationPreference[]
  quietHoursEnabled: boolean
  quietHoursEnd?: string
  quietHoursStart?: string
  tenantId: string
  timezone: string
  updatedAt: string
  userId: string
  version: number
}

export interface NotificationIntent {
  attemptCount: number
  body: string
  channel: NotificationChannel
  createdAt: string
  deliveredAt?: string
  eventType: NotificationEventType
  instanceId?: string
  intentId: string
  lastErrorCode?: string
  lastErrorMessage?: string
  maxAttempts: number
  metadata: Record<string, string>
  nextAttemptAt: string
  readAt?: string
  senderId: string
  status: NotificationStatus
  taskId?: string
  title: string
  urgent: boolean
}

export interface NotificationHistoryPage {
  hasMore: boolean
  items: NotificationIntent[]
  limit: number
  offset: number
  total: number
}

export function findNotificationPreferences() {
  return mobileApprovalRequest<NotificationPreferenceBundle>(
    '/approval/notifications/preferences',
  )
}

export function updateNotificationPreferences(bundle: NotificationPreferenceBundle) {
  return mobileApprovalRequest<NotificationPreferenceBundle>(
    '/approval/notifications/preferences',
    {
      method: 'PUT',
      data: {
        digestEnabled: bundle.digestEnabled,
        emergencyBypass: bundle.emergencyBypass,
        expectedVersion: bundle.version,
        preferences: bundle.preferences,
        quietHoursEnabled: bundle.quietHoursEnabled,
        quietHoursEnd: bundle.quietHoursEnabled ? bundle.quietHoursEnd : null,
        quietHoursStart: bundle.quietHoursEnabled ? bundle.quietHoursStart : null,
        timezone: bundle.timezone,
      },
      header: mobileApprovalMutationHeaders('mobile-notification-preferences'),
    },
  )
}

export function findNotificationHistory(unreadOnly: boolean, limit: number, offset: number) {
  const query = `unreadOnly=${unreadOnly}&limit=${limit}&offset=${offset}`
  return mobileApprovalRequest<NotificationHistoryPage>(`/approval/notifications?${query}`)
}

export function findNotificationUnreadCount() {
  return mobileApprovalRequest<{ unread: number }>('/approval/notifications/unread-count')
}

export function markNotificationRead(intentId: string) {
  return mobileApprovalRequest<NotificationIntent>(
    `/approval/notifications/${encodeURIComponent(intentId)}/read`,
    {
      header: mobileApprovalMutationHeaders('mobile-notification-read'),
      method: 'POST',
    },
  )
}

export function markAllNotificationsRead() {
  return mobileApprovalRequest<{ readAt: string, updatedNotifications: number }>(
    '/approval/notifications/read-all',
    {
      header: mobileApprovalMutationHeaders('mobile-notification-read-all'),
      method: 'POST',
    },
  )
}

export function replayNotification(intentId: string) {
  return mobileApprovalRequest<NotificationIntent>(
    `/approval/notifications/${encodeURIComponent(intentId)}/replay`,
    {
      header: mobileApprovalMutationHeaders('mobile-notification-replay'),
      method: 'POST',
    },
  )
}
