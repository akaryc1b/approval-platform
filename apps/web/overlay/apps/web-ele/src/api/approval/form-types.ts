export type FormFieldType =
  | 'ATTACHMENT'
  | 'BOOLEAN'
  | 'DATE'
  | 'DATETIME'
  | 'MONEY'
  | 'NUMBER'
  | 'SELECT'
  | 'TEXT'
  | 'TEXTAREA';
export type FieldAccess = 'EDITABLE' | 'HIDDEN' | 'READONLY';
export type RequiredOverride = 'INHERIT' | 'OPTIONAL' | 'REQUIRED';
export type DefaultValueType =
  | 'CURRENT_DATE'
  | 'CURRENT_DATETIME'
  | 'CURRENT_USER'
  | 'LITERAL'
  | 'NONE';

export interface FormFieldConstraints {
  maxLength?: number;
  minItems?: number;
  minimum?: number;
  multiple: boolean;
  precision?: number;
}

export interface FormDefaultValue {
  literal?: unknown;
  type: DefaultValueType;
}

export interface SelectOption {
  disabled: boolean;
  label: string;
  value: string;
}

export interface FormField {
  constraints: FormFieldConstraints;
  defaultValue?: FormDefaultValue;
  key: string;
  label: string;
  options?: SelectOption[];
  required: boolean;
  type: FormFieldType;
}

export interface FormDefinition {
  fields: FormField[];
  formKey: string;
  name: string;
  schemaVersion: string;
  version: number;
}

export interface UiFieldLayout {
  fieldKey: string;
  helpText?: string;
  placeholder?: string;
  span: number;
}

export interface UiSection {
  collapsed: boolean;
  fields: UiFieldLayout[];
  helpText?: string;
  key: string;
  title: string;
}

export interface UiFieldPermission {
  access: FieldAccess;
  fieldKey: string;
  requiredOverride?: RequiredOverride;
}

export interface UiNodePermissions {
  contextKey: string;
  fields: UiFieldPermission[];
}

export interface UiSchemaDefinition {
  formKey: string;
  formVersion: number;
  name: string;
  nodePermissions: UiNodePermissions[];
  schemaVersion: string;
  sections: UiSection[];
  version: number;
}

export interface FormRuntimeView {
  contextKey: string;
  defaultedUiSchema: boolean;
  definition: FormDefinition;
  fieldPermissions: Record<string, FieldAccess>;
  requiredFields: Record<string, boolean>;
  revisionNumber: number;
  uiSchema: UiSchemaDefinition;
  uiSchemaHash?: string;
  values: Record<string, unknown>;
}

export type FormDesignDraftStatus = 'ARCHIVED' | 'DRAFT' | 'PUBLISHED' | 'VALIDATED';

export interface FormDesignDraft {
  createdAt: string;
  createdBy: string;
  draftId: string;
  formDefinition: FormDefinition;
  formKey: string;
  name: string;
  publishedPackageVersion?: number;
  revision: number;
  sourceFormVersion?: number;
  sourceUiSchemaVersion?: number;
  status: FormDesignDraftStatus;
  tenantId: string;
  uiSchemaDefinition: UiSchemaDefinition;
  updatedAt: string;
  updatedBy: string;
}

export interface FormDesignDraftSummary {
  draftId: string;
  formKey: string;
  formVersion: number;
  name: string;
  publishedPackageVersion?: number;
  revision: number;
  status: FormDesignDraftStatus;
  uiSchemaVersion: number;
  updatedAt: string;
  updatedBy: string;
}

export interface FormDesignDraftPage {
  hasMore: boolean;
  items: FormDesignDraftSummary[];
  limit: number;
  offset: number;
  total: number;
}

export interface FormDesignValidationReport {
  draftId: string;
  errors: string[];
  fieldCount: number;
  formHash: string;
  formVersion: number;
  nodePermissionCount: number;
  revision: number;
  sectionCount: number;
  status: FormDesignDraftStatus;
  uiSchemaHash: string;
  uiSchemaVersion: number;
  valid: boolean;
  warnings: string[];
}

export interface FormDesignPreviewResult {
  contextKey: string;
  definition: FormDefinition;
  draftId: string;
  fieldPermissions: Record<string, FieldAccess>;
  formHash: string;
  requiredFields: Record<string, boolean>;
  revision: number;
  uiSchema: UiSchemaDefinition;
  uiSchemaHash: string;
  values: Record<string, unknown>;
}

export interface FormPackagePublishResult {
  draftId: string;
  draftRevision: number;
  formHash: string;
  formKey: string;
  formVersion: number;
  packageHash: string;
  packageVersion: number;
  publishedAt: string;
  publishedBy: string;
  replayedExistingPackage: boolean;
  uiSchemaHash: string;
  uiSchemaVersion: number;
}

export interface FormSummary {
  contentHash: string;
  fieldCount: number;
  formKey: string;
  name: string;
  publishedAt: string;
  publishedBy: string;
  schemaVersion: string;
  version: number;
}

export interface FormPage {
  hasMore: boolean;
  items: FormSummary[];
  limit: number;
  offset: number;
  total: number;
}

export interface PublishedForm {
  contentHash: string;
  definition: FormDefinition;
  publishedAt: string;
  publishedBy: string;
  tenantId: string;
}

export interface PublishedUiSchema {
  contentHash: string;
  definition: UiSchemaDefinition;
  publishedAt: string;
  publishedBy: string;
  tenantId: string;
}

export interface ValidationResult {
  contentHash: string;
  fieldCount: number;
  formKey: string;
  valid: boolean;
  version: number;
  warnings: string[];
}

export interface PublishResult {
  contentHash: string;
  fieldCount: number;
  formKey: string;
  name: string;
  publishedAt: string;
  publishedBy: string;
  replayedExistingVersion: boolean;
  schemaVersion: string;
  version: number;
}

export interface UiSchemaValidationResult {
  contentHash: string;
  formKey: string;
  formVersion: number;
  sectionCount: number;
  uiSchemaVersion: number;
  valid: boolean;
}

export interface UiSchemaPublishResult extends Omit<UiSchemaValidationResult, 'sectionCount' | 'valid'> {
  name: string;
  publishedAt: string;
  publishedBy: string;
  replayedExistingVersion: boolean;
}
