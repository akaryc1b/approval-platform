<script lang="ts" setup>
import type {
  AuditExportFormat,
  AuditFilters,
  AuditIntegrityReport,
  AuditPage,
  AuditRecord,
} from '#/api/approval/audit';

import { computed, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
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
  exportAuditEvents,
  findAuditEvents,
  verifyAuditIntegrity,
} from '#/api/approval/audit';

const PAGE_SIZE = 50;
const now = new Date();
const sevenDaysAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);

const loading = ref(false);
const verifying = ref(false);
const exporting = ref(false);
const pageNumber = ref(1);
const page = ref<AuditPage>(emptyPage());
const selected = ref<AuditRecord>();
const integrity = ref<AuditIntegrityReport>();
const exportFormat = ref<AuditExportFormat>('CSV');
const maxRecords = ref(5000);
const range = ref<[Date, Date]>([sevenDaysAgo, now]);
const filters = reactive({
  action: '',
  aggregateId: '',
  aggregateType: '',
  operatorId: '',
  requestId: '',
  traceId: '',
});

const hasFilters = computed(() => Object.values(filters).some(value => value.trim()));

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  month: '2-digit',
  second: '2-digit',
  year: 'numeric',
});

function emptyPage(): AuditPage {
  return { hasMore: false, items: [], limit: PAGE_SIZE, offset: 0, total: 0 };
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '审计请求失败';
}

function formatDate(value: string) {
  return dateFormatter.format(new Date(value));
}

function abbreviate(value: string, maxLength = 16) {
  return value.length <= maxLength ? value : `${value.slice(0, maxLength)}…`;
}

function currentFilters(): AuditFilters {
  const [occurredFrom, occurredTo] = range.value;
  return {
    action: filters.action.trim() || undefined,
    aggregateId: filters.aggregateId.trim() || undefined,
    aggregateType: filters.aggregateType.trim() || undefined,
    occurredFrom: occurredFrom.toISOString(),
    occurredTo: occurredTo.toISOString(),
    operatorId: filters.operatorId.trim() || undefined,
    requestId: filters.requestId.trim() || undefined,
    traceId: filters.traceId.trim() || undefined,
  };
}

