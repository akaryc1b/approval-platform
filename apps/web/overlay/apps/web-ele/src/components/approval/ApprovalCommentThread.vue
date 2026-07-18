<script lang="ts" setup>
import type {
  ApprovalAttachment,
  ApprovalCommentItem,
  ApprovalCommentPage,
  CommentOptions,
} from '#/api/approval/comments';

import { nextTick, ref, watch } from 'vue';

import {
  ElAlert,
  ElButton,
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
  downloadApprovalAttachment,
  findApprovalComments,
  findCommentOptions,
  uploadApprovalAttachment,
} from '#/api/approval/comments';

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
const replyingTo = ref<ApprovalCommentItem>();
const fileInput = ref<HTMLInputElement>();

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  month: '2-digit',
  year: 'numeric',
});

function emptyPage(): ApprovalCommentPage {
  return { hasMore: false, items: [], limit: 100, offset: 0, total: 0 };
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
    await focusComment();
  } catch (error) {
    comments.value = emptyPage();
    options.value = undefined;
    errorText.value = errorMessage(error);
  } finally {
    loading.value = false;
  }
}

function startReply(item: ApprovalCommentItem) {
  if (item.reply) return;
  replyingTo.value = item;
  const candidate = options.value?.mentionCandidates.find(
    (user) => user.userId === item.authorId,
  );
  if (candidate && !mentionIds.value.includes(candidate.userId)) {
    mentionIds.value = [...mentionIds.value, candidate.userId];
  }
}

function cancelReply() {
  replyingTo.value = undefined;
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
  const content = body.value.trim();
  if (!content) {
    ElMessage.warning('请填写评论内容');
    return;
  }
  submitting.value = true;
  try {
    await createApprovalComment(
      props.instanceId,
      replyingTo.value?.commentId,
      content,
      mentionIds.value,
      attachments.value.map((item) => item.attachmentId),
    );
    body.value = '';
    mentionIds.value = [];
    attachments.value = [];
    replyingTo.value = undefined;
    ElMessage.success('评论已发布');
    await loadComments();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    submitting.value = false;
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
          'comment-item--focus': item.commentId === focusCommentId,
          'comment-item--reply': item.reply,
        }"
      >
        <div class="comment-meta">
          <div>
            <strong>{{ item.authorDisplayName }}</strong>
            <span v-if="item.replyToAuthorDisplayName">
              回复 {{ item.replyToAuthorDisplayName }}
            </span>
          </div>
          <span>{{ formatDate(item.createdAt) }}</span>
        </div>
        <p>{{ item.body }}</p>
        <div v-if="item.mentionedUsers.length" class="tag-row">
          <span class="hint">提及</span>
          <ElTag
            v-for="user in item.mentionedUsers"
            :key="user.userId"
            effect="plain"
            size="small"
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
        <div v-if="!item.reply" class="comment-actions">
          <ElButton link type="primary" @click="startReply(item)">回复</ElButton>
        </div>
      </article>
    </div>

    <ElAlert
      v-if="comments.hasMore"
      :closable="false"
      title="当前仅展示前 100 条评论"
      type="info"
    />

    <div class="composer">
      <ElAlert
        v-if="replyingTo"
        :closable="false"
        type="info"
      >
        <template #title>
          正在回复 {{ replyingTo.authorDisplayName }}
          <ElButton link type="primary" @click="cancelReply">取消回复</ElButton>
        </template>
      </ElAlert>
      <ElSelect
        v-model="mentionIds"
        collapse-tags
        collapse-tags-tooltip
        filterable
        multiple
        placeholder="@ 提及流程参与人（可选）"
      >
        <ElOption
          v-for="user in options?.mentionCandidates || []"
          :key="user.userId"
          :label="user.displayName"
          :value="user.userId"
        >
          <span>{{ user.displayName }}</span>
          <span class="candidate-id">{{ user.userId }}</span>
        </ElOption>
      </ElSelect>
      <ElInput
        v-model="body"
        :maxlength="4000"
        :rows="4"
        :placeholder="replyingTo ? `回复 ${replyingTo.authorDisplayName}` : '发表审批评论'"
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
        <span>单文件不超过 10 MiB，最多 20 个</span>
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
        <span>上传后的文件会在评论发布时绑定到当前审批。</span>
        <ElButton
          :disabled="uploading"
          :loading="submitting"
          type="primary"
          @click="submitComment"
        >
          {{ replyingTo ? '发布回复' : '发布评论' }}
        </ElButton>
      </div>
    </div>
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
.comment-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.comment-header,
.comment-meta,
.composer-footer {
  justify-content: space-between;
}

.comment-meta > div {
  display: flex;
  align-items: center;
  gap: 8px;
}

.comment-header h3 {
  margin: 0 0 4px;
  font-size: 16px;
}

.comment-header span,
.comment-meta span,
.hint,
.composer-footer span,
.upload-row span,
.candidate-id {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.comment-list {
  display: grid;
  gap: 12px;
}

.comment-item {
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-blank);
  scroll-margin-top: 24px;
}

.comment-item--reply {
  margin-left: 36px;
  border-left: 3px solid var(--el-color-primary-light-5);
}

.comment-item--focus {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
}

.comment-item p {
  margin: 10px 0;
  color: var(--el-text-color-primary);
  line-height: 1.7;
  white-space: pre-wrap;
}

.tag-row,
.attachment-row,
.pending-attachments {
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

.pending-attachments {
  gap: 8px;
}

.candidate-id {
  margin-left: 12px;
}
</style>
