<script lang="ts" setup>
import type {
  SlaExecutionAction,
  SlaExecutionAttempt,
  SlaExecutionIntent,
  SlaExecutionIntentPage,
  SlaExecutionQueueSummary,
  SlaExecutionStatus,
} from '#/api/approval/sla-execution';

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
  ElMessageBox,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import {
  findSlaExecutionIntent,
  findSlaExecutionIntents,
  findSlaExecutionSummary,
  replaySlaExecutionIntent,
} from '#/api/approval/sla-execution';

const loading = ref(false);
const detailLoading = ref(false);
const replayingId = ref('');
const detailVisible = ref(false);
const page = ref<SlaExecutionIntentPage>({
  hasMore: false,
  items: [],
  limit: 50,
  offset: 0,
  total: 0,
});
const summary = ref<SlaExecutionQueueSummary>({
  cancelled: 0,
  claimed: 0,
  dead: 0,
  expiredLeases: 0,
  ready: 0,
  retryWait: 0,
  succeeded: 0,
});
const selected = ref<SlaExecutionIntent>();
const attempts = ref<SlaExecutionAttempt[]>([]);
const filters = reactive({
  actionType: undefined as SlaExecutionAction | undefined,
  requestId: '',
  responsibleUserId: '',
  status: undefined as SlaExecutionStatus | undefined,
});

const statusOptions: SlaExecutionStatus[] = [
  'READY', 'CLAIMED', 'RETRY_WAIT', 'SUCCEEDED', 'DEAD', 'CANCELLED',
];
const actionOptions: SlaExecutionAction[] = [
  'REMINDER', 'OVERDUE', 'ESCALATION', 'AUTOMATIC_ACTION',
];

function dateTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'SLA 执行运维请求失败';
}

function tagType(value: string) {
  if (value === 'SUCCEEDED') return 'success';
  if (value === 'DEAD' || value === 'PERMANENT_FAILURE') return 'danger';
  if (value.includes('RETRY') || value === 'CLAIMED') return 'warning';
  return 'info';
}

