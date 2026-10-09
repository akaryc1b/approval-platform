#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { requireCompleteCurrentE4 } from './scanner-evidence-provenance.mjs';
import { canonicalSemgrepPath, DESIGNER_PROTOTYPE_TARGET } from './semgrep-scan-coverage.mjs';

const PLAN_SHA256 = '53143fa727b6eafaae41319e6a2a4d159d14ee2557cd46cb650b895bbb7ee3ac';
const stable = value => Array.isArray(value) ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
export const canonicalPrototypeEvidence = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const digest = value => hash(canonicalPrototypeEvidence(value));
const blob = content => createHash('sha1').update(`blob ${Buffer.byteLength(content)}\0`).update(content).digest('hex');
const requireValue = (condition, message) => { if (!condition) throw new Error(message); };
const key = finding => `${finding.sourceClass}:${finding.findingId}`;

function requireCanonical(value, label) {
  requireValue(value && /^[0-9a-f]{64}$/.test(value.contentSha256 || ''), `${label} canonical digest required`);
  const { contentSha256, ...payload } = value;
  requireValue(digest(payload) === contentSha256, `${label} canonical digest mismatch`);
}

export function readDesignerPrototypeRemediationPlan() {
  const plan = JSON.parse(readFileSync(new URL('../../docs/m6/m6-pr-e-e3-designer-prototype-remediation.json', import.meta.url), 'utf8'));
  requireCanonical(plan, 'prototype remediation plan');
  requireValue(plan.contentSha256 === PLAN_SHA256, 'unreviewed prototype remediation plan');
  return plan;
}

function requireHistoricalReview(plan, review) {
  requireValue(digest(review) === plan.historicalReviewCanonicalSha256, 'historical I2 review drift');
  const old = plan.historicalFinding;
  const reviewed = review.reviewedFindings.find(finding => key(finding) === key(old));
  requireValue(reviewed && reviewed.sourceBlobSha === old.sourceBlobSha
    && reviewed.sourcePath === old.sourcePath && reviewed.disposition === old.priorDisposition
    && reviewed.evidenceCode === old.evidenceCode && reviewed.exception === null,
  'historical prototype review identity drift');
  requireValue(review.reviewBasisHead === plan.historicalReviewBasisHead
    && review.reviewBasisIntakeCanonicalSha256 === plan.historicalReviewBasisIntakeCanonicalSha256,
  'historical prototype review basis drift');
  requireValue(old.sourceClass === 'E4_SEMGREP' && old.path === `/src/${old.sourcePath}`
    && old.findingId === hash(['SEMGREP', old.ruleId, old.path, String(old.startLine), String(old.startColumn)].join('\0')),
  'historical prototype scanner identity drift');
}

function requireCurrentScan(e4, plan) {
  try { requireCompleteCurrentE4(e4, plan.repository); }
  catch (error) { throw new Error(`prototype remediation ${error.message}`); }
  const scanner = e4.scanners.semgrep;
  const coverage = scanner.coverage;
  requireCanonical(coverage, 'Semgrep coverage');
  requireValue(coverage.schemaVersion === 'M6_PR_E_E4_SEMGREP_TARGET_COVERAGE_V1'
    && coverage.requiredTarget === DESIGNER_PROTOTYPE_TARGET && coverage.requiredTargetScanned === true
    && coverage.requiredTargetExplicitlySkipped === false && Number.isSafeInteger(coverage.scannedPathCount)
    && coverage.scannedPathCount > 0 && typeof coverage.skippedPathInventoryReported === 'boolean'
    && Number.isSafeInteger(coverage.reportedSkippedPathCount) && coverage.reportedSkippedPathCount >= 0
    && (coverage.skippedPathInventoryReported || coverage.reportedSkippedPathCount === 0)
    && coverage.rawReportRetained === false && coverage.sourceSnippetRetained === false
    && ['scannedPathsSha256', 'reportedSkippedPathsSha256', 'rawPathsCanonicalSha256']
      .every(field => /^[0-9a-f]{64}$/.test(coverage[field] || '')),
  'prototype remediation positive scanned-target coverage required');
  requireValue(scanner.sourceSnippetRetained === false
    && e4.scanners.gitleaks.candidateSecretMaterialRetained === false, 'prototype remediation redaction required');
  for (const [field, expected] of Object.entries(plan.semgrepExecutionIdentity)) {
    requireValue(scanner[field] === expected, `prototype remediation Semgrep execution identity drift ${field}`);
  }
  const old = plan.historicalFinding;
  requireValue(!scanner.findings.some(finding => finding.findingId === old.findingId),
    'historical prototype finding still present');
  // Finding IDs encode locations. A relocated match is not remediation.
  requireValue(!scanner.findings.some(finding => finding.ruleId === old.ruleId
    && canonicalSemgrepPath(finding.path) === old.path), 'prototype rule/path still present at current location');
}

