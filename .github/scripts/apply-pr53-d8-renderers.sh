#!/usr/bin/env bash
set -euo pipefail

python3 <<'PY'
from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected D8 renderer block not found in {path}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

pc_types = Path("apps/web/overlay/apps/web-ele/src/api/approval/form-types.ts")
replace_once(
    pc_types,
    '''export interface UiFieldLayout {
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
}''',
    '''export type FormComponentType =
  | 'ATTACHMENT'
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
  | 'USER_SELECTOR';
export type FormFallbackRenderer = 'READONLY_JSON' | 'READONLY_TEXT';
export type UiSectionVisibilityMode = 'ALWAYS' | 'FIELD_EQUALS' | 'FIELD_NOT_EMPTY';

export interface UiComponentDefinition {
  componentType: FormComponentType | string;
  componentVersion: number;
  fallbackRenderer: FormFallbackRenderer;
  properties: Record<string, unknown>;
}

export interface UiSectionVisibility {
  expectedValue?: unknown;
  fieldKey?: string;
  mode: UiSectionVisibilityMode;
}

export interface UiFieldLayout {
  component?: UiComponentDefinition;
  fieldKey: string;
  helpText?: string;
  placeholder?: string;
  span: number;
}

export interface UiSection {
  children?: UiSection[];
  collapsed: boolean;
  collapsible?: boolean;
  columns?: number;
  fields: UiFieldLayout[];
  helpText?: string;
  key: string;
  order?: number;
  readonlySummary?: boolean;
  title: string;
  visibility?: UiSectionVisibility;
}''',
)

mobile_types = Path("apps/mobile/overlay/src/api/approval/form-types.ts")
replace_once(
    mobile_types,
    '''export interface UiFieldLayout {
  fieldKey: string
  helpText?: string
  placeholder?: string
  span: number
}

export interface UiSection {
  collapsed: boolean
  fields: UiFieldLayout[]
  helpText?: string
  key: string
  title: string
}''',
    '''export type FormComponentType
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
}''',
)

package = Path("package.json")
replace_once(
    package,
    '''    "web:benchmark:designer": "node scripts/benchmarks/approval-designer-topology-benchmark.mjs",''',
    '''    "web:benchmark:designer": "node scripts/benchmarks/approval-designer-topology-benchmark.mjs",
    "web:test:form-renderer": "node --test scripts/tests/approval-form-renderer-protocol.test.mjs",''',
)
PY

cat > apps/web/overlay/apps/web-ele/src/components/approval/ApprovalFormRenderer.vue <<'VUE'
<script lang="ts" setup>
import type { UploadFile, UploadFiles } from 'element-plus';
import type {
  FieldAccess,
  FormDefinition,
  FormField,
  UiFieldLayout,
  UiSchemaDefinition,
  UiSection,
} from '#/api/approval/form-types';

import { computed, ref, watch } from 'vue';
import {
  ElButton,
  ElCol,
  ElCollapse,
  ElCollapseItem,
  ElDatePicker,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElOption,
  ElRow,
  ElSelect,
  ElSwitch,
  ElTag,
  ElUpload,
} from 'element-plus';

interface SectionEntry {
  depth: number;
  section: UiSection;
}

const props = withDefaults(defineProps<{
  fieldPermissions?: Record<string, FieldAccess>;
  modelValue?: Record<string, unknown>;
  readonly?: boolean;
  requiredFields?: Record<string, boolean>;
  schema: FormDefinition;
  uiSchema?: UiSchemaDefinition;
}>(), {
  fieldPermissions: () => ({}),
  modelValue: () => ({}),
  readonly: false,
  requiredFields: () => ({}),
  uiSchema: undefined,
});

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>];
}>();

const registeredComponents = new Set([
  'ATTACHMENT', 'BOOLEAN', 'BUSINESS_REFERENCE', 'DATE', 'DATETIME',
  'DEPARTMENT_SELECTOR', 'MONEY', 'NUMBER', 'SELECT', 'TEXT', 'TEXTAREA',
  'USER_SELECTOR',
]);
const textComponents = new Set([
  'BUSINESS_REFERENCE', 'DEPARTMENT_SELECTOR', 'TEXT', 'USER_SELECTOR',
]);
const activeSections = ref<string[]>([]);
const model = computed(() => props.modelValue || {});
const fieldByKey = computed(() => new Map(props.schema.fields.map(field => [field.key, field])));
const sectionTree = computed<UiSection[]>(() => props.uiSchema?.sections.length
  ? props.uiSchema.sections
  : [{
      children: [],
      collapsed: false,
      collapsible: true,
      columns: 1,
      fields: props.schema.fields.map(field => ({ fieldKey: field.key, span: 24 })),
      key: 'default',
      order: 0,
      readonlySummary: false,
      title: '申请信息',
      visibility: { mode: 'ALWAYS' },
    }]);
