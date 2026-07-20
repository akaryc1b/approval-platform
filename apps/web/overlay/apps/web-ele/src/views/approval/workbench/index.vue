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
import type { DelegatedTaskAssignment } from '#/api/approval/delegations';
import type { FormRuntimeView } from '#/api/approval/form-types';

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
import { findTaskDelegation } from '#/api/approval/delegations';
import { findTaskFormRuntime, resubmitFormTask } from '#/api/approval/forms';
import ApprovalFormRenderer from '#/components/approval/ApprovalFormRenderer.vue';

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
const taskDelegation = ref<DelegatedTaskAssignment>();
const timeline = ref<ApprovalTimeline>();
const formRuntime = ref<FormRuntimeView>();
const formValues = ref<Record<string, unknown>>({});
const approvalComment = ref('');
const transferTargetId = ref('');
const submitting = ref(false);

const pageOffset = computed(() => (currentPage.value - 1) * pageSize);
const revisionTask = computed(() => selectedTask.value?.taskDefinitionKey === 'initiatorRevision');
const drawerTitle = computed(() => revisionTask.value ? '修改并重新提交' : '审批详情');
const activeTotal = computed(() => activeTab.value === 'processed'
  ? processedPage.value.total
  : activeTab.value === 'started'
    ? startedPage.value.total
    : pendingPage.value.total);
const activeItemCount = computed(() => activeTab.value === 'processed'
  ? processedPage.value.items.length
  : activeTab.value === 'started'
    ? startedPage.value.items.length
    : pendingPage.value.items.length);

const moneyFormatter = new Intl.NumberFormat('zh-CN', { currency: 'CNY', style: 'currency' });
const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  day: '2-digit', hour: '2-digit', minute: '2-digit', month: '2-digit', year: 'numeric',
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
    financeCountersign: '财务会签', financeReview: '财务审核',
    initiatorRevision: '发起人修改', managerApproval: '部门负责人审批',
  };
  return labels[task.taskDefinitionKey] || task.taskName;
}
function taskTagType(task: Pick<PendingTaskItem, 'taskDefinitionKey'>): TagType {
  return task.taskDefinitionKey === 'initiatorRevision' ? 'warning' : 'primary';
}
function instanceStatusLabel(status: StartedInstanceItem['status']) {
  return { COMPLETED: '已完成', REJECTED: '已驳回', RUNNING: '审批中', WITHDRAWN: '已撤回' }[status];
}
function instanceStatusType(status: StartedInstanceItem['status']): TagType {
  if (status === 'COMPLETED') return 'success';
  if (status === 'WITHDRAWN' || status === 'REJECTED') return 'info';
  return 'primary';
}
function timelineTitle(item: ApprovalTimelineItem) {
  const labels: Record<string, string> = {
    INSTANCE_STARTED: '发起审批', INSTANCE_WITHDRAWN: '发起人撤回',
    TASK_APPROVED: '同意审批', TASK_DELEGATED: '任务自动代理',
    TASK_REJECTED: '驳回到发起人', TASK_RESUBMITTED: '修改并重新提交',
    TASK_RETRIEVED: '审批人拿回', TASK_TRANSFERRED: '任务转办',
  };
  return labels[item.action] || item.action;
}
function timelineType(item: ApprovalTimelineItem): TagType {
  if (item.action === 'TASK_APPROVED') return 'success';
  if (item.action === 'TASK_REJECTED' || item.action === 'INSTANCE_WITHDRAWN') return 'danger';
  if (['TASK_DELEGATED', 'TASK_RESUBMITTED', 'TASK_RETRIEVED', 'TASK_TRANSFERRED'].includes(item.action)) return 'warning';
  return 'primary';
}

async function loadActivePage() {
  loading.value = true;
  loadError.value = '';
  const parameters = { keyword: keyword.value, limit: pageSize, offset: pageOffset.value };
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
  } finally {
    loading.value = false;
  }
}

async function loadOverviewCounts() {
  const parameters = { limit: 1, offset: 0 };
  const [pending, processed, started] = await Promise.allSettled([
    findPendingTasks(parameters), findProcessedTasks(parameters), findStartedInstances(parameters),
  ]);
  if (pending.status === 'fulfilled') pendingTotal.value = pending.value.total;
  if (processed.status === 'fulfilled') processedTotal.value = processed.value.total;
  if (started.status === 'fulfilled') startedTotal.value = started.value.total;
}

async function refreshWorkbench() {
  await Promise.all([loadActivePage(), loadOverviewCounts()]);
}

