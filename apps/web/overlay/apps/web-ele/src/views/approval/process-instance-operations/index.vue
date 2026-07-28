<script lang="ts" setup>
import type {
  MigrationAggregateStatus,
  MigrationInstanceItem,
  MigrationOperationsSummary,
  MigrationPlanDetail,
  MigrationPlanItem,
  MigrationPlanPage,
  MigrationPlanStatus,
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
} from 'element-plus';

import {
  findMigrationOperationInstances,
  findMigrationOperationPlan,
  findMigrationOperationPlans,
  findMigrationOperationsSummary,
} from '#/api/approval/process-instance-operations';

const loading = ref(false);
const detailLoading = ref(false);
const detailVisible = ref(false);
const selected = ref<MigrationPlanDetail>();
const instances = ref<MigrationInstanceItem[]>([]);
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
});
const page = ref<MigrationPlanPage>({
  hasMore: false,
  items: [],
  limit: 50,
  offset: 0,
  total: 0,
});
const filters = reactive({
  aggregateStatus: undefined as MigrationAggregateStatus | undefined,
  definitionKey: '',
  paused: undefined as boolean | undefined,
  planStatus: undefined as MigrationPlanStatus | undefined,
});

const planStatuses: MigrationPlanStatus[] = [
  'PROPOSED', 'AUTHORIZED', 'CONSUMED', 'EXPIRED', 'CANCELLED',
];
const aggregateStatuses: MigrationAggregateStatus[] = [
  'NOT_STARTED',
  'CANARY_PENDING',
  'CANARY_IN_PROGRESS',
  'BOUNDED_EXECUTION_IN_PROGRESS',
  'PAUSED',
  'UNRESOLVED',
  'TERMINAL_FAILURE_PRESENT',
  'PARTIALLY_COMPLETED',
  'COMPLETED_SUCCEEDED',
  'COMPLETED_WITH_TERMINAL_FAILURE',
  'INVALID_OR_INCOMPLETE_EVIDENCE',
];

function dateTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}

function shortHash(value?: string) {
  return value ? `${value.slice(0, 10)}…${value.slice(-8)}` : '-';
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '流程实例运维证据读取失败';
}

function tagType(value?: string) {
  if (!value) return 'info';
  if (value === 'COMPLETED_SUCCEEDED' || value === 'EXACTLY_COMPLETED') return 'success';
  if (value.includes('FAILURE') || value.includes('INVALID') || value.includes('CONFLICT')) {
    return 'danger';
  }
  if (value === 'PAUSED' || value.includes('UNKNOWN') || value.includes('RECONCIL')) {
    return 'warning';
  }
  return 'info';
}

