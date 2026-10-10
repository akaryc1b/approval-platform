import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { verifyGitleaksPublicCommitReview, requireGitleaksPublicCommitReviewReceipt,
  applyGitleaksPublicCommitDecision, requireGitleaksPublicCommitDecision } from './m6-pr-e-e3-review-gitleaks-public-commit.mjs';
import { requireCompleteCurrentE4 } from './scanner-evidence-provenance.mjs';
import { requireDesignerPrototypeTriage } from './m6-pr-e-e3-verify-designer-prototype-remediation.mjs';
import { requireServerDependencyRemediation } from './m6-pr-e-e3-verify-server-dependency-remediation.mjs';

const PLAN_SHA256 = '20158102576d7ba5931d0605bdd1aab548ec94db571b31a2bc5a20032f673c5d';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const seal = value => { const { contentSha256, ...payload } = value; return stable({ ...payload, contentSha256: hash(canonical(payload)) }); };
const blob = value => createHash('sha1').update(`blob ${Buffer.byteLength(value)}\0`).update(value).digest('hex');
const setHash = ids => hash(`${[...ids].sort().join('\n')}\n`);
const requireValue = (condition, message) => { if (!condition) throw new Error(message); };
const key = finding => `${finding.sourceClass}:${finding.findingId}`;

function requireCanonical(value, label) {
  requireValue(value && /^[0-9a-f]{64}$/.test(value.contentSha256 || '')
    && seal(value).contentSha256 === value.contentSha256, `${label} canonical mismatch`);
}
export function readGitleaksTestExpressionReviewPlan() {
  const plan = JSON.parse(readFileSync(new URL('../../docs/m6/m6-pr-e-e3-gitleaks-test-expression-review.json', import.meta.url), 'utf8'));
  requireCanonical(plan, 'Gitleaks expression review plan');
  requireValue(plan.contentSha256 === PLAN_SHA256, 'unreviewed Gitleaks expression plan');
  return plan;
}

function requireCurrentFinding(e4, plan, publicCommitReview = null) {
  if (publicCommitReview) requireGitleaksPublicCommitReviewReceipt(e4, publicCommitReview);
  try { requireCompleteCurrentE4(e4, plan.repository); }
  catch (error) { throw new Error(`Gitleaks review ${error.message}`); }
  const scanner = e4.scanners.gitleaks;
  requireValue(scanner && Array.isArray(scanner.findings) && scanner.findingCount === scanner.findings.length,
    'Gitleaks current findings array/count required');
  for (const [field, expected] of Object.entries(plan.scannerIdentity)) {
    requireValue(scanner[field] === expected, `Gitleaks reviewed scanner identity drift ${field}`);
  }
  const finding = plan.historicalFinding;
  const fingerprint = `${finding.commit}:${finding.path}:${finding.ruleId}:${finding.startLine}`;
  requireValue(finding.sourceClass === 'E4_GITLEAKS' && finding.fingerprint === fingerprint
    && finding.findingId === hash(['GITLEAKS', fingerprint, finding.ruleId, finding.path, String(finding.startLine)].join('\0')),
  'Gitleaks historical finding identity derivation drift');
  const ids = scanner.findings.map(item => {
    requireValue(item.sourceClass === 'E4_GITLEAKS' && /^[0-9a-f]{64}$/.test(item.findingId || ''),
      'Gitleaks current finding identity drift');
    return item.findingId;
  });
  requireValue(new Set(ids).size === ids.length, 'Gitleaks duplicate current finding');
  const actual = scanner.findings.find(item => key(item) === key(finding));
  requireValue(canonical(actual) === canonical(finding), 'Gitleaks reviewed historical finding metadata drift or absence');
  const reviewedIds = publicCommitReview ? ids.filter(id => id !== publicCommitReview.finding.findingId) : ids;
  const historical = reviewedIds.filter(id => id !== finding.findingId);
  requireValue(historical.length === plan.historicalFindingSet.findingCount
    && setHash(historical) === plan.historicalFindingSet.findingSetSha256,
  'Gitleaks unreviewed addition or historical identity loss');
  requireValue(reviewedIds.length === plan.currentReviewedFindingSet.findingCount
    && setHash(reviewedIds) === plan.currentReviewedFindingSet.findingSetSha256, 'Gitleaks reviewed current identity set drift');
}
function receipt(e4, plan) {
  return seal({ schemaVersion: 'M6_PR_E_E3_GITLEAKS_TEST_EXPRESSION_REVIEW_EVIDENCE_V1',
    repository: e4.repository, commitSha: e4.commitSha, sourceE4CanonicalSha256: e4.contentSha256,
    planCanonicalSha256: plan.contentSha256, reviewBasisHead: plan.reviewBasisHead,
    reviewBasisE4CanonicalSha256: plan.reviewBasisE4CanonicalSha256,
    historicalFinding: plan.historicalFinding, historicalSource: plan.historicalSource,
    reviewedExpression: plan.reviewedExpression, scannerIdentity: plan.scannerIdentity,
    retainedHistoricalFindingSet: plan.historicalFindingSet, currentFindingSet: plan.currentReviewedFindingSet,
    decision: plan.decision, releaseBlocked: true, rawCandidateMaterialRetained: false });
}

