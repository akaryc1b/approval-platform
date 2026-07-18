import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export interface CommentUserOption {
  displayName: string;
  userId: string;
}

export interface ApprovalCommentItem {
  attachmentIds: string[];
  authorDisplayName: string;
  authorId: string;
  body: string;
  commentId: string;
  createdAt: string;
  instanceId: string;
  mentionedUsers: CommentUserOption[];
}

export interface ApprovalCommentPage {
  hasMore: boolean;
  items: ApprovalCommentItem[];
  limit: number;
  offset: number;
  total: number;
}

export interface CommentOptions {
  instanceId: string;
  mentionCandidates: CommentUserOption[];
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

async function request<T>(path: string, init: RequestInit = {}) {
  const runtime = getApprovalRuntimeConfig();
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  headers.set('X-Operator-Id', runtime.operatorId);
  headers.set('X-Tenant-Id', runtime.tenantId);
  if (init.body && !headers.has('Content-Type')) {
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

export function createApprovalComment(
  instanceId: string,
  body: string,
  mentionIds: string[],
  attachmentIds: string[],
) {
  const requestId = operationId('web-comment-request');
  return request<ApprovalCommentItem>(
    `/approval/instances/${encodeURIComponent(instanceId)}/comments`,
    {
      body: JSON.stringify({
        attachmentIds,
        body: body.trim(),
        mentionIds,
      }),
      headers: {
        'Idempotency-Key': operationId('web-comment'),
        'X-Request-Id': requestId,
        'X-Trace-Id': requestId,
      },
      method: 'POST',
    },
  );
}
