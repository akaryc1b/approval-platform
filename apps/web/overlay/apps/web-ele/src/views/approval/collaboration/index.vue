<script lang="ts" setup>
import type { PendingTaskItem } from '#/api/approval';
import type { ApprovalIdentityCandidate } from '#/api/approval/identities';
import type {
  CollaborationMode,
  CollaborationParticipant,
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
import { findApprovalIdentityCandidates } from '#/api/approval/identities';
import {
  createTaskCollaboration,
  decideTaskCollaboration,
  findPendingCollaborationTasks,
  findTaskCollaboration,
  removeTaskCollaborator,
} from '#/api/approval/task-collaboration';

const loading = ref(false);
const saving = ref(false);
const pendingTasks = ref<PendingTaskItem[]>([]);
const collaborationTasks = ref<PendingCollaborationTask[]>([]);
const selectedTaskId = ref('');
const selectedPolicy = ref<TaskCollaboration>();
const mode = ref<CollaborationMode>('ALL');
const reason = ref('');
const candidateLoading = ref(false);
const candidateOptions = ref<ApprovalIdentityCandidate[]>([]);
const selectedCandidateKeys = ref<string[]>([]);

const selectedTask = computed(
  () => pendingTasks.value.find(item => item.taskId === selectedTaskId.value),
);

const activeParticipants = computed(
  () => selectedPolicy.value?.participants.filter(item => item.status !== 'REMOVED') || [],
);

function identityKey(candidate: ApprovalIdentityCandidate) {
  const { source, objectType, value } = candidate.reference;
  return `${source}\u001f${objectType}\u001f${value}`;
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试';
}

function modeLabel(value: CollaborationMode) {
  return value === 'ALL' ? '全部通过' : '任一通过';
}

function policyStatusLabel(status: TaskCollaboration['status']) {
  return {
    ACTIVE: '协作中',
    CANCELED: '已取消',
    REJECTED: '已拒绝',
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
    if (selectedTaskId.value) await loadPolicy(selectedTaskId.value);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

async function loadPolicy(taskId: string) {
  selectedTaskId.value = taskId;
  selectedPolicy.value = await findTaskCollaboration(taskId);
  reason.value = '';
  selectedCandidateKeys.value = [];
  if (!selectedPolicy.value) await searchCandidates('');
}

async function searchCandidates(keyword: string) {
  candidateLoading.value = true;
  try {
    const candidates = await findApprovalIdentityCandidates(keyword, 50, true);
    const merged = new Map(candidateOptions.value.map(item => [identityKey(item), item]));
    for (const candidate of candidates) merged.set(identityKey(candidate), candidate);
    candidateOptions.value = [...merged.values()];
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    candidateLoading.value = false;
  }
}

async function createPolicy() {
  const task = selectedTask.value;
  if (!task) return;
  const normalizedReason = reason.value.trim();
  const selected = candidateOptions.value.filter(
    item => selectedCandidateKeys.value.includes(identityKey(item)),
  );
  if (selected.length === 0) {
    ElMessage.warning('请选择至少一名加签人员');
    return;
  }
  if (!normalizedReason) {
    ElMessage.warning('请输入加签原因');
    return;
  }
  saving.value = true;
  try {
    selectedPolicy.value = await createTaskCollaboration(
      task.taskId,
      mode.value,
      selected.map(item => item.reference),
      normalizedReason,
    );
    ElMessage.success('加签协作已创建');
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
      decision === 'APPROVED' ? '确认同意该加签事项吗？' : '确认拒绝该加签事项吗？',
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
    ElMessage.success(decision === 'APPROVED' ? '已同意加签事项' : '已拒绝加签事项');
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
    description="运行中的审批任务可发起全部通过或任一通过协作；所有参与人和决策均由平台留痕。"
  >
    <div class="collaboration-page">
      <ElAlert
        :closable="false"
        show-icon
        title="协作未完成时，原审批任务不能同意、转办或拿回；任一参与人拒绝后，原审批人只能驳回任务。"
        type="info"
      />

      <ElCard shadow="never">
        <template #header>
          <div class="card-header">
            <div>
              <strong>待我协作</strong>
              <div class="muted">这些事项不会改变原任务责任人，仅提供受治理的协作决策。</div>
            </div>
            <ElButton :loading="loading" @click="loadData">刷新</ElButton>
          </div>
        </template>
        <ElSkeleton :loading="loading" :rows="3" animated>
          <ElEmpty v-if="collaborationTasks.length === 0" description="暂无待处理的加签事项" />
          <ElTable v-else :data="collaborationTasks" row-key="participantId">
            <ElTableColumn label="任务" min-width="180" prop="taskName" />
            <ElTableColumn label="原审批人" min-width="140" prop="ownerAssigneeId" />
            <ElTableColumn label="规则" width="110">
              <template #default="{ row }">
                <ElTag effect="plain">{{ modeLabel(row.mode) }}</ElTag>
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
          <ElEmpty v-if="pendingTasks.length === 0" description="暂无可发起加签的待办" />
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
          <template #header><strong>协作策略</strong></template>
          <ElEmpty v-if="!selectedTask" description="请先选择一个待办任务" />
          <template v-else-if="selectedPolicy">
            <ElDescriptions :column="2" border>
              <ElDescriptionsItem label="任务">{{ selectedPolicy.taskName }}</ElDescriptionsItem>
              <ElDescriptionsItem label="状态">
                <ElTag>{{ policyStatusLabel(selectedPolicy.status) }}</ElTag>
              </ElDescriptionsItem>
              <ElDescriptionsItem label="规则">{{ modeLabel(selectedPolicy.mode) }}</ElDescriptionsItem>
              <ElDescriptionsItem label="原因">{{ selectedPolicy.reason }}</ElDescriptionsItem>
            </ElDescriptions>
            <div class="participant-list">
              <div v-for="participant in activeParticipants" :key="participant.participantId" class="participant-row">
                <div>
                  <strong>{{ participant.participantUserId }}</strong>
                  <small>{{ participant.identity.source }} / {{ participant.identity.objectType }}</small>
                </div>
                <div class="participant-actions">
                  <ElTag :type="statusType(participant.status)">
                    {{ participantStatusLabel(participant.status) }}
                  </ElTag>
                  <ElButton
                    v-if="selectedPolicy.status === 'ACTIVE' && participant.status === 'PENDING'"
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
          <template v-else>
            <div class="form-grid">
              <label>
                <span>协作规则</span>
                <ElSelect v-model="mode" class="full-width">
                  <ElOption label="全部人员通过" value="ALL" />
                  <ElOption label="任一人员通过" value="ANY" />
                </ElSelect>
              </label>
              <label>
                <span>加签人员</span>
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
              <label>
                <span>加签原因</span>
                <ElInput
                  v-model="reason"
                  :maxlength="2000"
                  :rows="4"
                  placeholder="说明需要补充评审的原因"
                  show-word-limit
                  type="textarea"
                />
              </label>
              <ElButton :loading="saving" type="primary" @click="createPolicy">
                发起加签
              </ElButton>
            </div>
          </template>
        </ElCard>
      </div>
    </div>
  </Page>
</template>

<style scoped>
.collaboration-page,.form-grid,.participant-list,.task-list{display:grid;gap:16px}.management-grid{display:grid;grid-template-columns:minmax(280px,360px) minmax(0,1fr);gap:16px}.card-header,.participant-row,.participant-actions{display:flex;align-items:center;justify-content:space-between;gap:12px}.muted,.task-button small,.participant-row small{color:var(--el-text-color-secondary);font-size:13px}.task-button{display:grid;gap:6px;padding:14px;text-align:left;border:1px solid var(--el-border-color);border-radius:8px;background:var(--el-fill-color-blank);cursor:pointer}.task-button.active{border-color:var(--el-color-primary);background:var(--el-color-primary-light-9)}.participant-row{padding:12px 0;border-bottom:1px solid var(--el-border-color-lighter)}.participant-row>div:first-child{display:grid;gap:4px}.form-grid label{display:grid;gap:8px}.full-width{width:100%}@media(max-width:900px){.management-grid{grid-template-columns:1fr}}
</style>
