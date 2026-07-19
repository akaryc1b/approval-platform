<script lang="ts" setup>
import type {
  ApprovalBatchScenarioInput,
  ApprovalBatchScenarioResult,
  ApprovalBatchSimulationInput,
  ApprovalBatchSimulationReport,
  ApprovalDesignDraft,
  ApprovalDesignDraftSummary,
  ApprovalFormValueDisclosure,
} from '#/api/approval/process-design';

import { computed, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElCol,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElOption,
  ElProgress,
  ElRow,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import {
  exportApprovalDesignDraftBatchReport,
  findApprovalDesignDraft,
  findApprovalDesignDrafts,
  simulateApprovalDesignDraftBatch,
} from '#/api/approval/process-design';

type SimulationStatus =
  | 'BLOCKED'
  | 'COMPLETED'
  | 'REJECTED'
  | 'TRANSITION_LIMIT_REACHED';

interface ScenarioEditor {
  approvalDecisionsText: string;
  expectedSkippedText: string;
  expectedTerminalStatus: '' | SimulationStatus;
  expectedVisitedText: string;
  formValuesText: string;
  identitySnapshotsText: string;
  maxTransitions: number;
  name: string;
  scenarioId: string;
}

const draftKeyword = ref('');
const drafts = ref<ApprovalDesignDraftSummary[]>([]);
const selectedDraft = ref<ApprovalDesignDraft>();
const draftLoading = ref(false);
const running = ref(false);
const exporting = ref(false);
const scenarios = ref<ScenarioEditor[]>([]);
const report = ref<ApprovalBatchSimulationReport>();
const disclosure = ref<ApprovalFormValueDisclosure>('MASKED');
const editorVisible = ref(false);
const editingIndex = ref(-1);
const pathVisible = ref(false);
const pathResult = ref<ApprovalBatchScenarioResult>();

const editableDrafts = computed(() => drafts.value.filter(
  item => item.status === 'DRAFT' || item.status === 'VALIDATED',
));
const staleReport = computed(() => Boolean(
  report.value && selectedDraft.value &&
    report.value.draftRevision !== selectedDraft.value.revision,
));
const coverageCards = computed(() => {
  const coverage = report.value?.coverage;
  if (!coverage) return [];
  return [
    ['关键路径', coverage.criticalPathCoverage],
    ['决策覆盖', coverage.decisionCoverage],
    ['结构覆盖', coverage.structuralCoverage],
    ['节点覆盖', coverage.nodes],
    ['审批通过', coverage.approvalPassPaths],
    ['审批驳回', coverage.approvalRejectPaths],
    ['条件分支', coverage.conditionRoutes],
    ['默认路由', coverage.defaultRoutes],
    ['并行分支', coverage.parallelBranches],
    ['修订回路', coverage.handleRevisionLoops],
  ] as const;
});
const currentEditor = computed(() => scenarios.value[editingIndex.value]);

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '批量模拟请求失败';
}

function newId(prefix = 'scenario') {
  const value = globalThis.crypto?.randomUUID?.() ??
    `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}-${value.slice(0, 8)}`;
}

function blankScenario(): ScenarioEditor {
  return {
    approvalDecisionsText: '{}',
    expectedSkippedText: '',
    expectedTerminalStatus: '',
    expectedVisitedText: '',
    formValuesText: '{}',
    identitySnapshotsText: '{}',
    maxTransitions: 200,
    name: '新场景',
    scenarioId: newId(),
  };
}

function scenario(
  scenarioId: string,
  name: string,
  amount: number,
  decisions: Record<string, 'APPROVE' | 'REJECT'>,
  expectedTerminalStatus: SimulationStatus,
  expectedVisited: string[],
  expectedSkipped: string[],
  maxTransitions = 100,
): ScenarioEditor {
  return {
    approvalDecisionsText: JSON.stringify(decisions, null, 2),
    expectedSkippedText: expectedSkipped.join(', '),
    expectedTerminalStatus,
    expectedVisitedText: expectedVisited.join(', '),
    formValuesText: JSON.stringify({ amount }, null, 2),
    identitySnapshotsText: '{}',
    maxTransitions,
    name,
    scenarioId,
  };
}

