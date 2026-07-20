<script lang="ts" setup>
import type {
  CreateDelegationPayload,
  DelegationRule,
  DelegationScope,
} from '#/api/approval/delegations';

import { computed, onMounted, reactive, ref, watch } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElDialog,
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
  createDelegationRule,
  findDelegationRules,
  revokeDelegationRule,
} from '#/api/approval/delegations';

interface DelegationForm {
  definitionKey: string;
  delegateId: string;
  reason: string;
  scope: DelegationScope;
  validFrom: Date | null;
  validUntil: Date | null;
}

type TagType = 'danger' | 'info' | 'primary' | 'success' | 'warning';

const loading = ref(false);
const saving = ref(false);
const dialogOpen = ref(false);
const includeRevoked = ref(false);
const rules = ref<DelegationRule[]>([]);
const form = reactive<DelegationForm>(emptyForm());

const activeCount = computed(
  () => rules.value.filter(item => item.status === 'ACTIVE').length,
);
const definitionCount = computed(
  () => rules.value.filter(item => item.scope === 'DEFINITION' && item.status === 'ACTIVE').length,
);
const globalCount = computed(
  () => rules.value.filter(item => item.scope === 'ALL' && item.status === 'ACTIVE').length,
);

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  month: '2-digit',
  year: 'numeric',
});

function emptyForm(): DelegationForm {
  const validFrom = new Date();
  const validUntil = new Date(validFrom.getTime() + 8 * 60 * 60 * 1000);
  return {
    definitionKey: 'purchase-payment',
    delegateId: '',
    reason: '',
    scope: 'ALL',
    validFrom,
    validUntil,
  };
}

function resetForm() {
  Object.assign(form, emptyForm());
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试';
}

function formatDate(value?: string) {
  return value ? dateFormatter.format(new Date(value)) : '-';
}

function scopeLabel(scope: DelegationScope) {
  return scope === 'ALL' ? '全部审批' : '指定流程';
}

function statusLabel(rule: DelegationRule) {
  if (rule.status === 'REVOKED') return '已撤销';
  if (new Date(rule.validUntil).getTime() <= Date.now()) return '已到期';
  if (new Date(rule.validFrom).getTime() > Date.now()) return '待生效';
  return '生效中';
}

function statusType(rule: DelegationRule): TagType {
  const label = statusLabel(rule);
  if (label === '生效中') return 'success';
  if (label === '待生效') return 'warning';
  if (label === '已撤销') return 'danger';
  return 'info';
}

