#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

service = Path(
    'server-modules/approval-application/src/main/java/'
    'io/github/akaryc1b/approval/application/ApprovalBatchSimulationService.java'
)
service_text = service.read_text()
old_type = 'ApprovalDefinitionSimulator.Step'
expected_type_count = 3
if service_text.count(old_type) != expected_type_count:
    raise SystemExit(
        f'Expected {expected_type_count} simulator Step references, '
        f'found {service_text.count(old_type)}'
    )
service.write_text(
    service_text.replace(old_type, 'ApprovalDefinitionSimulator.SimulationStep')
)

test = Path(
    'server-modules/approval-application/src/test/java/'
    'io/github/akaryc1b/approval/application/ApprovalBatchSimulationServiceTest.java'
)
test_text = test.read_text()
old_ui_call = 'PurchasePaymentTemplate.uiSchemaDefinition()'
expected_ui_count = 3
if test_text.count(old_ui_call) != expected_ui_count:
    raise SystemExit(
        f'Expected {expected_ui_count} template UI calls, '
        f'found {test_text.count(old_ui_call)}'
    )
test_text = test_text.replace(old_ui_call, 'purchasePaymentUi()')
marker = '    private static ApprovalDefinition parallelDefinition() {\n'
helper = '''    private static UiSchemaDefinition purchasePaymentUi() {\n        FormDefinition form = PurchasePaymentTemplate.formDefinition();\n        return new UiSchemaDefinition(\n            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,\n            form.formKey(),\n            form.version(),\n            1,\n            "Purchase payment batch UI",\n            List.of(),\n            List.of()\n        );\n    }\n\n'''
if marker not in test_text:
    raise SystemExit('Parallel definition marker was not found')
if 'private static UiSchemaDefinition purchasePaymentUi()' in test_text:
    raise SystemExit('Purchase payment UI helper already exists')
test.write_text(test_text.replace(marker, helper + marker, 1))
PY

rm -f .github/pr53-connected.patch .github/scripts/apply-pr53-connected-patch.sh
git diff --check
