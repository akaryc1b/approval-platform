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
  rejectTask,
  resubmitTask,
  transferTask,
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
const transferIndex = ref(-1)

const revisionTask = computed(() => details.value?.taskDefinitionKey === 'initiatorRevision')
const transferCandidates = computed(() => details.value?.transferCandidates ?? [])
const pickerIndex = computed(() => Math.max(transferIndex.value, 0))
const selectedTransferCandidate = computed(() => {
  if (transferIndex.value < 0) {
    return undefined
  }
  return transferCandidates.value[transferIndex.value]
})

function taskStage(task: PendingTaskDetails) {
  const labels: Record<string, string> = {
    financeCountersign: '财务会签',
    financeReview: '财务审核',
    initiatorRevision: '发起人修改',
    managerApproval: '部门负责人审批',
  }
  return labels[task.taskDefinitionKey] || task.taskName
}

function timelineTitle(item: ApprovalTimelineItem) {
  const labels: Record<string, string> = {
    INSTANCE_STARTED: '发起审批',
    INSTANCE_WITHDRAWN: '发起人撤回',
    TASK_APPROVED: '同意审批',
    TASK_REJECTED: '驳回到发起人',
    TASK_RESUBMITTED: '发起人重新提交',
    TASK_RETRIEVED: '审批人拿回',
    TASK_TRANSFERRED: '任务转办',
  }
  return labels[item.action] || item.action
}

