export type FormFieldType
  = | 'ATTACHMENT'
    | 'BOOLEAN'
    | 'DATE'
    | 'DATETIME'
    | 'MONEY'
    | 'NUMBER'
    | 'SELECT'
    | 'TEXT'
    | 'TEXTAREA'
export type FieldAccess = 'EDITABLE' | 'HIDDEN' | 'READONLY'
export type RequiredOverride = 'INHERIT' | 'OPTIONAL' | 'REQUIRED'
export type DefaultValueType
  = 'CURRENT_DATE'
    | 'CURRENT_DATETIME'
    | 'CURRENT_USER'
    | 'LITERAL'
    | 'NONE'

export interface FormFieldConstraints {
  maxLength?: number
  minItems?: number
  minimum?: number
  multiple: boolean
  precision?: number
}

export interface FormDefaultValue {
  literal?: unknown
  type: DefaultValueType
}

export interface SelectOption {
  disabled: boolean
  label: string
  value: string
}

export interface FormField {
  constraints: FormFieldConstraints
  defaultValue?: FormDefaultValue
  key: string
  label: string
  options?: SelectOption[]
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

export type FormComponentType
  = | 'ATTACHMENT'
    | 'BOOLEAN'
    | 'BUSINESS_REFERENCE'
    | 'DATE'
    | 'DATETIME'
    | 'DEPARTMENT_SELECTOR'
    | 'MONEY'
    | 'NUMBER'
    | 'SELECT'
    | 'TEXT'
    | 'TEXTAREA'
    | 'USER_SELECTOR'
export type FormFallbackRenderer = 'READONLY_JSON' | 'READONLY_TEXT'
export type UiSectionVisibilityMode = 'ALWAYS' | 'FIELD_EQUALS' | 'FIELD_NOT_EMPTY'

export interface UiComponentDefinition {
  componentType: FormComponentType | string
  componentVersion: number
  fallbackRenderer: FormFallbackRenderer
  properties: Record<string, unknown>
}

export interface UiSectionVisibility {
  expectedValue?: unknown
  fieldKey?: string
  mode: UiSectionVisibilityMode
}

export interface UiFieldLayout {
  component?: UiComponentDefinition
  fieldKey: string
  helpText?: string
  placeholder?: string
  span: number
}

export interface UiSection {
  children?: UiSection[]
  collapsed: boolean
  collapsible?: boolean
  columns?: number
  fields: UiFieldLayout[]
  helpText?: string
  key: string
  order?: number
  readonlySummary?: boolean
  title: string
  visibility?: UiSectionVisibility
}

export interface UiFieldPermission {
  access: FieldAccess
  fieldKey: string
  requiredOverride?: RequiredOverride
}

export interface UiNodePermissions {
  contextKey: string
  fields: UiFieldPermission[]
}

export interface UiSchemaDefinition {
  formKey: string
  formVersion: number
  name: string
  nodePermissions: UiNodePermissions[]
  schemaVersion: string
  sections: UiSection[]
  version: number
}

export interface FormRuntimeView {
  contextKey: string
  defaultedUiSchema: boolean
  definition: FormDefinition
  fieldPermissions: Record<string, FieldAccess>
  requiredFields: Record<string, boolean>
  revisionNumber: number
  uiSchema: UiSchemaDefinition
  uiSchemaHash?: string
  values: Record<string, unknown>
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
    uiSchemaHash?: string
    uiSchemaVersion?: number
    values: Record<string, unknown>
  }
}
