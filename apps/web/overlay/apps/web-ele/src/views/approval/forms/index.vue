<script lang="ts" setup>
import type {
  FieldAccess,
  FormDefinition,
  FormDesignDraft,
  FormDesignDraftPage,
  FormDesignDraftStatus,
  FormDesignPreviewResult,
  FormDesignValidationReport,
  FormField,
  FormFieldType,
  FormPackagePublishResult,
  UiNodePermissions,
  UiSchemaDefinition,
  UiSection,
} from '#/api/approval/form-types';

import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElCol,
  ElDialog,
  ElDivider,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElOption,
  ElRow,
  ElSelect,
  ElSkeleton,
  ElSwitch,
  ElTabPane,
  ElTabs,
  ElTag,
} from 'element-plus';

import {
  createFormDesignDraft,
  findFormDesignDraft,
  findFormDesignDrafts,
  FormDesignApiError,
  previewFormDesignDraft,
  publishFormDesignDraft,
  updateFormDesignDraft,
  validateFormDesignDraft,
} from '#/api/approval/form-design';
import ApprovalFormRenderer from '#/components/approval/ApprovalFormRenderer.vue';

type Selection =
  | { kind: 'field'; fieldKey: string; sectionKey: string }
  | { kind: 'section'; sectionKey: string }
  | null;

interface DesignerDocument {
  formDefinition: FormDefinition;
  name: string;
  uiSchemaDefinition: UiSchemaDefinition;
}

interface PaletteItem {
  description: string;
  label: string;
  type: FormFieldType;
}

const palette: PaletteItem[] = [
  { description: '单行文字', label: '文本', type: 'TEXT' },
  { description: '多行说明', label: '多行文本', type: 'TEXTAREA' },
  { description: '普通数值', label: '数字', type: 'NUMBER' },
  { description: '金额与精度', label: '金额', type: 'MONEY' },
  { description: '年月日', label: '日期', type: 'DATE' },
  { description: '日期和时间', label: '日期时间', type: 'DATETIME' },
  { description: '是或否', label: '开关', type: 'BOOLEAN' },
  { description: '静态单选/多选', label: '下拉选择', type: 'SELECT' },
  { description: '一个或多个文件', label: '附件', type: 'ATTACHMENT' },
];

const statusOptions: Array<{ label: string; value: FormDesignDraftStatus }> = [
  { label: '草稿', value: 'DRAFT' },
  { label: '已校验', value: 'VALIDATED' },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '已归档', value: 'ARCHIVED' },
];

const draftPage = ref<FormDesignDraftPage>(emptyPage());
const draftsLoading = ref(false);
const keyword = ref('');
const statusFilter = ref<FormDesignDraftStatus>();
const draft = ref<FormDesignDraft>();
const working = ref<DesignerDocument>();
const selection = ref<Selection>(null);
const selectedRightTab = ref('properties');
const dirty = ref(false);
const saving = ref(false);
const conflictMessage = ref('');
const validation = ref<FormDesignValidationReport>();
const serverPreview = ref<FormDesignPreviewResult>();
const previewModel = ref<Record<string, unknown>>({});
const previewContext = ref('$start');
const previewVisible = ref(false);
const createVisible = ref(false);
const publishVisible = ref(false);
const publicationVisible = ref(false);
const publication = ref<FormPackagePublishResult>();
const packageVersion = ref(1);
const newContextKey = ref('');
const createInput = ref({
  formKey: '',
  formVersion: 1,
  name: '',
  source: 'BLANK' as 'BLANK' | 'PURCHASE_PAYMENT_TEMPLATE',
  uiSchemaVersion: 1,
});
const undoStack = ref<string[]>([]);
const lastSnapshot = ref('');
const historyMuted = ref(false);
const draggingField = ref<{ index: number; sectionKey: string }>();
const draggingSection = ref<number>();
let autosaveTimer: ReturnType<typeof setTimeout> | undefined;

const editable = computed(() => Boolean(
  draft.value && ['DRAFT', 'VALIDATED'].includes(draft.value.status),
));
const formDefinition = computed(() => working.value?.formDefinition);
const uiSchemaDefinition = computed(() => working.value?.uiSchemaDefinition);
const selectedSection = computed(() => {
  if (!selection.value || !uiSchemaDefinition.value) return undefined;
  return uiSchemaDefinition.value.sections.find(item => item.key === selection.value?.sectionKey);
});
const selectedField = computed(() => {
  const selected = selection.value;
  if (!selected || selected.kind !== 'field' || !formDefinition.value) return undefined;
  return formDefinition.value.fields.find(item => item.key === selected.fieldKey);
});
const selectedLayout = computed(() => {
  const selected = selection.value;
  if (!selected || selected.kind !== 'field') return undefined;
  return selectedSection.value?.fields.find(item => item.fieldKey === selected.fieldKey);
});
const selectedDefault = computed(() => selectedField.value?.defaultValue);
const contexts = computed(() => uiSchemaDefinition.value?.nodePermissions.map(item => item.contextKey) || []);
const selectedPermissions = computed(() => uiSchemaDefinition.value?.nodePermissions.find(
  item => item.contextKey === previewContext.value,
));
const permissionRows = computed(() => {
  const fields = formDefinition.value?.fields || [];
  const permissions = selectedPermissions.value?.fields || [];
  return fields.map(field => ({
    field,
    permission: permissions.find(item => item.fieldKey === field.key),
  }));
});
const localPermissions = computed<Record<string, FieldAccess>>(() => Object.fromEntries(
  (selectedPermissions.value?.fields || []).map(item => [item.fieldKey, item.access]),
));
const localRequired = computed<Record<string, boolean>>(() => Object.fromEntries(
  (formDefinition.value?.fields || []).map(field => {
    const override = selectedPermissions.value?.fields.find(item => item.fieldKey === field.key)
      ?.requiredOverride || 'INHERIT';
    return [field.key, override === 'REQUIRED' || (override === 'INHERIT' && field.required)];
  }),
));
const previewDefinition = computed(() => serverPreview.value?.definition || formDefinition.value);
const previewUiSchema = computed(() => serverPreview.value?.uiSchema || uiSchemaDefinition.value);
const previewPermissions = computed(() => serverPreview.value?.fieldPermissions || localPermissions.value);
const previewRequired = computed(() => serverPreview.value?.requiredFields || localRequired.value);
const saveState = computed(() => {
  if (conflictMessage.value) return '保存冲突';
  if (saving.value) return '正在保存';
  if (dirty.value) return '有未保存修改';
  return draft.value ? `已保存 · r${draft.value.revision}` : '未选择草稿';
});

