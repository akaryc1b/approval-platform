#!/usr/bin/env node
import { createHash } from 'node:crypto';

import { applyReviewedFindings } from './m6-pr-e-e3-apply-reviewed-findings.mjs';

const SHA40 = /^[0-9a-f]{40}$/;
const SHA64 = /^[0-9a-f]{64}$/;
const SCHEMA = 'M6_PR_E_E3_R2B_SEMGREP_FINDING_IDENTITY_TRANSITION_V1';
const TRIAGE_SCHEMA = 'M6_PR_E_E3_I2_TRIAGE_V2';
const EXPECTED_PLAN_CANONICAL_SHA256 = '3672eddf3ee72bb84a32e8cba56e64948f833b3063883606221e77fdb4d650bf';
const ACCEPTED_PRIOR_I2_CANONICAL_SHA256 = '48f3fe8a77194e7b970718d09ba2c7b434e24f2bef8f15a3f253570ec837d349';
const ACCEPTED_REVIEW_BASIS_HEAD = '234ed3d41b049ca9475e58e29e1204587a992693';
const ACCEPTED_REVIEW_BASIS_INTAKE_SHA256 = '9f0f5b9ecd4604492a1b250cae3b8d21bcd658efd4d9646b46aea1250436fbec';

const stable = (value) => Array.isArray(value)
  ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
    : value;
const canonical = (value) => JSON.stringify(stable(value));
const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const gitBlobSha = (content) => {
  const bytes = Buffer.from(content, 'utf8');
  return createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
};
const semgrepFindingId = ({ ruleId, scannerPath, startLine, startColumn }) => sha256(
  ['SEMGREP', ruleId, scannerPath, String(startLine), String(startColumn)].join('\0'),
);

function requireExactPlan(plan) {
  if (!plan || plan.schemaVersion !== SCHEMA) throw new Error('R2B Semgrep identity transition plan required');
  if (plan.repository !== 'akaryc1b/approval-platform') throw new Error('identity transition repository mismatch');
  if (!SHA64.test(plan.contentSha256 || '')) throw new Error('identity transition plan canonical SHA-256 required');
  if (plan.contentSha256 !== EXPECTED_PLAN_CANONICAL_SHA256) throw new Error('unexpected identity transition plan canonical SHA-256');
  const { contentSha256, ...payload } = plan;
  if (sha256(canonical(payload)) !== contentSha256) throw new Error('identity transition plan canonical mismatch');
  if (plan.priorI2CanonicalSha256 !== ACCEPTED_PRIOR_I2_CANONICAL_SHA256) throw new Error('accepted prior I2 canonical SHA-256 mismatch');
  if (plan.reviewBasisHead !== ACCEPTED_REVIEW_BASIS_HEAD) throw new Error('accepted review basis Head mismatch');
  if (plan.reviewBasisIntakeCanonicalSha256 !== ACCEPTED_REVIEW_BASIS_INTAKE_SHA256) throw new Error('accepted review basis intake SHA-256 mismatch');
  if (plan.expectedTransitionCount !== 1 || !Array.isArray(plan.transitions) || plan.transitions.length !== 1) {
    throw new Error('exactly one R2B Semgrep identity transition required');
  }
  const invariants = plan.invariants || {};
  for (const key of [
    'scannerSuppressionAdded',
    'severityDowngradeAdded',
    'exceptionAdded',
    'broadPathExclusionAdded',
    'findingDeletionClaimed',
    'readyAuthorized',
    'mergeAuthorized',
    'deploymentAuthorized',
    'releaseAuthorized',
  ]) {
    if (invariants[key] !== false) throw new Error(`identity transition invariant violated ${key}`);
  }
  return payload;
}

