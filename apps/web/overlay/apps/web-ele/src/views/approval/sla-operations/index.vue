<script lang="ts" setup>
import type {
  ResponsibilityChange,
  SlaInstance,
  SlaInstancePage,
  SlaStatus,
} from '#/api/approval/sla';

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
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import {
  findOverdueSla,
  findResponsibilityChanges,
  findSlaInstances,
  findUpcomingSla,
  pauseSla,
  resumeSla,
} from '#/api/approval/sla';

type ViewMode = 'all' | 'overdue' | 'upcoming';

const PAGE_SIZE = 50;
const loading = ref(false);
const changing = ref(false);
const historyLoading = ref(false);
const mode = ref<ViewMode>('all');
const page = ref<SlaInstancePage>(emptyPage());
const activeInstance = ref<SlaInstance>();
const history = ref<ResponsibilityChange[]>([]);
const historyVisible = ref(false);
const filters = reactive({
  requestId: '',
  responsibleUserId: '',
  status: undefined as SlaStatus | undefined,
  upcomingHours: 24,
});

function emptyPage(): SlaInstancePage {
  return { items: [], limit: PAGE_SIZE, offset: 0, total: 0 };
}

function formatDate(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}

function statusType(status: SlaStatus) {
  if (status === 'ACTIVE') return 'success';
  if (status === 'PAUSED') return 'warning';
  return 'info';
}

function message(error: unknown) {
  return error instanceof Error ? error.message : 'SLA 运维请求失败';
}

async function load() {
  loading.value = true;
  try {
    if (mode.value === 'overdue') {
      page.value = await findOverdueSla(PAGE_SIZE, 0);
    } else if (mode.value === 'upcoming') {
      const dueBefore = new Date(Date.now() + filters.upcomingHours * 3_600_000).toISOString();
      page.value = await findUpcomingSla(dueBefore, PAGE_SIZE, 0);
    } else {
      page.value = await findSlaInstances({
        limit: PAGE_SIZE,
        offset: 0,
        requestId: filters.requestId,
        responsibleUserId: filters.responsibleUserId,
        status: filters.status,
      });
    }
  } catch (error) {
    page.value = emptyPage();
    ElMessage.error(message(error));
  } finally {
    loading.value = false;
  }
}

async function openHistory(instance: SlaInstance) {
  activeInstance.value = instance;
  historyVisible.value = true;
  historyLoading.value = true;
  history.value = [];
  try {
    history.value = await findResponsibilityChanges(instance.slaInstanceId, 100);
  } catch (error) {
    ElMessage.error(message(error));
  } finally {
    historyLoading.value = false;
  }
}

async function changeState(instance: SlaInstance, action: 'pause' | 'resume') {
  const { value } = await ElMessageBox.prompt(
    action === 'pause' ? '请输入暂停原因（至少 8 个字符）' : '请输入恢复原因（至少 8 个字符）',
    action === 'pause' ? '暂停 SLA' : '恢复 SLA',
    { inputValidator: text => Boolean(text && text.trim().length >= 8) || '原因至少 8 个字符' },
  );
  changing.value = true;
  try {
    if (action === 'pause') {
      await pauseSla(instance, { reason: value });
      ElMessage.success('SLA 已暂停');
    } else {
      await resumeSla(instance, { reason: value });
      ElMessage.success('SLA 已恢复，截止时间由服务端重新计算');
    }
    await load();
  } catch (error) {
    ElMessage.error(message(error));
  } finally {
    changing.value = false;
  }
}

function modeChanged() {
  void load();
}

void load();
</script>