function emptyPage(): FormDesignDraftPage {
  return { hasMore: false, items: [], limit: 50, offset: 0, total: 0 };
}

function deepClone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function serialize(value = working.value) {
  return value ? JSON.stringify(value) : '';
}

function message(error: unknown) {
  return error instanceof Error ? error.message : '表单设计请求失败';
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(value));
}

function statusLabel(status: FormDesignDraftStatus) {
  return {
    ARCHIVED: '已归档',
    DRAFT: '草稿',
    PUBLISHED: '已发布',
    VALIDATED: '已校验',
  }[status];
}

function statusType(status: FormDesignDraftStatus) {
  return status === 'PUBLISHED' ? 'success' : status === 'VALIDATED' ? 'primary' :
    status === 'ARCHIVED' ? 'info' : 'warning';
}

function fieldTypeLabel(type: FormFieldType) {
  return palette.find(item => item.type === type)?.label || type;
}

function normalizeDocument(source: FormDesignDraft): DesignerDocument {
  const form = deepClone(source.formDefinition);
  const ui = deepClone(source.uiSchemaDefinition);
  form.fields.forEach((field) => {
    field.constraints ||= { multiple: false };
    field.constraints.multiple ??= false;
    field.defaultValue ||= { type: 'NONE' };
    if (field.type === 'SELECT') {
      field.options ||= [
        { disabled: false, label: '选项 A', value: 'A' },
        { disabled: false, label: '选项 B', value: 'B' },
      ];
    }
  });
  if (ui.sections.length === 0) {
    ui.sections.push({ collapsed: false, fields: [], key: 'default', title: '表单内容' });
  }
  if (!ui.nodePermissions.some(item => item.contextKey === '$start')) {
    ui.nodePermissions.unshift({ contextKey: '$start', fields: [] });
  }
  ui.nodePermissions.forEach((context) => {
    form.fields.forEach((field) => {
      if (!context.fields.some(item => item.fieldKey === field.key)) {
        context.fields.push({
          access: context.contextKey === '$start' ? 'EDITABLE' : 'READONLY',
          fieldKey: field.key,
          requiredOverride: 'INHERIT',
        });
      }
    });
  });
  return { formDefinition: form, name: source.name, uiSchemaDefinition: ui };
}

async function hydrate(source: FormDesignDraft) {
  historyMuted.value = true;
  draft.value = source;
  working.value = normalizeDocument(source);
  selection.value = working.value.uiSchemaDefinition.sections[0]
    ? { kind: 'section', sectionKey: working.value.uiSchemaDefinition.sections[0].key }
    : null;
  previewContext.value = working.value.uiSchemaDefinition.nodePermissions[0]?.contextKey || '$start';
  previewModel.value = {};
  serverPreview.value = undefined;
  validation.value = undefined;
  publication.value = undefined;
  conflictMessage.value = '';
  dirty.value = false;
  undoStack.value = [];
  lastSnapshot.value = serialize();
  await nextTick();
  historyMuted.value = false;
}

function scheduleAutosave() {
  if (autosaveTimer) clearTimeout(autosaveTimer);
  if (!editable.value || conflictMessage.value) return;
  autosaveTimer = setTimeout(() => void saveDraft('AUTO_SAVE', true), 1200);
}

watch(working, (value) => {
  if (historyMuted.value || !value || !draft.value) return;
  const snapshot = serialize(value);
  if (snapshot === lastSnapshot.value) return;
  if (lastSnapshot.value) {
    undoStack.value.push(lastSnapshot.value);
    if (undoStack.value.length > 40) undoStack.value.shift();
  }
  lastSnapshot.value = snapshot;
  dirty.value = true;
  validation.value = undefined;
  serverPreview.value = undefined;
  scheduleAutosave();
}, { deep: true, flush: 'post' });

watch(previewContext, () => {
  serverPreview.value = undefined;
});

async function loadDrafts() {
  draftsLoading.value = true;
  try {
    draftPage.value = await findFormDesignDrafts(keyword.value, statusFilter.value);
  } catch (error) {
    draftPage.value = emptyPage();
    ElMessage.error(message(error));
  } finally {
    draftsLoading.value = false;
  }
}

async function openDraft(draftId: string) {
  try {
    const result = await findFormDesignDraft(draftId);
    await hydrate(result);
  } catch (error) {
    ElMessage.error(message(error));
  }
}

async function reloadCurrent() {
  if (!draft.value) return;
  await openDraft(draft.value.draftId);
  ElMessage.success('已重新加载服务端最新版本');
}

function openCreateDialog(source: 'BLANK' | 'PURCHASE_PAYMENT_TEMPLATE' = 'BLANK') {
  const suffix = Date.now().toString(36);
  createInput.value = {
    formKey: `approval-form-${suffix}`,
    formVersion: 1,
    name: source === 'BLANK' ? '新审批表单' : '采购付款表单',
    source,
    uiSchemaVersion: 1,
  };
  createVisible.value = true;
}

async function createDraft() {
  if (!createInput.value.formKey.trim() || !createInput.value.name.trim()) {
    ElMessage.warning('请填写表单名称和唯一 Key');
    return;
  }
  try {
    const result = await createFormDesignDraft({
      ...createInput.value,
      formKey: createInput.value.formKey.trim(),
      name: createInput.value.name.trim(),
    });
    createVisible.value = false;
    await loadDrafts();
    await hydrate(result);
    ElMessage.success('设计草稿已创建');
  } catch (error) {
    ElMessage.error(message(error));
  }
}

