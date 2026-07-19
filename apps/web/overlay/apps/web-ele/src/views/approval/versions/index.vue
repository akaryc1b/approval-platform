<script lang="ts" setup>
import type {
  ApprovalReleasePackageDetail,
  ApprovalReleaseVersionSummary,
  ApprovalStructuralDiffChange,
  ApprovalStructuralDiffResult,
  ApprovalVersionCenter,
} from '#/api/approval/release-version-management';

import { computed, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElCol,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElDrawer,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElProgress,
  ElRow,
  ElSelect,
  ElSkeleton,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  ElTag,
} from 'element-plus';

import { copyPublishedApprovalDesignDraft } from '#/api/approval/process-design';
import {
  deployApprovalReleasePackage,
  diffApprovalReleaseVersions,
  findApprovalDefinitionVersion,
  findApprovalReleasePackage,
  findApprovalVersionCenter,
} from '#/api/approval/release-version-management';

const definitionKey = ref('');
const center = ref<ApprovalVersionCenter>();
const loading = ref(false);
const operationLoading = ref(false);
const fromReleaseVersion = ref<number>();
const toReleaseVersion = ref<number>();
const diff = ref<ApprovalStructuralDiffResult>();
const diffLoading = ref(false);
const detail = ref<ApprovalReleasePackageDetail>();
const detailVisible = ref(false);
const detailLoading = ref(false);
const detailTab = ref('package');
const copyVisible = ref(false);
const copyLoading = ref(false);
const copySource = ref<ApprovalReleaseVersionSummary>();
const copyInput = ref({
  formPackageVersion: 1,
  name: '',
  targetDefinitionVersion: 1,
});

const releaseVersions = computed(() => center.value?.releaseVersions || []);
const definitionVersions = computed(() => center.value?.definitionVersions || []);
const highImpactChanges = computed(() => diff.value?.changes.filter(
  item => item.impact === 'HIGH',
).length || 0);
const changedSubjectCount = computed(() => new Set(
  diff.value?.changes.map(item => `${item.subjectType}:${item.subjectId}`) || [],
).size);

function message(error: unknown) {
  return error instanceof Error ? error.message : '版本管理请求失败';
}

function formatDate(value?: string) {
  if (!value) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(value));
}

function shortHash(value?: string) {
  return value ? `${value.slice(0, 8)}…${value.slice(-6)}` : '—';
}

function displayValue(value: unknown) {
  if (value === undefined || value === null || value === '') return '—';
  if (typeof value === 'string') return value;
  return JSON.stringify(value, null, 2);
}

function deploymentLabel(row: ApprovalReleaseVersionSummary) {
  if (!row.deployment) return '未部署';
  return {
    DEPLOYED: '已部署',
    FAILED: '部署失败',
    PENDING: '部署中',
  }[row.deployment.status];
}

function deploymentType(row: ApprovalReleaseVersionSummary) {
  if (!row.deployment) return 'info';
  return row.deployment.status === 'DEPLOYED'
    ? 'success'
    : row.deployment.status === 'FAILED'
      ? 'danger'
      : 'warning';
}

function impactLabel(impact: ApprovalStructuralDiffChange['impact']) {
  return { HIGH: '高', LOW: '低', MEDIUM: '中' }[impact];
}

function impactType(impact: ApprovalStructuralDiffChange['impact']) {
  return impact === 'HIGH' ? 'danger' : impact === 'MEDIUM' ? 'warning' : 'info';
}

function changeTypeLabel(type: ApprovalStructuralDiffChange['changeType']) {
  return {
    ADDED: '新增',
    MODIFIED: '修改',
    REMOVED: '删除',
    REORDERED: '重排',
  }[type];
}

function subjectTypeLabel(type: ApprovalStructuralDiffChange['subjectType']) {
  return {
    BPMN: 'BPMN',
    COMPILER: '编译器',
    CONDITION_ROUTE: '条件分支',
    DEFINITION: '流程定义',
    FORM_PACKAGE: '表单包',
    NODE: '节点',
    PARALLEL_BRANCH: '并行分支',
    RELEASE_PACKAGE: '发布包',
    UI_PERMISSIONS: '字段权限',
  }[type];
}