const sections = computed<SectionEntry[]>(() => flattenSections(sectionTree.value));

watch(sections, (value) => {
  activeSections.value = value
    .filter(entry => !entry.section.collapsed)
    .map(entry => entry.section.key);
}, { immediate: true });

function ordered(items: UiSection[]) {
  if (!items.some(item => item.order != null)) return items;
  return [...items].sort((left, right) => (left.order ?? 0) - (right.order ?? 0));
}

function sectionVisible(section: UiSection) {
  const visibility = section.visibility || { mode: 'ALWAYS' as const };
  if (visibility.mode === 'ALWAYS') return true;
  const current = visibility.fieldKey ? model.value[visibility.fieldKey] : undefined;
  if (visibility.mode === 'FIELD_NOT_EMPTY') {
    return current != null && current !== '' && (!Array.isArray(current) || current.length > 0);
  }
  return JSON.stringify(current) === JSON.stringify(visibility.expectedValue);
}

function flattenSections(items: UiSection[], depth = 0): SectionEntry[] {
  const result: SectionEntry[] = [];
  for (const section of ordered(items)) {
    if (!sectionVisible(section)) continue;
    result.push({ depth, section });
    result.push(...flattenSections(section.children || [], depth + 1));
  }
  return result;
}

function field(layout: UiFieldLayout) {
  return fieldByKey.value.get(layout.fieldKey);
}

function componentType(layout: UiFieldLayout, item: FormField) {
  return layout.component?.componentType || item.type;
}

function componentSupported(layout: UiFieldLayout, item: FormField) {
  return (layout.component?.componentVersion || 1) === 1
    && registeredComponents.has(componentType(layout, item));
}

function access(item: FormField, section: UiSection): FieldAccess {
  const base = props.readonly ? 'READONLY' : (props.fieldPermissions[item.key] || 'EDITABLE');
  if (base === 'HIDDEN' || base === 'READONLY') return base;
  return section.readonlySummary ? 'READONLY' : 'EDITABLE';
}

function required(item: FormField) {
  return props.requiredFields[item.key] ?? item.required;
}

function visible(item: FormField | undefined, section: UiSection) {
  return Boolean(item && access(item, section) !== 'HIDDEN');
}

function disabled(item: FormField, section: UiSection) {
  return access(item, section) !== 'EDITABLE';
}

function value(item: FormField) {
  return model.value[item.key];
}

function setValue(item: FormField, section: UiSection, nextValue: unknown) {
  if (disabled(item, section)) return;
  emit('update:modelValue', { ...model.value, [item.key]: nextValue });
}

function attachmentNames(item: FormField) {
  const current = value(item);
  return Array.isArray(current) ? current.map(String) : [];
}

function attachmentChange(item: FormField, section: UiSection) {
  return (_file: UploadFile, files: UploadFiles) => {
    setValue(item, section, files.map(file => file.name));
  };
}

function numberValue(item: FormField) {
  const current = value(item);
  return current == null || current === '' ? undefined : Number(current);
}

function selectValue(item: FormField) {
  const current = value(item);
  if (item.constraints.multiple) return Array.isArray(current) ? current.map(String) : [];
  return current == null ? undefined : String(current);
}

function booleanValue(item: FormField) {
  return value(item) === true;
}

function fallbackValue(item: FormField, layout: UiFieldLayout) {
  const current = value(item);
  if (layout.component?.fallbackRenderer === 'READONLY_JSON') {
    return JSON.stringify(current ?? null, null, 2);
  }
  return current == null || current === '' ? '—' : String(current);
}
</script>

