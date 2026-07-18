<script lang="ts" setup>
import type {
  ApprovalCommentPage,
  CommentOptions,
} from '#/api/approval/comments';

import { ref, watch } from 'vue';

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
  findApprovalComments,
  findCommentOptions,
} from '#/api/approval/comments';

const props = defineProps<{ instanceId: string }>();

const loading = ref(false);
const submitting = ref(false);
const errorText = ref('');
const comments = ref<ApprovalCommentPage>(emptyPage());
const options = ref<CommentOptions>();
const body = ref('');
const mentionIds = ref<string[]>([]);
const attachmentText = ref('');

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

function attachmentIds() {
  return Array.from(new Set(
    attachmentText.value
      .split(/[\n,;，；]/)
      .map((item) => item.trim())
      .filter(Boolean),
  )).slice(0, 20);
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
  } catch (error) {
    comments.value = emptyPage();
    options.value = undefined;
    errorText.value = errorMessage(error);
  } finally {
    loading.value = false;
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
      content,
      mentionIds.value,
      attachmentIds(),
    );
    body.value = '';
    mentionIds.value = [];
    attachmentText.value = '';
    ElMessage.success('评论已发布');
    await loadComments();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    submitting.value = false;
  }
}

watch(() => props.instanceId, loadComments, { immediate: true });
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
        :key="item.commentId"
        class="comment-item"
      >
        <div class="comment-meta">
          <strong>{{ item.authorDisplayName }}</strong>
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
        <div v-if="item.attachmentIds.length" class="tag-row">
          <span class="hint">附件</span>
          <ElTag
            v-for="attachment in item.attachmentIds"
            :key="attachment"
            effect="plain"
            size="small"
            type="info"
          >
            {{ attachment }}
          </ElTag>
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
        placeholder="发表审批评论"
        show-word-limit
        type="textarea"
      />
      <ElInput
        v-model="attachmentText"
        placeholder="附件引用 ID（可选，逗号或换行分隔，最多 20 个）"
        type="textarea"
        :rows="2"
      />
      <div class="composer-footer">
        <span>附件上传能力接入后，这里将替换为文件选择器。</span>
        <ElButton :loading="submitting" type="primary" @click="submitComment">
          发布评论
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
.tag-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.comment-header,
.comment-meta,
.composer-footer {
  justify-content: space-between;
}

.comment-header h3 {
  margin: 0 0 4px;
  font-size: 16px;
}

.comment-header span,
.comment-meta span,
.hint,
.composer-footer span,
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
}

.comment-item p {
  margin: 10px 0;
  color: var(--el-text-color-primary);
  line-height: 1.7;
  white-space: pre-wrap;
}

.tag-row {
  flex-wrap: wrap;
  margin-top: 8px;
}

.composer {
  display: grid;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.candidate-id {
  margin-left: 12px;
}
</style>
