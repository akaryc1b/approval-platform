<script lang="ts" setup>
import type {
  ApprovalTimeline,
  ApprovalTimelineItem,
} from '@/api/approval'
import type {
  ApprovalCommentPage,
  CommentOptions,
  CommentUserOption,
} from '@/api/approval/comments'

import {
  findApprovalTimeline,
  markMessageRead,
} from '@/api/approval'
import {
  createApprovalComment,
  findApprovalComments,
  findCommentOptions,
} from '@/api/approval/comments'

defineOptions({ name: 'ApprovalDiscussionDetail' })

definePage({
  style: {
    navigationBarTitleText: '审批讨论详情',
  },
})

const instanceId = ref('')
const supplier = ref('')
const businessKey = ref('')
const purchaseOrderReference = ref('')
const amount = ref(0)
const status = ref('')
const copyMessageId = ref('')
const loading = ref(false)
const submitting = ref(false)
const loadError = ref('')
const timeline = ref<ApprovalTimeline>()
const comments = ref<ApprovalCommentPage>(emptyComments())
const options = ref<CommentOptions>()
const commentBody = ref('')
const selectedMentionIds = ref<string[]>([])
const attachmentText = ref('')

function emptyComments(): ApprovalCommentPage {
  return { hasMore: false, items: [], limit: 100, offset: 0, total: 0 }
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '审批讨论请求失败'
}

function formatMoney(value: number) {
  return `¥${Number(value).toFixed(2)}`
}

function formatDate(value: string) {
  const date = new Date(value)
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function timelineTitle(item: ApprovalTimelineItem) {
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
  }
  return labels[item.action] || item.action
}

function isMentionSelected(user: CommentUserOption) {
  return selectedMentionIds.value.includes(user.userId)
}

function toggleMention(user: CommentUserOption) {
  if (isMentionSelected(user)) {
    selectedMentionIds.value = selectedMentionIds.value.filter(id => id !== user.userId)
  }
  else {
    selectedMentionIds.value = [...selectedMentionIds.value, user.userId]
  }
}

function attachmentIds() {
  return Array.from(new Set(
    attachmentText.value
      .split(/[\n,;，；]/)
      .map(item => item.trim())
      .filter(Boolean),
  )).slice(0, 20)
}

async function loadDiscussion() {
  if (!instanceId.value) {
    loadError.value = '缺少审批实例编号'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    if (copyMessageId.value) {
      await markMessageRead(copyMessageId.value)
      copyMessageId.value = ''
    }
    const [timelineResult, commentResult, optionResult] = await Promise.all([
      findApprovalTimeline(instanceId.value),
      findApprovalComments(instanceId.value),
      findCommentOptions(instanceId.value),
    ])
    timeline.value = timelineResult
    comments.value = commentResult
    options.value = optionResult
  }
  catch (error) {
    loadError.value = errorMessage(error)
  }
  finally {
    loading.value = false
  }
}

