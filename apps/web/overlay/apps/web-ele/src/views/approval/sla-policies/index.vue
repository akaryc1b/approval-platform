<script lang="ts" setup>
import type {
  AutomaticAction,
  EscalationTargetType,
  SlaDurationMode,
  SlaPolicyIdentity,
  SlaPolicyPage,
  SlaPolicyVersion,
  SlaTargetType,
} from '#/api/approval/sla';

import { computed, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import {
  activatePolicyVersion,
  createPolicy,
  findPolicies,
  findPolicyVersion,
  publishPolicyVersion,
  savePolicyVersion,
} from '#/api/approval/sla';

const PAGE_SIZE = 50;
const loading = ref(false);
const saving = ref(false);
const createVisible = ref(false);
const editorVisible = ref(false);
const policies = ref<SlaPolicyPage>(emptyPage());
const selected = ref<SlaPolicyIdentity>();
const loadedVersion = ref<SlaPolicyVersion>();
const versionNumber = ref(1);
const reason = ref('');
const createModel = reactive({ displayName: '', policyKey: '', reason: '' });
const form = reactive({
  automaticAction: 'NONE' as AutomaticAction,
  calendarId: '',
  calendarVersion: undefined as number | undefined,
  definitionKey: '',
  duration: 'PT8H',
  durationMode: 'NATURAL_TIME' as SlaDurationMode,
  escalationTarget: '',
  escalationTargetType: undefined as EscalationTargetType | undefined,
  firstReminderOffset: 'PT1H',
  maximumReminderCount: 1,
  naturalTimePauses: false,
  overdueOffset: 'PT0S',
  releaseVersion: undefined as number | undefined,
  repeatReminderInterval: '',
  targetType: 'TASK' as SlaTargetType,
  taskDefinitionKey: '',
});

const immutable = computed(() => loadedVersion.value?.immutable === true);
const workingTime = computed(() => form.durationMode === 'WORKING_TIME');

function emptyPage(): SlaPolicyPage {
  return { items: [], limit: PAGE_SIZE, offset: 0, total: 0 };
}

function formatDate(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}

function statusType(status: string) {
  if (status === 'ACTIVE') return 'success';
  if (status === 'PUBLISHED') return 'primary';
  if (status === 'DRAFT') return 'warning';
  return 'info';
}

function message(error: unknown) {
  return error instanceof Error ? error.message : 'SLA 策略请求失败';
}

async function loadPolicies() {
  loading.value = true;
  try {
    policies.value = await findPolicies(PAGE_SIZE, 0);
  } catch (error) {
    policies.value = emptyPage();
    ElMessage.error(message(error));
  } finally {
    loading.value = false;
  }
}

function resetEditor(policy: SlaPolicyIdentity) {
  selected.value = policy;
  loadedVersion.value = undefined;
  versionNumber.value = policy.activeVersion ? policy.activeVersion + 1 : 1;
  reason.value = '';
  Object.assign(form, {
    automaticAction: 'NONE',
    calendarId: '',
    calendarVersion: undefined,
    definitionKey: '',
    duration: 'PT8H',
    durationMode: 'NATURAL_TIME',
    escalationTarget: '',
    escalationTargetType: undefined,
    firstReminderOffset: 'PT1H',
    maximumReminderCount: 1,
    naturalTimePauses: false,
    overdueOffset: 'PT0S',
    releaseVersion: undefined,
    repeatReminderInterval: '',
    targetType: 'TASK',
    taskDefinitionKey: '',
  });
  editorVisible.value = true;
}

function durationText(value?: number | string) {
  return value === undefined ? '' : String(value);
}

async function readVersion() {
  if (!selected.value) return;
  saving.value = true;
  try {
    const value = await findPolicyVersion(selected.value.policyId, versionNumber.value);
    loadedVersion.value = value;
    Object.assign(form, {
      automaticAction: value.automaticAction,
      calendarId: value.calendarId ?? '',
      calendarVersion: value.calendarVersion,
      definitionKey: value.definitionKey,
      duration: durationText(value.duration),
      durationMode: value.durationMode,
      escalationTarget: value.escalationTarget ?? '',
      escalationTargetType: value.escalationTargetType,
      firstReminderOffset: durationText(value.firstReminderOffset),
      maximumReminderCount: value.maximumReminderCount,
      naturalTimePauses: value.naturalTimePauses,
      overdueOffset: durationText(value.overdueOffset),
      releaseVersion: value.releaseVersion,
      repeatReminderInterval: durationText(value.repeatReminderInterval),
      targetType: value.targetType,
      taskDefinitionKey: value.taskDefinitionKey ?? '',
    });
  } catch (error) {
    loadedVersion.value = undefined;
    ElMessage.error(message(error));
  } finally {
    saving.value = false;
  }
}

function normalizeOptional(value: string) {
  const normalized = value.trim();
  return normalized || undefined;
}

async function submitCreate() {
  saving.value = true;
  try {
    await createPolicy(
      { displayName: createModel.displayName.trim(), policyKey: createModel.policyKey.trim() },
      { reason: createModel.reason },
    );
    createVisible.value = false;
    Object.assign(createModel, { displayName: '', policyKey: '', reason: '' });
    ElMessage.success('SLA 策略已创建');
    await loadPolicies();
  } catch (error) {
    ElMessage.error(message(error));
  } finally {
    saving.value = false;
  }
}

async function saveDraft() {
  if (!selected.value) return;
  saving.value = true;
  try {
    loadedVersion.value = await savePolicyVersion(
      selected.value.policyId,
      versionNumber.value,
      {
        automaticAction: form.automaticAction,
        calendarId: workingTime.value ? normalizeOptional(form.calendarId) : undefined,
        calendarVersion: workingTime.value ? form.calendarVersion : undefined,
        definitionKey: form.definitionKey.trim(),
        duration: form.duration.trim(),
        durationMode: form.durationMode,
        escalationTarget: normalizeOptional(form.escalationTarget),
        escalationTargetType: form.escalationTargetType,
        expectedIdentityVersion: selected.value.version,
        firstReminderOffset: normalizeOptional(form.firstReminderOffset),
        maximumReminderCount: form.maximumReminderCount,
        naturalTimePauses: form.naturalTimePauses,
        overdueOffset: normalizeOptional(form.overdueOffset),
        releaseVersion: form.releaseVersion,
        repeatReminderInterval: normalizeOptional(form.repeatReminderInterval),
        targetType: form.targetType,
        taskDefinitionKey: normalizeOptional(form.taskDefinitionKey),
      },
      { reason: reason.value },
    );
    ElMessage.success('策略草稿已保存');
    await loadPolicies();
  } catch (error) {
    ElMessage.error(message(error));
  } finally {
    saving.value = false;
  }
}

async function transition(policy: SlaPolicyIdentity, action: 'activate' | 'publish') {
  const { value } = await ElMessageBox.prompt(
    action === 'publish' ? '请输入发布原因（至少 8 个字符）' : '请输入激活原因（至少 8 个字符）',
    action === 'publish' ? '发布 SLA 策略' : '激活 SLA 策略',
    { inputValidator: text => Boolean(text && text.trim().length >= 8) || '原因至少 8 个字符' },
  );
  const version = policy.activeVersion ?? Math.max(1, versionNumber.value);
  try {
    if (action === 'publish') {
      await publishPolicyVersion(policy, version, { reason: value });
      ElMessage.success(`策略版本 v${version} 已发布`);
    } else {
      await activatePolicyVersion(policy, version, { reason: value });
      ElMessage.success(`策略版本 v${version} 已激活`);
    }
    await loadPolicies();
  } catch (error) {
    ElMessage.error(message(error));
  }
}

void loadPolicies();
</script>

<template>
  <Page title="SLA 策略" description="管理流程、任务和协作参与人的不可变 SLA 策略版本。">
    <div class="policy-page">
      <ElAlert
        :closable="false"
        show-icon
        title="工作时间策略必须绑定已发布日历快照；自然时间策略不得绑定日历。客户端不会计算最终 dueAt。"
        type="warning"
      />
      <ElCard shadow="never">
        <template #header>
          <div class="header-row">
            <div><strong>策略列表</strong><span>共 {{ policies.total }} 个</span></div>
            <div class="actions"><ElButton :loading="loading" @click="loadPolicies">刷新</ElButton><ElButton type="primary" @click="createVisible = true">新建策略</ElButton></div>
          </div>
        </template>
        <ElTable v-if="policies.items.length" v-loading="loading" :data="policies.items" row-key="policyId">
          <ElTableColumn label="策略" min-width="240"><template #default="scope"><strong>{{ scope.row.displayName }}</strong><div class="muted">{{ scope.row.policyKey }}</div></template></ElTableColumn>
          <ElTableColumn label="状态" width="120"><template #default="scope"><ElTag :type="statusType(scope.row.status)">{{ scope.row.status }}</ElTag></template></ElTableColumn>
          <ElTableColumn label="活动版本" width="110"><template #default="scope">{{ scope.row.activeVersion ? `v${scope.row.activeVersion}` : '-' }}</template></ElTableColumn>
          <ElTableColumn label="CAS 版本" width="100" prop="version" />
          <ElTableColumn label="更新时间" min-width="180"><template #default="scope">{{ formatDate(scope.row.updatedAt) }}</template></ElTableColumn>
          <ElTableColumn fixed="right" label="操作" width="250"><template #default="scope"><ElButton link type="primary" @click="resetEditor(scope.row)">版本配置</ElButton><ElButton link type="warning" @click="transition(scope.row, 'publish')">发布</ElButton><ElButton link type="success" @click="transition(scope.row, 'activate')">激活</ElButton></template></ElTableColumn>
        </ElTable>
        <ElEmpty v-else :description="loading ? '正在读取' : '暂无 SLA 策略'" />
      </ElCard>
    </div>

    <ElDialog v-model="createVisible" title="新建 SLA 策略" width="560px">
      <div class="form-grid">
        <label>策略 Key<ElInput v-model="createModel.policyKey" maxlength="100" /></label>
        <label>显示名称<ElInput v-model="createModel.displayName" maxlength="200" /></label>
        <label>操作原因<ElInput v-model="createModel.reason" maxlength="512" show-word-limit type="textarea" /></label>
      </div>
      <template #footer><ElButton @click="createVisible = false">取消</ElButton><ElButton :loading="saving" type="primary" @click="submitCreate">创建</ElButton></template>
    </ElDialog>

    <ElDialog v-model="editorVisible" :title="`策略版本配置 · ${selected?.displayName ?? ''}`" width="980px">
      <ElAlert v-if="immutable" :closable="false" show-icon title="当前策略版本已发布且不可修改，请使用新的版本号保存草稿。" type="info" />
      <div class="toolbar"><span>版本</span><ElInputNumber v-model="versionNumber" :min="1" /><ElButton :loading="saving" @click="readVersion">读取版本</ElButton></div>
      <div class="editor-grid">
        <label>流程定义 Key<ElInput v-model="form.definitionKey" maxlength="100" /></label>
        <label>Release 版本<ElInputNumber v-model="form.releaseVersion" :min="1" clearable /></label>
        <label>目标类型<ElSelect v-model="form.targetType"><ElOption label="流程" value="PROCESS" /><ElOption label="任务" value="TASK" /><ElOption label="协作参与人" value="COLLABORATION_PARTICIPANT" /></ElSelect></label>
        <label>任务定义 Key<ElInput v-model="form.taskDefinitionKey" :disabled="form.targetType === 'PROCESS'" maxlength="100" /></label>
        <label>计时模式<ElSelect v-model="form.durationMode"><ElOption label="自然时间" value="NATURAL_TIME" /><ElOption label="工作时间" value="WORKING_TIME" /></ElSelect></label>
        <label>持续时间（ISO-8601）<ElInput v-model="form.duration" placeholder="PT8H" /></label>
        <label v-if="workingTime">日历 ID<ElInput v-model="form.calendarId" /></label>
        <label v-if="workingTime">日历版本<ElInputNumber v-model="form.calendarVersion" :min="1" /></label>
        <label>首次提醒偏移<ElInput v-model="form.firstReminderOffset" placeholder="PT1H" /></label>
        <label>重复提醒间隔<ElInput v-model="form.repeatReminderInterval" placeholder="PT30M" /></label>
        <label>最大提醒次数<ElInputNumber v-model="form.maximumReminderCount" :max="100" :min="0" /></label>
        <label>逾期偏移<ElInput v-model="form.overdueOffset" placeholder="PT0S" /></label>
        <label>升级目标类型<ElSelect v-model="form.escalationTargetType" clearable><ElOption label="直属经理" value="MANAGER" /><ElOption label="指定用户" value="USER" /><ElOption label="角色" value="ROLE" /><ElOption label="部门管理员" value="DEPARTMENT_ADMIN" /></ElSelect></label>
        <label>升级目标<ElInput v-model="form.escalationTarget" maxlength="256" /></label>
        <label>自动动作<ElSelect v-model="form.automaticAction"><ElOption label="关闭" value="NONE" /><ElOption label="自动转交" value="AUTO_TRANSFER" /><ElOption label="自动同意" value="AUTO_APPROVE" /><ElOption label="自动拒绝" value="AUTO_REJECT" /></ElSelect></label>
        <label class="switch-field">自然时间暂停规则<ElSwitch v-model="form.naturalTimePauses" /></label>
      </div>
      <label class="reason-field">操作原因<ElInput v-model="reason" maxlength="512" show-word-limit type="textarea" /></label>
      <template #footer><ElButton @click="editorVisible = false">关闭</ElButton><ElButton :disabled="immutable" :loading="saving" type="primary" @click="saveDraft">保存草稿</ElButton></template>
    </ElDialog>
  </Page>
</template>

<style scoped>
.policy-page { display: grid; gap: 16px; }
.header-row, .toolbar, .actions { align-items: center; display: flex; gap: 12px; }
.header-row { justify-content: space-between; }
.header-row span, .muted { color: var(--el-text-color-secondary); font-size: 12px; }
.form-grid, .editor-grid { display: grid; gap: 16px; }
.form-grid label, .editor-grid label, .reason-field { display: grid; gap: 8px; }
.editor-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); margin-top: 18px; }
.toolbar { margin-bottom: 12px; }
.switch-field { align-content: center; grid-template-columns: 1fr auto !important; }
.reason-field { margin-top: 18px; }
</style>