async function load() {
  const key = definitionKey.value.trim();
  if (!key) {
    ElMessage.warning('请输入流程定义 Key');
    return;
  }
  loading.value = true;
  diff.value = undefined;
  try {
    center.value = await findApprovalVersionCenter(key);
    const versions = center.value.releaseVersions.map(item => item.releaseVersion);
    toReleaseVersion.value = versions[0];
    fromReleaseVersion.value = versions[1] ?? versions[0];
  } catch (error) {
    center.value = undefined;
    ElMessage.error(message(error));
  } finally {
    loading.value = false;
  }
}

async function runDiff() {
  const key = center.value?.definitionKey;
  if (!key || !fromReleaseVersion.value || !toReleaseVersion.value) {
    ElMessage.warning('请选择两个发布版本');
    return;
  }
  diffLoading.value = true;
  try {
    diff.value = await diffApprovalReleaseVersions(
      key,
      fromReleaseVersion.value,
      toReleaseVersion.value,
    );
  } catch (error) {
    diff.value = undefined;
    ElMessage.error(message(error));
  } finally {
    diffLoading.value = false;
  }
}

async function openDetail(row: ApprovalReleaseVersionSummary) {
  if (!center.value) return;
  detailVisible.value = true;
  detailLoading.value = true;
  detailTab.value = 'package';
  detail.value = undefined;
  try {
    detail.value = await findApprovalReleasePackage(
      center.value.definitionKey,
      row.releaseVersion,
    );
  } catch (error) {
    detailVisible.value = false;
    ElMessage.error(message(error));
  } finally {
    detailLoading.value = false;
  }
}

async function deploy(row: ApprovalReleaseVersionSummary) {
  if (!center.value || row.deployment?.status === 'PENDING') return;
  const action = row.deployment?.status === 'FAILED' ? '重试部署' : '部署';
  try {
    await ElMessageBox.confirm(
      `${action} Release v${row.releaseVersion}，Package Hash ${shortHash(row.packageHash)}？`,
      `${action}确认`,
      { confirmButtonText: action, type: 'warning' },
    );
  } catch {
    return;
  }
  operationLoading.value = true;
  try {
    const result = await deployApprovalReleasePackage(
      center.value.definitionKey,
      row.releaseVersion,
    );
    ElMessage.success(
      result.replayedExistingDeployment ? '已返回现有部署结果' : '部署请求已完成',
    );
    await load();
  } catch (error) {
    ElMessage.error(message(error));
  } finally {
    operationLoading.value = false;
  }
}

async function openCopy(row: ApprovalReleaseVersionSummary) {
  if (!center.value) return;
  copySource.value = row;
  copyLoading.value = true;
  try {
    const source = await findApprovalDefinitionVersion(
      center.value.definitionKey,
      row.definitionVersion,
    );
    copyInput.value = {
      formPackageVersion: row.formPackageVersion,
      name: `${source.definition.name} 新版本`,
      targetDefinitionVersion: (center.value.latestDefinitionVersion || 0) + 1,
    };
    copyVisible.value = true;
  } catch (error) {
    ElMessage.error(message(error));
  } finally {
    copyLoading.value = false;
  }
}

async function submitCopy() {
  if (!center.value || !copySource.value || !copyInput.value.name.trim()) return;
  copyLoading.value = true;
  try {
    const created = await copyPublishedApprovalDesignDraft({
      definitionKey: center.value.definitionKey,
      formPackageVersion: copyInput.value.formPackageVersion,
      name: copyInput.value.name.trim(),
      sourceDefinitionVersion: copySource.value.definitionVersion,
      targetDefinitionVersion: copyInput.value.targetDefinitionVersion,
    });
    copyVisible.value = false;
    ElMessage.success(`新草稿已创建：${created.name}`);
  } catch (error) {
    ElMessage.error(message(error));
  } finally {
    copyLoading.value = false;
  }
}
</script>

