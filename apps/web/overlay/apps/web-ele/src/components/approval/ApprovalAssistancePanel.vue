<script lang="ts" setup>
import type {
  ApprovalAssistanceGenerationView,
  ApprovalAssistanceReadView,
  ApprovalAssistanceUseCase,
} from '#/api/approval/assistance';

import { computed, ref, watch } from 'vue';

import {
  ElAlert,
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElTag,
} from 'element-plus';

import {
  findApprovalAssistance,
  generateApprovalAssistance,
} from '#/api/approval/assistance';

const props = defineProps<{ taskId: string }>();

const DEFAULT_USE_CASES: ApprovalAssistanceUseCase[] = [
  'SUMMARY',
  'MATERIAL_COMPLETENESS',
  'RISK_REVIEW',
];

const selectedUseCase = ref<ApprovalAssistanceUseCase>('SUMMARY');
const loading = ref(false);
const generating = ref(false);
const loadError = ref('');
const generationError = ref('');
const assistance = ref<ApprovalAssistanceReadView>();
const generation = ref<ApprovalAssistanceGenerationView>();
const availableUseCases = computed(
  () => assistance.value?.availableUseCases || DEFAULT_USE_CASES,
);

const useCaseLabels: Record<ApprovalAssistanceUseCase, string> = {
  MATERIAL_COMPLETENESS: '材料完整性',
  RISK_REVIEW: '风险复核',
  SUMMARY: '审批摘要',
};

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'AI 辅助请求失败';
}

async function loadAssistance() {
  if (!props.taskId) return;
  loading.value = true;
  loadError.value = '';
  try {
    assistance.value = await findApprovalAssistance(
      props.taskId,
      selectedUseCase.value,
    );
  } catch (error) {
    assistance.value = undefined;
    loadError.value = errorMessage(error);
  } finally {
    loading.value = false;
  }
}

async function selectUseCase(useCase: ApprovalAssistanceUseCase) {
  selectedUseCase.value = useCase;
  generation.value = undefined;
  generationError.value = '';
  await loadAssistance();
}

async function generateAssistance() {
  if (
    !props.taskId ||
    generating.value ||
    assistance.value?.availability !== 'AVAILABLE'
  ) return;
  generating.value = true;
  generationError.value = '';
  generation.value = undefined;
  try {
    generation.value = await generateApprovalAssistance(
      props.taskId,
      selectedUseCase.value,
    );
  } catch (error) {
    generationError.value = errorMessage(error);
  } finally {
    generating.value = false;
  }
}

watch(
  () => props.taskId,
  () => {
    selectedUseCase.value = 'SUMMARY';
    assistance.value = undefined;
    generation.value = undefined;
    generationError.value = '';
    void loadAssistance();
  },
  { immediate: true },
);
</script>