<template>
  <ElForm label-position="top" class="renderer">
    <ElCollapse v-model="activeSections">
      <ElCollapseItem
        v-for="entry in sections"
        :key="entry.section.key"
        :disabled="entry.section.collapsible === false"
        :name="entry.section.key"
        :style="{ marginLeft: `${entry.depth * 16}px` }"
        :title="entry.section.title"
      >
        <p v-if="entry.section.helpText" class="section-help">{{ entry.section.helpText }}</p>
        <ElRow :gutter="16">
          <template v-for="layout in entry.section.fields" :key="layout.fieldKey">
            <ElCol
              v-if="visible(field(layout), entry.section)"
              :span="layout.span"
            >
              <ElFormItem
                v-if="field(layout)"
                :label="field(layout)?.label"
                :required="required(field(layout)!)"
              >
                <template v-if="field(layout)" #default>
                  <ElInput
                    v-if="componentSupported(layout, field(layout)!) && textComponents.has(componentType(layout, field(layout)!))"
                    :disabled="disabled(field(layout)!, entry.section)"
                    :maxlength="field(layout)?.constraints.maxLength"
                    :model-value="String(value(field(layout)!) || '')"
                    :placeholder="layout.placeholder || '请输入'"
                    clearable
                    @update:model-value="setValue(field(layout)!, entry.section, $event)"
                  />
                  <ElInput
                    v-else-if="componentSupported(layout, field(layout)!) && componentType(layout, field(layout)!) === 'TEXTAREA'"
                    :disabled="disabled(field(layout)!, entry.section)"
                    :maxlength="field(layout)?.constraints.maxLength"
                    :model-value="String(value(field(layout)!) || '')"
                    :placeholder="layout.placeholder || '请输入多行内容'"
                    :rows="4"
                    resize="vertical"
                    type="textarea"
                    @update:model-value="setValue(field(layout)!, entry.section, $event)"
                  />
                  <ElInputNumber
                    v-else-if="componentSupported(layout, field(layout)!) && ['MONEY', 'NUMBER'].includes(componentType(layout, field(layout)!))"
                    :disabled="disabled(field(layout)!, entry.section)"
                    :min="field(layout)?.constraints.minimum"
                    :model-value="numberValue(field(layout)!)"
                    :precision="field(layout)?.constraints.precision"
                    controls-position="right"
                    @update:model-value="setValue(field(layout)!, entry.section, $event)"
                  />
                  <ElDatePicker
                    v-else-if="componentSupported(layout, field(layout)!) && componentType(layout, field(layout)!) === 'DATE'"
                    :disabled="disabled(field(layout)!, entry.section)"
                    :model-value="String(value(field(layout)!) || '')"
                    type="date"
                    value-format="YYYY-MM-DD"
                    @update:model-value="setValue(field(layout)!, entry.section, $event)"
                  />
                  <ElDatePicker
                    v-else-if="componentSupported(layout, field(layout)!) && componentType(layout, field(layout)!) === 'DATETIME'"
                    :disabled="disabled(field(layout)!, entry.section)"
                    :model-value="String(value(field(layout)!) || '')"
                    type="datetime"
                    value-format="YYYY-MM-DDTHH:mm:ss"
                    @update:model-value="setValue(field(layout)!, entry.section, $event)"
                  />
                  <ElSwitch
                    v-else-if="componentSupported(layout, field(layout)!) && componentType(layout, field(layout)!) === 'BOOLEAN'"
                    :disabled="disabled(field(layout)!, entry.section)"
                    :model-value="booleanValue(field(layout)!)"
                    @update:model-value="setValue(field(layout)!, entry.section, $event)"
                  />
                  <ElSelect
                    v-else-if="componentSupported(layout, field(layout)!) && componentType(layout, field(layout)!) === 'SELECT'"
                    :disabled="disabled(field(layout)!, entry.section)"
                    :model-value="selectValue(field(layout)!)"
                    :multiple="field(layout)?.constraints.multiple"
                    clearable
                    filterable
                    @update:model-value="setValue(field(layout)!, entry.section, $event)"
                  >
                    <ElOption
                      v-for="option in field(layout)?.options || []"
                      :key="option.value"
                      :disabled="option.disabled"
                      :label="option.label"
                      :value="option.value"
                    />
                  </ElSelect>
                  <div
                    v-else-if="componentSupported(layout, field(layout)!) && componentType(layout, field(layout)!) === 'ATTACHMENT'"
                    class="attachment-field"
                  >
                    <ElUpload
                      v-if="!disabled(field(layout)!, entry.section)"
                      :auto-upload="false"
                      :limit="field(layout)?.constraints.multiple ? 20 : 1"
                      :multiple="field(layout)?.constraints.multiple"
                      :on-change="attachmentChange(field(layout)!, entry.section)"
                    >
                      <ElButton>选择文件</ElButton>
                    </ElUpload>
                    <div v-if="attachmentNames(field(layout)!).length" class="attachment-list">
                      <ElTag v-for="name in attachmentNames(field(layout)!)" :key="name" effect="plain">
                        {{ name }}
                      </ElTag>
                    </div>
                    <span v-else class="field-help">暂无附件</span>
                  </div>
                  <pre v-else class="safe-fallback">{{ fallbackValue(field(layout)!, layout) }}</pre>
                  <span v-if="layout.helpText" class="field-help">{{ layout.helpText }}</span>
                </template>
              </ElFormItem>
            </ElCol>
          </template>
        </ElRow>
      </ElCollapseItem>
    </ElCollapse>
  </ElForm>
</template>