async function loadAudit(targetPage = pageNumber.value) {
  if (!range.value?.[0] || !range.value?.[1]) {
    ElMessage.warning('请选择审计时间范围');
    return;
  }
  loading.value = true;
  try {
    const result = await findAuditEvents(
      currentFilters(),
      PAGE_SIZE,
      (targetPage - 1) * PAGE_SIZE,
    );
    page.value = result;
    pageNumber.value = targetPage;
  } catch (error) {
    page.value = emptyPage();
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

async function search() {
  integrity.value = undefined;
  await loadAudit(1);
}

function reset() {
  Object.assign(filters, {
    action: '',
    aggregateId: '',
    aggregateType: '',
    operatorId: '',
    requestId: '',
    traceId: '',
  });
  range.value = [new Date(Date.now() - 7 * 24 * 60 * 60 * 1000), new Date()];
  integrity.value = undefined;
  loadAudit(1);
}

async function verify() {
  verifying.value = true;
  try {
    const filters = currentFilters();
    integrity.value = await verifyAuditIntegrity({
      occurredFrom: filters.occurredFrom,
      occurredTo: filters.occurredTo,
    });
    ElMessage.success(
      integrity.value.valid ? '审计链完整性验证通过' : '发现审计链完整性异常',
    );
    await loadAudit(1);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    verifying.value = false;
  }
}

async function exportRecords() {
  exporting.value = true;
  try {
    await exportAuditEvents(currentFilters(), exportFormat.value, maxRecords.value);
    ElMessage.success('审计导出已生成，导出操作本身已记录审计事件');
    await loadAudit(1);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    exporting.value = false;
  }
}

function attributeEntries(record: AuditRecord) {
  return Object.entries(record.attributes).sort(([left], [right]) =>
    left.localeCompare(right),
  );
}

loadAudit();
</script>

<template>
  <Page
    title="审计治理"
    description="按租户和服务端权限查询版本化审计事件，验证逐租户哈希链完整性，并生成有边界、已脱敏的结构化导出。"
  >
    <div class="audit-page">
      <ElAlert
        :closable="false"
        show-icon
        title="哈希链用于发现平台审计记录被修改或断链，不代表法律级不可抵赖。查询最长 31 天，单次导出最多 10,000 条。"
        type="warning"
      />

      <ElCard shadow="never">
        <template #header><strong>查询条件</strong></template>
        <ElForm label-position="top">
          <div class="filter-grid">
            <ElFormItem class="range-field" label="发生时间" required>
              <ElDatePicker
                v-model="range"
                end-placeholder="结束时间"
                range-separator="至"
                start-placeholder="开始时间"
                type="datetimerange"
                value-format="x"
              />
            </ElFormItem>
            <ElFormItem label="操作人">
              <ElInput v-model="filters.operatorId" clearable placeholder="operator ID" />
            </ElFormItem>
            <ElFormItem label="Action">
              <ElInput v-model="filters.action" clearable placeholder="如 TASK_APPROVED" />
            </ElFormItem>
            <ElFormItem label="聚合类型">
              <ElInput
                v-model="filters.aggregateType"
                clearable
                placeholder="如 APPROVAL_INSTANCE"
              />
            </ElFormItem>
            <ElFormItem label="聚合 ID">
              <ElInput v-model="filters.aggregateId" clearable />
            </ElFormItem>
            <ElFormItem label="Request ID">
              <ElInput v-model="filters.requestId" clearable />
            </ElFormItem>
            <ElFormItem label="Trace ID">
              <ElInput v-model="filters.traceId" clearable />
            </ElFormItem>
          </div>
          <div class="toolbar">
            <div class="toolbar-actions">
              <ElButton :loading="loading" type="primary" @click="search">查询</ElButton>
              <ElButton :disabled="!hasFilters && page.total === 0" @click="reset">重置</ElButton>
              <ElButton :loading="verifying" type="warning" @click="verify">
                验证完整性
              </ElButton>
            </div>
            <div class="export-actions">
              <ElSelect v-model="exportFormat" class="format-select">
                <ElOption label="CSV" value="CSV" />
                <ElOption label="JSON" value="JSON" />
              </ElSelect>
              <ElInput
                v-model.number="maxRecords"
                class="record-limit"
                max="10000"
                min="1"
                type="number"
              />
              <ElButton :loading="exporting" @click="exportRecords">服务端导出</ElButton>
            </div>
          </div>
        </ElForm>
      </ElCard>

      <ElAlert
        v-if="integrity"
        :closable="false"
        show-icon
        :title="
          integrity.valid
            ? `完整性通过：检查 ${integrity.checkedCount} 条，链尾序号 ${integrity.chainStateSequence}`
            : `完整性异常：${integrity.failureCode || '未知异常'}，首个异常序号 ${integrity.firstInvalidSequence || '-'}`
        "
        :type="integrity.valid ? 'success' : 'error'"
      >
        <template #default>
          <div class="integrity-detail">
            <span>{{ integrity.assuranceStatement }}</span>
            <code>{{ integrity.chainStateHash }}</code>
          </div>
        </template>
      </ElAlert>

      <ElCard shadow="never">
        <template #header>
          <div class="table-header">
            <strong>审计事件</strong>
            <span>共 {{ page.total }} 条</span>
          </div>
        </template>
        <ElTable
          v-if="page.items.length"
          v-loading="loading"
          :data="page.items"
          row-key="eventId"
          @row-click="selected = $event"
        >
          <ElTableColumn label="序号" prop="tenantSequence" width="90" />
          <ElTableColumn label="时间" min-width="170">
            <template #default="scope">{{ formatDate(scope.row.occurredAt) }}</template>
          </ElTableColumn>
          <ElTableColumn label="Action" min-width="210" prop="action" />
          <ElTableColumn label="契约" min-width="220">
            <template #default="scope">
              <ElTag effect="plain">{{ scope.row.schemaName }} v{{ scope.row.schemaVersion }}</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作人" min-width="150" prop="operatorId" />
          <ElTableColumn label="聚合" min-width="210">
            <template #default="scope">
              <div>{{ scope.row.aggregateType }}</div>
              <code>{{ abbreviate(scope.row.aggregateId, 24) }}</code>
            </template>
          </ElTableColumn>
          <ElTableColumn label="Request / Trace" min-width="190">
            <template #default="scope">
              <code>{{ abbreviate(scope.row.requestId) }}</code>
              <div v-if="scope.row.traceId">
                <code>{{ abbreviate(scope.row.traceId) }}</code>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn fixed="right" label="详情" width="90">
            <template #default="scope">
              <ElButton link type="primary" @click.stop="selected = scope.row">查看</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <ElEmpty v-else :description="loading ? '正在查询' : '当前条件下没有审计事件'" />
        <ElPagination
          v-if="page.total > PAGE_SIZE"
          v-model:current-page="pageNumber"
          class="pagination"
          :page-size="PAGE_SIZE"
          :total="page.total"
          @current-change="loadAudit"
        />
      </ElCard>
    </div>

    <ElDialog
      :model-value="Boolean(selected)"
      title="审计事件详情"
      width="820px"
      @close="selected = undefined"
    >
      <ElDescriptions v-if="selected" :column="2" border>
        <ElDescriptionsItem label="Event ID">{{ selected.eventId }}</ElDescriptionsItem>
        <ElDescriptionsItem label="租户序号">{{ selected.tenantSequence }}</ElDescriptionsItem>
        <ElDescriptionsItem label="Action">{{ selected.action }}</ElDescriptionsItem>
        <ElDescriptionsItem label="发生时间">{{ formatDate(selected.occurredAt) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="Schema">
          {{ selected.schemaName }} v{{ selected.schemaVersion }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="操作人">{{ selected.operatorId }}</ElDescriptionsItem>
        <ElDescriptionsItem label="聚合类型">{{ selected.aggregateType }}</ElDescriptionsItem>
        <ElDescriptionsItem label="聚合 ID">{{ selected.aggregateId }}</ElDescriptionsItem>
        <ElDescriptionsItem label="Request ID">{{ selected.requestId }}</ElDescriptionsItem>
        <ElDescriptionsItem label="Trace ID">{{ selected.traceId || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem :span="2" label="Previous hash">
          <code>{{ selected.previousHash }}</code>
        </ElDescriptionsItem>
        <ElDescriptionsItem :span="2" label="Payload hash">
          <code>{{ selected.payloadHash }}</code>
        </ElDescriptionsItem>
        <ElDescriptionsItem :span="2" label="Current hash">
          <code>{{ selected.currentHash }}</code>
        </ElDescriptionsItem>
      </ElDescriptions>
      <div v-if="selected" class="attributes-panel">
        <strong>规范化属性（敏感字段已由服务端脱敏）</strong>
        <div v-if="attributeEntries(selected).length" class="attribute-list">
          <div v-for="[key, value] in attributeEntries(selected)" :key="key">
            <code>{{ key }}</code>
            <span>{{ value }}</span>
          </div>
        </div>
        <ElEmpty v-else description="无附加属性" :image-size="60" />
      </div>
    </ElDialog>
  </Page>
</template>

<style scoped>
.audit-page {
  display: grid;
  gap: 16px;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 16px;
}

.range-field {
  grid-column: span 2;
}

.range-field :deep(.el-date-editor) {
  width: 100%;
}

.toolbar,
.toolbar-actions,
.export-actions,
.table-header,
.integrity-detail {
  display: flex;
  align-items: center;
  gap: 10px;
}

.toolbar,
.table-header,
.integrity-detail {
  justify-content: space-between;
}

.format-select {
  width: 100px;
}

.record-limit {
  width: 130px;
}

.table-header span,
.integrity-detail span {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.pagination {
  justify-content: flex-end;
  margin-top: 16px;
}

.attributes-panel {
  display: grid;
  gap: 12px;
  margin-top: 20px;
}

.attribute-list {
  display: grid;
  gap: 8px;
}

.attribute-list > div {
  display: grid;
  grid-template-columns: minmax(140px, 0.35fr) minmax(0, 1fr);
  gap: 12px;
  padding: 9px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}

.attribute-list span {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  overflow-wrap: anywhere;
}

@media (max-width: 1000px) {
  .filter-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
