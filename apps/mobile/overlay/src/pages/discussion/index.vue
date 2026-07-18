<script lang="ts" setup>
import type {
  PendingTaskPage,
  ProcessedTaskPage,
  StartedInstancePage,
} from '@/api/approval'
import type { CopiedInstancePage } from '@/api/approval/comments'

import {
  findPendingTasks,
  findProcessedTasks,
  findStartedInstances,
} from '@/api/approval'
import { findCopiedInstances } from '@/api/approval/comments'

defineOptions({ name: 'ApprovalDiscussionList' })

definePage({
  style: {
    navigationBarTitleText: '审批讨论',
  },
})

type DiscussionTab = 'copied' | 'pending' | 'processed' | 'started'

interface DiscussionItem {
  amount: number
  businessKey: string
  copyMessageId?: string
  instanceId: string
  key: string
  meta: string
  purchaseOrderReference: string
  status: string
  supplier: string
}

const tabs: Array<{ label: string, value: DiscussionTab }> = [
  { label: '待我处理', value: 'pending' },
  { label: '我已处理', value: 'processed' },
  { label: '我发起的', value: 'started' },
  { label: '抄送我的', value: 'copied' },
]
const pageSize = 20
const activeTab = ref<DiscussionTab>('pending')
const keyword = ref('')
const currentPage = ref(1)
const loading = ref(false)
const loadError = ref('')
const pendingPage = ref<PendingTaskPage>(emptyPage())
const processedPage = ref<ProcessedTaskPage>(emptyPage())
const startedPage = ref<StartedInstancePage>(emptyPage())
const copiedPage = ref<CopiedInstancePage>(emptyPage())

const items = computed<DiscussionItem[]>(() => {
  if (activeTab.value === 'processed') {
    return processedPage.value.items.map(item => ({
      amount: item.amount,
      businessKey: item.businessKey,
      instanceId: item.instanceId,
      key: item.taskId,
      meta: `已处理 ${formatDate(item.completedAt)} · ${item.taskName}`,
      purchaseOrderReference: item.purchaseOrderReference,
      status: '我已处理',
      supplier: item.supplier,
    }))
  }
  if (activeTab.value === 'started') {
    return startedPage.value.items.map(item => ({
      amount: item.amount,
      businessKey: item.businessKey,
      instanceId: item.instanceId,
      key: item.instanceId,
      meta: `当前环节 ${item.currentTaskName || '流程已结束'} · ${item.messageCount} 条消息`,
      purchaseOrderReference: item.purchaseOrderReference,
      status: statusLabel(item.status),
      supplier: item.supplier,
    }))
  }
  if (activeTab.value === 'copied') {
    return copiedPage.value.items.map(item => ({
      amount: item.amount,
      businessKey: item.businessKey,
      copyMessageId: item.read ? undefined : item.copyMessageId,
      instanceId: item.instanceId,
      key: item.copyMessageId,
      meta: `抄送人 ${item.copiedBy} · ${item.commentCount} 条评论 · ${formatDate(item.copiedAt)}`,
      purchaseOrderReference: item.purchaseOrderReference,
      status: item.read ? '已读抄送' : '未读抄送',
      supplier: item.supplier,
    }))
  }
  return pendingPage.value.items.map(item => ({
    amount: item.amount,
    businessKey: item.businessKey,
    instanceId: item.instanceId,
    key: item.taskId,
    meta: `当前环节 ${item.taskName} · ${formatDate(item.taskCreatedAt)}`,
    purchaseOrderReference: item.purchaseOrderReference,
    status: '待我处理',
    supplier: item.supplier,
  }))
})

const total = computed(() => {
  if (activeTab.value === 'processed') return processedPage.value.total
  if (activeTab.value === 'started') return startedPage.value.total
  if (activeTab.value === 'copied') return copiedPage.value.total
  return pendingPage.value.total
})
const hasMore = computed(() => {
  if (activeTab.value === 'processed') return processedPage.value.hasMore
  if (activeTab.value === 'started') return startedPage.value.hasMore
  if (activeTab.value === 'copied') return copiedPage.value.hasMore
  return pendingPage.value.hasMore
})

function emptyPage() {
  return { hasMore: false, items: [], limit: pageSize, offset: 0, total: 0 }
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '审批讨论加载失败'
}

function formatMoney(value: number) {
  return `¥${Number(value).toFixed(2)}`
}

