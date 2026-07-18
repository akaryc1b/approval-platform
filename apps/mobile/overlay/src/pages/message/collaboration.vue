<script lang="ts" setup>
import type {
  ApprovalUserOption,
  CollaborationOptions,
  MessageReceipt,
} from '@/api/approval'

import {
  copyInstance,
  findCollaborationOptions,
  findMessageReceipts,
  urgeInstance,
} from '@/api/approval'

defineOptions({
  name: 'ApprovalMessageCollaboration',
})

definePage({
  style: {
    navigationBarTitleText: '审批协作',
  },
})

const instanceId = ref('')
const supplier = ref('')
const businessKey = ref('')
const amount = ref(0)
const loading = ref(false)
const submitting = ref(false)
const loadError = ref('')
const comment = ref('')
const options = ref<CollaborationOptions>()
const receipts = ref<MessageReceipt[]>([])
const selectedRecipientIds = ref<string[]>([])

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

function formatMoney(value: number) {
  return `¥${Number(value).toFixed(2)}`
}

function formatDate(value?: string) {
  if (!value) {
    return '未读'
  }
  const date = new Date(value)
  return `${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function receiptTypeLabel(type: MessageReceipt['messageType']) {
  const kind = type as string
  if (kind === 'URGE') return '催办'
  if (kind === 'MENTION') return '@提及'
  return '抄送'
}

function selected(candidate: ApprovalUserOption) {
  return selectedRecipientIds.value.includes(candidate.userId)
}

function toggleRecipient(candidate: ApprovalUserOption) {
  if (selected(candidate)) {
    selectedRecipientIds.value = selectedRecipientIds.value.filter(
      value => value !== candidate.userId,
    )
    return
  }
  selectedRecipientIds.value = [...selectedRecipientIds.value, candidate.userId]
}

async function loadCollaboration() {
  if (!instanceId.value) {
    loadError.value = '缺少审批实例编号'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const [collaborationOptions, receiptItems] = await Promise.all([
      findCollaborationOptions(instanceId.value),
      findMessageReceipts(instanceId.value),
    ])
    options.value = collaborationOptions
    receipts.value = receiptItems
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

async function submitUrge() {
  if (!options.value?.canUrge || submitting.value) {
    return
  }
  const confirmed = await confirmAction(
    '确认催办',
    '消息将发送给当前待处理人，10 分钟内不会重复发送。',
    '发送催办',
  )
  if (!confirmed) {
    return
  }
  submitting.value = true
  try {
    const result = await urgeInstance(instanceId.value, comment.value)
    uni.showToast({
      title: `已催办 ${result.createdMessages} 人`,
      icon: 'success',
    })
    await loadCollaboration()
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    submitting.value = false
  }
}

async function submitCopy() {
  if (selectedRecipientIds.value.length === 0 || submitting.value) {
    if (selectedRecipientIds.value.length === 0) {
      uni.showToast({ title: '请选择抄送人员', icon: 'none' })
    }
    return
  }
  submitting.value = true
  try {
    const result = await copyInstance(
      instanceId.value,
      selectedRecipientIds.value,
      comment.value,
    )
    uni.showToast({
      title: `已抄送 ${result.createdMessages} 人`,
      icon: 'success',
    })
    selectedRecipientIds.value = []
    await loadCollaboration()
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    submitting.value = false
  }
}

onLoad((query) => {
  instanceId.value = String(query?.id || '')
  supplier.value = decodeURIComponent(String(query?.supplier || '采购付款审批'))
  businessKey.value = decodeURIComponent(String(query?.businessKey || ''))
  amount.value = Number(query?.amount || 0)
  loadCollaboration()
})
</script>

<template>
  <view class="page">
    <view v-if="loadError" class="state-card state-card--error">
      <text>{{ loadError }}</text>
      <wd-button size="small" plain @click="loadCollaboration">重新加载</wd-button>
    </view>

    <view v-else-if="loading" class="state-card">
      正在加载协作信息...
    </view>

    <template v-else>
      <view class="summary-card">
        <view class="summary-title">{{ supplier }}采购付款</view>
        <text>{{ businessKey }}</text>
        <strong>{{ formatMoney(amount) }}</strong>
        <text>
          当前待处理人：
          {{ options?.activeAssignees.map(item => item.displayName).join('、') || '无' }}
        </text>
      </view>

      <view class="section-card">
        <view class="section-title">
          <text>选择抄送人员</text>
          <text class="section-hint">已选 {{ selectedRecipientIds.length }} 人</text>
        </view>
        <view v-if="options?.copyCandidates.length" class="candidate-list">
          <view
            v-for="candidate in options.copyCandidates"
            :key="candidate.userId"
            class="candidate-row"
            :class="{ 'candidate-row--selected': selected(candidate) }"
            @click="toggleRecipient(candidate)"
          >
            <view>
              <text class="candidate-name">{{ candidate.displayName }}</text>
              <text class="candidate-id">{{ candidate.userId }}</text>
            </view>
            <wd-tag :type="selected(candidate) ? 'primary' : 'info'" plain>
              {{ selected(candidate) ? '已选择' : '选择' }}
            </wd-tag>
          </view>
        </view>
        <text v-else class="empty-text">暂无可抄送人员</text>
      </view>

      <view class="section-card">
        <view class="section-title">消息说明</view>
        <wd-textarea
          v-model="comment"
          :maxlength="2000"
          clearable
          placeholder="填写催办说明或抄送备注（可选）"
          show-word-limit
        />
      </view>

      <view class="section-card">
        <view class="section-title">已读回执</view>
        <view v-if="receipts.length" class="receipt-list">
          <view v-for="receipt in receipts" :key="receipt.messageId" class="receipt-row">
            <view>
              <text class="receipt-user">{{ receipt.recipientId }}</text>
              <text class="receipt-meta">
                {{ receiptTypeLabel(receipt.messageType) }} ·
                {{ formatDate(receipt.sentAt) }}
              </text>
            </view>
            <wd-tag :type="receipt.read ? 'success' : 'info'" plain>
              {{ receipt.read ? `已读 ${formatDate(receipt.readAt)}` : '未读' }}
            </wd-tag>
          </view>
        </view>
        <text v-else class="empty-text">尚未发送催办、抄送或提及消息</text>
      </view>
    </template>

    <view class="action-bar">
      <wd-button
        plain
        type="warning"
        :disabled="!options?.canUrge"
        :loading="submitting"
        @click="submitUrge"
      >
        催办当前处理人
      </wd-button>
      <wd-button
        type="primary"
        :disabled="selectedRecipientIds.length === 0"
        :loading="submitting"
        @click="submitCopy"
      >
        发送抄送
      </wd-button>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 180rpx;
  background: var(--wot-color-bg, var(--uni-bg-color-grey));
}

.summary-card,
.section-card,
.state-card {
  margin-bottom: 20rpx;
  padding: 28rpx;
  border-radius: var(--wot-radius-large, 24rpx);
  background: var(--wot-color-white, var(--uni-bg-color));
  box-shadow: 0 8rpx 24rpx rgb(15 23 42 / 5%);
}

.summary-card {
  display: grid;
  gap: 12rpx;
}

.summary-title,
.section-title,
.candidate-name,
.receipt-user {
  color: var(--wot-color-content, var(--uni-text-color));
  font-weight: 700;
}

.summary-title {
  font-size: 32rpx;
}

.summary-card text,
.section-hint,
.candidate-id,
.receipt-meta,
.empty-text,
.state-card {
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-size: 24rpx;
}

.summary-card strong {
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 36rpx;
}

.section-title,
.candidate-row,
.receipt-row,
.action-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18rpx;
}

.section-title {
  margin-bottom: 20rpx;
  font-size: 28rpx;
}

.candidate-list,
.receipt-list {
  display: grid;
}

.candidate-row,
.receipt-row {
  min-height: 92rpx;
  border-bottom: 1rpx solid var(--wot-color-border-light, var(--uni-border-color));
}

.candidate-row:last-child,
.receipt-row:last-child {
  border-bottom: 0;
}

.candidate-row--selected {
  margin: 4rpx -12rpx;
  padding: 0 12rpx;
  border-radius: var(--wot-radius-base, 16rpx);
  background: var(--wot-color-theme-light, var(--uni-bg-color));
}

.candidate-row > view,
.receipt-row > view {
  display: grid;
  flex: 1;
  gap: 6rpx;
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
