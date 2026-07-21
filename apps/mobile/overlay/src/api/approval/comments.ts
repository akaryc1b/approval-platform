import {
  mobileApprovalError,
  mobileApprovalHeaders,
  mobileApprovalMutationHeaders,
  mobileApprovalOperationId,
  mobileApprovalRequest,
  mobileApprovalUrl,
} from '@/api/approval/transport'

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

function pageQuery(keyword: string, limit: number, offset: number) {
  const query = [
    `limit=${encodeURIComponent(String(limit))}`,
    `offset=${encodeURIComponent(String(offset))}`,
  ]
  const normalized = keyword.trim()
  if (normalized) query.push(`keyword=${encodeURIComponent(normalized)}`)
  return query.join('&')
}

function fileErrorPayload(payload: unknown, requestId: string) {
  if (payload && typeof payload === 'object') {
    const value = payload as Record<string, unknown>
    return { ...value, requestId: value.requestId || requestId }
  }
  if (typeof payload === 'string' && payload.trim()) {
    return { message: payload, requestId }
  }
  return { requestId }
}

export function findCopiedInstances(keyword: string, limit: number, offset: number) {
  return mobileApprovalRequest<CopiedInstancePage>(
    `/approval/instances/copied?${pageQuery(keyword, limit, offset)}`,
  )
}

export function findCommentOptions(instanceId: string) {
  return mobileApprovalRequest<CommentOptions>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments/options`,
  )
}

export function findApprovalComments(instanceId: string, limit = 100, offset = 0) {
  return mobileApprovalRequest<ApprovalCommentPage>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments?limit=${limit}&offset=${offset}`,
  )
}

export function findApprovalCommentRevisions(instanceId: string, commentId: string) {
  return mobileApprovalRequest<CommentRevisionItem[]>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments/${encodeURIComponent(commentId)}/revisions`,
  )
}

export function uploadApprovalAttachment(filePath: string) {
  const header = mobileApprovalHeaders(mobileApprovalMutationHeaders('mobile-attachment'))
  const requestId = header['X-Request-Id']
  return new Promise<ApprovalAttachment>((resolve, reject) => {
    uni.uploadFile({
      url: mobileApprovalUrl('/approval/attachments'),
      filePath,
      name: 'file',
      header,
      success: (response) => {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          try {
            resolve(JSON.parse(response.data) as ApprovalAttachment)
          }
          catch {
            reject(mobileApprovalError({
              code: 'APPROVAL_ATTACHMENT_RESPONSE_INVALID',
              message: '附件响应解析失败',
              requestId,
            }, 502))
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
        reject(mobileApprovalError(
          fileErrorPayload(payload, requestId),
          response.statusCode,
        ))
      },
      fail: error => reject(mobileApprovalError({
        code: 'APPROVAL_ATTACHMENT_UPLOAD_NETWORK_ERROR',
        message: error.errMsg || '附件上传失败',
        requestId,
        retryable: true,
      }, 0)),
    })
  })
}

export function downloadApprovalAttachment(attachment: ApprovalAttachment) {
  const requestId = mobileApprovalOperationId('mobile-attachment-download-request')
  const header = mobileApprovalHeaders({
    'X-Request-Id': requestId,
    'X-Trace-Id': requestId,
  })
  return new Promise<string>((resolve, reject) => {
    uni.downloadFile({
      url: mobileApprovalUrl(
        `/approval/attachments/${encodeURIComponent(attachment.attachmentId)}/content`,
      ),
      header,
      success: (response) => {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.tempFilePath)
          return
        }
        reject(mobileApprovalError({
          code: 'APPROVAL_ATTACHMENT_DOWNLOAD_FAILED',
          message: `附件下载失败（${response.statusCode}）`,
          requestId,
        }, response.statusCode))
      },
      fail: error => reject(mobileApprovalError({
        code: 'APPROVAL_ATTACHMENT_DOWNLOAD_NETWORK_ERROR',
        message: error.errMsg || '附件下载失败',
        requestId,
        retryable: true,
      }, 0)),
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
  return mobileApprovalRequest<ApprovalCommentItem>(
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
      header: mobileApprovalMutationHeaders('mobile-comment'),
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
  return mobileApprovalRequest<ApprovalCommentItem>(
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
      header: mobileApprovalMutationHeaders('mobile-comment-edit'),
    },
  )
}

export function deleteApprovalComment(
  instanceId: string,
  commentId: string,
  expectedVersion: number,
  reason: string,
) {
  return mobileApprovalRequest<ApprovalCommentItem>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments/${encodeURIComponent(commentId)}`,
    {
      method: 'DELETE',
      data: { expectedVersion, reason: reason.trim() },
      header: mobileApprovalMutationHeaders('mobile-comment-delete'),
    },
  )
}
