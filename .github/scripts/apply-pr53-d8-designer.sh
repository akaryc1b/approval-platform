#!/usr/bin/env bash
set -euo pipefail

cat > apps/web/overlay/apps/web-ele/src/views/approval/forms/form-designer-sections.mjs <<'JS'
export function normalizeFormSections(sections) {
  const visit = (items) => {
    items.forEach((section, index) => {
      section.order = index;
      section.columns ??= 1;
      section.collapsible ??= true;
      section.readonlySummary ??= false;
      section.visibility ??= { mode: 'ALWAYS' };
      section.children ??= [];
      section.fields ??= [];
      visit(section.children);
    });
  };
  visit(sections);
  return sections;
}

export function flattenFormSections(sections, depth = 0, parent = undefined) {
  const result = [];
  sections.forEach((section, index) => {
    result.push({ section, depth, parent, siblings: sections, index });
    result.push(...flattenFormSections(section.children || [], depth + 1, section));
  });
  return result;
}

export function findFormSection(sections, key) {
  return flattenFormSections(sections).find(entry => entry.section.key === key);
}

export function moveFormSection(sections, key, direction) {
  const entry = findFormSection(sections, key);
  if (!entry) return false;
  const target = entry.index + direction;
  if (target < 0 || target >= entry.siblings.length) return false;
  const [section] = entry.siblings.splice(entry.index, 1);
  entry.siblings.splice(target, 0, section);
  normalizeFormSections(sections);
  return true;
}

export function removeFormSection(sections, key) {
  const entry = findFormSection(sections, key);
  if (!entry) return false;
  entry.siblings.splice(entry.index, 1);
  normalizeFormSections(sections);
  return true;
}

export function collectFormFieldOrder(sections) {
  return flattenFormSections(sections)
    .flatMap(entry => entry.section.fields.map(layout => layout.fieldKey));
}

export function countSectionFields(section) {
  return section.fields.length
    + (section.children || []).reduce((count, child) => count + countSectionFields(child), 0);
}
JS

cat > apps/web/overlay/apps/web-ele/src/views/approval/forms/form-designer-sections.d.mts <<'TS'
import type { UiSection } from '#/api/approval/form-types';

export interface FormSectionEntry {
  depth: number;
  index: number;
  parent?: UiSection;
  section: UiSection;
  siblings: UiSection[];
}

export function normalizeFormSections(sections: UiSection[]): UiSection[];
export function flattenFormSections(
  sections: UiSection[],
  depth?: number,
  parent?: UiSection,
): FormSectionEntry[];
export function findFormSection(
  sections: UiSection[],
  key: string,
): FormSectionEntry | undefined;
export function moveFormSection(
  sections: UiSection[],
  key: string,
  direction: -1 | 1,
): boolean;
export function removeFormSection(sections: UiSection[], key: string): boolean;
export function collectFormFieldOrder(sections: UiSection[]): string[];
export function countSectionFields(section: UiSection): number;
TS

cat > scripts/tests/form-designer-sections.test.mjs <<'JS'
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  collectFormFieldOrder,
  countSectionFields,
  findFormSection,
  flattenFormSections,
  moveFormSection,
  normalizeFormSections,
  removeFormSection,
} from '../../apps/web/overlay/apps/web-ele/src/views/approval/forms/form-designer-sections.mjs';

function section(key, fields = [], children = []) {
  return { key, title: key, collapsed: false, fields, children };
}

test('normalizes and flattens nested sections deterministically', () => {
  const sections = [section('root', [{ fieldKey: 'a' }], [section('child', [{ fieldKey: 'b' }])])];
  normalizeFormSections(sections);
  assert.deepEqual(flattenFormSections(sections).map(entry => [entry.section.key, entry.depth]), [
    ['root', 0],
    ['child', 1],
  ]);
  assert.equal(sections[0].order, 0);
  assert.equal(sections[0].columns, 1);
  assert.deepEqual(sections[0].visibility, { mode: 'ALWAYS' });
  assert.deepEqual(collectFormFieldOrder(sections), ['a', 'b']);
  assert.equal(countSectionFields(sections[0]), 2);
});

