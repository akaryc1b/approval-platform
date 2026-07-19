#!/usr/bin/env bash
set -euo pipefail

patch_file=.github/pr53-connected.patch
fix_patch=.github/pr53-connected-fix.patch
if [ ! -f "$patch_file" ]; then
  echo 'No queued connected patch found.'
  exit 0
fi

python3 - <<'PY'
from __future__ import annotations

from pathlib import Path
import re

patch = Path('.github/pr53-connected.patch')
lines = patch.read_text().splitlines(keepends=True)
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
            candidates: list[int] = []
            position = text.find(old_block)
            while position >= 0:
                candidates.append(position)
                position = text.find(old_block, position + 1)
            if not candidates:
                preview = old_block[:240].replace('\n', '\\n')
                raise SystemExit(
                    f'Exact patch context not found for {path} near old line '
                    f'{old_start}: {preview}'
                )
            line_offsets = [0]
            for line in text.splitlines(keepends=True):
                line_offsets.append(line_offsets[-1] + len(line))
            target_offset = line_offsets[min(max(old_start - 1, 0), len(line_offsets) - 1)]
            distances = sorted((abs(value - target_offset), value) for value in candidates)
            if len(distances) > 1 and distances[0][0] == distances[1][0]:
                raise SystemExit(
                    f'Exact patch context is ambiguous for {path} near old line {old_start}'
                )
            position = distances[0][1]

        text = text[:position] + new_block + text[position + len(old_block):]
        file_hunks += 1
        changed_hunks += 1

    if file_hunks:
        path.write_text(text)
        changed_files += 1

print(f'Applied {changed_hunks} exact hunks across {changed_files} files')
PY

if [ -f "$fix_patch" ]; then
  git apply --check --whitespace=error-all "$fix_patch"
  git apply --whitespace=error-all "$fix_patch"
fi

rm -f "$patch_file" "$fix_patch" .github/scripts/apply-pr53-connected-patch.sh
git diff --check
