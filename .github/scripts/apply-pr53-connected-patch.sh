#!/usr/bin/env bash
set -euo pipefail
set -x

bash .github/scripts/apply-pr53-connected-patch-core.sh

python3 - <<'PY'
from pathlib import Path

integration = Path(
    'server-modules/approval-persistence-jdbc/src/test/java/'
    'io/github/akaryc1b/approval/persistence/jdbc/'
    'JdbcApprovalDesignReleaseIntegrationTest.java'
)
text = integration.read_text()
typo = 'AprovalDefinitionSimulator'
if text.count(typo) != 1:
    raise SystemExit(
        f'Expected one transported simulator typo, found {text.count(typo)}'
    )
integration.write_text(text.replace(typo, 'ApprovalDefinitionSimulator', 1))

controller = Path(
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
    'ApprovalBatchSimulationController.java'
)
text = controller.read_text()
for import_line in (
    'import org.springframework.http.CacheControl;\n',
    'import org.springframework.http.MediaType;\n',
    'import org.springframework.http.ResponseEntity;\n',
):
    if text.count(import_line) != 1:
        raise SystemExit(f'Expected controller import {import_line!r}')
    text = text.replace(import_line, '', 1)
export_start = '''    @PostMapping(
        value = "/{draftId}/batch-simulate/export",
'''
record_start = '''    public record BatchSimulationRequest(
'''
if text.count(export_start) != 1 or text.count(record_start) != 1:
    raise SystemExit('Expected one export endpoint and request record')
start = text.index(export_start)
end = text.index(record_start, start)
controller.write_text(text[:start] + text[end:])

api_path = Path(
    'apps/web/overlay/apps/web-ele/src/api/approval/process-design.ts'
)
api = api_path.read_text()
export_function = '''export async function exportApprovalDesignDraftBatchReport(
'''
archive_function = '''export function archiveApprovalDesignDraft(
'''
if api.count(export_function) != 1 or api.count(archive_function) != 1:
    raise SystemExit('Expected batch export and archive API functions')
start = api.index(export_function)
end = api.index(archive_function, start)
replacement = '''export async function exportApprovalDesignDraftBatchReport(
  draftId: string,
  input: ApprovalBatchSimulationInput,
) {
  const report = await simulateApprovalDesignDraftBatch(draftId, input);
  return new Blob([JSON.stringify(report, null, 2)], {
    type: 'application/json;charset=utf-8',
  });
}

'''
api_path.write_text(api[:start] + replacement + api[end:])

page_path = Path(
    'apps/web/overlay/apps/web-ele/src/views/approval/simulations/index.vue'
)
page = page_path.read_text()
old_download = '''    link.href = url;
    link.download = `approval-simulation-${selectedDraft.value!.definitionKey}-${report.value?.reportHash.slice(0, 12) || 'report'}.json`;
    link.click();
'''
new_download = '''    link.href = url;
    const safeDefinitionKey = selectedDraft.value!.definitionKey.replace(
      /[^A-Za-z0-9._-]/g,
      '_',
    );
    const reportHash = report.value?.reportHash.slice(0, 12) || 'report';
    link.download = `approval-simulation-${safeDefinitionKey}-${reportHash}.json`;
    link.click();
'''
if page.count(old_download) != 1:
    raise SystemExit('Expected simulation report download block')
page_path.write_text(page.replace(old_download, new_download, 1))
PY

rm -f .github/scripts/apply-pr53-connected-patch-core.sh
git diff --check
