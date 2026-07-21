<script lang="ts" setup>
import type {
  NotificationChannel,
  NotificationEventType,
  NotificationHistoryPage,
  NotificationIntent,
  NotificationPreferenceBundle,
} from '@/api/approval/notifications'

import {
  findNotificationHistory,
  findNotificationPreferences,
  findNotificationUnreadCount,
  markAllNotificationsRead,
  markNotificationRead,
  replayNotification,
  updateNotificationPreferences,
} from '@/api/approval/notifications'

defineOptions({ name: 'ApprovalNotificationCenter' })
definePage({ style: { navigationBarTitleText: '通知中心' } })

type ActiveTab = 'history' | 'preferences'

const activeTab = ref<ActiveTab>('history')
const loading = ref(false)
const saving = ref(false)
const unreadOnly = ref(false)
const unreadCount = ref(0)
const history = ref<NotificationHistoryPage>({ hasMore: false, items: [], limit: 20, offset: 0, total: 0 })
const preferences = ref<NotificationPreferenceBundle>()
const page = ref(1)
const pageSize = 20

const eventTypes: NotificationEventType[] = [
  'TASK_ASSIGNED',
  'AUTOMATIC_DELEGATION',
  'EMPLOYEE_HANDOVER',
  'TASK_COLLABORATION_ASSIGNED',
  'TASK_COLLABORATION_RESULT',
  'APPROVAL_COMPLETED',
  'APPROVAL_REJECTED',
  'COMMENT_MENTION',
]
const channels: NotificationChannel[] = ['IN_APP', 'CONNECTOR', 'EMAIL']

function eventLabel(value: NotificationEventType) {
  return {
    APPROVAL_COMPLETED: '审批完成',
    APPROVAL_REJECTED: '审批驳回',
    AUTOMATIC_DELEGATION: '自动代理',
    COMMENT_MENTION: '评论提及',
    EMPLOYEE_HANDOVER: '离职交接',
    TASK_ASSIGNED: '任务待办',
    TASK_COLLABORATION_ASSIGNED: '加签待办',
    TASK_COLLABORATION_RESULT: '加签结果',
  }[value]
}

function channelLabel(value: NotificationChannel) {
  return { CONNECTOR: '连接器', EMAIL: '邮件', IN_APP: '站内信' }[value]
}

function statusLabel(item: NotificationIntent) {
  return {
    DEAD_LETTER: '投递失败',
    DELIVERED: item.readAt ? '已读' : '未读',
    PENDING: '等待投递',
    PROCESSING: '投递中',
    RETRY: '等待重试',
  }[item.status]
}

