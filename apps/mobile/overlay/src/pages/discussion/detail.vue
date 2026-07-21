<script lang="ts" setup>
import type { ApprovalTimeline } from '@/api/approval'
import type {
  ApprovalAttachment,
  ApprovalCommentItem,
  ApprovalCommentPage,
  CommentOptions,
  CommentRevisionItem,
  CommentUserOption,
  CommentVisibility,
} from '@/api/approval/comments'

import {
  findApprovalTimeline,
  markMessageRead,
} from '@/api/approval'
import {
  createApprovalComment,
  deleteApprovalComment,
  downloadApprovalAttachment,
  findApprovalCommentRevisions,
  findApprovalComments,
  findCommentOptions,
  updateApprovalComment,
  uploadApprovalAttachment,
} from '@/api/approval/comments'
import { getApprovalRuntimeConfig } from '@/platform/approval/runtime'

defineOptions({ name: 'ApprovalDiscussionDetail' })

definePage({
  style: {
    navigationBarTitleText: '审批讨论详情',
  },
})

const instanceId = ref('')
const focusCommentId = ref('')
const supplier = ref('')
const businessKey = ref('')
const purchaseOrderReference = ref('')
const amount = ref(0)
const status = ref('')
const messageId = ref('')
const loading = ref(false)
const submitting = ref(false)
const uploading = ref(false)
const deleting = ref(false)
const revisionLoading = ref(false)
const loadError = ref('')
const timeline = ref<ApprovalTimeline>()
const comments = ref<ApprovalCommentPage>(emptyComments())
const options = ref<CommentOptions>()
const commentBody = ref('')
const selectedMentionIds = ref<string[]>([])
const selectedVisibility = ref<CommentVisibility>('PARTICIPANTS')
const attachments = ref<ApprovalAttachment[]>([])
const replyingTo = ref<ApprovalCommentItem>()
const editingItem = ref<ApprovalCommentItem>()
const deleteTarget = ref<ApprovalCommentItem>()
const deleteReason = ref('')
const revisionTarget = ref<ApprovalCommentItem>()
const revisions = ref<CommentRevisionItem[]>([])
const operatorId = getApprovalRuntimeConfig().operatorId

const commentReadOnly = computed(() => comments.value.readOnly || options.value?.readOnly === true)
const visibilityLocked = computed(() => Boolean(replyingTo.value) || editingItem.value?.privateComment === true)
const mentionCandidates = computed(() => {
  const candidates = options.value?.mentionCandidates || []
  const parent = replyingTo.value
  if (!parent?.privateComment) return candidates
  const allowed = new Set([
    parent.authorId,
    ...parent.mentionedUsers.map(user => user.userId),
  ])
  return candidates.filter(candidate => allowed.has(candidate.userId))
})

function emptyComments(): ApprovalCommentPage {
  return { hasMore: false, items: [], limit: 100, offset: 0, readOnly: false, total: 0 }
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '审批讨论请求失败'
}

function formatMoney(value: number) {
  return `¥${Number(value).toFixed(2)}`
}

