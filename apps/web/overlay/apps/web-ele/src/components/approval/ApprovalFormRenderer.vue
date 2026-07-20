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