/** Read-only proof over the pinned historical Git source; never evaluates code. */
export function verifyGitleaksTestExpressionReview(e4, snapshot) {
  const plan = readGitleaksTestExpressionReviewPlan();
  const publicCommitReview = snapshot?.publicCommitReviewSnapshot
    ? verifyGitleaksPublicCommitReview(e4, snapshot.publicCommitReviewSnapshot) : null;
  requireCurrentFinding(e4, plan, publicCommitReview);
  requireValue(snapshot?.repository === e4.repository && snapshot.commitSha === e4.commitSha
    && snapshot.currentE4CanonicalSha256 === e4.contentSha256, 'Gitleaks review exact current E4 binding required');
  const source = snapshot.historicalSource, expected = plan.historicalSource;
  requireValue(source && source.commitSha === expected.commitSha && source.path === expected.path
    && source.blobSha === expected.blobSha && typeof source.content === 'string'
    && blob(source.content) === expected.blobSha && hash(source.content) === expected.sha256,
  'Gitleaks review historical source blob mismatch');
  const line = source.content.split('\n')[expected.line - 1];
  requireValue(typeof line === 'string' && hash(line) === expected.lineSha256,
    'Gitleaks review historical source line mismatch');
  // The fixed grammar proves an arrow callback assigning a repeated character to
  // a member, rather than a quoted credential. Keep receiver/member split in
  // committed evidence so reproducing this proof cannot create another finding.
  const parsed = line.match(/^  \['([^']+)', ([A-Za-z_$][\w$]*) => \{ \2\.([A-Za-z_$][\w$]*) = '([a-z])'\.repeat\((\d+)\); \}\],$/);
  const expression = plan.reviewedExpression;
  requireValue(parsed && parsed[1] === expression.testCaseLabel && parsed[2] === expression.receiverIdentifier
    && parsed[3] === expression.propertyIdentifier && parsed[4] === expression.assignedCharacter
    && Number(parsed[5]) === expression.repeatCount, 'Gitleaks reviewed fixed source grammar mismatch');
  return receipt(e4, plan);
}

export function requireGitleaksTestExpressionReviewReceipt(e4, evidence, publicCommitReview = null) {
  const plan = readGitleaksTestExpressionReviewPlan();
  requireCurrentFinding(e4, plan, publicCommitReview);
  requireValue(canonical(evidence) === canonical(receipt(e4, plan)), 'Gitleaks expression review receipt drift');
  return evidence;
}
function reviewEvidence(evidence) {
  return { reviewLayer: 'E3-I2-APPEND_ONLY-GITLEAKS', reviewCanonicalSha256: evidence.contentSha256,
    sourceCommitSha: evidence.historicalSource.commitSha, sourceBlobSha: evidence.historicalSource.blobSha,
    sourcePath: evidence.historicalSource.path, sourceLineSha256: evidence.historicalSource.lineSha256,
    evidenceCode: evidence.decision.evidenceCode, exploitPreconditionsSatisfied: false };
}

function requireDecisionIdentity(finding, evidence) {
  const historical = evidence.historicalFinding;
  requireValue(finding && ['ruleId', 'commit', 'fingerprint', 'path', 'startLine', 'endLine']
    .every(field => finding.sourceIdentity?.[field] === historical[field]), 'Gitleaks current decision source identity drift');
}

/** Retain the scanner finding and attach the one positive absent-credential review. */
export function applyGitleaksTestExpressionReview(triage, e4, snapshot) {
  requireDesignerPrototypeTriage(e4, triage);
  const evidence = verifyGitleaksTestExpressionReview(e4, snapshot), findingKey = key(evidence.historicalFinding);
  requireValue(triage.reviewedFindingCount === triage.decisions.filter(item => item.reviewEvidence).length
    && triage.currentReviewedFindingCount === triage.reviewedFindingCount, 'Gitleaks prior current review count drift');
  const publicCommitReview = snapshot?.publicCommitReviewSnapshot
    ? verifyGitleaksPublicCommitReview(e4, snapshot.publicCommitReviewSnapshot) : null;
  let decisions = triage.decisions.map(finding => {
    if (key(finding) !== findingKey) return finding;
    requireDecisionIdentity(finding, evidence);
    requireValue(finding.disposition === 'UNRESOLVED' && finding.severityBand === 'UNKNOWN' && !finding.reviewEvidence,
      'Gitleaks expression review may only review the exact unresolved finding');
    return stable({ ...finding, disposition: 'NOT_APPLICABLE',
      exploitPreconditions: ['ACTUAL_CREDENTIAL_MATERIAL_PRESENT'], reviewEvidence: reviewEvidence(evidence) });
  });
  if (publicCommitReview) decisions = applyGitleaksPublicCommitDecision(decisions, publicCommitReview);
  const appendedCount = publicCommitReview ? 2 : 1;
  const counts = {};
  for (const finding of decisions) counts[finding.disposition] = (counts[finding.disposition] || 0) + 1;
  return seal({ ...triage, schemaVersion: publicCommitReview ? 'M6_PR_E_E3_I2_TRIAGE_V5' : 'M6_PR_E_E3_I2_TRIAGE_V4',
    gitleaksTestExpressionReview: evidence, appendOnlyReviewedFindingCount: appendedCount,
    ...(publicCommitReview ? { gitleaksPublicCommitReview: publicCommitReview } : {}),
    reviewedFindingCount: triage.reviewedFindingCount + appendedCount,
    currentReviewedFindingCount: triage.currentReviewedFindingCount + appendedCount, decisions,
    summary: { ...triage.summary, dispositionCounts: counts,
      resolvedCount: decisions.length - (counts.UNRESOLVED || 0), unresolvedCount: counts.UNRESOLVED || 0 } });
}

