<script lang="ts" setup>
import type {
  ApprovalAttachment,
  ApprovalCommentItem,
  ApprovalCommentPage,
  CommentOptions,
  CommentRevisionItem,
  CommentUserOption,
  CommentVisibility,
} from '#/api/approval/comments';

import { computed, nextTick, ref, watch } from 'vue';

import {
  ElAlert,
  ElButton,
  ElDialog,
  ElEmpty,
  ElInput,
  ElMessage,
  ElOption,
  ElSelect,
  ElSkeleton,
  ElTag,
} from 'element-plus';

import {
  createApprovalComment,
  deleteApprovalComment,
  downloadApprovalAttachment,
  findApprovalCommentRevisions,
  findApprovalComments,
  findCommentOptions,
  updateApprovalComment,
  uploadApprovalAttachment,
} from '#/api/approval/comments';
import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

const props = defineProps<{
  focusCommentId?: string;
  instanceId: string;
}>();

const loading = ref(false);
const submitting = ref(false);
const uploading = ref(false);
const errorText = ref('');
const comments = ref<ApprovalCommentPage>(emptyPage());
const options = ref<CommentOptions>();
const body = ref('');
const mentionIds = ref<string[]>([]);
const attachments = ref<ApprovalAttachment[]>([]);
const visibility = ref<CommentVisibility>('PARTICIPANTS');
const replyingTo = ref<ApprovalCommentItem>();
const editingItem = ref<ApprovalCommentItem>();
const fileInput = ref<HTMLInputElement>();
const deleteTarget = ref<ApprovalCommentItem>();
const deleteReason = ref('');
const deleting = ref(false);
const revisionTarget = ref<ApprovalCommentItem>();
const revisions = ref<CommentRevisionItem[]>([]);
const revisionLoading = ref(false);

const operatorId = getApprovalRuntimeConfig().operatorId;

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  month: '2-digit',
  year: 'numeric',
});

const composerReadOnly = computed(
  () => comments.value.readOnly || options.value?.readOnly === true,
);

const visibilityLocked = computed(
  () => Boolean(replyingTo.value) || editingItem.value?.privateComment === true,
);

const mentionCandidates = computed(() => {
  const candidates = options.value?.mentionCandidates || [];
  const parent = replyingTo.value;
  if (!parent?.privateComment) return candidates;
  const allowed = new Set([
    parent.authorId,
    ...parent.mentionedUsers.map((user) => user.userId),
  ]);
  return candidates.filter((candidate) => allowed.has(candidate.userId));
});

function emptyPage(): ApprovalCommentPage {
  return {
    hasMore: false,
    items: [],
    limit: 100,
    offset: 0,
    readOnly: false,
    total: 0,
  };
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '评论请求失败';
}

function formatDate(value: string) {
  return dateFormatter.format(new Date(value));
}