<template>
  <section class="assistance-panel" aria-label="AI 辅助（未验证）">
    <div class="assistance-header">
      <div>
        <h3>AI 辅助（未验证）</h3>
        <p>仅供参考，必须人工复核；AI 不拥有审批权限。</p>
      </div>
      <div class="assistance-tags">
        <ElTag type="warning">ADVISORY</ElTag>
        <ElTag type="danger">UNVERIFIED_ADVISORY</ElTag>
        <ElTag type="info">必须人工复核</ElTag>
      </div>
    </div>

    <div class="use-case-list" aria-label="AI 辅助类型">
      <ElButton
        v-for="useCase in availableUseCases"
        :key="useCase"
        :loading="loading && selectedUseCase === useCase"
        :plain="selectedUseCase !== useCase"
        :disabled="generating"
        size="small"
        type="warning"
        @click="selectUseCase(useCase)"
      >
        {{ useCaseLabels[useCase] }}
      </ElButton>
    </div>

    <ElAlert
      v-if="loadError"
      :closable="false"
      :title="loadError"
      type="error"
    />
    <template v-else-if="assistance">
      <ElDescriptions :column="2" border class="snapshot" title="服务端证据快照">
        <ElDescriptionsItem label="辅助类型">
          {{ useCaseLabels[assistance.requestedUseCase] }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="只读状态">
          {{ assistance.code }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="流程版本">
          {{ assistance.taskSnapshot.definitionKey }} v{{ assistance.taskSnapshot.definitionVersion }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="表单版本">
          {{ assistance.taskSnapshot.formKey }} v{{ assistance.taskSnapshot.formVersion }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="编译器版本">
          {{ assistance.taskSnapshot.compilerVersion }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="任务更新时间">
          {{ assistance.taskSnapshot.taskUpdatedAt }}
        </ElDescriptionsItem>
      </ElDescriptions>

      <div class="generation-action">
        <ElButton
          :disabled="generating || assistance.availability !== 'AVAILABLE'"
          :loading="generating"
          type="warning"
          @click="generateAssistance"
        >
          显式生成 AI 建议
        </ElButton>
        <span>仅本次点击触发一次；页面加载、切换和刷新不会自动生成。</span>
      </div>

      <ElAlert
        v-if="generationError"
        :closable="false"
        :title="generationError"
        type="error"
      />

      <article
        v-if="generation?.advisoryResult"
        class="advisory-result"
        aria-label="未经验证的 AI 建议"
      >
        <div class="result-heading">
          <strong>AI 建议（未经验证）</strong>
          <ElTag :type="generation.status === 'LOW_CONFIDENCE' ? 'danger' : 'warning'">
            {{ generation.status }}
          </ElTag>
        </div>
        <p class="summary">{{ generation.advisoryResult.summary }}</p>
        <p class="confidence">
          置信度：{{ generation.advisoryResult.confidence.band }}
          （{{ generation.advisoryResult.confidence.score }}）
        </p>

        <section v-if="generation.advisoryResult.observations.length">
          <h4>观察</h4>
          <ul>
            <li v-for="item in generation.advisoryResult.observations" :key="item.id">
              {{ item.text }}
            </li>
          </ul>
        </section>
        <section v-if="generation.advisoryResult.riskSignals.length">
          <h4>风险复核信号</h4>
          <ul>
            <li v-for="item in generation.advisoryResult.riskSignals" :key="item.id">
              [{{ item.severity }}] {{ item.text }}
            </li>
          </ul>
        </section>
        <section v-if="generation.advisoryResult.missingMaterials.length">
          <h4>材料提示</h4>
          <ul>
            <li v-for="item in generation.advisoryResult.missingMaterials" :key="item.id">
              {{ item.materialType }}：{{ item.reason }}
            </li>
          </ul>
        </section>
        <section v-if="generation.advisoryResult.recommendations.length">
          <h4>人工复核建议</h4>
          <ul>
            <li v-for="item in generation.advisoryResult.recommendations" :key="item.id">
              {{ item.text }}
            </li>
          </ul>
        </section>
        <section>
          <h4>限制</h4>
          <ul>
            <li v-for="item in generation.advisoryResult.limitations" :key="item">
              {{ item }}
            </li>
          </ul>
        </section>
        <p class="evidence-id">证据 ID：{{ generation.evidenceId }}</p>
      </article>

      <ul class="limitations" aria-label="AI 辅助限制">
        <li v-for="limitation in assistance.limitations" :key="limitation.code">
          <strong>{{ limitation.code }}</strong>
          <span>{{ limitation.message }}</span>
        </li>
      </ul>
      <p class="command-boundary">
        本区域不会填写审批意见，不提供同意、驳回、转办或其他命令。
      </p>
    </template>
  </section>
</template>

<style scoped>
.assistance-panel {
  display: grid;
  gap: 16px;
  padding: 18px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 12px;
  background: var(--el-color-warning-light-9);
}

.assistance-header,
.assistance-tags,
.use-case-list,
.generation-action,
.result-heading {
  display: flex;
  align-items: center;
  gap: 10px;
}

.assistance-header,
.result-heading {
  justify-content: space-between;
}

.assistance-header h3,
.assistance-header p,
.advisory-result h4,
.advisory-result p {
  margin: 0;
}

.assistance-header p,
.command-boundary,
.limitations span,
.generation-action span,
.confidence,
.evidence-id {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.assistance-tags,
.use-case-list {
  flex-wrap: wrap;
}

.snapshot,
.advisory-result {
  background: var(--el-bg-color);
}

.advisory-result {
  display: grid;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 10px;
}

.advisory-result section {
  display: grid;
  gap: 6px;
}

.advisory-result ul,
.limitations {
  display: grid;
  gap: 8px;
  margin: 0;
  padding-left: 20px;
}

.limitations li {
  display: grid;
  gap: 2px;
}

.command-boundary {
  margin: 0;
  font-weight: 600;
}

@media (max-width: 720px) {
  .assistance-header,
  .generation-action {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