test('moves and removes sections within the correct sibling container', () => {
  const sections = [section('root', [], [section('first'), section('second')])];
  normalizeFormSections(sections);
  assert.equal(moveFormSection(sections, 'second', -1), true);
  assert.deepEqual(sections[0].children.map(item => item.key), ['second', 'first']);
  assert.equal(findFormSection(sections, 'second').parent.key, 'root');
  assert.equal(removeFormSection(sections, 'second'), true);
  assert.deepEqual(sections[0].children.map(item => item.key), ['first']);
});
JS

python3 <<'PY'
from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected D8 designer block not found in {path}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

package = Path("package.json")
replace_once(
    package,
    '    "web:test:form-renderer": "node --test scripts/tests/approval-form-renderer-protocol.test.mjs",',
    '    "web:test:form-renderer": "node --test scripts/tests/approval-form-renderer-protocol.test.mjs",\n'
    '    "web:test:form-designer": "node --test scripts/tests/form-designer-sections.test.mjs",',
)

path = Path("apps/web/overlay/apps/web-ele/src/views/approval/forms/index.vue")
text = path.read_text(encoding="utf-8")

text = text.replace(
    "  FieldAccess,\n  FormDefinition,",
    "  FieldAccess,\n  FormComponentType,\n  FormDefinition,",
    1,
)
text = text.replace(
    "  FormPackagePublishResult,\n  UiNodePermissions,",
    "  FormPackagePublishResult,\n  UiFieldLayout,\n  UiNodePermissions,",
    1,
)
text = text.replace(
    "import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';",
    "import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';\n"
    "import { onBeforeRouteLeave } from 'vue-router';",
    1,
)
text = text.replace(
    "  ElMessage,\n  ElOption,",
    "  ElMessage,\n  ElMessageBox,\n  ElOption,",
    1,
)
text = text.replace(
    "import ApprovalFormRenderer from '#/components/approval/ApprovalFormRenderer.vue';",
    "import ApprovalFormRenderer from '#/components/approval/ApprovalFormRenderer.vue';\n"
    "import {\n"
    "  collectFormFieldOrder,\n"
    "  countSectionFields,\n"
    "  findFormSection,\n"
    "  flattenFormSections,\n"
    "  moveFormSection,\n"
    "  normalizeFormSections,\n"
    "  removeFormSection,\n"
    "} from './form-designer-sections.mjs';",
    1,
)

palette_marker = "const statusOptions: Array<{ label: string; value: FormDesignDraftStatus }> = ["
component_block = '''const componentLabels: Record<string, string> = {
  ATTACHMENT: '附件',
  BOOLEAN: '布尔开关',
  BUSINESS_REFERENCE: '业务对象引用',
  DATE: '日期',
  DATETIME: '日期时间',
  DEPARTMENT_SELECTOR: '部门选择器',
  MONEY: '金额',
  NUMBER: '数字',
  SELECT: '下拉选择',
  TEXT: '文本',
  TEXTAREA: '多行文本',
  USER_SELECTOR: '用户选择器',
};
const registeredComponentTypes = new Set(Object.keys(componentLabels));

''' + palette_marker
if palette_marker not in text:
    raise SystemExit("component marker not found")
text = text.replace(palette_marker, component_block, 1)

text = text.replace(
    "const undoStack = ref<string[]>([]);\nconst lastSnapshot = ref('');",
    "const undoStack = ref<string[]>([]);\nconst redoStack = ref<string[]>([]);\nconst lastSnapshot = ref('');",
    1,
)
text = text.replace(
    "const draggingField = ref<{ index: number; sectionKey: string }>();\nconst draggingSection = ref<number>();",
    "const draggingField = ref<{ index: number; sectionKey: string }>();",
    1,
)

old_selected = '''const selectedSection = computed(() => {
  if (!selection.value || !uiSchemaDefinition.value) return undefined;
  return uiSchemaDefinition.value.sections.find(item => item.key === selection.value?.sectionKey);
});'''
new_selected = '''const sectionEntries = computed(() => flattenFormSections(
  uiSchemaDefinition.value?.sections || [],
));
const selectedSectionEntry = computed(() => {
  if (!selection.value || !uiSchemaDefinition.value) return undefined;
  return findFormSection(uiSchemaDefinition.value.sections, selection.value.sectionKey);
});
const selectedSection = computed(() => selectedSectionEntry.value?.section);'''
if old_selected not in text:
    raise SystemExit("selected section block not found")
