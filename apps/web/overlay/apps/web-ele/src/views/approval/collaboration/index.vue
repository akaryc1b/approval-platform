<script lang="ts" setup>
import type { PendingTaskItem } from '#/api/approval';
import type { ApprovalIdentityCandidate } from '#/api/approval/identities';
import type {
  CollaborationMode,
  CollaborationParticipant,
  CollaborationParticipantInput,
  PendingCollaborationTask,
  TaskCollaboration,
} from '#/api/approval/task-collaboration';

import { computed, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElEmpty,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElSelect,
  ElSkeleton,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import { findPendingTasks } from '#/api/approval';
import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';
import { findApprovalIdentityCandidates } from '#/api/approval/identities';
import {
  addTaskCollaborators,
  createTaskCollaboration,
  decideTaskCollaboration,
  findPendingCollaborationTasks,
  findTaskCollaboration,
  removeTaskCollaborator,
} from '#/api/approval/task-collaboration';

const currentOperatorId = getApprovalRuntimeConfig().operatorId;
const loading = ref(false);
const saving = ref(false);
const pendingTasks = ref<PendingTaskItem[]>([]);
const collaborationTasks = ref<PendingCollaborationTask[]>([]);
const selectedTaskId = ref('');
const selectedPolicy = ref<TaskCollaboration>();
const showAddForm = ref(false);
const mode = ref<CollaborationMode>('ALL');
const approvalThreshold = ref<number>();
const approvalWeightThreshold = ref<number>();
const reason = ref('');
const candidateLoading = ref(false);
const candidateOptions = ref<ApprovalIdentityCandidate[]>([]);
const selectedCandidateKeys = ref<string[]>([]);
const candidateWeights = ref<Record<string, number>>({});

const selectedTask = computed(
  () => pendingTasks.value.find(item => item.taskId === selectedTaskId.value),
);

const selectedCandidates = computed(() => candidateOptions.value.filter(
  item => selectedCandidateKeys.value.includes(identityKey(item)),
));

const decisionsStarted = computed(() => Boolean(selectedPolicy.value?.participants.some(
  item => item.status === 'APPROVED' || item.status === 'REJECTED',
)));

const canChangeParticipants = computed(() => selectedPolicy.value?.status === 'ACTIVE'
  && !decisionsStarted.value);

const currentMode = computed(() => selectedPolicy.value && showAddForm.value
  ? selectedPolicy.value.mode
  : mode.value);

const selectedWeight = computed(() => selectedCandidates.value.reduce(
  (total, candidate) => total + participantWeight(candidate),
  0,
));

const existingEligibleCount = computed(() => selectedPolicy.value?.participants.filter(
  item => item.status !== 'REMOVED',
).length || 0);

const existingTotalWeight = computed(() => selectedPolicy.value?.participants
  .filter(item => item.status !== 'REMOVED')
  .reduce((total, item) => total + item.weight, 0) || 0);

const previewParticipantCount = computed(() => (
  selectedPolicy.value && showAddForm.value ? existingEligibleCount.value : 0
) + selectedCandidates.value.length);

const previewTotalWeight = computed(() => (
  selectedPolicy.value && showAddForm.value ? existingTotalWeight.value : 0
) + selectedWeight.value);

const previewApprovalThreshold = computed(() => selectedPolicy.value && showAddForm.value
  ? selectedPolicy.value.approvalThreshold
  : approvalThreshold.value);

const previewApprovalWeightThreshold = computed(() => selectedPolicy.value && showAddForm.value
  ? selectedPolicy.value.approvalWeightThreshold
  : approvalWeightThreshold.value);

const ruleSummary = computed(() => {
  if (currentMode.value === 'ALL') {
    return `${previewParticipantCount.value} 人全部同意后满足协作要求`;
  }
  if (currentMode.value === 'ANY') {
    return '任一参与人同意即可满足；全部拒绝后协作失败';
  }
  if (currentMode.value === 'VOTE') {
    return `同意票达到 ${previewApprovalThreshold.value || 0} / ${previewParticipantCount.value} 后满足`;
  }
  return `同意权重达到 ${previewApprovalWeightThreshold.value || 0} / ${previewTotalWeight.value} 后满足`;
});

function identityKey(candidate: ApprovalIdentityCandidate) {
  const { source, objectType, value } = candidate.reference;
  return `${source}\u001f${objectType}\u001f${value}`;
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试';
}

function modeLabel(value: CollaborationMode) {
  return {
    ALL: '全部通过',
    ANY: '任一通过',
    VOTE: '投票通过',
    WEIGHTED: '加权通过',
  }[value];
}

function policyStatusLabel(status: TaskCollaboration['status']) {
  return {
    ACTIVE: '协作中',
    CANCELED: '已取消',
    REJECTED: '未达到要求',
    SATISFIED: '已满足',
  }[status];
}

function participantStatusLabel(status: CollaborationParticipant['status']) {
  return {
    APPROVED: '已同意',
    CANCELED: '已取消',
    PENDING: '待处理',
    REJECTED: '已拒绝',
    REMOVED: '已减签',
  }[status];
}

function statusType(status: CollaborationParticipant['status']) {
  if (status === 'APPROVED') return 'success';
  if (status === 'REJECTED') return 'danger';
  if (status === 'PENDING') return 'warning';
  return 'info';
}

function policyStatusType(status: TaskCollaboration['status']) {
  if (status === 'SATISFIED') return 'success';
  if (status === 'REJECTED') return 'danger';
  if (status === 'ACTIVE') return 'warning';
  return 'info';
}

function thresholdLabel(policy: Pick<
  PendingCollaborationTask,
  'approvalThreshold' | 'approvalWeightThreshold' | 'mode'
>) {
  if (policy.mode === 'VOTE') return `${policy.approvalThreshold} 票`;
  if (policy.mode === 'WEIGHTED') return `${policy.approvalWeightThreshold} 权重`;
  return '—';
}

function participantWeight(candidate: ApprovalIdentityCandidate) {
  if (currentMode.value !== 'WEIGHTED') return 1;
  return candidateWeights.value[identityKey(candidate)] || 1;
}

function setParticipantWeight(candidate: ApprovalIdentityCandidate, value?: number) {
  candidateWeights.value = {
    ...candidateWeights.value,
    [identityKey(candidate)]: Math.max(1, value || 1),
  };
}

function resetForm() {
  mode.value = 'ALL';
  approvalThreshold.value = undefined;
  approvalWeightThreshold.value = undefined;
  reason.value = '';
  selectedCandidateKeys.value = [];
  candidateWeights.value = {};
  showAddForm.value = false;
}

function onModeChanged() {
  approvalThreshold.value = mode.value === 'VOTE'
    ? Math.max(1, Math.min(approvalThreshold.value || 1, selectedCandidates.value.length || 1))
    : undefined;
  approvalWeightThreshold.value = mode.value === 'WEIGHTED'
    ? Math.max(1, Math.min(approvalWeightThreshold.value || 1, selectedWeight.value || 1))
    : undefined;
}

async function loadData() {
  loading.value = true;
  try {
    const [pending, collaboration] = await Promise.all([
      findPendingTasks({ limit: 100, offset: 0 }),
      findPendingCollaborationTasks(100),
    ]);
    pendingTasks.value = pending.items.filter(
      item => item.taskDefinitionKey !== 'initiatorRevision',
    );
    collaborationTasks.value = collaboration;
    if (selectedTaskId.value) await loadPolicy(selectedTaskId.value, false);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

async function loadPolicy(taskId: string, clearForm = true) {
  selectedTaskId.value = taskId;
  selectedPolicy.value = await findTaskCollaboration(taskId);
  if (clearForm) resetForm();
  if (!selectedPolicy.value) await searchCandidates('');
}

async function searchCandidates(keyword: string) {
  candidateLoading.value = true;
  try {
    const candidates = await findApprovalIdentityCandidates(keyword, 50, true);
    const ownerId = selectedPolicy.value?.ownerAssigneeId || currentOperatorId;
    const existingUsers = new Set(
      selectedPolicy.value?.participants.map(item => item.participantUserId) || [],
    );
    const merged = new Map(candidateOptions.value.map(item => [identityKey(item), item]));
    for (const candidate of candidates) {
      if (candidate.userId !== ownerId && !existingUsers.has(candidate.userId)) {
        merged.set(identityKey(candidate), candidate);
      }
    }
    candidateOptions.value = [...merged.values()];
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    candidateLoading.value = false;
  }
}

async function openAddParticipants() {
  if (!canChangeParticipants.value) return;
  reason.value = '';
  selectedCandidateKeys.value = [];
  candidateWeights.value = {};
  showAddForm.value = true;
  await searchCandidates('');
}

function participantInputs(): CollaborationParticipantInput[] {
  return selectedCandidates.value.map(candidate => ({
    ...candidate.reference,
    weight: participantWeight(candidate),
  }));
}

function validateForm() {
  if (selectedCandidates.value.length === 0) {
    ElMessage.warning('请选择至少一名协作人员');
    return false;
  }
  if (!reason.value.trim()) {
    ElMessage.warning(showAddForm.value ? '请输入追加原因' : '请输入协作原因');
    return false;
  }
  if (currentMode.value === 'VOTE') {
    const threshold = previewApprovalThreshold.value || 0;
    if (threshold < 1 || threshold > previewParticipantCount.value) {
      ElMessage.warning('投票阈值必须在 1 和参与人数之间');
      return false;
    }
  }
  if (currentMode.value === 'WEIGHTED') {
    const threshold = previewApprovalWeightThreshold.value || 0;
    if (threshold < 1 || threshold > previewTotalWeight.value) {
      ElMessage.warning('加权阈值必须在 1 和总权重之间');
      return false;
    }
  }
  return true;
}

async function submitParticipants() {
  const task = selectedTask.value;
  if (!task || !validateForm()) return;
  saving.value = true;
  try {
    if (selectedPolicy.value && showAddForm.value) {
      selectedPolicy.value = await addTaskCollaborators(
        task.taskId,
        participantInputs(),
        reason.value,
      );
      ElMessage.success('协作人员已追加');
      showAddForm.value = false;
    } else {
      selectedPolicy.value = await createTaskCollaboration(
        task.taskId,
        mode.value,
        participantInputs(),
        reason.value,
        mode.value === 'VOTE' ? approvalThreshold.value : undefined,
        mode.value === 'WEIGHTED' ? approvalWeightThreshold.value : undefined,
      );
      ElMessage.success('协作策略已创建');
    }
    await loadData();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}

async function removeParticipant(participant: CollaborationParticipant) {
  let removalReason = '';
  try {
    const result = await ElMessageBox.prompt(
      `确认移除 ${participant.participantUserId} 吗？`,
      '减签确认',
      {
        cancelButtonText: '取消',
        confirmButtonText: '确认减签',
        inputPlaceholder: '请输入减签原因',
        inputValidator: value => Boolean(value?.trim()) || '减签原因不能为空',
        type: 'warning',
      },
    );
    removalReason = result.value.trim();
  } catch {
    return;
  }
  saving.value = true;
  try {
    selectedPolicy.value = await removeTaskCollaborator(
      participant.participantId,
      removalReason,
    );
    ElMessage.success('已完成减签');
    await loadData();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}

async function decide(item: PendingCollaborationTask, decision: 'APPROVED' | 'REJECTED') {
  let comment = '';
  try {
    const result = await ElMessageBox.prompt(
      decision === 'APPROVED' ? '确认同意该协作事项吗？' : '确认拒绝该协作事项吗？',
      decision === 'APPROVED' ? '协作同意' : '协作拒绝',
      {
        cancelButtonText: '取消',
        confirmButtonText: decision === 'APPROVED' ? '确认同意' : '确认拒绝',
        inputPlaceholder: '请输入协作意见',
        inputValidator: value => Boolean(value?.trim()) || '协作意见不能为空',
        type: decision === 'APPROVED' ? 'warning' : 'error',
      },
    );
    comment = result.value.trim();
  } catch {
    return;
  }
  saving.value = true;
  try {
    await decideTaskCollaboration(item.participantId, decision, comment);
    ElMessage.success(decision === 'APPROVED' ? '已同意协作事项' : '已拒绝协作事项');
    await loadData();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}

onMounted(loadData);
</script>

<template>
  <Page
    title="加签协作"
    description="支持全部通过、任一通过、投票通过和加权通过，协作进度与参与人责任清晰可查。"
  >
    <div class="collaboration-page">
      <ElAlert
        :closable="false"
        show-icon
        title="协作进行中时原任务不能同意、转办或拿回；协作未达到要求后，原审批人只能驳回。"
        type="info"
      />

      <ElCard shadow="never">
        <template #header>
          <div class="card-header">
            <div>
              <strong>待我协作</strong>
              <div class="muted">处理分配给我的协作意见。</div>
            </div>
            <ElButton :loading="loading" @click="loadData">刷新</ElButton>
          </div>
        </template>
        <ElSkeleton :loading="loading" :rows="3" animated>
          <ElEmpty v-if="collaborationTasks.length === 0" description="暂无待处理的协作事项" />
          <ElTable v-else :data="collaborationTasks" row-key="participantId">
            <ElTableColumn label="任务" min-width="180" prop="taskName" />
            <ElTableColumn label="原审批人" min-width="140" prop="ownerAssigneeId" />
            <ElTableColumn label="规则" width="110">
              <template #default="{ row }">
                <ElTag effect="plain">{{ modeLabel(row.mode) }}</ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="阈值" width="110">
              <template #default="{ row }">{{ thresholdLabel(row) }}</template>
            </ElTableColumn>
            <ElTableColumn label="我的权重" width="100" prop="participantWeight" />
            <ElTableColumn label="当前进度" width="130">
              <template #default="{ row }">
                {{ row.progress.approvedCount }} 同意 / {{ row.progress.pendingCount }} 待处理
              </template>
            </ElTableColumn>
            <ElTableColumn label="原因" min-width="220" prop="reason" show-overflow-tooltip />
            <ElTableColumn fixed="right" label="操作" width="170">
              <template #default="{ row }">
                <ElButton :disabled="saving" link type="danger" @click="decide(row, 'REJECTED')">
                  拒绝
                </ElButton>
                <ElButton :disabled="saving" link type="primary" @click="decide(row, 'APPROVED')">
                  同意
                </ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
        </ElSkeleton>
      </ElCard>

      <div class="management-grid">
        <ElCard shadow="never">
          <template #header><strong>我的审批任务</strong></template>
          <ElEmpty v-if="pendingTasks.length === 0" description="暂无可发起协作的待办" />
          <div v-else class="task-list">
            <button
              v-for="task in pendingTasks"
              :key="task.taskId"
              class="task-button"
              :class="{ active: selectedTaskId === task.taskId }"
              type="button"
              @click="loadPolicy(task.taskId)"
            >
              <span>{{ task.taskName }}</span>
              <small>{{ task.supplier }} · {{ task.businessKey }}</small>
            </button>
          </div>
        </ElCard>

        <ElCard shadow="never">
          <template #header>
            <div class="card-header">
              <strong>协作策略</strong>
              <ElButton
                v-if="selectedPolicy && canChangeParticipants && !showAddForm"
                link
                type="primary"
                @click="openAddParticipants"
              >
                追加参与人
              </ElButton>
            </div>
          </template>
          <ElEmpty v-if="!selectedTask" description="请先选择一个待办任务" />
          <template v-else-if="selectedPolicy">
            <ElDescriptions :column="3" border>
              <ElDescriptionsItem label="任务">{{ selectedPolicy.taskName }}</ElDescriptionsItem>
              <ElDescriptionsItem label="状态">
                <ElTag :type="policyStatusType(selectedPolicy.status)">
                  {{ policyStatusLabel(selectedPolicy.status) }}
                </ElTag>
              </ElDescriptionsItem>
              <ElDescriptionsItem label="规则">{{ modeLabel(selectedPolicy.mode) }}</ElDescriptionsItem>
              <ElDescriptionsItem label="同意人数">
                {{ selectedPolicy.progress.approvedCount }} / {{ selectedPolicy.progress.eligibleParticipantCount }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="同意权重">
                {{ selectedPolicy.progress.approvedWeight }} / {{ selectedPolicy.progress.totalWeight }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="阈值">
                {{ thresholdLabel(selectedPolicy) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="剩余可达票数">
                {{ selectedPolicy.progress.maximumReachableApprovalCount }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="剩余可达权重">
                {{ selectedPolicy.progress.maximumReachableApprovalWeight }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="原因">{{ selectedPolicy.reason }}</ElDescriptionsItem>
            </ElDescriptions>
            <div class="participant-list">
              <div
                v-for="participant in selectedPolicy.participants"
                :key="participant.participantId"
                class="participant-row"
              >
                <div>
                  <strong>{{ participant.participantUserId }}</strong>
                  <small>
                    {{ participant.identity.source }} / {{ participant.identity.objectType }} · 权重 {{ participant.weight }}
                  </small>
                  <small v-if="participant.decisionComment">{{ participant.decisionComment }}</small>
                </div>
                <div class="participant-actions">
                  <ElTag :type="statusType(participant.status)">
                    {{ participantStatusLabel(participant.status) }}
                  </ElTag>
                  <ElButton
                    v-if="canChangeParticipants && participant.status === 'PENDING'"
                    :disabled="saving"
                    link
                    type="danger"
                    @click="removeParticipant(participant)"
                  >
                    减签
                  </ElButton>
                </div>
              </div>
            </div>
          </template>

          <div v-if="selectedTask && (!selectedPolicy || showAddForm)" class="form-grid">
            <label v-if="!selectedPolicy">
              <span>协作规则</span>
              <ElSelect v-model="mode" class="full-width" @change="onModeChanged">
                <ElOption label="全部人员通过" value="ALL" />
                <ElOption label="任一人员通过" value="ANY" />
                <ElOption label="达到指定票数" value="VOTE" />
                <ElOption label="达到指定权重" value="WEIGHTED" />
              </ElSelect>
            </label>
            <label>
              <span>协作人员</span>
              <ElSelect
                v-model="selectedCandidateKeys"
                class="full-width"
                filterable
                multiple
                remote
                :loading="candidateLoading"
                placeholder="搜索姓名、账号、邮箱或手机号"
                :remote-method="searchCandidates"
              >
                <ElOption
                  v-for="candidate in candidateOptions"
                  :key="identityKey(candidate)"
                  :label="`${candidate.displayName}（${candidate.username}）`"
                  :value="identityKey(candidate)"
                />
              </ElSelect>
            </label>
            <div v-if="currentMode === 'WEIGHTED' && selectedCandidates.length" class="weight-list">
              <div v-for="candidate in selectedCandidates" :key="identityKey(candidate)" class="weight-row">
                <span>{{ candidate.displayName }}</span>
                <ElInputNumber
                  :min="1"
                  :max="1000"
                  :model-value="participantWeight(candidate)"
                  @update:model-value="value => setParticipantWeight(candidate, value)"
                />
              </div>
            </div>
            <label v-if="!selectedPolicy && mode === 'VOTE'">
              <span>通过票数</span>
              <ElInputNumber
                v-model="approvalThreshold"
                class="full-width"
                :min="1"
                :max="Math.max(1, selectedCandidates.length)"
              />
            </label>
            <label v-if="!selectedPolicy && mode === 'WEIGHTED'">
              <span>通过权重</span>
              <ElInputNumber
                v-model="approvalWeightThreshold"
                class="full-width"
                :min="1"
                :max="Math.max(1, selectedWeight)"
              />
            </label>
            <div class="rule-preview">
              <strong>规则预览</strong>
              <span>参与人数：{{ previewParticipantCount }}</span>
              <span>总权重：{{ previewTotalWeight }}</span>
              <span>{{ ruleSummary }}</span>
            </div>
            <label>
              <span>{{ selectedPolicy ? '追加原因' : '协作原因' }}</span>
              <ElInput
                v-model="reason"
                :maxlength="2000"
                :rows="4"
                :placeholder="selectedPolicy ? '说明追加参与人的原因' : '说明需要补充评审的原因'"
                show-word-limit
                type="textarea"
              />
            </label>
            <div class="form-actions">
              <ElButton v-if="selectedPolicy" @click="showAddForm = false">取消</ElButton>
              <ElButton :loading="saving" type="primary" @click="submitParticipants">
                {{ selectedPolicy ? '确认追加' : '发起协作' }}
              </ElButton>
            </div>
          </div>
        </ElCard>
      </div>
    </div>
  </Page>
</template>

<style scoped>
.collaboration-page,.form-grid,.participant-list,.task-list,.weight-list{display:grid;gap:16px}.management-grid{display:grid;grid-template-columns:minmax(280px,360px) minmax(0,1fr);gap:16px}.card-header,.participant-row,.participant-actions,.weight-row,.form-actions{display:flex;align-items:center;justify-content:space-between;gap:12px}.muted,.task-button small,.participant-row small{color:var(--el-text-color-secondary);font-size:13px}.task-button{display:grid;gap:6px;padding:14px;text-align:left;border:1px solid var(--el-border-color);border-radius:8px;background:var(--el-fill-color-blank);cursor:pointer}.task-button.active{border-color:var(--el-color-primary);background:var(--el-color-primary-light-9)}.participant-row{padding:12px 0;border-bottom:1px solid var(--el-border-color-lighter)}.participant-row>div:first-child{display:grid;gap:4px}.form-grid{margin-top:18px}.form-grid label{display:grid;gap:8px}.full-width{width:100%}.rule-preview{display:flex;flex-wrap:wrap;gap:12px;padding:14px;border-radius:8px;background:var(--el-fill-color-light)}.weight-row{padding:10px 12px;border:1px solid var(--el-border-color-lighter);border-radius:8px}.form-actions{justify-content:flex-end}@media(max-width:900px){.management-grid{grid-template-columns:1fr}}
</style>
