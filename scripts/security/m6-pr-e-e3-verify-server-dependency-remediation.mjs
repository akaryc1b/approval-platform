import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { acceptedE2GraphProjection } from './m6-pr-e-e2-generate-sbom.mjs';
import { BASE_GRAPH, SERVER_DEPENDENCY_GRAPH, verifyObservabilityGraph, requirePreservedGraph }
  from './observability-dependency-graph.mjs';
import { verifyOsvCoverage } from './osv-scan-coverage.mjs';
import { requireCompleteCurrentE4 } from './scanner-evidence-provenance.mjs';

const REPOSITORY = 'akaryc1b/approval-platform';
const PLAN_HASH = '370e920ee0dcfd7f3cf7fec0a4078b0c0046b69089e5c64fca5846a8202e142d';
const BASELINE_HASH = '03ad70bfe23e655a599eb2454402b470845f0f3e46caaaad9ff21fa243933227';
const SHA40 = /^[0-9a-f]{40}$/, SHA64 = /^[0-9a-f]{64}$/;
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const digest = value => hash(canonical(value));
const requireValue = (condition, message) => { if (!condition) throw new Error(message); };
const same = (left, right) => canonical(left) === canonical(right);
const signed = payload => stable({ ...payload, contentSha256: digest(payload) });
function requireDigest(value, label) {
  const { contentSha256, ...payload } = value || {};
  requireValue(SHA64.test(contentSha256 || '') && digest(payload) === contentSha256, `${label} canonical digest mismatch`);
}
function pinned(path, expected) {
  const raw = readFileSync(new URL(`../../${path}`, import.meta.url));
  requireValue(hash(raw) === expected, `server remediation retained evidence drift: ${path}`);
  return JSON.parse(raw);
}

/** A new immutable plan supplements the historical I3 record; it never rewrites it. */
export function readServerDependencyRemediationPlan() {
  const plan = pinned('docs/m6/m6-pr-e-e3-r4-server-dependency-remediation.json', PLAN_HASH);
  requireDigest(plan, 'server remediation plan');
  requireValue(plan.schemaVersion === 'APPROVAL_SERVER_DEPENDENCY_REMEDIATION_PLAN_V1'
    && plan.repository === REPOSITORY && plan.targetE2GraphDigest === SERVER_DEPENDENCY_GRAPH
    && plan.historicalFindingCount === 2 && plan.remediatedFindings.length === 2
    && plan.expectedInputPackageCount === 535 && plan.findingReviewRequired === true && plan.releaseBlocked === true
    && same(plan.suppressions, []) && same(plan.exceptions, [])
    && Object.values(plan.requiredEvidence).every(value => value === true), 'server remediation plan identity/permission drift');
  const review = pinned(plan.historicalReviewPath, plan.historicalReviewFileSha256);
  const evidence = pinned(plan.advisoryEvidencePath, plan.advisoryEvidenceFileSha256);
  requireValue(review.repository === REPOSITORY && review.reviewBasisHead === plan.reviewBasisHead
    && review.reviewBasisE2GraphDigest === plan.priorE2GraphDigest, 'server remediation historical review binding');
  const ids = new Set();
  for (const item of plan.remediatedFindings) {
    requireValue(!ids.has(item.findingId), 'duplicate server remediation identity'); ids.add(item.findingId);
    const prior = review.reviewedFindings.find(row => row.findingId === item.findingId);
    requireValue(prior && digest(prior) === item.priorReviewRecordSha256
      && item.sourceClass === 'E4_OSV_SCANNER' && prior.sourceClass === item.sourceClass
      && prior.upstreamFindingId === item.upstreamFindingId && same(prior.aliases, item.aliases)
      && prior.disposition === item.priorDisposition && prior.componentRef === item.priorComponentRef
      && item.requiredAbsentFromCurrentScanner === true, 'server remediation historical finding drift');
    const source = evidence.sources.find(row => row.advisory.id === item.upstreamFindingId);
    requireValue(source && source.sourceUrl === `https://api.osv.dev/v1/vulns/${item.upstreamFindingId}`
      && SHA64.test(source.rawResponseSha256) && same(source.advisory.aliases, item.aliases), 'server advisory identity drift');
    const affected = source.advisory.affected[item.affectedEntryIndex];
    requireValue(affected?.package?.ecosystem === item.package.ecosystem
      && affected.package.name === item.package.name && affected.versions.includes(item.package.priorVersion)
      && !affected.versions.includes(item.package.targetVersion), 'server advisory exact affected package/version mismatch');
    requireValue(affected.ranges.some(range => range.type === 'ECOSYSTEM'
      && range.events.some(event => event.fixed === item.fixedSince)), 'server advisory branch-specific fixed version mismatch');
    const fixed = item.fixedSince.split('.'), target = item.package.targetVersion.split('.');
    requireValue(/^\d+\.\d+\.\d+$/.test(item.fixedSince) && /^\d+\.\d+\.\d+$/.test(item.package.targetVersion)
      && fixed[0] === target[0] && fixed[1] === target[1] && Number(target[2]) >= Number(fixed[2]),
    'server fixed version must use the reviewed affected branch');
    requireValue(item.targetComponentRef === `pkg:maven/${item.package.name.replace(':', '/')}@${item.package.targetVersion}?type=jar`,
      'server fixed component identity mismatch');
  }
  return plan;
}

