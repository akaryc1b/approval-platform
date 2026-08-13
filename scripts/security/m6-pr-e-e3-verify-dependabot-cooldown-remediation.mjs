#!/usr/bin/env node
import { createHash } from 'node:crypto';

const SHA40 = /^[0-9a-f]{40}$/;
const SHA64 = /^[0-9a-f]{64}$/;
const ACCEPTED_R2A_CANONICAL_SHA256 = '65e2036c1738f2b4eee56cc3bfc154eb9856401ec5ece2673268eb45c96270cc';
const R2B_RULE_COUNTS = Object.freeze({
  'zizmor/artipacked': 14,
  'zizmor/template-injection': 1,
  'zizmor/unpinned-uses': 43,
});
const stable = (value) => Array.isArray(value)
  ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
    : value;
const canonical = (value) => JSON.stringify(stable(value));
const sha256 = (value) => createHash('sha256').update(value).digest('hex');

function currentRuleCounts(zizmor) {
  const counts = {};
  for (const finding of zizmor.findings) counts[finding.ruleId] = (counts[finding.ruleId] || 0) + 1;
  return counts;
}

function historicalR2ARemediations(plan, currentIds) {
  const remediated = [];
  for (const item of plan.remediatedFindings || []) {
    const key = `${item.sourceClass}:${item.findingId}`;
    if (item.ruleId !== 'zizmor/dependabot-cooldown'
      || item.path !== '.github/dependabot.yml'
      || item.priorDisposition !== 'APPLICABLE') {
      throw new Error(`invalid R2A historical identity ${key}`);
    }
    if (currentIds.has(key)) throw new Error(`R2A remediated finding still present ${key}`);
    remediated.push(stable({
      ...item,
      currentStatus: 'REMEDIATED_BY_DEPENDABOT_COOLDOWN_AND_ABSENT_FROM_CURRENT_ZIZMOR',
    }));
  }
  if (remediated.length !== 3) throw new Error(`R2A must retain exactly 3 historical findings, got ${remediated.length}`);
  return remediated;
}

