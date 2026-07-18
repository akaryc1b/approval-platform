<script lang="ts" setup>
import type {
  FieldAccess,
  FormDefinition,
  FormPage,
  FormSummary,
  UiSchemaDefinition,
  ValidationResult,
} from '#/api/approval/form-types';

import { computed, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElCol,
  ElEmpty,
  ElInput,
  ElMessage,
  ElOption,
  ElPagination,
  ElRow,
  ElSelect,
  ElSkeleton,
  ElTabPane,
  ElTabs,
  ElTag,
} from 'element-plus';

import {
  findForm,
  findForms,
  findLatestUiSchema,
  findPurchasePaymentTemplate,
  findPurchasePaymentUiSchemaTemplate,
  publishForm,
  publishUiSchema,
  validateForm,
  validateUiSchema,
} from '#/api/approval/forms';
import ApprovalFormRenderer from '#/components/approval/ApprovalFormRenderer.vue';

const pageSize = 20;
const loading = ref(false);
const actionLoading = ref('');
const keyword = ref('');
const currentPage = ref(1);
const formPage = ref<FormPage>(emptyPage());
const formEditor = ref('');
const uiEditor = ref('');
const schema = ref<FormDefinition>();
const uiSchema = ref<UiSchemaDefinition>();
const formValidation = ref<ValidationResult>();
const previewModel = ref<Record<string, unknown>>({});
const activeEditorTab = ref('form');
const previewContext = ref('$start');
const editorError = ref('');

const previewPermissions = computed<Record<string, FieldAccess>>(() => {
  const context = uiSchema.value?.nodePermissions.find(
    item => item.contextKey === previewContext.value,
  );
  return Object.fromEntries((context?.fields || []).map(item => [item.fieldKey, item.access]));
});

const contexts = computed(() => uiSchema.value?.nodePermissions.map(item => item.contextKey) || []);

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

function setFormEditor(definition: FormDefinition) {
  schema.value = definition;
  formEditor.value = JSON.stringify(definition, null, 2);
  formValidation.value = undefined;
  previewModel.value = {};
}

function setUiEditor(definition: UiSchemaDefinition) {
  uiSchema.value = definition;
  uiEditor.value = JSON.stringify(definition, null, 2);
  previewContext.value = definition.nodePermissions[0]?.contextKey || '$start';
}

function parseForm() {
  const parsed = JSON.parse(formEditor.value) as FormDefinition;
  schema.value = parsed;
  editorError.value = '';
  return parsed;
}