function syncBindings() {
  if (!working.value) return;
  working.value.formDefinition.name = working.value.name;
  working.value.uiSchemaDefinition.name = `${working.value.name} UI`;
  working.value.uiSchemaDefinition.formKey = working.value.formDefinition.formKey;
  working.value.uiSchemaDefinition.formVersion = working.value.formDefinition.version;
}

async function saveDraft(mode: 'AUTO_SAVE' | 'EXPLICIT', quiet = false) {
  if (!draft.value || !working.value || !editable.value || saving.value) return false;
  if (autosaveTimer) clearTimeout(autosaveTimer);
  syncBindings();
  const draftId = draft.value.draftId;
  const expectedRevision = draft.value.revision;
  const savedSnapshot = serialize();
  saving.value = true;
  try {
    const result = await updateFormDesignDraft(draftId, {
      expectedRevision,
      formDefinition: deepClone(working.value.formDefinition),
      name: working.value.name,
      saveMode: mode,
      uiSchemaDefinition: deepClone(working.value.uiSchemaDefinition),
    });
    if (draft.value?.draftId !== draftId || !working.value) return true;
    draft.value = {
      ...result,
      formDefinition: working.value.formDefinition,
      uiSchemaDefinition: working.value.uiSchemaDefinition,
    };
    conflictMessage.value = '';
    if (serialize() === savedSnapshot) {
      dirty.value = false;
    } else {
      dirty.value = true;
      scheduleAutosave();
    }
    if (!quiet) ElMessage.success(`草稿已保存 · r${result.revision}`);
    void loadDrafts();
    return true;
  } catch (error) {
    if (error instanceof FormDesignApiError && error.status === 409) {
      conflictMessage.value = `${error.message}。服务端版本已变化，请重新加载后再编辑。`;
    } else if (!quiet) {
      ElMessage.error(message(error));
    }
    return false;
  } finally {
    saving.value = false;
  }
}

async function undo() {
  const snapshot = undoStack.value.pop();
  if (!snapshot) return;
  historyMuted.value = true;
  working.value = JSON.parse(snapshot) as DesignerDocument;
  lastSnapshot.value = snapshot;
  dirty.value = true;
  serverPreview.value = undefined;
  validation.value = undefined;
  await nextTick();
  historyMuted.value = false;
  scheduleAutosave();
}

function uniqueKey(prefix: string) {
  const existing = new Set(formDefinition.value?.fields.map(item => item.key));
  let index = 1;
  let candidate = `${prefix}_${index}`;
  while (existing.has(candidate)) candidate = `${prefix}_${++index}`;
  return candidate;
}

function fieldTemplate(type: FormFieldType): FormField {
  const key = uniqueKey(type.toLowerCase());
  const constraints = { multiple: false } as FormField['constraints'];
  if (type === 'TEXT') constraints.maxLength = 128;
  if (type === 'TEXTAREA') constraints.maxLength = 2000;
  if (type === 'NUMBER') {
    constraints.minimum = 0;
    constraints.precision = 2;
  }
  if (type === 'MONEY') {
    constraints.minimum = 0;
    constraints.precision = 2;
  }
  if (type === 'ATTACHMENT') {
    constraints.minItems = 0;
    constraints.multiple = true;
  }
  const field: FormField = {
    constraints,
    defaultValue: { type: 'NONE' },
    key,
    label: fieldTypeLabel(type),
    required: false,
    type,
  };
  if (type === 'SELECT') {
    field.options = [
      { disabled: false, label: '选项 A', value: 'A' },
      { disabled: false, label: '选项 B', value: 'B' },
    ];
  }
  return field;
}

function ensureSection() {
  if (!uiSchemaDefinition.value) return undefined;
  let section = selectedSection.value;
  if (!section) {
    section = { collapsed: false, fields: [], key: uniqueSectionKey(), title: '新分组' };
    uiSchemaDefinition.value.sections.push(section);
  }
  return section;
}

function uniqueSectionKey() {
  const existing = new Set(uiSchemaDefinition.value?.sections.map(item => item.key));
  let index = 1;
  let candidate = `section_${index}`;
  while (existing.has(candidate)) candidate = `section_${++index}`;
  return candidate;
}

function addSection() {
  if (!uiSchemaDefinition.value || !editable.value) return;
  const section: UiSection = {
    collapsed: false,
    fields: [],
    key: uniqueSectionKey(),
    title: '新分组',
  };
  uiSchemaDefinition.value.sections.push(section);
  selection.value = { kind: 'section', sectionKey: section.key };
}

function addField(type: FormFieldType) {
  if (!formDefinition.value || !uiSchemaDefinition.value || !editable.value) return;
  const section = ensureSection();
  if (!section) return;
  const field = fieldTemplate(type);
  formDefinition.value.fields.push(field);
  section.fields.push({ fieldKey: field.key, span: 24 });
  uiSchemaDefinition.value.nodePermissions.forEach((context) => {
    context.fields.push({
      access: context.contextKey === '$start' ? 'EDITABLE' : 'READONLY',
      fieldKey: field.key,
      requiredOverride: 'INHERIT',
    });
  });
  selection.value = { fieldKey: field.key, kind: 'field', sectionKey: section.key };
  selectedRightTab.value = 'properties';
}

function copySelectedField() {
  if (!selectedField.value || !selectedLayout.value || !selectedSection.value ||
    !formDefinition.value || !uiSchemaDefinition.value || !editable.value) return;
  const source = selectedField.value;
  const copy = deepClone(source);
  copy.key = uniqueKey(source.key.replace(/_\d+$/, ''));
  copy.label = `${source.label} 副本`;
  const fieldIndex = formDefinition.value.fields.findIndex(item => item.key === source.key);
  formDefinition.value.fields.splice(fieldIndex + 1, 0, copy);
  const layoutIndex = selectedSection.value.fields.findIndex(item => item.fieldKey === source.key);
  selectedSection.value.fields.splice(layoutIndex + 1, 0, {
    ...deepClone(selectedLayout.value),
    fieldKey: copy.key,
  });
  uiSchemaDefinition.value.nodePermissions.forEach((context) => {
    const permission = context.fields.find(item => item.fieldKey === source.key);
    context.fields.push(permission
      ? { ...deepClone(permission), fieldKey: copy.key }
      : { access: 'READONLY', fieldKey: copy.key, requiredOverride: 'INHERIT' });
  });
  selection.value = { fieldKey: copy.key, kind: 'field', sectionKey: selectedSection.value.key };
}