function requireExactSubsequentWorkflowPlan(r2aPlan, workflowPlan, currentDependabotBlobSha) {
  if (!workflowPlan || workflowPlan.schemaVersion !== 'M6_PR_E_E3_R2B_WORKFLOW_SUPPLY_CHAIN_REMEDIATION_PLAN_V1') {
    throw new Error('exact R2B workflow remediation plan required');
  }
  if (workflowPlan.repository !== r2aPlan.repository) throw new Error('R2B/R2A repository mismatch');
  if (!SHA40.test(workflowPlan.priorAcceptedHead || '')
    || workflowPlan.priorAcceptedHead !== workflowPlan.priorR2AHead) {
    throw new Error('R2B prior accepted Head mismatch');
  }
  if (workflowPlan.priorR2ACanonicalSha256 !== ACCEPTED_R2A_CANONICAL_SHA256) {
    throw new Error('R2B accepted historical R2A canonical mismatch');
  }
  if (workflowPlan.priorI4FindingSetSha256 !== r2aPlan.priorI4FindingSetSha256) {
    throw new Error('R2B/R2A I4 finding-set mismatch');
  }
  if (workflowPlan.dependabotBlobShaRetained !== r2aPlan.targetDependabotBlobSha
    || workflowPlan.dependabotBlobShaRetained !== currentDependabotBlobSha) {
    throw new Error('R2B retained Dependabot blob mismatch');
  }
  if (workflowPlan.expectedCurrentZizmorFindingCount !== 0) {
    throw new Error('R2B exact zero zizmor target required');
  }
  if (workflowPlan.workflowInventory?.expectedFileCount !== 9
    || Object.keys(workflowPlan.workflowInventory?.sourceBlobs || {}).length !== 9
    || Object.keys(workflowPlan.workflowInventory?.targetBlobs || {}).length !== 9) {
    throw new Error('R2B exact workflow inventory required');
  }
  if (workflowPlan.actionIdentityVerification?.reviewedSymbolicRefCount !== 6
    || workflowPlan.actionIdentityVerification?.upstreamSymbolicRefDriftDetected !== false
    || workflowPlan.actionIdentityVerification?.newThirdPartyActionRefCount !== 0
    || workflowPlan.actionIdentityVerification?.majorUpgradeApplied !== false) {
    throw new Error('R2B reviewed Action identity boundary mismatch');
  }
  if (workflowPlan.invariants?.dependabotChanged !== false
    || workflowPlan.invariants?.scannerSuppressionAdded !== false
    || workflowPlan.invariants?.scannerIgnoreAdded !== false
    || workflowPlan.invariants?.severityDowngradeAdded !== false
    || workflowPlan.invariants?.exceptionAdded !== false) {
    throw new Error('R2B evidence boundary widened');
  }

  const expectedHistorical = workflowPlan.expectedHistoricalFindingCounts || {};
  if (expectedHistorical.total !== 58) throw new Error('R2B historical total mismatch');
  const identities = new Set();
  for (const [ruleId, expectedCount] of Object.entries(R2B_RULE_COUNTS)) {
    if (workflowPlan.expectedCurrentRuleCounts?.[ruleId] !== 0) {
      throw new Error(`R2B current rule target mismatch ${ruleId}`);
    }
    if (expectedHistorical[ruleId] !== expectedCount) {
      throw new Error(`R2B historical rule count mismatch ${ruleId}`);
    }
    const rows = workflowPlan.historicalFindings?.[ruleId];
    if (!Array.isArray(rows) || rows.length !== expectedCount) {
      throw new Error(`R2B historical finding inventory mismatch ${ruleId}`);
    }
    for (const row of rows) {
      if (!Array.isArray(row) || row.length !== 4 || !SHA64.test(row[0] || '')
        || !/^\.github\/workflows\/[^/]+\.ya?ml$/.test(row[1] || '')
        || !Number.isInteger(row[2]) || row[2] < 1 || typeof row[3] !== 'string') {
        throw new Error(`R2B historical finding identity invalid ${ruleId}`);
      }
      if (identities.has(row[0])) throw new Error(`R2B duplicate historical finding ${row[0]}`);
      identities.add(row[0]);
    }
  }
  if (identities.size !== 58) throw new Error(`R2B historical identity total mismatch ${identities.size}`);

  const expectedR2A = (r2aPlan.remediatedFindings || []).map((item) => stable({
    ...item,
    currentStatus: 'REMEDIATED_BY_DEPENDABOT_COOLDOWN_AND_ABSENT_FROM_CURRENT_ZIZMOR',
  }));
  if (canonical(workflowPlan.priorR2ARemediatedFindings || []) !== canonical(expectedR2A)) {
    throw new Error('R2B prior R2A remediation identity drift');
  }
}

