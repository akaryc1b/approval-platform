import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const SHA64 = /^[0-9a-f]{64}$/;
const R3C_CONTRACT_SHA256 = '5b4ed3731f05dd1ff134b83a2e9f724646fdb29ff6f799f824d55c8cb66d8cfd';
const stable = (value) => Array.isArray(value)
  ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
    : value;
const canonical = (value) => JSON.stringify(stable(value));
const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const findingSetSha256 = (ids) => sha256(`${[...ids].sort().join('\n')}\n`);
const baseContract = JSON.parse(readFileSync(new URL(
  '../../docs/m6/m6-pr-e-e3-r2b-scanner-identity-reconciliation.json',
  import.meta.url,
), 'utf8'));
const r3cContract = JSON.parse(readFileSync(new URL(
  '../../docs/m6/m6-pr-e-e3-r3c-osv-identity-reconciliation.json',
  import.meta.url,
), 'utf8'));

function requireR3cContract() {
  const { contentSha256, ...payload } = r3cContract;
  if (r3cContract.schemaVersion !== 'M6_PR_E_E3_R3C_OSV_IDENTITY_RECONCILIATION_V1'
    || r3cContract.repository !== baseContract.repository
    || contentSha256 !== R3C_CONTRACT_SHA256
    || sha256(canonical(payload)) !== contentSha256) {
    throw new Error('R3C OSV identity reconciliation contract mismatch');
  }
  const previous = r3cContract.previousReviewedBoundary || {};
  const reviewed = baseContract.expectedCurrentIdentitySets?.osv || {};
  const current = r3cContract.currentOsvIdentitySet || {};
  const delta = r3cContract.identityDelta || {};
  if (previous.findingCount !== reviewed.findingCount
    || previous.findingSetSha256 !== reviewed.findingSetSha256
    || current.sourceClass !== baseContract.acceptedIdentitySets?.osv?.sourceClass
    || !Number.isInteger(current.findingCount)
    || !SHA64.test(current.findingSetSha256 || '')
    || delta.retainedFindingCount !== previous.findingCount
    || delta.retainedFindingSetSha256 !== previous.findingSetSha256
    || delta.addedFindingCount !== 3
    || !Array.isArray(delta.addedFindings)
    || delta.addedFindings.length !== 3
    || delta.removedFindingCount !== 0
    || canonical(delta.removedFindingIds || []) !== '[]'
    || current.findingCount !== previous.findingCount + delta.addedFindingCount) {
    throw new Error('R3C OSV identity delta contract mismatch');
  }
  const decision = r3cContract.decision || {};
  if (decision.addedFindingsDisposition !== 'UNRESOLVED'
    || decision.releaseBlocked !== true
    || decision.securityClosureAccepted !== false
    || decision.prb16Closed !== false
    || decision.prb17Closed !== false) {
    throw new Error('R3C OSV decision boundary mismatch');
  }
  return r3cContract;
}

function requireSet(label, ids, expected) {
  if (!expected
    || ids.length !== expected.findingCount
    || findingSetSha256(ids) !== expected.findingSetSha256) {
    throw new Error(`R3C ${label} identity-set drift`);
  }
}

function members(actual, required, label, findingId) {
  const values = new Set(actual || []);
  for (const item of required || []) {
    if (!values.has(item)) throw new Error(`R3C ${label} drift ${findingId} ${item}`);
  }
  return [...values].sort();
}

function objects(actual, required, label, findingId) {
  const values = (actual || []).map(stable);
  const identities = new Set(values.map(canonical));
  for (const item of required || []) {
    if (!identities.has(canonical(stable(item)))) {
      throw new Error(`R3C ${label} drift ${findingId}`);
    }
  }
  return values.sort((left, right) => canonical(left).localeCompare(canonical(right)));
}

