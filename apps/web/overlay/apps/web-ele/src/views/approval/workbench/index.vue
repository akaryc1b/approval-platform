<script lang="ts" setup>
import type {
  ApprovalTimeline,
  ApprovalTimelineItem,
  PendingTaskDetails,
  PendingTaskItem,
  PendingTaskPage,
} from '#/api/approval';

import { computed, onMounted, ref } from 'vue';

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
  ElTag,
  ElTimeline,
  ElTimelineItem,
} from 'element-plus';

import {
  approveTask,
  findApprovalTimeline,
  findPendingTask,
  findPendingTasks,
  rejectTask,
  resubmitTask,
  transferTask,
} from '#/api/approval';

const pageSize = 10;
const currentPage = ref(1);
const keyword = ref('');
const loading = ref(false);
const loadError = ref('');
const taskPage = ref<PendingTaskPage>({
  hasMore: false,
  items: [],
  limit: pageSize,
  offset: 0,
  total: 0,
});

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

function taskTagType(task: Pick<PendingTaskItem, 'taskDefinitionKey'>) {
  return task.taskDefinitionKey === 'initiatorRevision' ? 'warning' : 'primary';
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

function timelineType(item: ApprovalTimelineItem) {
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

async function loadTasks() {
  loading.value = true;
  loadError.value = '';
  try {
    taskPage.value = await findPendingTasks({
      keyword: keyword.value,
      limit: pageSize,
      offset: pageOffset.value,
    });
  } catch (error) {
    loadError.value = errorMessage(error);
    taskPage.value = {
      hasMore: false,
      items: [],
      limit: pageSize,
      offset: pageOffset.value,
      total: 0,
    };
  } finally {
    loading.value = false;
  }
}

async function searchTasks() {
  currentPage.value = 1;
  await loadTasks();
}

async function resetSearch() {
  keyword.value = '';
  currentPage.value = 1;
  await loadTasks();
}

async function changePage(page: number) {
  currentPage.value = page;
  await loadTasks();
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

async function finishAction(message: string) {
  ElMessage.success(message);
  drawerOpen.value = false;
  await loadTasks();
}

async function submitApproval() {
  const task = selectedTask.value;
  if (!task) {
    return;
  }
  const confirmed = await confirmAction(
    '审批确认',
    '确认同意该审批吗？',
    '确认同意',
    'warning',
  );
  if (!confirmed) {
    return;
  }

  submitting.value = true;
  try {
    await approveTask(task.taskId, approvalComment.value);
    await finishAction('审批已同意');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    submitting.value = false;
  }
}

async function submitRejection() {
  const task = selectedTask.value;
  const comment = approvalComment.value.trim();
  if (!task) {
    return;
  }
  if (!comment) {
    ElMessage.warning('驳回时必须填写原因');
    return;
  }
  const confirmed = await confirmAction(
    '驳回确认',
    '驳回后将生成发起人修改任务，确认继续吗？',
    '确认驳回',
    'error',
  );
  if (!confirmed) {
    return;
  }

  submitting.value = true;
  try {
    await rejectTask(task.taskId, comment);
    await finishAction('已驳回到发起人');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    submitting.value = false;
  }
}

async function submitResubmission() {
  const task = selectedTask.value;
  if (!task) {
    return;
  }
  const confirmed = await confirmAction(
    '重新提交确认',
    '重新提交后流程将从部门负责人审批重新开始，确认继续吗？',
    '重新提交',
    'warning',
  );
  if (!confirmed) {
    return;
  }

  submitting.value = true;
  try {
    await resubmitTask(task.taskId, approvalComment.value);
    await finishAction('申请已重新提交');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    submitting.value = false;
  }
}

async function submitTransfer() {
  const task = selectedTask.value;
  const comment = approvalComment.value.trim();
  if (!task) {
    return;
  }
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
  const confirmed = await confirmAction(
    '转办确认',
    `确认将该任务转办给 ${candidate?.displayName || transferTargetId.value} 吗？`,
    '确认转办',
    'warning',
  );
  if (!confirmed) {
    return;
  }

  submitting.value = true;
  try {
    await transferTask(task.taskId, transferTargetId.value, comment);
    await finishAction('任务已转办');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    submitting.value = false;
  }
}

onMounted(loadTasks);
</script>

<template>
  <Page title="审批工作台">
    <div class="workbench">
      <section class="overview-grid">
        <ElCard shadow="never">
          <div class="overview-card">
            <span>待我处理</span>
            <strong>{{ taskPage.total }}</strong>
            <small>包含审批与发起人修改任务</small>
          </div>
        </ElCard>
      </section>

      <ElCard shadow="never">
        <template #header>
          <div class="section-header">
            <div>
              <strong>待处理任务</strong>
              <span>查看申请信息、审批记录并完成处理</span>
            </div>
            <ElButton :loading="loading" @click="loadTasks">刷新</ElButton>
          </div>
        </template>

        <div class="search-bar">
          <ElInput
            v-model="keyword"
            clearable
            placeholder="搜索任务、业务编号、供应商或采购订单"
            @clear="resetSearch"
            @keyup.enter="searchTasks"
          />
          <ElButton type="primary" @click="searchTasks">搜索</ElButton>
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
          v-else-if="taskPage.items.length === 0"
          description="当前没有待处理任务"
        />

        <div v-else class="task-list">
          <article
            v-for="task in taskPage.items"
            :key="task.taskId"
            class="task-item"
          >
            <div class="task-main">
              <div class="task-title-row">
                <strong>{{ task.supplier }}采购付款</strong>
                <ElTag :type="taskTagType(task)" effect="plain">
                  {{ taskStage(task) }}
                </ElTag>
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

        <div v-if="taskPage.total > pageSize" class="pagination-row">
          <ElPagination
            :current-page="currentPage"
            :page-size="pageSize"
            :total="taskPage.total"
            background
            layout="prev, pager, next, total"
            @current-change="changePage"
          />
        </div>
      </ElCard>
    </div>

    <ElDrawer
      v-model="drawerOpen"
      :title="drawerTitle"
      destroy-on-close
      size="680px"
    >
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
          <ElDescriptionsItem label="业务编号">
            {{ selectedTask.businessKey }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="当前环节">
            {{ taskStage(selectedTask) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="发起人">
            {{ selectedTask.initiatorId }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="付款金额">
            {{ formatMoney(selectedTask.amount) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="供应商">
            {{ selectedTask.supplier }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="采购订单">
            {{ selectedTask.purchaseOrderReference }}
          </ElDescriptionsItem>
          <ElDescriptionsItem :span="2" label="附件">
            <div v-if="selectedTask.attachmentIds.length" class="attachment-list">
              <ElTag
                v-for="attachment in selectedTask.attachmentIds"
                :key="attachment"
                effect="plain"
              >
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
  grid-template-columns: minmax(220px, 320px);
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

@media (max-width: 720px) {
  .overview-grid {
    grid-template-columns: 1fr;
  }

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
