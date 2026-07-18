<script lang="ts" setup>
import type {
  PendingTaskItem,
  PendingTaskPage,
  ProcessedTaskItem,
  ProcessedTaskPage,
  StartedInstanceItem,
  StartedInstancePage,
} from '@/api/approval'

import {
  findPendingTasks,
  findProcessedTasks,
  findStartedInstances,
  retrieveTask,
  withdrawInstance,
} from '@/api/approval'

type ViewMode = 'pending' | 'processed' | 'started'

defineOptions({
  name: 'ApprovalTaskList',
})

definePage({
  style: {
    navigationBarTitleText: '审批中心',
  },
})

const pageSize = 20
const modes: Array<{ label: string, value: ViewMode }> = [
  { label: '待我处理', value: 'pending' },
  { label: '我已处理', value: 'processed' },
  { label: '我发起的', value: 'started' },
]
const activeMode = ref<ViewMode>('pending')
const currentPage = ref(1)
const keyword = ref('')
const loading = ref(false)
const loadError = ref('')
const actionId = ref('')
const pendingTotal = ref(0)
const processedTotal = ref(0)
const startedTotal = ref(0)

const pendingPage = ref<PendingTaskPage>(emptyPendingPage())
const processedPage = ref<ProcessedTaskPage>(emptyProcessedPage())
const startedPage = ref<StartedInstancePage>(emptyStartedPage())

const activeTotal = computed(() => {
  if (activeMode.value === 'processed') {
    return processedPage.value.total
  }
  if (activeMode.value === 'started') {
    return startedPage.value.total
  }
  return pendingPage.value.total
})
const activeHasMore = computed(() => {
  if (activeMode.value === 'processed') {
    return processedPage.value.hasMore
  }
  if (activeMode.value === 'started') {
    return startedPage.value.hasMore
  }
  return pendingPage.value.hasMore
})
const activeItemCount = computed(() => {
  if (activeMode.value === 'processed') {
    return processedPage.value.items.length
  }
  if (activeMode.value === 'started') {
    return startedPage.value.items.length
  }
  return pendingPage.value.items.length
})

function emptyPendingPage(): PendingTaskPage {
  return { hasMore: false, items: [], limit: pageSize, offset: 0, total: 0 }
}

function emptyProcessedPage(): ProcessedTaskPage {
  return { hasMore: false, items: [], limit: pageSize, offset: 0, total: 0 }
}

function emptyStartedPage(): StartedInstancePage {
  return { hasMore: false, items: [], limit: pageSize, offset: 0, total: 0 }
}

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

function statusLabel(status: StartedInstanceItem['status']) {
  const labels = {
    COMPLETED: '已完成',
    REJECTED: '已驳回',
    RUNNING: '审批中',
    WITHDRAWN: '已撤回',
  }
  return labels[status]
}

function statusTone(status: StartedInstanceItem['status']) {
  if (status === 'COMPLETED') {
    return 'success'
  }
  if (status === 'RUNNING') {
    return 'primary'
  }
  return 'info'
}

function formatMoney(value: number) {
  return `¥${Number(value).toFixed(2)}`
}

