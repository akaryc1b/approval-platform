<script lang="ts" setup>
import type {
  ConsistencyCheck,
  ConsistencyCheckPage,
  ConsistencyCheckStatus,
  ConsistencyCheckType,
  ConsistencyFinding,
  ConsistencyFindingPage,
  ConsistencySeverity,
} from '#/api/approval/consistency';

import { computed, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElEmpty,
  ElMessage,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import {
  findConsistencyChecks,
  findConsistencyFindings,
  runConsistencyCheck,
} from '#/api/approval/consistency';

const CHECK_PAGE_SIZE = 20;
const FINDING_PAGE_SIZE = 100;

const checkLoading = ref(false);
const findingLoading = ref(false);
const running = ref(false);
const checkPageNumber = ref(1);
const findingPageNumber = ref(1);
const statusFilter = ref<ConsistencyCheckStatus>();
const severityFilter = ref<ConsistencySeverity>();
const typeFilter = ref<ConsistencyCheckType>();
const checks = ref<ConsistencyCheckPage>(emptyCheckPage());
const findings = ref<ConsistencyFindingPage>(emptyFindingPage());
const selectedCheck = ref<ConsistencyCheck>();
const selectedFinding = ref<ConsistencyFinding>();

const criticalCount = computed(() => findings.value.items.filter(
  item => item.severity === 'CRITICAL',
).length);
const errorCount = computed(() => findings.value.items.filter(
  item => item.severity === 'ERROR',
).length);
const warningCount = computed(() => findings.value.items.filter(
  item => item.severity === 'WARNING',
).length);

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  month: '2-digit',
  second: '2-digit',
  year: 'numeric',
});

const checkTypeLabels: Record<ConsistencyCheckType, string> = {
  ATTACHMENT_REFERENCE: '附件引用',
  AUDIT_BUSINESS_EVIDENCE: '审计与业务证据',
  COLLABORATION_POLICY: '协作策略',
  COMMENT_REVISION: '评论修订',
  DELEGATION_EVIDENCE: '代理证据',
  HANDOVER_EVIDENCE: '交接证据',
  INSTANCE_TASK_STATE: '实例与任务状态',
  NOTIFICATION_DELIVERY: '通知投递',
};

function emptyCheckPage(): ConsistencyCheckPage {
  return { hasMore: false, items: [], limit: CHECK_PAGE_SIZE, offset: 0, total: 0 };
}

function emptyFindingPage(): ConsistencyFindingPage {
  return { hasMore: false, items: [], limit: FINDING_PAGE_SIZE, offset: 0, total: 0 };
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '一致性检查请求失败';
}

function formatDate(value?: string) {
  return value ? dateFormatter.format(new Date(value)) : '-';
}

function statusType(status: ConsistencyCheckStatus) {
  if (status === 'COMPLETED') return 'success';
  if (status === 'FAILED') return 'danger';
  return 'warning';
}

function statusLabel(status: ConsistencyCheckStatus) {
  return { COMPLETED: '已完成', FAILED: '失败', RUNNING: '执行中' }[status];
}

function severityType(severity: ConsistencySeverity) {
  if (severity === 'CRITICAL') return 'danger';
  if (severity === 'ERROR') return 'warning';
  return 'info';
}

function severityLabel(severity: ConsistencySeverity) {
  return { CRITICAL: '严重', ERROR: '错误', WARNING: '警告' }[severity];
}

function typeLabel(type: ConsistencyCheckType) {
  return checkTypeLabels[type];
}

function detailsEntries(finding: ConsistencyFinding) {
  return Object.entries(finding.details).sort(([left], [right]) => left.localeCompare(right));
}

async function loadChecks(targetPage = checkPageNumber.value) {
  checkLoading.value = true;
  try {
    checks.value = await findConsistencyChecks(
      statusFilter.value,
      CHECK_PAGE_SIZE,
      (targetPage - 1) * CHECK_PAGE_SIZE,
    );
    checkPageNumber.value = targetPage;
    if (!selectedCheck.value && checks.value.items.length) {
      await selectCheck(checks.value.items[0]);
    }
  } catch (error) {
    checks.value = emptyCheckPage();
    ElMessage.error(errorMessage(error));
  } finally {
    checkLoading.value = false;
  }
}

async function loadFindings(targetPage = findingPageNumber.value) {
  if (!selectedCheck.value) {
    findings.value = emptyFindingPage();
    return;
  }
  findingLoading.value = true;
  try {
    findings.value = await findConsistencyFindings(
      selectedCheck.value.checkId,
      typeFilter.value,
      severityFilter.value,
      FINDING_PAGE_SIZE,
      (targetPage - 1) * FINDING_PAGE_SIZE,
    );
    findingPageNumber.value = targetPage;
  } catch (error) {
    findings.value = emptyFindingPage();
    ElMessage.error(errorMessage(error));
  } finally {
    findingLoading.value = false;
  }
}

