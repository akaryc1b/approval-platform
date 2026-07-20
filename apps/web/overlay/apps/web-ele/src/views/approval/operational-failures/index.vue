<script lang="ts" setup>
import type {
  FailureCategory,
  FailureKind,
  OperationalFailure,
  OperationalFailureAttempt,
  OperationalFailurePage,
} from '#/api/approval/operational-failures';

import { computed, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import {
  findOperationalFailureAttempts,
  findOperationalFailures,
  replayOperationalFailure,
  replayOperationalFailureBatch,
} from '#/api/approval/operational-failures';

const PAGE_SIZE = 50;

const loading = ref(false);
const replaying = ref(false);
const attemptLoading = ref(false);
const pageNumber = ref(1);
const categoryFilter = ref<FailureCategory>();
const kindFilter = ref<FailureKind>();
const connectorFilter = ref('');
const failures = ref<OperationalFailurePage>(emptyPage());
const selectedRows = ref<OperationalFailure[]>([]);
const activeFailure = ref<OperationalFailure>();
const attempts = ref<OperationalFailureAttempt[]>([]);

const replayableSelection = computed(() => selectedRows.value.filter(item => item.replayable));
const notificationCount = computed(() => failures.value.items.filter(
  item => item.category === 'NOTIFICATION_DELIVERY',
).length);
const outboxCount = computed(() => failures.value.items.filter(
  item => item.category === 'BUSINESS_OUTBOX',
).length);
const consistencyCount = computed(() => failures.value.items.filter(
  item => item.category === 'CONSISTENCY_CHECK',
).length);

const categoryLabels: Readonly<Record<string, string>> = {
  BUSINESS_OUTBOX: '业务回调 Outbox',
  CONSISTENCY_CHECK: '一致性检查',
  NOTIFICATION_DELIVERY: '通知投递',
};

const kindLabels: Readonly<Record<string, string>> = {
  BUSINESS_CALLBACK: '业务回调',
  CONNECTOR: '连接器',
  EMAIL: '邮件',
  INTERNAL: '平台内部',
};

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  month: '2-digit',
  second: '2-digit',
  year: 'numeric',
});

function emptyPage(): OperationalFailurePage {
  return { hasMore: false, items: [], limit: PAGE_SIZE, offset: 0, total: 0 };
}

function formatDate(value?: string) {
  return value ? dateFormatter.format(new Date(value)) : '-';
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '运维失败队列请求失败';
}

function categoryType(category: FailureCategory) {
  if (category === 'BUSINESS_OUTBOX') return 'warning';
  if (category === 'CONSISTENCY_CHECK') return 'info';
  return 'danger';
}

function statusType(status: string) {
  return status === 'DEAD' || status === 'DEAD_LETTER' || status === 'FAILED'
    ? 'danger'
    : 'warning';
}