function statusType(item: NotificationIntent) {
  if (item.status === 'DEAD_LETTER') return 'error'
  if (item.status === 'RETRY') return 'warning'
  if (item.status === 'DELIVERED' && item.readAt) return 'success'
  return 'primary'
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

function formatDate(value?: string) {
  if (!value) return '-'
  const date = new Date(value)
  return `${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function preference(eventType: NotificationEventType, channel: NotificationChannel) {
  return preferences.value?.preferences.find(
    item => item.eventType === eventType && item.channel === channel,
  )
}

function switchValue(event: unknown) {
  return Boolean((event as { detail?: { value?: boolean } })?.detail?.value)
}

function setPreference(eventType: NotificationEventType, channel: NotificationChannel, event: unknown) {
  const item = preference(eventType, channel)
  if (item) item.enabled = switchValue(event)
}

function setQuietHours(event: unknown) {
  if (preferences.value) preferences.value.quietHoursEnabled = switchValue(event)
}

function setEmergencyBypass(event: unknown) {
  if (preferences.value) preferences.value.emergencyBypass = switchValue(event)
}

function setDigestEnabled(event: unknown) {
  if (preferences.value) preferences.value.digestEnabled = switchValue(event)
}

async function loadHistory() {
  loading.value = true
  try {
    const [items, unread] = await Promise.all([
      findNotificationHistory(unreadOnly.value, pageSize, (page.value - 1) * pageSize),
      findNotificationUnreadCount(),
    ])
    history.value = items
    unreadCount.value = unread.unread
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    loading.value = false
  }
}

async function loadPreferences() {
  loading.value = true
  try {
    preferences.value = await findNotificationPreferences()
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    loading.value = false
  }
}

async function selectTab(tab: ActiveTab) {
  activeTab.value = tab
  if (tab === 'history') await loadHistory()
  else await loadPreferences()
}

async function toggleUnread() {
  unreadOnly.value = !unreadOnly.value
  page.value = 1
  await loadHistory()
}

async function openNotification(item: NotificationIntent) {
  try {
    if (!item.readAt) await markNotificationRead(item.intentId)
    if (item.instanceId) {
      uni.navigateTo({
        url: `/pages/discussion/detail?instanceId=${encodeURIComponent(item.instanceId)}`,
      })
    }
    else if (item.taskId) {
      uni.navigateTo({ url: '/pages/task/list' })
    }
    else {
      uni.showToast({ title: '已标记为已读', icon: 'success' })
      await loadHistory()
    }
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
}

async function replay(item: NotificationIntent) {
  uni.showModal({
    title: '重新投递',
    content: '确认重新投递这条通知吗？',
    success: async (result) => {
      if (!result.confirm) return
      try {
        await replayNotification(item.intentId)
        uni.showToast({ title: '已重新进入队列', icon: 'success' })
        await loadHistory()
      }
      catch (error) {
        uni.showToast({ title: errorMessage(error), icon: 'none' })
      }
    },
  })
}

async function readAll() {
  try {
    const result = await markAllNotificationsRead()
    uni.showToast({ title: `已读 ${result.updatedNotifications} 条`, icon: 'success' })
    await loadHistory()
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
}

async function savePreferences() {
  const value = preferences.value
  if (!value) return
  if (!value.timezone.trim()) {
    uni.showToast({ title: '请输入时区', icon: 'none' })
    return
  }
  if (value.quietHoursEnabled && (!value.quietHoursStart || !value.quietHoursEnd)) {
    uni.showToast({ title: '请设置完整安静时间', icon: 'none' })
    return
  }
  saving.value = true
  try {
    preferences.value = await updateNotificationPreferences(value)
    uni.showToast({ title: '设置已保存', icon: 'success' })
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    saving.value = false
  }
}

async function previousPage() {
  if (page.value <= 1) return
  page.value -= 1
  await loadHistory()
}

async function nextPage() {
  if (!history.value.hasMore) return
  page.value += 1
  await loadHistory()
}

onShow(loadHistory)
</script>

<template>
  <view class="page">
    <view class="summary-card">
      <view>
        <text>未读通知</text>
        <strong>{{ unreadCount }}</strong>
      </view>
      <wd-button v-if="unreadCount" size="small" plain type="primary" @click="readAll">全部已读</wd-button>
    </view>

    <view class="tab-bar">
      <wd-button size="small" type="primary" :plain="activeTab !== 'history'" @click="selectTab('history')">
        通知记录
      </wd-button>
      <wd-button size="small" type="primary" :plain="activeTab !== 'preferences'" @click="selectTab('preferences')">
        通知偏好
      </wd-button>
    </view>

    <template v-if="activeTab === 'history'">
      <view class="toolbar-card" @click="toggleUnread">
        <text>{{ unreadOnly ? '只看未读' : '查看全部' }}</text>
        <switch :checked="unreadOnly" color="#2563eb" />
      </view>
      <view v-if="loading" class="state-card">正在加载通知...</view>
      <view v-else-if="history.items.length === 0" class="state-card">暂无通知</view>
      <view v-else class="list">
        <view
          v-for="item in history.items"
          :key="item.intentId"
          :class="['notification-card', { unread: !item.readAt }]"
        >
          <view class="card-main" @click="openNotification(item)">
            <view class="card-header">
              <text class="card-title">{{ item.title }}</text>
              <wd-tag :type="statusType(item)" plain>{{ statusLabel(item) }}</wd-tag>
            </view>
            <view class="tag-row">
              <wd-tag plain type="primary">{{ eventLabel(item.eventType) }}</wd-tag>
              <wd-tag plain>{{ channelLabel(item.channel) }}</wd-tag>
              <wd-tag v-if="item.urgent" type="error">紧急</wd-tag>
            </view>
            <text class="card-body">{{ item.body }}</text>
            <text class="card-meta">{{ item.senderId }} · {{ formatDate(item.createdAt) }}</text>
            <text v-if="item.lastErrorMessage" class="error-text">
              {{ item.lastErrorCode }} · {{ item.lastErrorMessage }}
            </text>
          </view>
          <view class="action-row">
            <wd-button v-if="item.status === 'DEAD_LETTER'" size="small" plain type="error" @click="replay(item)">
              重新投递
            </wd-button>
            <wd-button size="small" type="primary" @click="openNotification(item)">打开</wd-button>
          </view>
        </view>
      </view>
      <view v-if="history.total > pageSize" class="pagination">
        <wd-button size="small" plain :disabled="page <= 1" @click="previousPage">上一页</wd-button>
        <text>第 {{ page }} 页</text>
        <wd-button size="small" plain :disabled="!history.hasMore" @click="nextPage">下一页</wd-button>
      </view>
    </template>

    <template v-else>
      <view v-if="loading" class="state-card">正在加载设置...</view>
      <view v-else-if="preferences" class="preferences-list">
        <view class="settings-card">
          <view class="field">
            <text>时区</text>
            <input v-model="preferences.timezone" class="field-input" placeholder="例如 Asia/Shanghai">
          </view>
          <view class="switch-row">
            <view><text>安静时间</text><text class="hint">非紧急通知延后投递</text></view>
            <switch :checked="preferences.quietHoursEnabled" color="#2563eb" @change="setQuietHours" />
          </view>
          <view v-if="preferences.quietHoursEnabled" class="time-grid">
            <input v-model="preferences.quietHoursStart" class="field-input" placeholder="22:00">
            <input v-model="preferences.quietHoursEnd" class="field-input" placeholder="07:00">
          </view>
          <view class="switch-row">
            <view><text>紧急事项立即通知</text><text class="hint">绕过安静时间</text></view>
            <switch :checked="preferences.emergencyBypass" color="#2563eb" @change="setEmergencyBypass" />
          </view>
          <view class="switch-row">
            <view><text>允许摘要</text><text class="hint">合并非紧急通知</text></view>
            <switch :checked="preferences.digestEnabled" color="#2563eb" @change="setDigestEnabled" />
          </view>
        </view>

        <view v-for="eventType in eventTypes" :key="eventType" class="preference-card">
          <text class="card-title">{{ eventLabel(eventType) }}</text>
          <view v-for="channel in channels" :key="channel" class="switch-row">
            <text>{{ channelLabel(channel) }}</text>
            <switch
              :checked="preference(eventType, channel)?.enabled"
              color="#2563eb"
              @change="setPreference(eventType, channel, $event)"
            />
          </view>
        </view>
        <wd-button block type="primary" :loading="saving" @click="savePreferences">保存设置</wd-button>
      </view>
    </template>
  </view>
</template>

<style scoped>
.page{min-height:100vh;padding:24rpx 24rpx 120rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}.summary-card,.toolbar-card,.state-card,.notification-card,.settings-card,.preference-card{padding:26rpx;border-radius:24rpx;background:var(--wot-color-white,var(--uni-bg-color));box-shadow:0 8rpx 24rpx rgb(15 23 42 / 5%)}.summary-card,.toolbar-card,.switch-row,.card-header,.action-row,.pagination{display:flex;align-items:center;justify-content:space-between;gap:16rpx}.summary-card>view{display:grid;gap:8rpx}.summary-card strong{font-size:48rpx}.tab-bar,.tag-row,.action-row{display:flex;gap:14rpx}.tab-bar{margin:24rpx 0}.list,.preferences-list,.card-main,.settings-card,.preference-card{display:grid;gap:18rpx}.notification-card{border-left:6rpx solid transparent}.notification-card.unread{border-left-color:var(--wot-color-theme,var(--uni-color-primary))}.card-title{font-size:29rpx;font-weight:700}.card-body,.card-meta,.hint,.state-card,.pagination{color:var(--wot-color-content-secondary,var(--uni-text-color-grey));font-size:24rpx}.error-text{color:var(--wot-color-danger,var(--uni-color-error));font-size:23rpx}.action-row{justify-content:flex-end}.pagination{margin-top:24rpx}.field{display:grid;gap:10rpx}.field-input{box-sizing:border-box;width:100%;padding:18rpx;border:1rpx solid var(--wot-color-border-light,var(--uni-border-color));border-radius:14rpx;background:var(--wot-color-bg,var(--uni-bg-color-grey))}.switch-row>view{display:grid;gap:6rpx}.time-grid{display:grid;grid-template-columns:1fr 1fr;gap:14rpx}
</style>
