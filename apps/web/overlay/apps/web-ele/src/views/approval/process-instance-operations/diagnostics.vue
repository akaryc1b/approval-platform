<script lang="ts" setup>
import type {
  MigrationDiagnosticInstancePage,
  MigrationFailureClass,
  MigrationInstanceDiagnostics,
  MigrationPlanDiagnostics,
  MigrationReconciliationState,
} from '#/api/approval/process-instance-operations';

import { reactive, ref } from 'vue';
import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElInput,
  ElMessage,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTimeline,
  ElTimelineItem,
} from 'element-plus';

import {
  findMigrationDiagnosticInstances,
  findMigrationInstanceDiagnostics,
  findMigrationPlanDiagnostics,
} from '#/api/approval/process-instance-operations';

const planId = ref('');
const loading = ref(false);
const instanceLoading = ref(false);
const timelineLoading = ref(false);
const errorState = ref('');
const diagnostics = ref<MigrationPlanDiagnostics>();
const instancePage = ref<MigrationDiagnosticInstancePage>({
  hasMore: false,
  items: [],
  page: 1,
  pageSize: 25,
  planId: '',
  total: 0,
  totalPages: 0,
});
const timelineVisible = ref(false);
const selectedInstance = ref<MigrationInstanceDiagnostics>();
const filters = reactive({
  failureClass: undefined as MigrationFailureClass | undefined,
  reconciliationState: undefined as MigrationReconciliationState | undefined,
  status: undefined as string | undefined,
});

const failureClasses: MigrationFailureClass[] = [
  'AMBIGUOUS_UNKNOWN',
  'VERIFICATION_MISMATCH',
  'BINDING_CONFLICT',
  'STALE_AUTHORITY',
  'TERMINAL_FAILURE',
  'RETRYABLE_FAILURE',
  'ENGINE_REJECTED',
  'PRE_DISPATCH_REJECTED',
  'UNCLASSIFIED',
  'NONE',
];
const reconciliationStates: MigrationReconciliationState[] = [
  'OPEN',
  'MANUAL_REVIEW_REQUIRED',
  'RESOLVED_SOURCE',
  'RESOLVED_TERMINAL',
  'NONE',
];
const attemptStatuses = [
  'UNPROVISIONED',
  'PENDING',
  'CLAIMED',
  'ENGINE_REQUESTED',
  'VERIFYING',
  'UNKNOWN',
  'RECONCILING',
  'SUCCEEDED',
  'BLOCKED_STALE',
  'FAILED_RETRYABLE',
  'FAILED_TERMINAL',
  'CANCELLED',
];

function isUuid(value: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
    value,
  );
}

function dateTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}

function shortHash(value?: string) {
  return value ? `${value.slice(0, 10)}…${value.slice(-8)}` : '-';
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '高级诊断证据读取失败';
}

function tagType(value?: string) {
  if (!value) return 'info';
  if (value.includes('SUCCEEDED') || value === 'APPLIED' || value === 'INACTIVE') {
    return 'success';
  }
  if (
    value.includes('FAIL') ||
    value.includes('CONFLICT') ||
    value.includes('REJECT') ||
    value === 'ACTIVE'
  ) {
    return 'danger';
  }
  if (
    value.includes('UNKNOWN') ||
    value.includes('RECONCIL') ||
    value.includes('MANUAL') ||
    value.includes('STALE')
  ) {
    return 'warning';
  }
  return 'info';
}

async function loadInstances(page = 1) {
  if (!diagnostics.value) return;
  instanceLoading.value = true;
  try {
    instancePage.value = await findMigrationDiagnosticInstances(planId.value.trim(), {
      failureClass: filters.failureClass,
      page,
      pageSize: instancePage.value.pageSize,
      reconciliationState: filters.reconciliationState,
      sort: 'LATEST_EVIDENCE_DESC',
      status: filters.status,
    });
  } catch (error) {
    errorState.value = errorMessage(error);
    ElMessage.error(errorState.value);
  } finally {
    instanceLoading.value = false;
  }
}

async function loadPlan() {
  const id = planId.value.trim();
  if (!isUuid(id)) {
    ElMessage.warning('请输入有效的迁移计划 UUID');
    return;
  }
  loading.value = true;
  errorState.value = '';
  diagnostics.value = undefined;
  instancePage.value = {
    hasMore: false,
    items: [],
    page: 1,
    pageSize: 25,
    planId: id,
    total: 0,
    totalPages: 0,
  };
  try {
    diagnostics.value = await findMigrationPlanDiagnostics(id);
    await loadInstances(1);
  } catch (error) {
    errorState.value = errorMessage(error);
    ElMessage.error(errorState.value);
  } finally {
    loading.value = false;
  }
}