function deleteSelectedField() {
  if (!selectedField.value || !formDefinition.value || !uiSchemaDefinition.value || !editable.value) return;
  const key = selectedField.value.key;
  formDefinition.value.fields = formDefinition.value.fields.filter(item => item.key !== key);
  uiSchemaDefinition.value.sections.forEach((section) => {
    section.fields = section.fields.filter(item => item.fieldKey !== key);
  });
  uiSchemaDefinition.value.nodePermissions.forEach((context) => {
    context.fields = context.fields.filter(item => item.fieldKey !== key);
  });
  selection.value = selectedSection.value
    ? { kind: 'section', sectionKey: selectedSection.value.key }
    : null;
}

function deleteSelectedSection() {
  if (!selectedSection.value || !uiSchemaDefinition.value || !editable.value) return;
  if (selectedSection.value.fields.length > 0) {
    ElMessage.warning('请先移动或删除分组内字段');
    return;
  }
  if (uiSchemaDefinition.value.sections.length === 1) {
    ElMessage.warning('至少保留一个分组');
    return;
  }
  uiSchemaDefinition.value.sections = uiSchemaDefinition.value.sections.filter(
    item => item.key !== selectedSection.value?.key,
  );
  const first = uiSchemaDefinition.value.sections[0];
  selection.value = first ? { kind: 'section', sectionKey: first.key } : null;
}

function addOption() {
  if (!selectedField.value || selectedField.value.type !== 'SELECT') return;
  selectedField.value.options ||= [];
  const index = selectedField.value.options.length + 1;
  selectedField.value.options.push({
    disabled: false,
    label: `选项 ${index}`,
    value: `OPTION_${index}`,
  });
}

function removeOption(index: number) {
  selectedField.value?.options?.splice(index, 1);
}

function setLiteralDefault(value: string) {
  if (selectedDefault.value) selectedDefault.value.literal = value;
}

function selectSection(sectionKey: string) {
  selection.value = { kind: 'section', sectionKey };
  selectedRightTab.value = 'properties';
}

function selectField(sectionKey: string, fieldKey: string) {
  selection.value = { fieldKey, kind: 'field', sectionKey };
  selectedRightTab.value = 'properties';
}

function startFieldDrag(sectionKey: string, index: number, event: DragEvent) {
  if (!editable.value) return;
  draggingField.value = { index, sectionKey };
  event.dataTransfer?.setData('text/plain', `${sectionKey}:${index}`);
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
}

function dropField(targetSectionKey: string, targetIndex: number) {
  if (!draggingField.value || !uiSchemaDefinition.value || !editable.value) return;
  const sourceSection = uiSchemaDefinition.value.sections.find(
    item => item.key === draggingField.value?.sectionKey,
  );
  const targetSection = uiSchemaDefinition.value.sections.find(item => item.key === targetSectionKey);
  if (!sourceSection || !targetSection) return;
  const [layout] = sourceSection.fields.splice(draggingField.value.index, 1);
  if (!layout) return;
  const adjustedIndex = sourceSection === targetSection && draggingField.value.index < targetIndex
    ? targetIndex - 1
    : targetIndex;
  targetSection.fields.splice(Math.max(0, adjustedIndex), 0, layout);
  draggingField.value = undefined;
  reorderDefinitionFields();
  selection.value = { fieldKey: layout.fieldKey, kind: 'field', sectionKey: targetSectionKey };
}

function reorderDefinitionFields() {
  if (!formDefinition.value || !uiSchemaDefinition.value) return;
  const order = uiSchemaDefinition.value.sections.flatMap(section => section.fields.map(item => item.fieldKey));
  const byKey = new Map(formDefinition.value.fields.map(item => [item.key, item]));
  const ordered = order.map(key => byKey.get(key)).filter((item): item is FormField => Boolean(item));
  formDefinition.value.fields.forEach((field) => {
    if (!order.includes(field.key)) ordered.push(field);
  });
  formDefinition.value.fields = ordered;
}

function startSectionDrag(index: number, event: DragEvent) {
  if (!editable.value) return;
  draggingSection.value = index;
  event.dataTransfer?.setData('text/plain', `section:${index}`);
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
}

function dropSection(targetIndex: number) {
  if (draggingSection.value == null || !uiSchemaDefinition.value || !editable.value) return;
  const [section] = uiSchemaDefinition.value.sections.splice(draggingSection.value, 1);
  if (!section) return;
  const adjusted = draggingSection.value < targetIndex ? targetIndex - 1 : targetIndex;
  uiSchemaDefinition.value.sections.splice(Math.max(0, adjusted), 0, section);
  draggingSection.value = undefined;
  reorderDefinitionFields();
}

function addContext() {
  const key = newContextKey.value.trim();
  if (!key || !uiSchemaDefinition.value || !formDefinition.value || !editable.value) return;
  if (contexts.value.includes(key)) {
    ElMessage.warning('该节点上下文已存在');
    return;
  }
  const context: UiNodePermissions = {
    contextKey: key,
    fields: formDefinition.value.fields.map(field => ({
      access: 'READONLY',
      fieldKey: field.key,
      requiredOverride: 'INHERIT',
    })),
  };
  uiSchemaDefinition.value.nodePermissions.push(context);
  previewContext.value = key;
  newContextKey.value = '';
}

function removeCurrentContext() {
  if (!uiSchemaDefinition.value || previewContext.value === '$start' || !editable.value) return;
  uiSchemaDefinition.value.nodePermissions = uiSchemaDefinition.value.nodePermissions.filter(
    item => item.contextKey !== previewContext.value,
  );
  previewContext.value = uiSchemaDefinition.value.nodePermissions[0]?.contextKey || '$start';
}