function formatDate(value: string) {
  const date = new Date(value)
  return `${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    COMPLETED: '已完成',
    REJECTED: '已驳回',
    RUNNING: '审批中',
    WITHDRAWN: '已撤回',
  }
  return labels[status] || status
}

async function loadItems() {
  loading.value = true
  loadError.value = ''
  const offset = (currentPage.value - 1) * pageSize
  try {
    if (activeTab.value === 'processed') {
      processedPage.value = await findProcessedTasks({
        keyword: keyword.value,
        limit: pageSize,
        offset,
      })
    }
    else if (activeTab.value === 'started') {
      startedPage.value = await findStartedInstances({
        keyword: keyword.value,
        limit: pageSize,
        offset,
      })
    }
    else if (activeTab.value === 'copied') {
      copiedPage.value = await findCopiedInstances(keyword.value, pageSize, offset)
    }
    else {
      pendingPage.value = await findPendingTasks({
        keyword: keyword.value,
        limit: pageSize,
        offset,
      })
    }
  }
  catch (error) {
    loadError.value = errorMessage(error)
  }
  finally {
    loading.value = false
  }
}

async function selectTab(tab: DiscussionTab) {
  activeTab.value = tab
  currentPage.value = 1
  keyword.value = ''
  await loadItems()
}

async function searchItems() {
  currentPage.value = 1
  await loadItems()
}

async function previousPage() {
  if (currentPage.value <= 1) return
  currentPage.value -= 1
  await loadItems()
  uni.pageScrollTo({ scrollTop: 0 })
}

async function nextPage() {
  if (!hasMore.value) return
  currentPage.value += 1
  await loadItems()
  uni.pageScrollTo({ scrollTop: 0 })
}

function openDiscussion(item: DiscussionItem) {
  const query = [
    `instanceId=${encodeURIComponent(item.instanceId)}`,
    `supplier=${encodeURIComponent(item.supplier)}`,
    `businessKey=${encodeURIComponent(item.businessKey)}`,
    `purchaseOrderReference=${encodeURIComponent(item.purchaseOrderReference)}`,
    `amount=${encodeURIComponent(String(item.amount))}`,
    `status=${encodeURIComponent(item.status)}`,
  ]
  if (item.copyMessageId) {
    query.push(`copyMessageId=${encodeURIComponent(item.copyMessageId)}`)
  }
  uni.navigateTo({ url: `/pages/discussion/detail?${query.join('&')}` })
}

onShow(loadItems)
</script>

<template>
  <view class="page">
    <scroll-view class="tab-scroll" scroll-x>
      <view class="tab-row">
        <view
          v-for="tab in tabs"
          :key="tab.value"
          class="tab-item"
          :class="{ 'tab-item--active': activeTab === tab.value }"
          @click="selectTab(tab.value)"
        >
          {{ tab.label }}
        </view>
      </view>
    </scroll-view>

    <view class="search-card">
      <wd-search
        v-model="keyword"
        placeholder="搜索业务编号、供应商或采购订单"
        @clear="searchItems"
        @search="searchItems"
      />
    </view>

    <view class="summary-row">
      <text>共 {{ total }} 项</text>
      <wd-button size="small" plain :loading="loading" @click="loadItems">刷新</wd-button>
    </view>

    <view v-if="loadError" class="state-card state-card--error">
      <text>{{ loadError }}</text>
      <wd-button size="small" plain @click="loadItems">重新加载</wd-button>
    </view>
    <view v-else-if="loading" class="state-card">正在加载审批讨论...</view>
    <view v-else-if="items.length === 0" class="state-card">暂无可讨论的审批</view>
    <view v-else class="discussion-list">
      <view
        v-for="item in items"
        :key="item.key"
        class="discussion-card"
        @click="openDiscussion(item)"
      >
        <view class="card-header">
          <text class="card-title">{{ item.supplier }}采购付款</text>
          <wd-tag plain type="primary">{{ item.status }}</wd-tag>
        </view>
        <text class="card-meta">{{ item.businessKey }} · {{ item.purchaseOrderReference }}</text>
        <text class="card-meta">{{ item.meta }}</text>
        <view class="card-footer">
          <strong>{{ formatMoney(item.amount) }}</strong>
          <text>查看讨论 ›</text>
        </view>
      </view>
    </view>

    <view v-if="total > pageSize" class="pagination-bar">
      <wd-button size="small" plain :disabled="currentPage <= 1" @click="previousPage">
        上一页
      </wd-button>
      <text>第 {{ currentPage }} 页</text>
      <wd-button size="small" plain :disabled="!hasMore" @click="nextPage">
        下一页
      </wd-button>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 70rpx;
  background: var(--wot-color-bg, var(--uni-bg-color-grey));
}

.tab-scroll {
  white-space: nowrap;
}

.tab-row,
.summary-row,
.card-header,
.card-footer,
.pagination-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.tab-row {
  justify-content: flex-start;
  padding-bottom: 18rpx;
}

.tab-item {
  padding: 16rpx 24rpx;
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  border-radius: 999rpx;
  background: var(--wot-color-white, var(--uni-bg-color));
  font-size: 25rpx;
}

.tab-item--active {
  color: var(--wot-color-white, var(--uni-text-color-inverse));
  background: var(--wot-color-theme, var(--uni-color-primary));
}

.search-card,
.discussion-card,
.state-card {
  border-radius: var(--wot-radius-large, 24rpx);
  background: var(--wot-color-white, var(--uni-bg-color));
  box-shadow: 0 8rpx 24rpx rgb(15 23 42 / 5%);
}

.search-card {
  overflow: hidden;
}

.summary-row {
  padding: 22rpx 4rpx;
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-size: 24rpx;
}

.discussion-list {
  display: grid;
  gap: 20rpx;
}

.discussion-card {
  display: grid;
  gap: 14rpx;
  padding: 28rpx;
}

.card-title {
  flex: 1;
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 30rpx;
  font-weight: 700;
}

.card-meta,
.card-footer,
.pagination-bar,
.state-card {
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-size: 24rpx;
}

.card-footer strong {
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 29rpx;
}

.state-card {
  display: grid;
  justify-items: center;
  gap: 20rpx;
  padding: 48rpx 28rpx;
  text-align: center;
}

.state-card--error {
  color: var(--wot-color-danger, var(--uni-color-error));
}

.pagination-bar {
  margin-top: 28rpx;
  padding: 20rpx 4rpx;
}
</style>