function reviewFinding(actual, expected) {
  if (!actual
    || actual.sourceClass !== 'E4_OSV_SCANNER'
    || actual.findingId !== expected.findingId
    || actual.upstreamFindingId !== expected.upstreamFindingId) {
    throw new Error(`R3C reviewed OSV finding absent ${expected.findingId}`);
  }
  for (const key of ['ecosystem', 'name', 'version']) {
    if (actual.package?.[key] !== expected.package?.[key]) {
      throw new Error(`R3C reviewed OSV package drift ${expected.findingId} ${key}`);
    }
  }
  if (!['COMPILE_RUNTIME', 'BUILD_PLUGIN'].includes(expected.graphClass)
    || expected.disposition !== 'UNRESOLVED'
    || (Object.hasOwn(expected, 'releaseBlocking') && expected.releaseBlocking !== true)) {
    throw new Error(`R3C reviewed OSV disposition drift ${expected.findingId}`);
  }
  return stable({
    findingId: actual.findingId,
    upstreamFindingId: actual.upstreamFindingId,
    aliases: members(actual.aliases, expected.requiredAliases, 'alias', expected.findingId),
    package: actual.package,
    componentRefs: members(
      actual.componentRefs,
      expected.requiredComponentRefs,
      'component reference',
      expected.findingId,
    ),
    scopes: members(actual.scopes, expected.requiredScopes, 'scope', expected.findingId),
    fixedVersions: members(
      actual.fixedVersions,
      expected.fixedVersionsObserved,
      'fixed version',
      expected.findingId,
    ),
    upstreamSeverity: objects(
      actual.upstreamSeverity,
      expected.upstreamSeverityObserved,
      'upstream severity',
      expected.findingId,
    ),
    graphClass: expected.graphClass,
    disposition: 'UNRESOLVED',
    releaseBlocking: true,
  });
}

export function reviewCurrentOsvIdentitySetR3c(scanner) {
  const contract = requireR3cContract();
  if (!scanner
    || scanner.scanCompleted !== true
    || scanner.rawReportRetained !== false
    || !Array.isArray(scanner.findings)
    || scanner.findings.length !== scanner.findingCount) {
    throw new Error('R3C complete current OSV evidence required');
  }
  const ids = scanner.findings.map((finding) => {
    if (finding.sourceClass !== contract.currentOsvIdentitySet.sourceClass
      || !SHA64.test(finding.findingId || '')) {
      throw new Error('R3C current OSV finding identity drift');
    }
    return finding.findingId;
  }).sort();
  if (new Set(ids).size !== ids.length) throw new Error('R3C duplicate current OSV identity');
  if (ids.length !== contract.currentOsvIdentitySet.findingCount
    || findingSetSha256(ids) !== contract.currentOsvIdentitySet.findingSetSha256) {
    return null;
  }

  const latestAdditions = contract.identityDelta.addedFindings;
  const latestAdditionIds = new Set(latestAdditions.map((finding) => finding.findingId));
  const priorReviewedIds = ids.filter((findingId) => !latestAdditionIds.has(findingId));
  requireSet('prior reviewed OSV', priorReviewedIds, baseContract.expectedCurrentIdentitySets.osv);

  const baseAdditions = baseContract.reviewedAddedOsvFindings || [];
  const allAdditions = [...baseAdditions, ...latestAdditions];
  if (baseAdditions.length !== 2
    || latestAdditions.length !== 3
    || new Set(allAdditions.map((finding) => finding.findingId)).size !== 5) {
    throw new Error('R3C exactly five reviewed OSV additions required');
  }
  const allAdditionIds = new Set(allAdditions.map((finding) => finding.findingId));
  const historicalIds = ids.filter((findingId) => !allAdditionIds.has(findingId));
  requireSet('retained historical OSV', historicalIds, baseContract.acceptedIdentitySets.osv);

  const byId = new Map(scanner.findings.map((finding) => [finding.findingId, finding]));
  const addedOsvFindings = allAdditions.map((expected) => reviewFinding(byId.get(expected.findingId), expected));
  return stable({
    osvIdentityMode: 'REVIEWED_OSV_DATABASE_DRIFT_IDENTITY_SET_R3C',
    acceptedOsvIdentitySetUnchanged: true,
    acceptedOsvIdentitySetMatched: false,
    reviewedCurrentOsvIdentitySetMatched: false,
    latestReviewedOsvIdentitySetMatched: true,
    currentOsvFindingReviewRequired: false,
    currentOsvFindingCount: ids.length,
    currentOsvFindingSetSha256: findingSetSha256(ids),
    retainedHistoricalOsvFindingCount: historicalIds.length,
    retainedHistoricalOsvFindingSetSha256: findingSetSha256(historicalIds),
    latestReviewedPriorOsvFindingCount: priorReviewedIds.length,
    latestReviewedPriorOsvFindingSetSha256: findingSetSha256(priorReviewedIds),
    addedOsvFindingCount: addedOsvFindings.length,
    addedOsvFindings,
    unreviewedCurrentOsvFindingCount: 0,
    unreviewedCurrentOsvFindings: [],
    latestReviewedContractContentSha256: contract.contentSha256,
    latestDatabaseDriftRunId: contract.databaseDriftObservation.runId,
    scannerObservationVersion: contract.databaseDriftObservation.scannerVersion,
    scannerObservationSourceCommit: contract.databaseDriftObservation.scannerSourceCommit,
    scannerObservationBinarySha256: contract.databaseDriftObservation.scannerBinarySha256,
    scannerObservationIsHistoricalEvidenceOnly: true,
  });
}