async function validateCurrent() {
  if (!draft.value) return undefined;
  const saved = dirty.value ? await saveDraft('EXPLICIT', true) : true;
  if (!saved || !draft.value) return undefined;
  try {
    const report = await validateFormDesignDraft(draft.value.draftId, draft.value.revision);
    validation.value = report;
    draft.value = { ...draft.value, revision: report.revision, status: report.status };
    if (!report.valid) {
      ElMessage.error(report.errors[0] || 'Schema 校验未通过');
      return undefined;
    }
    dirty.value = false;
    void loadDrafts();
    return report;
  } catch (error) {
    ElMessage.error(message(error));
    return undefined;
  }
}

async function openPreview() {
  if (!draft.value) return;
  const report = await validateCurrent();
  if (!report || !draft.value) return;
  try {
    serverPreview.value = await previewFormDesignDraft(draft.value.draftId, previewContext.value);
    previewModel.value = deepClone(serverPreview.value.values);
    previewVisible.value = true;
  } catch (error) {
    ElMessage.error(message(error));
  }
}

async function openPublishDialog() {
  const report = await validateCurrent();
  if (!report || !draft.value) return;
  packageVersion.value = Math.max(1, (draft.value.publishedPackageVersion || 0) + 1);
  publishVisible.value = true;
}

async function confirmPublish() {
  if (!draft.value || packageVersion.value < 1) return;
  try {
    const result = await publishFormDesignDraft(
      draft.value.draftId,
      draft.value.revision,
      packageVersion.value,
    );
    publication.value = result;
    draft.value = {
      ...draft.value,
      publishedPackageVersion: result.packageVersion,
      revision: result.draftRevision,
      status: 'PUBLISHED',
    };
    dirty.value = false;
    publishVisible.value = false;
    publicationVisible.value = true;
    await loadDrafts();
  } catch (error) {
    ElMessage.error(message(error));
  }
}

onMounted(async () => {
  await loadDrafts();
  const first = draftPage.value.items[0];
  if (first) await openDraft(first.draftId);
});

onBeforeUnmount(() => {
  if (autosaveTimer) clearTimeout(autosaveTimer);
});
</script>

