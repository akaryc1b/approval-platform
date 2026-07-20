<script lang="ts" setup>
import type { PrincipalHandover } from '#/api/approval/handovers';
import type { ApprovalIdentityCandidate } from '#/api/approval/identities';

import { computed, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElSelect,
  ElSkeleton,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import {
  createEmployeeHandover,
  findEmployeeHandovers,
  revokeEmployeeHandover,
} from '#/api/approval/handovers';
import { findApprovalIdentityCandidates } from '#/api/approval/identities';

const principalCandidates = ref<ApprovalIdentityCandidate[]>([]);
const successorCandidates = ref<ApprovalIdentityCandidate[]>([]);
const principalLoading = ref(false);
const successorLoading = ref(false);
const loading = ref(false);
const saving = ref(false);
const includeRevoked = ref(false);
const principalKey = ref('');
const successorKey = ref('');
const reason = ref('');
const handovers = ref<PrincipalHandover[]>([]);

const selectedPrincipal = computed(
  () => principalCandidates.value.find(item => identityKey(item) === principalKey.value),
);
const selectedSuccessor = computed(
  () => successorCandidates.value.find(item => identityKey(item) === successorKey.value),
);
const activeCount = computed(
  () => handovers.value.filter(item => item.status === 'ACTIVE').length,
);

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  month: '2-digit',
  year: 'numeric',
});

function identityKey(candidate: ApprovalIdentityCandidate) {
  const reference = candidate.reference;
  return `${reference.source}:${reference.objectType}:${reference.value}`;
}

function candidateLabel(candidate: ApprovalIdentityCandidate) {
  return `${candidate.displayName}（${candidate.userId}）${candidate.active ? '' : ' · 已停用'}`;
}

function formatDate(value?: string) {
  return value ? dateFormatter.format(new Date(value)) : '-';
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试';
}

async function searchPrincipals(keyword = '') {
  principalLoading.value = true;
  try {
    principalCandidates.value = await findApprovalIdentityCandidates(keyword, 40, false);
  } catch (error) {
    principalCandidates.value = [];
    ElMessage.error(errorMessage(error));
  } finally {
    principalLoading.value = false;
  }
}

async function searchSuccessors(keyword = '') {
  successorLoading.value = true;
  try {
    successorCandidates.value = await findApprovalIdentityCandidates(keyword, 40, true);
  } catch (error) {
    successorCandidates.value = [];
    ElMessage.error(errorMessage(error));
  } finally {
    successorLoading.value = false;
  }
}

