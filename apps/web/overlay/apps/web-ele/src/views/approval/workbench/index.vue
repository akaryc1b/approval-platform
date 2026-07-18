<script lang="ts" setup>
import type {
  ApprovalTimeline,
  ApprovalTimelineItem,
  PendingTaskDetails,
  PendingTaskItem,
  PendingTaskPage,
  ProcessedTaskItem,
  ProcessedTaskPage,
  StartedInstanceItem,
  StartedInstancePage,
} from '#/api/approval';

import { computed, onMounted, ref, watch } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElDrawer,
  ElEmpty,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElSelect,
  ElSkeleton,
  ElTabPane,
  ElTabs,
  ElTag,
  ElTimeline,
  ElTimelineItem,
} from 'element-plus';

import {
  approveTask,
  findApprovalTimeline,
  findPendingTask,
  findPendingTasks,
  findProcessedTasks,
  findStartedInstances,
  rejectTask,
  resubmitTask,
  retrieveTask,
  transferTask,
  withdrawInstance,
} from '#/api/approval';

type WorkbenchTab = 'pending' | 'processed' | 'started';
type TagType = 'danger' | 'info' | 'primary' | 'success' | 'warning';

const pageSize = 10;
const activeTab = ref<WorkbenchTab>('pending');
const currentPage = ref(1);
const keyword = ref('');
const loading = ref(false);
const loadError = ref('');
const listActionId = ref('');

const pendingPage = ref<PendingTaskPage>(emptyPendingPage());
const processedPage = ref<ProcessedTaskPage>(emptyProcessedPage());
const startedPage = ref<StartedInstancePage>(emptyStartedPage());
const pendingTotal = ref(0);
const processedTotal = ref(0);
const startedTotal = ref(0);

const drawerOpen = ref(false);
const detailLoading = ref(false);
const detailError = ref('');
const selectedTask = ref<PendingTaskDetails>();
const timeline = ref<ApprovalTimeline>();
const approvalComment = ref('');
const transferTargetId = ref('');
const submitting = ref(false);

const pageOffset = computed(() => (currentPage.value - 1) * pageSize);
const revisionTask = computed(
  () => selectedTask.value?.taskDefinitionKey === 'initiatorRevision',
);
const drawerTitle = computed(() =>
  revisionTask.value ? '修改并重新提交' : '审批详情',
);
const activeTotal = computed(() => {
  if (activeTab.value === 'processed') {
    return processedPage.value.total;
  }
  if (activeTab.value === 'started') {
    return startedPage.value.total;
  }
  return pendingPage.value.total;
});
const activeItemCount = computed(() => {
  if (activeTab.value === 'processed') {
    return processedPage.value.items.length;
  }
  if (activeTab.value === 'started') {
    return startedPage.value.items.length;
  }
  return pendingPage.value.items.length;
});

const moneyFormatter = new Intl.NumberFormat('zh-CN', {
  currency: 'CNY',
  style: 'currency',
});
const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  month: '2-digit',
  year: 'numeric',
});

function emptyPendingPage(): PendingTaskPage {
  return { hasMore: false, items: [], limit: pageSize, offset: 0, total: 0 };
}

function emptyProcessedPage(): ProcessedTaskPage {
  return { hasMore: false, items: [], limit: pageSize, offset: 0, total: 0 };
}

function emptyStartedPage(): StartedInstancePage {
  return { hasMore: false, items: [], limit: pageSize, offset: 0, total: 0 };
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试';
}

function formatMoney(value: number) {
  return moneyFormatter.format(value);
}

function formatDate(value: string) {
  return dateFormatter.format(new Date(value));
}

function taskStage(task: Pick<PendingTaskItem, 'taskDefinitionKey' | 'taskName'>) {
  const labels: Record<string, string> = {
    financeCountersign: '财务会签',
    financeReview: '财务审核',
    initiatorRevision: '发起人修改',
    managerApproval: '部门负责人审批',
  };
  return labels[task.taskDefinitionKey] || task.taskName;
}