<template>
  <Page title="可视化表单设计器">
    <ElCard class="designer-toolbar" shadow="never">
      <div class="toolbar-main">
        <div>
          <strong>{{ working?.name || '选择或创建设计草稿' }}</strong>
          <span v-if="draft">{{ draft.formKey }} · Form v{{ draft.formDefinition.version }} · UI v{{ draft.uiSchemaDefinition.version }}</span>
        </div>
        <div class="toolbar-actions">
          <ElTag v-if="draft" :type="statusType(draft.status)" effect="plain">{{ statusLabel(draft.status) }}</ElTag>
          <ElTag :type="conflictMessage ? 'danger' : dirty ? 'warning' : 'success'" effect="plain">{{ saveState }}</ElTag>
          <ElButton :disabled="undoStack.length === 0 || !editable" @click="undo">撤销</ElButton>
          <ElButton :disabled="!editable" :loading="saving" @click="saveDraft('EXPLICIT')">保存</ElButton>
          <ElButton :disabled="!draft" @click="openPreview">节点预览</ElButton>
          <ElButton :disabled="!editable" type="primary" @click="openPublishDialog">发布 Package</ElButton>
        </div>
      </div>
    </ElCard>

    <ElAlert
      v-if="conflictMessage"
      class="conflict-alert"
      :closable="false"
      :title="conflictMessage"
      type="error"
    >
      <template #default>
        <ElButton size="small" type="danger" @click="reloadCurrent">重新加载服务端版本</ElButton>
      </template>
    </ElAlert>

    <ElRow class="designer-grid" :gutter="14">
      <ElCol :lg="5" :md="7" :sm="24">
        <ElCard class="side-panel" shadow="never">
          <template #header>
            <div class="panel-header">
              <div><strong>设计草稿</strong><span>{{ draftPage.total }} 个</span></div>
              <ElButton type="primary" @click="openCreateDialog('BLANK')">新建</ElButton>
            </div>
          </template>
          <div class="draft-filters">
            <ElInput v-model="keyword" clearable placeholder="搜索名称或 Key" @keyup.enter="loadDrafts" />
            <ElSelect v-model="statusFilter" clearable placeholder="全部状态" @change="loadDrafts">
              <ElOption
                v-for="item in statusOptions"
                :key="item.label"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
          </div>
          <ElSkeleton v-if="draftsLoading" :rows="5" animated />
          <ElEmpty v-else-if="draftPage.items.length === 0" description="暂无设计草稿" />
          <div v-else class="draft-list">
            <button
              v-for="item in draftPage.items"
              :key="item.draftId"
              class="draft-item"
              :class="{ active: draft?.draftId === item.draftId }"
              type="button"
              @click="openDraft(item.draftId)"
            >
              <div><strong>{{ item.name }}</strong><ElTag :type="statusType(item.status)" size="small">{{ statusLabel(item.status) }}</ElTag></div>
              <span>{{ item.formKey }} · r{{ item.revision }}</span>
              <span>{{ item.updatedBy }} · {{ formatDate(item.updatedAt) }}</span>
            </button>
          </div>
          <ElDivider content-position="left">字段组件</ElDivider>
          <div class="palette-grid">
            <button
              v-for="item in palette"
              :key="item.type"
              :disabled="!editable"
              class="palette-item"
              type="button"
              @click="addField(item.type)"
            >
              <strong>{{ item.label }}</strong><span>{{ item.description }}</span>
            </button>
          </div>
          <ElButton class="template-button" :disabled="Boolean(draft)" plain @click="openCreateDialog('PURCHASE_PAYMENT_TEMPLATE')">
            从采购付款模板创建
          </ElButton>
        </ElCard>
      </ElCol>

      <ElCol :lg="12" :md="17" :sm="24">
        <ElCard class="canvas-panel" shadow="never">
          <template #header>
            <div class="panel-header">
              <div><strong>设计画布</strong><span>拖动分组和字段可调整顺序</span></div>
              <ElButton :disabled="!editable" @click="addSection">添加分组</ElButton>
            </div>
          </template>
          <ElEmpty v-if="!working" description="请从左侧创建或打开草稿" />
          <div v-else class="canvas">
            <div
              v-for="(section, sectionIndex) in working.uiSchemaDefinition.sections"
              :key="section.key"
              class="section-node"
              :class="{ selected: selectedSection?.key === section.key && selection?.kind === 'section' }"
              draggable="true"
              @click="selectSection(section.key)"
              @dragover.prevent
              @dragstart="startSectionDrag(sectionIndex, $event)"
              @drop.stop="dropSection(sectionIndex)"
            >
              <div class="section-node-header">
                <div><span class="drag-handle">⋮⋮</span><strong>{{ section.title }}</strong><small>{{ section.key }}</small></div>
                <ElTag effect="plain" size="small">{{ section.fields.length }} 字段</ElTag>
              </div>
              <p v-if="section.helpText" class="node-help">{{ section.helpText }}</p>
              <div class="field-grid">
                <div
                  v-for="(layout, fieldIndex) in section.fields"
                  :key="layout.fieldKey"
                  class="field-node"
                  :class="{ selected: selection?.kind === 'field' && selection.fieldKey === layout.fieldKey }"
                  :style="{ gridColumn: layout.span >= 24 ? '1 / -1' : 'auto' }"
                  draggable="true"
                  @click.stop="selectField(section.key, layout.fieldKey)"
                  @dragover.prevent
                  @dragstart.stop="startFieldDrag(section.key, fieldIndex, $event)"
                  @drop.stop="dropField(section.key, fieldIndex)"
                >
                  <div class="field-node-title">
                    <span class="drag-handle">⋮⋮</span>
                    <div><strong>{{ working.formDefinition.fields.find(item => item.key === layout.fieldKey)?.label }}</strong><small>{{ layout.fieldKey }}</small></div>
                    <ElTag effect="plain" size="small">{{ fieldTypeLabel(working.formDefinition.fields.find(item => item.key === layout.fieldKey)?.type || 'TEXT') }}</ElTag>
                  </div>
                  <span class="field-placeholder">{{ layout.placeholder || '未设置占位提示' }}</span>
                </div>
                <button
                  class="field-drop-zone"
                  type="button"
                  @dragover.prevent
                  @drop.stop="dropField(section.key, section.fields.length)"
                >拖到此处追加字段</button>
              </div>
            </div>
          </div>
        </ElCard>
      </ElCol>

      <ElCol :lg="7" :md="24" :sm="24">
        <ElCard class="side-panel" shadow="never">
          <template #header><strong>属性与节点权限</strong></template>
          <ElTabs v-model="selectedRightTab">
            <ElTabPane label="属性" name="properties">
              <ElEmpty v-if="!working || !selection" description="选择画布中的分组或字段" />
              <ElForm v-else-if="selectedSection && selection?.kind === 'section'" label-position="top">
                <ElFormItem label="分组标题"><ElInput v-model="selectedSection.title" :disabled="!editable" /></ElFormItem>
                <ElFormItem label="分组 Key"><ElInput :model-value="selectedSection.key" disabled /></ElFormItem>
                <ElFormItem label="帮助说明"><ElInput v-model="selectedSection.helpText" :disabled="!editable" :rows="3" type="textarea" /></ElFormItem>
                <ElFormItem label="默认折叠"><ElSwitch v-model="selectedSection.collapsed" :disabled="!editable" /></ElFormItem>
                <ElButton :disabled="!editable" type="danger" plain @click="deleteSelectedSection">删除空分组</ElButton>
              </ElForm>
              <ElForm v-else-if="selectedField && selectedLayout" label-position="top">
                <div class="property-actions">
                  <ElButton :disabled="!editable" @click="copySelectedField">复制字段</ElButton>
                  <ElButton :disabled="!editable" type="danger" plain @click="deleteSelectedField">删除</ElButton>
                </div>
                <ElFormItem label="字段标题"><ElInput v-model="selectedField.label" :disabled="!editable" /></ElFormItem>
                <ElFormItem label="字段 Key"><ElInput :model-value="selectedField.key" disabled /></ElFormItem>
                <ElFormItem label="字段类型">
                  <ElSelect :model-value="selectedField.type" disabled>
                    <ElOption v-for="item in palette" :key="item.type" :label="item.label" :value="item.type" />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem label="必填"><ElSwitch v-model="selectedField.required" :disabled="!editable" /></ElFormItem>
                <ElFormItem label="占位提示"><ElInput v-model="selectedLayout.placeholder" :disabled="!editable" /></ElFormItem>
                <ElFormItem label="字段帮助"><ElInput v-model="selectedLayout.helpText" :disabled="!editable" /></ElFormItem>
                <ElFormItem label="布局宽度">
                  <ElSelect v-model="selectedLayout.span" :disabled="!editable">
                    <ElOption label="整行" :value="24" /><ElOption label="半行" :value="12" />
                    <ElOption label="三分之一" :value="8" /><ElOption label="四分之一" :value="6" />
                  </ElSelect>
                </ElFormItem>
                <template v-if="['TEXT', 'TEXTAREA'].includes(selectedField.type)">
                  <ElFormItem label="最大长度"><ElInputNumber v-model="selectedField.constraints.maxLength" :disabled="!editable" :min="1" :max="10000" /></ElFormItem>
                </template>
                <template v-if="['MONEY', 'NUMBER'].includes(selectedField.type)">
                  <ElFormItem label="最小值"><ElInputNumber v-model="selectedField.constraints.minimum" :disabled="!editable" /></ElFormItem>
                  <ElFormItem label="小数位"><ElInputNumber v-model="selectedField.constraints.precision" :disabled="!editable" :min="0" :max="8" /></ElFormItem>
                </template>
                <template v-if="['ATTACHMENT', 'SELECT'].includes(selectedField.type)">
                  <ElFormItem label="允许多选"><ElSwitch v-model="selectedField.constraints.multiple" :disabled="!editable" /></ElFormItem>
                </template>
                <ElFormItem v-if="selectedField.type === 'ATTACHMENT'" label="最少文件数">
                  <ElInputNumber v-model="selectedField.constraints.minItems" :disabled="!editable" :min="0" :max="20" />
                </ElFormItem>
                <template v-if="selectedField.type === 'SELECT'">
                  <ElDivider content-position="left">静态选项</ElDivider>
                  <div v-for="(option, index) in selectedField.options || []" :key="index" class="option-row">
                    <ElInput v-model="option.label" :disabled="!editable" placeholder="显示名称" />
                    <ElInput v-model="option.value" :disabled="!editable" placeholder="值" />
                    <ElSwitch v-model="option.disabled" :disabled="!editable" />
                    <ElButton :disabled="!editable" text type="danger" @click="removeOption(index)">删除</ElButton>
                  </div>
                  <ElButton :disabled="!editable" plain @click="addOption">添加选项</ElButton>
                </template>
                <template v-if="selectedDefault">
                  <ElDivider content-position="left">安全默认值</ElDivider>
                  <ElFormItem label="默认值类型">
                    <ElSelect v-model="selectedDefault.type" :disabled="!editable">
                      <ElOption label="无默认值" value="NONE" /><ElOption label="固定值" value="LITERAL" />
                      <ElOption label="当前用户" value="CURRENT_USER" /><ElOption label="当前日期" value="CURRENT_DATE" />
                      <ElOption label="当前日期时间" value="CURRENT_DATETIME" />
                    </ElSelect>
                  </ElFormItem>
                  <ElFormItem v-if="selectedDefault.type === 'LITERAL'" label="固定值">
                    <ElInput
                      :model-value="String(selectedDefault.literal ?? '')"
                      :disabled="!editable"
                      @update:model-value="setLiteralDefault"
                    />
                  </ElFormItem>
                </template>
              </ElForm>
            </ElTabPane>

            <ElTabPane label="节点权限" name="permissions">
              <div v-if="working" class="permission-panel">
                <div class="context-toolbar">
                  <ElSelect v-model="previewContext" placeholder="选择上下文">
                    <ElOption v-for="context in contexts" :key="context" :label="context" :value="context" />
                  </ElSelect>
                  <ElButton :disabled="previewContext === '$start' || !editable" type="danger" text @click="removeCurrentContext">删除节点</ElButton>
                </div>
                <div class="context-add">
                  <ElInput v-model="newContextKey" :disabled="!editable" placeholder="例如 task:manager-approve" @keyup.enter="addContext" />
                  <ElButton :disabled="!editable" @click="addContext">添加</ElButton>
                </div>
                <div class="permission-head"><span>字段</span><span>访问</span><span>必填覆盖</span></div>
                <div v-for="row in permissionRows" :key="row.field.key" class="permission-row">
                  <div><strong>{{ row.field.label }}</strong><small>{{ row.field.key }}</small></div>
                  <ElSelect v-if="row.permission" v-model="row.permission.access" :disabled="!editable">
                    <ElOption label="可编辑" value="EDITABLE" /><ElOption label="只读" value="READONLY" /><ElOption label="隐藏" value="HIDDEN" />
                  </ElSelect>
                  <ElSelect v-if="row.permission" v-model="row.permission.requiredOverride" :disabled="!editable">
                    <ElOption label="继承" value="INHERIT" /><ElOption label="强制必填" value="REQUIRED" /><ElOption label="强制可选" value="OPTIONAL" />
                  </ElSelect>
                </div>
              </div>
              <ElEmpty v-else description="请先打开草稿" />
            </ElTabPane>
          </ElTabs>
        </ElCard>
      </ElCol>
    </ElRow>

    <ElDialog v-model="createVisible" title="创建表单设计草稿" width="520px">
      <ElForm label-position="top">
        <ElFormItem label="创建方式">
          <ElSelect v-model="createInput.source">
            <ElOption label="空白表单" value="BLANK" /><ElOption label="采购付款模板" value="PURCHASE_PAYMENT_TEMPLATE" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="表单名称"><ElInput v-model="createInput.name" /></ElFormItem>
        <ElFormItem label="唯一 Key"><ElInput v-model="createInput.formKey" /></ElFormItem>
        <ElRow :gutter="12">
          <ElCol :span="12"><ElFormItem label="Form 版本"><ElInputNumber v-model="createInput.formVersion" :min="1" /></ElFormItem></ElCol>
          <ElCol :span="12"><ElFormItem label="UI 版本"><ElInputNumber v-model="createInput.uiSchemaVersion" :min="1" /></ElFormItem></ElCol>
        </ElRow>
      </ElForm>
      <template #footer><ElButton @click="createVisible = false">取消</ElButton><ElButton type="primary" @click="createDraft">创建并打开</ElButton></template>
    </ElDialog>

    <ElDialog v-model="previewVisible" title="服务端节点预览" width="760px">
      <div class="preview-meta">
        <ElSelect v-model="previewContext" @change="openPreview">
          <ElOption v-for="context in contexts" :key="context" :label="context" :value="context" />
        </ElSelect>
        <ElTag v-if="serverPreview" effect="plain">Form {{ serverPreview.formHash.slice(0, 12) }}</ElTag>
        <ElTag v-if="serverPreview" effect="plain">UI {{ serverPreview.uiSchemaHash.slice(0, 12) }}</ElTag>
      </div>
      <ApprovalFormRenderer
        v-if="previewDefinition"
        v-model="previewModel"
        :field-permissions="previewPermissions"
        :required-fields="previewRequired"
        :schema="previewDefinition"
        :ui-schema="previewUiSchema"
      />
    </ElDialog>

    <ElDialog v-model="publishVisible" title="发布不可变 Form Package" width="560px">
      <ElAlert :closable="false" title="发布会原子固化当前 Form Schema、UI Schema 及内容哈希；发布后的 Package 不可修改。" type="warning" />
      <ElForm class="publish-form" label-position="top">
        <ElFormItem label="Package 版本"><ElInputNumber v-model="packageVersion" :min="1" /></ElFormItem>
        <ElFormItem label="已校验版本"><ElTag v-if="validation" type="success">r{{ validation.revision }} · {{ validation.fieldCount }} 字段 · {{ validation.sectionCount }} 分组</ElTag></ElFormItem>
      </ElForm>
      <template #footer><ElButton @click="publishVisible = false">取消</ElButton><ElButton type="primary" @click="confirmPublish">确认发布</ElButton></template>
    </ElDialog>

    <ElDialog v-model="publicationVisible" title="Form Package 发布完成" width="620px">
      <div v-if="publication" class="publication-result">
        <ElAlert :closable="false" title="不可变版本已生成，可用于后续 Approval Release Package 绑定。" type="success" />
        <div><span>Package</span><strong>{{ publication.formKey }} / v{{ publication.packageVersion }}</strong></div>
        <div><span>Form Schema</span><code>v{{ publication.formVersion }} · {{ publication.formHash }}</code></div>
        <div><span>UI Schema</span><code>v{{ publication.uiSchemaVersion }} · {{ publication.uiSchemaHash }}</code></div>
        <div><span>Package Hash</span><code>{{ publication.packageHash }}</code></div>
      </div>
    </ElDialog>
  </Page>
