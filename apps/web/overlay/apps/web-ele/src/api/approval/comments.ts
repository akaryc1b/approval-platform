import {
  approvalCommandHeaders,
  approvalFetch,
  approvalRequest,
} from '#/api/approval/transport';

export type CommentStatus = 'ACTIVE' | 'DELETED';
export type CommentVisibility = 'MENTIONED_ONLY' | 'PARTICIPANTS';
export type CommentRevisionType = 'CREATE' | 'DELETE' | 'EDIT';

export interface CommentUserOption {
  displayName: string;
  externalIdentityValue: string;
  identitySource: string;
  objectType: string;
  userId: string;
}

export interface ApprovalAttachment {
  attachmentId: string;
  bound: boolean;
  boundAt?: string;
  contentType: string;
  createdAt: string;
  fileName: string;
  instanceId?: string;
  sha256: string;
  sizeBytes: number;
  uploaderId: string;
}

export interface ApprovalCommentItem {
  attachments: ApprovalAttachment[];
  authorDisplayName: string;
  authorId: string;
  body: string;
  canDelete: boolean;
  canEdit: boolean;
  commentId: string;
  createdAt: string;
  currentRevision: number;
  deleteReason?: string;
  deleted: boolean;
  deletedAt?: string;
  deletedBy?: string;
  edited: boolean;
  instanceId: string;
  mentionedUsers: CommentUserOption[];
  parentCommentId?: string;
  privateComment: boolean;
  reply: boolean;
  replyToAuthorDisplayName?: string;
  replyToAuthorId?: string;
  status: CommentStatus;
  updatedAt: string;
  version: number;
  visibility: CommentVisibility;
}

export interface ApprovalCommentPage {
  hasMore: boolean;
  items: ApprovalCommentItem[];
  limit: number;
  offset: number;
  readOnly: boolean;
  total: number;
}

export interface CommentOptions {
  editWindowMinutes: number;
  instanceId: string;
  mentionCandidates: CommentUserOption[];
  readOnly: boolean;
}

export interface CommentRevisionItem {
  attachmentIds: string[];
  body: string;
  mentionedUsers: CommentUserOption[];
  occurredAt: string;
  operatorId: string;
  reason?: string;
  revisionNumber: number;
  revisionType: CommentRevisionType;
  visibility: CommentVisibility;
}

export interface CopiedInstanceItem {
  amount: number;
  businessKey: string;
  commentCount: number;
  copiedAt: string;
  copiedBy: string;
  copyMessageId: string;
  copyReadAt?: string;
  currentTaskDefinitionKey?: string;
  currentTaskName?: string;
  definitionKey: string;
  initiatorId: string;
  instanceId: string;
  purchaseOrderReference: string;
  read: boolean;
  status: 'COMPLETED' | 'REJECTED' | 'RUNNING' | 'WITHDRAWN';
  supplier: string;
  updatedAt: string;
}

export interface CopiedInstancePage {
  hasMore: boolean;
  items: CopiedInstanceItem[];
  limit: number;
  offset: number;
  total: number;
}

interface PageParameters {
  keyword?: string;
  limit: number;
  offset: number;
}

function pageQuery(parameters: PageParameters) {
  const query = new URLSearchParams({
    limit: String(parameters.limit),
    offset: String(parameters.offset),
  });
  const keyword = parameters.keyword?.trim();
  if (keyword) query.set('keyword', keyword);
  return query.toString();
}

export function findCopiedInstances(parameters: PageParameters) {
  return approvalRequest<CopiedInstancePage>(
    `/approval/instances/copied?${pageQuery(parameters)}`,
  );
}

export function findCommentOptions(instanceId: string) {
  return approvalRequest<CommentOptions>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments/options`,
  );
}

export function findApprovalComments(instanceId: string, limit = 100, offset = 0) {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  return approvalRequest<ApprovalCommentPage>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments?${query.toString()}`,
  );
}

export function findApprovalCommentRevisions(instanceId: string, commentId: string) {
  return approvalRequest<CommentRevisionItem[]>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments/${encodeURIComponent(commentId)}/revisions`,
  );
}

export function uploadApprovalAttachment(file: File) {
  const body = new FormData();
  body.append('file', file, file.name);
  return approvalRequest<ApprovalAttachment>('/approval/attachments', {
    body,
    headers: approvalCommandHeaders('web-attachment'),
    method: 'POST',
  });
}

export async function downloadApprovalAttachment(attachment: ApprovalAttachment) {
  const response = await approvalFetch(
    `/approval/attachments/${encodeURIComponent(attachment.attachmentId)}/content`,
  );
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = attachment.fileName;
  anchor.click();
  URL.revokeObjectURL(url);
}

export function createApprovalComment(
  instanceId: string,
  parentCommentId: string | undefined,
  body: string,
  mentionIds: string[],
  attachmentIds: string[],
  visibility: CommentVisibility = 'PARTICIPANTS',
) {
  return approvalRequest<ApprovalCommentItem>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments`,
    {
      body: JSON.stringify({
        attachmentIds,
        body: body.trim(),
        mentionIds,
        parentCommentId: parentCommentId || null,
        visibility,
      }),
      headers: approvalCommandHeaders('web-comment'),
      method: 'POST',
    },
  );
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
  return approvalRequest<ApprovalCommentItem>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments/${encodeURIComponent(commentId)}`,
    {
      body: JSON.stringify({
        attachmentIds,
        body: body.trim(),
        expectedVersion,
        mentionIds,
        reason: reason?.trim() || null,
        visibility,
      }),
      headers: approvalCommandHeaders('web-comment-edit'),
      method: 'PUT',
    },
  );
}

export function deleteApprovalComment(
  instanceId: string,
  commentId: string,
  expectedVersion: number,
  reason: string,
) {
  return approvalRequest<ApprovalCommentItem>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments/${encodeURIComponent(commentId)}`,
    {
      body: JSON.stringify({ expectedVersion, reason: reason.trim() }),
      headers: approvalCommandHeaders('web-comment-delete'),
      method: 'DELETE',
    },
  );
}
