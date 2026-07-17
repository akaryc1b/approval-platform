<script lang="ts" setup>
defineOptions({
  name: 'ApprovalTaskList',
})

definePage({
  style: {
    navigationBarTitleText: '待我审批',
  },
})

const activeStatus = ref('pending')
const statuses = [
  { label: '待处理', value: 'pending' },
  { label: '即将超时', value: 'urgent' },
  { label: '高风险', value: 'risk' },
]

const tasks = [
  {
    id: 'purchase-payment-001',
    title: '服务器采购付款申请',
    applicant: '陈明 · 采购中心',
    summary: '付款金额 ¥186,000.00',
    status: 'pending',
    waiting: '18 分钟',
  },
  {
    id: 'contract-seal-002',
    title: '研发外包合同用印',
    applicant: '李然 · 研发中心',
    summary: 'AI 识别到 2 项条款风险',
    status: 'risk',
    waiting: '1 小时',
  },
  {
    id: 'office-device-003',
    title: '办公设备采购申请',
    applicant: '周琪 · 行政中心',
    summary: '采购金额 ¥32,600.00',
    status: 'urgent',
    waiting: '3 小时',
  },
]

const visibleTasks = computed(() => {
  if (activeStatus.value === 'pending') {
    return tasks
  }
  return tasks.filter(task => task.status === activeStatus.value)
})

function openTask(id: string) {
  uni.navigateTo({
    url: `/pages/task/detail?id=${encodeURIComponent(id)}`,
  })
}
</script>

<template>
  <view class="page">
    <view class="filters">
      <wd-button
        v-for="status in statuses"
        :key="status.value"
        :plain="activeStatus !== status.value"
        size="small"
        type="primary"
        @click="activeStatus = status.value"
      >
        {{ status.label }}
      </wd-button>
    </view>

    <view class="task-list">
      <view
        v-for="task in visibleTasks"
        :key="task.id"
        class="task-card"
        @click="openTask(task.id)"
      >
        <view class="task-card__header">
          <text class="task-card__title">{{ task.title }}</text>
          <wd-tag :type="task.status === 'risk' ? 'warning' : 'primary'">
            {{ task.status === 'risk' ? '高风险' : task.status === 'urgent' ? '即将超时' : '待审批' }}
          </wd-tag>
        </view>
        <text class="task-card__applicant">{{ task.applicant }}</text>
        <view class="task-card__footer">
          <text>{{ task.summary }}</text>
          <text>等待 {{ task.waiting }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  background: #f5f7fa;
}

.filters {
  display: flex;
  gap: 16rpx;
  padding: 8rpx 0 24rpx;
}

.task-list {
  display: grid;
  gap: 20rpx;
}

.task-card {
  display: grid;
  gap: 14rpx;
  padding: 28rpx;
  border-radius: 24rpx;
  background: #ffffff;
  box-shadow: 0 8rpx 24rpx rgb(15 23 42 / 5%);
}

.task-card__header,
.task-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.task-card__title {
  color: #111827;
  font-size: 30rpx;
  font-weight: 600;
}

.task-card__applicant,
.task-card__footer {
  color: #8a8f99;
  font-size: 24rpx;
}
</style>