text = text.replace(old_selected, new_selected, 1)

text = text.replace(
    "  if (ui.sections.length === 0) {\n    ui.sections.push({ collapsed: false, fields: [], key: 'default', title: '表单内容' });\n  }",
    "  if (ui.sections.length === 0) {\n"
    "    ui.sections.push(newSection('表单内容', 'default'));\n"
    "  }\n"
    "  normalizeFormSections(ui.sections);",
    1,
)
text = text.replace(
    "  undoStack.value = [];\n  lastSnapshot.value = serialize();",
    "  undoStack.value = [];\n  redoStack.value = [];\n  lastSnapshot.value = serialize();",
    1,
)
text = text.replace(
    "  lastSnapshot.value = snapshot;\n  dirty.value = true;",
    "  lastSnapshot.value = snapshot;\n  redoStack.value = [];\n  dirty.value = true;",
    1,
)

old_open = '''async function openDraft(draftId: string) {
  try {
    const result = await findFormDesignDraft(draftId);
    await hydrate(result);
  } catch (error) {
    ElMessage.error(message(error));
  }
}'''
new_open = '''async function confirmDiscardChanges(action: string) {
  if (!dirty.value && !saving.value && !conflictMessage.value) return true;
  try {
    await ElMessageBox.confirm(
      `当前草稿有未保存、保存中或冲突状态。确认${action}并放弃本地状态吗？`,
      '未保存修改保护',
      { confirmButtonText: '确认继续', cancelButtonText: '留在当前草稿', type: 'warning' },
    );
    return true;
  } catch {
    return false;
  }
}

async function openDraft(draftId: string, force = false) {
  if (!force && draft.value?.draftId !== draftId && !(await confirmDiscardChanges('切换草稿'))) {
    return false;
  }
  try {
    const result = await findFormDesignDraft(draftId);
    await hydrate(result);
    return true;
  } catch (error) {
    ElMessage.error(message(error));
    return false;
  }
}'''
if old_open not in text:
    raise SystemExit("openDraft block not found")
text = text.replace(old_open, new_open, 1)

text = text.replace(
    "function openCreateDialog(source: 'BLANK' | 'PURCHASE_PAYMENT_TEMPLATE' = 'BLANK') {",
    "async function openCreateDialog(source: 'BLANK' | 'PURCHASE_PAYMENT_TEMPLATE' = 'BLANK') {\n"
    "  if (!(await confirmDiscardChanges('新建草稿'))) return;",
    1,
)

old_undo = '''async function undo() {
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
}'''
new_undo = '''async function restoreSnapshot(snapshot: string) {
  historyMuted.value = true;
  working.value = JSON.parse(snapshot) as DesignerDocument;
  normalizeFormSections(working.value.uiSchemaDefinition.sections);
  lastSnapshot.value = serialize();
  dirty.value = true;
  serverPreview.value = undefined;
  validation.value = undefined;
  await nextTick();
  historyMuted.value = false;
  scheduleAutosave();
}

async function undo() {
  const snapshot = undoStack.value.pop();
  if (!snapshot) return;
  const current = serialize();
  if (current) redoStack.value.push(current);
  await restoreSnapshot(snapshot);
}

async function redo() {
  const snapshot = redoStack.value.pop();
  if (!snapshot) return;
  const current = serialize();
  if (current) undoStack.value.push(current);
  await restoreSnapshot(snapshot);
}'''
if old_undo not in text:
    raise SystemExit("undo block not found")
text = text.replace(old_undo, new_undo, 1)

