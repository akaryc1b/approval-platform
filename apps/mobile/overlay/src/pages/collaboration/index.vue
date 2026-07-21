<script lang="ts" setup>
import type { PendingTaskItem } from '@/api/approval'
import type { ApprovalIdentityCandidate } from '@/api/approval/identities'
import type {
  CollaborationMode,
  CollaborationParticipant,
  CollaborationParticipantInput,
  PendingCollaborationTask,
  TaskCollaboration,
} from '@/api/approval/task-collaboration'

import { findPendingTasks } from '@/api/approval'
import { getApprovalRuntimeConfig } from '@/platform/approval/runtime'
import { findApprovalIdentityCandidates } from '@/api/approval/identities'
import {
  addTaskCollaborators,
  createTaskCollaboration,
  decideTaskCollaboration,
  findPendingCollaborationTasks,
  findTaskCollaboration,
  removeTaskCollaborator,
} from '@/api/approval/task-collaboration'

defineOptions({
  name: 'ApprovalTaskCollaboration',
})

definePage({
  style: {
    navigationBarTitleText: '加签协作',
  },
})

type ViewMode = 'mine' | 'pending'

const currentOperatorId = getApprovalRuntimeConfig().operatorId
const loading = ref(false)
const saving = ref(false)
const viewMode = ref<ViewMode>('pending')
const pendingTasks = ref<PendingTaskItem[]>([])
const collaborationTasks = ref<PendingCollaborationTask[]>([])
const selectedTaskId = ref('')
const selectedPolicy = ref<TaskCollaboration>()
const showForm = ref(false)
const mode = ref<CollaborationMode>('ALL')
const approvalThreshold = ref<number>()
const approvalWeightThreshold = ref<number>()
const reason = ref('')
const keyword = ref('')
const candidateLoading = ref(false)
const candidates = ref<ApprovalIdentityCandidate[]>([])
const selectedCandidateKeys = ref<string[]>([])
const candidateWeights = ref<Record<string, number>>({})
const modes: CollaborationMode[] = ['ALL', 'ANY', 'VOTE', 'WEIGHTED']
const modeLabels = ['全部人员通过', '任一人员通过', '达到指定票数', '达到指定权重']

const selectedTask = computed(
  () => pendingTasks.value.find(item => item.taskId === selectedTaskId.value),
)

const selectedCandidates = computed(() => candidates.value.filter(
  candidate => selectedCandidateKeys.value.includes(candidateKey(candidate)),
))

const decisionsStarted = computed(() => Boolean(selectedPolicy.value?.participants.some(
  item => item.status === 'APPROVED' || item.status === 'REJECTED',
)))

const canChangeParticipants = computed(() => selectedPolicy.value?.status === 'ACTIVE'
  && !decisionsStarted.value)

const currentMode = computed(() => selectedPolicy.value && showForm.value
  ? selectedPolicy.value.mode
  : mode.value)

const selectedWeight = computed(() => selectedCandidates.value.reduce(
  (total, candidate) => total + participantWeight(candidate),
  0,
))

const existingEligibleCount = computed(() => selectedPolicy.value?.participants.filter(
  item => item.status !== 'REMOVED',
).length || 0)

const existingTotalWeight = computed(() => selectedPolicy.value?.participants
  .filter(item => item.status !== 'REMOVED')
  .reduce((total, item) => total + item.weight, 0) || 0)

const previewParticipantCount = computed(() => (
  selectedPolicy.value && showForm.value ? existingEligibleCount.value : 0
) + selectedCandidates.value.length)

const previewTotalWeight = computed(() => (
  selectedPolicy.value && showForm.value ? existingTotalWeight.value : 0
) + selectedWeight.value)

const previewApprovalThreshold = computed(() => selectedPolicy.value && showForm.value
  ? selectedPolicy.value.approvalThreshold
  : approvalThreshold.value)

const previewApprovalWeightThreshold = computed(() => selectedPolicy.value && showForm.value
  ? selectedPolicy.value.approvalWeightThreshold
  : approvalWeightThreshold.value)