async function selectCheck(check: ConsistencyCheck) {
  selectedCheck.value = check;
  findingPageNumber.value = 1;
  severityFilter.value = undefined;
  typeFilter.value = undefined;
  await loadFindings(1);
}

async function executeCheck() {
  running.value = true;
  try {
    const result = await runConsistencyCheck();
    ElMessage.success(`检测完成，发现 ${result.findingCount} 项一致性问题`);
    selectedCheck.value = result;
    statusFilter.value = undefined;
    await loadChecks(1);
    await selectCheck(result);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    running.value = false;
  }
}

async function checkFilterChanged() {
  selectedCheck.value = undefined;
  findings.value = emptyFindingPage();
  await loadChecks(1);
}

async function findingFilterChanged() {
  await loadFindings(1);
}

loadChecks();
</script>

<template>
  <Page
    title="运维一致性检查"
    description="只检测平台自有审批投影和证据关系，不直接读取或修改 Flowable 表，也不会自动修改责任人、审批结论或历史证据。"
  >
    <div class="operations-page">
      <ElAlert
        :closable="false"
        show-icon
        title="检测结果仅用于诊断。任何后续重放或修复都必须通过现有应用服务、端口或 Engine SPI，并单独产生审计事件。"
        type="warning"
      />

      <section class="summary-grid">
        <ElCard shadow="never">
          <span>当前检查 Findings</span>
          <strong>{{ findings.total }}</strong>
        </ElCard>
        <ElCard shadow="never">
          <span>严重</span>
          <strong class="danger">{{ criticalCount }}</strong>
        </ElCard>
        <ElCard shadow="never">
          <span>错误</span>
          <strong class="warning">{{ errorCount }}</strong>
        </ElCard>
        <ElCard shadow="never">
          <span>警告</span>
          <strong>{{ warningCount }}</strong>
        </ElCard>
      </section>

      <ElCard shadow="never">
        <template #header>
          <div class="header-row">
            <div>
              <strong>检查历史</strong>
              <span>每次运行只扫描当前租户的平台自有表</span>
            </div>
            <div class="actions">
              <ElSelect
                v-model="statusFilter"
                clearable
                placeholder="全部状态"
                @change="checkFilterChanged"
              >
                <ElOption label="执行中" value="RUNNING" />
                <ElOption label="已完成" value="COMPLETED" />
                <ElOption label="失败" value="FAILED" />
              </ElSelect>
              <ElButton :loading="checkLoading" @click="loadChecks()">刷新</ElButton>
              <ElButton :loading="running" type="primary" @click="executeCheck">
                运行 detect-only 检查
              </ElButton>
            </div>
          </div>
        </template>

        <ElTable
          v-if="checks.items.length"
          v-loading="checkLoading"
          :data="checks.items"
          highlight-current-row
          row-key="checkId"
          @current-change="selectCheck"
        >
          <ElTableColumn label="开始时间" min-width="180">
            <template #default="scope">{{ formatDate(scope.row.startedAt) }}</template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="110">
            <template #default="scope">
              <ElTag :type="statusType(scope.row.status)">
                {{ statusLabel(scope.row.status) }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="Findings" prop="findingCount" width="110" />
          <ElTableColumn label="执行人" min-width="150" prop="requestedBy" />
          <ElTableColumn label="Request ID" min-width="210" prop="requestId" />
          <ElTableColumn label="错误" min-width="200">
            <template #default="scope">
              {{ scope.row.errorCode || scope.row.errorMessage || '-' }}
            </template>
          </ElTableColumn>
        </ElTable>
        <ElEmpty v-else :description="checkLoading ? '正在读取' : '尚未运行一致性检查'" />
        <ElPagination
          v-if="checks.total > CHECK_PAGE_SIZE"
          v-model:current-page="checkPageNumber"
          class="pagination"
          :page-size="CHECK_PAGE_SIZE"
          :total="checks.total"
          @current-change="loadChecks"
        />
      </ElCard>

      <ElCard shadow="never">
        <template #header>
          <div class="header-row">
            <div>
              <strong>一致性 Findings</strong>
              <span v-if="selectedCheck">检查 {{ selectedCheck.checkId }}</span>
            </div>
            <div class="actions">
              <ElSelect
                v-model="severityFilter"
                clearable
                placeholder="全部严重级别"
                @change="findingFilterChanged"
              >
                <ElOption label="严重" value="CRITICAL" />
                <ElOption label="错误" value="ERROR" />
                <ElOption label="警告" value="WARNING" />
              </ElSelect>
              <ElSelect
                v-model="typeFilter"
                clearable
                placeholder="全部检查类型"
                @change="findingFilterChanged"
              >
                <ElOption
                  v-for="(label, value) in checkTypeLabels"
                  :key="value"
                  :label="label"
                  :value="value"
                />
              </ElSelect>
              <ElButton
                :disabled="!selectedCheck"
                :loading="findingLoading"
                @click="loadFindings()"
              >刷新</ElButton>
            </div>
          </div>
        </template>

        <ElTable
          v-if="findings.items.length"
          v-loading="findingLoading"
          :data="findings.items"
          row-key="findingId"
        >
          <ElTableColumn label="级别" width="100">
            <template #default="scope">
              <ElTag :type="severityType(scope.row.severity)">
                {{ severityLabel(scope.row.severity) }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="检查类型" min-width="180">
            <template #default="scope">{{ typeLabel(scope.row.checkType) }}</template>
          </ElTableColumn>
          <ElTableColumn label="受影响聚合" min-width="260">
            <template #default="scope">
              <div>{{ scope.row.aggregateType }}</div>
              <code>{{ scope.row.aggregateId }}</code>
            </template>
          </ElTableColumn>
          <ElTableColumn label="检测时间" min-width="180">
            <template #default="scope">{{ formatDate(scope.row.detectedAt) }}</template>
          </ElTableColumn>
          <ElTableColumn label="问题代码" min-width="230">
            <template #default="scope">{{ scope.row.details.issueCode || '-' }}</template>
          </ElTableColumn>
          <ElTableColumn fixed="right" label="详情" width="90">
            <template #default="scope">
              <ElButton link type="primary" @click="selectedFinding = scope.row">
                查看
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <ElEmpty
          v-else
          :description="selectedCheck ? '当前条件下没有 Findings' : '请选择一次检查'"
        />
        <ElPagination
          v-if="findings.total > FINDING_PAGE_SIZE"
          v-model:current-page="findingPageNumber"
          class="pagination"
          :page-size="FINDING_PAGE_SIZE"
          :total="findings.total"
          @current-change="loadFindings"
        />
      </ElCard>
    </div>

    <ElDialog
      :model-value="Boolean(selectedFinding)"
      title="一致性 Finding 详情"
      width="760px"
      @close="selectedFinding = undefined"
    >
      <template v-if="selectedFinding">
        <ElDescriptions :column="2" border>
          <ElDescriptionsItem label="Finding ID">
            {{ selectedFinding.findingId }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="检查类型">
            {{ typeLabel(selectedFinding.checkType) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="严重级别">
            <ElTag :type="severityType(selectedFinding.severity)">
              {{ severityLabel(selectedFinding.severity) }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="检测时间">
            {{ formatDate(selectedFinding.detectedAt) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="聚合类型">
            {{ selectedFinding.aggregateType }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="聚合 ID">
            {{ selectedFinding.aggregateId }}
          </ElDescriptionsItem>
        </ElDescriptions>
        <div class="detail-section">
          <strong>检测详情</strong>
          <div class="detail-list">
            <div v-for="[key, value] in detailsEntries(selectedFinding)" :key="key">
              <code>{{ key }}</code>
              <span>{{ value }}</span>
            </div>
          </div>
        </div>
        <ElAlert
          class="suggestion"
          :closable="false"
          :title="selectedFinding.suggestedAction"
          type="info"
        />
        <ElAlert
          :closable="false"
          title="当前版本只检测，不提供自动修复。"
          type="warning"
        />
      </template>
    </ElDialog>
  </Page>
</template>

<style scoped>
.operations-page {
  display: grid;
  gap: 16px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(160px, 1fr));
  gap: 16px;
}

.summary-grid :deep(.el-card__body) {
  display: grid;
  gap: 8px;
}

.summary-grid span,
.header-row span {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.summary-grid strong {
  font-size: 30px;
}

.danger {
  color: var(--el-color-danger);
}

.warning {
  color: var(--el-color-warning);
}

.header-row,
.header-row > div,
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-row {
  justify-content: space-between;
}

.header-row > div:first-child {
  align-items: flex-start;
  flex-direction: column;
  gap: 4px;
}

.actions :deep(.el-select) {
  min-width: 160px;
}

.pagination {
  justify-content: flex-end;
  margin-top: 16px;
}

.detail-section {
  display: grid;
  gap: 12px;
  margin: 20px 0;
}

.detail-list {
  display: grid;
  gap: 8px;
}

.detail-list > div {
  display: grid;
  grid-template-columns: minmax(160px, 0.35fr) minmax(0, 1fr);
  gap: 12px;
  padding: 9px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}

.detail-list span {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.suggestion {
  margin-bottom: 12px;
}

code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  overflow-wrap: anywhere;
}

@media (max-width: 1000px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(160px, 1fr));
  }

  .header-row {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
