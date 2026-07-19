#!/usr/bin/env bash
set -euo pipefail

python3 <<'PY'
from pathlib import Path

runtime = Path(
    'server-modules/approval-application/src/main/java/'
    'io/github/akaryc1b/approval/application/ApprovalFormRuntimeService.java'
)
text = runtime.read_text(encoding='utf-8')
old = '''    private static void rejectNonEditable(
        Map<String, Object> input,'''
new = '''    static void rejectNonEditable(
        Map<String, Object> input,'''
if old not in text:
    raise SystemExit('runtime permission helper marker was not found')
runtime.write_text(text.replace(old, new, 1), encoding='utf-8')

test = Path(
    'server-modules/approval-application/src/test/java/'
    'io/github/akaryc1b/approval/application/CompositeUiSchemaAuthorityTest.java'
)
text = test.read_text(encoding='utf-8')
text = text.replace('import java.util.Map;\n', 'import java.util.Map;\nimport java.util.Set;\n', 1)
old = '''        assertThrows(
            ApprovalFormRuntimeService.FieldPermissionException.class,
            () -> invokeRejectNonEditable(permissions.fieldAccess())
        );'''
new = '''        assertThrows(
            ApprovalFormRuntimeService.FieldPermissionException.class,
            () -> ApprovalFormRuntimeService.rejectNonEditable(
                Map.of("owner", "changed"),
                permissions.fieldAccess(),
                Set.of()
            )
        );'''
if old not in text:
    raise SystemExit('authority assertion marker was not found')
text = text.replace(old, new, 1)
old = '''    private static void invokeRejectNonEditable(Map<String, FieldAccess> access) {
        if (access.get("owner") != FieldAccess.EDITABLE) {
            throw new ApprovalFormRuntimeService.FieldPermissionException(
                "fields are not editable in the current context: owner"
            );
        }
    }

'''
if old not in text:
    raise SystemExit('obsolete test helper was not found')
test.write_text(text.replace(old, '', 1), encoding='utf-8')
PY

rm -f .github/scripts/apply-pr53-d8-authority-test-fix.sh
