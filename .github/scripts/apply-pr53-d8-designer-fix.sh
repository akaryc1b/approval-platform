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
path.write_text(text.replace(old, new, 1), encoding='utf-8')
PY
rm -f .github/scripts/apply-pr53-d8-designer-fix.sh
