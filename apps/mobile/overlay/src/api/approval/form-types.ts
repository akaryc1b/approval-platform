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
