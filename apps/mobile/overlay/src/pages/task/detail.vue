<script lang="ts" setup>
import type {
  ApprovalTimeline,
  ApprovalTimelineItem,
  PendingTaskDetails,
} from '@/api/approval'
import type { FormRuntimeView } from '@/api/approval/form-types'

import {
  approveTask,
  findApprovalTimeline,
  findPendingTask,
  rejectTask,
  transferTask,
} from '@/api/approval'
import { findTaskFormRuntime, resubmitFormTask } from '@/api/approval/forms'
import ApprovalFormRenderer from '@/components/approval/ApprovalFormRenderer.vue'

defineOptions({ name: 'ApprovalTaskDetail' })

definePage({ style: { navigationBarTitleText: '审批详情' } })

const taskId = ref('')
const opinion = ref('')
const loading = ref(false)
const loadError = ref('')
const submitting = ref(false)
const details = ref<PendingTaskDetails>()
const timeline = ref<ApprovalTimeline>()
const formRuntime = ref<FormRuntimeView>()
const formValues = ref<Record<string, unknown>>({})
const transferIndex = ref(-1)
const renderer = ref<{
  editableValues: () => Record<string, unknown>
  validate: () => string
}>()

const revisionTask = computed(() => details.value?.taskDefinitionKey === 'initiatorRevision')
const transferCandidates = computed(() => details.value?.transferCandidates ?? [])
const pickerIndex = computed(() => Math.max(transferIndex.value, 0))
const selectedTransferCandidate = computed(() => transferIndex.value < 0
  ? undefined
  : transferCandidates.value[transferIndex.value])

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
    TASK_RESUBMITTED: '修改并重新提交',
    TASK_RETRIEVED: '审批人拿回',
    TASK_TRANSFERRED: '任务转办',
  }
  return labels[item.action] || item.action
}

function formatMoney(value: number) {
  return `¥${Number(value).toFixed(2)}`
}