function requireTransition(transition, intake, review, currentSources) {
  if (transition.sourceClass !== 'E4_SEMGREP') throw new Error('identity transition must be Semgrep-only');
  if (!SHA64.test(transition.historicalFindingId || '') || !SHA64.test(transition.currentFindingId || '')) {
    throw new Error('historical and current finding IDs must be SHA-256');
  }
  if (transition.historicalFindingId === transition.currentFindingId) throw new Error('identity transition IDs must differ');
  if (!SHA40.test(transition.historicalSourceBlobSha || '') || !SHA40.test(transition.currentSourceBlobSha || '')) {
    throw new Error('identity transition source blobs must be Git SHAs');
  }
  if (transition.reasonCode !== 'R2B_E2_GENERATOR_SOURCE_LINE_RELOCATION') throw new Error('unreviewed identity transition reason');
  if (transition.semanticReviewRetained !== true || transition.dispositionChanged !== false) {
    throw new Error('identity transition must retain the historical semantic review');
  }
  if (!transition.sourcePath || transition.scannerPath !== `/src/${transition.sourcePath}`) {
    throw new Error('identity transition source path mismatch');
  }
  if (!transition.ruleId || transition.historicalDisposition !== 'NOT_APPLICABLE') {
    throw new Error('identity transition review identity mismatch');
  }
  if (!transition.evidenceCode || !transition.sourceExpression || !SHA64.test(transition.sourceExpressionSha256 || '')) {
    throw new Error('identity transition source expression evidence required');
  }
  if (sha256(transition.sourceExpression) !== transition.sourceExpressionSha256) {
    throw new Error('identity transition source expression digest mismatch');
  }
  const oldLocation = transition.historicalLocation || {};
  const newLocation = transition.currentLocation || {};
  for (const [label, location] of [['historical', oldLocation], ['current', newLocation]]) {
    for (const key of ['startLine', 'startColumn', 'endLine', 'endColumn']) {
      if (!Number.isInteger(location[key]) || location[key] < 1) throw new Error(`${label} ${key} required`);
    }
  }
  const historicalId = semgrepFindingId({
    ruleId: transition.ruleId,
    scannerPath: transition.scannerPath,
    startLine: oldLocation.startLine,
    startColumn: oldLocation.startColumn,
  });
  const currentId = semgrepFindingId({
    ruleId: transition.ruleId,
    scannerPath: transition.scannerPath,
    startLine: newLocation.startLine,
    startColumn: newLocation.startColumn,
  });
  if (historicalId !== transition.historicalFindingId) throw new Error('historical Semgrep finding identity derivation mismatch');
  if (currentId !== transition.currentFindingId) throw new Error('current Semgrep finding identity derivation mismatch');

  const reviewed = (review.reviewedFindings || []).find((item) =>
    item.sourceClass === transition.sourceClass && item.findingId === transition.historicalFindingId,
  );
  if (!reviewed) throw new Error('historical reviewed finding missing from I2 review');
  if (reviewed.sourceBlobSha !== transition.historicalSourceBlobSha
      || reviewed.sourcePath !== transition.sourcePath
      || reviewed.disposition !== transition.historicalDisposition
      || reviewed.evidenceCode !== transition.evidenceCode
      || reviewed.exploitPreconditionsSatisfied !== false
      || (reviewed.exception ?? null) !== null) {
    throw new Error('historical reviewed finding evidence drift');
  }

  const historicalKey = `${transition.sourceClass}:${transition.historicalFindingId}`;
  const currentKey = `${transition.sourceClass}:${transition.currentFindingId}`;
  const intakeById = new Map((intake.decisions || []).map((item) => [`${item.sourceClass}:${item.findingId}`, item]));
  if (intakeById.has(historicalKey)) throw new Error('historical Semgrep identity unexpectedly remains current');
  const current = intakeById.get(currentKey);
  if (!current) throw new Error('current Semgrep identity absent from intake');
  const identity = current.sourceIdentity || {};
  if (current.disposition !== 'UNRESOLVED'
      || current.severityBand !== reviewed.severityBand
      || identity.ruleId !== transition.ruleId
      || identity.path !== transition.sourcePath
      || identity.startLine !== newLocation.startLine
      || identity.startColumn !== newLocation.startColumn
      || identity.endLine !== newLocation.endLine
      || identity.endColumn !== newLocation.endColumn) {
    throw new Error('current Semgrep intake identity drift');
  }

  const source = currentSources?.[transition.sourcePath];
  if (!source || typeof source.content !== 'string' || !SHA40.test(source.blobSha || '')) {
    throw new Error(`current source evidence required ${transition.sourcePath}`);
  }
  const computedBlob = gitBlobSha(source.content);
  if (computedBlob !== source.blobSha || computedBlob !== transition.currentSourceBlobSha) {
    throw new Error('current Semgrep source blob drift');
  }
  const line = source.content.split(/\r?\n/)[newLocation.startLine - 1];
  if (typeof line !== 'string' || sha256(line) !== transition.currentSourceLineSha256) {
    throw new Error('current Semgrep source line drift');
  }
  const expression = line.slice(
    newLocation.startColumn - 1,
    newLocation.startColumn - 1 + transition.sourceExpression.length,
  );
  if (expression !== transition.sourceExpression) throw new Error('current Semgrep source expression drift');

  return { reviewed, current };
}