function requireFreshFullEvidence(e4, expectedHead) {
  requireValue(SHA40.test(expectedHead || '') && e4?.commitSha === expectedHead, 'server remediation independent current Head mismatch');
  requireCompleteCurrentE4(e4, REPOSITORY);
  requireDigest(e4, 'server remediation current E4');
  requireValue(e4.schemaVersion === 'M6_PR_E_E4_SCANNER_EVIDENCE_V1' && e4.repository === REPOSITORY
    && e4.allScannersCompleted === true && e4.rawScannerReportsRetained === false && e4.candidateSecretMaterialRetained === false
    && e4.workstreamReleaseBlocked === true && e4.authoritativeGitHubInventoryStillUnavailable === true,
  'server remediation requires full current E4; partial OSV is not admission evidence');
  const checkout = e4.checkout;
  requireValue(checkout?.expectedHeadSha === expectedHead && checkout.exactTreeMatches === true && checkout.trackedWorktreeClean === true
    && SHA40.test(checkout.checkedOutSha || '') && SHA40.test(checkout.checkedOutTreeSha || '')
    && checkout.checkedOutTreeSha === checkout.expectedHeadTreeSha, 'server remediation scanner source checkout mismatch');
  const baseline = pinned('docs/m6/m6-pr-e-e4-scanner-baseline.json', BASELINE_HASH);
  requireValue(e4.scannerBaselineSourceHead === baseline.sourceHead
    && same(Object.keys(e4.scanners).sort(), ['gitleaks', 'osv', 'semgrep', 'zizmor']), 'server remediation scanner inventory mismatch');
  let total = 0;
  for (const [name, expected] of Object.entries(baseline.scanners)) {
    const scanner = e4.scanners[name];
    requireValue(scanner.scanCompleted === true && scanner.rawReportRetained === false
      && scanner.version === expected.version && scanner.sourceCommit === expected.sourceCommit
      && Array.isArray(scanner.findings) && scanner.findingCount === scanner.findings.length
      && new Set(scanner.findings.map(row => row.findingId)).size === scanner.findings.length,
    `server remediation incomplete or unpinned ${name} scanner`);
    total += scanner.findingCount;
  }
  requireValue(total === e4.totalFindingCount, 'server remediation scanner finding count mismatch');
  const e2 = e4.e2CurrentEvidence;
  requireValue(e2?.commitSha === expectedHead && e2.contentSha256 === e4.e2CurrentContentSha256
    && e4.e2GraphDigest === SERVER_DEPENDENCY_GRAPH, 'server remediation current E2/source mismatch');
  const transition = verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, expectedHead);
  requireValue(same(transition, requirePreservedGraph(e4, BASE_GRAPH, expectedHead)), 'server remediation graph receipt mismatch');
  return verifyOsvCoverage(e4.scanners.osv.coverage, e4.scanners.osv.findings, e2,
    { head: expectedHead, graphDigest: e4.e2GraphDigest });
}

