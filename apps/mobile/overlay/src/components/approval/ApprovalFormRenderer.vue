<script lang="ts" setup>
import type { FormDefinition, FormField } from '@/api/approval/form-types'

const props = withDefaults(defineProps<{
  modelValue?: Record<string, unknown>
  readonly?: boolean
  schema: FormDefinition
}>(), { modelValue: () => ({}), readonly: false })

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>]
}>()

function value(field: FormField) {
  return props.modelValue?.[field.key]
}

function textValue(field: FormField) {
  const current = value(field)
  return current == null ? '' : String(current)
}

function setValue(field: FormField, nextValue: unknown) {
  emit('update:modelValue', { ...props.modelValue, [field.key]: nextValue })
}

function files(field: FormField) {
  const current = value(field)
  return Array.isArray(current) ? current.map(String) : []
}

function chooseFiles(field: FormField) {
  if (props.readonly) return
  uni.chooseMessageFile({
    count: field.constraints.multiple ? 20 : 1,
    type: 'file',
    success: result => setValue(field, result.tempFiles.map(file => file.name)),
  })
}

function validate() {
  for (const field of props.schema.fields) {
    const current = value(field)
    const empty = current == null || current === ''
      || (Array.isArray(current) && current.length === 0)
    if (field.required && empty) return `${field.label}不能为空`
    if (field.type === 'MONEY' && !empty) {
      const amount = Number(current)
      if (!Number.isFinite(amount)) return `${field.label}格式不正确`
      if (field.constraints.minimum != null && amount < field.constraints.minimum) {
        return `${field.label}不能小于${field.constraints.minimum}`
      }
    }
    if (field.type === 'ATTACHMENT') {
      const count = Array.isArray(current) ? current.length : 0
      if (count < (field.constraints.minItems || 0)) {
        return `${field.label}至少选择${field.constraints.minItems}个文件`
      }
    }
  }
  return ''
}

defineExpose({ validate })
</script>

<template>
  <view class="renderer">
    <view v-for="field in schema.fields" :key="field.key" class="field-card">
      <view class="field-label">
        <text>{{ field.label }}</text>
        <text v-if="field.required" class="required">必填</text>
      </view>
      <wd-input
        v-if="field.type === 'TEXT'"
        :disabled="readonly"
        :maxlength="field.constraints.maxLength"
        :model-value="textValue(field)"
        clearable
        no-border
        placeholder="请输入"
        @update:model-value="setValue(field, $event)"
      />
      <wd-input
        v-else-if="field.type === 'MONEY'"
        :disabled="readonly"
        :model-value="textValue(field)"
        clearable
        no-border
        placeholder="请输入金额"
        type="digit"
        @update:model-value="setValue(field, $event)"
      />
      <view v-else-if="field.type === 'ATTACHMENT'" class="attachment-field">
        <wd-button size="small" plain :disabled="readonly" @click="chooseFiles(field)">
          选择文件
        </wd-button>
        <text v-for="name in files(field)" :key="name" class="file-name">{{ name }}</text>
        <text v-if="files(field).length === 0" class="hint">尚未选择文件</text>
      </view>
      <text v-else class="hint">暂不支持 {{ field.type }}</text>
    </view>
  </view>
</template>

<style scoped>
.renderer,.attachment-field{display:grid;gap:18rpx}.field-card{display:grid;gap:14rpx;padding:26rpx;border-radius:24rpx;background:var(--wot-color-white,var(--uni-bg-color));box-shadow:0 8rpx 24rpx rgb(15 23 42 / 5%)}.field-label{display:flex;align-items:center;justify-content:space-between;color:var(--wot-color-content,var(--uni-text-color));font-weight:700}.required{color:var(--wot-color-danger,var(--uni-color-error));font-size:22rpx}.hint,.file-name{color:var(--wot-color-content-secondary,var(--uni-text-color-grey));font-size:24rpx}.file-name{padding:10rpx 14rpx;border-radius:14rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}
</style>
