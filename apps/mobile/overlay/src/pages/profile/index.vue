<script lang="ts" setup>
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

const entries = [
  { title: '我发起的', value: '9' },
  { title: '我已审批', value: '48' },
  { title: '抄送我的', value: '7' },
  { title: '草稿箱', value: '2' },
  { title: '审批代理', value: '未启用' },
  { title: '关注的审批', value: '3' },
]

function showPending(title: string) {
  uni.showToast({
    title: `${title}将在审批工作台链路中接入`,
    icon: 'none',
  })
}
</script>

<template>
  <view class="page">
    <view class="profile-card">
      <view class="avatar">A</view>
      <view class="profile-card__main">
        <text class="profile-card__name">审批平台用户</text>
        <text class="profile-card__meta">连接器：{{ runtime.connector }}</text>
      </view>
      <wd-tag plain type="success">在线</wd-tag>
    </view>

    <view class="entry-card">
      <view
        v-for="entry in entries"
        :key="entry.title"
        class="entry-row"
        @click="showPending(entry.title)"
      >
        <text>{{ entry.title }}</text>
        <view class="entry-row__value">
          <text>{{ entry.value }}</text>
          <text>›</text>
        </view>
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
        <text>{{ runtime.tenantId || '由登录上下文提供' }}</text>
      </view>
      <view class="runtime-hint">
        页面不直接依赖 RuoYi、钉钉或飞书 SDK，平台差异由启动适配器处理。
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 160rpx;
  background: #f5f7fa;
}

.profile-card,
.entry-card,
.runtime-card {
  border-radius: 24rpx;
  background: #ffffff;
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
  color: #ffffff;
  border-radius: 26rpx;
  background: linear-gradient(135deg, #2563eb, #4f46e5);
  font-size: 34rpx;
  font-weight: 700;
}

.profile-card__main {
  display: grid;
  flex: 1;
  gap: 10rpx;
}

.profile-card__name,
.runtime-card__title {
  color: #111827;
  font-size: 30rpx;
  font-weight: 700;
}

.profile-card__meta,
.runtime-hint,
.runtime-row text:first-child,
.entry-row__value {
  color: #8a8f99;
  font-size: 24rpx;
}

.entry-card,
.runtime-card {
  margin-top: 24rpx;
  padding: 0 28rpx;
}

.entry-row,
.runtime-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 92rpx;
  border-bottom: 1rpx solid #eef1f5;
}

.entry-row:last-child,
.runtime-row:last-of-type {
  border-bottom: 0;
}

.entry-row__value {
  display: flex;
  gap: 16rpx;
}

.runtime-card {
  padding-top: 26rpx;
  padding-bottom: 26rpx;
}

.runtime-hint {
  margin-top: 20rpx;
  line-height: 1.7;
}
</style>