export function applyReviewedFindingsWithIdentityTransitions(
  intake,
  review,
  transitionPlan,
  { currentSources = {} } = {},
) {
  if (!intake || !review) throw new Error('intake and review required');
  requireExactPlan(transitionPlan);
  if (intake.repository !== transitionPlan.repository || review.repository !== transitionPlan.repository) {
    throw new Error('identity transition repository mismatch');
  }
  if (review.reviewBasisHead !== transitionPlan.reviewBasisHead
      || review.reviewBasisIntakeCanonicalSha256 !== transitionPlan.reviewBasisIntakeCanonicalSha256) {
    throw new Error('identity transition I2 review basis mismatch');
  }

  const seenHistorical = new Set();
  const seenCurrent = new Set();
  const transitionRecords = [];
  const replacements = new Map();
  for (const transition of transitionPlan.transitions) {
    if (seenHistorical.has(transition.historicalFindingId) || seenCurrent.has(transition.currentFindingId)) {
      throw new Error('duplicate Semgrep identity transition');
    }
    seenHistorical.add(transition.historicalFindingId);
    seenCurrent.add(transition.currentFindingId);
    const { reviewed } = requireTransition(transition, intake, review, currentSources);
    replacements.set(`${transition.sourceClass}:${transition.historicalFindingId}`, transition);
    transitionRecords.push(stable({
      sourceClass: transition.sourceClass,
      ruleId: transition.ruleId,
      sourcePath: transition.sourcePath,
      historicalFindingId: transition.historicalFindingId,
      currentFindingId: transition.currentFindingId,
      historicalSourceBlobSha: transition.historicalSourceBlobSha,
      currentSourceBlobSha: transition.currentSourceBlobSha,
      historicalLocation: transition.historicalLocation,
      currentLocation: transition.currentLocation,
      historicalDisposition: reviewed.disposition,
      evidenceCode: reviewed.evidenceCode,
      reasonCode: transition.reasonCode,
      semanticReviewRetained: true,
      dispositionChanged: false,
    }));
  }

  const mappedReview = stable({
    ...review,
    reviewedFindings: (review.reviewedFindings || []).map((item) => {
      const transition = replacements.get(`${item.sourceClass}:${item.findingId}`);
      return transition
        ? { ...item, findingId: transition.currentFindingId, sourceBlobSha: transition.currentSourceBlobSha }
        : item;
    }),
  });
  const base = applyReviewedFindings(intake, mappedReview);
  if (base.reviewedFindingCount !== (review.reviewedFindings || []).length) {
    throw new Error('identity transition reviewed finding count mismatch');
  }

  const transitionByCurrent = new Map(transitionRecords.map((item) => [
    `${item.sourceClass}:${item.currentFindingId}`,
    item,
  ]));
  const decisions = base.decisions.map((decision) => {
    const transition = transitionByCurrent.get(`${decision.sourceClass}:${decision.findingId}`);
    if (!transition) return decision;
    return stable({
      ...decision,
      reviewEvidence: {
        ...decision.reviewEvidence,
        identityTransition: transition,
      },
    });
  });
  const { contentSha256: ignored, ...basePayload } = base;
  const payload = stable({
    ...basePayload,
    schemaVersion: TRIAGE_SCHEMA,
    sourceLegacyI2CanonicalSha256: transitionPlan.priorI2CanonicalSha256,
    identityTransitionPlanCanonicalSha256: transitionPlan.contentSha256,
    historicalReviewedFindingCount: (review.reviewedFindings || []).length,
    currentReviewedFindingCount: base.reviewedFindingCount,
    relocatedReviewedFindingCount: transitionRecords.length,
    identityTransitions: transitionRecords,
    decisions,
  });
  return stable({ ...payload, contentSha256: sha256(canonical(payload)) });
}

export const canonicalReviewedTriageWithIdentityTransitions = canonical;
