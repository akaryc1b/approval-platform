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

import {
  approvalCommandHeaders,
  approvalRequest,
} from '#/api/approval/transport';

export function findForms(keyword = '', limit = 20, offset = 0) {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (keyword.trim()) query.set('keyword', keyword.trim());
  return approvalRequest<FormPage>(`/approval/forms?${query.toString()}`);
}

export function findForm(formKey: string, version: number) {
  return approvalRequest<PublishedForm>(
    `/approval/forms/${encodeURIComponent(formKey)}/versions/${version}`,
  );
}

export function findStartFormRuntime(formKey: string, version: number) {
  return approvalRequest<FormRuntimeView>(
    `/approval/forms/${encodeURIComponent(formKey)}/versions/${version}/runtime`,
  );
}

export function findTaskFormRuntime(taskId: string) {
  return approvalRequest<FormRuntimeView>(
    `/approval/tasks/${encodeURIComponent(taskId)}/form-runtime`,
  );
}

export function resubmitFormTask(
  taskId: string,
  comment: string,
  values: Record<string, unknown>,
) {
  return approvalRequest<unknown>(`/approval/tasks/${encodeURIComponent(taskId)}/resubmit`, {
    body: JSON.stringify({ comment: comment.trim() || null, values }),
    headers: approvalCommandHeaders('resubmit'),
    method: 'POST',
  });
}

export function findPurchasePaymentTemplate() {
  return approvalRequest<FormDefinition>('/approval/forms/templates/purchase-payment');
}

export function validateForm(definition: FormDefinition) {
  return approvalRequest<ValidationResult>('/approval/forms/validate', {
    body: JSON.stringify(definition),
    method: 'POST',
  });
}

export function publishForm(definition: FormDefinition) {
  return approvalRequest<PublishResult>('/approval/forms/publish', {
    body: JSON.stringify(definition),
    headers: approvalCommandHeaders('form-publish'),
    method: 'POST',
  });
}

export function findPurchasePaymentUiSchemaTemplate() {
  return approvalRequest<UiSchemaDefinition>('/approval/ui-schemas/templates/purchase-payment');
}

export function findLatestUiSchema(formKey: string, formVersion: number) {
  return approvalRequest<PublishedUiSchema>(
    `/approval/ui-schemas/forms/${encodeURIComponent(formKey)}/versions/${formVersion}/latest`,
  );
}

export function validateUiSchema(definition: UiSchemaDefinition) {
  return approvalRequest<UiSchemaValidationResult>('/approval/ui-schemas/validate', {
    body: JSON.stringify(definition),
    method: 'POST',
  });
}

export function publishUiSchema(definition: UiSchemaDefinition) {
  return approvalRequest<UiSchemaPublishResult>('/approval/ui-schemas/publish', {
    body: JSON.stringify(definition),
    headers: approvalCommandHeaders('ui-schema-publish'),
    method: 'POST',
  });
}