/** Close only the two retained historical identities after a fresh, complete all-scanner E4. */
export function verifyServerDependencyRemediation(e4, plan = readServerDependencyRemediationPlan(), { expectedCommitSha } = {}) {
  const pinnedPlan = readServerDependencyRemediationPlan();
  requireValue(same(plan, pinnedPlan), 'server remediation supplied plan differs from pinned review');
  const coverage = requireFreshFullEvidence(e4, expectedCommitSha);
  requireValue(coverage.inputPackageCount === plan.expectedInputPackageCount
    && coverage.inputBytesSha256 === plan.expectedInputBytesSha256
    && e4.scanners.osv.inputPackageCount === coverage.inputPackageCount
    && SHA64.test(e4.scanners.osv.binarySha256 || ''), 'server remediation exact OSV input or binary provenance changed');
  const findings = e4.scanners.osv.findings;
  const closed = plan.remediatedFindings.map(item => {
    const target = coverage.targets.find(row => row.package.ecosystem === item.package.ecosystem
      && row.package.name === item.package.name && row.package.version === item.package.targetVersion);
    requireValue(target && target.componentRefs.includes(item.targetComponentRef), 'server remediation fixed target was not queried');
    const forbidden = new Set([item.upstreamFindingId, ...item.aliases, ...item.additionalAbsentAliases]);
    requireValue(!findings.some(finding => finding.findingId === item.findingId || forbidden.has(finding.upstreamFindingId)
      || (finding.aliases || []).some(alias => forbidden.has(alias))), 'server remediated advisory is still present in current OSV');
    return { sourceClass: item.sourceClass, findingId: item.findingId, upstreamFindingId: item.upstreamFindingId,
      aliases: item.aliases, priorDisposition: item.priorDisposition, priorComponentRef: item.priorComponentRef,
      priorReviewRecordSha256: item.priorReviewRecordSha256, fixedSince: item.fixedSince,
      fixedComponentRef: item.targetComponentRef, currentStatus: 'REMEDIATED_BY_FIXED_COMPONENT_AND_ABSENT_FROM_CURRENT_OSV',
      absenceEvidence: { sourceE4CanonicalSha256: e4.contentSha256, sourceE2ContentSha256: e4.e2CurrentContentSha256,
        osvCoverageContentSha256: coverage.contentSha256, queriedPackage: target.package,
        otherCurrentAdvisoryCountForTarget: target.advisoryIds.length }, reviewDispositionTransferred: false };
  });
  return signed({ schemaVersion: 'APPROVAL_SERVER_DEPENDENCY_REMEDIATION_EVIDENCE_V1', repository: REPOSITORY,
    commitSha: e4.commitSha, sourceE4CanonicalSha256: e4.contentSha256, sourceE2ContentSha256: e4.e2CurrentContentSha256,
    currentOsvCoverageContentSha256: coverage.contentSha256, manifestFileSha256: PLAN_HASH,
    priorE2GraphDigest: plan.priorE2GraphDigest, currentE2GraphDigest: e4.e2GraphDigest,
    historicalReviewFileSha256: plan.historicalReviewFileSha256, advisoryEvidenceFileSha256: plan.advisoryEvidenceFileSha256,
    remediatedFindings: closed, historicalFindingCount: 2, allScannersCompleted: true,
    findingReviewRequired: true, releaseBlocked: true,
    reasonCodes: ['AUTHORITATIVE_GITHUB_ALERT_INVENTORY_EVIDENCE_UNAVAILABLE', 'BUILD_PLUGIN_FINDINGS_REMAIN_UNRESOLVED',
      'BUNDLED_BINARY_INVENTORY_NOT_CLOSED_BY_MAVEN_COORDINATES', 'LICENSE_EVIDENCE_REMAINS_INCOMPLETE'] });
}

export function requireServerDependencyRemediation(e4, receipt, options) {
  const expected = verifyServerDependencyRemediation(e4, readServerDependencyRemediationPlan(), options);
  requireValue(same(receipt, expected), 'server dependency remediation receipt mismatch');
  return expected;
}

/** A valid R4 receipt must not let its consumer relabel the retained I3 review. */
export function requireServerDependencyHistoricalReview(review) {
  const plan = readServerDependencyRemediationPlan();
  const original = pinned(plan.historicalReviewPath, plan.historicalReviewFileSha256);
  requireValue(same(review, original), 'server remediation supplied historical review differs from pinned record');
  return original;
}