<template>
  <Page title="SLA 运维" description="查询即将到期、已逾期和责任变更证据；暂停/恢复使用乐观锁和治理审计。">
    <div class="operations-page">
      <ElAlert
        :closable="false"
        show-icon
        title="本页面只展示服务端返回的 dueAt/overdueAt，不允许客户端提交或重算权威截止时间。"
        type="info"
      />
      <ElCard shadow="never">
        <template #header>
          <div class="header-row">
            <ElRadioGroup v-model="mode" @change="modeChanged">
              <ElRadioButton value="all">全部实例</ElRadioButton>
              <ElRadioButton value="upcoming">即将到期</ElRadioButton>
              <ElRadioButton value="overdue">已逾期</ElRadioButton>
            </ElRadioGroup>
            <ElButton :loading="loading" @click="load">刷新</ElButton>
          </div>
        </template>
        <div class="filters">
          <ElSelect v-if="mode === 'all'" v-model="filters.status" clearable placeholder="全部状态" @change="load">
            <ElOption label="ACTIVE" value="ACTIVE" />
            <ElOption label="PAUSED" value="PAUSED" />
            <ElOption label="TERMINAL" value="TERMINAL" />
          </ElSelect>
          <ElInput v-if="mode === 'all'" v-model="filters.responsibleUserId" clearable placeholder="责任人 ID" @keyup.enter="load" />
          <ElInput v-if="mode === 'all'" v-model="filters.requestId" clearable placeholder="requestId" @keyup.enter="load" />
          <ElSelect v-if="mode === 'upcoming'" v-model="filters.upcomingHours" @change="load">
            <ElOption :value="4" label="未来 4 小时" />
            <ElOption :value="24" label="未来 24 小时" />
            <ElOption :value="72" label="未来 3 天" />
            <ElOption :value="168" label="未来 7 天" />
          </ElSelect>
          <ElButton v-if="mode === 'all'" type="primary" @click="load">查询</ElButton>
        </div>
        <ElTable v-if="page.items.length" v-loading="loading" :data="page.items" row-key="slaInstanceId">
          <ElTableColumn label="目标" min-width="210">
            <template #default="scope"><ElTag>{{ scope.row.targetType }}</ElTag><div>{{ scope.row.definitionKey }}</div><small>{{ scope.row.taskDefinitionKey || scope.row.approvalInstanceId }}</small></template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="115"><template #default="scope"><ElTag :type="statusType(scope.row.status)">{{ scope.row.status }}</ElTag></template></ElTableColumn>
          <ElTableColumn label="责任人" min-width="180"><template #default="scope"><strong>{{ scope.row.responsibleUserId }}</strong><div v-if="scope.row.responsibleUserId !== scope.row.originalResponsibleUserId" class="muted">原：{{ scope.row.originalResponsibleUserId }}</div></template></ElTableColumn>
          <ElTableColumn label="截止时间" min-width="185"><template #default="scope">{{ formatDate(scope.row.dueAt) }}</template></ElTableColumn>
          <ElTableColumn label="逾期时间" min-width="185"><template #default="scope">{{ formatDate(scope.row.overdueAt) }}</template></ElTableColumn>
          <ElTableColumn label="时区" min-width="145" prop="timeZone" />
          <ElTableColumn label="requestId" min-width="210" prop="requestId" show-overflow-tooltip />
          <ElTableColumn fixed="right" label="操作" width="240">
            <template #default="scope">
              <ElButton link type="primary" @click="openHistory(scope.row)">责任历史</ElButton>
              <ElButton v-if="scope.row.status === 'ACTIVE'" :loading="changing" link type="warning" @click="changeState(scope.row, 'pause')">暂停</ElButton>
              <ElButton v-if="scope.row.status === 'PAUSED'" :loading="changing" link type="success" @click="changeState(scope.row, 'resume')">恢复</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <ElEmpty v-else :description="loading ? '正在读取' : '当前条件下没有 SLA 实例'" />
      </ElCard>
    </div>

    <ElDialog v-model="historyVisible" :title="`责任变更历史 · ${activeInstance?.slaInstanceId ?? ''}`" width="980px">
      <ElTable v-loading="historyLoading" :data="history" row-key="responsibilityChangeId">
        <ElTableColumn label="时间" min-width="180"><template #default="scope">{{ formatDate(scope.row.changedAt) }}</template></ElTableColumn>
        <ElTableColumn label="来源" width="150" prop="source" />
        <ElTableColumn label="责任变化" min-width="240"><template #default="scope">{{ scope.row.previousResponsibleUserId }} → {{ scope.row.newResponsibleUserId }}</template></ElTableColumn>
        <ElTableColumn label="原因" min-width="240" prop="reason" show-overflow-tooltip />
        <ElTableColumn label="操作人" min-width="160" prop="changedBy" />
        <ElTableColumn label="requestId" min-width="200" prop="requestId" show-overflow-tooltip />
      </ElTable>
      <ElEmpty v-if="!history.length && !historyLoading" description="暂无责任变更记录" />
    </ElDialog>
  </Page>
</template>

<style scoped>
.operations-page { display: grid; gap: 16px; }
.header-row, .filters { align-items: center; display: flex; flex-wrap: wrap; gap: 12px; }
.header-row { justify-content: space-between; }
.filters { margin-bottom: 16px; }
.filters .el-input, .filters .el-select { width: 220px; }
.muted, small { color: var(--el-text-color-secondary); font-size: 12px; }
</style>
