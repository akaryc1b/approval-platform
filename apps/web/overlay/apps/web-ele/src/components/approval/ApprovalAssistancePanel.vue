<script lang="ts" setup>
import type {
  ApprovalAssistanceReadView,
  ApprovalAssistanceUseCase,
} from '#/api/approval/assistance';

import { ref, watch } from 'vue';

import {
  ElAlert,
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElTag,
} from 'element-plus';

import { findApprovalAssistance } from '#/api/approval/assistance';

const props = defineProps<{ taskId: string }>();

const selectedUseCase = ref<ApprovalAssistanceUseCase>('SUMMARY');
const loading = ref(false);
const loadError = ref('');
const assistance = ref<ApprovalAssistanceReadView>();

const useCaseLabels: Record<ApprovalAssistanceUseCase, string> = {
  MATERIAL_COMPLETENESS: '材料完整性',
  RISK_REVIEW: '风险复核',
  SUMMARY: '审批摘要',
};

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'AI 辅助状态加载失败';
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
  await loadAssistance();
}

watch(
  () => props.taskId,
  () => {
    selectedUseCase.value = 'SUMMARY';
    assistance.value = undefined;
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
        v-for="useCase in assistance?.availableUseCases || ['SUMMARY', 'MATERIAL_COMPLETENESS', 'RISK_REVIEW']"
        :key="useCase"
        :loading="loading && selectedUseCase === useCase"
        :plain="selectedUseCase !== useCase"
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
      <ElAlert
        :closable="false"
        show-icon
        title="生产 AI Provider 尚未配置；当前不会生成或伪造任何 AI 内容。"
        type="warning"
      />
      <ElDescriptions :column="2" border class="snapshot" title="服务端证据快照">
        <ElDescriptionsItem label="辅助类型">
          {{ useCaseLabels[assistance.requestedUseCase] }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="状态">
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
.use-case-list {
  display: flex;
  align-items: center;
  gap: 10px;
}

.assistance-header {
  justify-content: space-between;
}

.assistance-header h3,
.assistance-header p {
  margin: 0;
}

.assistance-header p,
.command-boundary,
.limitations span {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.assistance-tags,
.use-case-list {
  flex-wrap: wrap;
}

.snapshot {
  background: var(--el-bg-color);
}

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
  .assistance-header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
