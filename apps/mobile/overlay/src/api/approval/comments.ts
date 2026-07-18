import { getApprovalRuntimeConfig } from '@/platform/approval/runtime'

export interface CommentUserOption {
  displayName: string
  userId: string
}

export interface ApprovalCommentItem {
  attachmentIds: string[]
  authorDisplayName: string
  authorId: string
  body: string
  commentId: string
  createdAt: string
  instanceId: string
  mentionedUsers: CommentUserOption[]
}

export interface ApprovalCommentPage {
  hasMore: boolean
  items: ApprovalCommentItem[]
  limit: number
  offset: number
  total: number
}

export interface CommentOptions {
  instanceId: string
  mentionCandidates: CommentUserOption[]
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
  method?: 'GET' | 'POST'
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

function errorMessage(payload: unknown, statusCode: number) {
  if (payload && typeof payload === 'object') {
    const error = payload as ErrorPayload
    return error.message || error.error || error.code || `请求失败（${statusCode}）`
  }
  return `请求失败（${statusCode}）`
}

function request<T>(path: string, options: RequestOptions = {}) {
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

export function createApprovalComment(
  instanceId: string,
  body: string,
  mentionIds: string[],
  attachmentIds: string[],
) {
  const requestId = operationId('mobile-comment-request')
  return request<ApprovalCommentItem>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments`,
    {
      method: 'POST',
      data: {
        body: body.trim(),
        mentionIds,
        attachmentIds,
      },
      header: {
        'Idempotency-Key': operationId('mobile-comment'),
        'X-Request-Id': requestId,
        'X-Trace-Id': requestId,
      },
    },
  )
}
