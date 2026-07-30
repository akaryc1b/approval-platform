<script lang="ts" setup>
import type {
  MigrationDiagnosticInstancePage,
  MigrationFailureClass,
  MigrationInstanceDiagnostics,
  MigrationPlanDiagnostics,
  MigrationReconciliationState,
} from '@/api/approval/process-instance-operations'

import {
  findMigrationDiagnosticInstances,
  findMigrationInstanceDiagnostics,
  findMigrationPlanDiagnostics,
} from '@/api/approval/process-instance-operations'

defineOptions({
  name: 'ApprovalMigrationDiagnostics',
})

definePage({
  style: {
    navigationBarTitleText: '迁移高级诊断',
  },
})

const planId = ref('')
const loading = ref(false)
const loadError = ref('')
const diagnostics = ref<MigrationPlanDiagnostics>()
const instancePage = ref<MigrationDiagnosticInstancePage>({
  hasMore: false,
  items: [],
  page: 1,
  pageSize: 20,
  planId: '',
  total: 0,
  totalPages: 0,
})
const detailLoading = ref(false)
const selected = ref<MigrationInstanceDiagnostics>()
const failureClass = ref<MigrationFailureClass>()
const reconciliationState = ref<MigrationReconciliationState>()

const failureFilters: Array<{ label: string, value?: MigrationFailureClass }> = [
  { label: '全部' },
  { label: '模糊 UNKNOWN', value: 'AMBIGUOUS_UNKNOWN' },
  { label: '验证不一致', value: 'VERIFICATION_MISMATCH' },
  { label: 'CAS 冲突', value: 'BINDING_CONFLICT' },
  { label: '失效权限', value: 'STALE_AUTHORITY' },
  { label: '终态失败', value: 'TERMINAL_FAILURE' },
]

const reconciliationFilters: Array<{
  label: string
  value?: MigrationReconciliationState
}> = [
  { label: '全部对账' },
  { label: '待处理', value: 'OPEN' },
  { label: '人工复核', value: 'MANUAL_REVIEW_REQUIRED' },
  { label: '已确认源端', value: 'RESOLVED_SOURCE' },
  { label: '已确认终态', value: 'RESOLVED_TERMINAL' },
]

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '迁移高级诊断读取失败'
}

function formatDate(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-'
}

function shortHash(value?: string) {
  return value ? `${value.slice(0, 8)}…${value.slice(-6)}` : '-'
}

function statusType(value?: string) {
  if (!value)
    return 'default'
  if (value.includes('SUCCEEDED') || value === 'APPLIED' || value === 'INACTIVE')
    return 'success'
  if (value.includes('FAIL') || value.includes('CONFLICT') || value.includes('REJECT'))
    return 'danger'
  if (value.includes('UNKNOWN') || value.includes('MANUAL') || value.includes('STALE'))
    return 'warning'
  return 'primary'
}

function validPlanId(value: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
    value,
  )
}

async function loadInstances(page = 1) {
  if (!diagnostics.value)
    return
  loading.value = true
  try {
    instancePage.value = await findMigrationDiagnosticInstances(planId.value.trim(), {
      failureClass: failureClass.value,
      page,
      pageSize: instancePage.value.pageSize,
      reconciliationState: reconciliationState.value,
      sort: 'LATEST_EVIDENCE_DESC',
    })
  }
  catch (error) {
    loadError.value = errorMessage(error)
  }
  finally {
    loading.value = false
  }
}

async function loadPlan() {
  const id = planId.value.trim()
  if (!validPlanId(id)) {
    uni.showToast({ icon: 'none', title: '请输入有效的计划 UUID' })
    return
  }
  loading.value = true
  loadError.value = ''
  diagnostics.value = undefined
  selected.value = undefined
  try {
    diagnostics.value = await findMigrationPlanDiagnostics(id)
    await loadInstances(1)
  }
  catch (error) {
    loadError.value = errorMessage(error)
  }
  finally {
    loading.value = false
  }
}

async function applyFailure(value?: MigrationFailureClass) {
  failureClass.value = value
  await loadInstances(1)
}

async function applyReconciliation(value?: MigrationReconciliationState) {
  reconciliationState.value = value
  await loadInstances(1)
}

async function showInstance(instanceId: string) {
  detailLoading.value = true
  selected.value = undefined
  try {
    selected.value = await findMigrationInstanceDiagnostics(planId.value.trim(), instanceId)
  }
  catch (error) {
    uni.showToast({ icon: 'none', title: errorMessage(error) })
  }
  finally {
    detailLoading.value = false
  }
}
</script>