async function loadRules() {
  loading.value = true;
  try {
    rules.value = await findDelegationRules(includeRevoked.value);
  } catch (error) {
    rules.value = [];
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

function openCreateDialog() {
  resetForm();
  dialogOpen.value = true;
}

function validateForm() {
  const delegateId = form.delegateId.trim();
  const reason = form.reason.trim();
  if (!delegateId) throw new Error('请输入代理人用户 ID');
  if (!reason) throw new Error('请输入代理原因');
  if (!form.validFrom || !form.validUntil) throw new Error('请选择代理有效期');
  if (form.validUntil.getTime() <= form.validFrom.getTime()) {
    throw new Error('结束时间必须晚于开始时间');
  }
  if (form.scope === 'DEFINITION' && !form.definitionKey.trim()) {
    throw new Error('指定流程代理必须填写流程标识');
  }
  const payload: CreateDelegationPayload = {
    delegateId,
    reason,
    scope: form.scope,
    validFrom: form.validFrom.toISOString(),
    validUntil: form.validUntil.toISOString(),
  };
  if (form.scope === 'DEFINITION') {
    payload.definitionKey = form.definitionKey.trim();
  }
  return payload;
}

async function submitCreate() {
  let payload: CreateDelegationPayload;
  try {
    payload = validateForm();
  } catch (error) {
    ElMessage.warning(errorMessage(error));
    return;
  }
  saving.value = true;
  try {
    await createDelegationRule(payload);
    ElMessage.success('代理规则已创建');
    dialogOpen.value = false;
    await loadRules();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}

async function revoke(rule: DelegationRule) {
  let reason = '';
  try {
    const result = await ElMessageBox.prompt(
      `撤销 ${rule.delegateId} 的代理规则后，新任务将不再自动分派给该用户。`,
      '撤销代理规则',
      {
        cancelButtonText: '取消',
        confirmButtonText: '确认撤销',
        inputPlaceholder: '请输入撤销原因',
        inputValidator: value => Boolean(value?.trim()) || '撤销原因不能为空',
        type: 'warning',
      },
    );
    reason = result.value.trim();
  } catch {
    return;
  }
  try {
    await revokeDelegationRule(rule.ruleId, reason);
    ElMessage.success('代理规则已撤销');
    await loadRules();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

watch(includeRevoked, () => void loadRules());
onMounted(() => void loadRules());
</script>

<template>
  <Page title="代理规则" description="管理个人审批代理。代理仅影响新产生的审批任务，原责任人和代理依据会被永久记录。">
    <div class="delegation-page">
      <ElAlert
        :closable="false"
        show-icon
        title="指定流程规则优先于全部审批规则；发起人修改任务不会被代理。"
        type="info"
      />

      <div class="summary-grid">
        <ElCard shadow="never">
          <div class="summary-label">有效规则</div>
          <div class="summary-value">{{ activeCount }}</div>
        </ElCard>
        <ElCard shadow="never">
          <div class="summary-label">全部审批代理</div>
          <div class="summary-value">{{ globalCount }}</div>
        </ElCard>
        <ElCard shadow="never">
          <div class="summary-label">指定流程代理</div>
          <div class="summary-value">{{ definitionCount }}</div>
        </ElCard>
      </div>

      <ElCard shadow="never">
        <template #header>
          <div class="card-header">
            <div>
              <div class="card-title">我的代理规则</div>
              <div class="card-description">每个相同作用域的有效时间段不能重叠。</div>
            </div>
            <div class="header-actions">
              <span class="switch-label">显示已撤销</span>
              <ElSwitch v-model="includeRevoked" />
              <ElButton type="primary" @click="openCreateDialog">新建代理</ElButton>
            </div>
          </div>
        </template>

        <ElSkeleton :loading="loading" :rows="5" animated>
          <ElEmpty v-if="rules.length === 0" description="暂无代理规则" />
          <ElTable v-else :data="rules" row-key="ruleId">
            <ElTableColumn label="代理人" min-width="150" prop="delegateId" />
            <ElTableColumn label="范围" min-width="160">
              <template #default="{ row }">
                <div class="scope-cell">
                  <ElTag effect="plain" type="primary">{{ scopeLabel(row.scope) }}</ElTag>
                  <span v-if="row.definitionKey" class="definition-key">{{ row.definitionKey }}</span>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="有效期" min-width="260">
              <template #default="{ row }">
                {{ formatDate(row.validFrom) }} — {{ formatDate(row.validUntil) }}
              </template>
            </ElTableColumn>
            <ElTableColumn label="原因" min-width="220" prop="reason" show-overflow-tooltip />
            <ElTableColumn label="状态" width="100">
              <template #default="{ row }">
                <ElTag :type="statusType(row)">{{ statusLabel(row) }}</ElTag>
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

    <ElDialog v-model="dialogOpen" title="新建代理规则" width="560px">
      <ElForm label-position="top">
        <ElFormItem label="代理人用户 ID" required>
          <ElInput v-model="form.delegateId" maxlength="256" placeholder="例如 user-1002" />
        </ElFormItem>
        <ElFormItem label="代理范围" required>
          <ElSelect v-model="form.scope" class="full-width">
            <ElOption label="全部审批" value="ALL" />
            <ElOption label="指定流程" value="DEFINITION" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem v-if="form.scope === 'DEFINITION'" label="流程标识" required>
          <ElInput
            v-model="form.definitionKey"
            maxlength="256"
            placeholder="例如 purchase-payment"
          />
        </ElFormItem>
        <div class="date-grid">
          <ElFormItem label="开始时间" required>
            <ElDatePicker
              v-model="form.validFrom"
              class="full-width"
              type="datetime"
              placeholder="选择开始时间"
            />
          </ElFormItem>
          <ElFormItem label="结束时间" required>
            <ElDatePicker
              v-model="form.validUntil"
              class="full-width"
              type="datetime"
              placeholder="选择结束时间"
            />
          </ElFormItem>
        </div>
        <ElFormItem label="代理原因" required>
          <ElInput
            v-model="form.reason"
            :rows="3"
            maxlength="2000"
            placeholder="说明请假、出差或临时职责安排"
            show-word-limit
            type="textarea"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogOpen = false">取消</ElButton>
        <ElButton :loading="saving" type="primary" @click="submitCreate">创建代理</ElButton>
      </template>
    </ElDialog>
  </Page>
</template>

<style scoped>
.delegation-page {
  display: grid;
  gap: 16px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.summary-label,
.card-description,
.muted,
.switch-label,
.definition-key {
  color: var(--el-text-color-secondary);
}

.summary-value {
  margin-top: 8px;
  font-size: 28px;
  font-weight: 700;
}

.card-header,
.header-actions,
.scope-cell {
  display: flex;
  align-items: center;
}

.card-header {
  justify-content: space-between;
  gap: 16px;
}

.header-actions,
.scope-cell {
  gap: 10px;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
}

.card-description {
  margin-top: 4px;
  font-size: 13px;
}

.date-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.full-width {
  width: 100%;
}

@media (max-width: 900px) {
  .summary-grid,
  .date-grid {
    grid-template-columns: 1fr;
  }

  .card-header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
