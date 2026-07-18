<script lang="ts" setup>
import type { FormPage, FormSummary } from '@/api/approval/form-types'

import { findForms } from '@/api/approval/forms'

defineOptions({ name: 'ApprovalInitiate' })

definePage({
  style: { navigationBarTitleText: '发起审批' },
})

const keyword = ref('')
const loading = ref(false)
const errorText = ref('')
const page = ref<FormPage>({ hasMore: false, items: [], limit: 50, offset: 0, total: 0 })

async function loadForms() {
  loading.value = true
  errorText.value = ''
  try {
    page.value = await findForms(keyword.value, 50, 0)
  }
  catch (error) {
    errorText.value = error instanceof Error ? error.message : '表单模板加载失败'
    page.value = { hasMore: false, items: [], limit: 50, offset: 0, total: 0 }
  }
  finally {
    loading.value = false
  }
}

function openForm(item: FormSummary) {
  uni.navigateTo({
    url: `/pages/initiate/form?formKey=${encodeURIComponent(item.formKey)}&version=${item.version}`,
  })
}

onShow(loadForms)
</script>

<template>
  <view class="page">
    <view class="search-card">
      <wd-search
        v-model="keyword"
        placeholder="搜索已发布表单"
        @clear="loadForms"
        @search="loadForms"
      />
    </view>

    <view class="section-title">
      <text>可发起表单</text>
      <wd-tag plain type="primary">{{ page.total }} 个版本</wd-tag>
    </view>

    <view v-if="loading" class="state-card">正在加载表单...</view>
    <view v-else-if="errorText" class="state-card state-card--error">
      <text>{{ errorText }}</text>
      <wd-button size="small" plain @click="loadForms">重新加载</wd-button>
    </view>
    <view v-else-if="page.items.length === 0" class="state-card">
      暂无已发布表单
    </view>
    <view v-else class="template-list">
      <view
        v-for="item in page.items"
        :key="`${item.formKey}:${item.version}`"
        class="template-card"
        @click="openForm(item)"
      >
        <view class="template-main">
          <text class="template-name">{{ item.name }}</text>
          <text class="template-meta">{{ item.formKey }} · {{ item.fieldCount }} 个字段</text>
          <text class="template-meta">发布人 {{ item.publishedBy }}</text>
        </view>
        <view class="template-side">
          <wd-tag plain type="primary">v{{ item.version }}</wd-tag>
          <text>›</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page{min-height:100vh;padding:24rpx 24rpx 160rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}.search-card,.template-card,.state-card{border-radius:24rpx;background:var(--wot-color-white,var(--uni-bg-color));box-shadow:0 8rpx 24rpx rgb(15 23 42 / 5%)}.search-card{overflow:hidden}.section-title,.template-card,.template-side{display:flex;align-items:center;justify-content:space-between;gap:18rpx}.section-title{margin:32rpx 4rpx 18rpx;color:var(--wot-color-content,var(--uni-text-color));font-size:30rpx;font-weight:700}.template-list{display:grid;gap:18rpx}.template-card{padding:26rpx}.template-main{display:grid;flex:1;gap:9rpx}.template-name{color:var(--wot-color-content,var(--uni-text-color));font-size:29rpx;font-weight:700}.template-meta,.state-card,.template-side>text{color:var(--wot-color-content-secondary,var(--uni-text-color-grey));font-size:24rpx}.template-side{flex-direction:column}.state-card{display:grid;justify-items:center;gap:18rpx;padding:60rpx 24rpx;text-align:center}.state-card--error{color:var(--wot-color-danger,var(--uni-color-error))}
</style>
