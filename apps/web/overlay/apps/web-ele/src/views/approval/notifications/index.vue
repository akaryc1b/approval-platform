<script lang="ts" setup>
import type {
  NotificationChannel,
  NotificationEventType,
  NotificationHistoryPage,
  NotificationIntent,
  NotificationPreferenceBundle,
} from '#/api/approval/notifications';

import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';
import {
  ElBadge,
  ElButton,
  ElCard,
  ElCheckbox,
  ElEmpty,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElPagination,
  ElSkeleton,
  ElSwitch,
  ElTabPane,
  ElTabs,
  ElTag,
  ElTimeSelect,
} from 'element-plus';

import {
  findNotificationAttempts,
  findNotificationHistory,
  findNotificationPreferences,
  findNotificationUnreadCount,
  markAllNotificationsRead,
  markNotificationRead,
  replayNotification,
  updateNotificationPreferences,
} from '#/api/approval/notifications';

type ActiveTab = 'history' | 'preferences';
type TagType = 'danger' | 'info' | 'primary' | 'success' | 'warning';

const router = useRouter();
const activeTab = ref<ActiveTab>('history');
const loading = ref(false);
const saving = ref(false);
const unreadOnly = ref(false);
const unreadCount = ref(0);
const currentPage = ref(1);
const pageSize = 20;
const history = ref<NotificationHistoryPage>(emptyHistory());
const preferences = ref<NotificationPreferenceBundle>();

const eventTypes: NotificationEventType[] = [
  'TASK_ASSIGNED',
  'AUTOMATIC_DELEGATION',
  'EMPLOYEE_HANDOVER',
  'TASK_COLLABORATION_ASSIGNED',
  'TASK_COLLABORATION_RESULT',
  'APPROVAL_COMPLETED',
  'APPROVAL_REJECTED',
  'COMMENT_MENTION',
];
const channels: NotificationChannel[] = ['IN_APP', 'CONNECTOR', 'EMAIL'];

const offset = computed(() => (currentPage.value - 1) * pageSize);

function emptyHistory(): NotificationHistoryPage {
  return { hasMore: false, items: [], limit: pageSize, offset: 0, total: 0 };
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试';
}

function eventLabel(value: NotificationEventType) {
  return {
    APPROVAL_COMPLETED: '审批完成',
    APPROVAL_REJECTED: '审批驳回',
    AUTOMATIC_DELEGATION: '自动代理',
    COMMENT_MENTION: '评论提及',
    EMPLOYEE_HANDOVER: '离职交接',
    TASK_ASSIGNED: '任务待办',
    TASK_COLLABORATION_ASSIGNED: '加签待办',
    TASK_COLLABORATION_RESULT: '加签结果',
  }[value];
}

function channelLabel(value: NotificationChannel) {
  return { CONNECTOR: '组织连接器', EMAIL: '邮件', IN_APP: '站内信' }[value];
}

function statusLabel(item: NotificationIntent) {
  return {
    DEAD_LETTER: '投递失败',
    DELIVERED: item.readAt ? '已读' : '未读',
    PENDING: '等待投递',
    PROCESSING: '投递中',
    RETRY: '等待重试',
  }[item.status];
}

function statusType(item: NotificationIntent): TagType {
  if (item.status === 'DEAD_LETTER') return 'danger';
  if (item.status === 'RETRY') return 'warning';
  if (item.status === 'DELIVERED' && item.readAt) return 'success';
  if (item.status === 'DELIVERED') return 'primary';
  return 'info';
}