<style scoped>
.renderer :deep(.el-input-number),
.renderer :deep(.el-date-editor),
.renderer :deep(.el-select) { width: 100%; }
.section-help,.field-help { color: var(--el-text-color-secondary); font-size: 12px; }
.section-help { margin: 0 0 16px; }
.field-help { display: block; margin-top: 8px; }
.attachment-field,.attachment-list { display: grid; gap: 10px; }
.attachment-list { grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); }
.safe-fallback { margin: 0; padding: 12px; overflow: auto; border-radius: 8px; background: var(--el-fill-color-light); white-space: pre-wrap; }
@media (max-width: 768px) {
  .renderer :deep(.el-col) { max-width: 100%; flex: 0 0 100%; }
}
</style>
VUE

cat > apps/mobile/overlay/src/components/approval/ApprovalFormRenderer.vue <<'VUE'
<script lang="ts" setup>
import type { ApprovalAttachment } from '@/api/approval/comments'
import type {
  FieldAccess,
  FormDefinition,
  FormField,
  SelectOption,
  UiFieldLayout,
  UiSchemaDefinition,
  UiSection,
} from '@/api/approval/form-types'
import { uploadApprovalAttachment } from '@/api/approval/comments'

interface SectionEntry { depth: number, section: UiSection }

const props = withDefaults(defineProps<{
  fieldPermissions?: Record<string, FieldAccess>
  modelValue?: Record<string, unknown>
  readonly?: boolean
  requiredFields?: Record<string, boolean>
  schema: FormDefinition
  uiSchema?: UiSchemaDefinition
}>(), {
  fieldPermissions: () => ({}),
  modelValue: () => ({}),
  readonly: false,
  requiredFields: () => ({}),
  uiSchema: undefined,
})
const emit = defineEmits<{ 'update:modelValue': [value: Record<string, unknown>] }>()
const registeredComponents = new Set([
  'ATTACHMENT', 'BOOLEAN', 'BUSINESS_REFERENCE', 'DATE', 'DATETIME',
  'DEPARTMENT_SELECTOR', 'MONEY', 'NUMBER', 'SELECT', 'TEXT', 'TEXTAREA',
  'USER_SELECTOR',
])
const textComponents = new Set(['BUSINESS_REFERENCE', 'DEPARTMENT_SELECTOR', 'TEXT', 'USER_SELECTOR'])
const uploading = ref(false)
const uploadedByField = ref<Record<string, ApprovalAttachment[]>>({})
const collapsed = ref<Record<string, boolean>>({})
const fieldByKey = computed(() => new Map(props.schema.fields.map(item => [item.key, item])))
const sectionTree = computed<UiSection[]>(() => props.uiSchema?.sections.length
  ? props.uiSchema.sections
  : [{
      children: [], collapsed: false, collapsible: true, columns: 1,
      fields: props.schema.fields.map(item => ({ fieldKey: item.key, span: 24 })),
      key: 'default', order: 0, readonlySummary: false, title: '申请信息',
      visibility: { mode: 'ALWAYS' },
    }])
const sections = computed<SectionEntry[]>(() => flattenSections(sectionTree.value))

watch(sections, value => {
  collapsed.value = Object.fromEntries(value.map(entry => [entry.section.key, entry.section.collapsed]))
}, { immediate: true })