function taskTagType(task: Pick<PendingTaskItem, 'taskDefinitionKey'>): TagType {
  return task.taskDefinitionKey === 'initiatorRevision' ? 'warning' : 'primary';
}

function instanceStatusLabel(status: StartedInstanceItem['status']) {
  const labels = {
    COMPLETED: '已完成',
    REJECTED: '已驳回',
    RUNNING: '审批中',
    WITHDRAWN: '已撤回',
  };
  return labels[status];
}

function instanceStatusType(status: StartedInstanceItem['status']): TagType {
  if (status === 'COMPLETED') {
    return 'success';
  }
  if (status === 'WITHDRAWN' || status === 'REJECTED') {
    return 'info';
  }
  return 'primary';
}

function timelineTitle(item: ApprovalTimelineItem) {
  const labels: Record<string, string> = {
    INSTANCE_STARTED: '发起审批',
    INSTANCE_WITHDRAWN: '发起人撤回',
    TASK_APPROVED: '同意审批',
    TASK_REJECTED: '驳回到发起人',
    TASK_RESUBMITTED: '发起人重新提交',
    TASK_RETRIEVED: '审批人拿回',
    TASK_TRANSFERRED: '任务转办',
  };
  return labels[item.action] || item.action;
}

function timelineType(item: ApprovalTimelineItem): TagType {
  if (item.action === 'TASK_APPROVED') {
    return 'success';
  }
  if (item.action === 'TASK_REJECTED' || item.action === 'INSTANCE_WITHDRAWN') {
    return 'danger';
  }
  if (
    item.action === 'TASK_RESUBMITTED' ||
    item.action === 'TASK_RETRIEVED' ||
    item.action === 'TASK_TRANSFERRED'
  ) {
    return 'warning';
  }
  return 'primary';
}

async function loadActivePage() {
  loading.value = true;
  loadError.value = '';
  const parameters = {
    keyword: keyword.value,
    limit: pageSize,
    offset: pageOffset.value,
  };
  try {
    if (activeTab.value === 'processed') {
      processedPage.value = await findProcessedTasks(parameters);
      processedTotal.value = processedPage.value.total;
    } else if (activeTab.value === 'started') {
      startedPage.value = await findStartedInstances(parameters);
      startedTotal.value = startedPage.value.total;
    } else {
      pendingPage.value = await findPendingTasks(parameters);
      pendingTotal.value = pendingPage.value.total;
    }
  } catch (error) {
    loadError.value = errorMessage(error);
    if (activeTab.value === 'processed') {
      processedPage.value = emptyProcessedPage();
    } else if (activeTab.value === 'started') {
      startedPage.value = emptyStartedPage();
    } else {
      pendingPage.value = emptyPendingPage();
    }
  } finally {
    loading.value = false;
  }
}

async function loadOverviewCounts() {
  const parameters = { limit: 1, offset: 0 };
  const [pending, processed, started] = await Promise.allSettled([
    findPendingTasks(parameters),
    findProcessedTasks(parameters),
    findStartedInstances(parameters),
  ]);
  if (pending.status === 'fulfilled') {
    pendingTotal.value = pending.value.total;
  }
  if (processed.status === 'fulfilled') {
    processedTotal.value = processed.value.total;
  }
  if (started.status === 'fulfilled') {
    startedTotal.value = started.value.total;
  }
}

async function refreshWorkbench() {
  await Promise.all([loadActivePage(), loadOverviewCounts()]);
}

async function searchItems() {
  currentPage.value = 1;
  await loadActivePage();
}

async function resetSearch() {
  keyword.value = '';
  currentPage.value = 1;
  await loadActivePage();
}

async function changePage(page: number) {
  currentPage.value = page;
  await loadActivePage();
}

function selectTab(tab: WorkbenchTab) {
  activeTab.value = tab;
}

async function openTask(task: PendingTaskItem) {
  drawerOpen.value = true;
  detailLoading.value = true;
  detailError.value = '';
  selectedTask.value = undefined;
  timeline.value = undefined;
  approvalComment.value = '';
  transferTargetId.value = '';

  try {
    const [details, timelineResult] = await Promise.all([
      findPendingTask(task.taskId),
      findApprovalTimeline(task.instanceId),
    ]);
    selectedTask.value = details;
    timeline.value = timelineResult;
  } catch (error) {
    detailError.value = errorMessage(error);
  } finally {
    detailLoading.value = false;
  }
}