function formatDate(value: string) {
  const date = new Date(value)
  return `${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '审批事项加载失败'
}

async function loadActivePage() {
  loading.value = true
  loadError.value = ''
  const parameters = {
    keyword: keyword.value,
    limit: pageSize,
    offset: (currentPage.value - 1) * pageSize,
  }
  try {
    if (activeMode.value === 'processed') {
      processedPage.value = await findProcessedTasks(parameters)
      processedTotal.value = processedPage.value.total
    }
    else if (activeMode.value === 'started') {
      startedPage.value = await findStartedInstances(parameters)
      startedTotal.value = startedPage.value.total
    }
    else {
      pendingPage.value = await findPendingTasks(parameters)
      pendingTotal.value = pendingPage.value.total
    }
  }
  catch (error) {
    loadError.value = errorMessage(error)
    if (activeMode.value === 'processed') {
      processedPage.value = emptyProcessedPage()
    }
    else if (activeMode.value === 'started') {
      startedPage.value = emptyStartedPage()
    }
    else {
      pendingPage.value = emptyPendingPage()
    }
  }
  finally {
    loading.value = false
  }
}

async function loadCounts() {
  const parameters = { limit: 1, offset: 0 }
  const [pending, processed, started] = await Promise.allSettled([
    findPendingTasks(parameters),
    findProcessedTasks(parameters),
    findStartedInstances(parameters),
  ])
  if (pending.status === 'fulfilled') {
    pendingTotal.value = pending.value.total
  }
  if (processed.status === 'fulfilled') {
    processedTotal.value = processed.value.total
  }
  if (started.status === 'fulfilled') {
    startedTotal.value = started.value.total
  }
}

async function refreshAll() {
  await Promise.all([loadActivePage(), loadCounts()])
}

async function switchMode(mode: ViewMode) {
  if (activeMode.value === mode) {
    return
  }
  activeMode.value = mode
  keyword.value = ''
  currentPage.value = 1
  await loadActivePage()
}

async function searchItems() {
  currentPage.value = 1
  await loadActivePage()
}

async function resetSearch() {
  keyword.value = ''
  currentPage.value = 1
  await loadActivePage()
}

async function previousPage() {
  if (currentPage.value <= 1) {
    return
  }
  currentPage.value -= 1
  await loadActivePage()
  uni.pageScrollTo({ scrollTop: 0 })
}

async function nextPage() {
  if (!activeHasMore.value) {
    return
  }
  currentPage.value += 1
  await loadActivePage()
  uni.pageScrollTo({ scrollTop: 0 })
}

function openTask(taskId: string) {
  uni.navigateTo({
    url: `/pages/task/detail?id=${encodeURIComponent(taskId)}`,
  })
}

function confirmAction(title: string, content: string, confirmText: string) {
  return new Promise<boolean>((resolve) => {
    uni.showModal({
      title,
      content,
      confirmText,
      success: result => resolve(result.confirm),
      fail: () => resolve(false),
    })
  })
}

async function withdraw(item: StartedInstanceItem) {
  if (!item.withdrawable || actionId.value) {
    return
  }
  const confirmed = await confirmAction(
    '撤回确认',
    '撤回后当前审批任务将全部取消，确认继续吗？',
    '确认撤回',
  )
  if (!confirmed) {
    return
  }
  actionId.value = item.instanceId
  try {
    await withdrawInstance(item.instanceId, '发起人从移动审批中心撤回')
    uni.showToast({ title: '申请已撤回', icon: 'success' })
    await refreshAll()
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    actionId.value = ''
  }
}

async function retrieve(item: ProcessedTaskItem) {
  if (!item.retrievable || actionId.value) {
    return
  }
  const confirmed = await confirmAction(
    '拿回确认',
    '拿回后下游待办将取消，任务重新回到你名下，确认继续吗？',
    '确认拿回',
  )
  if (!confirmed) {
    return
  }
  actionId.value = item.taskId
  try {
    await retrieveTask(item.taskId, '审批人从移动已处理列表拿回')
    uni.showToast({ title: '任务已拿回', icon: 'success' })
    activeMode.value = 'pending'
    currentPage.value = 1
    await refreshAll()
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    actionId.value = ''
  }
}

onShow(refreshAll)
</script>

<template>
  <view class="page">
    <scroll-view class="mode-scroll" scroll-x>
      <view class="mode-row">
        <wd-button
          v-for="mode in modes"
          :key="mode.value"
          :plain="activeMode !== mode.value"
          size="small"
          type="primary"
          @click="switchMode(mode.value)"
        >
          {{ mode.label }}
          {{ mode.value === 'pending' ? pendingTotal : mode.value === 'processed' ? processedTotal : startedTotal }}
        </wd-button>
      </view>
    </scroll-view>

    <view class="search-card">
      <wd-search
        v-model="keyword"
        placeholder="搜索业务编号、供应商、采购订单或任务"
        @clear="resetSearch"
        @search="searchItems"
      />
    </view>

    <view class="summary-row">
      <text>共 {{ activeTotal }} 项</text>
      <wd-button size="small" plain :loading="loading" @click="refreshAll">
        刷新
      </wd-button>
    </view>

    <view v-if="loadError" class="state-card state-card--error">
      <text>{{ loadError }}</text>
      <wd-button size="small" plain @click="loadActivePage">重新加载</wd-button>
    </view>
    <view v-else-if="loading" class="state-card">
      <text>正在加载审批事项...</text>
    </view>
    <view v-else-if="activeItemCount === 0" class="state-card">
      <text>当前没有相关审批事项</text>
    </view>

    <view v-else-if="activeMode === 'pending'" class="task-list">
      <view
        v-for="task in pendingPage.items"
        :key="task.taskId"
        class="task-card"
        @click="openTask(task.taskId)"
      >
        <view class="task-card__header">
          <text class="task-card__title">{{ task.supplier }}采购付款</text>
          <wd-tag :type="taskTagType(task)" plain>{{ taskStage(task) }}</wd-tag>
        </view>
        <text class="task-card__meta">{{ task.businessKey }} · {{ task.purchaseOrderReference }}</text>
        <text class="task-card__meta">发起人 {{ task.initiatorId }}</text>
        <view class="task-card__footer">
          <text class="task-card__amount">{{ formatMoney(task.amount) }}</text>
          <text>{{ formatDate(task.taskCreatedAt) }}</text>
        </view>
      </view>
    </view>

    <view v-else-if="activeMode === 'processed'" class="task-list">
      <view v-for="task in processedPage.items" :key="task.taskId" class="task-card">
        <view class="task-card__header">
          <text class="task-card__title">{{ task.supplier }}采购付款</text>
          <wd-tag type="success" plain>{{ taskStage(task) }}</wd-tag>
        </view>
        <text class="task-card__meta">{{ task.businessKey }} · {{ task.purchaseOrderReference }}</text>
        <view class="task-card__footer">
          <view>
            <text class="task-card__amount">{{ formatMoney(task.amount) }}</text>
            <text class="task-card__date">{{ formatDate(task.completedAt) }}</text>
          </view>
          <wd-button
            v-if="task.retrievable"
            size="small"
            plain
            type="warning"
            :loading="actionId === task.taskId"
            @click="retrieve(task)"
          >
            拿回
          </wd-button>
          <wd-tag v-else type="info" plain>不可拿回</wd-tag>
        </view>
      </view>
    </view>

    <view v-else class="task-list">
      <view v-for="item in startedPage.items" :key="item.instanceId" class="task-card">
        <view class="task-card__header">
          <text class="task-card__title">{{ item.supplier }}采购付款</text>
          <wd-tag :type="statusTone(item.status)" plain>{{ statusLabel(item.status) }}</wd-tag>
        </view>
        <text class="task-card__meta">{{ item.businessKey }} · {{ item.purchaseOrderReference }}</text>
        <text class="task-card__meta">
          当前环节 {{ item.currentTaskName || '流程已结束' }}
        </text>
        <view class="task-card__footer">
          <view>
            <text class="task-card__amount">{{ formatMoney(item.amount) }}</text>
            <text class="task-card__date">{{ formatDate(item.updatedAt) }}</text>
          </view>
          <wd-button
            v-if="item.withdrawable"
            size="small"
            plain
            type="error"
            :loading="actionId === item.instanceId"
            @click="withdraw(item)"
          >
            撤回
          </wd-button>
        </view>
      </view>
    </view>

    <view v-if="activeTotal > pageSize" class="pagination-bar">
      <wd-button size="small" plain :disabled="currentPage <= 1" @click="previousPage">
        上一页
      </wd-button>
      <text>第 {{ currentPage }} 页</text>
      <wd-button size="small" plain :disabled="!activeHasMore" @click="nextPage">
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

.mode-scroll {
  width: 100%;
  margin-bottom: 20rpx;
  white-space: nowrap;
}

.mode-row {
  display: inline-flex;
  gap: 16rpx;
  min-width: 100%;
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
.state-card,
.task-card__date {
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-size: 24rpx;
}

.task-card__footer > view {
  display: grid;
  gap: 6rpx;
}

.task-card__amount {
  display: block;
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
