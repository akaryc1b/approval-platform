import { getApprovalRuntimeConfig } from '@/platform/approval/runtime'

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

interface RequestOptions {
  data?: unknown
  method?: 'GET' | 'POST' | 'PUT'
}

function joinUrl(baseUrl: string, path: string) {
  const normalized = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl
  return `${normalized}${path.startsWith('/') ? path : `/${path}`}`
}

function request<T>(path: string, options: RequestOptions = {}) {
  const runtime = getApprovalRuntimeConfig()
  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: joinUrl(runtime.apiBaseUrl, path),
      method: options.method || 'GET',
      data: options.data,
      header: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-Operator-Id': runtime.operatorId,
        'X-Tenant-Id': runtime.tenantId,
      },
      success: (response) => {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data as T)
          return
        }
        const payload = response.data as { code?: string, message?: string }
        reject(new Error(payload?.message || payload?.code || `请求失败（${response.statusCode}）`))
      },
      fail: error => reject(new Error(error.errMsg || '网络请求失败')),
    })
  })
}

export function findNotificationPreferences() {
  return request<NotificationPreferenceBundle>('/approval/notifications/preferences')
}

export function updateNotificationPreferences(bundle: NotificationPreferenceBundle) {
  return request<NotificationPreferenceBundle>('/approval/notifications/preferences', {
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
  })
}

export function findNotificationHistory(unreadOnly: boolean, limit: number, offset: number) {
  const query = `unreadOnly=${unreadOnly}&limit=${limit}&offset=${offset}`
  return request<NotificationHistoryPage>(`/approval/notifications?${query}`)
}

export function findNotificationUnreadCount() {
  return request<{ unread: number }>('/approval/notifications/unread-count')
}

export function markNotificationRead(intentId: string) {
  return request<NotificationIntent>(
    `/approval/notifications/${encodeURIComponent(intentId)}/read`,
    { method: 'POST' },
  )
}

export function markAllNotificationsRead() {
  return request<{ readAt: string, updatedNotifications: number }>(
    '/approval/notifications/read-all',
    { method: 'POST' },
  )
}

export function replayNotification(intentId: string) {
  return request<NotificationIntent>(
    `/approval/notifications/${encodeURIComponent(intentId)}/replay`,
    { method: 'POST' },
  )
}
