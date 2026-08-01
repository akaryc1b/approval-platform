<script lang="ts" setup>
import type {
  ApprovalAssistanceReadView,
  ApprovalAssistanceUseCase,
} from '@/api/approval/assistance'

import { computed, ref, watch } from 'vue'

import { findApprovalAssistance } from '@/api/approval/assistance'

const props = defineProps<{ taskId: string }>()

const DEFAULT_USE_CASES: ApprovalAssistanceUseCase[] = [
  'SUMMARY',
  'MATERIAL_COMPLETENESS',
  'RISK_REVIEW',
]

const selectedUseCase = ref<ApprovalAssistanceUseCase>('SUMMARY')
const loading = ref(false)
const loadError = ref('')
const assistance = ref<ApprovalAssistanceReadView>()
const availableUseCases = computed(
  () => assistance.value?.availableUseCases || DEFAULT_USE_CASES,
)

const useCaseLabels: Record<ApprovalAssistanceUseCase, string> = {
  MATERIAL_COMPLETENESS: '材料完整性',
  RISK_REVIEW: '风险复核',
  SUMMARY: '审批摘要',
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'AI 辅助状态加载失败'
}

async function loadAssistance() {
  if (!props.taskId) return
  loading.value = true
  loadError.value = ''
  try {
    assistance.value = await findApprovalAssistance(
      props.taskId,
      selectedUseCase.value,
    )
  }
  catch (error) {
    assistance.value = undefined
    loadError.value = errorMessage(error)
  }
  finally {
    loading.value = false
  }
}

async function selectUseCase(useCase: ApprovalAssistanceUseCase) {
  selectedUseCase.value = useCase
  await loadAssistance()
}

watch(
  () => props.taskId,
  () => {
    selectedUseCase.value = 'SUMMARY'
    assistance.value = undefined
    void loadAssistance()
  },
  { immediate: true },
)
</script>

<template>
  <view class="assistance-card" aria-label="AI 辅助（未验证）">
    <view class="assistance-header">
      <view>
        <view class="assistance-title">AI 辅助（未验证）</view>
        <text class="assistance-subtitle">仅供参考，必须人工复核；AI 不拥有审批权限。</text>
      </view>
      <wd-tag type="warning">ADVISORY</wd-tag>
    </view>

    <view class="safety-tags">
      <wd-tag type="danger">UNVERIFIED_ADVISORY</wd-tag>
      <wd-tag plain type="warning">必须人工复核</wd-tag>
    </view>

    <view class="use-case-list" aria-label="AI 辅助类型">
      <wd-button
        v-for="useCase in availableUseCases"
        :key="useCase"
        :loading="loading && selectedUseCase === useCase"
        :plain="selectedUseCase !== useCase"
        size="small"
        type="warning"
        @click="selectUseCase(useCase)"
      >
        {{ useCaseLabels[useCase] }}
      </wd-button>
    </view>

    <view v-if="loadError" class="assistance-error">{{ loadError }}</view>
    <template v-else-if="assistance">
      <view class="provider-notice">
        生产 AI Provider 尚未配置；当前不会生成或伪造任何 AI 内容。
      </view>
      <view class="snapshot-grid">
        <view><text>辅助类型</text><strong>{{ useCaseLabels[assistance.requestedUseCase] }}</strong></view>
        <view><text>状态</text><strong>{{ assistance.code }}</strong></view>
        <view><text>流程版本</text><strong>{{ assistance.taskSnapshot.definitionKey }} v{{ assistance.taskSnapshot.definitionVersion }}</strong></view>
        <view><text>表单版本</text><strong>{{ assistance.taskSnapshot.formKey }} v{{ assistance.taskSnapshot.formVersion }}</strong></view>
        <view><text>编译器版本</text><strong>{{ assistance.taskSnapshot.compilerVersion }}</strong></view>
        <view><text>任务更新时间</text><strong>{{ assistance.taskSnapshot.taskUpdatedAt }}</strong></view>
      </view>
      <view class="limitations" aria-label="AI 辅助限制">
        <view v-for="limitation in assistance.limitations" :key="limitation.code">
          <strong>{{ limitation.code }}</strong>
          <text>{{ limitation.message }}</text>
        </view>
      </view>
      <text class="command-boundary">
        本区域不会填写审批意见，不提供同意、驳回、转办或其他命令。
      </text>
    </template>
  </view>
</template>

<style scoped>
.assistance-card {
  display: grid;
  gap: 20rpx;
  margin-bottom: 20rpx;
  padding: 28rpx;
  border: 1rpx solid var(--wot-color-warning, var(--uni-color-warning));
  border-radius: 24rpx;
  background: var(--wot-color-warning-light, rgb(245 158 11 / 8%));
  box-shadow: 0 8rpx 24rpx rgb(15 23 42 / 5%);
}

.assistance-header,
.safety-tags,
.use-case-list {
  display: flex;
  align-items: center;
  gap: 12rpx;
}

.assistance-header {
  justify-content: space-between;
}

.safety-tags,
.use-case-list {
  flex-wrap: wrap;
}

.assistance-title {
  color: var(--wot-color-content, var(--uni-text-color));
  font-weight: 700;
}

.assistance-subtitle,
.snapshot-grid text,
.limitations text,
.command-boundary {
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-size: 24rpx;
}

.provider-notice,
.assistance-error {
  padding: 18rpx;
  border-radius: 14rpx;
  font-size: 24rpx;
}

.provider-notice {
  background: rgb(245 158 11 / 12%);
  color: var(--wot-color-warning, var(--uni-color-warning));
}

.assistance-error {
  background: rgb(239 68 68 / 10%);
  color: var(--wot-color-danger, var(--uni-color-error));
}

.snapshot-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20rpx;
}

.snapshot-grid > view,
.limitations,
.limitations > view {
  display: grid;
  gap: 8rpx;
}

.limitations {
  gap: 14rpx;
}

.command-boundary {
  font-weight: 700;
}
</style>