start = text.index("function ensureSection()")
end = text.index("function addContext()")
section_functions = r'''function newSection(title = '新分组', requestedKey?: string): UiSection {
  return {
    children: [],
    collapsed: false,
    collapsible: true,
    columns: 1,
    fields: [],
    key: requestedKey || uniqueSectionKey(),
    order: 0,
    readonlySummary: false,
    title,
    visibility: { mode: 'ALWAYS' },
  };
}

function ensureSection() {
  if (!uiSchemaDefinition.value) return undefined;
  let section = selectedSection.value;
  if (!section) {
    section = newSection();
    uiSchemaDefinition.value.sections.push(section);
    normalizeFormSections(uiSchemaDefinition.value.sections);
  }
  return section;
}

function uniqueSectionKey(prefix = 'section') {
  const existing = new Set(sectionEntries.value.map(entry => entry.section.key));
  const safePrefix = prefix.replace(/[^A-Za-z0-9_$.-]/g, '_') || 'section';
  let index = 1;
  let candidate = `${safePrefix}_${index}`;
  while (existing.has(candidate)) candidate = `${safePrefix}_${++index}`;
  return candidate;
}

function addSection() {
  if (!uiSchemaDefinition.value || !editable.value) return;
  const section = newSection();
  uiSchemaDefinition.value.sections.push(section);
  normalizeFormSections(uiSchemaDefinition.value.sections);
  selection.value = { kind: 'section', sectionKey: section.key };
}

function addChildSection() {
  if (!selectedSection.value || !selectedSectionEntry.value || !uiSchemaDefinition.value || !editable.value) return;
  if (selectedSectionEntry.value.depth >= 3) {
    ElMessage.warning('区块最多支持 4 层嵌套');
    return;
  }
  const child = newSection('子分组');
  selectedSection.value.children ||= [];
  selectedSection.value.children.push(child);
  normalizeFormSections(uiSchemaDefinition.value.sections);
  selection.value = { kind: 'section', sectionKey: child.key };
}

function cloneSectionTree(source: UiSection): UiSection {
  if (!formDefinition.value || !uiSchemaDefinition.value) return deepClone(source);
  const copy = deepClone(source);
  copy.key = uniqueSectionKey(source.key.replace(/_\d+$/, ''));
  copy.title = `${source.title} 副本`;
  copy.fields = copy.fields.map((layout) => {
    const sourceField = formDefinition.value?.fields.find(field => field.key === layout.fieldKey);
    if (!sourceField) return layout;
    const fieldCopy = deepClone(sourceField);
    fieldCopy.key = uniqueKey(sourceField.key.replace(/_\d+$/, ''));
    fieldCopy.label = `${sourceField.label} 副本`;
    formDefinition.value?.fields.push(fieldCopy);
    uiSchemaDefinition.value?.nodePermissions.forEach((context) => {
      const permission = context.fields.find(item => item.fieldKey === sourceField.key);
      context.fields.push(permission
        ? { ...deepClone(permission), fieldKey: fieldCopy.key }
        : { access: 'READONLY', fieldKey: fieldCopy.key, requiredOverride: 'INHERIT' });
    });
    return { ...layout, fieldKey: fieldCopy.key };
  });
  copy.children = (copy.children || []).map(child => cloneSectionTree(child));
  return copy;
}

function copySelectedSection() {
  if (!selectedSection.value || !selectedSectionEntry.value || !uiSchemaDefinition.value || !editable.value) return;
  const copy = cloneSectionTree(selectedSection.value);
  selectedSectionEntry.value.siblings.splice(selectedSectionEntry.value.index + 1, 0, copy);
  normalizeFormSections(uiSchemaDefinition.value.sections);
  reorderDefinitionFields();
  selection.value = { kind: 'section', sectionKey: copy.key };
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
  sectionEntries.value.forEach((entry) => {
    entry.section.fields = entry.section.fields.filter(item => item.fieldKey !== key);
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
  if (countSectionFields(selectedSection.value) > 0) {
    ElMessage.warning('请先移动或删除分组及其子分组内字段');
    return;
  }
  if (sectionEntries.value.length === 1) {
    ElMessage.warning('至少保留一个分组');
    return;
  }
  removeFormSection(uiSchemaDefinition.value.sections, selectedSection.value.key);
  const first = sectionEntries.value[0]?.section;
  selection.value = first ? { kind: 'section', sectionKey: first.key } : null;
}

function moveSelectedSection(direction: -1 | 1) {
  if (!selectedSection.value || !uiSchemaDefinition.value || !editable.value) return;
  moveFormSection(uiSchemaDefinition.value.sections, selectedSection.value.key, direction);
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
  const sourceSection = findFormSection(
    uiSchemaDefinition.value.sections,
    draggingField.value.sectionKey,
  )?.section;
  const targetSection = findFormSection(
    uiSchemaDefinition.value.sections,
    targetSectionKey,
  )?.section;
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
  const order = collectFormFieldOrder(uiSchemaDefinition.value.sections);
  const byKey = new Map(formDefinition.value.fields.map(item => [item.key, item]));
  const ordered = order.map(key => byKey.get(key)).filter((item): item is FormField => Boolean(item));
  formDefinition.value.fields.forEach((field) => {
    if (!order.includes(field.key)) ordered.push(field);
  });
  formDefinition.value.fields = ordered;
}

function componentOptions(field: FormField) {
  const types: FormComponentType[] = field.type === 'TEXT'
    ? ['TEXT', 'USER_SELECTOR', 'DEPARTMENT_SELECTOR', 'BUSINESS_REFERENCE']
    : [field.type];
  return types.map(value => ({ label: componentLabels[value] || value, value }));
}

function selectedComponentType() {
  if (!selectedField.value || !selectedLayout.value) return '';
  return selectedLayout.value.component?.componentType || selectedField.value.type;
}

function setSelectedComponentType(value: string) {
  if (!selectedField.value || !selectedLayout.value) return;
  if (value === selectedField.value.type) {
    selectedLayout.value.component = undefined;
    return;
  }
  selectedLayout.value.component = {
    componentType: value,
    componentVersion: 1,
    fallbackRenderer: 'READONLY_TEXT',
    properties: {},
  };
}

function selectedComponentSupported() {
  if (!selectedField.value || !selectedLayout.value) return true;
  const type = selectedComponentType();
  return registeredComponentTypes.has(type)
    && (selectedLayout.value.component?.componentVersion || 1) === 1
    && componentOptions(selectedField.value).some(option => option.value === type);
}

'''
text = text[:start] + section_functions + text[end:]