const ruleSummary = computed(() => {
  if (currentMode.value === 'ALL') {
    return `${previewParticipantCount.value} 人全部同意后满足协作要求`
  }
  if (currentMode.value === 'ANY') {
    return '任一参与人同意即可满足；全部拒绝后协作失败'
  }
  if (currentMode.value === 'VOTE') {
    return `同意票达到 ${previewApprovalThreshold.value || 0} / ${previewParticipantCount.value}`
  }
  return `同意权重达到 ${previewApprovalWeightThreshold.value || 0} / ${previewTotalWeight.value}`
})

function candidateKey(candidate: ApprovalIdentityCandidate) {
  const { source, objectType, value } = candidate.reference
  return `${source}\u001f${objectType}\u001f${value}`
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

function modeLabel(value: CollaborationMode) {
  return {
    ALL: '全部通过',
    ANY: '任一通过',
    VOTE: '投票通过',
    WEIGHTED: '加权通过',
  }[value]
}

function policyStatusLabel(status: TaskCollaboration['status']) {
  return {
    ACTIVE: '协作中',
    CANCELED: '已取消',
    REJECTED: '未达到要求',
    SATISFIED: '已满足',
  }[status]
}

function participantStatusLabel(status: CollaborationParticipant['status']) {
  return {
    APPROVED: '已同意',
    CANCELED: '已取消',
    PENDING: '待处理',
    REJECTED: '已拒绝',
    REMOVED: '已减签',
  }[status]
}

function participantTagType(status: CollaborationParticipant['status']) {
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  if (status === 'PENDING') return 'warning'
  return 'info'
}

function eventValue(event: unknown) {
  return (event as { detail?: { value?: unknown } })?.detail?.value
}

function selectView(value: ViewMode) {
  viewMode.value = value
}

function selectMode(event: unknown) {
  const index = Number(eventValue(event))
  mode.value = modes[index] || 'ALL'
  approvalThreshold.value = mode.value === 'VOTE' ? 1 : undefined
  approvalWeightThreshold.value = mode.value === 'WEIGHTED' ? 1 : undefined
}

function selectCandidates(event: unknown) {
  const value = eventValue(event)
  selectedCandidateKeys.value = Array.isArray(value) ? value.map(String) : []
}

function participantWeight(candidate: ApprovalIdentityCandidate) {
  if (currentMode.value !== 'WEIGHTED') return 1
  return candidateWeights.value[candidateKey(candidate)] || 1
}

function setParticipantWeight(candidate: ApprovalIdentityCandidate, event: unknown) {
  const value = Math.max(1, Number(eventValue(event)) || 1)
  candidateWeights.value = {
    ...candidateWeights.value,
    [candidateKey(candidate)]: value,
  }
}

function setApprovalThreshold(event: unknown) {
  approvalThreshold.value = Math.max(1, Number(eventValue(event)) || 1)
}

function setApprovalWeightThreshold(event: unknown) {
  approvalWeightThreshold.value = Math.max(1, Number(eventValue(event)) || 1)
}

function resetForm() {
  mode.value = 'ALL'
  approvalThreshold.value = undefined
  approvalWeightThreshold.value = undefined
  reason.value = ''
  keyword.value = ''
  selectedCandidateKeys.value = []
  candidateWeights.value = {}
  showForm.value = false
}

async function loadData() {
  loading.value = true
  try {
    const [pending, collaboration] = await Promise.all([
      findPendingTasks({ limit: 100, offset: 0 }),
      findPendingCollaborationTasks(100),
    ])
    pendingTasks.value = pending.items.filter(
      item => item.taskDefinitionKey !== 'initiatorRevision',
    )
    collaborationTasks.value = collaboration
    if (selectedTaskId.value) await loadPolicy(selectedTaskId.value, false)
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    loading.value = false
  }
}

async function loadPolicy(taskId: string, clearForm = true) {
  selectedTaskId.value = taskId
  selectedPolicy.value = await findTaskCollaboration(taskId)
  if (clearForm) resetForm()
}

async function openCreate() {
  resetForm()
  await searchCandidates()
  showForm.value = true
}

async function openAddParticipants() {
  if (!canChangeParticipants.value) return
  reason.value = ''
  keyword.value = ''
  selectedCandidateKeys.value = []
  candidateWeights.value = {}
  await searchCandidates()
  showForm.value = true
}

async function searchCandidates() {
  candidateLoading.value = true
  try {
    const result = await findApprovalIdentityCandidates(keyword.value, 50, true)
    const ownerId = selectedPolicy.value?.ownerAssigneeId || currentOperatorId
    const existingUsers = new Set(
      selectedPolicy.value?.participants.map(item => item.participantUserId) || [],
    )
    candidates.value = result.filter(candidate =>
      candidate.userId !== ownerId && !existingUsers.has(candidate.userId),
    )
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    candidateLoading.value = false
  }
}

function participantInputs(): CollaborationParticipantInput[] {
  return selectedCandidates.value.map(candidate => ({
    ...candidate.reference,
    weight: participantWeight(candidate),
  }))
}

function validateForm() {
  if (selectedCandidates.value.length === 0) {
    uni.showToast({ title: '请选择至少一名协作人员', icon: 'none' })
    return false
  }
  if (!reason.value.trim()) {
    uni.showToast({ title: selectedPolicy.value ? '请输入追加原因' : '请输入协作原因', icon: 'none' })
    return false
  }
  if (currentMode.value === 'VOTE') {
    const threshold = previewApprovalThreshold.value || 0
    if (threshold < 1 || threshold > previewParticipantCount.value) {
      uni.showToast({ title: '投票阈值超出参与人数', icon: 'none' })
      return false
    }
  }
  if (currentMode.value === 'WEIGHTED') {
    const threshold = previewApprovalWeightThreshold.value || 0
    if (threshold < 1 || threshold > previewTotalWeight.value) {
      uni.showToast({ title: '加权阈值超出总权重', icon: 'none' })
      return false
    }
  }
  return true
}

async function submitParticipants() {
  const task = selectedTask.value
  if (!task || !validateForm()) return
  saving.value = true
  try {
    if (selectedPolicy.value) {
      selectedPolicy.value = await addTaskCollaborators(
        task.taskId,
        participantInputs(),
        reason.value,
      )
      uni.showToast({ title: '参与人已追加', icon: 'success' })
    }
    else {
      selectedPolicy.value = await createTaskCollaboration(
        task.taskId,
        mode.value,
        participantInputs(),
        reason.value,
        mode.value === 'VOTE' ? approvalThreshold.value : undefined,
        mode.value === 'WEIGHTED' ? approvalWeightThreshold.value : undefined,
      )
      uni.showToast({ title: '协作已发起', icon: 'success' })
    }
    showForm.value = false
    await loadData()
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    saving.value = false
  }
}

function promptText(title: string, content: string, placeholder: string) {
  return new Promise<string | undefined>((resolve) => {
    uni.showModal({
      title,
      content,
      editable: true,
      placeholderText: placeholder,
      success: result => resolve(result.confirm ? result.content?.trim() : undefined),
      fail: () => resolve(undefined),
    })
  })
}

async function removeParticipant(participant: CollaborationParticipant) {
  const removalReason = await promptText(
    '减签确认',
    `确认移除 ${participant.participantUserId} 吗？`,
    '请输入减签原因',
  )
  if (!removalReason) return
  saving.value = true
  try {
    selectedPolicy.value = await removeTaskCollaborator(
      participant.participantId,
      removalReason,
    )
    uni.showToast({ title: '已完成减签', icon: 'success' })
    await loadData()
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    saving.value = false
  }
}

async function decide(item: PendingCollaborationTask, decision: 'APPROVED' | 'REJECTED') {
  const comment = await promptText(
    decision === 'APPROVED' ? '协作同意' : '协作拒绝',
    decision === 'APPROVED' ? '确认同意该协作事项吗？' : '确认拒绝该协作事项吗？',
    '请输入协作意见',
  )
  if (!comment) return
  saving.value = true
  try {
    await decideTaskCollaboration(item.participantId, decision, comment)
    uni.showToast({
      title: decision === 'APPROVED' ? '已同意' : '已拒绝',
      icon: 'success',
    })
    await loadData()
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    saving.value = false
  }
}

onShow(loadData)
</script>

<template>
  <view class="page">
    <view class="notice-card">
      <text class="notice-title">协作不改变原任务责任人</text>
      <text>协作进行中时原任务不能同意、转办或拿回；未达到要求后原审批人只能驳回。</text>
    </view>

    <view class="mode-tabs">
      <view :class="['mode-tab', { active: viewMode === 'pending' }]" @click="selectView('pending')">
        待我协作 {{ collaborationTasks.length }}
      </view>
      <view :class="['mode-tab', { active: viewMode === 'mine' }]" @click="selectView('mine')">
        我的待办协作
      </view>
    </view>

    <view v-if="loading" class="state-card">正在加载协作事项...</view>

    <template v-else-if="viewMode === 'pending'">
      <view v-if="collaborationTasks.length === 0" class="state-card">暂无待处理的协作事项</view>
      <view v-else class="card-list">
        <view v-for="item in collaborationTasks" :key="item.participantId" class="item-card">
          <view class="item-header">
            <view>
              <text class="item-title">{{ item.taskName }}</text>
              <text class="item-subtitle">原审批人：{{ item.ownerAssigneeId }}</text>
            </view>
            <wd-tag plain type="primary">{{ modeLabel(item.mode) }}</wd-tag>
          </view>
          <view class="metric-row">
            <text v-if="item.mode === 'VOTE'">阈值 {{ item.approvalThreshold }} 票</text>
            <text v-if="item.mode === 'WEIGHTED'">阈值 {{ item.approvalWeightThreshold }}</text>
            <text>我的权重 {{ item.participantWeight }}</text>
            <text>{{ item.progress.approvedCount }} 同意 / {{ item.progress.pendingCount }} 待处理</text>
          </view>
          <text class="item-reason">{{ item.reason }}</text>
          <view class="action-row">
            <wd-button plain size="small" type="error" :loading="saving" @click="decide(item, 'REJECTED')">
              拒绝
            </wd-button>
            <wd-button size="small" type="primary" :loading="saving" @click="decide(item, 'APPROVED')">
              同意
            </wd-button>
          </view>
        </view>
      </view>
    </template>

    <template v-else>
      <view v-if="pendingTasks.length === 0" class="state-card">暂无可发起协作的待办</view>
      <view v-else class="task-strip">
        <view
          v-for="task in pendingTasks"
          :key="task.taskId"
          :class="['task-chip', { active: selectedTaskId === task.taskId }]"
          @click="loadPolicy(task.taskId)"
        >
          <text>{{ task.taskName }}</text>
          <text>{{ task.supplier }}</text>
        </view>
      </view>

      <view v-if="selectedTask" class="policy-card">
        <view class="item-header">
          <view>
            <text class="item-title">{{ selectedTask.taskName }}</text>
            <text class="item-subtitle">{{ selectedTask.businessKey }}</text>
          </view>
          <wd-button v-if="!selectedPolicy" size="small" type="primary" @click="openCreate">
            发起协作
          </wd-button>
          <wd-button
            v-else-if="canChangeParticipants"
            size="small"
            plain
            type="primary"
            @click="openAddParticipants"
          >
            追加人员
          </wd-button>
        </view>

        <template v-if="selectedPolicy">
          <view class="policy-summary">
            <text>{{ modeLabel(selectedPolicy.mode) }}</text>
            <wd-tag plain type="primary">{{ policyStatusLabel(selectedPolicy.status) }}</wd-tag>
          </view>
          <view class="progress-grid">
            <view>
              <text class="metric-value">{{ selectedPolicy.progress.approvedCount }}</text>
              <text class="item-subtitle">同意人数 / {{ selectedPolicy.progress.eligibleParticipantCount }}</text>
            </view>
            <view>
              <text class="metric-value">{{ selectedPolicy.progress.approvedWeight }}</text>
              <text class="item-subtitle">同意权重 / {{ selectedPolicy.progress.totalWeight }}</text>
            </view>
            <view>
              <text class="metric-value">{{ selectedPolicy.progress.maximumReachableApprovalCount }}</text>
              <text class="item-subtitle">可达票数</text>
            </view>
            <view>
              <text class="metric-value">{{ selectedPolicy.progress.maximumReachableApprovalWeight }}</text>
              <text class="item-subtitle">可达权重</text>
            </view>
          </view>
          <text class="item-reason">{{ selectedPolicy.reason }}</text>
          <view class="participant-list">
            <view v-for="participant in selectedPolicy.participants" :key="participant.participantId" class="participant-row">
              <view>
                <text>{{ participant.participantUserId }}</text>
                <text class="item-subtitle">
                  权重 {{ participant.weight }} · {{ participant.identity.source }} / {{ participant.identity.objectType }}
                </text>
                <text v-if="participant.decisionComment" class="item-subtitle">
                  {{ participant.decisionComment }}
                </text>
              </view>
              <view class="participant-actions">
                <wd-tag :type="participantTagType(participant.status)" plain>
                  {{ participantStatusLabel(participant.status) }}
                </wd-tag>
                <wd-button
                  v-if="canChangeParticipants && participant.status === 'PENDING'"
                  plain
                  size="small"
                  type="error"
                  :loading="saving"
                  @click="removeParticipant(participant)"
                >
                  减签
                </wd-button>
              </view>
            </view>
          </view>
        </template>
      </view>
    </template>

    <view v-if="showForm" class="mask" @click.self="showForm = false">
      <view class="form-panel">
        <text class="form-title">{{ selectedPolicy ? '追加协作人员' : '发起协作' }}</text>
        <view v-if="!selectedPolicy" class="field">
          <text class="field-label">协作规则</text>
          <picker :range="modeLabels" :value="modes.indexOf(mode)" @change="selectMode">
            <view class="field-input">{{ modeLabels[modes.indexOf(mode)] }}</view>
          </picker>
        </view>
        <view class="field">
          <text class="field-label">搜索人员</text>
          <view class="search-row">
            <input v-model="keyword" class="field-input" placeholder="姓名、账号、邮箱或手机号">
            <wd-button size="small" :loading="candidateLoading" @click="searchCandidates">搜索</wd-button>
          </view>
        </view>
        <checkbox-group class="candidate-list" @change="selectCandidates">
          <label v-for="candidate in candidates" :key="candidateKey(candidate)" class="candidate-row">
            <checkbox :value="candidateKey(candidate)" :checked="selectedCandidateKeys.includes(candidateKey(candidate))" />
            <view>
              <text>{{ candidate.displayName }}</text>
              <text class="item-subtitle">{{ candidate.username }}</text>
            </view>
          </label>
        </checkbox-group>
        <view v-if="currentMode === 'WEIGHTED'" class="weight-list">
          <view v-for="candidate in selectedCandidates" :key="candidateKey(candidate)" class="weight-row">
            <text>{{ candidate.displayName }}</text>
            <input
              class="weight-input"
              type="number"
              :value="String(participantWeight(candidate))"
              @input="setParticipantWeight(candidate, $event)"
            >
          </view>
        </view>
        <view v-if="!selectedPolicy && mode === 'VOTE'" class="field">
          <text class="field-label">通过票数</text>
          <input
            class="field-input"
            type="number"
            :value="approvalThreshold ? String(approvalThreshold) : ''"
            @input="setApprovalThreshold"
          >
        </view>
        <view v-if="!selectedPolicy && mode === 'WEIGHTED'" class="field">
          <text class="field-label">通过权重</text>
          <input
            class="field-input"
            type="number"
            :value="approvalWeightThreshold ? String(approvalWeightThreshold) : ''"
            @input="setApprovalWeightThreshold"
          >
        </view>
        <view class="rule-preview">
          <text>参与人数 {{ previewParticipantCount }}</text>
          <text>总权重 {{ previewTotalWeight }}</text>
          <text>{{ ruleSummary }}</text>
        </view>
        <view class="field">
          <text class="field-label">{{ selectedPolicy ? '追加原因' : '协作原因' }}</text>
          <textarea
            v-model="reason"
            class="field-textarea"
            :maxlength="2000"
            :placeholder="selectedPolicy ? '说明追加参与人的原因' : '说明需要补充评审的原因'"
          />
        </view>
        <view class="action-row">
          <wd-button plain @click="showForm = false">取消</wd-button>
          <wd-button type="primary" :loading="saving" @click="submitParticipants">
            {{ selectedPolicy ? '确认追加' : '确认发起' }}
          </wd-button>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page{min-height:100vh;padding:24rpx 24rpx 120rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}.notice-card,.state-card,.item-card,.policy-card,.form-panel{padding:28rpx;border-radius:24rpx;background:var(--wot-color-white,var(--uni-bg-color));box-shadow:0 8rpx 24rpx rgb(15 23 42 / 5%)}.notice-card{display:grid;gap:10rpx}.notice-title,.item-title,.form-title{font-size:30rpx;font-weight:700}.mode-tabs{display:grid;grid-template-columns:1fr 1fr;gap:12rpx;margin:24rpx 0}.mode-tab{padding:22rpx;text-align:center;border-radius:18rpx;background:var(--wot-color-white,var(--uni-bg-color))}.mode-tab.active{color:var(--wot-color-theme,var(--uni-color-primary));font-weight:700}.card-list,.participant-list,.candidate-list,.weight-list{display:grid;gap:20rpx}.item-card,.policy-card{margin-bottom:20rpx}.item-header,.action-row,.policy-summary,.participant-row,.participant-actions,.search-row,.candidate-row,.metric-row,.weight-row{display:flex;align-items:center;justify-content:space-between;gap:18rpx}.item-header>view:first-child,.participant-row>view:first-child,.candidate-row>view{display:grid;flex:1;gap:8rpx}.item-subtitle,.item-reason,.metric-row{color:var(--wot-color-content-secondary,var(--uni-text-color-grey));font-size:24rpx}.item-reason{display:block;margin:20rpx 0}.task-strip{display:flex;gap:16rpx;margin-bottom:20rpx;overflow-x:auto}.task-chip{display:grid;flex:0 0 260rpx;gap:8rpx;padding:20rpx;border:1rpx solid var(--wot-color-border-light,var(--uni-border-color));border-radius:18rpx;background:var(--wot-color-white,var(--uni-bg-color))}.task-chip.active{border-color:var(--wot-color-theme,var(--uni-color-primary))}.participant-row{padding:18rpx 0;border-bottom:1rpx solid var(--wot-color-border-light,var(--uni-border-color))}.progress-grid{display:grid;grid-template-columns:1fr 1fr;gap:16rpx;margin:20rpx 0}.progress-grid>view{display:grid;gap:6rpx;padding:18rpx;border-radius:16rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}.metric-value{font-size:34rpx;font-weight:700}.mask{position:fixed;z-index:1000;inset:0;display:flex;align-items:flex-end;background:rgb(15 23 42 / 45%)}.form-panel{width:100%;max-height:88vh;overflow-y:auto;border-radius:28rpx 28rpx 0 0}.field{display:grid;gap:12rpx;margin-top:24rpx}.field-label{font-weight:600}.field-input,.field-textarea,.weight-input{box-sizing:border-box;width:100%;padding:18rpx;border:1rpx solid var(--wot-color-border-light,var(--uni-border-color));border-radius:14rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}.field-textarea{min-height:180rpx}.search-row .field-input{flex:1}.candidate-list{margin-top:18rpx}.candidate-row{justify-content:flex-start;padding:16rpx 0;border-bottom:1rpx solid var(--wot-color-border-light,var(--uni-border-color))}.weight-row{padding:16rpx;border-radius:14rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}.weight-input{width:180rpx}.rule-preview{display:grid;gap:8rpx;margin-top:20rpx;padding:18rpx;border-radius:16rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey));font-size:24rpx}.action-row{justify-content:flex-end;margin-top:24rpx}
</style>