<template>
  <view class="page">
    <view class="notice">
      <text class="notice__title">只读高级诊断</text>
      <text class="notice__text">
        不提供迁移、重试、对账、Kill Switch 或 Feature Flag 修改入口；Owner 仅显示脱敏摘要。
      </text>
    </view>

    <view class="lookup-card">
      <wd-input
        v-model="planId"
        clearable
        :maxlength="36"
        placeholder="输入租户内迁移计划 UUID"
      />
      <wd-button block :loading="loading" @click="loadPlan">
        读取诊断
      </wd-button>
    </view>

    <view v-if="loadError" class="state-card state-card--error">
      {{ loadError }}
    </view>

    <template v-if="diagnostics">
      <view class="metric-grid">
        <view class="metric-card">
          <text>UNKNOWN</text>
          <text class="metric-card__value">{{ diagnostics.unknownCount }}</text>
        </view>
        <view class="metric-card">
          <text>AMBIGUOUS</text>
          <text class="metric-card__value">{{ diagnostics.ambiguousUnknownCount }}</text>
        </view>
        <view class="metric-card">
          <text>人工复核</text>
          <text class="metric-card__value">{{ diagnostics.manualReviewCount }}</text>
        </view>
        <view class="metric-card">
          <text>CAS 冲突</text>
          <text class="metric-card__value">{{ diagnostics.bindingConflictCount }}</text>
        </view>
      </view>

      <view class="governance-card">
        <view class="governance-row">
          <text>计划 / 聚合</text>
          <view class="tag-row">
            <wd-tag plain :type="statusType(diagnostics.planStatus)">{{ diagnostics.planStatus }}</wd-tag>
            <wd-tag plain :type="statusType(diagnostics.aggregateStatus)">{{ diagnostics.aggregateStatus }}</wd-tag>
          </view>
        </view>
        <view class="governance-row">
          <text>Canary</text>
          <wd-tag plain :type="statusType(diagnostics.canaryStatus)">{{ diagnostics.canaryStatus }}</wd-tag>
        </view>
        <view class="governance-row">
          <text>有界编排</text>
          <wd-tag plain :type="statusType(diagnostics.orchestrationStatus)">
            {{ diagnostics.orchestrationStatus }}
          </wd-tag>
        </view>
        <view class="governance-row">
          <text>Kill Switch</text>
          <wd-tag plain :type="statusType(diagnostics.killSwitchStatus)">
            {{ diagnostics.killSwitchStatus }}
          </wd-tag>
        </view>
        <text class="meta">
          最近事件：{{ diagnostics.latestOrchestrationEvent || '-' }} ·
          暂停原因：{{ diagnostics.orchestrationPauseReason || '-' }}
        </text>
      </view>

      <view class="section-title">Failure Class</view>
      <scroll-view class="chip-scroll" scroll-x>
        <view class="chip-row">
          <wd-button
            v-for="item in failureFilters"
            :key="item.label"
            size="small"
            :plain="failureClass !== item.value"
            @click="applyFailure(item.value)"
          >
            {{ item.label }}
          </wd-button>
        </view>
      </scroll-view>

      <view class="section-title">Reconciliation</view>
      <scroll-view class="chip-scroll" scroll-x>
        <view class="chip-row">
          <wd-button
            v-for="item in reconciliationFilters"
            :key="item.label"
            size="small"
            :plain="reconciliationState !== item.value"
            @click="applyReconciliation(item.value)"
          >
            {{ item.label }}
          </wd-button>
        </view>
      </scroll-view>

      <view class="section-title">
        实例诊断 {{ instancePage.total }} 条
      </view>
      <view v-if="loading && instancePage.items.length === 0" class="state-card">
        正在读取有界诊断...
      </view>
      <view v-else-if="instancePage.items.length === 0" class="state-card">
        当前条件下没有实例诊断证据
      </view>
      <view v-else class="instance-list">
        <view
          v-for="item in instancePage.items"
          :key="item.approvalInstanceId"
          class="instance-card"
          @click="showInstance(item.approvalInstanceId)"
        >
          <view class="instance-card__header">
            <text>#{{ item.sequenceNo }} {{ item.canary ? 'Canary' : 'Bounded' }}</text>
            <wd-tag plain :type="statusType(item.failureClass)">{{ item.failureClass }}</wd-tag>
          </view>
          <text class="instance-id">{{ item.approvalInstanceId }}</text>
          <view class="instance-grid">
            <text>Attempt</text><text>{{ item.attemptStatus }} #{{ item.attemptNumber || '-' }}</text>
            <text>验证</text><text>{{ item.verificationClassification || '-' }}</text>
            <text>对账</text><text>{{ item.reconciliationState }}</text>
            <text>CAS</text><text>{{ item.bindingResult }}</text>
            <text>Owner</text><text>{{ item.leaseOwnerReference || '-' }}</text>
            <text>Fence</text><text>{{ item.fencingOwnerReference || '-' }}</text>
            <text>最近证据</text><text>{{ formatDate(item.latestEvidenceAt) }}</text>
          </view>
        </view>
      </view>

      <view class="pagination-card">
        <wd-button
          size="small"
          plain
          :disabled="instancePage.page <= 1"
          @click="loadInstances(instancePage.page - 1)"
        >
          上一页
        </wd-button>
        <text>{{ instancePage.page }} / {{ Math.max(instancePage.totalPages, 1) }}</text>
        <wd-button
          size="small"
          plain
          :disabled="!instancePage.hasMore"
          @click="loadInstances(instancePage.page + 1)"
        >
          下一页
        </wd-button>
      </view>
    </template>

    <view v-if="detailLoading" class="state-card">正在读取生命周期...</view>
    <view v-else-if="selected" class="timeline-card">
      <view class="section-title">实例生命周期</view>
      <view v-for="event in selected.timeline" :key="`${event.order}-${event.happenedAt}`" class="timeline-row">
        <view class="timeline-dot" />
        <view class="timeline-main">
          <text class="timeline-title">{{ event.stage }} · {{ event.state }}</text>
          <text class="meta">{{ formatDate(event.happenedAt) }}</text>
          <text class="meta">Evidence {{ shortHash(event.evidenceHash) }}</text>
        </view>
      </view>
      <view v-if="selected.timeline.length === 0" class="state-card">没有生命周期证据</view>
    </view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; padding: 24rpx 24rpx 120rpx; background: var(--wot-color-bg, var(--uni-bg-color-grey)); }
