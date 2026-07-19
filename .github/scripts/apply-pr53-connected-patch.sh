#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

path = Path('apps/web/overlay/apps/web-ele/src/views/approval/designer/use-approval-designer.ts')
text = path.read_text()
old_guard = '''    const switchingDraft = draft.value && draft.value.draftId !== draftId;
    if (!options.force && switchingDraft
      && !(await confirmDiscardChanges('切换流程草稿'))) {
      return false;
    }
'''
new_guard = '''    if (!options.force && draft.value
      && !(await confirmDiscardChanges('重新加载或切换流程草稿'))) {
      return false;
    }
'''
if text.count(old_guard) != 1:
    raise SystemExit('Expected one open-draft guard')
text = text.replace(old_guard, new_guard, 1)
old_created = '      await openDraft(created.draftId);\n'
new_created = '      await openDraft(created.draftId, { force: true });\n'
if text.count(old_created) != 1:
    raise SystemExit('Expected one created-draft open call')
path.write_text(text.replace(old_created, new_created, 1))
PY

rm -f .github/pr53-connected.patch .github/scripts/apply-pr53-connected-patch.sh
git diff --check
