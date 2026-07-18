<script lang="ts" setup>
import type { ApprovalAttachment } from '@/api/approval/comments'
import type {
  FieldAccess,
  FormDefinition,
  FormField,
  UiFieldLayout,
  UiSchemaDefinition,
  UiSection,
} from '@/api/approval/form-types'
import { uploadApprovalAttachment } from '@/api/approval/comments'

const props = withDefaults(defineProps<{
  fieldPermissions?: Record<string, FieldAccess>
  modelValue?: Record<string, unknown>
  readonly?: boolean
  schema: FormDefinition
  uiSchema?: UiSchemaDefinition
}>(), {
  fieldPermissions: () => ({}),
  modelValue: () => ({}),
  readonly: false,
  uiSchema: undefined,
})
const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>]
}>()

const uploading = ref(false)
const uploadedByField = ref<Record<string, ApprovalAttachment[]>>({})
const collapsed = ref<Record<string, boolean>>({})
const fieldByKey = computed(() => new Map(props.schema.fields.map(item => [item.key, item])))
const sections = computed<UiSection[]>(() => props.uiSchema?.sections.length
  ? props.uiSchema.sections
  : [{
      collapsed: false,
      fields: props.schema.fields.map(item => ({ fieldKey: item.key, span: 24 })),
      key: 'default',
      title: '申请信息',
    }])

watch(sections, value => {
  collapsed.value = Object.fromEntries(value.map(section => [section.key, section.collapsed]))
}, { immediate: true })