function purchasePaymentPresets() {
  scenarios.value = [
    scenario(
      'high-value-approved',
      '高金额全部通过',
      20_000,
      {
        financeCountersign: 'APPROVE',
        financeReview: 'APPROVE',
        managerApproval: 'APPROVE',
      },
      'COMPLETED',
      ['financeReview', 'end'],
      ['initiatorRevision'],
    ),
    scenario(
      'low-value-approved',
      '低金额全部通过',
      5_000,
      { financeCountersign: 'APPROVE', managerApproval: 'APPROVE' },
      'COMPLETED',
      ['amountCondition', 'end'],
      ['financeReview', 'initiatorRevision'],
    ),
    scenario(
      'manager-reject-loop',
      '经理驳回修订回路',
      5_000,
      { managerApproval: 'REJECT' },
      'TRANSITION_LIMIT_REACHED',
      ['initiatorRevision'],
      ['end'],
      8,
    ),
    scenario(
      'finance-review-reject',
      '财务复核驳回',
      20_000,
      { financeReview: 'REJECT', managerApproval: 'APPROVE' },
      'TRANSITION_LIMIT_REACHED',
      ['financeReview', 'initiatorRevision'],
      ['end'],
      12,
    ),
    scenario(
      'missing-countersign',
      '会签缺少决策',
      5_000,
      { managerApproval: 'APPROVE' },
      'BLOCKED',
      ['financeCountersign'],
      ['end'],
    ),
    scenario(
      'boundary-equal-10000',
      '边界值等于 10000',
      10_000,
      {
        financeCountersign: 'APPROVE',
        financeReview: 'APPROVE',
        managerApproval: 'APPROVE',
      },
      'COMPLETED',
      ['financeReview', 'end'],
      ['initiatorRevision'],
    ),
    scenario(
      'boundary-below-10000',
      '边界值低于 10000',
      9_999.99,
      { financeCountersign: 'APPROVE', managerApproval: 'APPROVE' },
      'COMPLETED',
      ['financeCountersign', 'end'],
      ['financeReview', 'initiatorRevision'],
    ),
  ];
  report.value = undefined;
}

function parseObject(text: string, fieldName: string) {
  let value: unknown;
  try {
    value = JSON.parse(text || '{}');
  } catch {
    throw new Error(`${fieldName} 不是有效 JSON`);
  }
  if (!value || Array.isArray(value) || typeof value !== 'object') {
    throw new Error(`${fieldName} 必须是 JSON 对象`);
  }
  return value as Record<string, unknown>;
}

function nodeIds(value: string) {
  return [...new Set(value.split(',').map(item => item.trim()).filter(Boolean))].sort();
}

function toScenario(value: ScenarioEditor): ApprovalBatchScenarioInput {
  const decisions = parseObject(value.approvalDecisionsText, '审批决策');
  for (const decision of Object.values(decisions)) {
    if (decision !== 'APPROVE' && decision !== 'REJECT') {
      throw new Error('审批决策只能使用 APPROVE 或 REJECT');
    }
  }
  return {
    approvalDecisions: decisions as Record<string, 'APPROVE' | 'REJECT'>,
    expectedSkippedNodeIds: nodeIds(value.expectedSkippedText),
    expectedTerminalStatus: value.expectedTerminalStatus || undefined,
    expectedVisitedNodeIds: nodeIds(value.expectedVisitedText),
    formValues: parseObject(value.formValuesText, 'Form values'),
    identitySnapshots: parseObject(
      value.identitySnapshotsText,
      'Identity snapshots',
    ) as ApprovalBatchScenarioInput['identitySnapshots'],
    maxTransitions: value.maxTransitions,
    name: value.name.trim(),
    scenarioId: value.scenarioId.trim(),
  };
}

function buildInput(): ApprovalBatchSimulationInput {
  if (!selectedDraft.value) throw new Error('请先选择草稿');
  if (scenarios.value.length === 0) throw new Error('请至少添加一个场景');
  const ids = scenarios.value.map(item => item.scenarioId.trim());
  if (new Set(ids).size !== ids.length) throw new Error('场景 ID 不能重复');
  return {
    expectedRevision: selectedDraft.value.revision,
    formValueDisclosure: disclosure.value,
    scenarios: scenarios.value.map(toScenario),
  };
}

async function searchDrafts() {
  draftLoading.value = true;
  try {
    const page = await findApprovalDesignDrafts(draftKeyword.value, undefined, 100, 0);
    drafts.value = page.items;
    if (editableDrafts.value.length === 0) {
      ElMessage.info('没有找到可模拟的草稿');
    }
  } catch (error) {
    drafts.value = [];
    ElMessage.error(errorMessage(error));
  } finally {
    draftLoading.value = false;
  }
}

