<script lang="ts" setup>
import type {
  ApprovalMessageItem,
  ApprovalMessagePage,
  StartedInstanceItem,
  StartedInstancePage,
} from '@/api/approval'

import {
  findMessages,
  findStartedInstances,
  findUnreadMessageCount,
  markAllMessagesRead,
  markMessageRead,
} from '@/api/approval'

defineOptions({
  name: 'ApprovalMessageCenter',
})

definePage({
  style: {
    navigationBarTitleText: '消息与协作',
  },
})

type ActiveTab = 'collaboration' | 'messages'

type TagType = 'danger' | 'primary' | 'success' | 'warning'

const activeTab = ref<ActiveTab>('messages')
const unreadCount = ref(0)
const unreadOnly = ref(false)
const loading = ref(false)
const currentPage = ref(1)
const pageSize = 20
const messagePage = ref<ApprovalMessagePage>(emptyMessagePage())

const startedLoading = ref(false)
const startedCurrentPage = ref(1)
const startedPageSize = 10
const startedPage = ref<StartedInstancePage>(emptyStartedPage())

function emptyMessagePage(): ApprovalMessagePage {
  return {
    hasMore: false,
    items: [],
    limit: pageSize,
    offset: 0,
    total: 0,
  }
}

function emptyStartedPage(): StartedInstancePage {
  return {
    hasMore: false,
    items: [],
    limit: startedPageSize,
    offset: 0,
    total: 0,
  }
}

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

function messageTypeLabel(type: ApprovalMessageItem['messageType']) {
  const kind = type as string
  if (kind === 'URGE') return '催办'
  if (kind === 'MENTION') return '@提及'
  return '抄送'
}