function field(layout: UiFieldLayout) {
  return fieldByKey.value.get(layout.fieldKey)
}
function access(item: FormField): FieldAccess {
  return props.readonly ? 'READONLY' : (props.fieldPermissions[item.key] || 'EDITABLE')
}
function visible(item?: FormField) {
  return Boolean(item && access(item) !== 'HIDDEN')
}
function disabled(item: FormField) {
  return access(item) !== 'EDITABLE'
}
function value(item: FormField) {
  return props.modelValue?.[item.key]
}
function textValue(item: FormField) {
  const current = value(item)
  return current == null ? '' : String(current)
}
function setValue(item: FormField, nextValue: unknown) {
  if (!disabled(item)) emit('update:modelValue', { ...props.modelValue, [item.key]: nextValue })
}
function attachmentIds(item: FormField) {
  const current = value(item)
  return Array.isArray(current) ? current.map(String) : []
}
function attachmentItems(item: FormField) {
  const known = uploadedByField.value[item.key] || []
  return known.length
    ? known.map(file => ({ id: file.attachmentId, name: file.fileName }))
    : attachmentIds(item).map(id => ({ id, name: id }))
}
function removeAttachment(item: FormField, attachmentId: string) {
  if (disabled(item)) return
  uploadedByField.value = {
    ...uploadedByField.value,
    [item.key]: (uploadedByField.value[item.key] || [])
      .filter(file => file.attachmentId !== attachmentId),
  }
  setValue(item, attachmentIds(item).filter(id => id !== attachmentId))
}
function chooseFiles(item: FormField) {
  if (disabled(item) || uploading.value) return
  const remaining = (item.constraints.multiple ? 20 : 1) - attachmentIds(item).length
  if (remaining <= 0) {
    uni.showToast({ title: '已达到附件数量上限', icon: 'none' })
    return
  }
  uni.chooseMessageFile({
    count: remaining,
    type: 'file',
    success: async result => {
      uploading.value = true
      try {
        const uploaded = [...(uploadedByField.value[item.key] || [])]
        for (const file of result.tempFiles) {
          if (file.size > 10 * 1024 * 1024) throw new Error(`${file.name} 超过 10 MiB`)
          uploaded.push(await uploadApprovalAttachment(file.path))
        }
        uploadedByField.value = { ...uploadedByField.value, [item.key]: uploaded }
        setValue(item, uploaded.map(file => file.attachmentId))
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
function toggleSection(key: string) {
  collapsed.value = { ...collapsed.value, [key]: !collapsed.value[key] }
}
function validate() {
  if (uploading.value) return '附件正在上传，请稍后'
  for (const item of props.schema.fields) {
    if (access(item) === 'HIDDEN') continue
    const current = value(item)
    const empty = current == null || current === ''
      || (Array.isArray(current) && current.length === 0)
    if (item.required && empty) return `${item.label}不能为空`
    if (item.type === 'MONEY' && !empty) {
      const amount = Number(current)
      if (!Number.isFinite(amount)) return `${item.label}格式不正确`
      if (item.constraints.minimum != null && amount < item.constraints.minimum) {
        return `${item.label}不能小于${item.constraints.minimum}`
      }
    }
    if (item.type === 'ATTACHMENT') {
      const count = Array.isArray(current) ? current.length : 0
      if (count < (item.constraints.minItems || 0)) {
        return `${item.label}至少选择${item.constraints.minItems}个文件`
      }
    }
  }
  return ''
}
function editableValues() {
  return Object.fromEntries(props.schema.fields
    .filter(item => access(item) === 'EDITABLE' && item.key in (props.modelValue || {}))
    .map(item => [item.key, props.modelValue?.[item.key]]))
}
defineExpose({ editableValues, uploading, validate })
</script>

<template>
  <view class="renderer">
    <view v-for="section in sections" :key="section.key" class="section-card">
      <view class="section-header" @click="toggleSection(section.key)">
        <view>
          <text class="section-title">{{ section.title }}</text>
          <text v-if="section.helpText" class="section-help">{{ section.helpText }}</text>
        </view>
        <text class="section-toggle">{{ collapsed[section.key] ? '展开' : '收起' }}</text>
      </view>
      <view v-if="!collapsed[section.key]" class="section-grid">
        <template v-for="layout in section.fields" :key="layout.fieldKey">
          <view
            v-if="visible(field(layout)) && field(layout)"
            class="field-card"
            :class="{ 'field-card--wide': layout.span >= 24 }"
          >
            <view class="field-label">
              <text>{{ field(layout)?.label }}</text>
              <text v-if="field(layout)?.required" class="required">必填</text>
            </view>
            <wd-input
              v-if="field(layout)?.type === 'TEXT'"
              :disabled="disabled(field(layout)!)"
              :maxlength="field(layout)?.constraints.maxLength"
              :model-value="textValue(field(layout)!)"
              clearable no-border
              :placeholder="layout.placeholder || '请输入'"
              @update:model-value="setValue(field(layout)!, $event)"
            />
            <wd-input
              v-else-if="field(layout)?.type === 'MONEY'"
              :disabled="disabled(field(layout)!)"
              :model-value="textValue(field(layout)!)"
              clearable no-border type="digit"
              :placeholder="layout.placeholder || '请输入金额'"
              @update:model-value="setValue(field(layout)!, $event)"
            />
            <view v-else-if="field(layout)?.type === 'ATTACHMENT'" class="attachment-field">
              <wd-button
                v-if="!disabled(field(layout)!)"
                size="small" plain
                :disabled="uploading" :loading="uploading"
                @click="chooseFiles(field(layout)!)"
              >选择并上传文件</wd-button>
              <view
                v-for="file in attachmentItems(field(layout)!)"
                :key="file.id"
                class="file-row"
              >
                <text class="file-name">{{ file.name }}</text>
                <text
                  v-if="!disabled(field(layout)!)"
                  class="remove"
                  @click="removeAttachment(field(layout)!, file.id)"
                >移除</text>
              </view>
              <text v-if="attachmentItems(field(layout)!).length === 0" class="hint">暂无附件</text>
            </view>
            <text v-if="layout.helpText" class="hint">{{ layout.helpText }}</text>
          </view>
        </template>
      </view>
    </view>
  </view>
</template>

<style scoped>
.renderer,.attachment-field,.section-card,.section-header>view{display:grid;gap:18rpx}.section-card{padding:26rpx;border-radius:24rpx;background:var(--wot-color-white,var(--uni-bg-color));box-shadow:0 8rpx 24rpx rgb(15 23 42 / 5%)}.section-header,.field-label,.file-row{display:flex;align-items:center;justify-content:space-between;gap:16rpx}.section-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18rpx}.field-card{display:grid;align-content:start;gap:14rpx;min-width:0;padding:20rpx;border-radius:18rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}.field-card--wide{grid-column:1/-1}.section-title,.field-label{color:var(--wot-color-content,var(--uni-text-color));font-weight:700}.section-title{font-size:29rpx}.section-help,.section-toggle,.hint,.file-name{color:var(--wot-color-content-secondary,var(--uni-text-color-grey));font-size:24rpx}.required,.remove{color:var(--wot-color-danger,var(--uni-color-error));font-size:22rpx}.file-row{padding:10rpx 14rpx;border-radius:14rpx;background:var(--wot-color-white,var(--uni-bg-color))}.file-name{flex:1;overflow-wrap:anywhere}@media(max-width:420px){.section-grid{grid-template-columns:1fr}.field-card{grid-column:1/-1}}
</style>
