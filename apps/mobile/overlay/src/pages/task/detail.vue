<script lang="ts" setup>
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

onLoad((query) => {
  taskId.value = String(query?.id || '')
})

function submitAction(action: 'approve' | 'reject' | 'transfer') {
  const labels = {
    approve: '同意',
    reject: '驳回',
    transfer: '转办',
  }
  uni.showToast({
    title: `${labels[action]}接口将在纵向业务链路中接入`,
    icon: 'none',
  })
}
</script>

<template>
  <view class="page">
    <view class="summary-card">
      <view class="summary-card__header">
        <view>
          <text class="eyebrow">采购付款审批</text>
          <view class="title">服务器采购付款申请</view>
        </view>
        <wd-tag type="primary">待我审批</wd-tag>
      </view>
      <view class="summary-grid">
        <view>
          <text>发起人</text>
          <strong>陈明 · 采购中心</strong>
        </view>
        <view>
          <text>付款金额</text>
          <strong>¥186,000.00</strong>
        </view>
        <view>
          <text>供应商</text>
          <strong>星云计算科技</strong>
        </view>
        <view>
          <text>任务编号</text>
          <strong>{{ taskId || 'preview-task' }}</strong>
        </view>
      </view>
    </view>

    <view class="risk-card">
      <view class="section-title">
        <text>AI 辅助检查</text>
        <wd-tag type="success" plain>建议人工确认</wd-tag>
      </view>
      <view class="risk-item">采购订单与付款金额一致</view>
      <view class="risk-item">附件包含合同、订单和发票</view>
      <view class="risk-item risk-item--warning">供应商账户近期发生变更</view>
    </view>

    <view class="timeline-card">
      <view class="section-title">审批进度</view>
      <view class="timeline-item timeline-item--done">
        <view class="timeline-dot" />
        <view>
          <strong>陈明发起申请</strong>
          <text>今天 09:12</text>
        </view>
      </view>
      <view class="timeline-item timeline-item--done">
        <view class="timeline-dot" />
        <view>
          <strong>采购负责人已同意</strong>
          <text>今天 09:28 · 同意采购付款</text>
        </view>
      </view>
      <view class="timeline-item">
        <view class="timeline-dot" />
        <view>
          <strong>等待你审批</strong>
          <text>已等待 18 分钟</text>
        </view>
      </view>
    </view>

    <view class="opinion-card">
      <view class="section-title">审批意见</view>
      <wd-textarea
        v-model="opinion"
        :maxlength="500"
        clearable
        placeholder="填写审批意见，可由 AI 辅助生成"
        show-word-limit
      />
    </view>

    <view class="action-bar">
      <wd-button plain @click="submitAction('transfer')">转办</wd-button>
      <wd-button type="error" plain @click="submitAction('reject')">驳回</wd-button>
      <wd-button type="primary" @click="submitAction('approve')">同意</wd-button>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 170rpx;
  background: #f5f7fa;
}

.summary-card,
.risk-card,
.timeline-card,
.opinion-card {
  margin-bottom: 20rpx;
  padding: 28rpx;
  border-radius: 24rpx;
  background: #ffffff;
}

.summary-card__header,
.section-title,
.action-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.eyebrow,
.summary-grid text,
.timeline-item text {
  color: #8a8f99;
  font-size: 24rpx;
}

.title {
  margin-top: 10rpx;
  color: #111827;
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
.timeline-item > view:last-child {
  display: grid;
  gap: 8rpx;
}

.section-title {
  margin-bottom: 20rpx;
  color: #111827;
  font-size: 28rpx;
  font-weight: 700;
}

.risk-item {
  margin-top: 14rpx;
  padding: 18rpx;
  color: #166534;
  border-radius: 16rpx;
  background: #f0fdf4;
  font-size: 25rpx;
}

.risk-item--warning {
  color: #9a3412;
  background: #fff7ed;
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
  background: #dbe3ef;
}

.timeline-item:last-child::after {
  display: none;
}

.timeline-dot {
  z-index: 1;
  width: 20rpx;
  height: 20rpx;
  margin-top: 4rpx;
  border: 4rpx solid #ffffff;
  border-radius: 50%;
  background: #94a3b8;
  box-shadow: 0 0 0 2rpx #94a3b8;
}

.timeline-item--done .timeline-dot {
  background: #22c55e;
  box-shadow: 0 0 0 2rpx #22c55e;
}

.action-bar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  justify-content: flex-end;
  padding: 20rpx 24rpx calc(20rpx + env(safe-area-inset-bottom));
  background: #ffffff;
  box-shadow: 0 -8rpx 24rpx rgb(15 23 42 / 8%);
}
</style>
