export type FormFieldType = 'ATTACHMENT' | 'MONEY' | 'TEXT'

export interface FormFieldConstraints {
  maxLength?: number
  minItems?: number
  minimum?: number
  multiple: boolean
  precision?: number
}

export interface FormField {
  constraints: FormFieldConstraints
  key: string
  label: string
  required: boolean
  type: FormFieldType
}

export interface FormDefinition {
  fields: FormField[]
  formKey: string
  name: string
  schemaVersion: string
  version: number
}

export interface FormSummary {
  contentHash: string
  fieldCount: number
  formKey: string
  name: string
  publishedAt: string
  publishedBy: string
  schemaVersion: string
  version: number
}

export interface FormPage {
  hasMore: boolean
  items: FormSummary[]
  limit: number
  offset: number
  total: number
}

export interface PublishedForm {
  contentHash: string
  definition: FormDefinition
  publishedAt: string
  publishedBy: string
  tenantId: string
}

export interface FormSubmissionResult {
  businessKey: string
  formKey: string
  formVersion: number
  instanceId: string
  replayedExistingSubmission: boolean
  schemaHash: string
  submissionId: string
  submittedAt: string
  submittedBy: string
  values: Record<string, unknown>
}

export interface FormSubmissionSnapshot {
  definition: FormDefinition
  submission: {
    businessKey: string
    formKey: string
    formVersion: number
    instanceId: string
    schemaHash: string
    startParameters: Record<string, unknown>
    submissionId: string
    submittedAt: string
    submittedBy: string
    tenantId: string
    values: Record<string, unknown>
  }
}