function formatDate(value?: string) {
  if (!value) return '-';
  return new Intl.DateTimeFormat('zh-CN', {
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(new Date(value));
}

function preference(eventType: NotificationEventType, channel: NotificationChannel) {
  return preferences.value?.preferences.find(
    item => item.eventType === eventType && item.channel === channel,
  );
}

async function loadHistory() {
  loading.value = true;
  try {
    history.value = await findNotificationHistory(
      unreadOnly.value,
      pageSize,
      offset.value,
    );
    unreadCount.value = (await findNotificationUnreadCount()).unread;
  } catch (error) {
    history.value = emptyHistory();
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

async function loadPreferences() {
  loading.value = true;
  try {
    preferences.value = await findNotificationPreferences();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

async function switchTab(tab: ActiveTab) {
  activeTab.value = tab;
  if (tab === 'history') await loadHistory();
  else await loadPreferences();
}

async function toggleUnread() {
  currentPage.value = 1;
  await loadHistory();
}

async function readAll() {
  try {
    const result = await markAllNotificationsRead();
    ElMessage.success(`已将 ${result.updatedNotifications} 条通知标记为已读`);
    await loadHistory();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function openNotification(item: NotificationIntent) {
  try {
    if (!item.readAt) await markNotificationRead(item.intentId);
    const route = item.metadata.route;
    if (route) {
      await router.push(route);
    } else if (item.instanceId) {
      await router.push({
        path: '/approval/discussion/detail',
        query: { instanceId: item.instanceId },
      });
    } else if (item.taskId) {
      await router.push('/approval/workbench');
    } else {
      ElMessage.success('通知已标记为已读');
    }
    await loadHistory();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function replay(item: NotificationIntent) {
  try {
    await ElMessageBox.confirm('确认重新投递这条通知吗？', '重新投递', {
      cancelButtonText: '取消',
      confirmButtonText: '确认重投',
      type: 'warning',
    });
    await replayNotification(item.intentId);
    ElMessage.success('已重新进入投递队列');
    await loadHistory();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    ElMessage.error(errorMessage(error));
  }
}

async function showAttempts(item: NotificationIntent) {
  try {
    const attempts = await findNotificationAttempts(item.intentId);
    const body = attempts.length === 0
      ? '暂无投递记录'
      : attempts.map((attempt) => {
          const result = attempt.successful
            ? `成功${attempt.providerMessageId ? ` · ${attempt.providerMessageId}` : ''}`
            : `失败 · ${attempt.errorCode || '未知错误'}${attempt.retryable ? ' · 可重试' : ''}`;
          return `第 ${attempt.attemptNumber} 次：${result}`;
        }).join('\n');
    await ElMessageBox.alert(body, '投递记录', { confirmButtonText: '关闭' });
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function savePreferences() {
  const value = preferences.value;
  if (!value) return;
  if (value.quietHoursEnabled && (!value.quietHoursStart || !value.quietHoursEnd)) {
    ElMessage.warning('请设置完整的安静时间');
    return;
  }
  if (!value.timezone.trim()) {
    ElMessage.warning('请输入时区');
    return;
  }
  saving.value = true;
  try {
    preferences.value = await updateNotificationPreferences(value);
    ElMessage.success('通知偏好已保存');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}

onMounted(loadHistory);
</script>

<template>
  <Page title="通知中心">
    <ElCard shadow="never">
      <ElTabs :model-value="activeTab" @tab-change="value => switchTab(value as ActiveTab)">
        <ElTabPane name="history">
          <template #label>
            <ElBadge :value="unreadCount" :hidden="unreadCount === 0">
              <span>通知记录</span>
            </ElBadge>
          </template>
        </ElTabPane>
        <ElTabPane label="通知偏好" name="preferences" />
      </ElTabs>

      <template v-if="activeTab === 'history'">
        <div class="toolbar">
          <ElCheckbox v-model="unreadOnly" @change="toggleUnread">只看未读</ElCheckbox>
          <div class="toolbar-actions">
            <ElButton :loading="loading" @click="loadHistory">刷新</ElButton>
            <ElButton :disabled="unreadCount === 0" @click="readAll">全部已读</ElButton>
          </div>
        </div>
        <ElSkeleton v-if="loading" :rows="5" animated />
        <ElEmpty v-else-if="history.items.length === 0" description="暂无通知" />
        <div v-else class="notification-list">
          <article
            v-for="item in history.items"
            :key="item.intentId"
            class="notification-item"
            :class="{ unread: !item.readAt }"
          >
            <div class="notification-main" @click="openNotification(item)">
              <div class="title-row">
                <strong>{{ item.title }}</strong>
                <ElTag effect="plain">{{ eventLabel(item.eventType) }}</ElTag>
                <ElTag effect="plain">{{ channelLabel(item.channel) }}</ElTag>
                <ElTag :type="statusType(item)">{{ statusLabel(item) }}</ElTag>
                <ElTag v-if="item.urgent" effect="dark" type="danger">紧急</ElTag>
              </div>
              <p>{{ item.body }}</p>
              <span>
                {{ item.senderId }} · {{ formatDate(item.createdAt) }}
                <template v-if="item.status === 'RETRY'"> · 下次重试 {{ formatDate(item.nextAttemptAt) }}</template>
              </span>
              <span v-if="item.lastErrorMessage" class="error-text">
                {{ item.lastErrorCode }} · {{ item.lastErrorMessage }}
              </span>
            </div>
            <div class="notification-actions">
              <ElButton link @click="showAttempts(item)">投递记录</ElButton>
              <ElButton
                v-if="item.status === 'DEAD_LETTER'"
                link
                type="danger"
                @click="replay(item)"
              >
                重新投递
              </ElButton>
              <ElButton link type="primary" @click="openNotification(item)">打开</ElButton>
            </div>
          </article>
        </div>
        <div v-if="history.total > pageSize" class="pagination-row">
          <ElPagination
            v-model:current-page="currentPage"
            :page-size="pageSize"
            :total="history.total"
            background
            layout="prev, pager, next, total"
            @current-change="loadHistory"
          />
        </div>
      </template>

      <template v-else>
        <ElSkeleton v-if="loading" :rows="8" animated />
        <div v-else-if="preferences" class="preferences-layout">
          <section class="settings-card">
            <h3>接收设置</h3>
            <label>
              <span>时区</span>
              <ElInput v-model="preferences.timezone" placeholder="例如 Asia/Shanghai" />
            </label>
            <div class="switch-line">
              <div>
                <strong>安静时间</strong>
                <span>非紧急通知将在安静时间结束后投递</span>
              </div>
              <ElSwitch v-model="preferences.quietHoursEnabled" />
            </div>
            <div v-if="preferences.quietHoursEnabled" class="time-grid">
              <ElTimeSelect
                v-model="preferences.quietHoursStart"
                start="00:00"
                step="00:30"
                end="23:30"
                placeholder="开始时间"
              />
              <ElTimeSelect
                v-model="preferences.quietHoursEnd"
                start="00:00"
                step="00:30"
                end="23:30"
                placeholder="结束时间"
              />
            </div>
            <div class="switch-line">
              <div>
                <strong>紧急通知绕过安静时间</strong>
                <span>任务待办、代理和交接等紧急事项可立即投递</span>
              </div>
              <ElSwitch v-model="preferences.emergencyBypass" />
            </div>
            <div class="switch-line">
              <div>
                <strong>允许摘要</strong>
                <span>允许将非紧急通知合并为摘要</span>
              </div>
              <ElSwitch v-model="preferences.digestEnabled" />
            </div>
          </section>

          <section class="preference-matrix">
            <div class="matrix-header">
              <strong>事件类型</strong>
              <strong v-for="channel in channels" :key="channel">{{ channelLabel(channel) }}</strong>
            </div>
            <div v-for="eventType in eventTypes" :key="eventType" class="matrix-row">
              <span>{{ eventLabel(eventType) }}</span>
              <div v-for="channel in channels" :key="channel">
                <ElSwitch
                  v-if="preference(eventType, channel)"
                  v-model="preference(eventType, channel)!.enabled"
                />
              </div>
            </div>
          </section>

          <div class="save-row">
            <span>配置版本 {{ preferences.version }}</span>
            <ElButton :loading="saving" type="primary" @click="savePreferences">保存设置</ElButton>
          </div>
        </div>
      </template>
    </ElCard>
  </Page>
</template>

<style scoped>
.toolbar,.toolbar-actions,.title-row,.notification-actions,.switch-line,.save-row{display:flex;align-items:center}.toolbar,.switch-line,.save-row{justify-content:space-between}.toolbar{margin-bottom:16px}.toolbar-actions,.title-row,.notification-actions{gap:10px}.notification-list,.preferences-layout,.settings-card{display:grid;gap:16px}.notification-item{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:18px;border:1px solid var(--el-border-color-lighter);border-radius:10px;background:var(--el-fill-color-blank)}.notification-item.unread{border-left:4px solid var(--el-color-primary);background:var(--el-color-primary-light-9)}.notification-main{display:grid;flex:1;gap:8px;cursor:pointer}.notification-main p{margin:0;color:var(--el-text-color-primary)}.notification-main span,.switch-line span,.save-row span{color:var(--el-text-color-secondary);font-size:13px}.error-text{color:var(--el-color-danger)!important}.pagination-row{display:flex;justify-content:flex-end;margin-top:18px}.settings-card,.preference-matrix{padding:18px;border:1px solid var(--el-border-color-lighter);border-radius:10px}.settings-card h3{margin:0}.settings-card label{display:grid;gap:8px}.switch-line>div{display:grid;gap:4px}.time-grid{display:grid;grid-template-columns:repeat(2,minmax(0,240px));gap:12px}.matrix-header,.matrix-row{display:grid;grid-template-columns:minmax(180px,1fr) repeat(3,140px);align-items:center;gap:12px;padding:12px}.matrix-header{background:var(--el-fill-color-light);border-radius:8px}.matrix-header strong:not(:first-child),.matrix-row>div{text-align:center}.matrix-row{border-bottom:1px solid var(--el-border-color-lighter)}@media(max-width:900px){.notification-item{align-items:flex-start;flex-direction:column}.matrix-header,.matrix-row{grid-template-columns:minmax(130px,1fr) repeat(3,90px)}.time-grid{grid-template-columns:1fr}}
</style>