async function selectDraft(row: ApprovalDesignDraftSummary) {
  draftLoading.value = true;
  try {
    selectedDraft.value = await findApprovalDesignDraft(row.draftId);
    report.value = undefined;
    if (selectedDraft.value.definitionKey === 'purchase-payment') {
      purchasePaymentPresets();
    } else {
      scenarios.value = [blankScenario()];
    }
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    draftLoading.value = false;
  }
}

function addScenario() {
  scenarios.value.push(blankScenario());
  editScenario(scenarios.value.length - 1);
}

function copyScenario(index: number) {
  const source = scenarios.value[index];
  if (!source) return;
  scenarios.value.splice(index + 1, 0, {
    ...source,
    name: `${source.name} 副本`,
    scenarioId: newId(source.scenarioId),
  });
}

function removeScenario(index: number) {
  scenarios.value.splice(index, 1);
  report.value = undefined;
}

function editScenario(index: number) {
  editingIndex.value = index;
  editorVisible.value = true;
}

async function runBatch() {
  running.value = true;
  try {
    const input = buildInput();
    report.value = await simulateApprovalDesignDraftBatch(
      selectedDraft.value!.draftId,
      input,
    );
    ElMessage.success(`已完成 ${report.value.scenarioCount} 个场景`);
  } catch (error) {
    report.value = undefined;
    ElMessage.error(errorMessage(error));
  } finally {
    running.value = false;
  }
}

async function downloadReport() {
  exporting.value = true;
  try {
    const input = buildInput();
    const blob = await exportApprovalDesignDraftBatchReport(
      selectedDraft.value!.draftId,
      input,
    );
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    const safeDefinitionKey = selectedDraft.value!.definitionKey.replace(
      /[^A-Za-z0-9._-]/g,
      '_',
    );
    const reportHash = report.value?.reportHash.slice(0, 12) || 'report';
    link.download = `approval-simulation-${safeDefinitionKey}-${reportHash}.json`;
    link.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    exporting.value = false;
  }
}

async function copyReport() {
  if (!report.value) return;
  try {
    await navigator.clipboard.writeText(JSON.stringify(report.value, null, 2));
    ElMessage.success('报告 JSON 已复制');
  } catch {
    ElMessage.error('无法复制报告 JSON');
  }
}

function copyReportAsBaseline() {
  if (!report.value) return;
  const byId = new Map(report.value.scenarioResults.map(item => [item.scenarioId, item]));
  scenarios.value = scenarios.value.map(item => {
    const result = byId.get(item.scenarioId);
    if (!result) return item;
    return {
      ...item,
      expectedSkippedText: result.skippedNodeIds.join(', '),
      expectedTerminalStatus: result.simulationStatus || '',
      expectedVisitedText: result.visitedNodeIds.join(', '),
    };
  });
  ElMessage.success('当前结果已复制为场景基线');
}

function showPath(row: ApprovalBatchScenarioResult) {
  pathResult.value = row;
  pathVisible.value = true;
}

function statusType(status: ApprovalBatchScenarioResult['runStatus']) {
  if (status === 'PASSED') return 'success';
  if (status === 'BLOCKED' || status === 'TRANSITION_LIMIT_REACHED') return 'warning';
  return 'danger';
}

function shortHash(value?: string) {
  return value ? `${value.slice(0, 8)}…${value.slice(-6)}` : '—';
}
</script>