async function showTimeline(instanceId: string) {
  timelineVisible.value = true;
  timelineLoading.value = true;
  selectedInstance.value = undefined;
  try {
    selectedInstance.value = await findMigrationInstanceDiagnostics(
      planId.value.trim(),
      instanceId,
    );
  } catch (error) {
    errorState.value = errorMessage(error);
    ElMessage.error(errorState.value);
  } finally {
    timelineLoading.value = false;
  }
}
</script>

<template>
  <Page
    title="迁移高级诊断"
    description="只读、租户隔离且脱敏的计划与实例诊断；不会产生迁移、重试或对账命令。"
  >
    <div class="stack">
      <ElAlert
        :closable="false"
        show-icon
        title="安全提示：本页面仅读取 V48 及以前的平台治理证据。Owner 只显示不可逆摘要，所有集合均有上限。"
        type="info"
      />

      <ElCard shadow="never">
        <div class="lookup-row">
          <ElInput
            v-model="planId"
            clearable
            maxlength="36"
            placeholder="迁移计划 UUID"
            @keyup.enter="loadPlan"
          />
          <ElButton :loading="loading" type="primary" @click="loadPlan">
            读取诊断
          </ElButton>
        </div>
      </ElCard>

      <ElAlert
        v-if="errorState"
        :closable="false"
        :title="errorState"
        show-icon
        type="error"
      />

      <template v-if="diagnostics">
        <ElCard shadow="never">
          <template #header><b>计划诊断摘要</b></template>
          <div class="summary-grid">
            <span>计划 <ElTag>{{ diagnostics.planStatus }}</ElTag></span>
            <span>聚合 <ElTag :type="tagType(diagnostics.aggregateStatus)">{{ diagnostics.aggregateStatus }}</ElTag></span>
            <span>选定 <b>{{ diagnostics.selectedCount }}</b></span>
            <span>精确完成 <b>{{ diagnostics.exactSuccessCount }}</b></span>
            <span>终态失败 <b>{{ diagnostics.terminalFailedCount }}</b></span>
            <span>未解决 <b>{{ diagnostics.unresolvedCount }}</b></span>
            <span>UNKNOWN <b>{{ diagnostics.unknownCount }}</b></span>
            <span>AMBIGUOUS <b>{{ diagnostics.ambiguousUnknownCount }}</b></span>
            <span>人工复核 <b>{{ diagnostics.manualReviewCount }}</b></span>
            <span>CAS 冲突 <b>{{ diagnostics.bindingConflictCount }}</b></span>
          </div>
        </ElCard>

        <div class="governance-grid">
          <ElCard shadow="never">
            <template #header><b>Canary 与有界编排</b></template>
            <p>Canary：<ElTag :type="tagType(diagnostics.canaryStatus)">{{ diagnostics.canaryStatus }}</ElTag></p>
            <p>实例：{{ diagnostics.canaryInstanceId ?? '-' }}</p>
            <p>编排：<ElTag :type="tagType(diagnostics.orchestrationStatus)">{{ diagnostics.orchestrationStatus }}</ElTag></p>
            <p>阶段：{{ diagnostics.orchestrationPhase ?? '-' }}</p>
            <p>单批上限：{{ diagnostics.orchestrationRequestedLimit ?? '-' }}</p>
            <p>最近事件：{{ diagnostics.latestOrchestrationEvent ?? '-' }}</p>
            <p>暂停原因：{{ diagnostics.orchestrationPauseReason ?? '-' }}</p>
          </ElCard>
          <ElCard shadow="never">
            <template #header><b>Kill Switch</b></template>
            <p><ElTag :type="tagType(diagnostics.killSwitchStatus)">{{ diagnostics.killSwitchStatus }}</ElTag></p>
            <p>允许派发：{{ diagnostics.dispatchAllowed ?? '-' }}</p>
            <p>观测时间：{{ dateTime(diagnostics.killSwitchObservedAt) }}</p>
            <p>当前视图没有修改 Kill Switch 的入口。</p>
          </ElCard>
          <ElCard shadow="never">
            <template #header><b>聚合与完成证据</b></template>
            <p>Aggregate：{{ shortHash(diagnostics.aggregateHash) }}</p>
            <p>Completion：{{ shortHash(diagnostics.completionEvidenceHash) }}</p>
            <p>完成状态：{{ diagnostics.completionStatus ?? '-' }}</p>
            <p>聚合时间：{{ dateTime(diagnostics.aggregatedAt) }}</p>
            <p>完成时间：{{ dateTime(diagnostics.completedAt) }}</p>
          </ElCard>
        </div>

        <ElCard shadow="never">
          <template #header>
            <div class="header-row">
              <b>实例诊断</b>
              <span class="muted">共 {{ instancePage.total }} 条，pageSize ≤ 100</span>
            </div>
          </template>
          <div class="filters">
            <ElSelect v-model="filters.status" clearable placeholder="Attempt 状态">
              <ElOption v-for="item in attemptStatuses" :key="item" :label="item" :value="item" />
            </ElSelect>
            <ElSelect v-model="filters.failureClass" clearable placeholder="Failure Class">
              <ElOption v-for="item in failureClasses" :key="item" :label="item" :value="item" />
            </ElSelect>
            <ElSelect
              v-model="filters.reconciliationState"
              clearable
              placeholder="Reconciliation"
            >
              <ElOption
                v-for="item in reconciliationStates"
                :key="item"
                :label="item"
                :value="item"
              />
            </ElSelect>
            <ElButton :loading="instanceLoading" @click="loadInstances(1)">应用只读过滤</ElButton>
          </div>

          <ElTable
            v-if="instancePage.items.length"
            v-loading="instanceLoading"
            :data="instancePage.items"
            row-key="approvalInstanceId"
          >
            <ElTableColumn label="#" prop="sequenceNo" width="60" />
            <ElTableColumn label="实例" min-width="210" prop="approvalInstanceId" show-overflow-tooltip />
            <ElTableColumn label="Attempt" min-width="170">
              <template #default="scope">
                <ElTag :type="tagType(scope.row.attemptStatus)">{{ scope.row.attemptStatus }}</ElTag>
                <div class="muted">#{{ scope.row.attemptNumber ?? '-' }}</div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="Failure Class" min-width="210">
              <template #default="scope">
                <ElTag :type="tagType(scope.row.failureClass)">{{ scope.row.failureClass }}</ElTag>
                <div class="muted">{{ scope.row.engineStableCode ?? '-' }}</div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="Ownership / Fence" min-width="240">
              <template #default="scope">
                {{ scope.row.leaseOwnerReference ?? '-' }} / {{ scope.row.fencingOwnerReference ?? '-' }}
                <div class="muted">lease {{ dateTime(scope.row.leaseUntil) }}</div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="验证 / 对账" min-width="270">
              <template #default="scope">
                {{ scope.row.verificationClassification ?? '-' }} /
                {{ scope.row.reconciliationState }}
                <div class="muted">{{ scope.row.reconciliationDisposition ?? '-' }}</div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="CAS" min-width="140">
              <template #default="scope">
                <ElTag :type="tagType(scope.row.bindingResult)">{{ scope.row.bindingResult }}</ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="最近证据" min-width="180">
              <template #default="scope">{{ dateTime(scope.row.latestEvidenceAt) }}</template>
            </ElTableColumn>
            <ElTableColumn fixed="right" label="查看" width="90">
              <template #default="scope">
                <ElButton link type="primary" @click="showTimeline(scope.row.approvalInstanceId)">
                  时间线
                </ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
          <ElEmpty
            v-else
            :description="instanceLoading ? '正在读取有界诊断' : '当前条件下没有实例诊断证据'"
          />
          <ElPagination
            v-if="instancePage.totalPages > 1"
            class="pagination"
            :current-page="instancePage.page"
            :page-size="instancePage.pageSize"
            :total="instancePage.total"
            layout="prev, pager, next, total"
            @current-change="loadInstances"
          />
        </ElCard>
      </template>

      <ElEmpty v-else-if="!loading && !errorState" description="输入租户内计划 UUID 读取高级诊断" />
    </div>

    <ElDialog
      v-model="timelineVisible"
      :title="`实例生命周期 · ${selectedInstance?.instance.approvalInstanceId ?? ''}`"
      width="760px"
    >
      <ElTimeline v-if="selectedInstance?.timeline.length" v-loading="timelineLoading">
        <ElTimelineItem
          v-for="event in selectedInstance.timeline"
          :key="`${event.order}-${event.happenedAt}`"
          :timestamp="dateTime(event.happenedAt)"
        >
          <b>{{ event.stage }}</b> · {{ event.state }}
          <div class="muted">Evidence {{ shortHash(event.evidenceHash) }}</div>
        </ElTimelineItem>
      </ElTimeline>
      <ElEmpty v-else :description="timelineLoading ? '正在读取' : '没有生命周期证据'" />
    </ElDialog>
  </Page>
</template>

<style scoped>
.stack { display: grid; gap: 16px; }
.lookup-row, .filters, .summary-grid, .header-row { align-items: center; display: flex; flex-wrap: wrap; gap: 12px; }
.lookup-row .el-input { max-width: 520px; }
.summary-grid span { background: var(--el-fill-color-light); border-radius: 6px; padding: 8px 12px; }
.governance-grid { display: grid; gap: 16px; grid-template-columns: repeat(3, minmax(0, 1fr)); }
.header-row { justify-content: space-between; }
.filters { margin-bottom: 16px; }
.filters .el-select { width: 220px; }
.muted { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px; }
.pagination { justify-content: flex-end; margin-top: 16px; }
@media (max-width: 960px) { .governance-grid { grid-template-columns: 1fr; } }
</style>