mount_old = '''onMounted(async () => {
  await loadDrafts();
  const first = draftPage.value.items[0];
  if (first) await openDraft(first.draftId);
});

onBeforeUnmount(() => {
  if (autosaveTimer) clearTimeout(autosaveTimer);
});'''
mount_new = '''function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value && !saving.value && !conflictMessage.value) return;
  event.preventDefault();
  event.returnValue = '';
}

onBeforeRouteLeave(() => confirmDiscardChanges('离开表单设计器'));

onMounted(async () => {
  window.addEventListener('beforeunload', handleBeforeUnload);
  await loadDrafts();
  const first = draftPage.value.items[0];
  if (first) await openDraft(first.draftId, true);
});

onBeforeUnmount(() => {
  if (autosaveTimer) clearTimeout(autosaveTimer);
  window.removeEventListener('beforeunload', handleBeforeUnload);
});'''
if mount_old not in text:
    raise SystemExit("mount block not found")
text = text.replace(mount_old, mount_new, 1)

text = text.replace(
    '''          <ElButton :disabled="undoStack.length === 0 || !editable" @click="undo">撤销</ElButton>
          <ElButton :disabled="!editable" :loading="saving" @click="saveDraft('EXPLICIT')">保存</ElButton>''',
    '''          <ElButton :disabled="undoStack.length === 0 || !editable" @click="undo">撤销</ElButton>
          <ElButton :disabled="redoStack.length === 0 || !editable" @click="redo">重做</ElButton>
          <ElButton :disabled="!editable" :loading="saving" @click="saveDraft('EXPLICIT')">保存</ElButton>''',
    1,
)

