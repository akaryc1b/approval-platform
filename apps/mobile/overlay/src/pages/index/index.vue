<script lang="ts" setup>
import type { PendingTaskItem, PendingTaskPage } from '@/api/approval'

import { findPendingTasks } from '@/api/approval'

defineOptions({
  name: 'ApprovalHome',
})

definePage({
  type: 'home',
  style: {
    navigationBarTitleText: '审批工作台',
  },
})

const loading = ref(false)
const loadError = ref('')
const taskPage = ref<PendingTaskPage>({
  hasMore: false,
  items: [],
  limit: 3,
  offset: 0,
  total: 0,
})

function taskStage(task: Pick<PendingTaskItem, 'taskDefinitionKey' | 'taskName'>) {
  const labels: Record<string, string> = {
    financeCountersign: '财务会签',
    financeReview: '财务审核',
    managerApproval: '部门负责人审批',
  }
  return labels[task.taskDefinitionKey] || task.taskName
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

async function loadSummary() {
  loading.value = true
  loadError.value = ''
  try {
    taskPage.value = await findPendingTasks({ limit: 3, offset: 0 })
  }
  catch (error) {
    loadError.value = errorMessage(error)
    taskPage.value = {
      hasMore: false,
      items: [],
      limit: 3,
      offset: 0,
      total: 0,
    }
  }
  finally {
    loading.value = false
  }
}

function openTask(taskId: string) {
  uni.navigateTo({
    url: `/pages/task/detail?id=${encodeURIComponent(taskId)}`,
  })
}

function openTaskList() {
  uni.navigateTo({ url: '/pages/task/list' })
}

function openInitiate() {
  uni.switchTab({ url: '/pages/initiate/index' })
}

onShow(loadSummary)
</script>

<template>
  <view class="page">
    <view class="hero">
      <view>
        <text class="hero__eyebrow">审批工作台</text>
        <view class="hero__title">你有 {{ taskPage.total }} 项待处理审批</view>
      </view>
      <wd-button size="small" type="primary" @click="openTaskList">
        全部待办
      </wd-button>
    </view>

    <view class="metric-grid">
      <view class="metric-card">
        <text class="metric-card__label">待我审批</text>
        <view class="metric-card__value">{{ taskPage.total }}</view>
        <wd-tag type="primary" plain>实时</wd-tag>
      </view>
      <view class="metric-card">
        <text class="metric-card__label">当前展示</text>
        <view class="metric-card__value">{{ taskPage.items.length }}</view>
        <wd-tag type="success" plain>最新任务</wd-tag>
      </view>
    </view>

    <view class="section-title">
      <text>优先处理</text>
      <text class="section-title__hint">按任务到达时间排序</text>
    </view>

    <view v-if="loadError" class="state-card state-card--error">
      <text>{{ loadError }}</text>
      <wd-button size="small" plain @click="loadSummary">重新加载</wd-button>
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
          <wd-tag type="primary" plain>{{ taskStage(task) }}</wd-tag>
        </view>
        <text class="task-card__meta">
          {{ task.businessKey }} · {{ task.purchaseOrderReference }}
        </text>
        <view class="task-card__footer">
          <text>{{ formatMoney(task.amount) }}</text>
          <text>{{ formatDate(task.taskCreatedAt) }}</text>
        </view>
      </view>
    </view>

    <view class="section-title">
      <text>快捷操作</text>
    </view>
    <view class="actions">
      <wd-button block type="primary" @click="openInitiate">
        发起审批
      </wd-button>
      <wd-button block plain @click="openTaskList">
        查看待办
      </wd-button>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 28rpx 28rpx 160rpx;
  background: var(--wot-color-bg, var(--uni-bg-color-grey));
}

.hero,
.section-title,
.task-card__header,
.task-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.hero {
  padding: 32rpx;
  color: var(--wot-color-white, var(--uni-text-color-inverse));
  border-radius: var(--wot-radius-large, 28rpx);
  background: var(--wot-color-theme, var(--uni-color-primary));
}

.hero__eyebrow {
  font-size: 24rpx;
  opacity: 0.82;
}

.hero__title {
  max-width: 480rpx;
  margin-top: 12rpx;
  font-size: 34rpx;
  font-weight: 700;
  line-height: 1.4;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20rpx;
  margin-top: 24rpx;
}

.metric-card,
.task-card,
.state-card {
  padding: 24rpx;
  border-radius: var(--wot-radius-large, 22rpx);
  background: var(--wot-color-white, var(--uni-bg-color));
  box-shadow: 0 8rpx 24rpx rgb(15 23 42 / 5%);
}

.metric-card__label,
.task-card__meta,
.task-card__footer,
.section-title__hint,
.state-card {
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-size: 24rpx;
}

.metric-card__value {
  margin: 12rpx 0;
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 48rpx;
  font-weight: 700;
}

.section-title {
  margin: 36rpx 4rpx 18rpx;
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 30rpx;
  font-weight: 700;
}

.task-list,
.actions {
  display: grid;
  gap: 18rpx;
}

.task-card {
  display: grid;
  gap: 12rpx;
}

.task-card__title {
  flex: 1;
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 29rpx;
  font-weight: 600;
}

.state-card {
  display: grid;
  justify-items: center;
  gap: 20rpx;
  text-align: center;
}

.state-card--error {
  color: var(--wot-color-danger, var(--uni-color-error));
}
</style>
