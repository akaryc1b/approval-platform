<script lang="ts" setup>
import type {
  ApprovalTimeline,
  PendingTaskPage,
  ProcessedTaskPage,
  StartedInstancePage,
} from '#/api/approval';
import type { CopiedInstancePage } from '#/api/approval/comments';

import { computed, ref, watch } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElDrawer,
  ElEmpty,
  ElInput,
  ElPagination,
  ElSkeleton,
  ElTabPane,
  ElTabs,
  ElTag,
  ElTimeline,
  ElTimelineItem,
} from 'element-plus';

import {
  findApprovalTimeline,
  findPendingTasks,
  findProcessedTasks,
  findStartedInstances,
  markMessageRead,
} from '#/api/approval';
import { findCopiedInstances } from '#/api/approval/comments';
import ApprovalCommentThread from '#/components/approval/ApprovalCommentThread.vue';

type DiscussionTab = 'copied' | 'pending' | 'processed' | 'started';
type TagType = 'danger' | 'info' | 'primary' | 'success' | 'warning';

interface DiscussionItem {
  amount: number;
  businessKey: string;
  copyMessageId?: string;
  copyRead?: boolean;
  instanceId: string;
  key: string;
  meta: string;
  purchaseOrderReference: string;
  status: string;
  supplier: string;
  tagType: TagType;
}

const pageSize = 20;
const activeTab = ref<DiscussionTab>('pending');
const currentPage = ref(1);
const keyword = ref('');
const loading = ref(false);
const errorText = ref('');
const pendingPage = ref<PendingTaskPage>(emptyPage());
const processedPage = ref<ProcessedTaskPage>(emptyPage());
const startedPage = ref<StartedInstancePage>(emptyPage());
const copiedPage = ref<CopiedInstancePage>(emptyPage());

const drawerOpen = ref(false);
const drawerLoading = ref(false);
const selectedItem = ref<DiscussionItem>();
const timeline = ref<ApprovalTimeline>();

const offset = computed(() => (currentPage.value - 1) * pageSize);
const activeTotal = computed(() => {
  if (activeTab.value === 'processed') return processedPage.value.total;
  if (activeTab.value === 'started') return startedPage.value.total;
  if (activeTab.value === 'copied') return copiedPage.value.total;
  return pendingPage.value.total;
});
const items = computed<DiscussionItem[]>(() => {
  if (activeTab.value === 'processed') {
    return processedPage.value.items.map((item) => ({
      amount: item.amount,
      businessKey: item.businessKey,
      instanceId: item.instanceId,
      key: item.taskId,
      meta: `已处理 ${formatDate(item.completedAt)} · ${item.taskName}`,
      purchaseOrderReference: item.purchaseOrderReference,
      status: '我已处理',
      supplier: item.supplier,
      tagType: 'success',
    }));
  }
  if (activeTab.value === 'started') {
    return startedPage.value.items.map((item) => ({
      amount: item.amount,
      businessKey: item.businessKey,
      instanceId: item.instanceId,
      key: item.instanceId,
      meta: `当前环节 ${item.currentTaskName || '流程已结束'} · ${item.messageCount} 条消息`,
      purchaseOrderReference: item.purchaseOrderReference,
      status: instanceStatusLabel(item.status),
      supplier: item.supplier,
      tagType: instanceStatusType(item.status),
    }));
  }
  if (activeTab.value === 'copied') {
    return copiedPage.value.items.map((item) => ({
      amount: item.amount,
      businessKey: item.businessKey,
      copyMessageId: item.copyMessageId,
      copyRead: item.read,
      instanceId: item.instanceId,
      key: item.copyMessageId,
      meta: `抄送人 ${item.copiedBy} · ${item.commentCount} 条评论 · ${formatDate(item.copiedAt)}`,
      purchaseOrderReference: item.purchaseOrderReference,
      status: item.read ? '已读抄送' : '未读抄送',
      supplier: item.supplier,
      tagType: item.read ? 'info' : 'warning',
    }));
  }
  return pendingPage.value.items.map((item) => ({
    amount: item.amount,
    businessKey: item.businessKey,
    instanceId: item.instanceId,
    key: item.taskId,
    meta: `当前环节 ${item.taskName} · ${formatDate(item.taskCreatedAt)}`,
    purchaseOrderReference: item.purchaseOrderReference,
    status: '待我处理',
    supplier: item.supplier,
    tagType: 'primary',
  }));
});

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

function emptyPage() {
  return { hasMore: false, items: [], limit: pageSize, offset: 0, total: 0 };
}

function formatMoney(value: number) {
  return moneyFormatter.format(value);
}

function formatDate(value: string) {
  return dateFormatter.format(new Date(value));
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '审批讨论加载失败';
}

function instanceStatusLabel(status: string) {
  const labels: Record<string, string> = {
    COMPLETED: '已完成',
    REJECTED: '已驳回',
    RUNNING: '审批中',
    WITHDRAWN: '已撤回',
  };
  return labels[status] || status;
}

