<script lang="ts" setup>
import type {
  CreateDelegationPayload,
  DelegationRule,
  DelegationScope,
} from '@/api/approval/delegations'

import {
  createDelegationRule,
  findDelegationRules,
  revokeDelegationRule,
} from '@/api/approval/delegations'

defineOptions({
  name: 'ApprovalDelegationRules',
})

definePage({
  style: {
    navigationBarTitleText: '代理规则',
  },
})

interface DelegationForm {
  definitionKey: string
  delegateId: string
  endDate: string
  endTime: string
  reason: string
  scope: DelegationScope
  startDate: string
  startTime: string
}

const loading = ref(false)
const submitting = ref(false)
const includeRevoked = ref(false)
const rules = ref<DelegationRule[]>([])
const showCreate = ref(false)
const scopeLabels = ['全部审批', '指定流程']
const form = reactive<DelegationForm>(emptyForm())

function pad(value: number) {
  return String(value).padStart(2, '0')
}

function dateValue(value: Date) {
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`
}

function timeValue(value: Date) {
  return `${pad(value.getHours())}:${pad(value.getMinutes())}`
}

function emptyForm(): DelegationForm {
  const start = new Date()
  const end = new Date(start.getTime() + 8 * 60 * 60 * 1000)
  return {
    definitionKey: 'purchase-payment',
    delegateId: '',
    endDate: dateValue(end),
    endTime: timeValue(end),
    reason: '',
    scope: 'ALL',
    startDate: dateValue(start),
    startTime: timeValue(start),
  }
}

function resetForm() {
  Object.assign(form, emptyForm())
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

function formatDate(value?: string) {
  if (!value) return '-'
  const date = new Date(value)
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function scopeLabel(scope: DelegationScope) {
  return scope === 'ALL' ? '全部审批' : '指定流程'
}

function scopeIndex() {
  return form.scope === 'ALL' ? 0 : 1
}

function statusLabel(rule: DelegationRule) {
  if (rule.status === 'REVOKED') return '已撤销'
  if (new Date(rule.validUntil).getTime() <= Date.now()) return '已到期'
  if (new Date(rule.validFrom).getTime() > Date.now()) return '待生效'
  return '生效中'
}

function statusType(rule: DelegationRule) {
  const status = statusLabel(rule)
  if (status === '生效中') return 'success'
  if (status === '待生效') return 'warning'
  if (status === '已撤销') return 'danger'
  return 'info'
}

function eventValue(event: unknown) {
  return String((event as { detail?: { value?: unknown } })?.detail?.value ?? '')
}

function selectScope(event: unknown) {
  form.scope = Number(eventValue(event)) === 1 ? 'DEFINITION' : 'ALL'
}

function combineDateTime(date: string, time: string) {
  const value = new Date(`${date}T${time}:00`)
  if (Number.isNaN(value.getTime())) throw new Error('代理时间格式不正确')
  return value
}

function validatePayload(): CreateDelegationPayload {
  const delegateId = form.delegateId.trim()
  const reason = form.reason.trim()
  if (!delegateId) throw new Error('请输入代理人用户 ID')
  if (!reason) throw new Error('请输入代理原因')
  const start = combineDateTime(form.startDate, form.startTime)
  const end = combineDateTime(form.endDate, form.endTime)
  if (end.getTime() <= start.getTime()) throw new Error('结束时间必须晚于开始时间')
  const payload: CreateDelegationPayload = {
    delegateId,
    reason,
    scope: form.scope,
    validFrom: start.toISOString(),
    validUntil: end.toISOString(),
  }
  if (form.scope === 'DEFINITION') {
    const definitionKey = form.definitionKey.trim()
    if (!definitionKey) throw new Error('请输入流程标识')
    payload.definitionKey = definitionKey
  }
  return payload
}

async function loadRules() {
  loading.value = true
  try {
    rules.value = await findDelegationRules(includeRevoked.value)
  }
  catch (error) {
    rules.value = []
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    loading.value = false
  }
}

function openCreate() {
  resetForm()
  showCreate.value = true
}

async function submitCreate() {
  let payload: CreateDelegationPayload
  try {
    payload = validatePayload()
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
    return
  }
  submitting.value = true
  try {
    await createDelegationRule(payload)
    uni.showToast({ title: '代理规则已创建', icon: 'success' })
    showCreate.value = false
    await loadRules()
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    submitting.value = false
  }
}

function requestRevokeReason(rule: DelegationRule) {
  return new Promise<string | undefined>((resolve) => {
    uni.showModal({
      title: '撤销代理规则',
      content: `撤销后，新任务将不再自动分派给 ${rule.delegateId}。`,
      editable: true,
      placeholderText: '请输入撤销原因',
      confirmText: '确认撤销',
      success: result => resolve(result.confirm ? result.content?.trim() : undefined),
      fail: () => resolve(undefined),
    })
  })
}

async function revoke(rule: DelegationRule) {
  const reason = await requestRevokeReason(rule)
  if (!reason) {
    if (reason === '') uni.showToast({ title: '撤销原因不能为空', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    await revokeDelegationRule(rule.ruleId, reason)
    uni.showToast({ title: '代理规则已撤销', icon: 'success' })
    await loadRules()
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    submitting.value = false
  }
}

onShow(loadRules)
watch(includeRevoked, loadRules)
</script>

<template>
  <view class="page">
    <view class="notice-card">
      <text class="notice-title">代理仅影响新产生的审批任务</text>
      <text>指定流程规则优先于全部审批规则，原责任人和代理依据会被永久记录。</text>
    </view>

    <view class="toolbar-card">
      <view>
        <text class="toolbar-title">我的代理规则</text>
        <text class="toolbar-subtitle">相同作用域的有效时间段不能重叠</text>
      </view>
      <view class="toolbar-actions">
        <view class="switch-row">
          <text>显示已撤销</text>
          <wd-switch v-model="includeRevoked" size="20" />
        </view>
        <wd-button size="small" type="primary" @click="openCreate">新建代理</wd-button>
      </view>
    </view>

    <view v-if="loading" class="state-card">正在加载代理规则...</view>
    <view v-else-if="rules.length === 0" class="state-card">暂无代理规则</view>
    <view v-else class="rule-list">
      <view v-for="rule in rules" :key="rule.ruleId" class="rule-card">
        <view class="rule-header">
          <view>
            <text class="rule-delegate">{{ rule.delegateId }}</text>
            <text class="rule-scope">
              {{ scopeLabel(rule.scope) }}
              <template v-if="rule.definitionKey"> · {{ rule.definitionKey }}</template>
            </text>
          </view>
          <wd-tag :type="statusType(rule)" plain>{{ statusLabel(rule) }}</wd-tag>
        </view>
        <view class="rule-row">
          <text>有效期</text>
          <text>{{ formatDate(rule.validFrom) }} 至 {{ formatDate(rule.validUntil) }}</text>
        </view>
        <view class="rule-row">
          <text>代理原因</text>
          <text>{{ rule.reason }}</text>
        </view>
        <view v-if="rule.revokeReason" class="rule-row">
          <text>撤销原因</text>
          <text>{{ rule.revokeReason }}</text>
        </view>
        <wd-button
          v-if="rule.status === 'ACTIVE'"
          block
          plain
          size="small"
          type="error"
          :loading="submitting"
          @click="revoke(rule)"
        >
          撤销代理
        </wd-button>
      </view>
    </view>

    <view v-if="showCreate" class="mask" @click.self="showCreate = false">
      <view class="form-panel">
        <view class="form-title">新建代理规则</view>
        <view class="field">
          <text class="field-label">代理人用户 ID</text>
          <input v-model="form.delegateId" class="field-input" :maxlength="256" placeholder="例如 user-1002">
        </view>
        <view class="field">
          <text class="field-label">代理范围</text>
          <picker :range="scopeLabels" :value="scopeIndex()" @change="selectScope">
            <view class="picker-value">{{ scopeLabels[scopeIndex()] }}</view>
          </picker>
        </view>
        <view v-if="form.scope === 'DEFINITION'" class="field">
          <text class="field-label">流程标识</text>
          <input v-model="form.definitionKey" class="field-input" :maxlength="256" placeholder="例如 purchase-payment">
        </view>
        <view class="date-grid">
          <view class="field">
            <text class="field-label">开始日期</text>
            <picker mode="date" :value="form.startDate" @change="form.startDate = eventValue($event)">
              <view class="picker-value">{{ form.startDate }}</view>
            </picker>
          </view>
          <view class="field">
            <text class="field-label">开始时间</text>
            <picker mode="time" :value="form.startTime" @change="form.startTime = eventValue($event)">
              <view class="picker-value">{{ form.startTime }}</view>
            </picker>
          </view>
          <view class="field">
            <text class="field-label">结束日期</text>
            <picker mode="date" :value="form.endDate" @change="form.endDate = eventValue($event)">
              <view class="picker-value">{{ form.endDate }}</view>
            </picker>
          </view>
          <view class="field">
            <text class="field-label">结束时间</text>
            <picker mode="time" :value="form.endTime" @change="form.endTime = eventValue($event)">
              <view class="picker-value">{{ form.endTime }}</view>
            </picker>
          </view>
        </view>
        <view class="field">
          <text class="field-label">代理原因</text>
          <textarea
            v-model="form.reason"
            class="field-textarea"
            :maxlength="2000"
            placeholder="说明请假、出差或临时职责安排"
          />
        </view>
        <view class="form-actions">
          <wd-button plain @click="showCreate = false">取消</wd-button>
          <wd-button :loading="submitting" type="primary" @click="submitCreate">创建代理</wd-button>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 120rpx;
  background: var(--wot-color-bg, var(--uni-bg-color-grey));
}

.notice-card,
.toolbar-card,
.rule-card,
.state-card,
.form-panel {
  border-radius: var(--wot-radius-large, 24rpx);
  background: var(--wot-color-white, var(--uni-bg-color));
  box-shadow: 0 8rpx 24rpx rgb(15 23 42 / 5%);
}

.notice-card,
.toolbar-card,
.rule-card,
.state-card {
  margin-bottom: 20rpx;
  padding: 28rpx;
}

.notice-card,
.toolbar-card > view:first-child,
.rule-header > view,
.field {
  display: grid;
  gap: 10rpx;
}

.notice-title,
.toolbar-title,
.rule-delegate,
.form-title,
.field-label {
  color: var(--wot-color-content, var(--uni-text-color));
  font-weight: 700;
}

.notice-card text:last-child,
.toolbar-subtitle,
.rule-scope,
.rule-row text:first-child,
.state-card {
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-size: 24rpx;
}

.toolbar-card,
.toolbar-actions,
.switch-row,
.rule-header,
.rule-row,
.form-actions {
  display: flex;
  align-items: center;
}

.toolbar-card,
.rule-header,
.rule-row {
  justify-content: space-between;
  gap: 20rpx;
}

.toolbar-actions {
  flex-direction: column;
  align-items: flex-end;
  gap: 12rpx;
}

.switch-row {
  gap: 12rpx;
  font-size: 24rpx;
}

.rule-list {
  display: grid;
  gap: 20rpx;
}

.rule-card {
  display: grid;
  gap: 20rpx;
}

.rule-row {
  align-items: flex-start;
}

.rule-row text:last-child {
  max-width: 470rpx;
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 25rpx;
  overflow-wrap: anywhere;
  text-align: right;
}

.mask {
  position: fixed;
  z-index: 1000;
  inset: 0;
  display: flex;
  align-items: flex-end;
  padding: 24rpx;
  background: rgb(15 23 42 / 45%);
}

.form-panel {
  width: 100%;
  max-height: 88vh;
  padding: 32rpx;
  overflow-y: auto;
}

.form-title {
  margin-bottom: 28rpx;
  font-size: 32rpx;
}

.field {
  margin-bottom: 22rpx;
}

.field-input,
.picker-value,
.field-textarea {
  box-sizing: border-box;
  width: 100%;
  padding: 22rpx;
  border: 1rpx solid var(--wot-color-border-light, var(--uni-border-color));
  border-radius: 14rpx;
  background: var(--wot-color-bg, var(--uni-bg-color-grey));
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 26rpx;
}

.field-textarea {
  min-height: 150rpx;
}

.date-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 18rpx;
}

.form-actions {
  justify-content: flex-end;
  gap: 20rpx;
}
</style>