async function loadFailures(targetPage = pageNumber.value) {
  loading.value = true;
  try {
    failures.value = await findOperationalFailures(
      categoryFilter.value,
      kindFilter.value,
      connectorFilter.value,
      PAGE_SIZE,
      (targetPage - 1) * PAGE_SIZE,
    );
    pageNumber.value = targetPage;
    selectedRows.value = [];
  } catch (error) {
    failures.value = emptyPage();
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

function filterChanged() {
  void loadFailures(1);
}

function selectionChanged(rows: OperationalFailure[]) {
  selectedRows.value = rows;
}

async function openAttempts(item: OperationalFailure) {
  activeFailure.value = item;
  attempts.value = [];
  attemptLoading.value = true;
  try {
    attempts.value = await findOperationalFailureAttempts(item.category, item.sourceId);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    attemptLoading.value = false;
  }
}

async function replayOne(item: OperationalFailure) {
  await ElMessageBox.confirm(
    `确认将 ${categoryLabels[item.category]} ${item.sourceId} 交回原状态机重放？`,
    '确认重放',
    { confirmButtonText: '确认重放', type: 'warning' },
  );
  replaying.value = true;
  try {
    const result = await replayOperationalFailure({
      category: item.category,
      sourceId: item.sourceId,
    });
    ElMessage.success(result.message);
    await loadFailures(pageNumber.value);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    replaying.value = false;
  }
}

async function replaySelected() {
  const items = replayableSelection.value.slice(0, 50);
  if (!items.length) {
    ElMessage.warning('请选择可重放的失败项');
    return;
  }
  await ElMessageBox.confirm(
    `确认批量重放 ${items.length} 项？每项仍由原责任状态机执行。`,
    '批量重放',
    { confirmButtonText: '确认重放', type: 'warning' },
  );
  replaying.value = true;
  try {
    const result = await replayOperationalFailureBatch(items.map(item => ({
      category: item.category,
      sourceId: item.sourceId,
    })));
    if (result.rejected) {
      ElMessage.warning(`已接受 ${result.replayed} 项，拒绝 ${result.rejected} 项`);
    } else {
      ElMessage.success(`已接受 ${result.replayed} 项重放`);
    }
    await loadFailures(pageNumber.value);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    replaying.value = false;
  }
}

void loadFailures();
</script>

<template>
  <Page
    title="运维失败队列"
    description="统一查看通知死信、业务回调 Outbox 和一致性检查失败；每类失败仍由自己的状态机、权限和审计规则负责。"
  >
    <div class="failure-page">
      <ElAlert
        :closable="false"
        show-icon
        title="人工重放不会删除原失败记录或历史尝试。批量上限为 50 项，Outbox 和通知使用条件状态更新，一致性失败只启动新的 detect-only 检查。"
        type="warning"
      />

      <section class="summary-grid">
        <ElCard shadow="never"><span>当前筛选总数</span><strong>{{ failures.total }}</strong></ElCard>
        <ElCard shadow="never"><span>通知死信</span><strong>{{ notificationCount }}</strong></ElCard>
        <ElCard shadow="never"><span>业务 Outbox</span><strong>{{ outboxCount }}</strong></ElCard>
        <ElCard shadow="never"><span>一致性检查</span><strong>{{ consistencyCount }}</strong></ElCard>
      </section>

      <ElCard shadow="never">
        <template #header>
          <div class="header-row">
            <div>
              <strong>失败任务</strong>
              <span>连接器失败按所属通知或业务回调责任展示，不创建第四套队列。</span>
            </div>
            <div class="actions">
              <ElSelect v-model="categoryFilter" clearable placeholder="全部责任队列" @change="filterChanged">
                <ElOption v-for="(label, value) in categoryLabels" :key="value" :label="label" :value="value" />
              </ElSelect>
              <ElSelect v-model="kindFilter" clearable placeholder="全部失败类型" @change="filterChanged">
                <ElOption v-for="(label, value) in kindLabels" :key="value" :label="label" :value="value" />
              </ElSelect>
              <ElInput v-model="connectorFilter" clearable placeholder="连接器 Key" @keyup.enter="filterChanged" />
              <ElButton :loading="loading" @click="loadFailures()">刷新</ElButton>
              <ElButton
                :disabled="!replayableSelection.length"
                :loading="replaying"
                type="primary"
                @click="replaySelected"
              >批量重放</ElButton>
            </div>
          </div>
        </template>

        <ElTable
          v-if="failures.items.length"
          v-loading="loading"
          :data="failures.items"
          row-key="sourceId"
          @selection-change="selectionChanged"
        >
          <ElTableColumn type="selection" width="48" />
          <ElTableColumn label="责任队列" min-width="170">
            <template #default="scope">
              <ElTag :type="categoryType(scope.row.category)">{{ categoryLabels[scope.row.category] }}</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="失败类型" min-width="120">
            <template #default="scope">{{ kindLabels[scope.row.failureKind] }}</template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="120">
            <template #default="scope"><ElTag :type="statusType(scope.row.status)">{{ scope.row.status }}</ElTag></template>
          </ElTableColumn>
          <ElTableColumn label="聚合" min-width="260">
            <template #default="scope">
              <div>{{ scope.row.aggregateType || '-' }}</div>
              <code>{{ scope.row.aggregateId || scope.row.sourceId }}</code>
            </template>
          </ElTableColumn>
          <ElTableColumn label="连接器/收件人" min-width="180">
            <template #default="scope">{{ scope.row.connectorKey || scope.row.recipientId || '-' }}</template>
          </ElTableColumn>
          <ElTableColumn label="尝试" width="100">
            <template #default="scope">{{ scope.row.attemptCount }}<span v-if="scope.row.maxAttempts"> / {{ scope.row.maxAttempts }}</span></template>
          </ElTableColumn>
          <ElTableColumn label="最后错误" min-width="260">
            <template #default="scope">
              <div>{{ scope.row.lastErrorCode || '-' }}</div>
              <small>{{ scope.row.lastErrorMessage || '-' }}</small>
            </template>
          </ElTableColumn>
          <ElTableColumn label="更新时间" min-width="180">
            <template #default="scope">{{ formatDate(scope.row.updatedAt) }}</template>
          </ElTableColumn>
          <ElTableColumn fixed="right" label="操作" width="150">
            <template #default="scope">
              <ElButton link type="primary" @click="openAttempts(scope.row)">尝试记录</ElButton>
              <ElButton
                :disabled="!scope.row.replayable"
                :loading="replaying"
                link
                type="warning"
                @click="replayOne(scope.row)"
              >重放</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <ElEmpty v-else :description="loading ? '正在读取' : '当前条件下没有失败项'" />
        <ElPagination
          v-if="failures.total > PAGE_SIZE"
          v-model:current-page="pageNumber"
          class="pagination"
          :page-size="PAGE_SIZE"
          :total="failures.total"
          @current-change="loadFailures"
        />
      </ElCard>
    </div>

    <ElDialog
      :model-value="Boolean(activeFailure)"
      title="投递尝试记录"
      width="900px"
      @close="activeFailure = undefined"
    >
      <ElTable v-loading="attemptLoading" :data="attempts" row-key="attemptId">
        <ElTableColumn label="#" prop="attemptNumber" width="70" />
        <ElTableColumn label="开始时间" min-width="180">
          <template #default="scope">{{ formatDate(scope.row.startedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="结果" width="100">
          <template #default="scope">
            <ElTag :type="scope.row.successful ? 'success' : 'danger'">
              {{ scope.row.successful ? '成功' : '失败' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="可重试" width="90">
          <template #default="scope">{{ scope.row.retryable ? '是' : '否' }}</template>
        </ElTableColumn>
        <ElTableColumn label="HTTP" prop="responseCode" width="90" />
        <ElTableColumn label="Provider/Worker" min-width="180">
          <template #default="scope">{{ scope.row.providerReference || scope.row.workerId || '-' }}</template>
        </ElTableColumn>
        <ElTableColumn label="错误" min-width="260">
          <template #default="scope">{{ scope.row.errorCode || scope.row.errorMessage || '-' }}</template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty v-if="!attemptLoading && !attempts.length" description="没有尝试记录" />
    </ElDialog>
  </Page>
</template>

<style scoped>
.failure-page { display: grid; gap: 16px; }
.summary-grid { display: grid; grid-template-columns: repeat(4, minmax(160px, 1fr)); gap: 16px; }
.summary-grid :deep(.el-card__body) { display: grid; gap: 8px; }
.summary-grid span, .header-row span { color: var(--el-text-color-secondary); font-size: 13px; }
.summary-grid strong { font-size: 30px; }
.header-row, .header-row > div, .actions { display: flex; align-items: center; gap: 10px; }
.header-row { justify-content: space-between; }
.header-row > div:first-child { align-items: flex-start; flex-direction: column; gap: 4px; }
.actions :deep(.el-select), .actions :deep(.el-input) { min-width: 160px; }
.pagination { justify-content: flex-end; margin-top: 16px; }
small { color: var(--el-text-color-secondary); }
code { overflow-wrap: anywhere; }
@media (max-width: 1100px) {
  .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .header-row { align-items: flex-start; flex-direction: column; }
  .actions { flex-wrap: wrap; }
}
</style>
