#!/usr/bin/env bash
set -euo pipefail
exec > >(tee /tmp/pr53-apply-patch.log) 2>&1
trap 'cp /tmp/pr53-apply-patch.log maven-verify.log 2>/dev/null || true' EXIT
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
text = text.replace('AprovalDefinitionSimulator', 'ApprovalDefinitionSimulator')
if 'AprovalDefinitionSimulator' in text:
    raise SystemExit('Transported simulator typo still exists')
integration.write_text(text)

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
    text = text.replace(import_line, '')
export_start = '''    @PostMapping(
        value = "/{draftId}/batch-simulate/export",
'''
record_start = '''    public record BatchSimulationRequest(
'''
if export_start in text:
    start = text.index(export_start)
    end = text.index(record_start, start)
    text = text[:start] + text[end:]
if '/batch-simulate/export' in text or 'ResponseEntity<BatchReport>' in text:
    raise SystemExit('Batch export controller endpoint was not removed')
controller.write_text(text)

api_path = Path(
    'apps/web/overlay/apps/web-ele/src/api/approval/process-design.ts'
)
api = api_path.read_text()
export_function = 'export async function exportApprovalDesignDraftBatchReport('
archive_function = 'export function archiveApprovalDesignDraft('
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
archive_start = api.find(archive_function)
if archive_start < 0:
    raise SystemExit('Archive API function was not found')
export_start_index = api.find(export_function)
if export_start_index >= 0:
    archive_start = api.find(archive_function, export_start_index)
    api = api[:export_start_index] + replacement + api[archive_start:]
else:
    api = api[:archive_start] + replacement + api[archive_start:]
api_path.write_text(api)

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
if old_download in page:
    page = page.replace(old_download, new_download, 1)
elif new_download not in page:
    raise SystemExit('Simulation report download block was not found')
page_path.write_text(page)
PY

rm -f .github/scripts/apply-pr53-connected-patch-core.sh
git diff --check