async function openTask(task: PendingTaskItem) {
  drawerOpen.value = true;
  detailLoading.value = true;
  detailError.value = '';
  selectedTask.value = undefined;
  taskDelegation.value = undefined;
  timeline.value = undefined;
  formRuntime.value = undefined;
  formValues.value = {};
  approvalComment.value = '';
  transferTargetId.value = '';
  try {
    const [details, progress, delegation] = await Promise.all([
      findPendingTask(task.taskId),
      findApprovalTimeline(task.instanceId),
      findTaskDelegation(task.taskId),
    ]);
    selectedTask.value = details;
    timeline.value = progress;
    taskDelegation.value = delegation;
    try {
      const runtime = await findTaskFormRuntime(task.taskId);
      formRuntime.value = runtime;
      formValues.value = { ...runtime.values };
    } catch {
      formRuntime.value = undefined;
    }
  } catch (error) {
    detailError.value = errorMessage(error);
  } finally {
    detailLoading.value = false;
  }
}

async function confirmAction(title: string, message: string, confirmButtonText: string, type: 'error' | 'warning') {
  try {
    await ElMessageBox.confirm(message, title, { cancelButtonText: '取消', confirmButtonText, type });
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
  if (!task || !await confirmAction('审批确认', '确认同意该审批吗？', '确认同意', 'warning')) return;
  submitting.value = true;
  try {
    await approveTask(task.taskId, approvalComment.value);
    await finishDrawerAction('审批已同意');
  } catch (error) { ElMessage.error(errorMessage(error)); } finally { submitting.value = false; }
}

async function submitRejection() {
  const task = selectedTask.value;
  const comment = approvalComment.value.trim();
  if (!task) return;
  if (!comment) { ElMessage.warning('驳回时必须填写原因'); return; }
  if (!await confirmAction('驳回确认', '驳回后将生成发起人修改任务，确认继续吗？', '确认驳回', 'error')) return;
  submitting.value = true;
  try {
    await rejectTask(task.taskId, comment);
    await finishDrawerAction('已驳回到发起人');
  } catch (error) { ElMessage.error(errorMessage(error)); } finally { submitting.value = false; }
}

function editableFormValues() {
  if (!formRuntime.value) return {};
  return Object.fromEntries(Object.entries(formValues.value).filter(
    ([key]) => formRuntime.value?.fieldPermissions[key] === 'EDITABLE',
  ));
}

async function submitResubmission() {
  const task = selectedTask.value;
  if (!task) return;
  if (!await confirmAction('重新提交确认', '确认保存允许修改的字段并重新进入审批吗？', '重新提交', 'warning')) return;
  submitting.value = true;
  try {
    if (formRuntime.value) {
      await resubmitFormTask(task.taskId, approvalComment.value, editableFormValues());
    } else {
      await resubmitTask(task.taskId, approvalComment.value);
    }
    await finishDrawerAction('申请已重新提交');
  } catch (error) { ElMessage.error(errorMessage(error)); } finally { submitting.value = false; }
}

async function submitTransfer() {
  const task = selectedTask.value;
  const comment = approvalComment.value.trim();
  if (!task) return;
  if (!transferTargetId.value) { ElMessage.warning('请选择转办人员'); return; }
  if (!comment) { ElMessage.warning('转办时必须填写原因'); return; }
  const candidate = task.transferCandidates?.find(item => item.userId === transferTargetId.value);
  if (!await confirmAction('转办确认', `确认转办给 ${candidate?.displayName || transferTargetId.value} 吗？`, '确认转办', 'warning')) return;
  submitting.value = true;
  try {
    await transferTask(task.taskId, transferTargetId.value, comment);
    await finishDrawerAction('任务已转办');
  } catch (error) { ElMessage.error(errorMessage(error)); } finally { submitting.value = false; }
}

async function submitWithdrawal(item: StartedInstanceItem) {
  if (!item.withdrawable || listActionId.value) return;
  if (!await confirmAction('撤回确认', '撤回后当前审批任务将全部取消，确认继续吗？', '确认撤回', 'error')) return;
  listActionId.value = item.instanceId;
  try { await withdrawInstance(item.instanceId, '发起人从审批工作台撤回'); await refreshWorkbench(); }
  catch (error) { ElMessage.error(errorMessage(error)); } finally { listActionId.value = ''; }
}

async function submitRetrieve(item: ProcessedTaskItem) {
  if (!item.retrievable || listActionId.value) return;
  if (!await confirmAction('拿回确认', '拿回后下游待办将取消，确认继续吗？', '确认拿回', 'warning')) return;
  listActionId.value = item.taskId;
  try {
    await retrieveTask(item.taskId, '审批人从已处理列表拿回');
    activeTab.value = 'pending';
    await refreshWorkbench();
  } catch (error) { ElMessage.error(errorMessage(error)); } finally { listActionId.value = ''; }
}

async function changePage(page: number) {
  currentPage.value = page;
  await loadActivePage();
}

watch(activeTab, async () => { keyword.value = ''; currentPage.value = 1; await loadActivePage(); });
onMounted(refreshWorkbench);
</script>

<template>
  <Page title="审批工作台">
    <div class="workbench">
      <section class="overview-grid">
        <ElCard shadow="never" @click="activeTab = 'pending'"><div class="overview-card"><span>待我处理</span><strong>{{ pendingTotal }}</strong></div></ElCard>
        <ElCard shadow="never" @click="activeTab = 'processed'"><div class="overview-card"><span>我已处理</span><strong>{{ processedTotal }}</strong></div></ElCard>
        <ElCard shadow="never" @click="activeTab = 'started'"><div class="overview-card"><span>我发起的</span><strong>{{ startedTotal }}</strong></div></ElCard>
      </section>
      <ElCard shadow="never">
        <template #header><div class="section-header"><strong>审批事项</strong><ElButton :loading="loading" @click="refreshWorkbench">刷新</ElButton></div></template>
        <ElTabs v-model="activeTab"><ElTabPane :label="`待我处理 ${pendingTotal}`" name="pending"/><ElTabPane :label="`我已处理 ${processedTotal}`" name="processed"/><ElTabPane :label="`我发起的 ${startedTotal}`" name="started"/></ElTabs>
        <div class="search-bar"><ElInput v-model="keyword" clearable placeholder="搜索业务编号、供应商或任务" @keyup.enter="loadActivePage"/><ElButton type="primary" @click="loadActivePage">搜索</ElButton></div>
        <ElAlert v-if="loadError" :closable="false" :title="loadError" type="error"/>
        <ElSkeleton v-else-if="loading" :rows="5" animated/>
        <ElEmpty v-else-if="activeItemCount === 0" description="当前没有相关审批记录"/>
        <div v-else class="task-list">
          <article v-for="task in pendingPage.items" v-show="activeTab === 'pending'" :key="task.taskId" class="task-item">
            <div><strong>{{ task.supplier }}采购付款</strong><span>{{ task.businessKey }}</span></div>
            <div class="task-actions"><ElTag :type="taskTagType(task)">{{ taskStage(task) }}</ElTag><strong>{{ formatMoney(task.amount) }}</strong><ElButton type="primary" @click="openTask(task)">{{ task.taskDefinitionKey === 'initiatorRevision' ? '修改' : '处理' }}</ElButton></div>
          </article>
          <article v-for="task in processedPage.items" v-show="activeTab === 'processed'" :key="task.taskId" class="task-item">
            <div><strong>{{ task.supplier }}采购付款</strong><span>{{ task.businessKey }} · {{ formatDate(task.completedAt) }}</span></div>
            <div class="task-actions"><strong>{{ formatMoney(task.amount) }}</strong><ElButton v-if="task.retrievable" :loading="listActionId === task.taskId" @click="submitRetrieve(task)">拿回</ElButton></div>
          </article>
          <article v-for="item in startedPage.items" v-show="activeTab === 'started'" :key="item.instanceId" class="task-item">
            <div><strong>{{ item.supplier }}采购付款</strong><span>{{ item.businessKey }} · {{ item.currentTaskName || '流程已结束' }}</span></div>
            <div class="task-actions"><ElTag :type="instanceStatusType(item.status)">{{ instanceStatusLabel(item.status) }}</ElTag><ElButton v-if="item.withdrawable" :loading="listActionId === item.instanceId" type="danger" plain @click="submitWithdrawal(item)">撤回</ElButton></div>
          </article>
        </div>
        <ElPagination v-if="activeTotal > pageSize" :current-page="currentPage" :page-size="pageSize" :total="activeTotal" background layout="prev, pager, next, total" @current-change="changePage"/>
      </ElCard>
    </div>

    <ElDrawer v-model="drawerOpen" :title="drawerTitle" destroy-on-close size="720px">
      <ElSkeleton v-if="detailLoading" :rows="10" animated/>
      <ElAlert v-else-if="detailError" :closable="false" :title="detailError" type="error"/>
      <div v-else-if="selectedTask" class="detail-content">
        <ElAlert v-if="revisionTask" :closable="false" title="仅可修改当前节点允许编辑的字段。" type="warning"/>
        <ElAlert
          v-else-if="taskDelegation"
          :closable="false"
          show-icon
          type="warning"
          :title="`该任务由 ${taskDelegation.principalAssigneeId} 委托给 ${taskDelegation.delegateAssigneeId} 处理`"
        />
        <ElDescriptions :column="2" border title="申请信息">
          <ElDescriptionsItem label="业务编号">{{ selectedTask.businessKey }}</ElDescriptionsItem><ElDescriptionsItem label="当前环节">{{ taskStage(selectedTask) }}</ElDescriptionsItem><ElDescriptionsItem label="发起人">{{ selectedTask.initiatorId }}</ElDescriptionsItem><ElDescriptionsItem label="付款金额">{{ formatMoney(selectedTask.amount) }}</ElDescriptionsItem>
          <template v-if="taskDelegation">
            <ElDescriptionsItem label="原责任人">{{ taskDelegation.principalAssigneeId }}</ElDescriptionsItem>
            <ElDescriptionsItem label="实际处理人">{{ taskDelegation.delegateAssigneeId }}</ElDescriptionsItem>
            <ElDescriptionsItem label="代理范围">{{ taskDelegation.delegationScope === 'ALL' ? '全部审批' : taskDelegation.definitionKey }}</ElDescriptionsItem>
            <ElDescriptionsItem label="代理规则">{{ taskDelegation.delegationRuleId }}</ElDescriptionsItem>
          </template>
        </ElDescriptions>
        <section v-if="formRuntime" class="detail-section">
          <div class="section-header"><h3>申请表单</h3><ElTag effect="plain">{{ formRuntime.defaultedUiSchema ? '安全默认' : `UI v${formRuntime.uiSchema.version}` }}</ElTag></div>
          <ApprovalFormRenderer v-model="formValues" :field-permissions="formRuntime.fieldPermissions" :required-fields="formRuntime.requiredFields" :readonly="!revisionTask" :schema="formRuntime.definition" :ui-schema="formRuntime.uiSchema"/>
        </section>
        <section class="detail-section"><h3>审批进度</h3><ElTimeline v-if="timeline?.items.length"><ElTimelineItem v-for="item in timeline.items" :key="item.eventId" :timestamp="formatDate(item.occurredAt)" :type="timelineType(item)"><strong>{{ timelineTitle(item) }}</strong><div>{{ item.operatorId }}</div><div v-if="item.action === 'TASK_DELEGATED'">{{ item.attributes.principalAssigneeId }} → {{ item.attributes.delegateAssigneeId }}</div><div v-if="item.attributes.comment">{{ item.attributes.comment }}</div></ElTimelineItem></ElTimeline><ElEmpty v-else description="暂无审批记录"/></section>
        <section v-if="!revisionTask && selectedTask.transferCandidates?.length" class="detail-section"><h3>转办人员</h3><ElSelect v-model="transferTargetId" class="full-width" placeholder="从审批人快照中选择"><ElOption v-for="candidate in selectedTask.transferCandidates" :key="candidate.userId" :label="candidate.displayName" :value="candidate.userId"/></ElSelect></section>
        <section class="detail-section"><h3>{{ revisionTask ? '修改说明' : '审批意见' }}</h3><ElInput v-model="approvalComment" :maxlength="2000" :rows="4" show-word-limit type="textarea"/></section>
      </div>
      <template #footer><div class="drawer-footer"><ElButton @click="drawerOpen = false">取消</ElButton><div class="action-group"><ElButton v-if="revisionTask" :loading="submitting" type="primary" @click="submitResubmission">重新提交</ElButton><template v-else><ElButton v-if="selectedTask?.transferCandidates?.length" :loading="submitting" @click="submitTransfer">转办</ElButton><ElButton :loading="submitting" type="danger" plain @click="submitRejection">驳回</ElButton><ElButton :loading="submitting" type="primary" @click="submitApproval">同意</ElButton></template></div></div></template>
    </ElDrawer>
  </Page>
</template>

<style scoped>
.workbench,.detail-content{display:grid;gap:16px}.overview-grid{display:grid;grid-template-columns:repeat(3,minmax(220px,1fr));gap:16px}.overview-card{display:grid;gap:6px;cursor:pointer}.overview-card strong{font-size:32px}.section-header,.task-item,.task-actions,.search-bar,.drawer-footer,.action-group{display:flex;align-items:center;gap:12px}.section-header,.task-item,.drawer-footer{justify-content:space-between}.search-bar{margin:12px 0}.task-list{display:grid}.task-item{padding:18px 0;border-bottom:1px solid var(--el-border-color-lighter)}.task-item>div:first-child,.task-actions{display:grid;gap:6px}.task-item span{color:var(--el-text-color-secondary);font-size:13px}.task-actions{justify-items:end}.detail-section{padding-top:8px}.detail-section h3{margin:0 0 16px;font-size:16px}.full-width{width:100%}.drawer-footer{width:100%}@media(max-width:900px){.overview-grid{grid-template-columns:1fr}}@media(max-width:720px){.task-item,.search-bar{align-items:stretch;flex-direction:column}.task-actions{justify-items:start}}
</style>
