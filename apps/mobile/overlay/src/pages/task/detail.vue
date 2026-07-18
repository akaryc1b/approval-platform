<script lang="ts" setup>
import type {
  ApprovalTimeline,
  ApprovalTimelineItem,
  PendingTaskDetails,
} from '@/api/approval'

import {
  approveTask,
  findApprovalTimeline,
  findPendingTask,
} from '@/api/approval'

defineOptions({
  name: 'ApprovalTaskDetail',
})

definePage({
  style: {
    navigationBarTitleText: '审批详情',
  },
})

const taskId = ref('')
const opinion = ref('')
const loading = ref(false)
const loadError = ref('')
const submitting = ref(false)
const details = ref<PendingTaskDetails>()
const timeline = ref<ApprovalTimeline>()

function taskStage(task: PendingTaskDetails) {
  const labels: Record<string, string> = {
    financeCountersign: '财务会签',
    financeReview: '财务审核',
    managerApproval: '部门负责人审批',
  }
  return labels[task.taskDefinitionKey] || task.taskName
}

function timelineTitle(item: ApprovalTimelineItem) {
  const labels: Record<string, string> = {
    INSTANCE_STARTED: '发起审批',
    TASK_APPROVED: '同意审批',
  }
  return labels[item.action] || item.action
}

function formatMoney(value: number) {
  return `¥${Number(value).toFixed(2)}`
}

function formatDate(value: string) {
  const date = new Date(value)
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '审批详情加载失败'
}

async function loadDetails() {
  if (!taskId.value) {
    loadError.value = '缺少审批任务编号'
    return
  }
  loading.value = true
  loadError.value = ''
  details.value = undefined
  timeline.value = undefined
  try {
    const task = await findPendingTask(taskId.value)
    const progress = await findApprovalTimeline(task.instanceId)
    details.value = task
    timeline.value = progress
  }
  catch (error) {
    loadError.value = errorMessage(error)
  }
  finally {
    loading.value = false
  }
}

function confirmApproval() {
  return new Promise<boolean>((resolve) => {
    uni.showModal({
      title: '审批确认',
      content: '确认同意该审批吗？',
      confirmText: '确认同意',
      success: result => resolve(result.confirm),
      fail: () => resolve(false),
    })
  })
}

async function submitApproval() {
  const task = details.value
  if (!task || submitting.value) {
    return
  }
  if (!await confirmApproval()) {
    return
  }

  submitting.value = true
  try {
    await approveTask(task.taskId, opinion.value)
    uni.showToast({ title: '审批已同意', icon: 'success' })
    setTimeout(() => uni.navigateBack(), 500)
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    submitting.value = false
  }
}

onLoad((query) => {
  taskId.value = String(query?.id || '')
  loadDetails()
})
</script>