<template>
  <Page title="流程版本管理">
    <div class="version-page">
      <ElCard shadow="never">
        <div class="search-bar">
          <ElInput
            v-model="definitionKey"
            clearable
            placeholder="输入流程定义 Key"
            @keyup.enter="load"
          />
          <ElButton type="primary" :loading="loading" @click="load">
            查询版本
          </ElButton>
        </div>
      </ElCard>

      <ElSkeleton v-if="loading" :rows="8" animated />

      <template v-else-if="center">
        <ElRow :gutter="16">
          <ElCol :lg="6" :sm="12" :xs="24">
            <ElCard class="summary-card" shadow="never">
              <span>最新 DSL</span>
              <strong>v{{ center.latestDefinitionVersion || '—' }}</strong>
            </ElCard>
          </ElCol>
          <ElCol :lg="6" :sm="12" :xs="24">
            <ElCard class="summary-card" shadow="never">
              <span>最新发布</span>
              <strong>v{{ center.latestPublishedReleaseVersion || '—' }}</strong>
            </ElCard>
          </ElCol>
          <ElCol :lg="6" :sm="12" :xs="24">
            <ElCard class="summary-card" shadow="never">
              <span>当前已部署</span>
              <strong>v{{ center.currentDeployedReleaseVersion || '—' }}</strong>
            </ElCard>
          </ElCol>
          <ElCol :lg="6" :sm="12" :xs="24">
            <ElCard class="summary-card" shadow="never">
              <span>当前生效</span>
              <strong>v{{ center.currentEffectiveReleaseVersion || '—' }}</strong>
            </ElCard>
          </ElCol>
        </ElRow>

        <ElCard shadow="never">
          <template #header>
            <div class="card-title">
              <div>
                <strong>Release Package 版本</strong>
                <span>{{ center.definitionKey }}</span>
              </div>
              <span>共 {{ center.releasePage.total }} 个版本</span>
            </div>
          </template>

          <ElTable :data="releaseVersions" row-key="releaseVersion">
            <ElTableColumn label="版本" width="105">
              <template #default="{ row }">
                <strong>v{{ row.releaseVersion }}</strong>
                <div class="subtle">DSL v{{ row.definitionVersion }}</div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="状态" min-width="190">
              <template #default="{ row }">
                <div class="tag-line">
                  <ElTag type="success">已发布</ElTag>
                  <ElTag :type="deploymentType(row)">
                    {{ deploymentLabel(row) }}
                  </ElTag>
                  <ElTag v-if="row.currentEffective" type="primary">当前生效</ElTag>
                  <ElTag v-else-if="row.currentDeployed" type="warning">最新部署</ElTag>
                </div>
                <div v-if="row.deployment?.lastErrorMessage" class="error-text">
                  {{ row.deployment.lastErrorMessage }}
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="制品摘要" min-width="260">
              <template #default="{ row }">
                <div class="hash-line">
                  <span>Package</span><code>{{ shortHash(row.packageHash) }}</code>
                </div>
                <div class="hash-line">
                  <span>BPMN</span><code>{{ shortHash(row.bpmnHash) }}</code>
                </div>
                <div class="hash-line">
                  <span>Form / UI</span>
                  <code>
                    v{{ row.formSchemaVersion }} / v{{ row.uiSchemaVersion }}
                  </code>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="发布" min-width="175">
              <template #default="{ row }">
                <div>{{ row.publishedBy }}</div>
                <div class="subtle">{{ formatDate(row.publishedAt) }}</div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="部署" min-width="180">
              <template #default="{ row }">
                <template v-if="row.deployment">
                  <div>{{ row.deployment.requestedBy }}</div>
                  <div class="subtle">
                    {{ formatDate(row.deployment.deployedAt || row.deployment.updatedAt) }}
                  </div>
                </template>
                <span v-else class="subtle">尚未部署</span>
              </template>
            </ElTableColumn>
            <ElTableColumn fixed="right" label="操作" min-width="260">
              <template #default="{ row }">
                <ElButton link type="primary" @click="openDetail(row)">详情</ElButton>
                <ElButton
                  link
                  type="primary"
                  :loading="copyLoading && copySource?.releaseVersion === row.releaseVersion"
                  @click="openCopy(row)"
                >
                  创建草稿
                </ElButton>
                <ElButton
                  v-if="row.deployment?.status !== 'DEPLOYED'"
                  link
                  type="warning"
                  :disabled="row.deployment?.status === 'PENDING'"
                  :loading="operationLoading"
                  @click="deploy(row)"
                >
                  {{ row.deployment?.status === 'FAILED' ? '重试部署' : '部署' }}
                </ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
        </ElCard>

        <ElCard shadow="never">
          <template #header>
            <div class="card-title">
              <div>
                <strong>结构化版本 Diff</strong>
                <span>按稳定节点 ID、条件路由和并行分支比较</span>
              </div>
            </div>
          </template>
          <div class="diff-toolbar">
            <ElSelect v-model="fromReleaseVersion" placeholder="基准版本">
              <ElOption
                v-for="item in releaseVersions"
                :key="item.releaseVersion"
                :label="`Release v${item.releaseVersion}`"
                :value="item.releaseVersion"
              />
            </ElSelect>
            <span>对比</span>
            <ElSelect v-model="toReleaseVersion" placeholder="目标版本">
              <ElOption
                v-for="item in releaseVersions"
                :key="item.releaseVersion"
                :label="`Release v${item.releaseVersion}`"
                :value="item.releaseVersion"
              />
            </ElSelect>
            <ElButton type="primary" :loading="diffLoading" @click="runDiff">
              开始比较
            </ElButton>
          </div>

          <template v-if="diff">
            <ElRow class="diff-summary" :gutter="16">
              <ElCol :md="8" :xs="24">
                <ElProgress
                  :percentage="diff.changes.length ? 100 : 0"
                  :status="highImpactChanges ? 'exception' : 'success'"
                />
              </ElCol>
              <ElCol :md="8" :xs="24">
                <strong>{{ diff.changes.length }}</strong> 项结构变化
              </ElCol>
              <ElCol :md="8" :xs="24">
                <strong>{{ changedSubjectCount }}</strong> 个受影响对象，
                <strong>{{ highImpactChanges }}</strong> 项高影响
              </ElCol>
            </ElRow>
            <ElAlert
              v-if="diff.changes.length === 0"
              :closable="false"
              show-icon
              title="两个版本的结构与制品关系一致"
              type="success"
            />
            <ElTable v-else :data="diff.changes">
              <ElTableColumn label="影响" width="80">
                <template #default="{ row }">
                  <ElTag :type="impactType(row.impact)">
                    {{ impactLabel(row.impact) }}
                  </ElTag>
                </template>
              </ElTableColumn>
              <ElTableColumn label="变化" width="90">
                <template #default="{ row }">
                  {{ changeTypeLabel(row.changeType) }}
                </template>
              </ElTableColumn>
              <ElTableColumn label="对象" min-width="190">
                <template #default="{ row }">
                  <div>{{ subjectTypeLabel(row.subjectType) }}</div>
                  <code>{{ row.subjectId }}</code>
                </template>
              </ElTableColumn>
              <ElTableColumn label="路径" min-width="250" prop="path" />
              <ElTableColumn label="变更前" min-width="220">
                <template #default="{ row }">
                  <pre class="diff-value">{{ displayValue(row.before) }}</pre>
                </template>
              </ElTableColumn>
              <ElTableColumn label="变更后" min-width="220">
                <template #default="{ row }">
                  <pre class="diff-value">{{ displayValue(row.after) }}</pre>
                </template>
              </ElTableColumn>
            </ElTable>
          </template>
          <ElEmpty v-else description="选择两个发布版本查看结构化差异" />
        </ElCard>

        <ElCard shadow="never">
          <template #header>
            <div class="card-title">
              <div>
                <strong>Approval DSL 历史版本</strong>
                <span>不可变发布快照</span>
              </div>
              <span>共 {{ center.definitionPage.total }} 个版本</span>
            </div>
          </template>
          <ElTable :data="definitionVersions" row-key="definitionVersion">
            <ElTableColumn label="DSL 版本" width="120">
              <template #default="{ row }">
                <strong>v{{ row.definitionVersion }}</strong>
              </template>
            </ElTableColumn>
            <ElTableColumn label="DSL Hash" min-width="190">
              <template #default="{ row }">
                <code>{{ shortHash(row.definitionHash) }}</code>
              </template>
            </ElTableColumn>
            <ElTableColumn label="Form Package" min-width="190">
              <template #default="{ row }">
                v{{ row.formPackageVersion }} ·
                <code>{{ shortHash(row.formPackageHash) }}</code>
              </template>
            </ElTableColumn>
            <ElTableColumn label="发布人" prop="publishedBy" min-width="150" />
            <ElTableColumn label="发布时间" min-width="180">
              <template #default="{ row }">
                {{ formatDate(row.publishedAt) }}
              </template>
            </ElTableColumn>
          </ElTable>
        </ElCard>
      </template>

      <ElEmpty v-else description="输入流程定义 Key 查询真实发布版本" />
    </div>

    <ElDrawer v-model="detailVisible" size="72%" title="Release Package 详情">
      <ElSkeleton v-if="detailLoading" :rows="10" animated />
      <ElTabs v-else-if="detail" v-model="detailTab">
        <ElTabPane label="制品关系" name="package">
          <ElDescriptions :column="2" border>
            <ElDescriptionsItem label="Release 版本">
              v{{ detail.releaseVersion }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="DSL 版本">
              v{{ detail.definitionVersion }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="DSL Hash">
              <code>{{ detail.definitionHash }}</code>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="Package Hash">
              <code>{{ detail.packageHash }}</code>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="Form Package">
              v{{ detail.formPackageVersion }} ·
              <code>{{ detail.formPackageHash }}</code>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="Form Schema">
              v{{ detail.formVersion }} · <code>{{ detail.formHash }}</code>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="UI Schema">
              v{{ detail.uiSchemaVersion }} · <code>{{ detail.uiSchemaHash }}</code>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="编译器">
              {{ detail.compilerVersion }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="Compiled Artifact Hash">
              <code>{{ detail.compiledArtifactHash }}</code>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="Deployment Metadata Hash">
              <code>{{ detail.deploymentMetadataHash }}</code>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="发布人">
              {{ detail.publishedBy }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="发布时间">
              {{ formatDate(detail.publishedAt) }}
            </ElDescriptionsItem>
          </ElDescriptions>
        </ElTabPane>
        <ElTabPane label="BPMN 制品" name="bpmn">
          <div class="artifact-heading">
            <span>{{ detail.bpmnResourceName }}</span>
            <code>{{ detail.bpmnHash }}</code>
          </div>
          <ElInput
            :model-value="detail.bpmnArtifact"
            readonly
            resize="vertical"
            :rows="24"
            type="textarea"
          />
        </ElTabPane>
      </ElTabs>
    </ElDrawer>

    <ElDialog v-model="copyVisible" title="从历史版本创建新草稿" width="520px">
      <ElForm label-position="top">
        <ElFormItem label="来源版本">
          <ElInput
            :model-value="copySource ? `DSL v${copySource.definitionVersion}` : ''"
            disabled
          />
        </ElFormItem>
        <ElFormItem label="草稿名称">
          <ElInput v-model="copyInput.name" maxlength="128" />
        </ElFormItem>
        <ElRow :gutter="16">
          <ElCol :span="12">
            <ElFormItem label="目标 DSL 版本">
              <ElInputNumber
                v-model="copyInput.targetDefinitionVersion"
                :min="1"
                controls-position="right"
              />
            </ElFormItem>
          </ElCol>
          <ElCol :span="12">
            <ElFormItem label="Form Package 版本">
              <ElInputNumber
                v-model="copyInput.formPackageVersion"
                :min="1"
                controls-position="right"
              />
            </ElFormItem>
          </ElCol>
        </ElRow>
      </ElForm>
      <template #footer>
        <ElButton @click="copyVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="copyLoading" @click="submitCopy">
          创建草稿
        </ElButton>
      </template>
    </ElDialog>
  </Page>
</template>

<style scoped>
.version-page {
  display: grid;
  gap: 16px;
}

.search-bar {
  display: grid;
  grid-template-columns: minmax(260px, 560px) auto;
  gap: 12px;
  justify-content: start;
}

.summary-card :deep(.el-card__body) {
  display: flex;
  min-height: 88px;
  flex-direction: column;
  justify-content: center;
  gap: 8px;
}

.summary-card span,
.subtle,
.card-title span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.summary-card strong {
  font-size: 24px;
}

.card-title,
.card-title > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-title > div {
  justify-content: flex-start;
}

.tag-line,
.diff-toolbar,
.hash-line,
.artifact-heading {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tag-line {
  flex-wrap: wrap;
}

.hash-line {
  justify-content: space-between;
  line-height: 24px;
}

.hash-line span {
  color: var(--el-text-color-secondary);
}

.error-text {
  margin-top: 6px;
  color: var(--el-color-danger);
  font-size: 12px;
}

.diff-toolbar {
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.diff-toolbar :deep(.el-select) {
  width: 220px;
}

.diff-summary {
  align-items: center;
  margin-bottom: 16px;
}

.diff-value {
  max-height: 160px;
  overflow: auto;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.artifact-heading {
  justify-content: space-between;
  margin-bottom: 12px;
}

code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
}

@media (max-width: 640px) {
  .search-bar {
    grid-template-columns: 1fr;
  }

  .card-title,
  .card-title > div,
  .artifact-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