<template>
  <Page title="批量模拟">
    <div class="simulation-page">
      <ElCard shadow="never">
        <div class="toolbar">
          <ElInput
            v-model="draftKeyword"
            clearable
            placeholder="搜索草稿名称或流程 Key"
            @keyup.enter="searchDrafts"
          />
          <ElButton type="primary" :loading="draftLoading" @click="searchDrafts">
            查询草稿
          </ElButton>
        </div>
        <ElTable
          v-if="editableDrafts.length"
          :data="editableDrafts"
          height="260"
          row-key="draftId"
          @row-dblclick="selectDraft"
        >
          <ElTableColumn prop="name" label="草稿" min-width="180" />
          <ElTableColumn prop="definitionKey" label="流程 Key" min-width="150" />
          <ElTableColumn prop="definitionVersion" label="DSL 版本" width="100" />
          <ElTableColumn prop="revision" label="Revision" width="100" />
          <ElTableColumn prop="status" label="状态" width="110" />
          <ElTableColumn label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <ElButton link type="primary" @click="selectDraft(row)">选择</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <ElEmpty v-else description="查询并选择可编辑草稿" :image-size="72" />
      </ElCard>

      <template v-if="selectedDraft">
        <ElAlert
          :closable="false"
          type="info"
          show-icon
          :title="`${selectedDraft.name} · ${selectedDraft.definitionKey} · revision ${selectedDraft.revision}`"
        />
        <ElAlert
          v-if="staleReport"
          :closable="false"
          type="warning"
          show-icon
          title="草稿 revision 已变化，请重新运行批量模拟"
        />

        <ElCard shadow="never">
          <template #header>
            <div class="card-header">
              <div>
                <strong>场景集</strong>
                <span>最多 100 个显式场景</span>
              </div>
              <div class="actions">
                <ElSelect v-model="disclosure" class="disclosure-select">
                  <ElOption label="值脱敏" value="MASKED" />
                  <ElOption label="仅字段名" value="FIELD_NAMES_ONLY" />
                  <ElOption label="完整值" value="FULL" />
                </ElSelect>
                <ElButton
                  v-if="selectedDraft.definitionKey === 'purchase-payment'"
                  @click="purchasePaymentPresets"
                >
                  采购付款预置
                </ElButton>
                <ElButton @click="addScenario">新增场景</ElButton>
                <ElButton type="primary" :loading="running" @click="runBatch">
                  批量运行
                </ElButton>
              </div>
            </div>
          </template>

          <ElTable :data="scenarios" height="360" row-key="scenarioId">
            <ElTableColumn prop="scenarioId" label="场景 ID" min-width="180" />
            <ElTableColumn prop="name" label="名称" min-width="180" />
            <ElTableColumn label="预期终态" width="190">
              <template #default="{ row }">
                {{ row.expectedTerminalStatus || '未设置' }}
              </template>
            </ElTableColumn>
            <ElTableColumn prop="maxTransitions" label="最大步数" width="100" />
            <ElTableColumn label="操作" width="190" fixed="right">
              <template #default="{ $index }">
                <ElButton link type="primary" @click="editScenario($index)">编辑</ElButton>
                <ElButton link @click="copyScenario($index)">复制</ElButton>
                <ElButton link type="danger" @click="removeScenario($index)">删除</ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
        </ElCard>
      </template>

      <template v-if="report">
        <ElCard shadow="never">
          <template #header>
            <div class="card-header">
              <div>
                <strong>覆盖报告</strong>
                <span>
                  {{ report.scenarioCount }} 个场景 · Report {{ shortHash(report.reportHash) }}
                </span>
              </div>
              <div class="actions">
                <ElButton @click="copyReportAsBaseline">复制为基线</ElButton>
                <ElButton @click="copyReport">复制 JSON</ElButton>
                <ElButton :loading="exporting" @click="downloadReport">下载 JSON</ElButton>
              </div>
            </div>
          </template>
          <ElRow :gutter="16">
            <ElCol
              v-for="([label, metric]) in coverageCards"
              :key="label"
              :lg="6"
              :md="8"
              :sm="12"
              :xs="24"
            >
              <div class="coverage-card">
                <div>
                  <span>{{ label }}</span>
                  <strong>{{ metric.percentage }}%</strong>
                </div>
                <ElProgress :percentage="metric.percentage" :show-text="false" />
                <small>{{ metric.covered }} / {{ metric.total }}</small>
              </div>
            </ElCol>
          </ElRow>
        </ElCard>

        <ElCard shadow="never">
          <template #header><strong>场景结果</strong></template>
          <ElTable :data="report.scenarioResults" height="420" row-key="scenarioId">
            <ElTableColumn prop="scenarioId" label="场景 ID" min-width="180" />
            <ElTableColumn prop="name" label="名称" min-width="180" />
            <ElTableColumn label="运行结果" width="180">
              <template #default="{ row }">
                <ElTag :type="statusType(row.runStatus)">{{ row.runStatus }}</ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="simulationStatus" label="模拟终态" width="210" />
            <ElTableColumn prop="terminalNodeId" label="终止节点" min-width="130" />
            <ElTableColumn label="问题" min-width="220">
              <template #default="{ row }">
                {{ [...row.issueCodes, ...row.expectationFailures].join('；') || '—' }}
              </template>
            </ElTableColumn>
            <ElTableColumn label="路径" width="100" fixed="right">
              <template #default="{ row }">
                <ElButton link type="primary" @click="showPath(row)">查看</ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
        </ElCard>

        <ElCard shadow="never">
          <template #header><strong>未覆盖项</strong></template>
          <ElEmpty
            v-if="report.uncoveredItems.length === 0"
            description="当前场景集已覆盖全部结构项"
            :image-size="72"
          />
          <ElTable v-else :data="report.uncoveredItems" height="320">
            <ElTableColumn prop="category" label="类别" width="190" />
            <ElTableColumn prop="stableId" label="稳定标识" min-width="360" />
          </ElTable>
        </ElCard>
      </template>
    </div>

    <ElDialog v-model="editorVisible" title="编辑模拟场景" width="760px">
      <ElForm v-if="currentEditor" label-position="top">
        <ElRow :gutter="16">
          <ElCol :span="12">
            <ElFormItem label="场景 ID">
              <ElInput v-model="currentEditor.scenarioId" />
            </ElFormItem>
          </ElCol>
          <ElCol :span="12">
            <ElFormItem label="场景名称">
              <ElInput v-model="currentEditor.name" />
            </ElFormItem>
          </ElCol>
        </ElRow>
        <ElFormItem label="Form values JSON">
          <ElInput v-model="currentEditor.formValuesText" type="textarea" :rows="6" />
        </ElFormItem>
        <ElFormItem label="Approval decisions JSON">
          <ElInput v-model="currentEditor.approvalDecisionsText" type="textarea" :rows="5" />
        </ElFormItem>
        <ElFormItem label="Identity snapshots JSON">
          <ElInput v-model="currentEditor.identitySnapshotsText" type="textarea" :rows="5" />
        </ElFormItem>
        <ElRow :gutter="16">
          <ElCol :span="12">
            <ElFormItem label="预期终态">
              <ElSelect v-model="currentEditor.expectedTerminalStatus" clearable>
                <ElOption label="COMPLETED" value="COMPLETED" />
                <ElOption label="REJECTED" value="REJECTED" />
                <ElOption label="BLOCKED" value="BLOCKED" />
                <ElOption
                  label="TRANSITION_LIMIT_REACHED"
                  value="TRANSITION_LIMIT_REACHED"
                />
              </ElSelect>
            </ElFormItem>
          </ElCol>
          <ElCol :span="12">
            <ElFormItem label="最大 transitions">
              <ElInputNumber
                v-model="currentEditor.maxTransitions"
                :min="1"
                :max="1000"
              />
            </ElFormItem>
          </ElCol>
        </ElRow>
        <ElFormItem label="预期访问节点（逗号分隔）">
          <ElInput v-model="currentEditor.expectedVisitedText" />
        </ElFormItem>
        <ElFormItem label="预期跳过节点（逗号分隔）">
          <ElInput v-model="currentEditor.expectedSkippedText" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="editorVisible = false">完成</ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="pathVisible" title="模拟路径" width="860px">
      <template v-if="pathResult">
        <ElAlert
          :closable="false"
          :title="pathResult.pathSummary"
          type="info"
        />
        <ElTable :data="pathResult.steps" height="420">
          <ElTableColumn prop="sequence" label="#" width="70" />
          <ElTableColumn prop="nodeId" label="节点 ID" min-width="150" />
          <ElTableColumn prop="kind" label="类型" width="140" />
          <ElTableColumn prop="outcome" label="结果" min-width="190" />
          <ElTableColumn prop="nextNodeId" label="下一节点" min-width="150" />
        </ElTable>
      </template>
    </ElDialog>
  </Page>
</template>

<style scoped>
.simulation-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(260px, 560px) auto;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.card-header,
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.card-header {
  justify-content: space-between;
  flex-wrap: wrap;
}

.card-header > div:first-child {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.card-header span {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.disclosure-select {
  width: 138px;
}

.coverage-card {
  padding: 14px;
  margin-bottom: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.coverage-card > div:first-child {
  display: flex;
  justify-content: space-between;
  margin-bottom: 10px;
}

.coverage-card strong {
  font-size: 20px;
}

.coverage-card small {
  display: block;
  margin-top: 8px;
  color: var(--el-text-color-secondary);
}

@media (max-width: 720px) {
  .toolbar {
    grid-template-columns: 1fr;
  }

  .card-header,
  .actions {
    align-items: stretch;
    flex-direction: column;
  }

  .disclosure-select {
    width: 100%;
  }
}
</style>