function formatSize(value: number) {
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`
  return `${(value / 1024 / 1024).toFixed(1)} MiB`
}

function formatDate(value: string) {
  const date = new Date(value)
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function visibilityLabel(value: CommentVisibility) {
  return value === 'MENTIONED_ONLY' ? '仅作者和提及人可见' : '全部审批参与人可见'
}

function revisionLabel(item: CommentRevisionItem) {
  const labels = { CREATE: '创建', DELETE: '删除', EDIT: '编辑' }
  return labels[item.revisionType]
}

function identityDescription(user: CommentUserOption) {
  return `${user.identitySource} · ${user.objectType} · ${user.externalIdentityValue}`
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

function resetComposer() {
  commentBody.value = ''
  selectedMentionIds.value = []
  selectedVisibility.value = 'PARTICIPANTS'
  attachments.value = []
  replyingTo.value = undefined
  editingItem.value = undefined
}

function startReply(item: ApprovalCommentItem) {
  if (item.reply || item.deleted || commentReadOnly.value) return
  resetComposer()
  replyingTo.value = item
  selectedVisibility.value = item.visibility
  if (item.privateComment) {
    const inherited = item.mentionedUsers
      .map(user => user.userId)
      .filter(userId => userId !== operatorId)
    selectedMentionIds.value = item.authorId === operatorId
      ? inherited
      : Array.from(new Set([item.authorId, ...inherited]))
  }
  else if (item.authorId !== operatorId) {
    selectedMentionIds.value = [item.authorId]
  }
}

function startEdit(item: ApprovalCommentItem) {
  if (!item.canEdit || item.deleted || commentReadOnly.value) return
  resetComposer()
  editingItem.value = item
  commentBody.value = item.body
  selectedMentionIds.value = item.mentionedUsers.map(user => user.userId)
  selectedVisibility.value = item.visibility
  attachments.value = [...item.attachments]
}

function cancelComposerMode() {
  resetComposer()
}

function setVisibility(value: CommentVisibility) {
  if (visibilityLocked.value) return
  selectedVisibility.value = value
}

async function focusComment() {
  if (!focusCommentId.value) return
  await nextTick()
  uni.pageScrollTo({
    selector: `#comment-${focusCommentId.value}`,
    duration: 300,
  })
}

async function loadDiscussion() {
  if (!instanceId.value) {
    loadError.value = '缺少审批实例编号'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    if (messageId.value) {
      await markMessageRead(messageId.value)
      messageId.value = ''
    }
    const [timelineResult, commentResult, optionResult] = await Promise.all([
      findApprovalTimeline(instanceId.value),
      findApprovalComments(instanceId.value),
      findCommentOptions(instanceId.value),
    ])
    timeline.value = timelineResult
    comments.value = commentResult
    options.value = optionResult
    if (commentResult.readOnly) resetComposer()
    await focusComment()
  }
  catch (error) {
    loadError.value = errorMessage(error)
  }
  finally {
    loading.value = false
  }
}

function chooseAttachments() {
  const remaining = 20 - attachments.value.length
  if (remaining <= 0) {
    uni.showToast({ title: '单条评论最多 20 个附件', icon: 'none' })
    return
  }
  uni.chooseMessageFile({
    count: remaining,
    type: 'file',
    success: async (result) => {
      uploading.value = true
      try {
        for (const file of result.tempFiles) {
          if (file.size > 10 * 1024 * 1024) {
            throw new Error(`${file.name} 超过 10 MiB`)
          }
          const uploaded = await uploadApprovalAttachment(file.path)
          attachments.value = [...attachments.value, uploaded]
        }
        uni.showToast({ title: `已上传 ${result.tempFiles.length} 个附件`, icon: 'success' })
      }
      catch (error) {
        uni.showToast({ title: errorMessage(error), icon: 'none' })
      }
      finally {
        uploading.value = false
      }
    },
  })
}

function removeAttachment(attachmentId: string) {
  attachments.value = attachments.value.filter(item => item.attachmentId !== attachmentId)
}

