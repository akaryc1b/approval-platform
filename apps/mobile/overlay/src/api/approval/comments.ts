import { getApprovalRuntimeConfig } from '@/platform/approval/runtime'

export type CommentStatus = 'ACTIVE' | 'DELETED'
export type CommentVisibility = 'MENTIONED_ONLY' | 'PARTICIPANTS'
export type CommentRevisionType = 'CREATE' | 'DELETE' | 'EDIT'

export interface CommentUserOption {
  displayName: string
  externalIdentityValue: string
  identitySource: string
  objectType: string
  userId: string
}

export interface ApprovalAttachment {
  attachmentId: string
  bound: boolean
  boundAt?: string
  contentType: string
  createdAt: string
  fileName: string
  instanceId?: string
  sha256: string
  sizeBytes: number
  uploaderId: string
}

export interface ApprovalCommentItem {
  attachments: ApprovalAttachment[]
  authorDisplayName: string
  authorId: string
  body: string
  canDelete: boolean
  canEdit: boolean
  commentId: string
  createdAt: string
  currentRevision: number
  deleteReason?: string
  deleted: boolean
  deletedAt?: string
  deletedBy?: string
  edited: boolean
  instanceId: string
  mentionedUsers: CommentUserOption[]
  parentCommentId?: string
  privateComment: boolean
  reply: boolean
  replyToAuthorDisplayName?: string
  replyToAuthorId?: string
  status: CommentStatus
  updatedAt: string
  version: number
  visibility: CommentVisibility
}

export interface ApprovalCommentPage {
  hasMore: boolean
  items: ApprovalCommentItem[]
  limit: number
  offset: number
  readOnly: boolean
  total: number
}

export interface CommentOptions {
  editWindowMinutes: number
  instanceId: string
  mentionCandidates: CommentUserOption[]
  readOnly: boolean
}

export interface CommentRevisionItem {
  attachmentIds: string[]
  body: string
  mentionedUsers: CommentUserOption[]
  occurredAt: string
  operatorId: string
  reason?: string
  revisionNumber: number
  revisionType: CommentRevisionType
  visibility: CommentVisibility
}

export interface CopiedInstanceItem {
  amount: number
  businessKey: string
  commentCount: number
  copiedAt: string
  copiedBy: string
  copyMessageId: string
  copyReadAt?: string
  currentTaskDefinitionKey?: string
  currentTaskName?: string
  definitionKey: string
  initiatorId: string
  instanceId: string
  purchaseOrderReference: string
  read: boolean
  status: 'COMPLETED' | 'REJECTED' | 'RUNNING' | 'WITHDRAWN'
  supplier: string
  updatedAt: string
}

export interface CopiedInstancePage {
  hasMore: boolean
  items: CopiedInstanceItem[]
  limit: number
  offset: number
  total: number
}

interface RequestOptions {
  data?: unknown
  header?: Record<string, string>
  method?: 'DELETE' | 'GET' | 'POST' | 'PUT'
}

interface ErrorPayload {
  code?: string
  error?: string
  message?: string
}

function operationId(prefix: string) {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(16).slice(2)}`
}

function joinUrl(baseUrl: string, path: string) {
  const normalized = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl
  return `${normalized}${path.startsWith('/') ? path : `/${path}`}`
}

function runtimeHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const runtime = getApprovalRuntimeConfig()
  return {
    Accept: 'application/json',
    'X-Operator-Id': runtime.operatorId,
    'X-Tenant-Id': runtime.tenantId,
    ...extra,
  }
}

function mutationHeaders(prefix: string) {
  const requestId = operationId(`${prefix}-request`)
  return {
    'Idempotency-Key': operationId(prefix),
    'X-Request-Id': requestId,
    'X-Trace-Id': requestId,
  }
}

function errorMessage(payload: unknown, statusCode: number) {
  if (payload && typeof payload === 'object') {
    const error = payload as ErrorPayload
    return error.message || error.error || error.code || `请求失败（${statusCode}）`
  }
  if (typeof payload === 'string' && payload.trim()) return payload
  return `请求失败（${statusCode}）`
}

function request<T>(path: string, options: RequestOptions = {}) {
  const runtime = getApprovalRuntimeConfig()
  const header: Record<string, string> = runtimeHeaders(options.header)
  if (options.data !== undefined) header['Content-Type'] = 'application/json'
  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: joinUrl(runtime.apiBaseUrl, path),
      method: options.method || 'GET',
      data: options.data,
      header,
      success: (response) => {
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

function pageQuery(keyword: string, limit: number, offset: number) {
  const query = [
    `limit=${encodeURIComponent(String(limit))}`,
    `offset=${encodeURIComponent(String(offset))}`,
  ]
  const normalized = keyword.trim()
  if (normalized) query.push(`keyword=${encodeURIComponent(normalized)}`)
  return query.join('&')
}

export function findCopiedInstances(keyword: string, limit: number, offset: number) {
  return request<CopiedInstancePage>(
    `/approval/instances/copied?${pageQuery(keyword, limit, offset)}`,
  )
}

export function findCommentOptions(instanceId: string) {
  return request<CommentOptions>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments/options`,
  )
}

