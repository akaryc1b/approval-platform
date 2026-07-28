<script lang="ts" setup>
import type {
  MigrationInstanceItem,
  MigrationOperationsSummary,
  MigrationPlanItem,
  MigrationPlanPage,
} from '@/api/approval/process-instance-operations'

import {
  findMigrationOperationInstances,
  findMigrationOperationPlans,
  findMigrationOperationsSummary,
} from '@/api/approval/process-instance-operations'

defineOptions({
  name: 'ApprovalMigrationOperations',
})

definePage({
  style: {
    navigationBarTitleText: '流程实例迁移运维',
  },
})

const loading = ref(false)
const detailLoading = ref(false)
const loadError = ref('')
const selected = ref<MigrationPlanItem>()
const instances = ref<MigrationInstanceItem[]>([])
const summary = ref<MigrationOperationsSummary>({
  activePlans: 0,
  completedPlans: 0,
  consumedPlans: 0,
  killSwitchObservedPlans: 0,
  observedAt: '',
  pausedPlans: 0,
  tenantId: '',
  totalPlans: 0,
  unresolvedPlans: 0,
})
const page = ref<MigrationPlanPage>({
  hasMore: false,
  items: [],
  limit: 50,
  offset: 0,
  total: 0,
})

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '迁移运维证据读取失败'
}

function formatDate(value?: string) {
  if (!value)
    return '-'
  return new Date(value).toLocaleString('zh-CN')
}

function statusType(value?: string) {
  if (!value)
    return 'default'
  if (value === 'COMPLETED_SUCCEEDED')
    return 'success'
  if (value.includes('FAILURE') || value.includes('INVALID') || value.includes('CONFLICT'))
    return 'danger'
  if (value.includes('PAUSED') || value.includes('UNKNOWN') || value.includes('RECONCIL'))
    return 'warning'
  return 'primary'
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [nextSummary, nextPage] = await Promise.all([
      findMigrationOperationsSummary(),
      findMigrationOperationPlans(50, 0),
    ])
    summary.value = nextSummary
    page.value = nextPage
  }
  catch (error) {
    loadError.value = errorMessage(error)
  }
  finally {
    loading.value = false
  }
}

async function showPlan(plan: MigrationPlanItem) {
  selected.value = plan
  instances.value = []
  detailLoading.value = true
  try {
    const result = await findMigrationOperationInstances(plan.planId, 200, 0)
    instances.value = result.items
  }
  catch (error) {
    uni.showToast({ icon: 'none', title: errorMessage(error) })
  }
  finally {
    detailLoading.value = false
  }
}

onShow(load)
</script>