async function loadHandovers() {
  const principal = selectedPrincipal.value;
  if (!principal) {
    handovers.value = [];
    return;
  }
  loading.value = true;
  try {
    handovers.value = await findEmployeeHandovers(
      principal.userId,
      includeRevoked.value,
    );
  } catch (error) {
    handovers.value = [];
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

async function principalChanged() {
  successorKey.value = '';
  reason.value = '';
  await loadHandovers();
}

async function submitCreate() {
  const principal = selectedPrincipal.value;
  const successor = selectedSuccessor.value;
  const normalizedReason = reason.value.trim();
  if (!principal) {
    ElMessage.warning('请从组织身份目录选择离职人员');
    return;
  }
  if (!successor) {
    ElMessage.warning('请从组织身份目录选择活跃接任人');
    return;
  }
  if (identityKey(principal) === identityKey(successor)) {
    ElMessage.warning('接任人不能与离职人员相同');
    return;
  }
  if (!normalizedReason) {
    ElMessage.warning('请输入交接原因');
    return;
  }
  try {
    await ElMessageBox.confirm(
      '创建后会立即转移该人员当前所有待办，并持续接管后续新任务。确认继续吗？',
      '确认离职交接',
      {
        cancelButtonText: '取消',
        confirmButtonText: '创建并转移',
        type: 'warning',
      },
    );
  } catch {
    return;
  }
  saving.value = true;
  try {
    const result = await createEmployeeHandover(
      principal,
      successor,
      normalizedReason,
    );
    ElMessage.success(`交接已创建，已转移 ${result.transferredTaskCount} 个待办`);
    successorKey.value = '';
    reason.value = '';
    await loadHandovers();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}

async function revoke(handover: PrincipalHandover) {
  let revokeReason = '';
  try {
    const result = await ElMessageBox.prompt(
      '撤销后只停止后续新任务自动交接，已经转移的任务不会自动退回。',
      '撤销离职交接',
      {
        cancelButtonText: '取消',
        confirmButtonText: '确认撤销',
        inputPlaceholder: '请输入撤销原因',
        inputValidator: value => Boolean(value?.trim()) || '撤销原因不能为空',
        type: 'warning',
      },
    );
    revokeReason = result.value.trim();
  } catch {
    return;
  }
  try {
    await revokeEmployeeHandover(handover.handoverId, revokeReason);
    ElMessage.success('离职交接已撤销');
    await loadHandovers();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}
</script>

<template>
  <Page
    title="离职交接"
    description="使用组织连接器中的精确身份建立人员交接。现有待办立即转移，后续任务自动由接任人处理，原责任人证据永久保留。"
  >
    <div class="handover-page">
      <ElAlert
        :closable="false"
        show-icon
        title="接任人必须为活跃账号；撤销交接不会自动退回已经转移的任务。"
        type="warning"
      />

      <ElCard shadow="never">
        <template #header><strong>创建交接</strong></template>
        <ElForm label-position="top">
          <div class="identity-grid">
            <ElFormItem label="离职人员" required>
              <ElSelect
                v-model="principalKey"
                class="full-width"
                filterable
                remote
                reserve-keyword
                :loading="principalLoading"
                placeholder="搜索姓名、账号或手机号，可包含停用账号"
                :remote-method="searchPrincipals"
                @change="principalChanged"
              >
                <ElOption
                  v-for="candidate in principalCandidates"
                  :key="identityKey(candidate)"
                  :label="candidateLabel(candidate)"
                  :value="identityKey(candidate)"
                >
                  <div class="candidate-option">
                    <span>{{ candidate.displayName }} · {{ candidate.userId }}</span>
                    <ElTag :type="candidate.active ? 'success' : 'info'" size="small">
                      {{ candidate.active ? '活跃' : '已停用' }}
                    </ElTag>
                  </div>
                </ElOption>
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="接任人员" required>
              <ElSelect
                v-model="successorKey"
                class="full-width"
                filterable
                remote
                reserve-keyword
                :loading="successorLoading"
                placeholder="搜索活跃接任人"
                :remote-method="searchSuccessors"
              >
                <ElOption
                  v-for="candidate in successorCandidates"
                  :key="identityKey(candidate)"
                  :label="candidateLabel(candidate)"
                  :value="identityKey(candidate)"
                  :disabled="identityKey(candidate) === principalKey"
                />
              </ElSelect>
            </ElFormItem>
          </div>
          <ElFormItem label="交接原因" required>
            <ElInput
              v-model="reason"
              :rows="3"
              maxlength="2000"
              placeholder="说明离职、调岗或长期职责变更原因"
              show-word-limit
              type="textarea"
            />
          </ElFormItem>
          <ElButton :loading="saving" type="primary" @click="submitCreate">
            创建交接并转移待办
          </ElButton>
        </ElForm>
      </ElCard>

      <ElCard shadow="never">
        <template #header>
          <div class="card-header">
            <div>
              <strong>交接记录</strong>
              <div class="muted">当前有效 {{ activeCount }} 条</div>
            </div>
            <div class="header-actions">
              <span class="muted">显示已撤销</span>
              <ElSwitch v-model="includeRevoked" @change="loadHandovers" />
              <ElButton :disabled="!selectedPrincipal" :loading="loading" @click="loadHandovers">
                刷新
              </ElButton>
            </div>
          </div>
        </template>
        <ElSkeleton :loading="loading" :rows="4" animated>
          <ElEmpty v-if="!selectedPrincipal" description="请先选择离职人员" />
          <ElEmpty v-else-if="handovers.length === 0" description="暂无交接记录" />
          <ElTable v-else :data="handovers" row-key="handoverId">
            <ElTableColumn label="原责任人" min-width="150" prop="principalId" />
            <ElTableColumn label="接任人" min-width="150" prop="successorId" />
            <ElTableColumn label="原因" min-width="220" prop="reason" show-overflow-tooltip />
            <ElTableColumn label="创建时间" min-width="180">
              <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="100">
              <template #default="{ row }">
                <ElTag :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                  {{ row.status === 'ACTIVE' ? '生效中' : '已撤销' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn fixed="right" label="操作" width="100">
              <template #default="{ row }">
                <ElButton
                  v-if="row.status === 'ACTIVE'"
                  link
                  type="danger"
                  @click="revoke(row)"
                >
                  撤销
                </ElButton>
                <span v-else class="muted">—</span>
              </template>
            </ElTableColumn>
          </ElTable>
        </ElSkeleton>
      </ElCard>
    </div>
  </Page>
</template>

<style scoped>
.handover-page{display:grid;gap:16px}.identity-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.full-width{width:100%}.candidate-option,.card-header,.header-actions{display:flex;align-items:center;gap:12px}.candidate-option,.card-header{justify-content:space-between}.muted{color:var(--el-text-color-secondary);font-size:13px}@media(max-width:900px){.identity-grid{grid-template-columns:1fr}.card-header{align-items:flex-start;flex-direction:column}}
</style>
