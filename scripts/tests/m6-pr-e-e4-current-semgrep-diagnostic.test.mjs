import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const scanner = path.join(root, 'scripts/security/m6-pr-e-e4-scan.mjs');

test(
  'temporary diagnostic emits only normalized redacted Semgrep identities',
  { timeout: 2_400_000 },
  () => {
    if (process.env.GITHUB_ACTIONS !== 'true') return;

    const execution = spawnSync(
      process.execPath,
      [scanner, `--root=${root}`],
      {
        cwd: root,
        encoding: 'utf8',
        env: { ...process.env, GOFLAGS: '-modcacherw' },
        maxBuffer: 512 * 1024 * 1024,
        timeout: 2_300_000,
      },
    );
    assert.equal(execution.status, 0, execution.stderr || execution.stdout);

    const match = execution.stdout.match(
      /M6_PR_E_E4_SCANNER_EVIDENCE_BEGIN\n([^\n]+)\nM6_PR_E_E4_SCANNER_EVIDENCE_END/,
    );
    assert.ok(match, 'normalized E4 scanner evidence missing');
    const evidence = JSON.parse(match[1]);
    const semgrep = evidence.scanners?.semgrep;

    assert.equal(evidence.rawScannerReportsRetained, false);
    assert.equal(evidence.candidateSecretMaterialRetained, false);
    assert.equal(semgrep?.rawReportRetained, false);
    assert.equal(semgrep?.sourceSnippetRetained, false);

    const findings = semgrep.findings.map((finding) => ({
      findingId: finding.findingId,
      ruleId: finding.ruleId,
      path: finding.path,
      startLine: finding.startLine,
      startColumn: finding.startColumn,
      endLine: finding.endLine,
      endColumn: finding.endColumn,
    }));
    console.log('M6_PR_E_E4_SEMGREP_IDENTITY_DIAGNOSTIC_BEGIN');
    console.log(JSON.stringify({
      commitSha: evidence.commitSha,
      findingCount: semgrep.findingCount,
      findings,
    }));
    console.log('M6_PR_E_E4_SEMGREP_IDENTITY_DIAGNOSTIC_END');
  },
);