function receipt(e4, plan) {
  const old = plan.historicalFinding;
  const payload = stable({
    schemaVersion: 'M6_PR_E_E3_DESIGNER_PROTOTYPE_REMEDIATION_EVIDENCE_V1',
    repository: e4.repository,
    commitSha: e4.commitSha,
    sourceE4CanonicalSha256: e4.contentSha256,
    planCanonicalSha256: plan.contentSha256,
    historicalReviewCanonicalSha256: plan.historicalReviewCanonicalSha256,
    reviewedArtifacts: plan.reviewedArtifacts,
    semgrepExecutionIdentity: plan.semgrepExecutionIdentity,
    semgrepTargetCoverage: e4.scanners.semgrep.coverage,
    scannedCheckout: e4.checkout,
    remediatedFindings: [{
      ...old,
      currentStatus: 'REMEDIATED_BY_RESERVED_KEY_VALIDATION_AND_ABSENT_FROM_CURRENT_SEMGREP_RULE_PATH',
      correctedSourceBlobSha: plan.reviewedArtifacts[old.sourcePath].blobSha,
      regressionTestBlobSha: plan.reviewedArtifacts['scripts/tests/approval-designer-conflict.test.mjs'].blobSha,
      sourceE4CanonicalSha256: e4.contentSha256,
      planCanonicalSha256: plan.contentSha256,
    }],
    remediatedFindingCount: 1,
    releaseBlocked: true,
    historicalReviewUnchanged: true,
    currentFindingsRetained: true,
    scannerInputReconstructed: false,
    suppressionAdded: false,
    exceptionAdded: false,
    severityDowngradeAdded: false,
  });
  return stable({ ...payload, contentSha256: digest(payload) });
}

/** Only the full-scanner caller supplies current source and exact Git-tree blobs. */
export function verifyDesignerPrototypeRemediation(e4, snapshot) {
  const plan = readDesignerPrototypeRemediationPlan();
  const review = JSON.parse(readFileSync(new URL('../../docs/m6/m6-pr-e-e3-i2-reviewed-findings.json', import.meta.url), 'utf8'));
  requireHistoricalReview(plan, review);
  requireCurrentScan(e4, plan);
  requireValue(snapshot?.repository === e4.repository && snapshot.commitSha === e4.commitSha
    && snapshot.currentE4CanonicalSha256 === e4.contentSha256 && snapshot.scannerExecutionCount === 1,
  'prototype remediation exact Head/scan binding required');
  requireValue(Array.isArray(snapshot.suppressionPathsPresent) && snapshot.suppressionPathsPresent.length === 0,
    'prototype remediation suppression paths prohibited');
  for (const [path, expected] of Object.entries(plan.reviewedArtifacts)) {
    const actual = snapshot.currentSources?.[path];
    requireValue(actual && typeof actual.content === 'string' && actual.commitSha === e4.commitSha
      && actual.headBlobSha === expected.blobSha && actual.blobSha === expected.blobSha
      && blob(actual.content) === expected.blobSha && hash(actual.content) === expected.sha256,
    `prototype remediation exact source/test blob required ${path}`);
  }
  return receipt(e4, plan);
}

/** Revalidate a serialized receipt when carrying it through I2 → I3 → I4. */
export function requireDesignerPrototypeRemediationReceipt(e4, evidence) {
  const plan = readDesignerPrototypeRemediationPlan();
  requireCurrentScan(e4, plan);
  requireValue(canonicalPrototypeEvidence(evidence) === canonicalPrototypeEvidence(receipt(e4, plan)),
    'prototype remediation receipt drift');
  return evidence;
}

/** The remediation receipt cannot excuse dropping or inventing a current finding. */
export function requireDesignerPrototypeTriage(e4, triage) {
  const evidence = requireDesignerPrototypeRemediationReceipt(e4, triage.designerPrototypeRemediation);
  requireCanonical(triage, 'prototype triage');
  requireValue(triage.repository === e4.repository && triage.commitSha === e4.commitSha,
    'prototype triage exact Head mismatch');
  const actual = (triage.decisions || []).map(key).sort();
  const expected = Object.values(e4.scanners).flatMap(scanner => scanner.findings.map(key)).sort();
  requireValue(canonicalPrototypeEvidence(actual) === canonicalPrototypeEvidence(expected),
    'prototype triage must retain every current finding identity');
  return evidence;
}
