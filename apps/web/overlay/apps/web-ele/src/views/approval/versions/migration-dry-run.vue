<script lang="ts" setup>
import type {
  ReleaseMigrationAssessment,
  ReleaseMigrationAssessmentFinding,
  ReleaseMigrationAssessmentStatus,
  ReleaseMigrationInstanceDecision,
} from '#/api/approval/release-migration-assessment';

import { computed, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElSpace,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';

import { runReleaseMigrationDryRun } from '#/api/approval/release-migration-assessment';

const loading = ref(false);
const report = ref<ReleaseMigrationAssessment>();
const form = reactive({
  definitionKey: '',
  limit: 100,
  offset: 0,
  reason: '',
  sourceReleaseVersion: 1,
  targetReleaseVersion: 2,
});

const pagingText = computed(() => {
  if (!report.value) return '-';
  const start = report.value.total === 0 ? 0 : report.value.offset + 1;
  const end = Math.min(report.value.offset + report.value.instances.length, report.value.total);
  return `${start}–${end} / ${report.value.total}${report.value.hasMore ? '（仍有后续数据）' : ''}`;
});

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '版本迁移评估请求失败';
}

function statusType(status: ReleaseMigrationAssessmentStatus) {
  if (status === 'READY') return 'success';
  if (status === 'BLOCKED') return 'danger';
  if (status === 'PARTIAL') return 'warning';
  return 'info';
}

function decisionType(decision: ReleaseMigrationInstanceDecision) {
  if (decision === 'ELIGIBLE') return 'success';
  if (decision === 'BLOCKED') return 'danger';
  return 'info';
}

function findingType(finding: ReleaseMigrationAssessmentFinding) {
  return finding.severity === 'BLOCKER' ? 'danger' : 'warning';
}

function dateTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}

