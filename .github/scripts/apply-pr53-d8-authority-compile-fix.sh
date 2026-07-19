#!/usr/bin/env bash
set -euo pipefail

python3 <<'PY'
from pathlib import Path

path = Path(
    'server-modules/approval-application/src/main/java/'
    'io/github/akaryc1b/approval/application/ApprovalFormDesignService.java'
)
text = path.read_text(encoding='utf-8')
old = '''        access = new LinkedHashMap<>(UiSchemaDefinitionValidator.applySectionAccess(
            draft.uiSchemaDefinition(),
            access
        ));
        Map<String, Object> values = defaultValues.resolve('''
new = '''        Map<String, FieldAccess> effectiveAccess = UiSchemaDefinitionValidator.applySectionAccess(
            draft.uiSchemaDefinition(),
            access
        );
        Map<String, Object> values = defaultValues.resolve('''
if old not in text:
    raise SystemExit('authoritative preview access block was not found')
text = text.replace(old, new, 1)
text = text.replace(
    '''            if (access.getOrDefault(key, FieldAccess.HIDDEN) != FieldAccess.HIDDEN) {''',
    '''            if (effectiveAccess.getOrDefault(key, FieldAccess.HIDDEN) != FieldAccess.HIDDEN) {''',
    1,
)
text = text.replace(
    '''            Map.copyOf(access),
            Map.copyOf(required),''',
    '''            effectiveAccess,
            Map.copyOf(required),''',
    1,
)
path.write_text(text, encoding='utf-8')
PY

rm -f .github/scripts/apply-pr53-d8-authority-compile-fix.sh
