#!/usr/bin/env bash
set -euo pipefail

archive="$(mktemp)"
cleanup() {
  rm -f "$archive"
}
trap cleanup EXIT

if [ -f .github/pr53-connected-patch.tgz.b64 ]; then
  base64 --decode .github/pr53-connected-patch.tgz.b64 >"$archive"
elif compgen -G '.github/pr53-connected-patch.tgz.b64.part-*' >/dev/null; then
  LC_ALL=C cat .github/pr53-connected-patch.tgz.b64.part-* \
    | base64 --decode >"$archive"
elif compgen -G '.github/pr53-connected-patch.tgz.part-*' >/dev/null; then
  LC_ALL=C cat .github/pr53-connected-patch.tgz.part-* >"$archive"
else
  echo 'No queued connected patch payload found.'
  exit 0
fi

tar -tzf "$archive" >/dev/null
tar -xzf "$archive"

python3 - <<'PY'
from __future__ import annotations

from pathlib import Path
import re

PATCH = Path('.github/pr53-connected.patch')
if not PATCH.is_file():
    raise SystemExit('Connected patch archive did not contain the patch manifest')

lines = PATCH.read_text().splitlines(keepends=True)
hunk_header = re.compile(r'^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@')
index = 0
changed_files = 0
changed_hunks = 0

while index < len(lines):
    if not lines[index].startswith('diff --git '):
        index += 1
        continue

    parts = lines[index].rstrip('\n').split(' ')
    if len(parts) != 4 or not parts[3].startswith('b/'):
        raise SystemExit(f'Unsupported diff header at line {index + 1}')
    path = Path(parts[3][2:])
    if not path.is_file():
        raise SystemExit(f'Patch target does not exist: {path}')
    text = path.read_text()
    cursor = 0
    index += 1
    file_hunks = 0

    while index < len(lines) and not lines[index].startswith('diff --git '):
        match = hunk_header.match(lines[index])
        if not match:
            index += 1
            continue

        old_start = int(match.group(1))
        index += 1
        body: list[str] = []
        while index < len(lines):
            line = lines[index]
            if line.startswith('diff --git ') or line.startswith('@@ '):
                break
            body.append(line)
            index += 1

        old_block = ''.join(
            line[1:] for line in body
            if line.startswith((' ', '-')) and not line.startswith('--- ')
        )
        new_block = ''.join(
            line[1:] for line in body
            if line.startswith((' ', '+')) and not line.startswith('+++ ')
        )
        if not old_block:
            existing_lines = text.splitlines(keepends=True)
            position = sum(len(line) for line in existing_lines[:max(old_start - 1, 0)])
        else:
            position = text.find(old_block, cursor)
            if position < 0:
                position = text.find(old_block)
            if position < 0:
                preview = old_block[:240].replace('\n', '\\n')
                raise SystemExit(
                    f'Exact patch context not found for {path} near old line '
                    f'{old_start}: {preview}'
                )
            duplicate = text.find(old_block, position + 1)
            if duplicate >= 0 and duplicate >= cursor:
                raise SystemExit(
                    f'Exact patch context is ambiguous for {path} near old line {old_start}'
                )

        text = text[:position] + new_block + text[position + len(old_block):]
        cursor = position + len(new_block)
        file_hunks += 1
        changed_hunks += 1

    if file_hunks:
        path.write_text(text)
        changed_files += 1

print(f'Applied {changed_hunks} exact hunks across {changed_files} files')
PY

rm -f .github/pr53-connected-patch.tgz.b64 \
  .github/pr53-connected-patch.tgz.b64.part-* \
  .github/pr53-connected-patch.tgz.part-* \
  .github/pr53-connected.patch \
  .github/pr53-version-page.part-* \
  .github/workflows/pr53-connected-patch.yml \
  .github/scripts/apply-pr53-connected-patch.sh

git diff --check