export function verifyDependabotCooldownRemediation(
  e4,
  plan,
  currentDependabotBlobSha,
  { subsequentWorkflowRemediationPlan = null } = {},
) {
  if (!e4 || !plan) throw new Error('E4 evidence and R2A plan required');
  if (e4.repository !== plan.repository) throw new Error('R2A repository mismatch');
  if (!SHA40.test(currentDependabotBlobSha || '')) throw new Error('current Dependabot blob SHA required');
  if (currentDependabotBlobSha !== plan.targetDependabotBlobSha) {
    throw new Error(`Dependabot target blob mismatch ${currentDependabotBlobSha}`);
  }
  const zizmor = e4.scanners?.zizmor;
  if (!zizmor || zizmor.scanCompleted !== true || zizmor.rawReportRetained !== false
    || !Array.isArray(zizmor.findings) || zizmor.findings.length !== zizmor.findingCount) {
    throw new Error('complete current zizmor evidence required');
  }

  const counts = currentRuleCounts(zizmor);
  const subsequent = subsequentWorkflowRemediationPlan !== null;
  if (subsequent) {
    requireExactSubsequentWorkflowPlan(plan, subsequentWorkflowRemediationPlan, currentDependabotBlobSha);
    if (zizmor.findingCount !== subsequentWorkflowRemediationPlan.expectedCurrentZizmorFindingCount) {
      throw new Error(`R2A/R2B current zizmor count mismatch ${zizmor.findingCount}`);
    }
    for (const ruleId of Object.keys(plan.expectedCurrentRuleCounts || {})) {
      const expected = ruleId === 'zizmor/dependabot-cooldown'
        ? 0
        : subsequentWorkflowRemediationPlan.expectedCurrentRuleCounts?.[ruleId];
      if ((counts[ruleId] || 0) !== expected) {
        throw new Error(`R2A/R2B zizmor rule count mismatch ${ruleId} ${(counts[ruleId] || 0)} != ${expected}`);
      }
    }
  } else {
    if (zizmor.findingCount !== plan.expectedCurrentZizmorFindingCount) {
      throw new Error(`R2A current zizmor count mismatch ${zizmor.findingCount}`);
    }
    for (const [ruleId, expected] of Object.entries(plan.expectedCurrentRuleCounts || {})) {
      if ((counts[ruleId] || 0) !== expected) {
        throw new Error(`R2A zizmor rule count mismatch ${ruleId} ${(counts[ruleId] || 0)} != ${expected}`);
      }
    }
  }

  const currentIds = new Set(zizmor.findings.map((finding) => `${finding.sourceClass}:${finding.findingId}`));
  const remediated = historicalR2ARemediations(plan, currentIds);
  const currentCounts = stable(Object.fromEntries(
    Object.keys(plan.expectedCurrentRuleCounts).sort().map((ruleId) => [ruleId, counts[ruleId] || 0]),
  ));

  if (!subsequent) {
    const payload = stable({
      schemaVersion: 'M6_PR_E_E3_R2A_DEPENDABOT_COOLDOWN_REMEDIATION_EVIDENCE_V1',
      repository: e4.repository,
      commitSha: e4.commitSha,
      sourceE4CanonicalSha256: e4.contentSha256,
      priorAcceptedHead: plan.priorAcceptedHead,
      priorI4FindingSetSha256: plan.priorI4FindingSetSha256,
      sourceDependabotBlobSha: plan.sourceDependabotBlobSha,
      currentDependabotBlobSha,
      cooldownDefaultDays: plan.cooldownDefaultDays,
      remediatedFindings: remediated,
      currentZizmorFindingCount: zizmor.findingCount,
      currentRuleCounts: currentCounts,
      releaseBlocked: true,
      reasonCodes: [
        'AUTHORITATIVE_GITHUB_ALERT_INVENTORY_EVIDENCE_UNAVAILABLE',
        'E3_SCANNER_FINDINGS_UNRESOLVED',
        'E3_APPLICABLE_FINDINGS_REQUIRE_REMEDIATION',
      ],
    });
    return stable({ ...payload, contentSha256: sha256(canonical(payload)) });
  }

  const payload = stable({
    schemaVersion: 'M6_PR_E_E3_R2A_DEPENDABOT_COOLDOWN_REMEDIATION_EVIDENCE_V2',
    repository: e4.repository,
    commitSha: e4.commitSha,
    sourceE4CanonicalSha256: e4.contentSha256,
    priorAcceptedHead: plan.priorAcceptedHead,
    priorI4FindingSetSha256: plan.priorI4FindingSetSha256,
    sourceDependabotBlobSha: plan.sourceDependabotBlobSha,
    currentDependabotBlobSha,
    cooldownDefaultDays: plan.cooldownDefaultDays,
    remediatedFindings: remediated,
    currentZizmorFindingCount: zizmor.findingCount,
    currentRuleCounts: currentCounts,
    acceptedHistoricalR2ACanonicalSha256: ACCEPTED_R2A_CANONICAL_SHA256,
    subsequentWorkflowRemediationPlanCanonicalSha256: sha256(canonical(subsequentWorkflowRemediationPlan)),
    releaseBlocked: true,
    reasonCodes: [
      'AUTHORITATIVE_GITHUB_ALERT_INVENTORY_EVIDENCE_UNAVAILABLE',
      'E3_SCANNER_FINDINGS_UNRESOLVED',
    ],
  });
  return stable({ ...payload, contentSha256: sha256(canonical(payload)) });
}

export const canonicalDependabotCooldownRemediation = canonical;