function ordered(items: UiSection[]) {
  if (!items.some(item => item.order != null)) return items
  return [...items].sort((left, right) => (left.order ?? 0) - (right.order ?? 0))
}
function sectionVisible(section: UiSection) {
  const visibility = section.visibility || { mode: 'ALWAYS' as const }
  if (visibility.mode === 'ALWAYS') return true
  const current = visibility.fieldKey ? props.modelValue?.[visibility.fieldKey] : undefined
  if (visibility.mode === 'FIELD_NOT_EMPTY') {
    return current != null && current !== '' && (!Array.isArray(current) || current.length > 0)
  }
  return JSON.stringify(current) === JSON.stringify(visibility.expectedValue)
}
function flattenSections(items: UiSection[], depth = 0): SectionEntry[] {
  const result: SectionEntry[] = []
  for (const section of ordered(items)) {
    if (!sectionVisible(section)) continue
    result.push({ depth, section })
    result.push(...flattenSections(section.children || [], depth + 1))
  }
  return result
}
function field(layout: UiFieldLayout) { return fieldByKey.value.get(layout.fieldKey) }
function componentType(layout: UiFieldLayout, item: FormField) {
  return layout.component?.componentType || item.type
}
function componentSupported(layout: UiFieldLayout, item: FormField) {
  return (layout.component?.componentVersion || 1) === 1
    && registeredComponents.has(componentType(layout, item))
}
function access(item: FormField, section?: UiSection): FieldAccess {
  const base = props.readonly ? 'READONLY' : (props.fieldPermissions[item.key] || 'EDITABLE')
  if (base === 'HIDDEN' || base === 'READONLY') return base
  return section?.readonlySummary ? 'READONLY' : 'EDITABLE'
}
function required(item: FormField) { return props.requiredFields[item.key] ?? item.required }
function visible(item: FormField | undefined, section: UiSection) {
  return Boolean(item && access(item, section) !== 'HIDDEN')
}
function disabled(item: FormField, section?: UiSection) { return access(item, section) !== 'EDITABLE' }
function value(item: FormField) { return props.modelValue?.[item.key] }
function textValue(item: FormField) { const current = value(item); return current == null ? '' : String(current) }
function booleanValue(item: FormField) { return value(item) === true }
function setValue(item: FormField, section: UiSection, nextValue: unknown) {
  if (!disabled(item, section)) emit('update:modelValue', { ...props.modelValue, [item.key]: nextValue })
}
function detailValue(event: unknown) { return (event as { detail?: { value?: unknown } })?.detail?.value }
function setTextFromEvent(item: FormField, section: UiSection, event: unknown) {
  setValue(item, section, detailValue(event) ?? '')
}
function setBooleanFromEvent(item: FormField, section: UiSection, event: unknown) {
  setValue(item, section, detailValue(event) === true)
}
function enabledOptions(item: FormField): SelectOption[] { return (item.options || []).filter(option => !option.disabled) }
function selectedOptionIndex(item: FormField) {
  const index = enabledOptions(item).findIndex(option => option.value === textValue(item)); return index < 0 ? 0 : index
}
function selectOption(item: FormField, section: UiSection, event: unknown) {
  const option = enabledOptions(item)[Number(detailValue(event))]; if (option) setValue(item, section, option.value)
}
function selectedOptionLabel(item: FormField) {
  return (item.options || []).find(option => option.value === textValue(item))?.label || textValue(item) || '请选择'
}
function multipleValues(item: FormField) { const current = value(item); return Array.isArray(current) ? current.map(String) : [] }
function toggleOption(item: FormField, section: UiSection, option: SelectOption) {
  if (disabled(item, section) || option.disabled) return
  const selected = multipleValues(item)
  setValue(item, section, selected.includes(option.value)
    ? selected.filter(value => value !== option.value) : [...selected, option.value])
}
function datePart(item: FormField) { return textValue(item).slice(0, 10) }
function timePart(item: FormField) { return textValue(item).slice(11, 16) || '00:00' }
function setDate(item: FormField, section: UiSection, event: unknown) {
  const date = String(detailValue(event) || '')
  setValue(item, section, item.type === 'DATE' ? date : `${date}T${timePart(item)}:00`)
}
function setTime(item: FormField, section: UiSection, event: unknown) {
  const time = String(detailValue(event) || '00:00')
  setValue(item, section, `${datePart(item) || new Date().toISOString().slice(0, 10)}T${time}:00`)
}
function attachmentIds(item: FormField) { const current = value(item); return Array.isArray(current) ? current.map(String) : [] }
function attachmentItems(item: FormField) {
  const known = uploadedByField.value[item.key] || []
  return known.length ? known.map(file => ({ id: file.attachmentId, name: file.fileName }))
    : attachmentIds(item).map(id => ({ id, name: id }))
}
function removeAttachment(item: FormField, section: UiSection, attachmentId: string) {
  if (disabled(item, section)) return
  uploadedByField.value = { ...uploadedByField.value, [item.key]: (uploadedByField.value[item.key] || [])
    .filter(file => file.attachmentId !== attachmentId) }
  setValue(item, section, attachmentIds(item).filter(id => id !== attachmentId))
}
function chooseFiles(item: FormField, section: UiSection) {
  if (disabled(item, section) || uploading.value) return
  const remaining = (item.constraints.multiple ? 20 : 1) - attachmentIds(item).length
  if (remaining <= 0) return uni.showToast({ title: '已达到附件数量上限', icon: 'none' })
  uni.chooseMessageFile({
    count: remaining, type: 'file',
    success: async result => {
      uploading.value = true
      try {
        const uploaded = [...(uploadedByField.value[item.key] || [])]
        for (const file of result.tempFiles) {
          if (file.size > 10 * 1024 * 1024) throw new Error(`${file.name} 超过 10 MiB`)
          uploaded.push(await uploadApprovalAttachment(file.path))
        }
        uploadedByField.value = { ...uploadedByField.value, [item.key]: uploaded }
        setValue(item, section, uploaded.map(file => file.attachmentId))
      } catch (error) {
        uni.showToast({ title: error instanceof Error ? error.message : '附件上传失败', icon: 'none' })
      } finally { uploading.value = false }
    },
  })
}
function toggleSection(section: UiSection) {
  if (section.collapsible === false) return
  collapsed.value = { ...collapsed.value, [section.key]: !collapsed.value[section.key] }
}
function fallbackValue(item: FormField, layout: UiFieldLayout) {
  const current = value(item)
  return layout.component?.fallbackRenderer === 'READONLY_JSON'
    ? JSON.stringify(current ?? null) : (current == null || current === '' ? '—' : String(current))
}
function validate() {
  if (uploading.value) return '附件正在上传，请稍后'
  for (const item of props.schema.fields) {
    if (access(item) === 'HIDDEN') continue
    const current = value(item)
    const empty = current == null || current === '' || (Array.isArray(current) && current.length === 0)
    if (required(item) && empty) return `${item.label}不能为空`
    if (['MONEY', 'NUMBER'].includes(item.type) && !empty && !Number.isFinite(Number(current))) return `${item.label}格式不正确`
    if (['TEXT', 'TEXTAREA'].includes(item.type) && item.constraints.maxLength
      && textValue(item).length > item.constraints.maxLength) return `${item.label}不能超过${item.constraints.maxLength}个字符`
    if (item.type === 'ATTACHMENT' && (Array.isArray(current) ? current.length : 0) < (item.constraints.minItems || 0)) {
      return `${item.label}至少选择${item.constraints.minItems}个文件`
    }
  }
  return ''
}
function editableValues() {
  const sectionByField = new Map(sections.value.flatMap(entry => entry.section.fields.map(layout => [layout.fieldKey, entry.section] as const)))
  return Object.fromEntries(props.schema.fields
    .filter(item => access(item, sectionByField.get(item.key)) === 'EDITABLE' && item.key in (props.modelValue || {}))
    .map(item => [item.key, props.modelValue?.[item.key]]))
}
defineExpose({ editableValues, uploading, validate })
</script>

