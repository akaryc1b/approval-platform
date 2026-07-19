#!/usr/bin/env bash
set -euo pipefail

target='apps/web/overlay/apps/web-ele/src/views/approval/forms/index.vue'
sed -i '/^  UiFieldLayout,$/d' "$target"
python3 <<'PY'
from pathlib import Path

path = Path('apps/web/overlay/apps/web-ele/src/views/approval/forms/index.vue')
text = path.read_text(encoding='utf-8')
old = '''                    <ElInput v-model="selectedSection.visibility!.expectedValue" :disabled="!editable" />'''
new = '''                    <ElInput
                      :model-value="String(selectedSection.visibility?.expectedValue ?? '')"
                      :disabled="!editable"
                      @update:model-value="selectedSection.visibility!.expectedValue = $event"
                    />'''
if old not in text:
    raise SystemExit('expected visibility value editor was not found')
text = text.replace(old, new, 1)

replacements = [
    (
        "function cloneSectionTree(source: UiSection): UiSection {\n",
        "function cloneSectionTree(source: UiSection, reservedSectionKeys: Set<string>): UiSection {\n",
    ),
    (
        "  copy.key = uniqueSectionKey(source.key.replace(/_\\d+$/, ''));\n",
        "  const prefix = source.key.replace(/_\\d+$/, '') || 'section';\n"
        "  let candidateIndex = 1;\n"
        "  let candidate = `${prefix}_${candidateIndex}`;\n"
        "  while (reservedSectionKeys.has(candidate)) candidate = `${prefix}_${++candidateIndex}`;\n"
        "  reservedSectionKeys.add(candidate);\n"
        "  copy.key = candidate;\n",
    ),
    (
        "  copy.children = (copy.children || []).map(child => cloneSectionTree(child));\n",
        "  copy.children = (copy.children || []).map(child => cloneSectionTree(child, reservedSectionKeys));\n",
    ),
    (
        "  const copy = cloneSectionTree(selectedSection.value);\n",
        "  const reservedSectionKeys = new Set(sectionEntries.value.map(entry => entry.section.key));\n"
        "  const copy = cloneSectionTree(selectedSection.value, reservedSectionKeys);\n",
    ),
]
for before, after in replacements:
    if before not in text:
        raise SystemExit(f'expected designer logic block not found: {before[:80]!r}')
    text = text.replace(before, after, 1)

marker = '''function selectedComponentSupported() {
  if (!selectedField.value || !selectedLayout.value) return true;
  const type = selectedComponentType();
  return registeredComponentTypes.has(type)
    && (selectedLayout.value.component?.componentVersion || 1) === 1
    && componentOptions(selectedField.value).some(option => option.value === type);
}

'''
addition = marker + '''function setSectionVisibilityMode(mode: 'ALWAYS' | 'FIELD_EQUALS' | 'FIELD_NOT_EMPTY') {
  if (!selectedSection.value) return;
  if (mode === 'ALWAYS') {
    selectedSection.value.visibility = { mode: 'ALWAYS' };
    return;
  }
  const fieldKey = selectedSection.value.visibility?.fieldKey
    || formDefinition.value?.fields[0]?.key;
  selectedSection.value.visibility = mode === 'FIELD_EQUALS'
    ? { expectedValue: '', fieldKey, mode }
    : { fieldKey, mode };
}

'''
if marker not in text:
    raise SystemExit('selected component marker was not found')
text = text.replace(marker, addition, 1)
old_select = '''                  <ElSelect v-model="selectedSection.visibility!.mode" :disabled="!editable">
                    <ElOption label="始终显示" value="ALWAYS" />
                    <ElOption label="字段等于固定值" value="FIELD_EQUALS" />
                    <ElOption label="字段非空" value="FIELD_NOT_EMPTY" />
                  </ElSelect>'''
new_select = '''                  <ElSelect
                    :model-value="selectedSection.visibility?.mode || 'ALWAYS'"
                    :disabled="!editable"
                    @update:model-value="setSectionVisibilityMode"
                  >
                    <ElOption label="始终显示" value="ALWAYS" />
                    <ElOption label="字段等于固定值" value="FIELD_EQUALS" />
                    <ElOption label="字段非空" value="FIELD_NOT_EMPTY" />
                  </ElSelect>'''
if old_select not in text:
    raise SystemExit('section visibility selector was not found')
path.write_text(text.replace(old_select, new_select, 1), encoding='utf-8')
PY
rm -f .github/scripts/apply-pr53-d8-designer-fix.sh
