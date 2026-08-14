import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const wrapper = readFileSync(new URL(
  '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs',
  import.meta.url,
), 'utf8');
const generic = readFileSync(new URL(
  '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation-generic.mjs',
  import.meta.url,
), 'utf8');
const source = `${generic}\n${wrapper}`;

test('R3B uses the generic normalized OSV drift evidence model introduced by PR 110', () => {
  for (const marker of [
    'currentOsvFindingProjection',
    'UNREVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET',
    'currentOsvFindingReviewRequired',
    'unreviewedCurrentOsvFindings',
    'OSV_DATABASE_DRIFT_RETAINED_WITHOUT_ACCEPTANCE',
    'CURRENT_OSV_IDENTITY_SET_REQUIRES_E3_REVIEW',
    'OSV_DATABASE_SNAPSHOT_IDENTITY_UNAVAILABLE',
    'releaseBlocked: true',
  ]) assert.ok(source.includes(marker), marker);

  assert.doesNotMatch(source, /M6_PR_E_E3_R2B_OSV_IDENTITY_DRIFT_EVIDENCE_(BEGIN|END)/);
  assert.doesNotMatch(source, /suppressionAdded:\s*true|exceptionAdded:\s*true|severityDowngradeAdded:\s*true|findingDeletionClaimed:\s*true/);
});
