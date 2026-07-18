<script lang="ts" setup>
import type { PublishedForm } from '@/api/approval/form-types'

import { findForm, submitForm } from '@/api/approval/forms'
import ApprovalFormRenderer from '@/components/approval/ApprovalFormRenderer.vue'
import { getApprovalRuntimeConfig } from '@/platform/approval/runtime'

defineOptions({ name: 'ApprovalDynamicForm' })

definePage({
  style: { navigationBarTitleText: '填写审批表单' },
})

const runtime = getApprovalRuntimeConfig()
const formKey = ref('')
const version = ref(0)
const loading = ref(false)
const submitting = ref(false)
const errorText = ref('')
const published = ref<PublishedForm>()
const formValues = ref<Record<string, unknown>>({})
const businessKey = ref(`FORM-${Date.now().toString(36).toUpperCase()}`)
const connectorKey = ref(runtime.connector)
const financeReviewerRoleCode = ref('FINANCE_REVIEWER')
const financeApproverPositionCode = ref('FINANCE_APPROVER')
const maximumFinanceApprovers = ref('20')
const showRouting = ref(false)
const renderer = ref<{ validate: () => string }>()

async function loadForm() {
  if (!formKey.value || version.value < 1) {
    errorText.value = '缺少表单版本信息'
    return
  }
  loading.value = true
  errorText.value = ''
  try {
    published.value = await findForm(formKey.value, version.value)
    formValues.value = {}
  }
  catch (error) {
    errorText.value = error instanceof Error ? error.message : '表单加载失败'
  }
  finally {
    loading.value = false
  }
}

function validationMessage() {
  if (!businessKey.value.trim()) return '请填写业务编号'
  const formMessage = renderer.value?.validate() || ''
  if (formMessage) return formMessage
  if (!connectorKey.value.trim()) return '请填写审批人连接器'
  if (!financeReviewerRoleCode.value.trim()) return '请填写财务审核角色编码'
  if (!financeApproverPositionCode.value.trim()) return '请填写财务会签岗位编码'
  const maximum = Number(maximumFinanceApprovers.value)
  if (!Number.isInteger(maximum) || maximum < 1 || maximum > 100) {
    return '财务会签人数上限必须为 1 到 100'
  }
  return ''
}

async function submitDynamicForm() {
  const schema = published.value?.definition
  if (!schema || submitting.value) return
  const message = validationMessage()
  if (message) {
    uni.showToast({ title: message, icon: 'none' })
    return
  }
  const confirmed = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: '确认发起审批',
      content: `业务编号：${businessKey.value.trim()}\n表单版本：v${schema.version}`,
      confirmText: '发起审批',
      success: result => resolve(result.confirm),
      fail: () => resolve(false),
    })
  })
  if (!confirmed) return

  submitting.value = true
  try {
    const result = await submitForm(
      schema.formKey,
      schema.version,
      businessKey.value.trim(),
      formValues.value,
      {
        connectorKey: connectorKey.value.trim(),
        initiatorUserId: {
          source: connectorKey.value.trim(),
          objectType: 'USER',
          value: runtime.operatorId,
        },
        financeReviewerRoleCode: financeReviewerRoleCode.value.trim(),
        financeApproverPositionCode: financeApproverPositionCode.value.trim(),
        maximumFinanceApprovers: Number(maximumFinanceApprovers.value),
      },
    )
    await new Promise<void>((resolve) => {
      uni.showModal({
        title: '审批已发起',
        content: `实例编号：${result.instanceId}`,
        showCancel: false,
        success: () => resolve(),
        fail: () => resolve(),
      })
    })
    uni.navigateTo({ url: '/pages/task/list?tab=started' })
  }
  catch (error) {
    uni.showToast({
      title: error instanceof Error ? error.message : '审批发起失败',
      icon: 'none',
    })
  }
  finally {
    submitting.value = false
  }
}

function goBack() {
  uni.navigateBack()
}

onLoad((query) => {
  formKey.value = decodeURIComponent(String(query?.formKey || ''))
  version.value = Number(query?.version || 0)
  loadForm()
})
</script>

<template>
  <view class="page">
    <view v-if="loading" class="state-card">正在加载表单...</view>
    <view v-else-if="errorText" class="state-card state-card--error">
      <text>{{ errorText }}</text>
      <wd-button size="small" plain @click="loadForm">重新加载</wd-button>
    </view>
    <template v-else-if="published">
      <view class="form-header">
        <view>
          <text class="form-name">{{ published.definition.name }}</text>
          <text class="form-meta">
            {{ published.definition.formKey }} · v{{ published.definition.version }}
          </text>
        </view>
        <wd-tag plain type="success">已发布</wd-tag>
      </view>

      <view class="business-card">
        <text class="section-title">发起信息</text>
        <wd-input v-model="businessKey" label="业务编号" placeholder="必须唯一" clearable />
      </view>

      <ApprovalFormRenderer
        ref="renderer"
        v-model="formValues"
        :schema="published.definition"
      />

      <view class="routing-card">
        <view class="routing-title" @click="showRouting = !showRouting">
          <view>
            <text class="section-title">审批人解析参数</text>
            <text class="form-meta">后续由流程配置器管理，当前用于纵向链路</text>
          </view>
          <text>{{ showRouting ? '收起' : '展开' }}</text>
        </view>
        <view v-if="showRouting" class="routing-fields">
          <wd-input v-model="connectorKey" label="连接器" placeholder="如 generic-rest" />
          <wd-input v-model="financeReviewerRoleCode" label="财务审核角色" />
          <wd-input v-model="financeApproverPositionCode" label="财务会签岗位" />
          <wd-input v-model="maximumFinanceApprovers" label="会签人数上限" type="number" />
          <text class="form-meta">发起人：{{ runtime.operatorId }}</text>
        </view>
      </view>
    </template>

    <view class="action-bar">
      <wd-button plain @click="goBack">返回</wd-button>
      <wd-button
        type="primary"
        :disabled="!published || loading"
        :loading="submitting"
        @click="submitDynamicForm"
      >
        发起审批
      </wd-button>
    </view>
  </view>
</template>

<style scoped>
.page{min-height:100vh;padding:24rpx 24rpx 180rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}.form-header,.action-bar,.routing-title{display:flex;align-items:center;justify-content:space-between;gap:18rpx}.form-header,.business-card,.routing-card,.state-card{margin-bottom:20rpx;padding:26rpx;border-radius:24rpx;background:var(--wot-color-white,var(--uni-bg-color));box-shadow:0 8rpx 24rpx rgb(15 23 42 / 5%)}.form-header>view,.routing-title>view,.routing-fields{display:grid;gap:8rpx}.form-name,.section-title{color:var(--wot-color-content,var(--uni-text-color));font-size:31rpx;font-weight:700}.section-title{font-size:28rpx}.form-meta,.state-card,.routing-title>text{color:var(--wot-color-content-secondary,var(--uni-text-color-grey));font-size:24rpx}.business-card,.routing-fields{display:grid;gap:16rpx}.routing-card{margin-top:20rpx}.routing-fields{margin-top:18rpx}.state-card{display:grid;justify-items:center;gap:18rpx;padding:60rpx 24rpx;text-align:center}.state-card--error{color:var(--wot-color-danger,var(--uni-color-error))}.action-bar{position:fixed;right:0;bottom:0;left:0;justify-content:flex-end;padding:20rpx 24rpx calc(20rpx + env(safe-area-inset-bottom));background:var(--wot-color-white,var(--uni-bg-color));box-shadow:0 -8rpx 24rpx rgb(15 23 42 / 8%)}
</style>
