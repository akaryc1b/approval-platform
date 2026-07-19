#!/usr/bin/env bash
set -euo pipefail

patch_file=.github/pr53-connected.patch
if [ ! -f "$patch_file" ]; then
  echo 'No queued connected patch found.'
  exit 0
fi

git apply --check --whitespace=error-all "$patch_file"
git apply --whitespace=error-all "$patch_file"
rm -f "$patch_file" .github/scripts/apply-pr53-connected-patch.sh
git diff --check
