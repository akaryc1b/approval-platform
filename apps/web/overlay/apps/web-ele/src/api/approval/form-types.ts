export type FormFieldType = 'ATTACHMENT' | 'MONEY' | 'TEXT';
export type FieldAccess = 'EDITABLE' | 'HIDDEN' | 'READONLY';

export interface FormFieldConstraints {
  maxLength?: number;
  minItems?: number;
  minimum?: number;
  multiple: boolean;
  precision?: number;
}

export interface FormField {
  constraints: FormFieldConstraints;
  key: string;
  label: string;
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
  revisionNumber: number;
  uiSchema: UiSchemaDefinition;
  uiSchemaHash?: string;
  values: Record<string, unknown>;
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
