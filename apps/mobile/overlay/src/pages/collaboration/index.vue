<script lang="ts" setup>
import type { PendingTaskItem } from '@/api/approval'
import type { ApprovalIdentityCandidate } from '@/api/approval/identities'
import type {
  CollaborationMode,
  CollaborationParticipant,
  PendingCollaborationTask,
  TaskCollaboration,
} from '@/api/approval/task-collaboration'

import { findPendingTasks } from '@/api/approval'
import { findApprovalIdentityCandidates } from '@/api/approval/identities'
import {
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

const loading = ref(false)
const saving = ref(false)
const viewMode = ref<ViewMode>('pending')
const pendingTasks = ref<PendingTaskItem[]>([])
const collaborationTasks = ref<PendingCollaborationTask[]>([])
const selectedTaskId = ref('')
const selectedPolicy = ref<TaskCollaboration>()
const showCreate = ref(false)
const mode = ref<CollaborationMode>('ALL')
const reason = ref('')
const keyword = ref('')
const candidateLoading = ref(false)
const candidates = ref<ApprovalIdentityCandidate[]>([])
const selectedCandidateKeys = ref<string[]>([])
const modeLabels = ['全部人员通过', '任一人员通过']

const selectedTask = computed(
  () => pendingTasks.value.find(item => item.taskId === selectedTaskId.value),
)

function candidateKey(candidate: ApprovalIdentityCandidate) {
  const { source, objectType, value } = candidate.reference
  return `${source}\u001f${objectType}\u001f${value}`
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

function modeLabel(value: CollaborationMode) {
  return value === 'ALL' ? '全部通过' : '任一通过'
}

function policyStatusLabel(status: TaskCollaboration['status']) {
  return {
    ACTIVE: '协作中',
    CANCELED: '已取消',
    REJECTED: '已拒绝',
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
  mode.value = Number(eventValue(event)) === 1 ? 'ANY' : 'ALL'
}

function selectCandidates(event: unknown) {
  const value = eventValue(event)
  selectedCandidateKeys.value = Array.isArray(value) ? value.map(String) : []
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
    if (selectedTaskId.value) await loadPolicy(selectedTaskId.value)
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    loading.value = false
  }
}

async function loadPolicy(taskId: string) {
  selectedTaskId.value = taskId
  selectedPolicy.value = await findTaskCollaboration(taskId)
  showCreate.value = false
}

async function openCreate() {
  mode.value = 'ALL'
  reason.value = ''
  keyword.value = ''
  selectedCandidateKeys.value = []
  await searchCandidates()
  showCreate.value = true
}

async function searchCandidates() {
  candidateLoading.value = true
  try {
    candidates.value = await findApprovalIdentityCandidates(keyword.value, 50, true)
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    candidateLoading.value = false
  }
}

async function createPolicy() {
  const task = selectedTask.value
  if (!task) return
  const selected = candidates.value.filter(
    candidate => selectedCandidateKeys.value.includes(candidateKey(candidate)),
  )
  if (selected.length === 0) {
    uni.showToast({ title: '请选择至少一名加签人员', icon: 'none' })
    return
  }
  if (!reason.value.trim()) {
    uni.showToast({ title: '请输入加签原因', icon: 'none' })
    return
  }
  saving.value = true
  try {
    selectedPolicy.value = await createTaskCollaboration(
      task.taskId,
      mode.value,
      selected.map(item => item.reference),
      reason.value,
    )
    showCreate.value = false
    uni.showToast({ title: '加签已发起', icon: 'success' })
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
    decision === 'APPROVED' ? '确认同意该加签事项吗？' : '确认拒绝该加签事项吗？',
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
      <text class="notice-title">加签不会改变原任务责任人</text>
      <text>协作未完成时，原任务不能同意、转办或拿回；参与人拒绝后原审批人只能驳回。</text>
    </view>

    <view class="mode-tabs">
      <view :class="['mode-tab', { active: viewMode === 'pending' }]" @click="selectView('pending')">
        待我协作 {{ collaborationTasks.length }}
      </view>
      <view :class="['mode-tab', { active: viewMode === 'mine' }]" @click="selectView('mine')">
        我的待办加签
      </view>
    </view>

    <view v-if="loading" class="state-card">正在加载协作事项...</view>

    <template v-else-if="viewMode === 'pending'">
      <view v-if="collaborationTasks.length === 0" class="state-card">暂无待处理的加签事项</view>
      <view v-else class="card-list">
        <view v-for="item in collaborationTasks" :key="item.participantId" class="item-card">
          <view class="item-header">
            <view>
              <text class="item-title">{{ item.taskName }}</text>
              <text class="item-subtitle">原审批人：{{ item.ownerAssigneeId }}</text>
            </view>
            <wd-tag plain type="primary">{{ modeLabel(item.mode) }}</wd-tag>
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
      <view v-if="pendingTasks.length === 0" class="state-card">暂无可发起加签的待办</view>
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
            发起加签
          </wd-button>
        </view>

        <template v-if="selectedPolicy">
          <view class="policy-summary">
            <text>{{ modeLabel(selectedPolicy.mode) }}</text>
            <wd-tag plain type="primary">{{ policyStatusLabel(selectedPolicy.status) }}</wd-tag>
          </view>
          <text class="item-reason">{{ selectedPolicy.reason }}</text>
          <view class="participant-list">
            <view v-for="participant in selectedPolicy.participants" :key="participant.participantId" class="participant-row">
              <view>
                <text>{{ participant.participantUserId }}</text>
                <text class="item-subtitle">{{ participant.identity.source }} / {{ participant.identity.objectType }}</text>
              </view>
              <view class="participant-actions">
                <wd-tag :type="participantTagType(participant.status)" plain>
                  {{ participantStatusLabel(participant.status) }}
                </wd-tag>
                <wd-button
                  v-if="selectedPolicy.status === 'ACTIVE' && participant.status === 'PENDING'"
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

    <view v-if="showCreate" class="mask" @click.self="showCreate = false">
      <view class="form-panel">
        <text class="form-title">发起加签</text>
        <view class="field">
          <text class="field-label">协作规则</text>
          <picker :range="modeLabels" :value="mode === 'ALL' ? 0 : 1" @change="selectMode">
            <view class="field-input">{{ modeLabels[mode === 'ALL' ? 0 : 1] }}</view>
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
        <view class="field">
          <text class="field-label">加签原因</text>
          <textarea v-model="reason" class="field-textarea" :maxlength="2000" placeholder="说明需要补充评审的原因" />
        </view>
        <view class="action-row">
          <wd-button plain @click="showCreate = false">取消</wd-button>
          <wd-button type="primary" :loading="saving" @click="createPolicy">确认发起</wd-button>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page{min-height:100vh;padding:24rpx 24rpx 120rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}.notice-card,.state-card,.item-card,.policy-card,.form-panel{padding:28rpx;border-radius:24rpx;background:var(--wot-color-white,var(--uni-bg-color));box-shadow:0 8rpx 24rpx rgb(15 23 42 / 5%)}.notice-card{display:grid;gap:10rpx}.notice-title,.item-title,.form-title{font-size:30rpx;font-weight:700}.mode-tabs{display:grid;grid-template-columns:1fr 1fr;gap:12rpx;margin:24rpx 0}.mode-tab{padding:22rpx;text-align:center;border-radius:18rpx;background:var(--wot-color-white,var(--uni-bg-color))}.mode-tab.active{color:var(--wot-color-theme,var(--uni-color-primary));font-weight:700}.card-list,.participant-list,.candidate-list{display:grid;gap:20rpx}.item-card,.policy-card{margin-bottom:20rpx}.item-header,.action-row,.policy-summary,.participant-row,.participant-actions,.search-row,.candidate-row{display:flex;align-items:center;justify-content:space-between;gap:18rpx}.item-header>view:first-child,.participant-row>view:first-child,.candidate-row>view{display:grid;flex:1;gap:8rpx}.item-subtitle,.item-reason{color:var(--wot-color-content-secondary,var(--uni-text-color-grey));font-size:24rpx}.item-reason{display:block;margin:20rpx 0}.task-strip{display:flex;gap:16rpx;margin-bottom:20rpx;overflow-x:auto}.task-chip{display:grid;flex:0 0 260rpx;gap:8rpx;padding:20rpx;border:1rpx solid var(--wot-color-border-light,var(--uni-border-color));border-radius:18rpx;background:var(--wot-color-white,var(--uni-bg-color))}.task-chip.active{border-color:var(--wot-color-theme,var(--uni-color-primary))}.participant-row{padding:18rpx 0;border-bottom:1rpx solid var(--wot-color-border-light,var(--uni-border-color))}.mask{position:fixed;z-index:1000;inset:0;display:flex;align-items:flex-end;background:rgb(15 23 42 / 45%)}.form-panel{width:100%;max-height:88vh;overflow-y:auto;border-radius:28rpx 28rpx 0 0}.field{display:grid;gap:12rpx;margin-top:24rpx}.field-label{font-weight:600}.field-input,.field-textarea{box-sizing:border-box;width:100%;padding:18rpx;border:1rpx solid var(--wot-color-border-light,var(--uni-border-color));border-radius:14rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}.field-textarea{min-height:180rpx}.search-row .field-input{flex:1}.candidate-list{margin-top:18rpx}.candidate-row{justify-content:flex-start;padding:16rpx 0;border-bottom:1rpx solid var(--wot-color-border-light,var(--uni-border-color))}.action-row{justify-content:flex-end;margin-top:24rpx}
</style>
