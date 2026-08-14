import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const file = (value) => path.join(root, value);
const acceptancePath = file('docs/m6/M6_PR_E_OSV_DATABASE_DRIFT_FAIL_CLOSED_FINAL_ACCEPTANCE.md');
const correctionPath = file('docs/m6/M6_PR_E_OSV_DATABASE_DRIFT_FAIL_CLOSED_CORRECTION.md');
const verifierPath = file('scripts/security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs');
const acceptedTestPath = file('scripts/tests/m6-pr-e-e3-r2b-workflow-supply-chain-remediation-boundary-accepted.test.mjs');
const reconciliationPath = file('scripts/tests/m6-pr-e-e3-r2b-scanner-identity-reconciliation.test.mjs');
const aggregatePath = file('scripts/tests/m6-pr-e-e3-r2b-workflow-supply-chain-remediation-boundary.test.mjs');
const load = (value) => readFileSync(value, 'utf8');
const blob = (value) => createHash('sha1').update(`blob ${Buffer.byteLength(value)}\0`).update(value).digest('hex');

test('OSV drift acceptance binds exact implementation Run Jobs scanner state and artifacts', () => {
  assert.equal(existsSync(acceptancePath), true);
  const body = load(acceptancePath);
  for (const marker of [
    'M6_PR_E_OSV_DATABASE_DRIFT_FAIL_CLOSED_ACCEPTED_PENDING_FINAL_DOCUMENTED_HEAD_VALIDATION',
    '6dfd9bbec3103a08263f3d30ff2323877bca298a', '31794417910', '#1469', '9 / 9 success',
    '94748237763', '94748237765', '94748237790', '94748237793', '94748237836',
    '94748237867', '94748237877', '94748237882', '94748803142',
    '4a8054dc0b0715a9fff62978f0217064d0cb6b87b27eb698bc87746c35999e67',
    '6e98f9a7e20ffc17fabe3c3cab92309366c5e168cdee90c1ed0365ee25ebad12',
    'b8cbb7686e6144634b4a5ed5f8c4351410d092a96666e5b737f261d07a721565',
    'OSV:       120', 'Total:     150', 'UNREVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET',
    'currentOsvFindingReviewRequired = true', 'unreviewedCurrentOsvFindingCount = 120',
    'UNRESOLVED:     147', 'releaseBlocked: true', '267 / 267 PASS',
    '9216859580', '78f0be5f846542fe41b3c590fd40e309b84e2d04953bf656f56622f02cb85e15',
    '9216841219', '7fcde37a25e07663f9d65dbb6f87458de526ac7edb416274fdc2250d45a38060',
    '9216813948', 'd3166bc11dc04d3c49970d58313917a9d857809d4f6d540780d80da32d0853e5',
    '9216798269', '7c49f719e752db61b95d9e8a33cb0502c1aaaa944e5ea789a74d266a97e938cd',
  ]) assert.ok(body.includes(marker), marker);
});

test('OSV drift accepted implementation blobs remain exact and separated', () => {
  assert.equal(blob(load(verifierPath)), '1ff31b063e019dcf3802dfd473339c2976e18484');
  assert.equal(blob(load(acceptedTestPath)), '2c6218eda079c0a73ba7cc9c02757e709f7fd0b5');
  assert.equal(blob(load(reconciliationPath)), 'c77ed6c103b131ba87ccd59254e9dbf3f631542d');
  assert.equal(blob(load(correctionPath)), '56c3f7992661231f2256743f140a4aed1a6fb158');
  const accepted = load(acceptedTestPath);
  assert.ok(accepted.includes("verify-workflow-supply-chain-remediation-accepted.mjs';"));
  assert.equal(accepted.includes("verify-workflow-supply-chain-remediation.mjs';"), false);
  const verifier = load(verifierPath);
  assert.ok(verifier.includes('UNREVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET'));
  assert.ok(verifier.includes('currentOsvFindingReviewRequired:'));
  assert.doesNotMatch(verifier, /suppressionAdded:\s*true|exceptionAdded:\s*true|severityDowngradeAdded:\s*true|findingDeletionClaimed:\s*true/);
});

test('OSV drift final gate authorizes only exact Ready and ordinary Merge Commit', () => {
  const body = load(acceptancePath);
  for (const marker of [
    'M6_PR_E_OSV_DATABASE_DRIFT_FAIL_CLOSED_ACCEPTED', 'M6_PR_E_SECURITY_CLOSURE_NOT_ACCEPTED',
    'READY_AUTHORIZED_AFTER_FINAL_DOCUMENTED_HEAD_VALIDATION',
    'MERGE_COMMIT_AUTHORIZED_AFTER_FINAL_DOCUMENTED_HEAD_VALIDATION',
    'NO_SQUASH', 'NO_REBASE', 'NO_FORCE_UPDATE', 'NO_AUTO_MERGE',
    'NO_DEPLOYMENT', 'NO_PRODUCTION_PROMOTION', 'PRB_16_REMAINS_OPEN',
    'PRB_17_REMAINS_OPEN', 'ISSUE_97_REMAINS_OPEN',
  ]) assert.ok(body.includes(marker), marker);
  assert.doesNotMatch(body, /PRB_16_(PASS|CLOSED)|PRB_17_(PASS|CLOSED)|ISSUE_97_(CLOSED|COMPLETED)|M6_PR_E_SECURITY_CLOSURE_ACCEPTED|PRODUCTION_PROMOTION_AUTHORIZED/);
});

test('OSV drift final acceptance is loaded by the existing R2B aggregate', () => {
  assert.ok(load(aggregatePath).includes("import './m6-pr-e-osv-database-drift-final-acceptance-boundary.test.mjs';"));
});
