#!/usr/bin/env bash
set -euo pipefail
set -x

python3 .github/scripts/d6-2-composable-1.py
python3 .github/scripts/d6-2-composable-2.py
python3 .github/scripts/d6-2-fix-generated-literals.py
python3 .github/scripts/d6-2-composable-3.py
python3 .github/scripts/d6-2-ui-1.py
python3 .github/scripts/d6-2-ui-2.py

rm -f \
  .github/pr53-connected.patch \
  .github/scripts/apply-pr53-connected-patch.sh \
  .github/scripts/d6-2-composable-1.py \
  .github/scripts/d6-2-composable-2.py \
  .github/scripts/d6-2-composable-3.py \
  .github/scripts/d6-2-fix-generated-literals.py \
  .github/scripts/d6-2-ui-1.py \
  .github/scripts/d6-2-ui-2.py

git diff --check