async function confirmAction(
  title: string,
  message: string,
  confirmButtonText: string,
  type: 'error' | 'warning',
) {
  try {
    await ElMessageBox.confirm(message, title, {
      cancelButtonText: '取消',
      confirmButtonText,
      type,
    });
    return true;
  } catch {
    return false;
  }
}

async function finishDrawerAction(message: string) {
  ElMessage.success(message);
  drawerOpen.value = false;
  await refreshWorkbench();
}

async function submitApproval() {
  const task = selectedTask.value;
  if (!task) return;
  if (!await confirmAction('审批确认', '确认同意该审批吗？', '确认同意', 'warning')) return;
  submitting.value = true;
  try {
    await approveTask(task.taskId, approvalComment.value);
    await finishDrawerAction('审批已同意');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    submitting.value = false;
  }
}

async function submitRejection() {
  const task = selectedTask.value;
  const comment = approvalComment.value.trim();
  if (!task) return;
  if (!comment) {
    ElMessage.warning('驳回时必须填写原因');
    return;
  }
  if (!await confirmAction(
    '驳回确认',
    '驳回后将生成发起人修改任务，确认继续吗？',
    '确认驳回',
    'error',
  )) return;
  submitting.value = true;
  try {
    await rejectTask(task.taskId, comment);
    await finishDrawerAction('已驳回到发起人');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    submitting.value = false;
  }
}

async function submitResubmission() {
  const task = selectedTask.value;
  if (!task) return;
  if (!await confirmAction(
    '重新提交确认',
    '重新提交后流程将从部门负责人审批重新开始，确认继续吗？',
    '重新提交',
    'warning',
  )) return;
  submitting.value = true;
  try {
    await resubmitTask(task.taskId, approvalComment.value);
    await finishDrawerAction('申请已重新提交');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    submitting.value = false;
  }
}

async function submitTransfer() {
  const task = selectedTask.value;
  const comment = approvalComment.value.trim();
  if (!task) return;
  if (!transferTargetId.value) {
    ElMessage.warning('请选择转办人员');
    return;
  }
  if (!comment) {
    ElMessage.warning('转办时必须填写原因');
    return;
  }
  const candidate = task.transferCandidates?.find(
    (item) => item.userId === transferTargetId.value,
  );
  if (!await confirmAction(
    '转办确认',
    `确认将该任务转办给 ${candidate?.displayName || transferTargetId.value} 吗？`,
    '确认转办',
    'warning',
  )) return;
  submitting.value = true;
  try {
    await transferTask(task.taskId, transferTargetId.value, comment);
    await finishDrawerAction('任务已转办');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    submitting.value = false;
  }
}

async function submitWithdrawal(item: StartedInstanceItem) {
  if (!item.withdrawable || listActionId.value) return;
  if (!await confirmAction(
    '撤回确认',
    '撤回后当前审批任务将全部取消，确认继续吗？',
    '确认撤回',
    'error',
  )) return;
  listActionId.value = item.instanceId;
  try {
    await withdrawInstance(item.instanceId, '发起人从审批工作台撤回');
    ElMessage.success('申请已撤回');
    await refreshWorkbench();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    listActionId.value = '';
  }
}

async function submitRetrieve(item: ProcessedTaskItem) {
  if (!item.retrievable || listActionId.value) return;
  if (!await confirmAction(
    '拿回确认',
    '拿回后下游待办将取消，任务重新回到你名下，确认继续吗？',
    '确认拿回',
    'warning',
  )) return;
  listActionId.value = item.taskId;
  try {
    await retrieveTask(item.taskId, '审批人从已处理列表拿回');
    ElMessage.success('任务已拿回');
    activeTab.value = 'pending';
    await refreshWorkbench();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    listActionId.value = '';
  }
}

watch(activeTab, async () => {
  keyword.value = '';
  currentPage.value = 1;
  await loadActivePage();
});

