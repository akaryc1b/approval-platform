#!/usr/bin/env bash
set -euo pipefail

bash .github/scripts/apply-pr53-connected-patch-core.sh

python3 - <<'PY'
from pathlib import Path

integration = Path(
    'server-modules/approval-persistence-jdbc/src/test/java/'
    'io/github/akaryc1b/approval/persistence/jdbc/'
    'JdbcApprovalDesignReleaseIntegrationTest.java'
)
text = integration.read_text()
typo = 'AprovalDefinitionSimulator'
if text.count(typo) != 1:
    raise SystemExit(
        f'Expected one transported simulator typo, found {text.count(typo)}'
    )
integration.write_text(text.replace(typo, 'ApprovalDefinitionSimulator', 1))

controller = Path(
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
    'ApprovalBatchSimulationController.java'
)
text = controller.read_text()
nested = (
    '        BatchReport report = service.simulate('
    'request.toCommand(tenantId, draftId));\n'
)
expanded = (
    '        BatchCommand command = request.toCommand(tenantId, draftId);\n'
    '        BatchReport report = service.simulate(command);\n'
)
if text.count(nested) != 1:
    raise SystemExit(
        f'Expected one nested export simulation call, found {text.count(nested)}'
    )
controller.write_text(text.replace(nested, expanded, 1))
PY

rm -f .github/scripts/apply-pr53-connected-patch-core.sh
git diff --check
