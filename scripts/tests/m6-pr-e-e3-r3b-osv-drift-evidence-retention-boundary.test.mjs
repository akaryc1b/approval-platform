import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL(
  '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs',
  import.meta.url,
), 'utf8');

test('R3B retains normalized OSV identity drift evidence before failing closed', () => {
  for (const marker of [
    'M6_PR_E_E3_R2B_OSV_IDENTITY_DRIFT_EVIDENCE_BEGIN',
    'M6_PR_E_E3_R2B_OSV_IDENTITY_DRIFT_EVIDENCE_END',
    'M6_PR_E_E3_R2B_OSV_IDENTITY_DRIFT_EVIDENCE_V1',
    'candidateSecretMaterialRetained: false',
    'rawReportRetained: false',
    'releaseBlocked: true',
  ]) assert.ok(source.includes(marker), marker);

  const retain = source.indexOf(
    'retainOsvIdentityDriftEvidence(e4, osvIds, expectedCurrent.osv);',
  );
  const failClosed = source.indexOf(
    "requireIdentitySet('current OSV', osvIds, expectedCurrent.osv);",
  );
  assert.ok(retain >= 0, 'OSV drift retention call must exist');
  assert.ok(failClosed > retain, 'OSV drift evidence must be retained before fail-closed assertion');
  assert.match(source, /findings: normalizedFindings/);
  assert.match(source, /left\.findingId\.localeCompare\(right\.findingId\)/);
  assert.match(source, /findingSetSha256: currentFindingSetSha256/);
  assert.doesNotMatch(source, /suppressionAdded:\s*true|exceptionAdded:\s*true|severityDowngradeAdded:\s*true/);
});
