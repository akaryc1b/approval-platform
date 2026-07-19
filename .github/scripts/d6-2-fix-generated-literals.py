from pathlib import Path

path = Path('apps/web/overlay/apps/web-ele/src/views/approval/designer/use-approval-designer.ts')
text = path.read_text()
broken = "].filter(Boolean).join('\n');"
fixed = "].filter(Boolean).join('\\n');"
if text.count(broken) != 1:
    raise SystemExit(f'generated newline literal: expected one marker, found {text.count(broken)}')
path.write_text(text.replace(broken, fixed, 1))