<template>
  <view class="page">
    <view v-if="loadError" class="state-card state-card--error">
      <text>{{ loadError }}</text>
      <wd-button size="small" plain @click="loadDetails">重新加载</wd-button>
    </view>

    <view v-else-if="loading" class="state-card">
      <text>正在加载审批详情...</text>
    </view>

    <template v-else-if="details">
      <view class="summary-card">
        <view class="summary-card__header">
          <view>
            <text class="eyebrow">采购付款审批</text>
            <view class="title">{{ details.supplier }}采购付款</view>
          </view>
          <wd-tag type="primary">{{ taskStage(details) }}</wd-tag>
        </view>
        <view class="summary-grid">
          <view>
            <text>发起人</text>
            <strong>{{ details.initiatorId }}</strong>
          </view>
          <view>
            <text>付款金额</text>
            <strong>{{ formatMoney(details.amount) }}</strong>
          </view>
          <view>
            <text>供应商</text>
            <strong>{{ details.supplier }}</strong>
          </view>
          <view>
            <text>采购订单</text>
            <strong>{{ details.purchaseOrderReference }}</strong>
          </view>
          <view class="summary-grid__wide">
            <text>业务编号</text>
            <strong>{{ details.businessKey }}</strong>
          </view>
        </view>
      </view>

      <view class="attachment-card">
        <view class="section-title">附件</view>
        <view v-if="details.attachmentIds.length" class="attachment-list">
          <wd-tag
            v-for="attachment in details.attachmentIds"
            :key="attachment"
            plain
            type="primary"
          >
            {{ attachment }}
          </wd-tag>
        </view>
        <text v-else class="muted-text">无附件</text>
      </view>

      <view class="timeline-card">
        <view class="section-title">审批进度</view>
        <view v-if="timeline?.items.length" class="timeline-list">
          <view
            v-for="item in timeline.items"
            :key="item.eventId"
            class="timeline-item"
          >
            <view class="timeline-dot" />
            <view class="timeline-content">
              <strong>{{ timelineTitle(item) }}</strong>
              <text>操作人 {{ item.operatorId }}</text>
              <text>{{ formatDate(item.occurredAt) }}</text>
            </view>
          </view>
        </view>
        <text v-else class="muted-text">暂无审批记录</text>
      </view>

      <view class="opinion-card">
        <view class="section-title">审批意见</view>
        <wd-textarea
          v-model="opinion"
          :maxlength="500"
          clearable
          placeholder="填写审批意见"
          show-word-limit
        />
      </view>
    </template>

    <view class="action-bar">
      <wd-button plain @click="uni.navigateBack()">返回</wd-button>
      <wd-button
        type="primary"
        :disabled="!details || loading"
        :loading="submitting"
        @click="submitApproval"
      >
        同意
      </wd-button>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 170rpx;
  background: var(--wot-color-bg, var(--uni-bg-color-grey));
}

.summary-card,
.attachment-card,
.timeline-card,
.opinion-card,
.state-card {
  margin-bottom: 20rpx;
  padding: 28rpx;
  border-radius: var(--wot-radius-large, 24rpx);
  background: var(--wot-color-white, var(--uni-bg-color));
}

.summary-card__header,
.action-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.eyebrow,
.summary-grid text,
.timeline-content text,
.muted-text,
.state-card {
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-size: 24rpx;
}

.title {
  margin-top: 10rpx;
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 34rpx;
  font-weight: 700;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24rpx;
  margin-top: 28rpx;
}

.summary-grid > view,
.timeline-content {
  display: grid;
  gap: 8rpx;
}

.summary-grid strong,
.timeline-content strong {
  color: var(--wot-color-content, var(--uni-text-color));
  word-break: break-all;
}

.summary-grid__wide {
  grid-column: 1 / -1;
}

.section-title {
  margin-bottom: 20rpx;
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 28rpx;
  font-weight: 700;
}

.attachment-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.timeline-list {
  display: grid;
}

.timeline-item {
  position: relative;
  display: flex;
  gap: 20rpx;
  padding: 0 0 28rpx 6rpx;
}

.timeline-item::after {
  position: absolute;
  top: 22rpx;
  bottom: 0;
  left: 15rpx;
  width: 2rpx;
  content: '';
  background: var(--wot-color-border-light, var(--uni-border-color));
}

.timeline-item:last-child::after {
  display: none;
}

.timeline-dot {
  z-index: 1;
  width: 20rpx;
  height: 20rpx;
  margin-top: 4rpx;
  border: 4rpx solid var(--wot-color-white, var(--uni-bg-color));
  border-radius: 50%;
  background: var(--wot-color-theme, var(--uni-color-primary));
  box-shadow: 0 0 0 2rpx var(--wot-color-theme, var(--uni-color-primary));
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

.action-bar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  justify-content: flex-end;
  padding: 20rpx 24rpx calc(20rpx + env(safe-area-inset-bottom));
  background: var(--wot-color-white, var(--uni-bg-color));
  box-shadow: 0 -8rpx 24rpx rgb(15 23 42 / 8%);
}
</style>
