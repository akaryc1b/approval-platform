export type FormFieldType =
  | 'TEXT'
  | 'TEXTAREA'
  | 'NUMBER'
  | 'MONEY'
  | 'RADIO'
  | 'CHECKBOX'
  | 'DATE'
  | 'DATE_RANGE'
  | 'USER'
  | 'DEPARTMENT'
  | 'ATTACHMENT'
  | 'DETAIL_TABLE'
  | 'LOCATION'
  | 'SIGNATURE'
  | 'REFERENCE';

export interface FormFieldSchema {
  key: string;
  type: FormFieldType;
  label: string;
  required?: boolean;
  defaultValue?: unknown;
  properties?: Record<string, unknown>;
}

export interface ApprovalFormSchema {
  schemaVersion: '1.0';
  formKey: string;
  name: string;
  fields: FormFieldSchema[];
}

export type FieldAccess = 'HIDDEN' | 'READONLY' | 'EDITABLE' | 'REQUIRED';

export interface NodeFieldPermission {
  nodeId: string;
  fields: Record<string, FieldAccess>;
}
