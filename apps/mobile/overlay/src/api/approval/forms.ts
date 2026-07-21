import type {
  FormPage,
  FormRuntimeView,
  FormSubmissionResult,
  FormSubmissionSnapshot,
  PublishedForm,
} from './form-types'

import {
  mobileApprovalMutationHeaders,
  mobileApprovalRequest,
} from '@/api/approval/transport'

export function findForms(keyword = '', limit = 50, offset = 0) {
  const query = [
    `limit=${encodeURIComponent(String(limit))}`,
    `offset=${encodeURIComponent(String(offset))}`,
  ]
  if (keyword.trim()) query.push(`keyword=${encodeURIComponent(keyword.trim())}`)
  return mobileApprovalRequest<FormPage>(`/approval/forms?${query.join('&')}`)
}

export function findForm(formKey: string, version: number) {
  return mobileApprovalRequest<PublishedForm>(
    `/approval/forms/${encodeURIComponent(formKey)}/versions/${version}`,
  )
}

export function findStartFormRuntime(formKey: string, version: number) {
  return mobileApprovalRequest<FormRuntimeView>(
    `/approval/forms/${encodeURIComponent(formKey)}/versions/${version}/runtime`,
  )
}

export function findTaskFormRuntime(taskId: string) {
  return mobileApprovalRequest<FormRuntimeView>(
    `/approval/tasks/${encodeURIComponent(taskId)}/form-runtime`,
  )
}

export function findFormSnapshot(instanceId: string) {
  return mobileApprovalRequest<FormSubmissionSnapshot | undefined>(
    `/approval/instances/${encodeURIComponent(instanceId)}/form-snapshot`,
    { allowNotFound: true },
  )
}

export function submitForm(
  formKey: string,
  version: number,
  businessKey: string,
  values: Record<string, unknown>,
  startParameters: Record<string, unknown>,
) {
  return mobileApprovalRequest<FormSubmissionResult>(
    `/approval/forms/${encodeURIComponent(formKey)}/versions/${version}/submissions`,
    {
      method: 'POST',
      data: { businessKey, values, startParameters },
      header: mobileApprovalMutationHeaders('mobile-form-submit'),
    },
  )
}

export function resubmitFormTask(
  taskId: string,
  comment: string,
  values: Record<string, unknown>,
) {
  return mobileApprovalRequest<unknown>(
    `/approval/tasks/${encodeURIComponent(taskId)}/resubmit`,
    {
      method: 'POST',
      data: { comment: comment.trim() || null, values },
      header: mobileApprovalMutationHeaders('mobile-resubmit'),
    },
  )
}
