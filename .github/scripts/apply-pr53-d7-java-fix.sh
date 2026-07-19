#!/usr/bin/env bash
set -euo pipefail

target='server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalArtifactTransferIntegrationTest.java'
sed -i '/import static org\.junit\.jupiter\.api\.Assertions\.assertTrue;/d' "$target"
rm -f .github/scripts/apply-pr53-d7-java-fix.sh
