#!/usr/bin/env bash
set -euo pipefail

python3 .github/scripts/d6-2-final-follow-up.py

rm -f \
  .github/pr53-connected.patch \
  .github/scripts/apply-pr53-connected-patch.sh \
  .github/scripts/d6-2-final-follow-up.py

git diff --check
