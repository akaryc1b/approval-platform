import type {
  FormDesignDraft,
  FormDesignDraftPage,
  FormDesignDraftStatus,
  FormDesignPreviewResult,
  FormDesignValidationReport,
  FormPackagePublishResult,
  FormDefinition,
  UiSchemaDefinition,
} from './form-types';

import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

interface ApiErrorPayload {
  code?: string;
  error?: string;
  message?: string;
}

export class FormDesignApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
    this.name = 'FormDesignApiError';
  }
}

export type DraftSource = 'BLANK' | 'PURCHASE_PAYMENT_TEMPLATE';
export type DraftSaveMode = 'AUTO_SAVE' | 'EXPLICIT';

export interface CreateDraftInput {
  formKey: string;
  formVersion: number;
  name: string;
  source: DraftSource;
  uiSchemaVersion: number;
}

export interface UpdateDraftInput {
  expectedRevision: number;
  formDefinition: FormDefinition;
  name: string;
  saveMode: DraftSaveMode;
  uiSchemaDefinition: UiSchemaDefinition;
}

function operationId(prefix: string) {
  const value = globalThis.crypto?.randomUUID?.() ??
    `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}-${value}`;
}

function writeHeaders(action: string) {
  const requestId = operationId(`web-${action}-request`);
  return {
    'Idempotency-Key': operationId(`web-${action}`),
    'X-Request-Id': requestId,
    'X-Trace-Id': requestId,
  };
}

async function request<T>(path: string, init: RequestInit = {}) {
  const runtime = getApprovalRuntimeConfig();
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  headers.set('X-Operator-Id', runtime.operatorId);
  headers.set('X-Tenant-Id', runtime.tenantId);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${runtime.apiBaseUrl}${path}`, {
    ...init,
    credentials: 'same-origin',
    headers,
  });
  if (!response.ok) {
    let payload: ApiErrorPayload | undefined;
    try {
      payload = (await response.json()) as ApiErrorPayload;
    } catch {
      payload = undefined;
    }
    throw new FormDesignApiError(
      payload?.message || payload?.error || payload?.code ||
        `请求失败（${response.status}）`,
      response.status,
      payload?.code,
    );
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export function findFormDesignDrafts(
  keyword = '',
  status?: FormDesignDraftStatus,
  limit = 50,
  offset = 0,
) {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (keyword.trim()) query.set('keyword', keyword.trim());
  if (status) query.set('status', status);
  return request<FormDesignDraftPage>(`/approval/form-design-drafts?${query.toString()}`);
}

export function findFormDesignDraft(draftId: string) {
  return request<FormDesignDraft>(
    `/approval/form-design-drafts/${encodeURIComponent(draftId)}`,
  );
}

export function createFormDesignDraft(input: CreateDraftInput) {
  return request<FormDesignDraft>('/approval/form-design-drafts', {
    body: JSON.stringify(input),
    headers: writeHeaders('form-design-create'),
    method: 'POST',
  });
}

export function updateFormDesignDraft(draftId: string, input: UpdateDraftInput) {
  return request<FormDesignDraft>(
    `/approval/form-design-drafts/${encodeURIComponent(draftId)}`,
    {
      body: JSON.stringify(input),
      headers: writeHeaders('form-design-save'),
      method: 'PUT',
    },
  );
}

export function validateFormDesignDraft(draftId: string, expectedRevision: number) {
  return request<FormDesignValidationReport>(
    `/approval/form-design-drafts/${encodeURIComponent(draftId)}/validate`,
    {
      body: JSON.stringify({ expectedRevision }),
      headers: writeHeaders('form-design-validate'),
      method: 'POST',
    },
  );
}

export function previewFormDesignDraft(draftId: string, contextKey: string) {
  return request<FormDesignPreviewResult>(
    `/approval/form-design-drafts/${encodeURIComponent(draftId)}/preview`,
    {
      body: JSON.stringify({ contextKey }),
      method: 'POST',
    },
  );
}

export function publishFormDesignDraft(
  draftId: string,
  expectedRevision: number,
  packageVersion: number,
) {
  return request<FormPackagePublishResult>(
    `/approval/form-design-drafts/${encodeURIComponent(draftId)}/publish`,
    {
      body: JSON.stringify({ expectedRevision, packageVersion }),
      headers: writeHeaders('form-package-publish'),
      method: 'POST',
    },
  );
}

export function archiveFormDesignDraft(draftId: string, expectedRevision: number) {
  return request<FormDesignDraft>(
    `/approval/form-design-drafts/${encodeURIComponent(draftId)}/archive`,
    {
      body: JSON.stringify({ expectedRevision }),
      headers: writeHeaders('form-design-archive'),
      method: 'POST',
    },
  );
}