function formatDate(value: string) {
  const date = new Date(value)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
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
  try {
    const task = await findPendingTask(taskId.value)
    const [progress, runtime] = await Promise.all([
      findApprovalTimeline(task.instanceId),
      findTaskFormRuntime(task.taskId),
    ])
    details.value = task
    timeline.value = progress
    formRuntime.value = runtime
    formValues.value = { ...runtime.values }
    opinion.value = ''
    transferIndex.value = -1
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

async function finishAction(action: () => Promise<unknown>, successMessage: string) {
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
  if (!task || submitting.value) return
  if (!await confirmAction('审批确认', '确认同意该审批吗？', '确认同意')) return
  await finishAction(() => approveTask(task.taskId, opinion.value), '审批已同意')
}

async function submitRejection() {
  const task = details.value
  const comment = opinion.value.trim()
  if (!task || submitting.value) return
  if (!comment) {
    uni.showToast({ title: '驳回时必须填写原因', icon: 'none' })
    return
  }
  if (!await confirmAction('驳回确认', '驳回后将生成发起人修改任务，确认继续吗？', '确认驳回')) return
  await finishAction(() => rejectTask(task.taskId, comment), '已驳回到发起人')
}

async function submitResubmission() {
  const task = details.value
  if (!task || submitting.value) return
  const validation = renderer.value?.validate() || ''
  if (validation) {
    uni.showToast({ title: validation, icon: 'none' })
    return
  }
  if (!await confirmAction('重新提交确认', '确认保存修改并重新进入审批吗？', '重新提交')) return
  await finishAction(
    () => resubmitFormTask(
      task.taskId,
      opinion.value,
      renderer.value?.editableValues() || {},
    ),
    '申请已重新提交',
  )
}

async function submitTransfer() {
  const task = details.value
  const candidate = selectedTransferCandidate.value
  const comment = opinion.value.trim()
  if (!task || submitting.value) return
  if (!candidate) {
    uni.showToast({ title: '请选择转办人员', icon: 'none' })
    return
  }
  if (!comment) {
    uni.showToast({ title: '转办时必须填写原因', icon: 'none' })
    return
  }
  if (!await confirmAction('转办确认', `确认转办给${candidate.displayName}吗？`, '确认转办')) return
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
    <view v-else-if="loading" class="state-card">正在加载审批详情...</view>

    <template v-else-if="details">
      <view v-if="revisionTask" class="revision-notice">
        <strong>申请已被驳回</strong>
        <text>仅可修改当前节点允许编辑的字段，保存后重新进入审批。</text>
      </view>

      <view class="summary-card">
        <view class="summary-header">
          <view>
            <text class="muted">{{ details.businessKey }}</text>
            <view class="title">{{ details.supplier }}采购付款</view>
          </view>
          <wd-tag :type="revisionTask ? 'warning' : 'primary'">{{ taskStage(details) }}</wd-tag>
        </view>
        <view class="summary-grid">
          <view><text>发起人</text><strong>{{ details.initiatorId }}</strong></view>
          <view><text>金额</text><strong>{{ formatMoney(details.amount) }}</strong></view>
          <view><text>采购订单</text><strong>{{ details.purchaseOrderReference }}</strong></view>
          <view><text>表单版本</text><strong>v{{ details.formVersion }}</strong></view>
        </view>
      </view>

      <view v-if="formRuntime" class="form-card">
        <view class="card-title-row">
          <view>
            <view class="section-title">申请表单</view>
            <text class="muted">
              {{ formRuntime.contextKey }} · 修订 {{ formRuntime.revisionNumber }}
            </text>
          </view>
          <wd-tag plain>{{ formRuntime.defaultedUiSchema ? '安全默认' : `UI v${formRuntime.uiSchema.version}` }}</wd-tag>
        </view>
        <ApprovalFormRenderer
          ref="renderer"
          v-model="formValues"
          :field-permissions="formRuntime.fieldPermissions"
          :required-fields="formRuntime.requiredFields"
          :readonly="!revisionTask"
          :schema="formRuntime.definition"
          :ui-schema="formRuntime.uiSchema"
        />
      </view>

      <view class="timeline-card">
        <view class="section-title">审批进度</view>
        <view v-if="timeline?.items.length" class="timeline-list">
          <view v-for="item in timeline.items" :key="item.eventId" class="timeline-item">
            <strong>{{ timelineTitle(item) }}</strong>
            <text>{{ item.operatorId }} · {{ formatDate(item.occurredAt) }}</text>
            <text v-if="item.attributes.comment">{{ item.attributes.comment }}</text>
          </view>
        </view>
        <text v-else class="muted">暂无审批记录</text>
      </view>

      <view v-if="!revisionTask && transferCandidates.length" class="action-card">
        <view class="section-title">转办人员</view>
        <picker
          :range="transferCandidates"
          range-key="displayName"
          :value="pickerIndex"
          @change="selectTransferCandidate"
        >
          <view class="picker-field">
            <text>{{ selectedTransferCandidate?.displayName || '从审批人快照中选择' }}</text>
            <text>›</text>
          </view>
        </picker>
      </view>

      <view class="action-card">
        <view class="section-title">{{ revisionTask ? '修改说明' : '审批意见' }}</view>
        <wd-textarea
          v-model="opinion"
          :maxlength="2000"
          clearable
          :placeholder="revisionTask ? '填写本次修改说明（可选）' : '驳回或转办时必填'"
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
        >重新提交</wd-button>
        <template v-else>
          <wd-button
            v-if="transferCandidates.length"
            plain
            :disabled="!details || loading"
            :loading="submitting"
            @click="submitTransfer"
          >转办</wd-button>
          <wd-button
            type="error"
            plain
            :disabled="!details || loading"
            :loading="submitting"
            @click="submitRejection"
          >驳回</wd-button>
          <wd-button
            type="primary"
            :disabled="!details || loading"
            :loading="submitting"
            @click="submitApproval"
          >同意</wd-button>
        </template>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page{min-height:100vh;padding:24rpx 24rpx 180rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}.summary-card,.form-card,.timeline-card,.action-card,.state-card,.revision-notice{margin-bottom:20rpx;padding:28rpx;border-radius:24rpx;background:var(--wot-color-white,var(--uni-bg-color))}.summary-header,.card-title-row,.action-bar,.action-group,.picker-field{display:flex;align-items:center;justify-content:space-between;gap:20rpx}.title{margin-top:8rpx;color:var(--wot-color-content,var(--uni-text-color));font-size:34rpx;font-weight:700}.muted,.summary-grid text,.timeline-item text,.state-card{color:var(--wot-color-content-secondary,var(--uni-text-color-grey));font-size:24rpx}.summary-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:24rpx;margin-top:28rpx}.summary-grid>view,.timeline-item{display:grid;gap:8rpx}.section-title{margin-bottom:18rpx;color:var(--wot-color-content,var(--uni-text-color));font-size:28rpx;font-weight:700}.card-title-row .section-title{margin-bottom:6rpx}.form-card :deep(.renderer){margin-top:20rpx}.timeline-list{display:grid;gap:18rpx}.timeline-item{padding-bottom:18rpx;border-bottom:1rpx solid var(--wot-color-border-light,var(--uni-border-color))}.picker-field{min-height:84rpx;padding:0 22rpx;border:1rpx solid var(--wot-color-border-light,var(--uni-border-color));border-radius:16rpx}.revision-notice{display:grid;gap:8rpx;color:var(--wot-color-warning,#d97706);background:var(--wot-color-warning-light,#fff7ed)}.state-card{display:grid;justify-items:center;gap:18rpx;padding:60rpx 24rpx}.state-card--error{color:var(--wot-color-danger,var(--uni-color-error))}.action-bar{position:fixed;right:0;bottom:0;left:0;padding:20rpx 24rpx calc(20rpx + env(safe-area-inset-bottom));background:var(--wot-color-white,var(--uni-bg-color));box-shadow:0 -8rpx 24rpx rgb(15 23 42 / 8%)}
</style>
