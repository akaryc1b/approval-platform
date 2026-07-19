#!/usr/bin/env bash
set -euo pipefail

bash .github/scripts/apply-pr53-connected-patch-core.sh

python3 - <<'PY'
from pathlib import Path

path = Path(
    'server-modules/approval-persistence-jdbc/src/test/java/'
    'io/github/akaryc1b/approval/persistence/jdbc/'
    'JdbcApprovalDesignReleaseIntegrationTest.java'
)
text = path.read_text()
typo = 'AprovalDefinitionSimulator'
if text.count(typo) != 1:
    raise SystemExit(
        f'Expected one transported simulator typo, found {text.count(typo)}'
    )
path.write_text(text.replace(typo, 'ApprovalDefinitionSimulator', 1))
PY

rm -f .github/scripts/apply-pr53-connected-patch-core.sh
git diff --check
