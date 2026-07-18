<script lang="ts" setup>
import type {
  ApprovalMessageItem,
  ApprovalMessagePage,
  CollaborationOptions,
  MessageReceipt,
  StartedInstanceItem,
  StartedInstancePage,
} from '#/api/approval';

import { computed, onMounted, ref, watch } from 'vue';

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

type ActiveTab = 'collaboration' | 'messages';

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
  return type === 'URGE' ? '催办' : '抄送';
}

function messageTypeTag(type: ApprovalMessageItem['messageType']) {
  return type === 'URGE' ? 'warning' : 'primary';
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

async function readMessage(item: ApprovalMessageItem) {
  if (item.read) {
    return;
  }
  try {
    await markMessageRead(item.messageId);
    item.read = true;
    item.readAt = new Date().toISOString();
    unreadCount.value = Math.max(0, unreadCount.value - 1);
    if (messageUnreadOnly.value) {
      await loadMessages();
    }
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

async function submitUrge() {
  const item = selectedInstance.value;
  if (!item || !collaborationOptions.value?.canUrge) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      '催办消息将发送给当前待处理人，10 分钟内不会重复发送。',
      '确认催办',
      {
        confirmButtonText: '发送催办',
        cancelButtonText: '取消',
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
  if (!item) {
    return;
  }
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
    await refreshCollaboration(item);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    actionLoading.value = false;
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

watch(messageUnreadOnly, async () => {
  messageCurrentPage.value = 1;
  await loadMessages();
});

watch(activeTab, async (tab) => {
  if (tab === 'messages') {
    await loadMessages();
  } else {
    await loadStarted();
  }
});

onMounted(async () => {
  await Promise.all([loadMessages(), loadStarted(), loadUnreadCount()]);
});
</script>

<template>
  <Page title="消息与协作" description="处理催办、抄送、未读消息和已读回执。">
    <div class="page-content">
      <section class="summary-grid">
        <ElBadge :value="unreadCount" :hidden="unreadCount === 0">
          <ElCard class="summary-card" shadow="never" @click="activeTab = 'messages'">
            <span>未读消息</span>
            <strong>{{ unreadCount }}</strong>
            <small>催办与抄送</small>
          </ElCard>
        </ElBadge>
        <ElCard class="summary-card" shadow="never" @click="activeTab = 'collaboration'">
          <span>我发起的</span>
          <strong>{{ startedPage.total }}</strong>
          <small>催办、抄送与回执</small>
        </ElCard>
      </section>

      <ElCard shadow="never">
        <template #header>
          <div class="section-header">
            <div>
              <strong>协作中心</strong>
              <span>用户消息与业务回调相互独立</span>
            </div>
            <ElButton
              :loading="activeTab === 'messages' ? messageLoading : startedLoading"
              @click="activeTab === 'messages' ? loadMessages() : loadStarted()"
            >
              刷新
            </ElButton>
          </div>
        </template>

        <ElTabs v-model="activeTab">
          <ElTabPane :label="`消息中心 ${unreadCount ? `(${unreadCount})` : ''}`" name="messages" />
          <ElTabPane label="我发起的协作" name="collaboration" />
        </ElTabs>

        <template v-if="activeTab === 'messages'">
          <div class="toolbar">
            <div class="switch-row">
              <span>只看未读</span>
              <ElSwitch v-model="messageUnreadOnly" />
            </div>
            <ElButton :disabled="unreadCount === 0" text type="primary" @click="readAllMessages">
              全部已读
            </ElButton>
          </div>

          <ElSkeleton v-if="messageLoading" :rows="6" animated />
          <ElEmpty v-else-if="messagePage.items.length === 0" description="暂无消息" />
          <div v-else class="message-list">
            <article
              v-for="item in messagePage.items"
              :key="item.messageId"
              :class="['message-item', { 'message-item--unread': !item.read }]"
              @click="readMessage(item)"
            >
              <div class="message-main">
                <div class="title-row">
                  <strong>{{ item.title }}</strong>
                  <ElTag :type="messageTypeTag(item.messageType)" effect="plain">
                    {{ messageTypeLabel(item.messageType) }}
                  </ElTag>
                  <ElTag v-if="!item.read" type="danger" effect="light">未读</ElTag>
                </div>
                <p>{{ item.body }}</p>
                <span>
                  {{ item.businessKey }} · {{ item.purchaseOrderReference }} · 发送人 {{ item.senderId }}
                </span>
              </div>
              <div class="message-meta">
                <strong>{{ formatMoney(item.amount) }}</strong>
                <span>{{ formatDate(item.createdAt) }}</span>
                <span>{{ item.read ? `已读 ${formatDate(item.readAt)}` : '点击标记已读' }}</span>
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
          <ElSkeleton v-if="startedLoading" :rows="6" animated />
          <ElEmpty v-else-if="startedPage.items.length === 0" description="暂无发起记录" />
          <div v-else class="message-list">
            <article v-for="item in startedPage.items" :key="item.instanceId" class="message-item">
              <div class="message-main">
                <div class="title-row">
                  <strong>{{ item.supplier }}采购付款</strong>
                  <ElTag effect="plain">{{ instanceStatusLabel(item.status) }}</ElTag>
                </div>
                <p>{{ item.businessKey }} · {{ item.purchaseOrderReference }}</p>
                <span>
                  当前环节 {{ item.currentTaskName || '流程已结束' }} · 消息回执
                  {{ item.readCount }}/{{ item.messageCount }}
                </span>
              </div>
              <div class="message-meta">
                <strong>{{ formatMoney(item.amount) }}</strong>
                <span>{{ formatDate(item.updatedAt) }}</span>
                <ElButton type="primary" plain @click="openCollaboration(item)">
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
    </div>

    <ElDialog v-model="dialogOpen" title="审批协作" width="640px">
      <ElSkeleton v-if="dialogLoading" :rows="8" animated />
      <div v-else-if="selectedInstance" class="dialog-content">
        <ElCard shadow="never">
          <div class="dialog-summary">
            <strong>{{ selectedInstance.supplier }}采购付款</strong>
            <span>{{ selectedInstance.businessKey }} · {{ formatMoney(selectedInstance.amount) }}</span>
            <span>
              当前待处理人：
              {{ collaborationOptions?.activeAssignees.map((item) => item.displayName).join('、') || '无' }}
            </span>
          </div>
        </ElCard>

        <section>
          <h3>抄送人员</h3>
          <ElSelect
            v-model="selectedRecipients"
            multiple
            filterable
            collapse-tags
            placeholder="从审批身份快照中选择"
          >
            <ElOption
              v-for="candidate in collaborationOptions?.copyCandidates || []"
              :key="candidate.userId"
              :label="candidate.displayName"
              :value="candidate.userId"
            />
          </ElSelect>
        </section>

        <section>
          <h3>消息说明</h3>
          <ElInput
            v-model="collaborationComment"
            type="textarea"
            :rows="3"
            :maxlength="2000"
            show-word-limit
            placeholder="催办说明或抄送备注（可选）"
          />
        </section>

        <section>
          <h3>已读回执</h3>
          <ElEmpty v-if="receipts.length === 0" description="尚未发送催办或抄送消息" :image-size="64" />
          <div v-else class="receipt-list">
            <div v-for="receipt in receipts" :key="receipt.messageId" class="receipt-item">
              <span>{{ receipt.recipientId }}</span>
              <ElTag :type="receipt.read ? 'success' : 'info'" effect="plain">
                {{ receipt.read ? `已读 ${formatDate(receipt.readAt)}` : '未读' }}
              </ElTag>
              <small>{{ receipt.messageType === 'URGE' ? '催办' : '抄送' }} · {{ formatDate(receipt.sentAt) }}</small>
            </div>
          </div>
        </section>
      </div>

      <template #footer>
        <div class="dialog-actions">
          <ElButton @click="dialogOpen = false">关闭</ElButton>
          <ElButton
            :disabled="!collaborationOptions?.canUrge"
            :loading="actionLoading"
            type="warning"
            plain
            @click="submitUrge"
          >
            催办当前处理人
          </ElButton>
          <ElButton :loading="actionLoading" type="primary" @click="submitCopy">
            发送抄送
          </ElButton>
        </div>
      </template>
    </ElDialog>
  </Page>
</template>

<style scoped>
.page-content,
.dialog-content,
.message-main,
.message-meta,
.dialog-summary,
.receipt-list {
  display: grid;
  gap: 12px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(220px, 320px));
  gap: 16px;
}

.summary-card {
  cursor: pointer;
}

.summary-card :deep(.el-card__body) {
  display: grid;
  gap: 6px;
}

.summary-card span,
.summary-card small,
.section-header span,
.message-main span,
.message-main p,
.message-meta span,
.dialog-summary span,
.receipt-item small {
  color: var(--el-text-color-secondary);
}

.summary-card strong {
  font-size: 30px;
}

.section-header,
.toolbar,
.message-item,
.title-row,
.switch-row,
.receipt-item,
.dialog-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.section-header,
.toolbar,
.message-item,
.receipt-item,
.dialog-actions {
  justify-content: space-between;
}

.message-list {
  display: grid;
}

.message-item {
  padding: 18px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.message-item--unread {
  padding-right: 12px;
  padding-left: 12px;
  border-radius: var(--el-border-radius-base);
  background: var(--el-color-primary-light-9);
}

.message-main {
  min-width: 0;
}

.message-main p {
  margin: 0;
}

.message-meta {
  justify-items: end;
  white-space: nowrap;
}

.title-row {
  flex-wrap: wrap;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  padding-top: 18px;
}

.dialog-content section h3 {
  margin: 0 0 10px;
  font-size: 15px;
}

.dialog-content :deep(.el-select) {
  width: 100%;
}

.receipt-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

@media (max-width: 720px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }

  .message-item,
  .section-header,
  .toolbar,
  .dialog-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .message-meta {
    justify-items: start;
  }
}
</style>
