<script lang="ts" setup>
import type { UploadFile, UploadFiles } from 'element-plus';
import type { FormDefinition, FormField } from '#/api/approval/form-types';

import { computed } from 'vue';

import {
  ElAlert,
  ElButton,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElUpload,
} from 'element-plus';

const props = withDefaults(defineProps<{
  modelValue?: Record<string, unknown>;
  readonly?: boolean;
  schema: FormDefinition;
}>(), {
  modelValue: () => ({}),
  readonly: false,
});

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>];
}>();

const model = computed(() => props.modelValue || {});

function value(field: FormField) {
  return model.value[field.key];
}

function setValue(field: FormField, nextValue: unknown) {
  emit('update:modelValue', { ...model.value, [field.key]: nextValue });
}

function attachmentNames(field: FormField) {
  const current = value(field);
  return Array.isArray(current) ? current.map(String) : [];
}

function changeAttachments(field: FormField, _file: UploadFile, files: UploadFiles) {
  setValue(field, files.map((item) => item.name));
}
</script>

<template>
  <div class="renderer">
    <ElAlert
      :closable="false"
      :title="`${schema.name} · v${schema.version}`"
      type="info"
    />
    <ElForm label-position="top">
      <ElFormItem
        v-for="field in schema.fields"
        :key="field.key"
        :label="field.label"
        :required="field.required"
      >
        <ElInput
          v-if="field.type === 'TEXT'"
          :disabled="readonly"
          :maxlength="field.constraints.maxLength"
          :model-value="String(value(field) || '')"
          clearable
          show-word-limit
          @update:model-value="setValue(field, $event)"
        />
        <ElInputNumber
          v-else-if="field.type === 'MONEY'"
          :disabled="readonly"
          :min="field.constraints.minimum"
          :model-value="Number(value(field) || 0)"
          :precision="field.constraints.precision"
          controls-position="right"
          @update:model-value="setValue(field, $event)"
        />
        <ElUpload
          v-else-if="field.type === 'ATTACHMENT'"
          :auto-upload="false"
          :disabled="readonly"
          :limit="field.constraints.multiple ? 20 : 1"
          :multiple="field.constraints.multiple"
          @change="changeAttachments(field, $event, $event ? [] : [])"
        >
          <ElButton :disabled="readonly">选择文件</ElButton>
          <template #tip>
            <span class="field-tip">
              {{ attachmentNames(field).length }} 个文件；发布后的业务页面接入附件服务。
            </span>
          </template>
        </ElUpload>
        <ElAlert
          v-else
          :closable="false"
          :title="`暂不支持字段类型 ${field.type}`"
          type="warning"
        />
      </ElFormItem>
    </ElForm>
  </div>
</template>

<style scoped>
.renderer {
  display: grid;
  gap: 16px;
}

.renderer :deep(.el-input-number) {
  width: 100%;
}

.field-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