function instanceStatusType(status: string): TagType {
  if (status === 'COMPLETED') return 'success';
  if (status === 'RUNNING') return 'primary';
  return 'info';
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

async function loadPage() {
  loading.value = true;
  errorText.value = '';
  const parameters = {
    keyword: keyword.value,
    limit: pageSize,
    offset: offset.value,
  };
  try {
    if (activeTab.value === 'processed') {
      processedPage.value = await findProcessedTasks(parameters);
    } else if (activeTab.value === 'started') {
      startedPage.value = await findStartedInstances(parameters);
    } else if (activeTab.value === 'copied') {
      copiedPage.value = await findCopiedInstances(parameters);
    } else {
      pendingPage.value = await findPendingTasks(parameters);
    }
  } catch (error) {
    errorText.value = errorMessage(error);
  } finally {
    loading.value = false;
  }
}

async function searchItems() {
  currentPage.value = 1;
  await loadPage();
}

async function changePage(page: number) {
  currentPage.value = page;
  await loadPage();
}

async function openDiscussion(item: DiscussionItem) {
  selectedItem.value = item;
  drawerOpen.value = true;
  drawerLoading.value = true;
  timeline.value = undefined;
  try {
    if (item.copyMessageId && !item.copyRead) {
      await markMessageRead(item.copyMessageId);
      item.copyRead = true;
      item.status = '已读抄送';
      item.tagType = 'info';
    }
    timeline.value = await findApprovalTimeline(item.instanceId);
  } catch (error) {
    errorText.value = errorMessage(error);
  } finally {
    drawerLoading.value = false;
  }
}

watch(activeTab, async () => {
  currentPage.value = 1;
  keyword.value = '';
  await loadPage();
}, { immediate: true });
</script>

<template>
  <Page title="审批讨论">
    <ElCard shadow="never">
      <template #header>
        <div class="section-header">
          <div>
            <strong>参与审批的讨论事项</strong>
            <span>待办、已处理、我发起和抄送审批共用评论线程</span>
          </div>
          <ElButton :loading="loading" @click="loadPage">刷新</ElButton>
        </div>
      </template>

      <ElTabs v-model="activeTab">
        <ElTabPane label="待我处理" name="pending" />
        <ElTabPane label="我已处理" name="processed" />
        <ElTabPane label="我发起的" name="started" />
        <ElTabPane label="抄送我的" name="copied" />
      </ElTabs>

      <div class="search-row">
        <ElInput
          v-model="keyword"
          clearable
          placeholder="搜索业务编号、供应商或采购订单"
          @keyup.enter="searchItems"
        />
        <ElButton type="primary" @click="searchItems">搜索</ElButton>
      </div>

      <ElAlert
        v-if="errorText"
        :closable="false"
        :title="errorText"
        type="error"
      />
      <ElSkeleton v-else-if="loading" :rows="5" animated />
      <ElEmpty v-else-if="items.length === 0" description="暂无可讨论的审批" />
      <div v-else class="discussion-list">
        <article v-for="item in items" :key="item.key" class="discussion-item">
          <div class="discussion-main">
            <div class="title-row">
              <strong>{{ item.supplier }}采购付款</strong>
              <ElTag :type="item.tagType" effect="plain">{{ item.status }}</ElTag>
            </div>
            <span>{{ item.businessKey }} · {{ item.purchaseOrderReference }}</span>
            <span>{{ item.meta }}</span>
          </div>
          <div class="discussion-actions">
            <strong>{{ formatMoney(item.amount) }}</strong>
            <ElButton type="primary" @click="openDiscussion(item)">查看讨论</ElButton>
          </div>
        </article>
      </div>

      <div v-if="activeTotal > pageSize" class="pagination-row">
        <ElPagination
          :current-page="currentPage"
          :page-size="pageSize"
          :total="activeTotal"
          background
          layout="prev, pager, next, total"
          @current-change="changePage"
        />
      </div>
    </ElCard>

    <ElDrawer v-model="drawerOpen" size="720px" title="审批讨论详情">
      <ElSkeleton v-if="drawerLoading" :rows="8" animated />
      <div v-else-if="selectedItem" class="drawer-content">
        <ElDescriptions :column="2" border title="审批信息">
          <ElDescriptionsItem label="业务编号">{{ selectedItem.businessKey }}</ElDescriptionsItem>
          <ElDescriptionsItem label="状态">{{ selectedItem.status }}</ElDescriptionsItem>
          <ElDescriptionsItem label="供应商">{{ selectedItem.supplier }}</ElDescriptionsItem>
          <ElDescriptionsItem label="金额">{{ formatMoney(selectedItem.amount) }}</ElDescriptionsItem>
          <ElDescriptionsItem :span="2" label="采购订单">
            {{ selectedItem.purchaseOrderReference }}
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

        <ApprovalCommentThread :instance-id="selectedItem.instanceId" />
      </div>
    </ElDrawer>
  </Page>
</template>

<style scoped>
.section-header,
.search-row,
.discussion-item,
.title-row,
.discussion-actions,
.pagination-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.section-header,
.discussion-item,
.pagination-row {
  justify-content: space-between;
}

.section-header > div,
.discussion-main,
.discussion-actions,
.drawer-content {
  display: grid;
  gap: 8px;
}

.section-header span,
.discussion-main span,
.timeline-meta {
  color: var(--el-text-color-secondary);
}

.search-row {
  margin: 16px 0;
}

.search-row :deep(.el-input) {
  max-width: 520px;
}

.discussion-list {
  display: grid;
}

.discussion-item {
  padding: 18px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.discussion-item:last-child {
  border-bottom: 0;
}

.title-row {
  flex-wrap: wrap;
}

.discussion-actions {
  justify-items: end;
  white-space: nowrap;
}

.pagination-row {
  padding-top: 18px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.drawer-content {
  gap: 24px;
}

.drawer-content h3 {
  margin-bottom: 16px;
  font-size: 16px;
}

.timeline-meta {
  margin-top: 4px;
  font-size: 13px;
}
</style>
