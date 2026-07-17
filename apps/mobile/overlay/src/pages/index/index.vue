<script lang="ts" setup>
defineOptions({
  name: 'ApprovalHome',
})

definePage({
  type: 'home',
  style: {
    navigationBarTitleText: '审批工作台',
  },
})

const metrics = [
  { label: '待我审批', value: 12, tone: 'primary' },
  { label: '抄送未读', value: 2, tone: 'warning' },
  { label: '即将超时', value: 3, tone: 'danger' },
] as const

const tasks = [
  {
    id: 'purchase-payment-001',
    title: '服务器采购付款申请',
    applicant: '采购中心',
    elapsed: '18 分钟',
    status: '待审批',
  },
  {
    id: 'contract-seal-002',
    title: '研发外包合同用印',
    applicant: '研发中心',
    elapsed: '1 小时',
    status: '有风险',
  },
  {
    id: 'office-device-003',
    title: '办公设备采购申请',
    applicant: '行政中心',
    elapsed: '3 小时',
    status: '会签中',
  },
]

function openTask(id: string) {
  uni.navigateTo({
    url: `/pages/task/detail?id=${encodeURIComponent(id)}`,
  })
}

function openTaskList() {
  uni.navigateTo({ url: '/pages/task/list' })
}

function openInitiate() {
  uni.switchTab({ url: '/pages/initiate/index' })
}
</script>

<template>
  <view class="page">
    <view class="hero">
      <view>
        <text class="hero__eyebrow">今日需要你处理</text>
        <view class="hero__title">优先完成高风险和即将超时审批</view>
      </view>
      <wd-button size="small" type="primary" @click="openTaskList">
        全部待办
      </wd-button>
    </view>

    <view class="metric-grid">
      <view v-for="metric in metrics" :key="metric.label" class="metric-card">
        <text class="metric-card__label">{{ metric.label }}</text>
        <view class="metric-card__value">{{ metric.value }}</view>
        <wd-tag :type="metric.tone" plain>
          实时
        </wd-tag>
      </view>
    </view>

    <view class="section-title">
      <text>优先处理</text>
      <text class="section-title__hint">按 SLA 与风险排序</text>
    </view>

    <view class="task-list">
      <view
        v-for="task in tasks"
        :key="task.id"
        class="task-card"
        @click="openTask(task.id)"
      >
        <view class="task-card__main">
          <text class="task-card__title">{{ task.title }}</text>
          <text class="task-card__meta">
            {{ task.applicant }} · 已等待 {{ task.elapsed }}
          </text>
        </view>
        <wd-tag :type="task.status === '有风险' ? 'warning' : 'primary'">
          {{ task.status }}
        </wd-tag>
      </view>
    </view>

    <view class="section-title">
      <text>快捷操作</text>
    </view>
    <view class="actions">
      <wd-button block type="primary" @click="openInitiate">
        发起审批
      </wd-button>
      <wd-button block plain @click="openTaskList">
        查看待办
      </wd-button>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 28rpx 28rpx 160rpx;
  background: #f5f7fa;
}

.hero,
.section-title,
.task-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.hero {
  padding: 32rpx;
  color: #ffffff;
  border-radius: 28rpx;
  background: linear-gradient(135deg, #2563eb, #4f46e5);
}

.hero__eyebrow {
  font-size: 24rpx;
  opacity: 0.8;
}

.hero__title {
  max-width: 480rpx;
  margin-top: 12rpx;
  font-size: 34rpx;
  font-weight: 700;
  line-height: 1.4;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20rpx;
  margin-top: 24rpx;
}

.metric-card,
.task-card {
  padding: 24rpx;
  border-radius: 22rpx;
  background: #ffffff;
  box-shadow: 0 8rpx 24rpx rgb(15 23 42 / 5%);
}

.metric-card__label,
.task-card__meta,
.section-title__hint {
  color: #8a8f99;
  font-size: 24rpx;
}

.metric-card__value {
  margin: 12rpx 0;
  color: #111827;
  font-size: 48rpx;
  font-weight: 700;
}

.section-title {
  margin: 36rpx 4rpx 18rpx;
  color: #111827;
  font-size: 30rpx;
  font-weight: 700;
}

.task-list,
.actions {
  display: grid;
  gap: 18rpx;
}

.task-card__main {
  display: grid;
  flex: 1;
  gap: 10rpx;
}

.task-card__title {
  color: #111827;
  font-size: 29rpx;
  font-weight: 600;
}
</style>
