import type { IdentityReference } from '@/api/approval/identities'

import { getApprovalRuntimeConfig } from '@/platform/approval/runtime'

export type CollaborationMode = 'ALL' | 'ANY'
export type CollaborationStatus = 'ACTIVE' | 'CANCELED' | 'REJECTED' | 'SATISFIED'
export type ParticipantDecision = 'APPROVED' | 'REJECTED'
export type ParticipantStatus = 'APPROVED' | 'CANCELED' | 'PENDING' | 'REJECTED' | 'REMOVED'

export interface CollaborationParticipant {
  addedAt: string
  addedBy: string
  canceledAt?: string
  decidedAt?: string
  decisionComment?: string
  identity: IdentityReference
  participantId: string
  participantUserId: string
  policyId: string
  removalReason?: string
  removedAt?: string
  removedBy?: string
  status: ParticipantStatus
  tenantId: string
  version: number
}

export interface TaskCollaboration {
  createdAt: string
  createdBy: string
  definitionKey: string
  engineInstanceId: string
  engineTaskId: string
  instanceId: string
  mode: CollaborationMode
  ownerAssigneeId: string
  participants: CollaborationParticipant[]
  policyId: string
  reason: string
  status: CollaborationStatus
  taskDefinitionKey: string
  taskId: string
  taskName: string
  tenantId: string
  terminalAt?: string
  terminalBy?: string
  terminalReason?: string
  version: number
}

export interface PendingCollaborationTask {
  addedAt: string
  definitionKey: string
  instanceId: string
  mode: CollaborationMode
  ownerAssigneeId: string
  participantId: string
  policyId: string
  reason: string
  taskDefinitionKey: string
  taskId: string
  taskName: string
}

interface RequestOptions {
  data?: unknown
  header?: Record<string, string>
  method?: 'GET' | 'POST'
}

interface ApiErrorPayload {
  code?: string
  error?: string
  message?: string
}

function joinUrl(baseUrl: string, path: string) {
  const normalized = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl
  return `${normalized}${path.startsWith('/') ? path : `/${path}`}`
}

function operationId(prefix: string) {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(16).slice(2)}`
}

function errorMessage(payload: unknown, statusCode: number) {
  if (payload && typeof payload === 'object') {
    const error = payload as ApiErrorPayload
    return error.message || error.error || error.code || `请求失败（${statusCode}）`
  }
  return typeof payload === 'string' && payload.trim()
    ? payload
    : `请求失败（${statusCode}）`
}

function request<T>(path: string, options: RequestOptions = {}, allowNotFound = false) {
  const runtime = getApprovalRuntimeConfig()
  const header: Record<string, string> = {
    Accept: 'application/json',
    'X-Operator-Id': runtime.operatorId,
    'X-Tenant-Id': runtime.tenantId,
    ...options.header,
  }
  if (options.data !== undefined) header['Content-Type'] = 'application/json'
  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: joinUrl(runtime.apiBaseUrl, path),
      method: options.method || 'GET',
      data: options.data,
      header,
      success: (response) => {
        if (allowNotFound && response.statusCode === 404) {
          resolve(undefined as T)
          return
        }
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data as T)
          return
        }
        reject(new Error(errorMessage(response.data, response.statusCode)))
      },
      fail: error => reject(new Error(error.errMsg || '网络请求失败')),
    })
  })
}

function commandHeaders(prefix: string) {
  const requestId = operationId(`${prefix}-request`)
  return {
    'Idempotency-Key': operationId(prefix),
    'X-Request-Id': requestId,
    'X-Trace-Id': requestId,
  }
}

export function findTaskCollaboration(taskId: string) {
  return request<TaskCollaboration | undefined>(
    `/approval/tasks/${encodeURIComponent(taskId)}/collaboration`,
    {},
    true,
  )
}

export function findPendingCollaborationTasks(limit = 100) {
  return request<PendingCollaborationTask[]>(
    `/approval/collaboration/tasks/pending?limit=${encodeURIComponent(String(limit))}`,
  )
}

export function createTaskCollaboration(
  taskId: string,
  mode: CollaborationMode,
  participants: IdentityReference[],
  reason: string,
) {
  const runtime = getApprovalRuntimeConfig()
  return request<TaskCollaboration>(
    `/approval/tasks/${encodeURIComponent(taskId)}/collaboration`,
    {
      data: {
        connectorKey: runtime.connectorKey,
        mode,
        participants,
        reason: reason.trim(),
      },
      header: commandHeaders('mobile-task-collaboration-create'),
      method: 'POST',
    },
  )
}

export function removeTaskCollaborator(participantId: string, reason: string) {
  return request<TaskCollaboration>(
    `/approval/collaboration/participants/${encodeURIComponent(participantId)}/remove`,
    {
      data: { reason: reason.trim() },
      header: commandHeaders('mobile-task-collaboration-remove'),
      method: 'POST',
    },
  )
}

export function decideTaskCollaboration(
  participantId: string,
  decision: ParticipantDecision,
  comment: string,
) {
  return request<TaskCollaboration>(
    `/approval/collaboration/participants/${encodeURIComponent(participantId)}/decide`,
    {
      data: { comment: comment.trim(), decision },
      header: commandHeaders('mobile-task-collaboration-decide'),
      method: 'POST',
    },
  )
}