async function submitComment() {
  const body = commentBody.value.trim()
  if (!body) {
    uni.showToast({ title: '请填写评论内容', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    await createApprovalComment(
      instanceId.value,
      body,
      selectedMentionIds.value,
      attachmentIds(),
    )
    commentBody.value = ''
    selectedMentionIds.value = []
    attachmentText.value = ''
    uni.showToast({ title: '评论已发布', icon: 'success' })
    comments.value = await findApprovalComments(instanceId.value)
    timeline.value = await findApprovalTimeline(instanceId.value)
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    submitting.value = false
  }
}

function goBack() {
  uni.navigateBack()
}

onLoad((query) => {
  instanceId.value = String(query?.instanceId || '')
  supplier.value = decodeURIComponent(String(query?.supplier || ''))
  businessKey.value = decodeURIComponent(String(query?.businessKey || ''))
  purchaseOrderReference.value = decodeURIComponent(String(query?.purchaseOrderReference || ''))
  amount.value = Number(query?.amount || 0)
  status.value = decodeURIComponent(String(query?.status || ''))
  copyMessageId.value = String(query?.copyMessageId || '')
  loadDiscussion()
})
</script>

<template>
  <view class="page">
    <view v-if="loadError" class="state-card state-card--error">
      <text>{{ loadError }}</text>
      <wd-button size="small" plain @click="loadDiscussion">重新加载</wd-button>
    </view>
    <view v-else-if="loading" class="state-card">正在加载审批讨论...</view>

    <template v-else>
      <view class="summary-card">
        <view class="summary-header">
          <view>
            <text class="eyebrow">采购付款审批</text>
            <view class="title">{{ supplier }}采购付款</view>
          </view>
          <wd-tag plain type="primary">{{ status }}</wd-tag>
        </view>
        <view class="summary-grid">
          <view>
            <text>业务编号</text>
            <strong>{{ businessKey }}</strong>
          </view>
          <view>
            <text>付款金额</text>
            <strong>{{ formatMoney(amount) }}</strong>
          </view>
          <view class="summary-wide">
            <text>采购订单</text>
            <strong>{{ purchaseOrderReference }}</strong>
          </view>
        </view>
      </view>

      <view class="timeline-card">
        <view class="section-title">审批时间线</view>
        <view v-if="timeline?.items.length" class="timeline-list">
          <view v-for="item in timeline.items" :key="item.eventId" class="timeline-item">
            <view class="timeline-dot" />
            <view class="timeline-content">
              <strong>{{ timelineTitle(item) }}</strong>
              <text>操作人 {{ item.operatorId }}</text>
              <text v-if="item.attributes.comment">意见：{{ item.attributes.comment }}</text>
              <text>{{ formatDate(item.occurredAt) }}</text>
            </view>
          </view>
        </view>
        <text v-else class="muted">暂无审批记录</text>
      </view>

      <view class="comment-card">
        <view class="section-title">审批评论（{{ comments.total }}）</view>
        <view v-if="comments.items.length" class="comment-list">
          <view v-for="item in comments.items" :key="item.commentId" class="comment-item">
            <view class="comment-header">
              <strong>{{ item.authorDisplayName }}</strong>
              <text>{{ formatDate(item.createdAt) }}</text>
            </view>
            <text class="comment-body">{{ item.body }}</text>
            <view v-if="item.mentionedUsers.length" class="tag-row">
              <wd-tag
                v-for="user in item.mentionedUsers"
                :key="user.userId"
                plain
                type="primary"
              >
                @{{ user.displayName }}
              </wd-tag>
            </view>
            <view v-if="item.attachmentIds.length" class="tag-row">
              <wd-tag
                v-for="attachment in item.attachmentIds"
                :key="attachment"
                plain
                type="default"
              >
                {{ attachment }}
              </wd-tag>
            </view>
          </view>
        </view>
        <text v-else class="muted">暂无评论</text>
      </view>

      <view class="composer-card">
        <view class="section-title">发表评论</view>
        <text class="composer-label">@ 提及流程参与人（可选）</text>
        <view class="mention-list">
          <view
            v-for="user in options?.mentionCandidates || []"
            :key="user.userId"
            class="mention-chip"
            :class="{ 'mention-chip--active': isMentionSelected(user) }"
            @click="toggleMention(user)"
          >
            @{{ user.displayName }}
          </view>
        </view>
        <wd-textarea
          v-model="commentBody"
          :maxlength="4000"
          clearable
          placeholder="填写审批评论"
          show-word-limit
        />
        <wd-textarea
          v-model="attachmentText"
          :maxlength="4000"
          clearable
          placeholder="附件引用 ID（可选，逗号或换行分隔，最多 20 个）"
        />
        <text class="muted">附件上传接入后将替换为文件选择器。</text>
      </view>
    </template>

    <view class="action-bar">
      <wd-button plain @click="goBack">返回</wd-button>
      <wd-button
        type="primary"
        :disabled="!instanceId || loading"
        :loading="submitting"
        @click="submitComment"
      >
        发布评论
      </wd-button>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 170rpx;
  background: var(--wot-color-bg, var(--uni-bg-color-grey));
}

.summary-card,
.timeline-card,
.comment-card,
.composer-card,
.state-card {
  margin-bottom: 20rpx;
  padding: 28rpx;
  border-radius: var(--wot-radius-large, 24rpx);
  background: var(--wot-color-white, var(--uni-bg-color));
  box-shadow: 0 8rpx 24rpx rgb(15 23 42 / 5%);
}

.summary-header,
.comment-header,
.action-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.eyebrow,
.summary-grid text,
.timeline-content text,
.comment-header text,
.composer-label,
.muted,
.state-card {
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-size: 24rpx;
}

.title {
  margin-top: 10rpx;
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 34rpx;
  font-weight: 700;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24rpx;
  margin-top: 28rpx;
}

.summary-grid > view,
.timeline-content {
  display: grid;
  gap: 8rpx;
}

.summary-wide {
  grid-column: 1 / -1;
}

.section-title {
  margin-bottom: 20rpx;
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 29rpx;
  font-weight: 700;
}

.timeline-list,
.comment-list {
  display: grid;
  gap: 18rpx;
}

.timeline-item {
  display: flex;
  gap: 18rpx;
}

.timeline-dot {
  width: 18rpx;
  height: 18rpx;
  margin-top: 6rpx;
  border-radius: 50%;
  background: var(--wot-color-theme, var(--uni-color-primary));
}

.timeline-content {
  flex: 1;
}

.comment-item {
  display: grid;
  gap: 14rpx;
  padding: 22rpx;
  border: 1rpx solid var(--wot-color-border-light, var(--uni-border-color));
  border-radius: var(--wot-radius-large, 18rpx);
}

.comment-body {
  color: var(--wot-color-content, var(--uni-text-color));
  line-height: 1.7;
  white-space: pre-wrap;
}

.tag-row,
.mention-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.mention-list {
  margin-bottom: 20rpx;
}

.mention-chip {
  padding: 12rpx 18rpx;
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  border: 1rpx solid var(--wot-color-border-light, var(--uni-border-color));
  border-radius: 999rpx;
  font-size: 24rpx;
}

.mention-chip--active {
  color: var(--wot-color-theme, var(--uni-color-primary));
  border-color: var(--wot-color-theme, var(--uni-color-primary));
  background: var(--wot-color-primary-light, var(--uni-bg-color-grey));
}

.composer-card {
  display: grid;
  gap: 16rpx;
}

.state-card {
  display: grid;
  justify-items: center;
  gap: 20rpx;
  text-align: center;
}

.state-card--error {
  color: var(--wot-color-danger, var(--uni-color-error));
}

.action-bar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  justify-content: flex-end;
  padding: 20rpx 24rpx calc(20rpx + env(safe-area-inset-bottom));
  background: var(--wot-color-white, var(--uni-bg-color));
  box-shadow: 0 -8rpx 24rpx rgb(15 23 42 / 8%);
}
</style>