<template>
  <view class="renderer">
    <view
      v-for="entry in sections"
      :key="entry.section.key"
      class="section-card"
      :style="{ marginLeft: `${entry.depth * 20}rpx` }"
    >
      <view class="section-header" @click="toggleSection(entry.section)">
        <view>
          <text class="section-title">{{ entry.section.title }}</text>
          <text v-if="entry.section.helpText" class="section-help">{{ entry.section.helpText }}</text>
        </view>
        <text v-if="entry.section.collapsible !== false" class="section-toggle">
          {{ collapsed[entry.section.key] ? '展开' : '收起' }}
        </text>
      </view>
      <view
        v-if="!collapsed[entry.section.key]"
        class="section-grid"
        :style="{ gridTemplateColumns: `repeat(${Math.max(1, Math.min(4, entry.section.columns || 1))}, minmax(0, 1fr))` }"
      >
        <template v-for="layout in entry.section.fields" :key="layout.fieldKey">
          <view
            v-if="visible(field(layout), entry.section) && field(layout)"
            class="field-card"
            :class="{ 'field-card--wide': layout.span >= 24 }"
          >
            <view class="field-label">
              <text>{{ field(layout)?.label }}</text>
              <text v-if="required(field(layout)!)" class="required">必填</text>
            </view>
            <wd-input
              v-if="componentSupported(layout, field(layout)!) && textComponents.has(componentType(layout, field(layout)!))"
              :disabled="disabled(field(layout)!, entry.section)"
              :maxlength="field(layout)?.constraints.maxLength"
              :model-value="textValue(field(layout)!)"
              clearable no-border :placeholder="layout.placeholder || '请输入'"
              @update:model-value="setValue(field(layout)!, entry.section, $event)"
            />
            <textarea
              v-else-if="componentSupported(layout, field(layout)!) && componentType(layout, field(layout)!) === 'TEXTAREA'"
              class="native-textarea" :disabled="disabled(field(layout)!, entry.section)"
              :maxlength="field(layout)?.constraints.maxLength" :value="textValue(field(layout)!)"
              @input="setTextFromEvent(field(layout)!, entry.section, $event)"
            />
            <wd-input
              v-else-if="componentSupported(layout, field(layout)!) && ['MONEY', 'NUMBER'].includes(componentType(layout, field(layout)!))"
              :disabled="disabled(field(layout)!, entry.section)" :model-value="textValue(field(layout)!)"
              clearable no-border type="digit" @update:model-value="setValue(field(layout)!, entry.section, $event)"
            />
            <picker
              v-else-if="componentSupported(layout, field(layout)!) && componentType(layout, field(layout)!) === 'DATE'"
              :disabled="disabled(field(layout)!, entry.section)" mode="date" :value="datePart(field(layout)!)"
              @change="setDate(field(layout)!, entry.section, $event)"
            ><view class="picker-value">{{ datePart(field(layout)!) || '请选择日期' }}</view></picker>
            <view
              v-else-if="componentSupported(layout, field(layout)!) && componentType(layout, field(layout)!) === 'DATETIME'"
              class="datetime-row"
            >
              <picker :disabled="disabled(field(layout)!, entry.section)" mode="date" :value="datePart(field(layout)!)"
                @change="setDate(field(layout)!, entry.section, $event)"><view class="picker-value">{{ datePart(field(layout)!) || '选择日期' }}</view></picker>
              <picker :disabled="disabled(field(layout)!, entry.section)" mode="time" :value="timePart(field(layout)!)"
                @change="setTime(field(layout)!, entry.section, $event)"><view class="picker-value">{{ timePart(field(layout)!) }}</view></picker>
            </view>
            <view
              v-else-if="componentSupported(layout, field(layout)!) && componentType(layout, field(layout)!) === 'BOOLEAN'"
              class="switch-row"
            >
              <text>{{ booleanValue(field(layout)!) ? '是' : '否' }}</text>
              <switch :checked="booleanValue(field(layout)!)" :disabled="disabled(field(layout)!, entry.section)"
                @change="setBooleanFromEvent(field(layout)!, entry.section, $event)" />
            </view>
            <template v-else-if="componentSupported(layout, field(layout)!) && componentType(layout, field(layout)!) === 'SELECT'">
              <view v-if="field(layout)?.constraints.multiple" class="option-chips">
                <button v-for="option in field(layout)?.options || []" :key="option.value" class="option-chip"
                  :class="{ active: multipleValues(field(layout)!).includes(option.value), disabled: option.disabled }"
                  :disabled="disabled(field(layout)!, entry.section) || option.disabled"
                  @click="toggleOption(field(layout)!, entry.section, option)">{{ option.label }}</button>
              </view>
              <picker v-else :disabled="disabled(field(layout)!, entry.section)" :range="enabledOptions(field(layout)!)"
                range-key="label" :value="selectedOptionIndex(field(layout)!)"
                @change="selectOption(field(layout)!, entry.section, $event)"><view class="picker-value">{{ selectedOptionLabel(field(layout)!) }}</view></picker>
            </template>
            <view
              v-else-if="componentSupported(layout, field(layout)!) && componentType(layout, field(layout)!) === 'ATTACHMENT'"
              class="attachment-field"
            >
              <wd-button v-if="!disabled(field(layout)!, entry.section)" size="small" plain :disabled="uploading"
                :loading="uploading" @click="chooseFiles(field(layout)!, entry.section)">选择并上传文件</wd-button>
              <view v-for="file in attachmentItems(field(layout)!)" :key="file.id" class="file-row">
                <text class="file-name">{{ file.name }}</text>
                <text v-if="!disabled(field(layout)!, entry.section)" class="remove"
                  @click="removeAttachment(field(layout)!, entry.section, file.id)">移除</text>
              </view>
            </view>
            <text v-else class="safe-fallback">{{ fallbackValue(field(layout)!, layout) }}</text>
            <text v-if="layout.helpText" class="hint">{{ layout.helpText }}</text>
          </view>
        </template>
      </view>
    </view>
  </view>
