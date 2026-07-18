<script lang="ts" setup>
import type {
  ApprovalMessageItem,
  ApprovalMessagePage,
  CollaborationOptions,
  MessageReceipt,
  StartedInstanceItem,
  StartedInstancePage,
} from '#/api/approval';

import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';
import {
  ElBadge,
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElSelect,
  ElSkeleton,
  ElSwitch,
  ElTabPane,
  ElTabs,
  ElTag,
} from 'element-plus';

import {
  copyInstance,
  findCollaborationOptions,
  findMessageReceipts,
  findMessages,
  findStartedInstances,
  findUnreadMessageCount,
  markAllMessagesRead,
  markMessageRead,
  urgeInstance,
} from '#/api/approval';

const router = useRouter();
type ActiveTab = 'collaboration' | 'messages';
type TagType = 'danger' | 'info' | 'primary' | 'success' | 'warning';

const activeTab = ref<ActiveTab>('messages');
const unreadCount = ref(0);
const messageLoading = ref(false);
const messageUnreadOnly = ref(false);
const messageCurrentPage = ref(1);
const messagePageSize = 20;
const messagePage = ref<ApprovalMessagePage>(emptyMessagePage());

const startedLoading = ref(false);
const startedCurrentPage = ref(1);
const startedPageSize = 10;
const startedPage = ref<StartedInstancePage>(emptyStartedPage());

const dialogOpen = ref(false);
const dialogLoading = ref(false);
const actionLoading = ref(false);
const selectedInstance = ref<StartedInstanceItem>();
const collaborationOptions = ref<CollaborationOptions>();
const receipts = ref<MessageReceipt[]>([]);
const selectedRecipients = ref<string[]>([]);
const collaborationComment = ref('');

const messageOffset = computed(
  () => (messageCurrentPage.value - 1) * messagePageSize,
);
const startedOffset = computed(
  () => (startedCurrentPage.value - 1) * startedPageSize,
);

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

function emptyMessagePage(): ApprovalMessagePage {
  return {
    hasMore: false,
    items: [],
    limit: messagePageSize,
    offset: 0,
    total: 0,
  };
}

function emptyStartedPage(): StartedInstancePage {
  return {
    hasMore: false,
    items: [],
    limit: startedPageSize,
    offset: 0,
    total: 0,
  };
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试';
}

function formatMoney(value: number) {
  return moneyFormatter.format(value);
}

function formatDate(value?: string) {
  return value ? dateFormatter.format(new Date(value)) : '未读';
}

function messageTypeLabel(type: ApprovalMessageItem['messageType']) {
  if (type === 'URGE') return '催办';
  if (type === 'MENTION') return '@提及';
  return '抄送';
}

function messageTypeTag(type: ApprovalMessageItem['messageType']): TagType {
  if (type === 'URGE') return 'warning';
  if (type === 'MENTION') return 'success';
  return 'primary';
}

function instanceStatusLabel(status: StartedInstanceItem['status']) {
  const labels = {
    COMPLETED: '已完成',
    REJECTED: '已驳回',
    RUNNING: '审批中',
    WITHDRAWN: '已撤回',
  };
  return labels[status];
}

async function loadUnreadCount() {
  try {
    unreadCount.value = (await findUnreadMessageCount()).unread;
  } catch {
    unreadCount.value = 0;
  }
}

async function loadMessages() {
  messageLoading.value = true;
  try {
    messagePage.value = await findMessages(
      messageUnreadOnly.value,
      messagePageSize,
      messageOffset.value,
    );
  } catch (error) {
    messagePage.value = emptyMessagePage();
    ElMessage.error(errorMessage(error));
  } finally {
    messageLoading.value = false;
  }
}

async function loadStarted() {
  startedLoading.value = true;
  try {
    startedPage.value = await findStartedInstances({
      limit: startedPageSize,
      offset: startedOffset.value,
    });
  } catch (error) {
    startedPage.value = emptyStartedPage();
    ElMessage.error(errorMessage(error));
  } finally {
    startedLoading.value = false;
  }
}

async function markRead(item: ApprovalMessageItem) {
  if (item.read) return;
  await markMessageRead(item.messageId);
  item.read = true;
  item.readAt = new Date().toISOString();
  unreadCount.value = Math.max(0, unreadCount.value - 1);
}