canvas_start = text.index('          <div v-else class="canvas">')
canvas_end = text.index('          </div>\n        </ElCard>', canvas_start) + len('          </div>')
canvas = '''          <div v-else class="canvas">
            <div
              v-for="entry in sectionEntries"
              :key="entry.section.key"
              class="section-node"
              :class="{ selected: selectedSection?.key === entry.section.key && selection?.kind === 'section' }"
              :style="{ marginLeft: `${entry.depth * 18}px` }"
              @click="selectSection(entry.section.key)"
            >
              <div class="section-node-header">
                <div>
                  <span class="section-depth">L{{ entry.depth + 1 }}</span>
                  <strong>{{ entry.section.title }}</strong>
                  <small>{{ entry.section.key }}</small>
                </div>
                <div class="section-node-actions">
                  <ElTag effect="plain" size="small">{{ entry.section.fields.length }} 字段</ElTag>
                  <ElButton size="small" text @click.stop="selectSection(entry.section.key); moveSelectedSection(-1)">上移</ElButton>
                  <ElButton size="small" text @click.stop="selectSection(entry.section.key); moveSelectedSection(1)">下移</ElButton>
                  <ElButton size="small" text @click.stop="selectSection(entry.section.key); copySelectedSection()">复制</ElButton>
                  <ElButton size="small" text @click.stop="selectSection(entry.section.key); addChildSection()">添加子区块</ElButton>
                </div>
              </div>
              <p v-if="entry.section.helpText" class="node-help">{{ entry.section.helpText }}</p>
              <div class="section-badges">
                <ElTag size="small" type="info">{{ entry.section.columns || 1 }} 列</ElTag>
                <ElTag v-if="entry.section.readonlySummary" size="small" type="warning">只读摘要</ElTag>
                <ElTag v-if="entry.section.visibility?.mode !== 'ALWAYS'" size="small" type="success">条件显示</ElTag>
              </div>
              <div class="field-grid">
                <div
                  v-for="(layout, fieldIndex) in entry.section.fields"
                  :key="layout.fieldKey"
                  class="field-node"
                  :class="{ selected: selection?.kind === 'field' && selection.fieldKey === layout.fieldKey }"
                  :style="{ gridColumn: layout.span >= 24 ? '1 / -1' : 'auto' }"
                  draggable="true"
                  @click.stop="selectField(entry.section.key, layout.fieldKey)"
                  @dragover.prevent
                  @dragstart.stop="startFieldDrag(entry.section.key, fieldIndex, $event)"
                  @drop.stop="dropField(entry.section.key, fieldIndex)"
                >
                  <div class="field-node-title">
                    <span class="drag-handle">⋮⋮</span>
                    <div><strong>{{ working.formDefinition.fields.find(item => item.key === layout.fieldKey)?.label }}</strong><small>{{ layout.fieldKey }}</small></div>
                    <ElTag effect="plain" size="small">{{ layout.component?.componentType || fieldTypeLabel(working.formDefinition.fields.find(item => item.key === layout.fieldKey)?.type || 'TEXT') }}</ElTag>
                  </div>
                  <span class="field-placeholder">{{ layout.placeholder || '未设置占位提示' }}</span>
                </div>
                <button
                  class="field-drop-zone"
                  type="button"
                  @dragover.prevent
                  @drop.stop="dropField(entry.section.key, entry.section.fields.length)"
                >拖到此处追加字段</button>
              </div>
            </div>
          </div>'''
text = text[:canvas_start] + canvas + text[canvas_end:]

old_section_form = '''              <ElForm v-else-if="selectedSection && selection?.kind === 'section'" label-position="top">
                <ElFormItem label="分组标题"><ElInput v-model="selectedSection.title" :disabled="!editable" /></ElFormItem>
                <ElFormItem label="分组 Key"><ElInput :model-value="selectedSection.key" disabled /></ElFormItem>
                <ElFormItem label="帮助说明"><ElInput v-model="selectedSection.helpText" :disabled="!editable" :rows="3" type="textarea" /></ElFormItem>
                <ElFormItem label="默认折叠"><ElSwitch v-model="selectedSection.collapsed" :disabled="!editable" /></ElFormItem>
                <ElButton :disabled="!editable" type="danger" plain @click="deleteSelectedSection">删除空分组</ElButton>
              </ElForm>'''