onMounted(refreshWorkbench);
</script>

<template>
  <Page title="审批工作台">
    <div class="workbench">
      <section class="overview-grid">
        <ElCard class="overview-shell" shadow="never" @click="selectTab('pending')">
          <div class="overview-card">
            <span>待我处理</span>
            <strong>{{ pendingTotal }}</strong>
            <small>审批与发起人修改</small>
          </div>
        </ElCard>
        <ElCard class="overview-shell" shadow="never" @click="selectTab('processed')">
          <div class="overview-card">
            <span>我已处理</span>
            <strong>{{ processedTotal }}</strong>
            <small>可安全拿回时显示操作</small>
          </div>
        </ElCard>
        <ElCard class="overview-shell" shadow="never" @click="selectTab('started')">
          <div class="overview-card">
            <span>我发起的</span>
            <strong>{{ startedTotal }}</strong>
            <small>运行中的申请可撤回</small>
          </div>
        </ElCard>
      </section>

      <ElCard shadow="never">
        <template #header>
          <div class="section-header">
            <div>
              <strong>审批事项</strong>
              <span>按参与身份查看并执行允许的协作动作</span>
            </div>
            <ElButton :loading="loading" @click="refreshWorkbench">刷新</ElButton>
          </div>
        </template>

        <ElTabs v-model="activeTab" class="workbench-tabs">
          <ElTabPane :label="`待我处理 ${pendingTotal}`" name="pending" />
          <ElTabPane :label="`我已处理 ${processedTotal}`" name="processed" />
          <ElTabPane :label="`我发起的 ${startedTotal}`" name="started" />
        </ElTabs>

        <div class="search-bar">
          <ElInput
            v-model="keyword"
            clearable
            placeholder="搜索业务编号、供应商、采购订单或任务"
            @clear="resetSearch"
            @keyup.enter="searchItems"
          />
          <ElButton type="primary" @click="searchItems">搜索</ElButton>
        </div>

        <ElAlert
          v-if="loadError"
          :closable="false"
          :title="loadError"
          class="state-block"
          type="error"
        />
        <ElSkeleton v-else-if="loading" :rows="5" animated class="state-block" />
        <ElEmpty
          v-else-if="activeItemCount === 0"
          :description="activeTab === 'pending' ? '当前没有待处理任务' : '当前没有相关审批记录'"
        />

        <div v-else-if="activeTab === 'pending'" class="task-list">
          <article v-for="task in pendingPage.items" :key="task.taskId" class="task-item">
            <div class="task-main">
              <div class="task-title-row">
                <strong>{{ task.supplier }}采购付款</strong>
                <ElTag :type="taskTagType(task)" effect="plain">{{ taskStage(task) }}</ElTag>
              </div>
              <span>{{ task.businessKey }} · {{ task.purchaseOrderReference }}</span>
              <span>发起人 {{ task.initiatorId }} · {{ formatDate(task.taskCreatedAt) }}</span>
            </div>
            <div class="task-actions">
              <strong>{{ formatMoney(task.amount) }}</strong>
              <ElButton type="primary" @click="openTask(task)">
                {{ task.taskDefinitionKey === 'initiatorRevision' ? '修改' : '处理' }}
              </ElButton>
            </div>
          </article>
        </div>

        <div v-else-if="activeTab === 'processed'" class="task-list">
          <article v-for="task in processedPage.items" :key="task.taskId" class="task-item">
            <div class="task-main">
              <div class="task-title-row">
                <strong>{{ task.supplier }}采购付款</strong>
                <ElTag effect="plain" type="success">{{ taskStage(task) }}</ElTag>
              </div>
              <span>{{ task.businessKey }} · {{ task.purchaseOrderReference }}</span>
              <span>处理时间 {{ formatDate(task.completedAt) }}</span>
            </div>
            <div class="task-actions">
              <strong>{{ formatMoney(task.amount) }}</strong>
              <ElButton
                v-if="task.retrievable"
                :loading="listActionId === task.taskId"
                type="warning"
                plain
                @click="submitRetrieve(task)"
              >
                拿回
              </ElButton>
              <ElTag v-else effect="plain" type="info">不可拿回</ElTag>
            </div>
          </article>
        </div>

        <div v-else class="task-list">
          <article v-for="item in startedPage.items" :key="item.instanceId" class="task-item">
            <div class="task-main">
              <div class="task-title-row">
                <strong>{{ item.supplier }}采购付款</strong>
                <ElTag :type="instanceStatusType(item.status)" effect="plain">
                  {{ instanceStatusLabel(item.status) }}
                </ElTag>
              </div>
              <span>{{ item.businessKey }} · {{ item.purchaseOrderReference }}</span>
              <span>
                当前环节 {{ item.currentTaskName || '流程已结束' }} · 更新时间 {{ formatDate(item.updatedAt) }}
              </span>
            </div>
            <div class="task-actions">
              <strong>{{ formatMoney(item.amount) }}</strong>
              <ElButton
                v-if="item.withdrawable"
                :loading="listActionId === item.instanceId"
                type="danger"
                plain
                @click="submitWithdrawal(item)"
              >
                撤回
              </ElButton>
            </div>
          </article>
        </div>

        <div v-if="activeTotal > pageSize" class="pagination-row">
          <ElPagination
            :current-page="currentPage"
            :page-size="pageSize"
            :total="activeTotal"
            background
            layout="prev, pager, next, total"
            @current-change="changePage"
          />
        </div>
      </ElCard>
    </div>

    <ElDrawer v-model="drawerOpen" :title="drawerTitle" destroy-on-close size="680px">
      <ElSkeleton v-if="detailLoading" :rows="10" animated />
      <ElAlert
        v-else-if="detailError"
        :closable="false"
        :title="detailError"
        type="error"
      />

      <div v-else-if="selectedTask" class="detail-content">
        <ElAlert
          v-if="revisionTask"
          :closable="false"
          show-icon
          title="该申请已被驳回。确认材料后可重新提交，流程将从部门负责人审批重新开始。"
          type="warning"
        />

        <ElDescriptions :column="2" border title="申请信息">
          <ElDescriptionsItem label="业务编号">{{ selectedTask.businessKey }}</ElDescriptionsItem>
          <ElDescriptionsItem label="当前环节">{{ taskStage(selectedTask) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="发起人">{{ selectedTask.initiatorId }}</ElDescriptionsItem>
          <ElDescriptionsItem label="付款金额">{{ formatMoney(selectedTask.amount) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="供应商">{{ selectedTask.supplier }}</ElDescriptionsItem>
          <ElDescriptionsItem label="采购订单">{{ selectedTask.purchaseOrderReference }}</ElDescriptionsItem>
          <ElDescriptionsItem :span="2" label="附件">
            <div v-if="selectedTask.attachmentIds.length" class="attachment-list">
              <ElTag v-for="attachment in selectedTask.attachmentIds" :key="attachment" effect="plain">
                {{ attachment }}
              </ElTag>
            </div>
            <span v-else>无附件</span>
          </ElDescriptionsItem>
        </ElDescriptions>

        <section class="detail-section">
          <h3>审批进度</h3>
          <ElTimeline v-if="timeline?.items.length">
            <ElTimelineItem
              v-for="item in timeline.items"
              :key="item.eventId"
              :timestamp="formatDate(item.occurredAt)"
              :type="timelineType(item)"
            >
              <strong>{{ timelineTitle(item) }}</strong>
              <div class="timeline-meta">操作人：{{ item.operatorId }}</div>
              <div v-if="item.attributes.comment" class="timeline-comment">
                意见：{{ item.attributes.comment }}
              </div>
            </ElTimelineItem>
          </ElTimeline>
          <ElEmpty v-else description="暂无审批记录" :image-size="72" />
        </section>

        <section
          v-if="!revisionTask && selectedTask.transferCandidates?.length"
          class="detail-section"
        >
          <h3>转办人员</h3>
          <ElSelect
            v-model="transferTargetId"
            class="transfer-select"
            clearable
            filterable
            placeholder="从流程审批人快照中选择"
          >
            <ElOption
              v-for="candidate in selectedTask.transferCandidates"
              :key="candidate.userId"
              :label="candidate.displayName"
              :value="candidate.userId"
            >
              <span>{{ candidate.displayName }}</span>
              <span class="candidate-id">{{ candidate.userId }}</span>
            </ElOption>
          </ElSelect>
          <div class="detail-hint">点击“转办”时，下方审批意见将作为转办原因且必须填写。</div>
        </section>

        <section class="detail-section">
          <h3>{{ revisionTask ? '重新提交说明' : '审批意见' }}</h3>
          <ElInput
            v-model="approvalComment"
            :maxlength="2000"
            :placeholder="revisionTask ? '填写本次修改说明（可选）' : '填写审批意见；驳回或转办时必填'"
            :rows="4"
            show-word-limit
            type="textarea"
          />
        </section>
      </div>

      <template #footer>
        <div class="drawer-footer">
          <ElButton @click="drawerOpen = false">取消</ElButton>
          <div class="action-group">
            <ElButton
              v-if="revisionTask"
              :disabled="!selectedTask"
              :loading="submitting"
              type="primary"
              @click="submitResubmission"
            >
              重新提交
            </ElButton>
            <template v-else>
              <ElButton
                v-if="selectedTask?.transferCandidates?.length"
                :disabled="!selectedTask"
                :loading="submitting"
                plain
                @click="submitTransfer"
              >
                转办
              </ElButton>
              <ElButton
                :disabled="!selectedTask"
                :loading="submitting"
                type="danger"
                plain
                @click="submitRejection"
              >
                驳回
              </ElButton>
              <ElButton
                :disabled="!selectedTask"
                :loading="submitting"
                type="primary"
                @click="submitApproval"
              >
                同意
              </ElButton>
            </template>
          </div>
        </div>
      </template>
    </ElDrawer>
  </Page>
</template>

<style scoped>
.workbench,
.detail-content {
  display: grid;
  gap: 16px;
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(220px, 1fr));
  gap: 16px;
}

.overview-shell {
  cursor: pointer;
}

.overview-card {
  display: grid;
  gap: 6px;
}

.overview-card span,
.overview-card small,
.section-header span,
.task-main span,
.timeline-meta,
.timeline-comment,
.detail-hint,
.candidate-id {
  color: var(--el-text-color-secondary);
}

.overview-card strong {
  font-size: 32px;
}

.section-header,
.task-item,
.task-title-row,
.task-actions,
.search-bar,
.pagination-row,
.drawer-footer,
.action-group,
.attachment-list {
  display: flex;
  align-items: center;
  gap: 12px;
}

.section-header,
.task-item,
.pagination-row,
.drawer-footer {
  justify-content: space-between;
}

.section-header > div,
.task-main,
.task-actions {
  display: grid;
  gap: 6px;
}

.workbench-tabs {
  margin-bottom: 12px;
}

.search-bar :deep(.el-input) {
  max-width: 520px;
}

.state-block {
  margin: 20px 0;
}

.task-list {
  display: grid;
}

.task-item {
  padding: 18px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.task-item:last-child {
  border-bottom: 0;
}

.task-main {
  min-width: 0;
}

.task-title-row {
  flex-wrap: wrap;
}

.task-actions {
  justify-items: end;
  white-space: nowrap;
}

.pagination-row {
  padding-top: 18px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.detail-section {
  padding-top: 8px;
}

.detail-section h3 {
  margin: 0 0 16px;
  font-size: 16px;
}

.attachment-list {
  flex-wrap: wrap;
}

.transfer-select {
  width: 100%;
}

.detail-hint,
.timeline-meta,
.timeline-comment {
  margin-top: 4px;
  font-size: 13px;
}

.candidate-id {
  float: right;
  margin-left: 16px;
  font-size: 12px;
}

.drawer-footer {
  width: 100%;
}

@media (max-width: 900px) {
  .overview-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .section-header,
  .task-item,
  .search-bar {
    align-items: stretch;
    flex-direction: column;
  }

  .task-actions {
    justify-items: start;
  }

  .search-bar :deep(.el-input) {
    max-width: none;
  }
}
</style>
