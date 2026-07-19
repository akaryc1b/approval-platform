#!/usr/bin/env bash
set -euo pipefail
patch_file=.github/pr53-connected.patch
if [ ! -f "$patch_file" ]; then
  echo 'No queued connected patch found.'
  exit 0
fi
python3 - <<'PY'
from pathlib import Path
import re
patch = Path('.github/pr53-connected.patch')
lines = patch.read_text().splitlines(keepends=True)
header = re.compile(r'^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@')
i = 0
while i < len(lines):
    if not lines[i].startswith('diff --git '):
        i += 1
        continue
    path = Path(lines[i].rstrip('\n').split(' ')[3][2:])
    text = path.read_text()
    i += 1
    changed = False
    while i < len(lines) and not lines[i].startswith('diff --git '):
        match = header.match(lines[i])
        if not match:
            i += 1
            continue
        old_line = int(match.group(1))
        i += 1
        body = []
        while i < len(lines) and not lines[i].startswith(('diff --git ', '@@ ')):
            body.append(lines[i])
            i += 1
        old = ''.join(x[1:] for x in body if x.startswith((' ', '-')) and not x.startswith('--- '))
        new = ''.join(x[1:] for x in body if x.startswith((' ', '+')) and not x.startswith('+++ '))
        candidates = []
        pos = text.find(old)
        while pos >= 0:
            candidates.append(pos)
            pos = text.find(old, pos + 1)
        if not candidates:
            raise SystemExit(f'Exact context missing for {path} near line {old_line}')
        offsets = [0]
        for line in text.splitlines(keepends=True):
            offsets.append(offsets[-1] + len(line))
        target = offsets[min(max(old_line - 1, 0), len(offsets) - 1)]
        pos = min(candidates, key=lambda value: abs(value - target))
        text = text[:pos] + new + text[pos + len(old):]
        changed = True
    if changed:
        path.write_text(text)
PY
rm -f "$patch_file" .github/scripts/apply-pr53-connected-patch.sh
git diff --check
