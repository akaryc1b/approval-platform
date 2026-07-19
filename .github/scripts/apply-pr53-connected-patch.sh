#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from pathlib import Path

service = Path(
    'server-modules/approval-application/src/main/java/'
    'io/github/akaryc1b/approval/application/ApprovalBatchSimulationService.java'
)
service_text = service.read_text()
replacements = (
    (
        'ApprovalDefinitionSimulator.Step',
        'ApprovalDefinitionSimulator.SimulationStep',
        3,
    ),
    ('startsWith("ROUTE:")', 'startsWith("ROUTE_")', 1),
    ('substring("ROUTE:".length())', 'substring("ROUTE_".length())', 1),
    ('"RESUBMITTED".equals(step.outcome())', '"HANDLED".equals(step.outcome())', 1),
)
for old, new, expected_count in replacements:
    actual_count = service_text.count(old)
    if actual_count != expected_count:
        raise SystemExit(
            f'Expected {expected_count} occurrences of {old!r}, found {actual_count}'
        )
    service_text = service_text.replace(old, new)
service.write_text(service_text)

test = Path(
    'server-modules/approval-application/src/test/java/'
    'io/github/akaryc1b/approval/application/ApprovalBatchSimulationServiceTest.java'
)
test_text = test.read_text()
if 'private static UiSchemaDefinition purchasePaymentUi()' in test_text:
    raise SystemExit('Unexpected temporary purchase payment UI helper remains in source')
if test_text.count('PurchasePaymentTemplate.uiSchemaDefinition()') != 3:
    raise SystemExit('Tests must use the complete PurchasePaymentTemplate UI Schema')
marker = '''        assertEquals(
            List.of(
                "blocked-countersign",
                "high-value",
                "low-value",
                "manager-reject-loop"
            ),
            report.scenarios().stream().map(value -> value.scenarioId()).toList()
        );
'''
diagnostic = marker + '''        assertTrue(
            report.scenarios().stream().noneMatch(value ->
                value.runStatus() == ScenarioRunStatus.ERROR
            ),
            () -> "scenario errors: " + report.scenarios().stream()
                .filter(value -> value.runStatus() == ScenarioRunStatus.ERROR)
                .map(value -> value.scenarioId() + ": " + value.expectationFailures())
                .toList()
        );
'''
if test_text.count(marker) != 1:
    raise SystemExit('Batch simulation result ordering assertion marker was not found')
test.write_text(test_text.replace(marker, diagnostic, 1))
PY

rm -f .github/pr53-connected.patch .github/scripts/apply-pr53-connected-patch.sh
git diff --check
