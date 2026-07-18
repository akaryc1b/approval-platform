<script lang="ts" setup>
import type { UploadFile, UploadFiles } from 'element-plus';
import type {
  FieldAccess,
  FormDefinition,
  FormField,
  UiFieldLayout,
  UiSchemaDefinition,
} from '#/api/approval/form-types';

import { computed, ref, watch } from 'vue';

import {
  ElButton,
  ElCol,
  ElCollapse,
  ElCollapseItem,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElRow,
  ElTag,
  ElUpload,
} from 'element-plus';

const props = withDefaults(defineProps<{
  fieldPermissions?: Record<string, FieldAccess>;
  modelValue?: Record<string, unknown>;
  readonly?: boolean;
  schema: FormDefinition;
  uiSchema?: UiSchemaDefinition;
}>(), {
  fieldPermissions: () => ({}),
  modelValue: () => ({}),
  readonly: false,
  uiSchema: undefined,
});

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>];
}>();

const activeSections = ref<string[]>([]);
const model = computed(() => props.modelValue || {});
const fieldByKey = computed(() => new Map(props.schema.fields.map(field => [field.key, field])));
const sections = computed(() => {
  if (props.uiSchema?.sections.length) return props.uiSchema.sections;
  return [{
    collapsed: false,
    fields: props.schema.fields.map(field => ({ fieldKey: field.key, span: 24 })),
    key: 'default',
    title: '申请信息',
  }];
});

watch(sections, (value) => {
  activeSections.value = value.filter(section => !section.collapsed).map(section => section.key);
}, { immediate: true });

function field(layout: UiFieldLayout) {
  return fieldByKey.value.get(layout.fieldKey);
}

function access(item: FormField) {
  if (props.readonly) return 'READONLY';
  return props.fieldPermissions[item.key] || 'EDITABLE';
}

function visible(item: FormField | undefined) {
  return Boolean(item && access(item) !== 'HIDDEN');
}

function disabled(item: FormField) {
  return access(item) !== 'EDITABLE';
}

function value(item: FormField) {
  return model.value[item.key];
}

function setValue(item: FormField, nextValue: unknown) {
  if (disabled(item)) return;
  emit('update:modelValue', { ...model.value, [item.key]: nextValue });
}

function attachmentNames(item: FormField) {
  const current = value(item);
  return Array.isArray(current) ? current.map(String) : [];
}

function attachmentChange(item: FormField) {
  return (_file: UploadFile, files: UploadFiles) => {
    setValue(item, files.map(file => file.name));
  };
}

function numberValue(item: FormField) {
  const current = value(item);
  return current == null || current === '' ? undefined : Number(current);
}
</script>

<template>
  <ElForm label-position="top" class="renderer">
    <ElCollapse v-model="activeSections">
      <ElCollapseItem
        v-for="section in sections"
        :key="section.key"
        :name="section.key"
        :title="section.title"
      >
        <p v-if="section.helpText" class="section-help">{{ section.helpText }}</p>
        <ElRow :gutter="16">
          <template v-for="layout in section.fields" :key="layout.fieldKey">
            <ElCol v-if="visible(field(layout))" :span="layout.span">
              <ElFormItem
                v-if="field(layout)"
                :label="field(layout)?.label"
                :required="field(layout)?.required"
              >
                <template v-if="field(layout)" #default>
                  <ElInput
                    v-if="field(layout)?.type === 'TEXT'"
                    :disabled="disabled(field(layout)!)"
                    :maxlength="field(layout)?.constraints.maxLength"
                    :model-value="String(value(field(layout)!) || '')"
                    :placeholder="layout.placeholder || '请输入'"
                    clearable
                    show-word-limit
                    @update:model-value="setValue(field(layout)!, $event)"
                  />
                  <ElInputNumber
                    v-else-if="field(layout)?.type === 'MONEY'"
                    :disabled="disabled(field(layout)!)"
                    :min="field(layout)?.constraints.minimum"
                    :model-value="numberValue(field(layout)!)"
                    :placeholder="layout.placeholder || '请输入金额'"
                    :precision="field(layout)?.constraints.precision"
                    controls-position="right"
                    @update:model-value="setValue(field(layout)!, $event)"
                  />
                  <div v-else-if="field(layout)?.type === 'ATTACHMENT'" class="attachment-field">
                    <ElUpload
                      v-if="!disabled(field(layout)!)"
                      :auto-upload="false"
                      :limit="field(layout)?.constraints.multiple ? 20 : 1"
                      :multiple="field(layout)?.constraints.multiple"
                      :on-change="attachmentChange(field(layout)!)"
                    >
                      <ElButton>选择文件</ElButton>
                    </ElUpload>
                    <div v-if="attachmentNames(field(layout)!).length" class="attachment-list">
                      <ElTag
                        v-for="name in attachmentNames(field(layout)!)"
                        :key="name"
                        effect="plain"
                      >
                        {{ name }}
                      </ElTag>
                    </div>
                    <span v-else class="field-help">暂无附件</span>
                  </div>
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
.renderer :deep(.el-input-number) {
  width: 100%;
}

.section-help,
.field-help {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.section-help {
  margin: 0 0 16px;
}

.field-help {
  display: block;
  margin-top: 8px;
}

.attachment-field,
.attachment-list {
  display: grid;
  gap: 10px;
}

.attachment-list {
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
}

@media (max-width: 768px) {
  .renderer :deep(.el-col) {
    max-width: 100%;
    flex: 0 0 100%;
  }
}
</style>