function timelineTone(item: ApprovalTimelineItem) {
  if (item.action === 'TASK_REJECTED' || item.action === 'INSTANCE_WITHDRAWN') {
    return 'danger'
  }
  if (
    item.action === 'TASK_RESUBMITTED'
    || item.action === 'TASK_RETRIEVED'
    || item.action === 'TASK_TRANSFERRED'
  ) {
    return 'warning'
  }
  if (item.action === 'TASK_APPROVED') {
    return 'success'
  }
  return 'primary'
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
  opinion.value = ''
  transferIndex.value = -1
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

function goBack() {
  uni.navigateBack()
}

function selectTransferCandidate(event: { detail: { value: number | string } }) {
  transferIndex.value = Number(event.detail.value)
}

async function finishAction(
  action: () => Promise<unknown>,
  successMessage: string,
) {
  submitting.value = true
  try {
    await action()
    uni.showToast({ title: successMessage, icon: 'success' })
    setTimeout(goBack, 500)
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    submitting.value = false
  }
}

async function submitApproval() {
  const task = details.value
  if (!task || submitting.value) {
    return
  }
  const confirmed = await confirmAction('审批确认', '确认同意该审批吗？', '确认同意')
  if (!confirmed) {
    return
  }
  await finishAction(
    () => approveTask(task.taskId, opinion.value),
    '审批已同意',
  )
}

async function submitRejection() {
  const task = details.value
  const comment = opinion.value.trim()
  if (!task || submitting.value) {
    return
  }
  if (!comment) {
    uni.showToast({ title: '驳回时必须填写原因', icon: 'none' })
    return
  }
  const confirmed = await confirmAction(
    '驳回确认',
    '驳回后将生成发起人修改任务，确认继续吗？',
    '确认驳回',
  )
  if (!confirmed) {
    return
  }
  await finishAction(
    () => rejectTask(task.taskId, comment),
    '已驳回到发起人',
  )
}

async function submitResubmission() {
  const task = details.value
  if (!task || submitting.value) {
    return
  }
  const confirmed = await confirmAction(
    '重新提交确认',
    '重新提交后流程将从部门负责人审批重新开始，确认继续吗？',
    '重新提交',
  )
  if (!confirmed) {
    return
  }
  await finishAction(
    () => resubmitTask(task.taskId, opinion.value),
    '申请已重新提交',
  )
}

async function submitTransfer() {
  const task = details.value
  const candidate = selectedTransferCandidate.value
  const comment = opinion.value.trim()
  if (!task || submitting.value) {
    return
  }
  if (!candidate) {
    uni.showToast({ title: '请选择转办人员', icon: 'none' })
    return
  }
  if (!comment) {
    uni.showToast({ title: '转办时必须填写原因', icon: 'none' })
    return
  }
  const confirmed = await confirmAction(
    '转办确认',
    `确认将该任务转办给${candidate.displayName}吗？`,
    '确认转办',
  )
  if (!confirmed) {
    return
  }
  await finishAction(
    () => transferTask(task.taskId, candidate.userId, comment),
    '任务已转办',
  )
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
      <view v-if="revisionTask" class="revision-notice">
        <strong>申请已被驳回</strong>
        <text>确认材料后可重新提交，流程将从部门负责人审批重新开始。</text>
      </view>

      <view class="summary-card">
        <view class="summary-card__header">
          <view>
            <text class="eyebrow">采购付款审批</text>
            <view class="title">{{ details.supplier }}采购付款</view>
          </view>
          <wd-tag :type="revisionTask ? 'warning' : 'primary'">
            {{ taskStage(details) }}
          </wd-tag>
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
            <view class="timeline-dot" :class="`timeline-dot--${timelineTone(item)}`" />
            <view class="timeline-content">
              <strong>{{ timelineTitle(item) }}</strong>
              <text>操作人 {{ item.operatorId }}</text>
              <text v-if="item.attributes.comment">
                意见：{{ item.attributes.comment }}
              </text>
              <text>{{ formatDate(item.occurredAt) }}</text>
            </view>
          </view>
        </view>
        <text v-else class="muted-text">暂无审批记录</text>
      </view>

      <view
        v-if="!revisionTask && transferCandidates.length"
        class="transfer-card"
      >
        <view class="section-title">转办人员</view>
        <picker
          :range="transferCandidates"
          range-key="displayName"
          :value="pickerIndex"
          @change="selectTransferCandidate"
        >
          <view class="picker-field">
            <view>
              <text v-if="selectedTransferCandidate">
                {{ selectedTransferCandidate.displayName }}
              </text>
              <text v-else class="muted-text">从流程审批人快照中选择</text>
            </view>
            <text class="picker-arrow">›</text>
          </view>
        </picker>
        <text class="field-hint">点击“转办”时，下方审批意见将作为转办原因且必须填写。</text>
      </view>

      <view class="opinion-card">
        <view class="section-title">
          {{ revisionTask ? '重新提交说明' : '审批意见' }}
        </view>
        <wd-textarea
          v-model="opinion"
          :maxlength="2000"
          clearable
          :placeholder="revisionTask ? '填写本次修改说明（可选）' : '填写审批意见；驳回或转办时必填'"
          show-word-limit
        />
      </view>
    </template>

    <view class="action-bar">
      <wd-button plain @click="goBack">返回</wd-button>
      <view class="action-group">
        <wd-button
          v-if="revisionTask"
          type="primary"
          :disabled="!details || loading"
          :loading="submitting"
          @click="submitResubmission"
        >
          重新提交
        </wd-button>
        <template v-else>
          <wd-button
            v-if="transferCandidates.length"
            plain
            :disabled="!details || loading"
            :loading="submitting"
            @click="submitTransfer"
          >
            转办
          </wd-button>
          <wd-button
            type="error"
            plain
            :disabled="!details || loading"
            :loading="submitting"
            @click="submitRejection"
          >
            驳回
          </wd-button>
          <wd-button
            type="primary"
            :disabled="!details || loading"
            :loading="submitting"
            @click="submitApproval"
          >
            同意
          </wd-button>
        </template>
      </view>
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
.transfer-card,
.opinion-card,
.state-card,
.revision-notice {
  margin-bottom: 20rpx;
  padding: 28rpx;
  border-radius: var(--wot-radius-large, 24rpx);
  background: var(--wot-color-white, var(--uni-bg-color));
}

.revision-notice {
  display: grid;
  gap: 10rpx;
  color: var(--wot-color-warning, #d97706);
  background: var(--wot-color-warning-light, #fff7ed);
  font-size: 25rpx;
  line-height: 1.6;
}

.summary-card__header,
.action-bar,
.action-group,
.picker-field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.eyebrow,
.summary-grid text,
.timeline-content text,
.muted-text,
.field-hint,
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

.picker-field {
  min-height: 84rpx;
  padding: 0 22rpx;
  border: 1rpx solid var(--wot-color-border-light, var(--uni-border-color));
  border-radius: var(--wot-radius-medium, 16rpx);
  color: var(--wot-color-content, var(--uni-text-color));
}

.picker-arrow {
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-size: 40rpx;
}

.field-hint {
  display: block;
  margin-top: 14rpx;
  line-height: 1.5;
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

.timeline-dot--success {
  background: var(--wot-color-success, #22c55e);
  box-shadow: 0 0 0 2rpx var(--wot-color-success, #22c55e);
}

.timeline-dot--danger {
  background: var(--wot-color-danger, var(--uni-color-error));
  box-shadow: 0 0 0 2rpx var(--wot-color-danger, var(--uni-color-error));
}

.timeline-dot--warning {
  background: var(--wot-color-warning, #f59e0b);
  box-shadow: 0 0 0 2rpx var(--wot-color-warning, #f59e0b);
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
  padding: 20rpx 24rpx calc(20rpx + env(safe-area-inset-bottom));
  background: var(--wot-color-white, var(--uni-bg-color));
  box-shadow: 0 -8rpx 24rpx rgb(15 23 42 / 8%);
}
</style>