async function openMessage(item: ApprovalMessageItem) {
  try {
    await markRead(item);
    await router.push({
      path: '/approval/discussion/detail',
      query: {
        amount: String(item.amount),
        businessKey: item.businessKey,
        commentId: item.metadata.commentId || undefined,
        instanceId: item.instanceId,
        purchaseOrderReference: item.purchaseOrderReference,
        supplier: item.supplier,
      },
    });
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function readAllMessages() {
  try {
    const result = await markAllMessagesRead();
    ElMessage.success(`已将 ${result.updatedMessages} 条消息标记为已读`);
    await Promise.all([loadMessages(), loadUnreadCount()]);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function openCollaboration(item: StartedInstanceItem) {
  selectedInstance.value = item;
  dialogOpen.value = true;
  dialogLoading.value = true;
  selectedRecipients.value = [];
  collaborationComment.value = '';
  collaborationOptions.value = undefined;
  receipts.value = [];
  try {
    const [options, receiptItems] = await Promise.all([
      findCollaborationOptions(item.instanceId),
      findMessageReceipts(item.instanceId),
    ]);
    collaborationOptions.value = options;
    receipts.value = receiptItems;
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    dialogLoading.value = false;
  }
}

async function refreshCollaboration(item: StartedInstanceItem) {
  const [options, receiptItems] = await Promise.all([
    findCollaborationOptions(item.instanceId),
    findMessageReceipts(item.instanceId),
  ]);
  collaborationOptions.value = options;
  receipts.value = receiptItems;
  await loadStarted();
}

async function submitUrge() {
  const item = selectedInstance.value;
  if (!item || !collaborationOptions.value?.canUrge) return;
  try {
    await ElMessageBox.confirm(
      '催办消息将发送给当前待处理人，10 分钟内不会重复发送。',
      '确认催办',
      {
        cancelButtonText: '取消',
        confirmButtonText: '发送催办',
        type: 'warning',
      },
    );
  } catch {
    return;
  }
  actionLoading.value = true;
  try {
    const result = await urgeInstance(item.instanceId, collaborationComment.value);
    ElMessage.success(`已向 ${result.createdMessages} 位待处理人发送催办`);
    await refreshCollaboration(item);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    actionLoading.value = false;
  }
}

async function submitCopy() {
  const item = selectedInstance.value;
  if (!item) return;
  if (selectedRecipients.value.length === 0) {
    ElMessage.warning('请选择抄送人员');
    return;
  }
  actionLoading.value = true;
  try {
    const result = await copyInstance(
      item.instanceId,
      selectedRecipients.value,
      collaborationComment.value,
    );
    ElMessage.success(`已抄送 ${result.createdMessages} 人`);
    selectedRecipients.value = [];
    collaborationComment.value = '';
    await refreshCollaboration(item);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    actionLoading.value = false;
  }
}

watch(messageUnreadOnly, async () => {
  messageCurrentPage.value = 1;
  await loadMessages();
});

watch(activeTab, async (tab) => {
  if (tab === 'messages') {
    await Promise.all([loadMessages(), loadUnreadCount()]);
  } else {
    await loadStarted();
  }
}, { immediate: true });
</script>

<template>
  <Page title="消息与协作">
    <ElCard shadow="never">
      <ElTabs v-model="activeTab">
        <ElTabPane name="messages">
          <template #label>
            <ElBadge :value="unreadCount" :hidden="unreadCount === 0">
              <span>我的消息</span>
            </ElBadge>
          </template>
        </ElTabPane>
        <ElTabPane label="我发起的协作" name="collaboration" />
      </ElTabs>

      <template v-if="activeTab === 'messages'">
        <div class="toolbar">
          <div class="switch-row">
            <span>只看未读</span>
            <ElSwitch v-model="messageUnreadOnly" />
          </div>
          <ElButton :disabled="unreadCount === 0" @click="readAllMessages">
            全部已读
          </ElButton>
        </div>
        <ElSkeleton v-if="messageLoading" :rows="5" animated />
        <ElEmpty
          v-else-if="messagePage.items.length === 0"
          description="暂无审批消息"
        />
        <div v-else class="message-list">
          <article
            v-for="item in messagePage.items"
            :key="item.messageId"
            class="message-item"
            :class="{ 'message-item--unread': !item.read }"
            @click="openMessage(item)"
          >
            <div class="item-main">
              <div class="title-row">
                <strong>{{ item.title }}</strong>
                <ElTag :type="messageTypeTag(item.messageType)" effect="plain">
                  {{ messageTypeLabel(item.messageType) }}
                </ElTag>
                <ElTag v-if="!item.read" effect="dark" size="small">未读</ElTag>
              </div>
              <p>{{ item.body }}</p>
              <span>
                {{ item.businessKey }} · {{ item.supplier }} · 发送人 {{ item.senderId }}
              </span>
            </div>
            <div class="item-side">
              <strong>{{ formatMoney(item.amount) }}</strong>
              <span>{{ formatDate(item.createdAt) }}</span>
              <ElButton link type="primary">打开讨论</ElButton>
            </div>
          </article>
        </div>
        <div v-if="messagePage.total > messagePageSize" class="pagination-row">
          <ElPagination
            v-model:current-page="messageCurrentPage"
            :page-size="messagePageSize"
            :total="messagePage.total"
            background
            layout="prev, pager, next, total"
            @current-change="loadMessages"
          />
        </div>
      </template>

      <template v-else>
        <ElSkeleton v-if="startedLoading" :rows="5" animated />
        <ElEmpty
          v-else-if="startedPage.items.length === 0"
          description="暂无我发起的审批"
        />
        <div v-else class="message-list">
          <article
            v-for="item in startedPage.items"
            :key="item.instanceId"
            class="message-item"
          >
            <div class="item-main">
              <div class="title-row">
                <strong>{{ item.supplier }}采购付款</strong>
                <ElTag effect="plain">{{ instanceStatusLabel(item.status) }}</ElTag>
              </div>
              <span>{{ item.businessKey }} · {{ item.purchaseOrderReference }}</span>
              <span>
                消息 {{ item.messageCount }} 条 · 已读 {{ item.readCount }} 条 ·
                当前环节 {{ item.currentTaskName || '流程已结束' }}
              </span>
            </div>
            <div class="item-side">
              <strong>{{ formatMoney(item.amount) }}</strong>
              <ElButton type="primary" @click="openCollaboration(item)">
                催办 / 抄送 / 回执
              </ElButton>
            </div>
          </article>
        </div>
        <div v-if="startedPage.total > startedPageSize" class="pagination-row">
          <ElPagination
            v-model:current-page="startedCurrentPage"
            :page-size="startedPageSize"
            :total="startedPage.total"
            background
            layout="prev, pager, next, total"
            @current-change="loadStarted"
          />
        </div>
      </template>
    </ElCard>

    <ElDialog v-model="dialogOpen" title="审批协作" width="680px">
      <ElSkeleton v-if="dialogLoading" :rows="7" animated />
      <div v-else-if="selectedInstance" class="dialog-content">
        <div class="summary-card">
          <strong>{{ selectedInstance.supplier }}采购付款</strong>
          <span>
            {{ selectedInstance.businessKey }} ·
            {{ formatMoney(selectedInstance.amount) }} ·
            {{ selectedInstance.currentTaskName || '流程已结束' }}
          </span>
        </div>
        <ElSelect
          v-model="selectedRecipients"
          collapse-tags
          filterable
          multiple
          placeholder="从审批身份快照中选择抄送人员"
        >
          <ElOption
            v-for="candidate in collaborationOptions?.copyCandidates || []"
            :key="candidate.userId"
            :label="candidate.displayName"
            :value="candidate.userId"
          />
        </ElSelect>
        <ElInput
          v-model="collaborationComment"
          :maxlength="2000"
          :rows="3"
          placeholder="催办或抄送说明（可选）"
          show-word-limit
          type="textarea"
        />
        <div class="dialog-actions">
          <ElButton
            :disabled="!collaborationOptions?.canUrge"
            :loading="actionLoading"
            type="warning"
            plain
            @click="submitUrge"
          >
            催办当前处理人
          </ElButton>
          <ElButton
            :disabled="selectedRecipients.length === 0"
            :loading="actionLoading"
            type="primary"
            @click="submitCopy"
          >
            发送抄送
          </ElButton>
        </div>
        <section>
          <h3>已读回执</h3>
          <ElEmpty v-if="receipts.length === 0" description="暂无消息回执" :image-size="64" />
          <div v-else class="receipt-list">
            <div v-for="receipt in receipts" :key="receipt.messageId" class="receipt-item">
              <span>
                {{ receipt.recipientId }} ·
                {{ messageTypeLabel(receipt.messageType) }} ·
                {{ formatDate(receipt.sentAt) }}
              </span>
              <ElTag :type="receipt.read ? 'success' : 'info'" effect="plain">
                {{ receipt.read ? `已读 ${formatDate(receipt.readAt)}` : '未读' }}
              </ElTag>
            </div>
          </div>
        </section>
      </div>
    </ElDialog>
  </Page>
</template>

<style scoped>
.toolbar,
.switch-row,
.message-item,
.title-row,
.pagination-row,
.dialog-actions,
.receipt-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.toolbar,
.message-item,
.pagination-row,
.receipt-item {
  justify-content: space-between;
}

.toolbar {
  margin: 16px 0;
}

.message-list,
.item-main,
.item-side,
.dialog-content,
.receipt-list {
  display: grid;
  gap: 10px;
}

.message-item {
  padding: 18px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}

.message-item--unread {
  padding-right: 12px;
  padding-left: 12px;
  border-radius: var(--el-border-radius-base);
  background: var(--el-color-primary-light-9);
}

.message-item:last-child {
  border-bottom: 0;
}

.item-main p {
  margin: 0;
  line-height: 1.6;
}

.item-main span,
.item-side span,
.summary-card span {
  color: var(--el-text-color-secondary);
}

.item-side {
  justify-items: end;
  white-space: nowrap;
}

.title-row {
  flex-wrap: wrap;
}

.pagination-row {
  padding-top: 18px;
}

.dialog-content {
  gap: 16px;
}

.summary-card {
  display: grid;
  gap: 6px;
  padding: 14px;
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-light);
}

.dialog-actions {
  justify-content: flex-end;
}

.dialog-content h3 {
  margin-bottom: 12px;
  font-size: 16px;
}

.receipt-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
</style>