</template>

<style scoped>
.designer-toolbar,.conflict-alert{margin-bottom:14px}.toolbar-main,.toolbar-actions,.panel-header,.context-toolbar,.context-add,.preview-meta,.property-actions{display:flex;align-items:center;gap:10px}.toolbar-main,.panel-header{justify-content:space-between}.toolbar-main>div:first-child,.panel-header>div:first-child,.draft-item,.field-node-title>div,.permission-row>div{display:grid;gap:4px}.toolbar-main span,.panel-header span,.draft-item span,.field-node small,.section-node small,.permission-row small{color:var(--el-text-color-secondary);font-size:12px}.designer-grid{align-items:flex-start}.side-panel,.canvas-panel{min-height:720px}.draft-filters{display:grid;grid-template-columns:minmax(0,1fr) 116px;gap:8px;margin-bottom:12px}.draft-list{display:grid;gap:8px;max-height:260px;overflow:auto}.draft-item{width:100%;padding:12px;color:inherit;text-align:left;border:1px solid var(--el-border-color-lighter);border-radius:8px;background:var(--el-fill-color-blank);cursor:pointer}.draft-item>div{display:flex;align-items:center;justify-content:space-between;gap:8px}.draft-item:hover,.draft-item.active{border-color:var(--el-color-primary-light-3);background:var(--el-color-primary-light-9)}.palette-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.palette-item{display:grid;gap:4px;padding:10px;color:inherit;text-align:left;border:1px solid var(--el-border-color-lighter);border-radius:8px;background:var(--el-fill-color-light);cursor:pointer}.palette-item span{color:var(--el-text-color-secondary);font-size:11px}.palette-item:disabled{cursor:not-allowed;opacity:.55}.template-button{width:100%;margin-top:12px}.canvas{display:grid;gap:14px}.section-node{padding:14px;border:1px solid var(--el-border-color);border-radius:10px;background:var(--el-bg-color);cursor:pointer}.section-node.selected,.field-node.selected{border-color:var(--el-color-primary);box-shadow:0 0 0 2px var(--el-color-primary-light-8)}.section-node-header,.field-node-title{display:flex;align-items:center;justify-content:space-between;gap:10px}.section-node-header>div,.field-node-title{display:flex;align-items:center;gap:8px}.field-node-title>div{flex:1}.drag-handle{color:var(--el-text-color-placeholder);cursor:grab}.node-help{margin:8px 0;color:var(--el-text-color-secondary);font-size:12px}.field-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:12px}.field-node{min-width:0;padding:12px;border:1px solid var(--el-border-color-lighter);border-radius:8px;background:var(--el-fill-color-light);cursor:pointer}.field-placeholder{display:block;margin-top:10px;color:var(--el-text-color-placeholder);font-size:12px}.field-drop-zone{grid-column:1/-1;padding:10px;color:var(--el-text-color-placeholder);border:1px dashed var(--el-border-color);border-radius:8px;background:transparent}.property-actions{justify-content:flex-end;margin-bottom:12px}.option-row{display:grid;grid-template-columns:1fr 1fr auto auto;gap:6px;margin-bottom:8px}.permission-panel{display:grid;gap:12px}.context-toolbar .el-select,.preview-meta .el-select{flex:1}.context-add{align-items:stretch}.permission-head,.permission-row{display:grid;grid-template-columns:minmax(100px,1fr) 112px 124px;align-items:center;gap:8px}.permission-head{padding:0 8px;color:var(--el-text-color-secondary);font-size:12px}.permission-row{padding:9px 8px;border:1px solid var(--el-border-color-lighter);border-radius:8px}.preview-meta{margin-bottom:16px}.publish-form{margin-top:16px}.publication-result{display:grid;gap:12px}.publication-result>div:not(.el-alert){display:grid;grid-template-columns:110px 1fr;gap:10px}.publication-result span{color:var(--el-text-color-secondary)}.publication-result code{overflow-wrap:anywhere;font-size:12px}.side-panel :deep(.el-input-number),.side-panel :deep(.el-select),.publish-form :deep(.el-input-number),.create-dialog :deep(.el-input-number){width:100%}@media(max-width:1200px){.side-panel,.canvas-panel{min-height:auto}.designer-grid>.el-col{margin-bottom:14px}}@media(max-width:768px){.toolbar-main,.panel-header{align-items:stretch;flex-direction:column}.toolbar-actions{flex-wrap:wrap}.field-grid{grid-template-columns:1fr}.field-node{grid-column:1/-1!important}.permission-head,.permission-row{grid-template-columns:1fr}.permission-head{display:none}.option-row{grid-template-columns:1fr 1fr}}
</style>
