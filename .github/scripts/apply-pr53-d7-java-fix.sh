#!/usr/bin/env bash
set -euo pipefail

target='server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalArtifactTransferIntegrationTest.java'
sed -i '/import static org\.junit\.jupiter\.api\.Assertions\.assertTrue;/d' "$target"
python3 <<'PY'
from pathlib import Path

path = Path(
    "server-modules/approval-persistence-jdbc/src/test/java/"
    "io/github/akaryc1b/approval/persistence/jdbc/"
    "JdbcApprovalArtifactTransferIntegrationTest.java"
)
text = path.read_text(encoding="utf-8")
old = '''        jdbc.update(
            "delete from ap_form_package where tenant_id = ? and form_key = ?",
            TARGET_TENANT,
            PurchasePaymentTemplate.DEFINITION_KEY
        );'''
new = '''        jdbc.update(
            "update ap_form_package set form_hash = ? "
                + "where tenant_id = ? and form_key = ?",
            "0".repeat(64),
            TARGET_TENANT,
            PurchasePaymentTemplate.DEFINITION_KEY
        );'''
if old not in text:
    raise SystemExit("expected target Form Package test fixture was not found")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
PY
rm -f .github/scripts/apply-pr53-d7-java-fix.sh