function messageTypeTag(type: ApprovalMessageItem['messageType']): TagType {
  const kind = type as string
  if (kind === 'URGE') return 'warning'
  if (kind === 'MENTION') return 'success'
  return 'primary'
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

async function loadUnreadCount() {
  try {
    unreadCount.value = (await findUnreadMessageCount()).unread
  }
  catch {
    unreadCount.value = 0
  }
}

async function loadMessages() {
  loading.value = true
  try {
    messagePage.value = await findMessages(
      unreadOnly.value,
      pageSize,
      (currentPage.value - 1) * pageSize,
    )
  }
  catch (error) {
    messagePage.value = emptyMessagePage()
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    loading.value = false
  }
}

async function loadStarted() {
  startedLoading.value = true
  try {
    startedPage.value = await findStartedInstances({
      limit: startedPageSize,
      offset: (startedCurrentPage.value - 1) * startedPageSize,
    })
  }
  catch (error) {
    startedPage.value = emptyStartedPage()
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    startedLoading.value = false
  }
}

async function selectTab(tab: ActiveTab) {
  activeTab.value = tab
  if (tab === 'messages') {
    await loadMessages()
  }
  else {
    await loadStarted()
  }
}

async function toggleUnreadOnly() {
  unreadOnly.value = !unreadOnly.value
  currentPage.value = 1
  await loadMessages()
}

async function readMessage(item: ApprovalMessageItem) {
  if (item.read) {
    return
  }
  try {
    await markMessageRead(item.messageId)
    item.read = true
    item.readAt = new Date().toISOString()
    unreadCount.value = Math.max(0, unreadCount.value - 1)
    if (unreadOnly.value) {
      await loadMessages()
    }
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
}

async function readAll() {
  try {
    const result = await markAllMessagesRead()
    uni.showToast({ title: `已读 ${result.updatedMessages} 条`, icon: 'success' })
    await Promise.all([loadMessages(), loadUnreadCount()])
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
}

async function previousMessages() {
  if (currentPage.value <= 1) {
    return
  }
  currentPage.value -= 1
  await loadMessages()
}

async function nextMessages() {
  if (!messagePage.value.hasMore) {
    return
  }
  currentPage.value += 1
  await loadMessages()
}

async function previousStarted() {
  if (startedCurrentPage.value <= 1) {
    return
  }
  startedCurrentPage.value -= 1
  await loadStarted()
}

async function nextStarted() {
  if (!startedPage.value.hasMore) {
    return
  }
  startedCurrentPage.value += 1
  await loadStarted()
}

function openCollaboration(item: StartedInstanceItem) {
  uni.navigateTo({
    url: `/pages/message/collaboration?id=${encodeURIComponent(item.instanceId)}&supplier=${encodeURIComponent(item.supplier)}&amount=${encodeURIComponent(String(item.amount))}&businessKey=${encodeURIComponent(item.businessKey)}`,
  })
}

onShow(async () => {
  await Promise.all([loadUnreadCount(), loadMessages(), loadStarted()])
})
</script>

<template>
  <view class="page">
    <view class="summary-grid">
      <view class="summary-card" @click="selectTab('messages')">
        <text>未读消息</text>
        <strong>{{ unreadCount }}</strong>
        <wd-tag type="danger" plain>催办、抄送与提及</wd-tag>
      </view>
      <view class="summary-card" @click="selectTab('collaboration')">
        <text>我发起的</text>
        <strong>{{ startedPage.total }}</strong>
        <wd-tag type="primary" plain>协作与回执</wd-tag>
      </view>
    </view>

    <view class="tab-bar">
      <wd-button
        size="small"
        type="primary"
        :plain="activeTab !== 'messages'"
        @click="selectTab('messages')"
      >
        消息中心
      </wd-button>
      <wd-button
        size="small"
        type="primary"
        :plain="activeTab !== 'collaboration'"
        @click="selectTab('collaboration')"
      >
        我发起的协作
      </wd-button>
    </view>

    <template v-if="activeTab === 'messages'">
      <view class="toolbar-card">
        <wd-button size="small" plain @click="toggleUnreadOnly">
          {{ unreadOnly ? '查看全部' : '只看未读' }}
        </wd-button>
        <wd-button size="small" type="primary" plain :disabled="unreadCount === 0" @click="readAll">
          全部已读
        </wd-button>
      </view>

      <view v-if="loading" class="state-card">正在加载消息...</view>
      <view v-else-if="messagePage.items.length === 0" class="state-card">暂无消息</view>
      <view v-else class="list">
        <view
          v-for="item in messagePage.items"
          :key="item.messageId"
          class="message-card"
          :class="{ 'message-card--unread': !item.read }"
          @click="readMessage(item)"
        >
          <view class="card-header">
            <text class="card-title">{{ item.title }}</text>
            <wd-tag :type="messageTypeTag(item.messageType)" plain>
              {{ messageTypeLabel(item.messageType) }}
            </wd-tag>
          </view>
          <text class="card-body">{{ item.body }}</text>
          <text class="card-meta">{{ item.businessKey }} · 发送人 {{ item.senderId }}</text>
          <view class="card-footer">
            <text>{{ formatMoney(item.amount) }}</text>
            <text>{{ item.read ? `已读 ${formatDate(item.readAt)}` : '点击标记已读' }}</text>
          </view>
        </view>
      </view>

      <view v-if="messagePage.total > pageSize" class="pagination">
        <wd-button size="small" plain :disabled="currentPage <= 1" @click="previousMessages">
          上一页
        </wd-button>
        <text>第 {{ currentPage }} 页</text>
        <wd-button size="small" plain :disabled="!messagePage.hasMore" @click="nextMessages">
          下一页
        </wd-button>
      </view>
    </template>

    <template v-else>
      <view v-if="startedLoading" class="state-card">正在加载发起记录...</view>
      <view v-else-if="startedPage.items.length === 0" class="state-card">暂无发起记录</view>
      <view v-else class="list">
        <view
          v-for="item in startedPage.items"
          :key="item.instanceId"
          class="message-card"
          @click="openCollaboration(item)"
        >
          <view class="card-header">
            <text class="card-title">{{ item.supplier }}采购付款</text>
            <wd-tag type="primary" plain>{{ statusLabel(item.status) }}</wd-tag>
          </view>
          <text class="card-body">{{ item.businessKey }} · {{ item.purchaseOrderReference }}</text>
          <text class="card-meta">当前环节：{{ item.currentTaskName || '流程已结束' }}</text>
          <view class="card-footer">
            <text>{{ formatMoney(item.amount) }}</text>
            <text>已读回执 {{ item.readCount }}/{{ item.messageCount }}</text>
          </view>
        </view>
      </view>

      <view v-if="startedPage.total > startedPageSize" class="pagination">
        <wd-button size="small" plain :disabled="startedCurrentPage <= 1" @click="previousStarted">
          上一页
        </wd-button>
        <text>第 {{ startedCurrentPage }} 页</text>
        <wd-button size="small" plain :disabled="!startedPage.hasMore" @click="nextStarted">
          下一页
        </wd-button>
      </view>
    </template>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 80rpx;
  background: var(--wot-color-bg, var(--uni-bg-color-grey));
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20rpx;
}

.summary-card,
.toolbar-card,
.message-card,
.state-card {
  border-radius: var(--wot-radius-large, 24rpx);
  background: var(--wot-color-white, var(--uni-bg-color));
  box-shadow: 0 8rpx 24rpx rgb(15 23 42 / 5%);
}

.summary-card {
  display: grid;
  gap: 12rpx;
  padding: 24rpx;
}

.summary-card text,
.card-meta,
.card-footer,
.state-card,
.pagination {
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-size: 24rpx;
}

.summary-card strong {
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 46rpx;
}

.tab-bar,
.toolbar-card,
.card-header,
.card-footer,
.pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.tab-bar {
  justify-content: flex-start;
  margin: 28rpx 0 20rpx;
}

.toolbar-card {
  padding: 18rpx 22rpx;
}

.list {
  display: grid;
  gap: 20rpx;
  margin-top: 20rpx;
}

.message-card {
  display: grid;
  gap: 14rpx;
  padding: 26rpx;
}

.message-card--unread {
  border: 2rpx solid var(--wot-color-theme, var(--uni-color-primary));
  background: var(--wot-color-theme-light, var(--uni-bg-color));
}

.card-title {
  flex: 1;
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 29rpx;
  font-weight: 700;
}

.card-body {
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 26rpx;
  line-height: 1.6;
}

.state-card {
  margin-top: 20rpx;
  padding: 60rpx 24rpx;
  text-align: center;
}

.pagination {
  margin-top: 28rpx;
  padding: 12rpx 4rpx;
}
</style>