async function load() {
  loading.value = true;
  try {
    [summary.value, page.value] = await Promise.all([
      findSlaExecutionSummary(),
      findSlaExecutionIntents({
        actionTypes: filters.actionType ? [filters.actionType] : undefined,
        limit: 50,
        offset: 0,
        requestId: filters.requestId,
        responsibleUserId: filters.responsibleUserId,
        statuses: filters.status ? [filters.status] : undefined,
      }),
    ]);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

async function showEvidence(intent: SlaExecutionIntent) {
  selected.value = intent;
  attempts.value = [];
  detailVisible.value = true;
  detailLoading.value = true;
  try {
    const detail = await findSlaExecutionIntent(intent.intentId);
    selected.value = detail.intent;
    attempts.value = detail.attempts;
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    detailLoading.value = false;
  }
}

async function replay(intent: SlaExecutionIntent) {
  const { value } = await ElMessageBox.prompt(
    '请输入至少 8 个字符的治理原因。重放只创建新 intent，原 DEAD 证据不变。',
    '治理重放',
    {
      confirmButtonText: '创建重放',
      inputValidator: text => Boolean(text && text.trim().length >= 8)
        || '原因至少 8 个字符',
    },
  );
  replayingId.value = intent.intentId;
  try {
    const result = await replaySlaExecutionIntent(intent.intentId, { reason: value });
    ElMessage.success(result.replayedExistingRequest
      ? '已返回原有重放证据'
      : '已创建新的执行 intent');
    await load();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    replayingId.value = '';
  }
}

void load();
</script>

<template>
  <Page title="SLA 执行队列" description="查看 durable intent、append-only attempt、租约、重试与治理重放。">
    <div class="stack">
      <ElAlert
        :closable="false"
        show-icon
        title="租户、操作人和 worker 身份由服务端确定；DEAD 重放不会修改原失败证据。"
        type="info"
      />
      <ElCard shadow="never">
        <div class="summary">
          <span>READY <b>{{ summary.ready }}</b></span>
          <span>CLAIMED <b>{{ summary.claimed }}</b></span>
          <span>RETRY <b>{{ summary.retryWait }}</b></span>
          <span>SUCCESS <b>{{ summary.succeeded }}</b></span>
          <span>DEAD <b>{{ summary.dead }}</b></span>
          <span>CANCELLED <b>{{ summary.cancelled }}</b></span>
          <span>EXPIRED LEASE <b>{{ summary.expiredLeases }}</b></span>
        </div>
      </ElCard>
      <ElCard shadow="never">
        <template #header>
          <div class="row"><b>执行 intent</b><ElButton :loading="loading" @click="load">刷新</ElButton></div>
        </template>
        <div class="filters">
          <ElSelect v-model="filters.status" clearable placeholder="状态" @change="load">
            <ElOption v-for="item in statusOptions" :key="item" :label="item" :value="item" />
          </ElSelect>
          <ElSelect v-model="filters.actionType" clearable placeholder="动作" @change="load">
            <ElOption v-for="item in actionOptions" :key="item" :label="item" :value="item" />
          </ElSelect>
          <ElInput v-model="filters.responsibleUserId" clearable placeholder="责任人 ID" @keyup.enter="load" />
          <ElInput v-model="filters.requestId" clearable placeholder="requestId" @keyup.enter="load" />
          <ElButton type="primary" @click="load">查询</ElButton>
        </div>
        <ElTable v-if="page.items.length" v-loading="loading" :data="page.items" row-key="intentId">
          <ElTableColumn label="动作" min-width="170">
            <template #default="scope"><ElTag>{{ scope.row.actionType }}</ElTag> · {{ scope.row.actionSequence }}</template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="135">
            <template #default="scope"><ElTag :type="tagType(scope.row.status)">{{ scope.row.status }}</ElTag></template>
          </ElTableColumn>
          <ElTableColumn label="计划时间" min-width="180">
            <template #default="scope">{{ dateTime(scope.row.scheduledAt) }}</template>
          </ElTableColumn>
          <ElTableColumn label="尝试" width="100">
            <template #default="scope">{{ scope.row.attemptCount }}/{{ scope.row.maxAttempts }}</template>
          </ElTableColumn>
          <ElTableColumn label="责任人" min-width="170" prop="responsibleUserId" />
          <ElTableColumn label="requestId" min-width="210" prop="requestId" show-overflow-tooltip />
          <ElTableColumn label="最后错误" min-width="240" prop="lastErrorSummary" show-overflow-tooltip />
          <ElTableColumn fixed="right" label="操作" width="170">
            <template #default="scope">
              <ElButton link type="primary" @click="showEvidence(scope.row)">证据</ElButton>
              <ElButton
                v-if="scope.row.status === 'DEAD'"
                :loading="replayingId === scope.row.intentId"
                link
                type="danger"
                @click="replay(scope.row)"
              >重放</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <ElEmpty v-else :description="loading ? '正在读取' : '当前条件下没有执行 intent'" />
      </ElCard>
    </div>

    <ElDialog v-model="detailVisible" :title="`执行证据 · ${selected?.intentId ?? ''}`" width="1000px">
      <div v-if="selected" class="summary">
        <span>状态 <ElTag :type="tagType(selected.status)">{{ selected.status }}</ElTag></span>
        <span>动作 <b>{{ selected.actionType }}</b></span>
        <span>责任人 <b>{{ selected.responsibleUserId }}</b></span>
      </div>
      <ElTable v-loading="detailLoading" :data="attempts" row-key="attemptId">
        <ElTableColumn label="#" width="65" prop="attemptNumber" />
        <ElTableColumn label="结果" width="180">
          <template #default="scope"><ElTag :type="tagType(scope.row.result)">{{ scope.row.result }}</ElTag></template>
        </ElTableColumn>
        <ElTableColumn label="领取时间" min-width="180"><template #default="scope">{{ dateTime(scope.row.claimedAt) }}</template></ElTableColumn>
        <ElTableColumn label="完成时间" min-width="180"><template #default="scope">{{ dateTime(scope.row.finishedAt) }}</template></ElTableColumn>
        <ElTableColumn label="错误码" min-width="180" prop="errorCode" />
        <ElTableColumn label="错误摘要" min-width="260" prop="errorSummary" show-overflow-tooltip />
      </ElTable>
      <ElEmpty v-if="!attempts.length && !detailLoading" description="尚无执行 attempt" />
    </ElDialog>
  </Page>
</template>

<style scoped>
.stack { display: grid; gap: 16px; }
.row, .filters, .summary { align-items: center; display: flex; flex-wrap: wrap; gap: 12px; }
.row { justify-content: space-between; }
.filters { margin-bottom: 16px; }
.filters .el-input, .filters .el-select { width: 210px; }
.summary span { background: var(--el-fill-color-light); border-radius: 6px; padding: 8px 12px; }
</style>
