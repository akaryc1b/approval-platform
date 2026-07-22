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

import {
  approvalCommandHeaders,
  approvalRequest,
} from '#/api/approval/transport';

export { ApprovalApiError as FormDesignApiError } from '#/api/approval/transport';

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

export function findFormDesignDrafts(
  keyword = '',
  status?: FormDesignDraftStatus,
  limit = 50,
  offset = 0,
) {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (keyword.trim()) query.set('keyword', keyword.trim());
  if (status) query.set('status', status);
  return approvalRequest<FormDesignDraftPage>(
    `/approval/form-design-drafts?${query.toString()}`,
  );
}

export function findFormDesignDraft(draftId: string) {
  return approvalRequest<FormDesignDraft>(
    `/approval/form-design-drafts/${encodeURIComponent(draftId)}`,
  );
}

export function createFormDesignDraft(input: CreateDraftInput) {
  return approvalRequest<FormDesignDraft>('/approval/form-design-drafts', {
    body: JSON.stringify(input),
    headers: approvalCommandHeaders('form-design-create'),
    method: 'POST',
  });
}

export function updateFormDesignDraft(draftId: string, input: UpdateDraftInput) {
  return approvalRequest<FormDesignDraft>(
    `/approval/form-design-drafts/${encodeURIComponent(draftId)}`,
    {
      body: JSON.stringify(input),
      headers: approvalCommandHeaders('form-design-save'),
      method: 'PUT',
    },
  );
}

export function validateFormDesignDraft(draftId: string, expectedRevision: number) {
  return approvalRequest<FormDesignValidationReport>(
    `/approval/form-design-drafts/${encodeURIComponent(draftId)}/validate`,
    {
      body: JSON.stringify({ expectedRevision }),
      headers: approvalCommandHeaders('form-design-validate'),
      method: 'POST',
    },
  );
}

export function previewFormDesignDraft(draftId: string, contextKey: string) {
  return approvalRequest<FormDesignPreviewResult>(
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
  return approvalRequest<FormPackagePublishResult>(
    `/approval/form-design-drafts/${encodeURIComponent(draftId)}/publish`,
    {
      body: JSON.stringify({ expectedRevision, packageVersion }),
      headers: approvalCommandHeaders('form-package-publish'),
      method: 'POST',
    },
  );
}

export function archiveFormDesignDraft(draftId: string, expectedRevision: number) {
  return approvalRequest<FormDesignDraft>(
    `/approval/form-design-drafts/${encodeURIComponent(draftId)}/archive`,
    {
      body: JSON.stringify({ expectedRevision }),
      headers: approvalCommandHeaders('form-design-archive'),
      method: 'POST',
    },
  );
}