</template>

<style scoped>
.renderer,.attachment-field,.section-card,.section-header>view{display:grid;gap:18rpx}.section-card{padding:26rpx;border-radius:24rpx;background:var(--wot-color-white,var(--uni-bg-color));box-shadow:0 8rpx 24rpx rgb(15 23 42 / 5%)}.section-header,.field-label,.file-row,.switch-row{display:flex;align-items:center;justify-content:space-between;gap:16rpx}.section-grid{display:grid;gap:18rpx}.field-card{display:grid;align-content:start;gap:14rpx;min-width:0;padding:20rpx;border-radius:18rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}.field-card--wide{grid-column:1/-1}.section-title,.field-label{color:var(--wot-color-content,var(--uni-text-color));font-weight:700}.section-title{font-size:29rpx}.section-help,.section-toggle,.hint,.file-name,.safe-fallback{color:var(--wot-color-content-secondary,var(--uni-text-color-grey));font-size:24rpx}.required,.remove{color:var(--wot-color-danger,var(--uni-color-error));font-size:22rpx}.file-row{padding:10rpx 14rpx;border-radius:14rpx;background:var(--wot-color-white,var(--uni-bg-color))}.file-name{flex:1;overflow-wrap:anywhere}.native-textarea{box-sizing:border-box;width:100%;min-height:180rpx;padding:16rpx;border-radius:14rpx;background:var(--wot-color-white,var(--uni-bg-color));font-size:28rpx}.picker-value,.safe-fallback{min-height:44rpx;padding:16rpx;border-radius:14rpx;background:var(--wot-color-white,var(--uni-bg-color));font-size:28rpx}.datetime-row{display:grid;grid-template-columns:1fr .7fr;gap:12rpx}.option-chips{display:flex;flex-wrap:wrap;gap:12rpx}.option-chip{padding:10rpx 18rpx;border:2rpx solid var(--wot-color-border,var(--uni-border-color));border-radius:999rpx;background:var(--wot-color-white,var(--uni-bg-color));font-size:24rpx;line-height:1.5}.option-chip.active{color:var(--wot-color-theme,var(--uni-color-primary));border-color:var(--wot-color-theme,var(--uni-color-primary))}.option-chip.disabled{opacity:.45}@media(max-width:420px){.section-grid{grid-template-columns:1fr!important}.field-card{grid-column:1/-1}}
</style>
VUE

