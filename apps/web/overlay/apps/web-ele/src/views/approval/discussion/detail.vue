<script lang="ts" setup>
import type { ApprovalTimeline } from '#/api/approval';

import { computed, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElDescriptions,
  ElDescriptionsItem,
  ElEmpty,
  ElSkeleton,
  ElTimeline,
  ElTimelineItem,
} from 'element-plus';

import { findApprovalTimeline } from '#/api/approval';
import ApprovalCommentThread from '#/components/approval/ApprovalCommentThread.vue';

const route = useRoute();
const loading = ref(false);
const errorText = ref('');
const timeline = ref<ApprovalTimeline>();

const instanceId = computed(() => String(route.query.instanceId || ''));
const commentId = computed(() => String(route.query.commentId || '') || undefined);
const supplier = computed(() => String(route.query.supplier || '采购付款'));
const businessKey = computed(() => String(route.query.businessKey || ''));
const purchaseOrderReference = computed(
  () => String(route.query.purchaseOrderReference || ''),
);
const amount = computed(() => Number(route.query.amount || 0));

const moneyFormatter = new Intl.NumberFormat('zh-CN', {
  currency: 'CNY',
  style: 'currency',
});
const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  month: '2-digit',
  year: 'numeric',
});

function formatDate(value: string) {
  return dateFormatter.format(new Date(value));
}

function timelineTitle(action: string) {
  const labels: Record<string, string> = {
    INSTANCE_COMMENTED: '发表审批评论',
    INSTANCE_COPIED: '抄送审批',
    INSTANCE_STARTED: '发起审批',
    INSTANCE_URGED: '催办审批',
    INSTANCE_WITHDRAWN: '撤回审批',
    TASK_APPROVED: '同意审批',
    TASK_REJECTED: '驳回到发起人',
    TASK_RESUBMITTED: '重新提交',
    TASK_RETRIEVED: '拿回任务',
    TASK_TRANSFERRED: '转办任务',
  };
  return labels[action] || action;
}

async function loadTimeline() {
  if (!instanceId.value) {
    errorText.value = '缺少审批实例编号';
    return;
  }
  loading.value = true;
  errorText.value = '';
  try {
    timeline.value = await findApprovalTimeline(instanceId.value);
  } catch (error) {
    errorText.value = error instanceof Error ? error.message : '审批时间线加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(loadTimeline);
</script>

<template>
  <Page title="审批讨论详情">
    <ElAlert
      v-if="errorText"
      :closable="false"
      :title="errorText"
      type="error"
    />
    <ElSkeleton v-else-if="loading" :rows="8" animated />
    <div v-else-if="instanceId" class="detail-grid">
      <ElDescriptions :column="2" border title="审批信息">
        <ElDescriptionsItem label="业务编号">{{ businessKey || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="供应商">{{ supplier }}</ElDescriptionsItem>
        <ElDescriptionsItem label="金额">{{ moneyFormatter.format(amount) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="采购订单">
          {{ purchaseOrderReference || '-' }}
        </ElDescriptionsItem>
      </ElDescriptions>

      <section>
        <h3>审批时间线</h3>
        <ElTimeline v-if="timeline?.items.length">
          <ElTimelineItem
            v-for="item in timeline.items"
            :key="item.eventId"
            :timestamp="formatDate(item.occurredAt)"
          >
            <strong>{{ timelineTitle(item.action) }}</strong>
            <div class="timeline-meta">操作人：{{ item.operatorId }}</div>
            <div v-if="item.attributes.comment" class="timeline-meta">
              意见：{{ item.attributes.comment }}
            </div>
          </ElTimelineItem>
        </ElTimeline>
        <ElEmpty v-else description="暂无审批记录" :image-size="72" />
      </section>

      <ApprovalCommentThread
        :focus-comment-id="commentId"
        :instance-id="instanceId"
      />
    </div>
  </Page>
</template>

<style scoped>
.detail-grid {
  display: grid;
  gap: 24px;
}

.detail-grid h3 {
  margin: 0 0 16px;
  font-size: 16px;
}

.timeline-meta {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