<template>
  <view class="page">
    <view class="notice">
      <text class="notice__title">只读运维视图</text>
      <text class="notice__text">
        页面不提供执行、重试、回滚、强制成功或对账命令。
      </text>
    </view>

    <view class="metric-grid">
      <view class="metric-card">
        <text>计划</text>
        <text class="metric-card__value">{{ summary.totalPlans }}</text>
      </view>
      <view class="metric-card">
        <text>进行中</text>
        <text class="metric-card__value">{{ summary.activePlans }}</text>
      </view>
      <view class="metric-card">
        <text>暂停</text>
        <text class="metric-card__value">{{ summary.pausedPlans }}</text>
      </view>
      <view class="metric-card">
        <text>未解决</text>
        <text class="metric-card__value">{{ summary.unresolvedPlans }}</text>
      </view>
    </view>

    <view class="section-title">
      <text>迁移计划证据</text>
      <wd-button size="small" plain :loading="loading" @click="load">
        刷新
      </wd-button>
    </view>

    <view v-if="loadError" class="state-card state-card--error">
      <text>{{ loadError }}</text>
      <wd-button size="small" plain @click="load">重新加载</wd-button>
    </view>
    <view v-else-if="loading" class="state-card">正在读取持久化证据...</view>
    <view v-else-if="page.items.length === 0" class="state-card">当前没有迁移计划</view>
    <view v-else class="plan-list">
      <view
        v-for="plan in page.items"
        :key="plan.planId"
        class="plan-card"
        @click="showPlan(plan)"
      >
        <view class="plan-card__header">
          <view>
            <text class="plan-card__title">{{ plan.definitionKey }}</text>
            <text class="plan-card__meta">
              v{{ plan.sourceReleaseVersion }} → v{{ plan.targetReleaseVersion }}
            </text>
          </view>
          <wd-tag :type="statusType(plan.aggregateStatus)" plain>
            {{ plan.aggregateStatus || plan.planStatus }}
          </wd-tag>
        </view>
        <view class="plan-card__counts">
          <text>成功 {{ plan.exactSuccessCount }}</text>
          <text>失败 {{ plan.terminalFailedCount }}</text>
          <text>未解决 {{ plan.unresolvedCount }}</text>
        </view>
        <text class="plan-card__meta">
          暂停原因：{{ plan.pauseReason }} · 最近聚合：{{ formatDate(plan.latestAggregatedAt) }}
        </text>
      </view>
    </view>

    <view v-if="selected" class="section-title">
      <text>实例证据 · {{ selected.definitionKey }}</text>
    </view>
    <view v-if="selected && detailLoading" class="state-card">正在读取实例证据...</view>
    <view v-else-if="selected && instances.length === 0" class="state-card">
      计划尚无实例执行证据
    </view>
    <view v-else-if="selected" class="instance-list">
      <view v-for="item in instances" :key="item.approvalInstanceId" class="instance-card">
        <view class="instance-card__header">
          <text>#{{ item.sequenceNo }} {{ item.canary ? 'Canary' : 'Bounded' }}</text>
          <wd-tag
            :type="item.bindingConflict ? 'danger' : item.exactCompletion ? 'success' : 'default'"
            plain
          >
            {{ item.bindingConflict ? '冲突' : item.exactCompletion ? '精确完成' : '未完成' }}
          </wd-tag>
        </view>
        <text class="plan-card__meta">{{ item.approvalInstanceId }}</text>
        <text class="plan-card__meta">
          Attempt：{{ item.attemptStatus || 'UNPROVISIONED' }} · {{ item.attemptNumber || '-' }}
        </text>
        <text class="plan-card__meta">
          验证：{{ item.verificationClassification || '-' }} · 对账：{{ item.reconciliationStatus || '-' }}
        </text>
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
.notice,
.metric-card,
.plan-card,
.instance-card,
.state-card {
  border-radius: 22rpx;
  background: var(--wot-color-white, var(--uni-bg-color));
  box-shadow: 0 8rpx 24rpx rgb(15 23 42 / 5%);
}
.notice { display: grid; gap: 10rpx; padding: 26rpx; }
.notice__title { font-size: 30rpx; font-weight: 700; }
.notice__text,
.plan-card__meta,
.state-card { color: var(--wot-color-content-secondary, var(--uni-text-color-grey)); font-size: 24rpx; }
.metric-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18rpx; margin-top: 20rpx; }
.metric-card { display: grid; gap: 10rpx; padding: 22rpx; }
.metric-card__value { font-size: 42rpx; font-weight: 700; }
.section-title,
.plan-card__header,
.instance-card__header,
.plan-card__counts { display: flex; align-items: center; justify-content: space-between; gap: 18rpx; }
.section-title { margin: 32rpx 4rpx 16rpx; font-size: 30rpx; font-weight: 700; }
.plan-list,
.instance-list { display: grid; gap: 16rpx; }
.plan-card,
.instance-card { display: grid; gap: 14rpx; padding: 24rpx; }
.plan-card__title { display: block; font-size: 29rpx; font-weight: 700; }
.plan-card__meta { display: block; margin-top: 6rpx; }
.plan-card__counts { justify-content: flex-start; font-size: 24rpx; }
.state-card { display: grid; gap: 18rpx; justify-items: center; padding: 28rpx; text-align: center; }
.state-card--error { color: var(--wot-color-danger, var(--uni-color-error)); }
</style>
