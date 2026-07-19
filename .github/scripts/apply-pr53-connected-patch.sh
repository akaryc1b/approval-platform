#!/usr/bin/env bash
set -euo pipefail
set -x

python3 .github/scripts/d6-patch-1.py
python3 .github/scripts/d6-patch-2.py
python3 .github/scripts/d6-patch-3.py
python3 .github/scripts/d6-patch-ui.py

rm -f \
  .github/pr53-connected.patch \
  .github/scripts/apply-pr53-connected-patch.sh \
  .github/scripts/d6-patch-1.py \
  .github/scripts/d6-patch-2.py \
  .github/scripts/d6-patch-3.py \
  .github/scripts/d6-patch-ui.py

git diff --check