.notice, .lookup-card, .metric-card, .governance-card, .instance-card, .pagination-card, .timeline-card, .state-card { border-radius: 22rpx; background: var(--wot-color-white, var(--uni-bg-color)); box-shadow: 0 8rpx 24rpx rgb(15 23 42 / 5%); }
.notice, .lookup-card, .governance-card, .timeline-card { display: grid; gap: 16rpx; padding: 24rpx; }
.notice__title, .section-title { font-size: 30rpx; font-weight: 700; }
.notice__text, .meta, .instance-id, .state-card { color: var(--wot-color-content-secondary, var(--uni-text-color-grey)); font-size: 23rpx; }
.metric-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16rpx; margin-top: 20rpx; }
.metric-card { display: grid; gap: 8rpx; padding: 22rpx; }
.metric-card__value { font-size: 42rpx; font-weight: 700; }
.governance-card { margin-top: 20rpx; }
.governance-row, .instance-card__header, .pagination-card, .tag-row { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; }
.section-title { margin: 30rpx 4rpx 16rpx; }
.chip-scroll { width: 100%; white-space: nowrap; }
.chip-row { display: inline-flex; gap: 12rpx; padding-bottom: 8rpx; }
.instance-list { display: grid; gap: 16rpx; }
.instance-card { display: grid; gap: 14rpx; padding: 24rpx; }
.instance-id { word-break: break-all; }
.instance-grid { display: grid; grid-template-columns: 150rpx minmax(0, 1fr); gap: 10rpx 16rpx; font-size: 24rpx; }
.pagination-card { margin-top: 18rpx; padding: 18rpx; }
.state-card { margin-top: 18rpx; padding: 28rpx; text-align: center; }
.state-card--error { color: var(--wot-color-danger, var(--uni-color-error)); }
.timeline-card { margin-top: 24rpx; }
.timeline-row { display: flex; gap: 16rpx; padding: 12rpx 0; }
.timeline-dot { width: 16rpx; height: 16rpx; margin-top: 8rpx; border-radius: 50%; background: var(--wot-color-theme, var(--uni-color-primary)); }
.timeline-main { display: grid; flex: 1; gap: 6rpx; }
.timeline-title { font-size: 25rpx; font-weight: 600; }
</style>