export function requireGitleaksTestExpressionTriage(e4, triage) {
  const publicCommitReview = triage.gitleaksPublicCommitReview
    ? requireGitleaksPublicCommitReviewReceipt(e4, triage.gitleaksPublicCommitReview) : null;
  const evidence = requireGitleaksTestExpressionReviewReceipt(e4, triage.gitleaksTestExpressionReview, publicCommitReview);
  requireCanonical(triage, 'Gitleaks reviewed triage');
  requireValue(triage.repository === e4.repository && triage.commitSha === e4.commitSha,
    'Gitleaks reviewed triage exact Head mismatch');
  const currentKeys = Object.values(e4.scanners).flatMap(scanner => scanner.findings.map(key)).sort();
  requireValue(Array.isArray(triage.decisions) && canonical(triage.decisions.map(key).sort()) === canonical(currentKeys),
    'Gitleaks reviewed triage must retain every current identity');
  const reviewedCurrentCount = triage.decisions.filter(item => item.reviewEvidence).length;
  const extraReviews = triage.decisions.filter(item => item.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS');
  requireValue(triage.appendOnlyReviewedFindingCount === (publicCommitReview ? 2 : 1) && extraReviews.length === 1,
    'Gitleaks append-only review count drift');
  if (['M6_PR_E_E3_I2_TRIAGE_V4', 'M6_PR_E_E3_I2_TRIAGE_V5'].includes(triage.schemaVersion)) {
    requireValue((triage.schemaVersion === 'M6_PR_E_E3_I2_TRIAGE_V5') === Boolean(publicCommitReview), 'Gitleaks I2 schema/review drift');
    requireValue(triage.reviewedFindingCount === reviewedCurrentCount
      && triage.currentReviewedFindingCount === reviewedCurrentCount, 'Gitleaks current review count drift');
  } else {
    const serverSchema = ['M6_PR_E_E3_I3_TRIAGE_V5', 'M6_PR_E_E3_I4_TRIAGE_V6'].includes(triage.schemaVersion);
    requireValue(serverSchema || ['M6_PR_E_E3_I3_TRIAGE_V4', 'M6_PR_E_E3_I4_TRIAGE_V5'].includes(triage.schemaVersion),
      'Gitleaks reviewed triage schema mismatch');
    if (serverSchema) {
      const server = requireServerDependencyRemediation(e4, triage.serverDependencyRemediation,
        { expectedCommitSha: triage.commitSha });
      requireValue(triage.sourceServerDependencyRemediationCanonicalSha256 === server.contentSha256,
        'Gitleaks triage server dependency remediation binding mismatch');
    } else requireValue(triage.serverDependencyRemediation === undefined
      && triage.sourceServerDependencyRemediationCanonicalSha256 === undefined,
    'unexpected server dependency remediation on historical triage schema');
    const historicalKeys = (triage.historicallyRemediatedFindings || []).map(key);
    const uniqueHistoricalCount = new Set(historicalKeys).size;
    requireValue(Array.isArray(triage.historicallyRemediatedFindings)
      && uniqueHistoricalCount === historicalKeys.length
      && !historicalKeys.some(item => currentKeys.includes(item))
      && triage.historicallyRemediatedFindingCount === uniqueHistoricalCount
      && triage.cumulativeReviewedFindingCount === reviewedCurrentCount + uniqueHistoricalCount,
    'Gitleaks cumulative review count drift');
  }
  const counts = {};
  for (const item of triage.decisions) counts[item.disposition] = (counts[item.disposition] || 0) + 1;
  requireValue(canonical(triage.summary?.dispositionCounts) === canonical(counts)
    && triage.summary.unresolvedCount === (counts.UNRESOLVED || 0)
    && triage.summary.releaseBlocked === true, 'Gitleaks reviewed triage summary drift');
  const finding = triage.decisions.find(item => key(item) === key(evidence.historicalFinding));
  requireDecisionIdentity(finding, evidence);
  requireValue(finding?.disposition === 'NOT_APPLICABLE' && finding.severityBand === 'UNKNOWN'
    && canonical(finding.reviewEvidence) === canonical(reviewEvidence(evidence)), 'Gitleaks reviewed current disposition drift');
  if (publicCommitReview) requireGitleaksPublicCommitDecision(triage, publicCommitReview);
  return evidence;
}
