<script lang="ts" setup>
import type { PendingTaskItem, PendingTaskPage } from '@/api/approval'

import { findPendingTasks } from '@/api/approval'

defineOptions({
  name: 'ApprovalTaskList',
})

definePage({
  style: {
    navigationBarTitleText: '待我处理',
  },
})

const pageSize = 20
const currentPage = ref(1)
const keyword = ref('')
const loading = ref(false)
const loadError = ref('')
const taskPage = ref<PendingTaskPage>({
  hasMore: false,
  items: [],
  limit: pageSize,
  offset: 0,
  total: 0,
})

function taskStage(task: Pick<PendingTaskItem, 'taskDefinitionKey' | 'taskName'>) {
  const labels: Record<string, string> = {
    financeCountersign: '财务会签',
    financeReview: '财务审核',
    initiatorRevision: '发起人修改',
    managerApproval: '部门负责人审批',
  }
  return labels[task.taskDefinitionKey] || task.taskName
}

function taskTagType(task: Pick<PendingTaskItem, 'taskDefinitionKey'>) {
  return task.taskDefinitionKey === 'initiatorRevision' ? 'warning' : 'primary'
}

function formatMoney(value: number) {
  return `¥${Number(value).toFixed(2)}`
}

function formatDate(value: string) {
  const date = new Date(value)
  return `${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '待办加载失败'
}

async function loadTasks() {
  loading.value = true
  loadError.value = ''
  try {
    taskPage.value = await findPendingTasks({
      keyword: keyword.value,
      limit: pageSize,
      offset: (currentPage.value - 1) * pageSize,
    })
  }
  catch (error) {
    loadError.value = errorMessage(error)
    taskPage.value = {
      hasMore: false,
      items: [],
      limit: pageSize,
      offset: (currentPage.value - 1) * pageSize,
      total: 0,
    }
  }
  finally {
    loading.value = false
  }
}

async function searchTasks() {
  currentPage.value = 1
  await loadTasks()
}

async function resetSearch() {
  keyword.value = ''
  currentPage.value = 1
  await loadTasks()
}

async function previousPage() {
  if (currentPage.value <= 1) {
    return
  }
  currentPage.value -= 1
  await loadTasks()
  uni.pageScrollTo({ scrollTop: 0 })
}

async function nextPage() {
  if (!taskPage.value.hasMore) {
    return
  }
  currentPage.value += 1
  await loadTasks()
  uni.pageScrollTo({ scrollTop: 0 })
}

function openTask(taskId: string) {
  uni.navigateTo({
    url: `/pages/task/detail?id=${encodeURIComponent(taskId)}`,
  })
}

onShow(loadTasks)
</script>

<template>
  <view class="page">
    <view class="search-card">
      <wd-search
        v-model="keyword"
        placeholder="搜索任务、业务编号、供应商或采购订单"
        @clear="resetSearch"
        @search="searchTasks"
      />
    </view>

    <view class="summary-row">
      <text>共 {{ taskPage.total }} 项待处理任务</text>
      <wd-button size="small" plain :loading="loading" @click="loadTasks">
        刷新
      </wd-button>
    </view>

    <view v-if="loadError" class="state-card state-card--error">
      <text>{{ loadError }}</text>
      <wd-button size="small" plain @click="loadTasks">重新加载</wd-button>
    </view>

    <view v-else-if="loading" class="state-card">
      <text>正在加载待办...</text>
    </view>

    <view v-else-if="taskPage.items.length === 0" class="state-card">
      <text>当前没有待处理任务</text>
    </view>

    <view v-else class="task-list">
      <view
        v-for="task in taskPage.items"
        :key="task.taskId"
        class="task-card"
        @click="openTask(task.taskId)"
      >
        <view class="task-card__header">
          <text class="task-card__title">{{ task.supplier }}采购付款</text>
          <wd-tag :type="taskTagType(task)" plain>{{ taskStage(task) }}</wd-tag>
        </view>
        <text class="task-card__meta">
          {{ task.businessKey }} · {{ task.purchaseOrderReference }}
        </text>
        <text class="task-card__meta">发起人 {{ task.initiatorId }}</text>
        <view class="task-card__footer">
          <text class="task-card__amount">{{ formatMoney(task.amount) }}</text>
          <text>{{ formatDate(task.taskCreatedAt) }}</text>
        </view>
      </view>
    </view>

    <view v-if="taskPage.total > pageSize" class="pagination-bar">
      <wd-button size="small" plain :disabled="currentPage <= 1" @click="previousPage">
        上一页
      </wd-button>
      <text>第 {{ currentPage }} 页</text>
      <wd-button size="small" plain :disabled="!taskPage.hasMore" @click="nextPage">
        下一页
      </wd-button>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 60rpx;
  background: var(--wot-color-bg, var(--uni-bg-color-grey));
}

.search-card,
.task-card,
.state-card {
  border-radius: var(--wot-radius-large, 24rpx);
  background: var(--wot-color-white, var(--uni-bg-color));
  box-shadow: 0 8rpx 24rpx rgb(15 23 42 / 5%);
}

.search-card {
  overflow: hidden;
}

.summary-row,
.task-card__header,
.task-card__footer,
.pagination-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.summary-row {
  padding: 24rpx 4rpx;
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-size: 25rpx;
}

.task-list {
  display: grid;
  gap: 20rpx;
}

.task-card {
  display: grid;
  gap: 14rpx;
  padding: 28rpx;
}

.task-card__title {
  flex: 1;
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 30rpx;
  font-weight: 600;
}

.task-card__meta,
.task-card__footer,
.pagination-bar,
.state-card {
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-size: 24rpx;
}

.task-card__amount {
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 28rpx;
  font-weight: 700;
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
