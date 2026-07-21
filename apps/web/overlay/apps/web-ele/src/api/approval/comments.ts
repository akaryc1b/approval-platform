import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

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

interface ApiErrorPayload {
  code?: string;
  error?: string;
  message?: string;
}

function operationId(prefix: string) {
  const randomId = globalThis.crypto?.randomUUID?.() ??
    `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}-${randomId}`;
}

function joinUrl(baseUrl: string, path: string) {
  return `${baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
}

async function parseError(response: Response) {
  let payload: ApiErrorPayload | undefined;
  try {
    payload = (await response.json()) as ApiErrorPayload;
  } catch {
    payload = undefined;
  }
  return payload?.message || payload?.error || payload?.code ||
    `请求失败（${response.status}）`;
}

function runtimeHeaders(init?: HeadersInit) {
  const runtime = getApprovalRuntimeConfig();
  const headers = new Headers(init);
  headers.set('Accept', 'application/json');
  headers.set('X-Operator-Id', runtime.operatorId);
  headers.set('X-Tenant-Id', runtime.tenantId);
  return headers;
}

async function request<T>(path: string, init: RequestInit = {}) {
  const runtime = getApprovalRuntimeConfig();
  const headers = runtimeHeaders(init.headers);
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const response = await fetch(joinUrl(runtime.apiBaseUrl, path), {
    ...init,
    credentials: 'same-origin',
    headers,
  });
  if (!response.ok) {
    throw new Error(await parseError(response));
  }
  return (await response.json()) as T;
}

async function requestBlob(path: string) {
  const runtime = getApprovalRuntimeConfig();
  const response = await fetch(joinUrl(runtime.apiBaseUrl, path), {
    credentials: 'same-origin',
    headers: runtimeHeaders(),
  });
  if (!response.ok) {
    throw new Error(await parseError(response));
  }
  return response.blob();
}

function mutationHeaders(prefix: string) {
  const requestId = operationId(`${prefix}-request`);
  return {
    'Idempotency-Key': operationId(prefix),
    'X-Request-Id': requestId,
    'X-Trace-Id': requestId,
  };
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
  return request<CopiedInstancePage>(
    `/approval/instances/copied?${pageQuery(parameters)}`,
  );
}

export function findCommentOptions(instanceId: string) {
  return request<CommentOptions>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments/options`,
  );
}

export function findApprovalComments(instanceId: string, limit = 100, offset = 0) {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  return request<ApprovalCommentPage>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments?${query.toString()}`,
  );
}

export function findApprovalCommentRevisions(instanceId: string, commentId: string) {
  return request<CommentRevisionItem[]>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments/${encodeURIComponent(commentId)}/revisions`,
  );
}

export function uploadApprovalAttachment(file: File) {
  const body = new FormData();
  body.append('file', file, file.name);
  return request<ApprovalAttachment>('/approval/attachments', {
    body,
    headers: mutationHeaders('web-attachment'),
    method: 'POST',
  });
}

export async function downloadApprovalAttachment(attachment: ApprovalAttachment) {
  const blob = await requestBlob(
    `/approval/attachments/${encodeURIComponent(attachment.attachmentId)}/content`,
  );
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
  return request<ApprovalCommentItem>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments`,
    {
      body: JSON.stringify({
        attachmentIds,
        body: body.trim(),
        mentionIds,
        parentCommentId: parentCommentId || null,
        visibility,
      }),
      headers: mutationHeaders('web-comment'),
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
  return request<ApprovalCommentItem>(
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
      headers: mutationHeaders('web-comment-edit'),
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
  return request<ApprovalCommentItem>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments/${encodeURIComponent(commentId)}`,
    {
      body: JSON.stringify({ expectedVersion, reason: reason.trim() }),
      headers: mutationHeaders('web-comment-delete'),
      method: 'DELETE',
    },
  );
}
