import type {
  FormDefinition,
  FormPage,
  PublishedForm,
  PublishResult,
  ValidationResult,
} from './form-types';

import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

interface ApiErrorPayload {
  code?: string;
  error?: string;
  message?: string;
}

function operationId(prefix: string) {
  const value = globalThis.crypto?.randomUUID?.() ??
    `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}-${value}`;
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
    throw new Error(
      payload?.message || payload?.error || payload?.code ||
      `请求失败（${response.status}）`,
    );
  }
  return (await response.json()) as T;
}

export function findForms(keyword = '', limit = 20, offset = 0) {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (keyword.trim()) query.set('keyword', keyword.trim());
  return request<FormPage>(`/approval/forms?${query.toString()}`);
}

export function findForm(formKey: string, version: number) {
  return request<PublishedForm>(
    `/approval/forms/${encodeURIComponent(formKey)}/versions/${version}`,
  );
}

export function findPurchasePaymentTemplate() {
  return request<FormDefinition>('/approval/forms/templates/purchase-payment');
}

export function validateForm(definition: FormDefinition) {
  return request<ValidationResult>('/approval/forms/validate', {
    body: JSON.stringify(definition),
    method: 'POST',
  });
}

export function publishForm(definition: FormDefinition) {
  const requestId = operationId('web-form-publish-request');
  return request<PublishResult>('/approval/forms/publish', {
    body: JSON.stringify(definition),
    headers: {
      'Idempotency-Key': operationId('web-form-publish'),
      'X-Request-Id': requestId,
      'X-Trace-Id': requestId,
    },
    method: 'POST',
  });
}