async function load(offset = 0) {
  loading.value = true;
  try {
    [summary.value, page.value] = await Promise.all([
      findMigrationOperationsSummary(),
      findMigrationOperationPlans({
        aggregateStatus: filters.aggregateStatus,
        definitionKey: filters.definitionKey,
        limit: page.value.limit,
        offset,
        paused: filters.paused,
        planStatus: filters.planStatus,
      }),
    ]);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

async function showEvidence(plan: MigrationPlanItem) {
  detailVisible.value = true;
  detailLoading.value = true;
  selected.value = undefined;
  instances.value = [];
  try {
    const [detail, instancePage] = await Promise.all([
      findMigrationOperationPlan(plan.planId),
      findMigrationOperationInstances(plan.planId, 200, 0),
    ]);
    selected.value = detail;
    instances.value = instancePage.items;
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    detailLoading.value = false;
  }
}

function pageChanged(pageNumber: number) {
  void load((pageNumber - 1) * page.value.limit);
}

void load();
</script>

<template>
  <Page
    title="流程实例迁移运维"
    description="只读查看计划、Canary、Attempt、验证、对账、Kill Switch 与聚合完成证据。"
  >
    <div class="stack">
      <ElAlert
        :closable="false"
        show-icon
        title="本页面没有执行、重试、回滚、强制成功或对账命令；所有状态均来自服务端持久化证据。"
        type="info"
      />

      <ElCard shadow="never">
        <div class="summary-grid">
          <span>计划 <b>{{ summary.totalPlans }}</b></span>
          <span>已消费 <b>{{ summary.consumedPlans }}</b></span>
          <span>进行中 <b>{{ summary.activePlans }}</b></span>
          <span>暂停 <b>{{ summary.pausedPlans }}</b></span>
          <span>未解决 <b>{{ summary.unresolvedPlans }}</b></span>
          <span>已完成 <b>{{ summary.completedPlans }}</b></span>
          <span>Kill Switch 证据 <b>{{ summary.killSwitchObservedPlans }}</b></span>
        </div>
      </ElCard>

      <ElCard shadow="never">
        <template #header>
          <div class="row">
            <b>迁移计划证据</b>
            <ElButton :loading="loading" @click="load(0)">刷新</ElButton>
          </div>
        </template>
        <div class="filters">
          <ElInput
            v-model="filters.definitionKey"
            clearable
            placeholder="流程定义 Key"
            @keyup.enter="load(0)"
          />
          <ElSelect v-model="filters.planStatus" clearable placeholder="计划状态">
            <ElOption
              v-for="item in planStatuses"
              :key="item"
              :label="item"
              :value="item"
            />
          </ElSelect>
          <ElSelect v-model="filters.aggregateStatus" clearable placeholder="聚合状态">
            <ElOption
              v-for="item in aggregateStatuses"
              :key="item"
              :label="item"
              :value="item"
            />
          </ElSelect>
          <ElSelect v-model="filters.paused" clearable placeholder="暂停状态">
            <ElOption label="已暂停" :value="true" />
            <ElOption label="未暂停" :value="false" />
          </ElSelect>
          <ElButton type="primary" @click="load(0)">查询</ElButton>
        </div>

        <ElTable v-if="page.items.length" v-loading="loading" :data="page.items" row-key="planId">
          <ElTableColumn label="流程 / 版本" min-width="210">
            <template #default="scope">
              <b>{{ scope.row.definitionKey }}</b>
              <div class="muted">v{{ scope.row.sourceReleaseVersion }} → v{{ scope.row.targetReleaseVersion }}</div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="计划" width="130">
            <template #default="scope"><ElTag>{{ scope.row.planStatus }}</ElTag></template>
          </ElTableColumn>
          <ElTableColumn label="聚合" min-width="230">
            <template #default="scope">
              <ElTag :type="tagType(scope.row.aggregateStatus)">
                {{ scope.row.aggregateStatus ?? '尚未聚合' }}
              </ElTag>
              <div class="muted">rev {{ scope.row.aggregateRevision ?? '-' }}</div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="完成 / 失败 / 未解决" min-width="190">
            <template #default="scope">
              {{ scope.row.exactSuccessCount }} / {{ scope.row.terminalFailedCount }} /
              {{ scope.row.unresolvedCount }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="暂停原因" min-width="190" prop="pauseReason" />
          <ElTableColumn label="最近聚合" min-width="180">
            <template #default="scope">{{ dateTime(scope.row.latestAggregatedAt) }}</template>
          </ElTableColumn>
          <ElTableColumn fixed="right" label="查看" width="90">
            <template #default="scope">
              <ElButton link type="primary" @click="showEvidence(scope.row)">证据</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <ElEmpty v-else :description="loading ? '正在读取' : '当前条件下没有迁移计划'" />
        <ElPagination
          v-if="page.total > page.limit"
          class="pagination"
          :current-page="Math.floor(page.offset / page.limit) + 1"
          :page-size="page.limit"
          :total="page.total"
          layout="prev, pager, next, total"
          @current-change="pageChanged"
        />
      </ElCard>
    </div>

    <ElDialog
      v-model="detailVisible"
      :title="`计划证据 · ${selected?.plan.planId ?? ''}`"
      width="1120px"
    >
      <div v-if="selected" class="detail-grid">
        <span>Plan Hash <b>{{ shortHash(selected.plan.planHash) }}</b></span>
        <span>Input Hash <b>{{ shortHash(selected.inputEvidenceHash) }}</b></span>
        <span>Aggregate Hash <b>{{ shortHash(selected.aggregateHash) }}</b></span>
        <span>Completion Hash <b>{{ shortHash(selected.completionEvidenceHash) }}</b></span>
        <span>Request <b>{{ selected.requestId }}</b></span>
        <span>Audit <b>{{ selected.auditReference }}</b></span>
      </div>
      <ElTable v-loading="detailLoading" :data="instances" row-key="approvalInstanceId">
        <ElTableColumn label="#" width="60" prop="sequenceNo" />
        <ElTableColumn label="Canary" width="90">
          <template #default="scope"><ElTag>{{ scope.row.canary ? 'YES' : 'NO' }}</ElTag></template>
        </ElTableColumn>
        <ElTableColumn label="实例" min-width="210" prop="approvalInstanceId" show-overflow-tooltip />
        <ElTableColumn label="Attempt" min-width="180">
          <template #default="scope">
            {{ scope.row.attemptStatus ?? 'UNPROVISIONED' }} · {{ scope.row.attemptNumber ?? '-' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="验证 / 对账" min-width="260">
          <template #default="scope">
            {{ scope.row.verificationClassification ?? '-' }} /
            {{ scope.row.reconciliationStatus ?? '-' }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="结果" min-width="180">
          <template #default="scope">
            <ElTag :type="scope.row.bindingConflict ? 'danger' : scope.row.exactCompletion ? 'success' : 'info'">
              {{ scope.row.bindingConflict ? 'BINDING_CONFLICT' : scope.row.exactCompletion ? 'EXACT_COMPLETION' : 'OPEN' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="最近证据" min-width="180">
          <template #default="scope">{{ dateTime(scope.row.latestEvidenceAt) }}</template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty v-if="!instances.length && !detailLoading" description="计划没有选定实例证据" />
    </ElDialog>
  </Page>
</template>

<style scoped>
.stack { display: grid; gap: 16px; }
.row, .filters, .summary-grid, .detail-grid { align-items: center; display: flex; flex-wrap: wrap; gap: 12px; }
.row { justify-content: space-between; }
.filters { margin-bottom: 16px; }
.filters .el-input, .filters .el-select { width: 210px; }
.summary-grid span, .detail-grid span { background: var(--el-fill-color-light); border-radius: 6px; padding: 8px 12px; }
.detail-grid { margin-bottom: 16px; }
.muted { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px; }
.pagination { justify-content: flex-end; margin-top: 16px; }
</style>
