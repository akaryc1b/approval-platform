import type {
  FormDefinition,
  FormPage,
  FormRuntimeView,
  PublishedForm,
  PublishedUiSchema,
  PublishResult,
  UiSchemaDefinition,
  UiSchemaPublishResult,
  UiSchemaValidationResult,
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

function writeHeaders(action: string) {
  const requestId = operationId(`web-${action}-request`);
  return {
    'Idempotency-Key': operationId(`web-${action}`),
    'X-Request-Id': requestId,
    'X-Trace-Id': requestId,
  };
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

export function findStartFormRuntime(formKey: string, version: number) {
  return request<FormRuntimeView>(
    `/approval/forms/${encodeURIComponent(formKey)}/versions/${version}/runtime`,
  );
}

export function findTaskFormRuntime(taskId: string) {
  return request<FormRuntimeView>(
    `/approval/tasks/${encodeURIComponent(taskId)}/form-runtime`,
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
  return request<PublishResult>('/approval/forms/publish', {
    body: JSON.stringify(definition),
    headers: writeHeaders('form-publish'),
    method: 'POST',
  });
}

export function findPurchasePaymentUiSchemaTemplate() {
  return request<UiSchemaDefinition>('/approval/ui-schemas/templates/purchase-payment');
}

export function findLatestUiSchema(formKey: string, formVersion: number) {
  return request<PublishedUiSchema>(
    `/approval/ui-schemas/forms/${encodeURIComponent(formKey)}/versions/${formVersion}/latest`,
  );
}

export function validateUiSchema(definition: UiSchemaDefinition) {
  return request<UiSchemaValidationResult>('/approval/ui-schemas/validate', {
    body: JSON.stringify(definition),
    method: 'POST',
  });
}

export function publishUiSchema(definition: UiSchemaDefinition) {
  return request<UiSchemaPublishResult>('/approval/ui-schemas/publish', {
    body: JSON.stringify(definition),
    headers: writeHeaders('ui-schema-publish'),
    method: 'POST',
  });
}
