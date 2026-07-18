<script lang="ts" setup>
import type {
  FormDefinition,
  FormPage,
  FormSummary,
  ValidationResult,
} from '#/api/approval/form-types';

import { onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElCol,
  ElEmpty,
  ElInput,
  ElMessage,
  ElPagination,
  ElRow,
  ElSkeleton,
  ElTabPane,
  ElTabs,
  ElTag,
} from 'element-plus';

import {
  findForm,
  findForms,
  findPurchasePaymentTemplate,
  publishForm,
  validateForm,
} from '#/api/approval/forms';
import ApprovalFormRenderer from '#/components/approval/ApprovalFormRenderer.vue';

const pageSize = 20;
const loading = ref(false);
const validating = ref(false);
const publishing = ref(false);
const keyword = ref('');
const currentPage = ref(1);
const formPage = ref<FormPage>(emptyPage());
const editorText = ref('');
const schema = ref<FormDefinition>();
const validation = ref<ValidationResult>();
const previewModel = ref<Record<string, unknown>>({});
const activeEditorTab = ref('schema');
const editorError = ref('');

const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  dateStyle: 'medium',
  timeStyle: 'short',
});

function emptyPage(): FormPage {
  return { hasMore: false, items: [], limit: pageSize, offset: 0, total: 0 };
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '动态表单请求失败';
}

function formatDate(value: string) {
  return dateFormatter.format(new Date(value));
}

function setEditor(definition: FormDefinition) {
  schema.value = definition;
  editorText.value = JSON.stringify(definition, null, 2);
  validation.value = undefined;
  previewModel.value = {};
  editorError.value = '';
}

function parseEditor() {
  try {
    const parsed = JSON.parse(editorText.value) as FormDefinition;
    schema.value = parsed;
    editorError.value = '';
    return parsed;
  } catch (error) {
    editorError.value = errorMessage(error);
    throw error;
  }
}

async function loadForms() {
  loading.value = true;
  try {
    formPage.value = await findForms(
      keyword.value,
      pageSize,
      (currentPage.value - 1) * pageSize,
    );
  } catch (error) {
    formPage.value = emptyPage();
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

async function loadTemplate() {
  try {
    setEditor(await findPurchasePaymentTemplate());
    activeEditorTab.value = 'schema';
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function openPublished(item: FormSummary) {
  try {
    const result = await findForm(item.formKey, item.version);
    setEditor(result.definition);
    validation.value = {
      contentHash: result.contentHash,
      fieldCount: result.definition.fields.length,
      formKey: result.definition.formKey,
      valid: true,
      version: result.definition.version,
      warnings: [],
    };
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function validateEditor() {
  validating.value = true;
  try {
    validation.value = await validateForm(parseEditor());
    ElMessage.success('Form Schema 校验通过');
  } catch (error) {
    validation.value = undefined;
    ElMessage.error(errorMessage(error));
  } finally {
    validating.value = false;
  }
}

async function publishEditor() {
  publishing.value = true;
  try {
    const result = await publishForm(parseEditor());
    ElMessage.success(
      result.replayedExistingVersion ? '该版本已发布，已返回原结果' : '表单版本发布成功',
    );
    validation.value = {
      contentHash: result.contentHash,
      fieldCount: result.fieldCount,
      formKey: result.formKey,
      valid: true,
      version: result.version,
      warnings: [],
    };
    await loadForms();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    publishing.value = false;
  }
}

async function searchForms() {
  currentPage.value = 1;
  await loadForms();
}

async function changePage(page: number) {
  currentPage.value = page;
  await loadForms();
}

onMounted(async () => {
  await Promise.all([loadForms(), loadTemplate()]);
});
</script>

<template>
  <Page title="动态表单">
    <ElRow :gutter="16">
      <ElCol :lg="8" :md="9" :sm="24">
        <ElCard shadow="never">
          <template #header>
            <div class="panel-header">
              <div>
                <strong>已发布表单</strong>
                <span>版本发布后不可修改</span>
              </div>
              <ElButton :loading="loading" text @click="loadForms">刷新</ElButton>
            </div>
          </template>

          <div class="search-row">
            <ElInput
              v-model="keyword"
              clearable
              placeholder="搜索表单名称或 Key"
              @keyup.enter="searchForms"
            />
            <ElButton type="primary" @click="searchForms">搜索</ElButton>
          </div>

          <ElSkeleton v-if="loading" :rows="5" animated />
          <ElEmpty v-else-if="formPage.items.length === 0" description="暂无已发布表单" />
          <div v-else class="form-list">
            <button
              v-for="item in formPage.items"
              :key="`${item.formKey}:${item.version}`"
              class="form-list-item"
              type="button"
              @click="openPublished(item)"
            >
              <div class="item-title">
                <strong>{{ item.name }}</strong>
                <ElTag effect="plain">v{{ item.version }}</ElTag>
              </div>
              <span>{{ item.formKey }} · {{ item.fieldCount }} 个字段</span>
              <span>{{ item.publishedBy }} · {{ formatDate(item.publishedAt) }}</span>
            </button>
          </div>

          <ElPagination
            v-if="formPage.total > pageSize"
            :current-page="currentPage"
            :page-size="pageSize"
            :total="formPage.total"
            background
            layout="prev, pager, next"
            @current-change="changePage"
          />
        </ElCard>
      </ElCol>

      <ElCol :lg="16" :md="15" :sm="24">
        <ElCard shadow="never">
          <template #header>
            <div class="panel-header">
              <div>
                <strong>Schema 编辑与预览</strong>
                <span>服务端是唯一校验来源</span>
              </div>
              <div class="action-row">
                <ElButton @click="loadTemplate">采购付款模板</ElButton>
                <ElButton :loading="validating" @click="validateEditor">校验</ElButton>
                <ElButton :loading="publishing" type="primary" @click="publishEditor">
                  发布版本
                </ElButton>
              </div>
            </div>
          </template>

          <ElAlert
            v-if="validation"
            :closable="false"
            :title="`校验通过 · ${validation.fieldCount} 个字段 · ${validation.contentHash.slice(0, 12)}`"
            type="success"
          />
          <ElAlert
            v-if="editorError"
            :closable="false"
            :title="editorError"
            type="error"
          />

          <ElTabs v-model="activeEditorTab">
            <ElTabPane label="Schema JSON" name="schema">
              <ElInput
                v-model="editorText"
                :rows="30"
                spellcheck="false"
                type="textarea"
                @blur="parseEditor"
              />
            </ElTabPane>
            <ElTabPane label="运行时预览" name="preview">
              <ApprovalFormRenderer
                v-if="schema"
                v-model="previewModel"
                :schema="schema"
              />
              <ElEmpty v-else description="请先加载或编辑 Form Schema" />
            </ElTabPane>
          </ElTabs>
        </ElCard>
      </ElCol>
    </ElRow>
  </Page>
</template>

<style scoped>
.panel-header,
.action-row,
.search-row,
.item-title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.panel-header,
.item-title {
  justify-content: space-between;
}

.panel-header > div:first-child,
.form-list-item {
  display: grid;
  gap: 5px;
}

.panel-header span,
.form-list-item span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.search-row {
  margin-bottom: 16px;
}

.form-list {
  display: grid;
  gap: 10px;
  margin-bottom: 16px;
}

.form-list-item {
  width: 100%;
  padding: 14px;
  color: inherit;
  text-align: left;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-blank);
  cursor: pointer;
}

.form-list-item:hover {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
}

:deep(.el-textarea__inner) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

@media (max-width: 768px) {
  .action-row,
  .panel-header {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