function formatSize(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`;
  return `${(value / 1024 / 1024).toFixed(1)} MiB`;
}

function identityDescription(user: CommentUserOption) {
  return `${user.identitySource} · ${user.objectType} · ${user.externalIdentityValue}`;
}

function revisionLabel(revision: CommentRevisionItem) {
  const labels = { CREATE: '创建', DELETE: '删除', EDIT: '编辑' } as const;
  return labels[revision.revisionType];
}

async function focusComment() {
  if (!props.focusCommentId) return;
  await nextTick();
  document.getElementById(`approval-comment-${props.focusCommentId}`)?.scrollIntoView({
    behavior: 'smooth',
    block: 'center',
  });
}

async function loadComments() {
  if (!props.instanceId) return;
  loading.value = true;
  errorText.value = '';
  try {
    const [page, optionResult] = await Promise.all([
      findApprovalComments(props.instanceId),
      findCommentOptions(props.instanceId),
    ]);
    comments.value = page;
    options.value = optionResult;
    if (page.readOnly) resetComposer();
    await focusComment();
  } catch (error) {
    comments.value = emptyPage();
    options.value = undefined;
    errorText.value = errorMessage(error);
  } finally {
    loading.value = false;
  }
}

function resetComposer() {
  body.value = '';
  mentionIds.value = [];
  attachments.value = [];
  visibility.value = 'PARTICIPANTS';
  replyingTo.value = undefined;
  editingItem.value = undefined;
}

function startReply(item: ApprovalCommentItem) {
  if (item.reply || item.deleted || composerReadOnly.value) return;
  resetComposer();
  replyingTo.value = item;
  visibility.value = item.visibility;
  if (item.privateComment) {
    mentionIds.value = item.authorId === operatorId ? [] : [item.authorId];
  } else if (item.authorId !== operatorId) {
    mentionIds.value = [item.authorId];
  }
}

function startEdit(item: ApprovalCommentItem) {
  if (!item.canEdit || item.deleted || composerReadOnly.value) return;
  resetComposer();
  editingItem.value = item;
  body.value = item.body;
  mentionIds.value = item.mentionedUsers.map((user) => user.userId);
  attachments.value = [...item.attachments];
  visibility.value = item.visibility;
}

function cancelComposerMode() {
  resetComposer();
}

function chooseFiles() {
  fileInput.value?.click();
}

async function uploadFiles(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  input.value = '';
  if (!files.length) return;
  if (attachments.value.length + files.length > 20) {
    ElMessage.warning('单条评论最多上传 20 个附件');
    return;
  }
  uploading.value = true;
  try {
    for (const file of files) {
      if (file.size > 10 * 1024 * 1024) {
        throw new Error(`${file.name} 超过 10 MiB`);
      }
      const uploaded = await uploadApprovalAttachment(file);
      attachments.value = [...attachments.value, uploaded];
    }
    ElMessage.success(`已上传 ${files.length} 个附件`);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    uploading.value = false;
  }
}

function removeAttachment(attachmentId: string) {
  attachments.value = attachments.value.filter(
    (item) => item.attachmentId !== attachmentId,
  );
}

async function downloadAttachment(item: ApprovalAttachment) {
  try {
    await downloadApprovalAttachment(item);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function submitComment() {
  if (composerReadOnly.value) {
    ElMessage.warning('审批已结束，评论区为只读状态');
    return;
  }
  const content = body.value.trim();
  if (!content) {
    ElMessage.warning('请填写评论内容');
    return;
  }
  if (visibility.value === 'MENTIONED_ONLY' && mentionIds.value.length === 0) {
    ElMessage.warning('私密评论必须至少提及一名审批参与人');
    return;
  }
  submitting.value = true;
  try {
    if (editingItem.value) {
      await updateApprovalComment(
        props.instanceId,
        editingItem.value.commentId,
        content,
        mentionIds.value,
        attachments.value.map((item) => item.attachmentId),
        visibility.value,
        editingItem.value.version,
      );
      ElMessage.success('评论已更新');
    } else {
      await createApprovalComment(
        props.instanceId,
        replyingTo.value?.commentId,
        content,
        mentionIds.value,
        attachments.value.map((item) => item.attachmentId),
        visibility.value,
      );
      ElMessage.success(replyingTo.value ? '回复已发布' : '评论已发布');
    }
    resetComposer();
    await loadComments();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    submitting.value = false;
  }
}

function askDelete(item: ApprovalCommentItem) {
  deleteTarget.value = item;
  deleteReason.value = '';
}

async function confirmDelete() {
  const target = deleteTarget.value;
  const reason = deleteReason.value.trim();
  if (!target) return;
  if (!reason) {
    ElMessage.warning('请填写删除原因');
    return;
  }
  deleting.value = true;
  try {
    await deleteApprovalComment(
      props.instanceId,
      target.commentId,
      target.version,
      reason,
    );
    deleteTarget.value = undefined;
    deleteReason.value = '';
    if (editingItem.value?.commentId === target.commentId) resetComposer();
    ElMessage.success('评论已删除并保留修订证据');
    await loadComments();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    deleting.value = false;
  }
}

async function showRevisions(item: ApprovalCommentItem) {
  revisionTarget.value = item;
  revisions.value = [];
  revisionLoading.value = true;
  try {
    revisions.value = await findApprovalCommentRevisions(
      props.instanceId,
      item.commentId,
    );
  } catch (error) {
    revisionTarget.value = undefined;
    ElMessage.error(errorMessage(error));
  } finally {
    revisionLoading.value = false;
  }
}

watch(
  () => [props.instanceId, props.focusCommentId],
  loadComments,
  { immediate: true },
);
</script>

<template>
  <section class="comment-thread">
    <div class="comment-header">
      <div>
        <h3>审批讨论</h3>
        <span>共 {{ comments.total }} 条评论</span>
      </div>
      <ElButton :loading="loading" text @click="loadComments">刷新</ElButton>
    </div>

    <ElAlert
      v-if="composerReadOnly"
      :closable="false"
      title="审批实例已结束，历史评论和附件仍可查看，评论区为只读状态。"
      type="warning"
    />
    <ElSkeleton v-if="loading" :rows="4" animated />
    <ElAlert
      v-else-if="errorText"
      :closable="false"
      :title="errorText"
      type="error"
    />
    <ElEmpty
      v-else-if="comments.items.length === 0"
      description="暂无评论"
      :image-size="72"
    />
    <div v-else class="comment-list">
      <article
        v-for="item in comments.items"
        :id="`approval-comment-${item.commentId}`"
        :key="item.commentId"
        class="comment-item"
        :class="{
          'comment-item--deleted': item.deleted,
          'comment-item--focus': item.commentId === focusCommentId,
          'comment-item--private': item.privateComment,
          'comment-item--reply': item.reply,
        }"
      >
        <div class="comment-meta">
          <div class="comment-author">
            <strong>{{ item.authorDisplayName }}</strong>
            <span v-if="item.replyToAuthorDisplayName">
              回复 {{ item.replyToAuthorDisplayName }}
            </span>
            <ElTag v-if="item.privateComment" effect="plain" size="small" type="warning">
              私密评论
            </ElTag>
            <ElTag v-if="item.edited && !item.deleted" effect="plain" size="small" type="info">
              已编辑
            </ElTag>
            <ElTag v-if="item.deleted" effect="plain" size="small" type="danger">
              已删除
            </ElTag>
          </div>
          <span>{{ formatDate(item.updatedAt || item.createdAt) }}</span>
        </div>
        <p :class="{ tombstone: item.deleted }">{{ item.body }}</p>
        <div v-if="item.deleteReason" class="delete-evidence">
          删除原因：{{ item.deleteReason }}
        </div>
        <div v-if="item.mentionedUsers.length" class="tag-row">
          <span class="hint">可见提及</span>
          <ElTag
            v-for="user in item.mentionedUsers"
            :key="user.userId"
            effect="plain"
            size="small"
            :title="identityDescription(user)"
          >
            @{{ user.displayName }}
          </ElTag>
        </div>
        <div v-if="item.attachments.length" class="attachment-row">
          <span class="hint">附件</span>
          <ElButton
            v-for="attachment in item.attachments"
            :key="attachment.attachmentId"
            link
            type="primary"
            @click="downloadAttachment(attachment)"
          >
            {{ attachment.fileName }}（{{ formatSize(attachment.sizeBytes) }}）
          </ElButton>
        </div>
        <div class="comment-actions">
          <ElButton
            v-if="!item.reply && !item.deleted && !composerReadOnly"
            link
            type="primary"
            @click="startReply(item)"
          >
            回复
          </ElButton>
          <ElButton
            v-if="item.canEdit"
            link
            type="primary"
            @click="startEdit(item)"
          >
            编辑
          </ElButton>
          <ElButton
            v-if="item.canDelete"
            link
            type="danger"
            @click="askDelete(item)"
          >
            删除
          </ElButton>
          <ElButton
            v-if="item.authorId === operatorId || item.currentRevision > 1"
            link
            @click="showRevisions(item)"
          >
            修订历史（{{ item.currentRevision }}）
          </ElButton>
        </div>
      </article>
    </div>

    <ElAlert
      v-if="comments.hasMore"
      :closable="false"
      title="当前仅展示前 100 条评论"
      type="info"
    />

    <div v-if="!composerReadOnly" class="composer">
      <ElAlert
        v-if="replyingTo || editingItem"
        :closable="false"
        type="info"
      >
        <template #title>
          {{
            editingItem
              ? `正在编辑 ${editingItem.authorDisplayName} 的评论`
              : `正在回复 ${replyingTo?.authorDisplayName}`
          }}
          <ElButton link type="primary" @click="cancelComposerMode">取消</ElButton>
        </template>
      </ElAlert>
      <div class="composer-grid">
        <ElSelect
          v-model="visibility"
          :disabled="visibilityLocked"
          placeholder="评论可见范围"
        >
          <ElOption label="全部审批参与人可见" value="PARTICIPANTS" />
          <ElOption label="仅作者和明确提及人可见" value="MENTIONED_ONLY" />
        </ElSelect>
        <ElSelect
          v-model="mentionIds"
          collapse-tags
          collapse-tags-tooltip
          filterable
          multiple
          placeholder="@ 精确提及审批参与人"
        >
          <ElOption
            v-for="user in mentionCandidates"
            :key="user.userId"
            :label="user.displayName"
            :value="user.userId"
          >
            <span>{{ user.displayName }}</span>
            <span class="candidate-id">{{ identityDescription(user) }}</span>
          </ElOption>
        </ElSelect>
      </div>
      <ElAlert
        v-if="visibility === 'MENTIONED_ONLY'"
        :closable="false"
        title="私密评论只对作者和明确提及人可见；私密回复不会扩大父评论接收范围。"
        type="warning"
      />
      <ElInput
        v-model="body"
        :maxlength="4000"
        :rows="4"
        :placeholder="
          editingItem
            ? '修改评论内容'
            : replyingTo
              ? `回复 ${replyingTo.authorDisplayName}`
              : '发表审批评论'
        "
        show-word-limit
        type="textarea"
      />
      <input
        ref="fileInput"
        hidden
        multiple
        type="file"
        @change="uploadFiles"
      />
      <div class="upload-row">
        <ElButton :loading="uploading" plain @click="chooseFiles">
          选择附件
        </ElButton>
        <span>附件必须由当前作者上传并绑定当前审批；单文件不超过 10 MiB。</span>
      </div>
      <div v-if="attachments.length" class="pending-attachments">
        <ElTag
          v-for="attachment in attachments"
          :key="attachment.attachmentId"
          closable
          effect="plain"
          type="info"
          @close="removeAttachment(attachment.attachmentId)"
        >
          {{ attachment.fileName }} · {{ formatSize(attachment.sizeBytes) }}
        </ElTag>
      </div>
      <div class="composer-footer">
        <span>作者可在发布后 {{ options?.editWindowMinutes || 15 }} 分钟内编辑或删除。</span>
        <ElButton
          :disabled="uploading"
          :loading="submitting"
          type="primary"
          @click="submitComment"
        >
          {{ editingItem ? '保存修改' : replyingTo ? '发布回复' : '发布评论' }}
        </ElButton>
      </div>
    </div>

    <ElDialog
      :model-value="Boolean(deleteTarget)"
      title="删除评论"
      width="480px"
      @close="deleteTarget = undefined"
    >
      <ElAlert
        :closable="false"
        title="删除后正文显示稳定墓碑，历史正文、提及和附件证据不会物理删除。"
        type="warning"
      />
      <ElInput
        v-model="deleteReason"
        class="dialog-input"
        :maxlength="2000"
        :rows="4"
        placeholder="填写删除原因"
        show-word-limit
        type="textarea"
      />
      <template #footer>
        <ElButton @click="deleteTarget = undefined">取消</ElButton>
        <ElButton :loading="deleting" type="danger" @click="confirmDelete">
          确认删除
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      :model-value="Boolean(revisionTarget)"
      :title="`评论修订历史 · ${revisionTarget?.authorDisplayName || ''}`"
      width="680px"
      @close="revisionTarget = undefined"
    >
      <ElSkeleton v-if="revisionLoading" :rows="4" animated />
      <div v-else class="revision-list">
        <article v-for="revision in revisions" :key="revision.revisionNumber">
          <div class="revision-meta">
            <strong>修订 {{ revision.revisionNumber }} · {{ revisionLabel(revision) }}</strong>
            <span>{{ formatDate(revision.occurredAt) }}</span>
          </div>
          <p>{{ revision.body }}</p>
          <div class="revision-detail">
            <span>范围：{{ revision.visibility }}</span>
            <span>操作人：{{ revision.operatorId }}</span>
            <span v-if="revision.reason">原因：{{ revision.reason }}</span>
          </div>
          <div v-if="revision.mentionedUsers.length" class="tag-row">
            <ElTag
              v-for="user in revision.mentionedUsers"
              :key="user.userId"
              effect="plain"
              size="small"
            >
              @{{ user.displayName }}
            </ElTag>
          </div>
        </article>
      </div>
    </ElDialog>
  </section>
</template>

<style scoped>
.comment-thread {
  display: grid;
  gap: 16px;
}

.comment-header,
.comment-meta,
.composer-footer,
.tag-row,
.attachment-row,
.upload-row,
.comment-actions,
.revision-meta,
.revision-detail {
  display: flex;
  align-items: center;
  gap: 10px;
}

.comment-header,
.comment-meta,
.composer-footer,
.revision-meta {
  justify-content: space-between;
}

.comment-author {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.comment-header h3 {
  margin: 0 0 4px;
  font-size: 16px;
}

.comment-header span,
.comment-meta > span,
.hint,
.composer-footer span,
.upload-row span,
.candidate-id,
.revision-meta span,
.revision-detail {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.comment-list,
.revision-list {
  display: grid;
  gap: 12px;
}

.comment-item,
.revision-list article {
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-blank);
}

.comment-item {
  scroll-margin-top: 24px;
}

.comment-item--reply {
  margin-left: 36px;
  border-left: 3px solid var(--el-color-primary-light-5);
}

.comment-item--private {
  border-color: var(--el-color-warning-light-5);
}

.comment-item--deleted {
  background: var(--el-fill-color-light);
}

.comment-item--focus {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
}

.comment-item p,
.revision-list p {
  margin: 10px 0;
  color: var(--el-text-color-primary);
  line-height: 1.7;
  white-space: pre-wrap;
}

.tombstone {
  color: var(--el-text-color-secondary) !important;
  font-style: italic;
}

.delete-evidence {
  padding: 8px 10px;
  border-radius: 6px;
  color: var(--el-color-danger-dark-2);
  background: var(--el-color-danger-light-9);
  font-size: 13px;
}

.tag-row,
.attachment-row,
.pending-attachments,
.revision-detail {
  display: flex;
  flex-wrap: wrap;
  margin-top: 8px;
}

.comment-actions {
  justify-content: flex-end;
  margin-top: 6px;
}

.composer {
  display: grid;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.composer-grid {
  display: grid;
  grid-template-columns: minmax(220px, 0.7fr) minmax(320px, 1.3fr);
  gap: 12px;
}

.pending-attachments {
  gap: 8px;
}

.candidate-id {
  margin-left: 12px;
}

.dialog-input {
  margin-top: 16px;
}

@media (max-width: 800px) {
  .composer-grid {
    grid-template-columns: 1fr;
  }

  .comment-item--reply {
    margin-left: 18px;
  }
}
</style>
