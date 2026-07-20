<script lang="ts" setup>
import { findUnreadMessageCount } from '@/api/approval'
import { getApprovalRuntimeConfig } from '@/platform/approval/runtime'

defineOptions({
  name: 'ApprovalProfile',
})

definePage({
  style: {
    navigationBarTitleText: '我的',
  },
})

const runtime = getApprovalRuntimeConfig()
const unreadMessages = ref(0)

function openTaskList() {
  uni.navigateTo({ url: '/pages/task/list' })
}

function openCollaboration() {
  uni.navigateTo({ url: '/pages/collaboration/index' })
}

function openDiscussion() {
  uni.navigateTo({ url: '/pages/discussion/index' })
}

function openMessages() {
  uni.navigateTo({ url: '/pages/message/index' })
}

function openDelegations() {
  uni.navigateTo({ url: '/pages/delegation/index' })
}

function openInitiate() {
  uni.switchTab({ url: '/pages/initiate/index' })
}

async function loadUnreadMessages() {
  try {
    unreadMessages.value = (await findUnreadMessageCount()).unread
  }
  catch {
    unreadMessages.value = 0
  }
}

onShow(loadUnreadMessages)
</script>

<template>
  <view class="page">
    <view class="profile-card">
      <view class="avatar">{{ runtime.operatorId.slice(0, 1).toUpperCase() }}</view>
      <view class="profile-card__main">
        <text class="profile-card__name">{{ runtime.operatorId }}</text>
        <text class="profile-card__meta">租户：{{ runtime.tenantId }}</text>
      </view>
      <wd-tag plain type="success">{{ runtime.connector }}</wd-tag>
    </view>

    <view class="entry-card">
      <view class="entry-row" @click="openTaskList">
        <text>审批工作台</text>
        <text class="entry-row__value">›</text>
      </view>
      <view class="entry-row" @click="openCollaboration">
        <view class="entry-row__content">
          <text>加签协作</text>
          <text class="entry-row__hint">发起加签、减签或处理协作待办</text>
        </view>
        <text class="entry-row__value">›</text>
      </view>
      <view class="entry-row" @click="openDiscussion">
        <text>审批讨论</text>
        <text class="entry-row__value">›</text>
      </view>
      <view class="entry-row" @click="openMessages">
        <text>消息与协作</text>
        <view class="entry-row__right">
          <wd-tag v-if="unreadMessages" type="danger" plain>
            {{ unreadMessages }} 未读
          </wd-tag>
          <text class="entry-row__value">›</text>
        </view>
      </view>
      <view class="entry-row" @click="openDelegations">
        <view class="entry-row__content">
          <text>代理规则</text>
          <text class="entry-row__hint">请假或出差期间自动分派新任务</text>
        </view>
        <text class="entry-row__value">›</text>
      </view>
      <view class="entry-row" @click="openInitiate">
        <text>发起审批</text>
        <text class="entry-row__value">›</text>
      </view>
    </view>

    <view class="runtime-card">
      <view class="runtime-card__title">运行环境</view>
      <view class="runtime-row">
        <text>API</text>
        <text>{{ runtime.apiBaseUrl }}</text>
      </view>
      <view class="runtime-row">
        <text>租户</text>
        <text>{{ runtime.tenantId }}</text>
      </view>
      <view class="runtime-row">
        <text>操作人</text>
        <text>{{ runtime.operatorId }}</text>
      </view>
      <view class="runtime-row">
        <text>连接器</text>
        <text>{{ runtime.connector }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 160rpx;
  background: var(--wot-color-bg, var(--uni-bg-color-grey));
}

.profile-card,
.entry-card,
.runtime-card {
  border-radius: var(--wot-radius-large, 24rpx);
  background: var(--wot-color-white, var(--uni-bg-color));
  box-shadow: 0 8rpx 24rpx rgb(15 23 42 / 5%);
}

.profile-card {
  display: flex;
  align-items: center;
  gap: 22rpx;
  padding: 32rpx;
}

.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 88rpx;
  height: 88rpx;
  color: var(--wot-color-white, var(--uni-text-color-inverse));
  border-radius: var(--wot-radius-large, 26rpx);
  background: var(--wot-color-theme, var(--uni-color-primary));
  font-size: 34rpx;
  font-weight: 700;
}

.profile-card__main,
.entry-row__content {
  display: grid;
  flex: 1;
  gap: 10rpx;
}

.profile-card__name,
.runtime-card__title {
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 30rpx;
  font-weight: 700;
}

.profile-card__meta,
.runtime-row text:first-child,
.entry-row__value,
.entry-row__hint {
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-size: 24rpx;
}

.entry-row__hint {
  font-size: 22rpx;
}

.entry-card,
.runtime-card {
  margin-top: 24rpx;
  padding: 0 28rpx;
}

.entry-row,
.runtime-row,
.entry-row__right {
  display: flex;
  align-items: center;
}

.entry-row,
.runtime-row {
  justify-content: space-between;
  min-height: 92rpx;
  color: var(--wot-color-content, var(--uni-text-color));
  border-bottom: 1rpx solid var(--wot-color-border-light, var(--uni-border-color));
}

.entry-row__right {
  gap: 14rpx;
}

.entry-row:last-child,
.runtime-row:last-child {
  border-bottom: 0;
}

.runtime-card {
  padding-top: 26rpx;
  padding-bottom: 26rpx;
}

.runtime-card__title {
  margin-bottom: 12rpx;
}

.runtime-row text:last-child {
  max-width: 470rpx;
  overflow-wrap: anywhere;
  text-align: right;
}
</style>