function parseUiSchema() {
  const parsed = JSON.parse(uiEditor.value) as UiSchemaDefinition;
  uiSchema.value = parsed;
  editorError.value = '';
  return parsed;
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

async function loadTemplates() {
  try {
    const [form, ui] = await Promise.all([
      findPurchasePaymentTemplate(),
      findPurchasePaymentUiSchemaTemplate(),
    ]);
    setFormEditor(form);
    setUiEditor(ui);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function openPublished(item: FormSummary) {
  actionLoading.value = 'open';
  try {
    const result = await findForm(item.formKey, item.version);
    setFormEditor(result.definition);
    try {
      const ui = await findLatestUiSchema(item.formKey, item.version);
      setUiEditor(ui.definition);
    } catch {
      uiSchema.value = undefined;
      uiEditor.value = '';
    }
    formValidation.value = {
      contentHash: result.contentHash,
      fieldCount: result.definition.fields.length,
      formKey: result.definition.formKey,
      valid: true,
      version: result.definition.version,
      warnings: [],
    };
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    actionLoading.value = '';
  }
}

async function validateFormEditor() {
  actionLoading.value = 'validate-form';
  try {
    formValidation.value = await validateForm(parseForm());
    ElMessage.success('Form Schema 校验通过');
  } catch (error) {
    editorError.value = errorMessage(error);
    ElMessage.error(editorError.value);
  } finally {
    actionLoading.value = '';
  }
}

async function publishFormEditor() {
  actionLoading.value = 'publish-form';
  try {
    const result = await publishForm(parseForm());
    ElMessage.success(result.replayedExistingVersion ? '该 Form Schema 已发布' : 'Form Schema 发布成功');
    await loadForms();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    actionLoading.value = '';
  }
}

async function validateUiEditor() {
  actionLoading.value = 'validate-ui';
  try {
    const result = await validateUiSchema(parseUiSchema());
    ElMessage.success(`UI Schema 校验通过 · ${result.contentHash.slice(0, 12)}`);
  } catch (error) {
    editorError.value = errorMessage(error);
    ElMessage.error(editorError.value);
  } finally {
    actionLoading.value = '';
  }
}

async function publishUiEditor() {
  actionLoading.value = 'publish-ui';
  try {
    const result = await publishUiSchema(parseUiSchema());
    ElMessage.success(result.replayedExistingVersion ? '该 UI Schema 已发布' : 'UI Schema 发布成功');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    actionLoading.value = '';
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
  await Promise.all([loadForms(), loadTemplates()]);
});
</script>

<template>
  <Page title="动态表单">
    <ElRow :gutter="16">
      <ElCol :lg="7" :md="9" :sm="24">
        <ElCard shadow="never">
          <template #header>
            <div class="panel-header">
              <div><strong>已发布 Form Schema</strong><span>版本发布后不可修改</span></div>
              <ElButton :loading="loading" text @click="loadForms">刷新</ElButton>
            </div>
          </template>
          <div class="search-row">
            <ElInput
              v-model="keyword"
              clearable
              placeholder="搜索名称或 Key"
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
                <strong>{{ item.name }}</strong><ElTag effect="plain">v{{ item.version }}</ElTag>
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

      <ElCol :lg="17" :md="15" :sm="24">
        <ElCard shadow="never">
          <template #header>
            <div class="panel-header">
              <div><strong>Schema 工作台</strong><span>Form 与 UI Schema 分别不可变版本化</span></div>
              <ElButton @click="loadTemplates">采购付款模板</ElButton>
            </div>
          </template>
          <ElAlert v-if="editorError" :closable="false" :title="editorError" type="error" />
          <ElTabs v-model="activeEditorTab">
            <ElTabPane label="Form Schema" name="form">
              <div class="editor-actions">
                <ElButton
                  :loading="actionLoading === 'validate-form'"
                  @click="validateFormEditor"
                >校验</ElButton>
                <ElButton
                  :loading="actionLoading === 'publish-form'"
                  type="primary"
                  @click="publishFormEditor"
                >发布 Form 版本</ElButton>
              </div>
              <ElInput v-model="formEditor" :rows="28" spellcheck="false" type="textarea" />
            </ElTabPane>
            <ElTabPane label="UI Schema" name="ui">
              <div class="editor-actions">
                <ElButton
                  :loading="actionLoading === 'validate-ui'"
                  @click="validateUiEditor"
                >校验</ElButton>
                <ElButton
                  :loading="actionLoading === 'publish-ui'"
                  type="primary"
                  @click="publishUiEditor"
                >发布 UI 版本</ElButton>
              </div>
              <ElInput v-model="uiEditor" :rows="28" spellcheck="false" type="textarea" />
            </ElTabPane>
            <ElTabPane label="跨节点预览" name="preview">
              <div class="preview-toolbar">
                <ElSelect v-model="previewContext" placeholder="选择节点">
                  <ElOption v-for="context in contexts" :key="context" :label="context" :value="context" />
                </ElSelect>
                <ElTag effect="plain">{{ previewContext }}</ElTag>
              </div>
              <ApprovalFormRenderer
                v-if="schema"
                v-model="previewModel"
                :field-permissions="previewPermissions"
                :schema="schema"
                :ui-schema="uiSchema"
              />
              <ElEmpty v-else description="请先加载 Form Schema" />
            </ElTabPane>
          </ElTabs>
        </ElCard>
      </ElCol>
    </ElRow>
  </Page>
</template>

<style scoped>
.panel-header,.search-row,.item-title,.editor-actions,.preview-toolbar{display:flex;align-items:center;gap:10px}.panel-header,.item-title{justify-content:space-between}.panel-header>div:first-child,.form-list-item{display:grid;gap:5px}.panel-header span,.form-list-item span{color:var(--el-text-color-secondary);font-size:12px}.search-row,.editor-actions,.preview-toolbar{margin-bottom:16px}.form-list{display:grid;gap:10px;margin-bottom:16px}.form-list-item{width:100%;padding:14px;color:inherit;text-align:left;border:1px solid var(--el-border-color-lighter);border-radius:var(--el-border-radius-base);background:var(--el-fill-color-blank);cursor:pointer}.form-list-item:hover{border-color:var(--el-color-primary-light-5);background:var(--el-color-primary-light-9)}:deep(.el-textarea__inner){font-family:ui-monospace,SFMono-Regular,Menlo,monospace}@media(max-width:768px){.panel-header{align-items:stretch;flex-direction:column}}
</style>
