<script lang="ts" setup>
import type { FormDefinition, FormField } from '@/api/approval/form-types'
import type { ApprovalAttachment } from '@/api/approval/comments'

import { uploadApprovalAttachment } from '@/api/approval/comments'

const props = withDefaults(defineProps<{
  modelValue?: Record<string, unknown>
  readonly?: boolean
  schema: FormDefinition
}>(), { modelValue: () => ({}), readonly: false })

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>]
}>()

const uploading = ref(false)
const uploadedByField = ref<Record<string, ApprovalAttachment[]>>({})

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

function attachmentIds(field: FormField) {
  const current = value(field)
  return Array.isArray(current) ? current.map(String) : []
}

function attachmentItems(field: FormField) {
  const known = uploadedByField.value[field.key] || []
  if (known.length) return known.map(item => ({ id: item.attachmentId, name: item.fileName }))
  return attachmentIds(field).map(id => ({ id, name: id }))
}

function removeAttachment(field: FormField, attachmentId: string) {
  uploadedByField.value = {
    ...uploadedByField.value,
    [field.key]: (uploadedByField.value[field.key] || [])
      .filter(item => item.attachmentId !== attachmentId),
  }
  setValue(field, attachmentIds(field).filter(id => id !== attachmentId))
}

function chooseFiles(field: FormField) {
  if (props.readonly || uploading.value) return
  const currentCount = attachmentIds(field).length
  const remaining = field.constraints.multiple ? 20 - currentCount : 1 - currentCount
  if (remaining <= 0) {
    uni.showToast({ title: '已达到附件数量上限', icon: 'none' })
    return
  }
  uni.chooseMessageFile({
    count: remaining,
    type: 'file',
    success: async (result) => {
      uploading.value = true
      try {
        const uploaded = [...(uploadedByField.value[field.key] || [])]
        for (const file of result.tempFiles) {
          if (file.size > 10 * 1024 * 1024) throw new Error(`${file.name} 超过 10 MiB`)
          uploaded.push(await uploadApprovalAttachment(file.path))
        }
        uploadedByField.value = { ...uploadedByField.value, [field.key]: uploaded }
        setValue(field, uploaded.map(item => item.attachmentId))
      }
      catch (error) {
        uni.showToast({
          title: error instanceof Error ? error.message : '附件上传失败',
          icon: 'none',
        })
      }
      finally {
        uploading.value = false
      }
    },
  })
}

function validate() {
  if (uploading.value) return '附件正在上传，请稍后'
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

defineExpose({ uploading, validate })
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
        <wd-button
          size="small"
          plain
          :disabled="readonly || uploading"
          :loading="uploading"
          @click="chooseFiles(field)"
        >
          选择并上传文件
        </wd-button>
        <view
          v-for="file in attachmentItems(field)"
          :key="file.id"
          class="file-row"
        >
          <text class="file-name">{{ file.name }}</text>
          <text v-if="!readonly" class="remove" @click="removeAttachment(field, file.id)">移除</text>
        </view>
        <text v-if="attachmentItems(field).length === 0" class="hint">尚未上传文件</text>
      </view>
      <text v-else class="hint">暂不支持 {{ field.type }}</text>
    </view>
  </view>
</template>

<style scoped>
.renderer,.attachment-field{display:grid;gap:18rpx}.field-card{display:grid;gap:14rpx;padding:26rpx;border-radius:24rpx;background:var(--wot-color-white,var(--uni-bg-color));box-shadow:0 8rpx 24rpx rgb(15 23 42 / 5%)}.field-label,.file-row{display:flex;align-items:center;justify-content:space-between;gap:16rpx}.field-label{color:var(--wot-color-content,var(--uni-text-color));font-weight:700}.required,.remove{color:var(--wot-color-danger,var(--uni-color-error));font-size:22rpx}.hint,.file-name{color:var(--wot-color-content-secondary,var(--uni-text-color-grey));font-size:24rpx}.file-row{padding:10rpx 14rpx;border-radius:14rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}.file-name{flex:1;overflow-wrap:anywhere}
</style>