async function openAttachment(attachment: ApprovalAttachment) {
  try {
    const filePath = await downloadApprovalAttachment(attachment)
    await new Promise<void>((resolve, reject) => {
      uni.openDocument({
        filePath,
        success: () => resolve(),
        fail: error => reject(new Error(error.errMsg || '无法打开附件')),
      })
    })
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
}

async function submitComment() {
  if (commentReadOnly.value) {
    uni.showToast({ title: '审批已结束，评论区只读', icon: 'none' })
    return
  }
  const body = commentBody.value.trim()
  if (!body) {
    uni.showToast({ title: '请填写评论内容', icon: 'none' })
    return
  }
  if (selectedVisibility.value === 'MENTIONED_ONLY' && selectedMentionIds.value.length === 0) {
    uni.showToast({ title: '私密评论必须至少提及一人', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    if (editingItem.value) {
      await updateApprovalComment(
        instanceId.value,
        editingItem.value.commentId,
        body,
        selectedMentionIds.value,
        attachments.value.map(item => item.attachmentId),
        selectedVisibility.value,
        editingItem.value.version,
      )
      uni.showToast({ title: '评论已更新', icon: 'success' })
    }
    else {
      await createApprovalComment(
        instanceId.value,
        replyingTo.value?.commentId,
        body,
        selectedMentionIds.value,
        attachments.value.map(item => item.attachmentId),
        selectedVisibility.value,
      )
      uni.showToast({ title: replyingTo.value ? '回复已发布' : '评论已发布', icon: 'success' })
    }
    resetComposer()
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

function askDelete(item: ApprovalCommentItem) {
  deleteTarget.value = item
  deleteReason.value = ''
  revisionTarget.value = undefined
}

function cancelDelete() {
  deleteTarget.value = undefined
  deleteReason.value = ''
}

async function confirmDelete() {
  const target = deleteTarget.value
  const reason = deleteReason.value.trim()
  if (!target) return
  if (!reason) {
    uni.showToast({ title: '请填写删除原因', icon: 'none' })
    return
  }
  deleting.value = true
  try {
    await deleteApprovalComment(
      instanceId.value,
      target.commentId,
      target.version,
      reason,
    )
    cancelDelete()
    if (editingItem.value?.commentId === target.commentId) resetComposer()
    comments.value = await findApprovalComments(instanceId.value)
    timeline.value = await findApprovalTimeline(instanceId.value)
    uni.showToast({ title: '评论已删除', icon: 'success' })
  }
  catch (error) {
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    deleting.value = false
  }
}

async function showRevisions(item: ApprovalCommentItem) {
  revisionTarget.value = item
  deleteTarget.value = undefined
  revisions.value = []
  revisionLoading.value = true
  try {
    revisions.value = await findApprovalCommentRevisions(instanceId.value, item.commentId)
  }
  catch (error) {
    revisionTarget.value = undefined
    uni.showToast({ title: errorMessage(error), icon: 'none' })
  }
  finally {
    revisionLoading.value = false
  }
}

function closeRevisions() {
  revisionTarget.value = undefined
  revisions.value = []
}

function goBack() {
  uni.navigateBack()
}

onLoad((query) => {
  instanceId.value = String(query?.instanceId || '')
  focusCommentId.value = String(query?.commentId || '')
  supplier.value = decodeURIComponent(String(query?.supplier || ''))
  businessKey.value = decodeURIComponent(String(query?.businessKey || ''))
  purchaseOrderReference.value = decodeURIComponent(String(query?.purchaseOrderReference || ''))
  amount.value = Number(query?.amount || 0)
  status.value = decodeURIComponent(String(query?.status || ''))
  messageId.value = String(query?.messageId || query?.copyMessageId || '')
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
              <strong>{{ item.summary }}</strong>
              <text>操作人 {{ item.operatorId }}</text>
              <text>{{ item.schemaName }} v{{ item.schemaVersion }}</text>
              <text>{{ formatDate(item.occurredAt) }}</text>
            </view>
          </view>
        </view>
        <text v-else class="muted">暂无审批记录</text>
      </view>

      <view v-if="commentReadOnly" class="readonly-card">
        审批实例已结束，历史评论和附件仍可查看，评论区为只读状态。
      </view>

      <view class="comment-card">
        <view class="section-title">审批评论（{{ comments.total }}）</view>
        <view v-if="comments.items.length" class="comment-list">
          <view
            v-for="item in comments.items"
            :id="`comment-${item.commentId}`"
            :key="item.commentId"
            class="comment-item"
            :class="{
              'comment-item--deleted': item.deleted,
              'comment-item--focus': item.commentId === focusCommentId,
              'comment-item--private': item.privateComment,
              'comment-item--reply': item.reply,
            }"
          >
            <view class="comment-header">
              <view>
                <strong>{{ item.authorDisplayName }}</strong>
                <text v-if="item.replyToAuthorDisplayName">
                  回复 {{ item.replyToAuthorDisplayName }}
                </text>
              </view>
              <text>{{ formatDate(item.updatedAt || item.createdAt) }}</text>
            </view>
            <view class="status-tags">
              <wd-tag v-if="item.privateComment" plain type="primary">私密评论</wd-tag>
              <wd-tag v-if="item.edited && !item.deleted" plain>已编辑</wd-tag>
              <wd-tag v-if="item.deleted" plain type="error">已删除</wd-tag>
            </view>
            <text class="comment-body" :class="{ tombstone: item.deleted }">{{ item.body }}</text>
            <view v-if="item.deleteReason" class="delete-evidence">
              删除原因：{{ item.deleteReason }}
            </view>
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
            <view v-if="item.attachments.length" class="attachment-list">
              <view
                v-for="attachment in item.attachments"
                :key="attachment.attachmentId"
                class="attachment-row"
                @click="openAttachment(attachment)"
              >
                <view>
                  <strong>{{ attachment.fileName }}</strong>
                  <text>{{ formatSize(attachment.sizeBytes) }} · {{ attachment.contentType }}</text>
                </view>
                <text class="attachment-open">打开</text>
              </view>
            </view>
            <view class="comment-actions">
              <wd-button
                v-if="!item.reply && !item.deleted && !commentReadOnly"
                size="small"
                plain
                @click="startReply(item)"
              >
                回复
              </wd-button>
              <wd-button v-if="item.canEdit" size="small" plain @click="startEdit(item)">
                编辑
              </wd-button>
              <wd-button
                v-if="item.canDelete"
                size="small"
                plain
                type="error"
                @click="askDelete(item)"
              >
                删除
              </wd-button>
              <wd-button
                v-if="item.authorId === operatorId || item.currentRevision > 1"
                size="small"
                plain
                @click="showRevisions(item)"
              >
                修订 {{ item.currentRevision }}
              </wd-button>
            </view>
          </view>
        </view>
        <text v-else class="muted">暂无评论</text>
      </view>

      <view v-if="revisionTarget" class="evidence-card">
        <view class="section-header">
          <view class="section-title">修订历史 · {{ revisionTarget.authorDisplayName }}</view>
          <wd-button size="small" plain @click="closeRevisions">关闭</wd-button>
        </view>
        <text v-if="revisionLoading" class="muted">正在读取修订证据...</text>
        <view v-else class="revision-list">
          <view v-for="revision in revisions" :key="revision.revisionNumber" class="revision-item">
            <view class="revision-header">
              <strong>修订 {{ revision.revisionNumber }} · {{ revisionLabel(revision) }}</strong>
              <text>{{ formatDate(revision.occurredAt) }}</text>
            </view>
            <text class="comment-body">{{ revision.body }}</text>
            <text class="muted">{{ visibilityLabel(revision.visibility) }}</text>
            <text class="muted">操作人 {{ revision.operatorId }}</text>
            <text v-if="revision.reason" class="muted">原因：{{ revision.reason }}</text>
            <view v-if="revision.mentionedUsers.length" class="tag-row">
              <wd-tag
                v-for="user in revision.mentionedUsers"
                :key="user.userId"
                plain
              >
                @{{ user.displayName }}
              </wd-tag>
            </view>
          </view>
        </view>
      </view>

      <view v-if="deleteTarget" class="evidence-card">
        <view class="section-header">
          <view class="section-title">删除评论</view>
          <wd-button size="small" plain @click="cancelDelete">取消</wd-button>
        </view>
        <text class="muted">删除后显示稳定墓碑，历史正文、提及和附件证据不会物理删除。</text>
        <wd-textarea
          v-model="deleteReason"
          :maxlength="2000"
          clearable
          placeholder="填写删除原因"
          show-word-limit
        />
        <wd-button block type="error" :loading="deleting" @click="confirmDelete">
          确认删除
        </wd-button>
      </view>

      <view v-if="!commentReadOnly" class="composer-card">
        <view class="section-title">
          {{ editingItem ? '编辑评论' : replyingTo ? `回复 ${replyingTo.authorDisplayName}` : '发表评论' }}
        </view>
        <view v-if="replyingTo || editingItem" class="reply-notice">
          <text>{{ replyingTo ? '回复仅支持一层并继承父评论范围' : '仅原作者可在 15 分钟窗口内编辑' }}</text>
          <wd-button size="small" plain @click="cancelComposerMode">取消</wd-button>
        </view>
        <text class="composer-label">评论可见范围</text>
        <view class="visibility-list">
          <view
            class="visibility-chip"
            :class="{ 'visibility-chip--active': selectedVisibility === 'PARTICIPANTS' }"
            @click="setVisibility('PARTICIPANTS')"
          >
            全部参与人
          </view>
          <view
            class="visibility-chip"
            :class="{ 'visibility-chip--active': selectedVisibility === 'MENTIONED_ONLY' }"
            @click="setVisibility('MENTIONED_ONLY')"
          >
            仅作者和提及人
          </view>
        </view>
        <text v-if="selectedVisibility === 'MENTIONED_ONLY'" class="private-hint">
          私密评论必须至少提及一人；私密回复不能扩大父评论接收范围。
        </text>
        <text class="composer-label">@ 精确提及审批参与人</text>
        <view class="mention-list">
          <view
            v-for="user in mentionCandidates"
            :key="user.userId"
            class="mention-chip"
            :class="{ 'mention-chip--active': isMentionSelected(user) }"
            @click="toggleMention(user)"
          >
            <strong>@{{ user.displayName }}</strong>
            <text>{{ identityDescription(user) }}</text>
          </view>
        </view>
        <wd-textarea
          v-model="commentBody"
          :maxlength="4000"
          clearable
          :placeholder="editingItem ? '修改评论内容' : replyingTo ? `回复 ${replyingTo.authorDisplayName}` : '填写审批评论'"
          show-word-limit
        />
        <view class="upload-bar">
          <wd-button size="small" plain :loading="uploading" @click="chooseAttachments">
            选择附件
          </wd-button>
          <text>仅允许当前作者上传、当前审批绑定的附件</text>
        </view>
        <view v-if="attachments.length" class="pending-list">
          <view
            v-for="attachment in attachments"
            :key="attachment.attachmentId"
            class="pending-row"
          >
            <view>
              <strong>{{ attachment.fileName }}</strong>
              <text>{{ formatSize(attachment.sizeBytes) }}</text>
            </view>
            <wd-button
              size="small"
              type="error"
              plain
              @click="removeAttachment(attachment.attachmentId)"
            >
              移除
            </wd-button>
          </view>
        </view>
      </view>
    </template>

    <view class="action-bar">
      <wd-button plain @click="goBack">返回</wd-button>
      <wd-button plain :loading="loading" @click="loadDiscussion">刷新</wd-button>
      <wd-button
        v-if="!commentReadOnly"
        type="primary"
        :disabled="!instanceId || loading || uploading"
        :loading="submitting"
        @click="submitComment"
      >
        {{ editingItem ? '保存修改' : replyingTo ? '发布回复' : '发布评论' }}
      </wd-button>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 190rpx;
  background: var(--wot-color-bg, var(--uni-bg-color-grey));
}

.summary-card,
.timeline-card,
.comment-card,
.composer-card,
.evidence-card,
.readonly-card,
.state-card {
  margin-bottom: 20rpx;
  padding: 28rpx;
  border-radius: var(--wot-radius-large, 24rpx);
  background: var(--wot-color-white, var(--uni-bg-color));
  box-shadow: 0 8rpx 24rpx rgb(15 23 42 / 5%);
}

.readonly-card,
.private-hint {
  color: var(--wot-color-warning, #d97706);
  background: #fff7ed;
  font-size: 24rpx;
  line-height: 1.6;
}

.summary-header,
.comment-header,
.action-bar,
.reply-notice,
.upload-bar,
.attachment-row,
.pending-row,
.section-header,
.revision-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}

.comment-header > view,
.attachment-row > view,
.pending-row > view {
  display: grid;
  gap: 6rpx;
}

.eyebrow,
.summary-grid text,
.timeline-content text,
.comment-header text,
.composer-label,
.muted,
.state-card,
.upload-bar text,
.attachment-row text,
.pending-row text,
.reply-notice text,
.revision-header text,
.mention-chip text {
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
.timeline-content,
.revision-item,
.mention-chip {
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

.section-header .section-title {
  margin-bottom: 0;
}

.timeline-list,
.comment-list,
.attachment-list,
.pending-list,
.revision-list {
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

.comment-item,
.revision-item {
  display: grid;
  gap: 14rpx;
  padding: 22rpx;
  border: 1rpx solid var(--wot-color-border-light, var(--uni-border-color));
  border-radius: var(--wot-radius-base, 16rpx);
  scroll-margin-top: 24rpx;
}

.comment-item--reply {
  margin-left: 36rpx;
  border-left: 6rpx solid var(--wot-color-theme, var(--uni-color-primary));
}

.comment-item--private {
  border-color: var(--wot-color-warning, #d97706);
}

.comment-item--deleted {
  background: var(--wot-color-bg, var(--uni-bg-color-grey));
}

.comment-item--focus {
  border-color: var(--wot-color-theme, var(--uni-color-primary));
  background: var(--wot-color-theme-light, var(--uni-bg-color));
}

.comment-body {
  color: var(--wot-color-content, var(--uni-text-color));
  font-size: 26rpx;
  line-height: 1.7;
  white-space: pre-wrap;
}

.tombstone {
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  font-style: italic;
}

.delete-evidence {
  padding: 14rpx;
  border-radius: 12rpx;
  color: var(--wot-color-danger, var(--uni-color-error));
  background: #fef2f2;
  font-size: 24rpx;
}

.tag-row,
.status-tags,
.visibility-list,
.comment-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}

.comment-actions {
  justify-content: flex-end;
}

.attachment-row,
.pending-row {
  padding: 18rpx 0;
  border-bottom: 1rpx solid var(--wot-color-border-light, var(--uni-border-color));
}

.attachment-open {
  color: var(--wot-color-theme, var(--uni-color-primary));
}

.reply-notice,
.upload-bar,
.private-hint,
.visibility-list,
.mention-list,
.evidence-card .section-header {
  margin-bottom: 18rpx;
}

.private-hint {
  display: block;
  padding: 14rpx;
  border-radius: 12rpx;
}

.visibility-chip {
  padding: 12rpx 20rpx;
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  border: 1rpx solid var(--wot-color-border-light, var(--uni-border-color));
  border-radius: 999rpx;
  font-size: 24rpx;
}

.visibility-chip--active {
  color: var(--wot-color-theme, var(--uni-color-primary));
  border-color: var(--wot-color-theme, var(--uni-color-primary));
  background: var(--wot-color-theme-light, var(--uni-bg-color));
}

.mention-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12rpx;
}

.mention-chip {
  padding: 14rpx 16rpx;
  color: var(--wot-color-content-secondary, var(--uni-text-color-grey));
  border: 1rpx solid var(--wot-color-border-light, var(--uni-border-color));
  border-radius: 16rpx;
  font-size: 24rpx;
}

.mention-chip--active {
  color: var(--wot-color-theme, var(--uni-color-primary));
  border-color: var(--wot-color-theme, var(--uni-color-primary));
  background: var(--wot-color-theme-light, var(--uni-bg-color));
}

.action-bar {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  padding: 20rpx 24rpx calc(20rpx + env(safe-area-inset-bottom));
  background: var(--wot-color-white, var(--uni-bg-color));
  box-shadow: 0 -8rpx 24rpx rgb(15 23 42 / 8%);
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
</style>