export function findApprovalComments(instanceId: string, limit = 100, offset = 0) {
  return request<ApprovalCommentPage>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments?limit=${limit}&offset=${offset}`,
  )
}

export function findApprovalCommentRevisions(instanceId: string, commentId: string) {
  return request<CommentRevisionItem[]>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments/${encodeURIComponent(commentId)}/revisions`,
  )
}

export function uploadApprovalAttachment(filePath: string) {
  const runtime = getApprovalRuntimeConfig()
  return new Promise<ApprovalAttachment>((resolve, reject) => {
    uni.uploadFile({
      url: joinUrl(runtime.apiBaseUrl, '/approval/attachments'),
      filePath,
      name: 'file',
      header: runtimeHeaders(mutationHeaders('mobile-attachment')),
      success: (response) => {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          try {
            resolve(JSON.parse(response.data) as ApprovalAttachment)
          }
          catch {
            reject(new Error('附件响应解析失败'))
          }
          return
        }
        let payload: unknown = response.data
        try {
          payload = JSON.parse(response.data)
        }
        catch {
          payload = response.data
        }
        reject(new Error(errorMessage(payload, response.statusCode)))
      },
      fail: error => reject(new Error(error.errMsg || '附件上传失败')),
    })
  })
}

export function downloadApprovalAttachment(attachment: ApprovalAttachment) {
  const runtime = getApprovalRuntimeConfig()
  return new Promise<string>((resolve, reject) => {
    uni.downloadFile({
      url: joinUrl(
        runtime.apiBaseUrl,
        `/approval/attachments/${encodeURIComponent(attachment.attachmentId)}/content`,
      ),
      header: runtimeHeaders(),
      success: (response) => {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.tempFilePath)
          return
        }
        reject(new Error(`附件下载失败（${response.statusCode}）`))
      },
      fail: error => reject(new Error(error.errMsg || '附件下载失败')),
    })
  })
}

export function createApprovalComment(
  instanceId: string,
  parentCommentId: string | undefined,
  body: string,
  mentionIds: string[],
  attachmentIds: string[],
  visibility: CommentVisibility = 'PARTICIPANTS',
) {
  return request<ApprovalCommentItem>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments`,
    {
      method: 'POST',
      data: {
        parentCommentId: parentCommentId || null,
        body: body.trim(),
        mentionIds,
        attachmentIds,
        visibility,
      },
      header: mutationHeaders('mobile-comment'),
    },
  )
}

export function updateApprovalComment(
  instanceId: string,
  commentId: string,
  body: string,
  mentionIds: string[],
  attachmentIds: string[],
  visibility: CommentVisibility,
  expectedVersion: number,
  reason?: string,
) {
  return request<ApprovalCommentItem>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments/${encodeURIComponent(commentId)}`,
    {
      method: 'PUT',
      data: {
        body: body.trim(),
        mentionIds,
        attachmentIds,
        visibility,
        expectedVersion,
        reason: reason?.trim() || null,
      },
      header: mutationHeaders('mobile-comment-edit'),
    },
  )
}

export function deleteApprovalComment(
  instanceId: string,
  commentId: string,
  expectedVersion: number,
  reason: string,
) {
  return request<ApprovalCommentItem>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments/${encodeURIComponent(commentId)}`,
    {
      method: 'DELETE',
      data: { expectedVersion, reason: reason.trim() },
      header: mutationHeaders('mobile-comment-delete'),
    },
  )
}
