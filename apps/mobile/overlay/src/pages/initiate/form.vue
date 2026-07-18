<script lang="ts" setup>
import type { PublishedForm } from '@/api/approval/form-types'

import { findForm } from '@/api/approval/forms'
import ApprovalFormRenderer from '@/components/approval/ApprovalFormRenderer.vue'

defineOptions({ name: 'ApprovalDynamicForm' })

definePage({
  style: { navigationBarTitleText: '填写审批表单' },
})

const formKey = ref('')
const version = ref(0)
const loading = ref(false)
const errorText = ref('')
const published = ref<PublishedForm>()
const formValues = ref<Record<string, unknown>>({})
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

function checkForm() {
  const message = renderer.value?.validate() || ''
  if (message) {
    uni.showToast({ title: message, icon: 'none' })
    return
  }
  uni.showToast({ title: '填写内容校验通过', icon: 'success' })
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
      <ApprovalFormRenderer
        ref="renderer"
        v-model="formValues"
        :schema="published.definition"
      />
    </template>

    <view class="action-bar">
      <wd-button plain @click="goBack">返回</wd-button>
      <wd-button
        type="primary"
        :disabled="!published || loading"
        @click="checkForm"
      >
        检查填写内容
      </wd-button>
    </view>
  </view>
</template>

<style scoped>
.page{min-height:100vh;padding:24rpx 24rpx 180rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}.form-header,.action-bar{display:flex;align-items:center;justify-content:space-between;gap:18rpx}.form-header,.state-card{margin-bottom:20rpx;padding:26rpx;border-radius:24rpx;background:var(--wot-color-white,var(--uni-bg-color));box-shadow:0 8rpx 24rpx rgb(15 23 42 / 5%)}.form-header>view{display:grid;gap:8rpx}.form-name{color:var(--wot-color-content,var(--uni-text-color));font-size:31rpx;font-weight:700}.form-meta,.state-card{color:var(--wot-color-content-secondary,var(--uni-text-color-grey));font-size:24rpx}.state-card{display:grid;justify-items:center;gap:18rpx;padding:60rpx 24rpx;text-align:center}.state-card--error{color:var(--wot-color-danger,var(--uni-color-error))}.action-bar{position:fixed;right:0;bottom:0;left:0;justify-content:flex-end;padding:20rpx 24rpx calc(20rpx + env(safe-area-inset-bottom));background:var(--wot-color-white,var(--uni-bg-color));box-shadow:0 -8rpx 24rpx rgb(15 23 42 / 8%)}
</style>