new_section_form = '''              <ElForm v-else-if="selectedSection && selection?.kind === 'section'" label-position="top">
                <div class="property-actions">
                  <ElButton :disabled="!editable" @click="copySelectedSection">复制区块</ElButton>
                  <ElButton :disabled="!editable || (selectedSectionEntry?.depth || 0) >= 3" @click="addChildSection">添加子区块</ElButton>
                  <ElButton :disabled="!editable" type="danger" plain @click="deleteSelectedSection">删除空区块</ElButton>
                </div>
                <ElFormItem label="分组标题"><ElInput v-model="selectedSection.title" :disabled="!editable" /></ElFormItem>
                <ElFormItem label="稳定 Section ID"><ElInput :model-value="selectedSection.key" disabled /></ElFormItem>
                <ElFormItem label="帮助说明"><ElInput v-model="selectedSection.helpText" :disabled="!editable" :rows="3" type="textarea" /></ElFormItem>
                <ElFormItem label="列数">
                  <ElSelect v-model="selectedSection.columns" :disabled="!editable">
                    <ElOption label="单列" :value="1" /><ElOption label="双列" :value="2" />
                    <ElOption label="三列" :value="3" /><ElOption label="四列" :value="4" />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem label="允许折叠"><ElSwitch v-model="selectedSection.collapsible" :disabled="!editable" /></ElFormItem>
                <ElFormItem label="默认折叠"><ElSwitch v-model="selectedSection.collapsed" :disabled="!editable || selectedSection.collapsible === false" /></ElFormItem>
                <ElFormItem label="只读摘要区块"><ElSwitch v-model="selectedSection.readonlySummary" :disabled="!editable" /></ElFormItem>
                <ElDivider content-position="left">受控条件显示</ElDivider>
                <ElFormItem label="显示规则">
                  <ElSelect v-model="selectedSection.visibility!.mode" :disabled="!editable">
                    <ElOption label="始终显示" value="ALWAYS" />
                    <ElOption label="字段等于固定值" value="FIELD_EQUALS" />
                    <ElOption label="字段非空" value="FIELD_NOT_EMPTY" />
                  </ElSelect>
                </ElFormItem>
                <template v-if="selectedSection.visibility?.mode !== 'ALWAYS'">
                  <ElFormItem label="条件字段">
                    <ElSelect v-model="selectedSection.visibility!.fieldKey" :disabled="!editable" filterable>
                      <ElOption v-for="field in formDefinition?.fields || []" :key="field.key" :label="`${field.label} · ${field.key}`" :value="field.key" />
                    </ElSelect>
                  </ElFormItem>
                  <ElFormItem v-if="selectedSection.visibility?.mode === 'FIELD_EQUALS'" label="期望值">
                    <ElInput v-model="selectedSection.visibility!.expectedValue" :disabled="!editable" />
                  </ElFormItem>
                </template>
              </ElForm>'''
if old_section_form not in text:
    raise SystemExit("section property form not found")
text = text.replace(old_section_form, new_section_form, 1)

field_type_marker = '''                <ElFormItem label="字段类型">
                  <ElSelect :model-value="selectedField.type" disabled>
                    <ElOption v-for="item in palette" :key="item.type" :label="item.label" :value="item.type" />
                  </ElSelect>
                </ElFormItem>'''
component_form = field_type_marker + '''
                <ElDivider content-position="left">白名单组件</ElDivider>
                <ElFormItem label="组件类型">
                  <ElSelect
                    :model-value="selectedComponentType()"
                    :disabled="!editable"
                    @update:model-value="setSelectedComponentType"
                  >
                    <ElOption v-for="item in componentOptions(selectedField)" :key="item.value" :label="item.label" :value="item.value" />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem v-if="selectedLayout.component" label="历史版本安全 fallback">
                  <ElSelect v-model="selectedLayout.component.fallbackRenderer" :disabled="!editable">
                    <ElOption label="只读文本" value="READONLY_TEXT" /><ElOption label="只读 JSON" value="READONLY_JSON" />
                  </ElSelect>
                </ElFormItem>
                <ElAlert
                  v-if="!selectedComponentSupported()"
                  :closable="false"
                  show-icon
                  title="当前组件或版本未在宿主白名单注册；发布前服务端将阻断，历史运行时仅使用安全只读 fallback。"
                  type="error"
                />'''
if field_type_marker not in text:
    raise SystemExit("field type marker not found")
text = text.replace(field_type_marker, component_form, 1)

text = text.replace(
    ".section-node-header,.field-node-title{display:flex;align-items:center;justify-content:space-between;gap:10px}",
    ".section-node-header,.field-node-title{display:flex;align-items:center;justify-content:space-between;gap:10px}.section-node-actions,.section-badges{display:flex;align-items:center;flex-wrap:wrap;gap:6px}.section-depth{display:inline-grid;place-items:center;min-width:24px;height:24px;border-radius:999px;background:var(--el-color-primary-light-9);color:var(--el-color-primary);font-size:11px;font-weight:700}",
    1,
)

path.write_text(text, encoding="utf-8")
PY

rm -f .github/scripts/apply-pr53-d8-designer.sh