mkdir -p scripts/lib scripts/tests
cat > scripts/lib/approval-form-renderer-protocol.mjs <<'JS'
export const registeredComponentTypes = new Set([
  'ATTACHMENT', 'BOOLEAN', 'BUSINESS_REFERENCE', 'DATE', 'DATETIME',
  'DEPARTMENT_SELECTOR', 'MONEY', 'NUMBER', 'SELECT', 'TEXT', 'TEXTAREA',
  'USER_SELECTOR',
]);

export function effectiveFieldAccess(baseAccess, readonlySummary = false) {
  if (baseAccess === 'HIDDEN' || baseAccess === 'READONLY') return baseAccess;
  return readonlySummary ? 'READONLY' : 'EDITABLE';
}

export function resolveComponent(fieldType, component) {
  const componentType = component?.componentType || fieldType;
  const componentVersion = component?.componentVersion || 1;
  return {
    componentType,
    componentVersion,
    registered: componentVersion === 1 && registeredComponentTypes.has(componentType),
    fallbackRenderer: component?.fallbackRenderer || 'READONLY_TEXT',
  };
}

export function flattenVisibleSections(sections, values = {}, depth = 0) {
  const ordered = sections.some(section => section.order != null)
    ? [...sections].sort((left, right) => (left.order ?? 0) - (right.order ?? 0))
    : sections;
  const result = [];
  for (const section of ordered) {
    const visibility = section.visibility || { mode: 'ALWAYS' };
    const current = visibility.fieldKey ? values[visibility.fieldKey] : undefined;
    const visible = visibility.mode === 'ALWAYS'
      || visibility.mode === 'FIELD_NOT_EMPTY'
        && current != null && current !== '' && (!Array.isArray(current) || current.length > 0)
      || visibility.mode === 'FIELD_EQUALS'
        && JSON.stringify(current) === JSON.stringify(visibility.expectedValue);
    if (!visible) continue;
    result.push({ depth, section });
    result.push(...flattenVisibleSections(section.children || [], values, depth + 1));
  }
  return result;
}
JS

cat > scripts/tests/approval-form-renderer-protocol.test.mjs <<'JS'
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  effectiveFieldAccess,
  flattenVisibleSections,
  resolveComponent,
} from '../lib/approval-form-renderer-protocol.mjs';

test('flattens nested sections in deterministic visible order', () => {
  const sections = [
    { key: 'second', order: 1, visibility: { mode: 'FIELD_NOT_EMPTY', fieldKey: 'owner' }, children: [] },
    { key: 'first', order: 0, visibility: { mode: 'ALWAYS' }, children: [
      { key: 'child', order: 0, visibility: { mode: 'FIELD_EQUALS', fieldKey: 'kind', expectedValue: 'A' }, children: [] },
    ] },
  ];
  assert.deepEqual(
    flattenVisibleSections(sections, { kind: 'A', owner: 'user-1' }).map(entry => [entry.section.key, entry.depth]),
    [['first', 0], ['child', 1], ['second', 0]],
  );
  assert.deepEqual(
    flattenVisibleSections(sections, { kind: 'B', owner: '' }).map(entry => entry.section.key),
    ['first'],
  );
});

test('section readonly can only reduce field access', () => {
  assert.equal(effectiveFieldAccess('HIDDEN', false), 'HIDDEN');
  assert.equal(effectiveFieldAccess('READONLY', false), 'READONLY');
  assert.equal(effectiveFieldAccess('EDITABLE', true), 'READONLY');
  assert.equal(effectiveFieldAccess('EDITABLE', false), 'EDITABLE');
});

test('resolves only host-registered versioned components and safe fallback', () => {
  assert.deepEqual(resolveComponent('TEXT', undefined), {
    componentType: 'TEXT', componentVersion: 1, registered: true, fallbackRenderer: 'READONLY_TEXT',
  });
  assert.equal(resolveComponent('TEXT', { componentType: 'USER_SELECTOR', componentVersion: 1 }).registered, true);
  assert.equal(resolveComponent('TEXT', { componentType: 'REMOTE_SCRIPT', componentVersion: 1 }).registered, false);
  assert.equal(resolveComponent('TEXT', { componentType: 'TEXT', componentVersion: 2 }).registered, false);
});
JS

rm -f .github/scripts/apply-pr53-d8-renderers.sh