async function generateReport() {
  if (form.sourceReleaseVersion === form.targetReleaseVersion) {
    ElMessage.warning('源发布版本与目标发布版本必须不同');
    return;
  }
  loading.value = true;
  try {
    report.value = await runReleaseMigrationDryRun(
      form.definitionKey,
      form.sourceReleaseVersion,
      {
        limit: form.limit,
        offset: form.offset,
        targetReleaseVersion: form.targetReleaseVersion,
      },
      form.reason,
    );
    ElMessage.success('detect-only 评估报告已生成，未执行任何实例迁移');
  } catch (error) {
    report.value = undefined;
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

function reset() {
  form.definitionKey = '';
  form.sourceReleaseVersion = 1;
  form.targetReleaseVersion = 2;
  form.reason = '';
  form.limit = 100;
  form.offset = 0;
  report.value = undefined;
}

async function copyText(value: string, label: string) {
  try {
    await navigator.clipboard.writeText(value);
    ElMessage.success(`${label}已复制`);
  } catch {
    ElMessage.error(`${label}复制失败，请手动选择复制`);
  }
}

function assessmentSummary() {
  const value = report.value;
  if (!value) return '';
  return [
    '版本迁移评估（detect-only）',
    `评估 ID：${value.assessmentId}`,
    `流程定义：${value.definitionKey}`,
    `版本：v${value.sourceReleaseVersion} → v${value.targetReleaseVersion}`,
    `状态：${value.status}`,
    `完整：${value.complete}`,
    `实例：total=${value.total}, running=${value.runningCount}, eligible=${value.eligibleCount}, blocked=${value.blockedCount}, terminal=${value.terminalCount}`,
    `高影响变更：${value.highImpactCount}`,
    `报告哈希：${value.reportHash}`,
    `评估时间：${value.assessedAt}`,
    '说明：仅生成评估证据，不执行实例迁移。',
  ].join('\n');
}
</script>

<template>
  <Page
    title="版本迁移评估"
    description="评估运行中审批实例与目标发布版本的兼容性"
  >
    <div class="stack">
      <ElAlert
        :closable="false"
        show-icon
        title="仅生成评估证据，不执行实例迁移"
        type="warning"
      >
        <template #default>
          当前页面仅提供 detect-only 报告能力，不提供迁移执行、强制迁移、实例修改或运行绑定重写能力。
        </template>
      </ElAlert>

      <ElAlert
        :closable="false"
        show-icon
        title="真实迁移可能产生不可逆的流程运行影响"
        type="error"
      >
        <template #default>
          本报告只能作为后续治理决策证据；任何未来迁移都必须重新验证实例状态、任务 Key、发布包与绑定证据。
        </template>
      </ElAlert>

      <ElCard shadow="never">
        <template #header><b>评估条件</b></template>
        <ElForm class="query-form" label-position="top">
          <ElFormItem label="流程定义 Key" required>
            <ElInput
              v-model="form.definitionKey"
              clearable
              placeholder="例如 purchase-payment"
              @keyup.enter="generateReport"
            />
          </ElFormItem>
          <ElFormItem label="源发布版本" required>
            <ElInputNumber v-model="form.sourceReleaseVersion" :min="1" :precision="0" />
          </ElFormItem>
          <ElFormItem label="目标发布版本" required>
            <ElInputNumber v-model="form.targetReleaseVersion" :min="1" :precision="0" />
          </ElFormItem>
          <ElFormItem label="每页数量">
            <ElInputNumber v-model="form.limit" :max="100" :min="1" :precision="0" />
          </ElFormItem>
          <ElFormItem label="偏移量">
            <ElInputNumber v-model="form.offset" :min="0" :precision="0" />
          </ElFormItem>
          <ElFormItem class="reason-field" label="操作原因" required>
            <ElInput
              v-model="form.reason"
              maxlength="512"
              placeholder="请输入 8–512 个 Unicode 字符的治理原因"
              show-word-limit
              type="textarea"
              :rows="3"
            />
          </ElFormItem>
        </ElForm>
        <ElSpace wrap>
          <ElButton type="primary" :loading="loading" @click="generateReport">
            生成评估报告
          </ElButton>
          <ElButton :disabled="loading" @click="reset">重置</ElButton>
          <ElButton
            :disabled="!report"
            @click="report && copyText(report.reportHash, '报告哈希')"
          >
            复制报告哈希
          </ElButton>
          <ElButton
            :disabled="!report"
            @click="report && copyText(assessmentSummary(), '评估摘要')"
          >
            复制评估摘要
          </ElButton>
        </ElSpace>
      </ElCard>

      <template v-if="report">
        <ElCard shadow="never">
          <template #header>
            <div class="section-header">
              <b>评估总览</b>
              <ElSpace>
                <ElTag :type="statusType(report.status)" effect="dark">{{ report.status }}</ElTag>
                <ElTag :type="report.detectOnly ? 'warning' : 'danger'">
                  detectOnly={{ report.detectOnly }}
                </ElTag>
                <ElTag :type="report.complete ? 'success' : 'warning'">
                  complete={{ report.complete }}
                </ElTag>
              </ElSpace>
            </div>
          </template>
          <div class="summary-grid">
            <div><span>全部绑定</span><b>{{ report.total }}</b></div>
            <div><span>运行中</span><b>{{ report.runningCount }}</b></div>
            <div><span>可评估迁移</span><b>{{ report.eligibleCount }}</b></div>
            <div><span>阻断</span><b>{{ report.blockedCount }}</b></div>
            <div><span>终态跳过</span><b>{{ report.terminalCount }}</b></div>
            <div><span>高影响变更</span><b>{{ report.highImpactCount }}</b></div>
          </div>
        </ElCard>

        <ElCard shadow="never">
          <template #header><b>版本证据</b></template>
          <ElDescriptions :column="2" border>
            <ElDescriptionsItem label="流程定义 Key" :span="2">
              {{ report.definitionKey }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="源发布版本">
              v{{ report.sourceReleaseVersion }} · {{ report.sourceLifecycleState }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="目标发布版本">
              v{{ report.targetReleaseVersion }} · {{ report.targetLifecycleState }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="源 Package Hash" :span="2">
              <code class="hash-value">{{ report.sourcePackageHash }}</code>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="目标 Package Hash" :span="2">
              <code class="hash-value">{{ report.targetPackageHash }}</code>
            </ElDescriptionsItem>
          </ElDescriptions>
        </ElCard>

        <ElCard shadow="never">
          <template #header><b>全局发现</b></template>
          <ElTable v-if="report.globalFindings.length" :data="report.globalFindings">
            <ElTableColumn label="级别" width="120">
              <template #default="scope">
                <ElTag :type="findingType(scope.row)">{{ scope.row.severity }}</ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="代码" min-width="220" prop="code" />
            <ElTableColumn label="任务 Key" min-width="180" prop="taskDefinitionKey" />
            <ElTableColumn label="发现" min-width="360" prop="message" />
          </ElTable>
          <ElEmpty v-else description="本次评估没有全局发现" />
        </ElCard>

        <ElCard shadow="never">
          <template #header>
            <div class="section-header">
              <b>实例明细</b>
              <span class="muted">{{ pagingText }}</span>
            </div>
          </template>
          <ElTable
            v-if="report.instances.length"
            :data="report.instances"
            row-key="approvalInstanceId"
          >
            <ElTableColumn type="expand">
              <template #default="scope">
                <div class="finding-panel">
                  <b>实例发现</b>
                  <ElTable v-if="scope.row.findings.length" :data="scope.row.findings">
                    <ElTableColumn label="级别" width="120">
                      <template #default="findingScope">
                        <ElTag :type="findingType(findingScope.row)">
                          {{ findingScope.row.severity }}
                        </ElTag>
                      </template>
                    </ElTableColumn>
                    <ElTableColumn label="代码" min-width="220" prop="code" />
                    <ElTableColumn label="任务 Key" min-width="180" prop="taskDefinitionKey" />
                    <ElTableColumn label="发现" min-width="360" prop="message" />
                  </ElTable>
                  <ElEmpty v-else description="该实例没有发现" />
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="审批实例 ID" min-width="250" prop="approvalInstanceId" />
            <ElTableColumn label="业务 Key" min-width="180" prop="businessKey" />
            <ElTableColumn label="引擎实例 ID" min-width="220" prop="engineInstanceId" />
            <ElTableColumn label="实例状态" width="130" prop="instanceStatus" />
            <ElTableColumn label="决策" width="175">
              <template #default="scope">
                <ElTag :type="decisionType(scope.row.decision)">{{ scope.row.decision }}</ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="活动任务 Key" min-width="240">
              <template #default="scope">
                <ElSpace v-if="scope.row.activeTaskDefinitionKeys.length" wrap>
                  <ElTag
                    v-for="taskKey in scope.row.activeTaskDefinitionKeys"
                    :key="taskKey"
                    type="info"
                  >
                    {{ taskKey }}
                  </ElTag>
                </ElSpace>
                <span v-else>-</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="Binding Evidence Hash" min-width="320">
              <template #default="scope">
                <code class="hash-value">{{ scope.row.bindingEvidenceHash }}</code>
              </template>
            </ElTableColumn>
          </ElTable>
          <ElEmpty v-else description="本页没有可显示的实例评估记录" />
        </ElCard>

        <ElCard shadow="never">
          <template #header><b>报告证据</b></template>
          <ElDescriptions :column="2" border>
            <ElDescriptionsItem label="Assessment ID">{{ report.assessmentId }}</ElDescriptionsItem>
            <ElDescriptionsItem label="评估时间">{{ dateTime(report.assessedAt) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="完整报告">{{ report.complete }}</ElDescriptionsItem>
            <ElDescriptionsItem label="分页">{{ pagingText }}</ElDescriptionsItem>
            <ElDescriptionsItem label="Report Hash" :span="2">
              <div class="hash-row">
                <code class="hash-value">{{ report.reportHash }}</code>
                <ElButton link type="primary" @click="copyText(report.reportHash, '报告哈希')">
                  复制
                </ElButton>
              </div>
            </ElDescriptionsItem>
          </ElDescriptions>
        </ElCard>
      </template>

      <ElEmpty v-else description="填写评估条件后生成 detect-only 报告" />
    </div>
  </Page>
</template>

<style scoped>
.stack { display: grid; gap: 16px; }
.query-form { display: grid; gap: 0 16px; grid-template-columns: minmax(260px, 2fr) repeat(4, minmax(150px, 1fr)); }
.query-form .el-input-number { width: 100%; }
.reason-field { grid-column: 1 / -1; }
.section-header, .hash-row { align-items: center; display: flex; gap: 12px; justify-content: space-between; }
.summary-grid { display: grid; gap: 12px; grid-template-columns: repeat(6, minmax(120px, 1fr)); }
.summary-grid div { background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-lighter); border-radius: 8px; display: grid; gap: 8px; padding: 14px; }
.summary-grid span, .muted { color: var(--el-text-color-secondary); }
.summary-grid b { font-size: 24px; }
.hash-value { overflow-wrap: anywhere; white-space: normal; }
.finding-panel { padding: 12px 48px 20px; }
.finding-panel > b { display: block; margin-bottom: 12px; }
@media (max-width: 1200px) {
  .query-form { grid-template-columns: repeat(2, minmax(220px, 1fr)); }
  .summary-grid { grid-template-columns: repeat(3, minmax(120px, 1fr)); }
}
@media (max-width: 720px) {
  .query-form, .summary-grid { grid-template-columns: 1fr; }
  .reason-field { grid-column: auto; }
  .section-header, .hash-row { align-items: flex-start; flex-direction: column; }
}
</style>
