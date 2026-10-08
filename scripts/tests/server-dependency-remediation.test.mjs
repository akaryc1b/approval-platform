import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { acceptedE2GraphProjection } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { BASE_GRAPH, SERVER_DEPENDENCY_GRAPH, graphHash, verifyObservabilityGraph } from '../security/observability-dependency-graph.mjs';
import { readServerDependencyRemediationPlan, verifyServerDependencyRemediation, requireServerDependencyRemediation }
  from '../security/m6-pr-e-e3-verify-server-dependency-remediation.mjs';
import { verifyPgjdbcRemediation } from '../security/m6-pr-e-e3-verify-pgjdbc-remediation.mjs';
import { applyRuntimeDeploymentReviews } from '../security/m6-pr-e-e3-apply-runtime-deployment-reviews.mjs';

const read = path => JSON.parse(readFileSync(new URL(`../../${path}`, import.meta.url)));
const partial = read('docs/m6/m6-pr-e-e3-r4-osv-preparation.json');
const baseline = read('docs/m6/m6-pr-e-e4-scanner-baseline.json');
const review = read('docs/m6/m6-pr-e-e3-i3-reviewed-findings.json');
const pgPlan = read('docs/m6/m6-pr-e-e3-r1-pgjdbc-remediation.json');
const plan = readServerDependencyRemediationPlan();
const resign = value => { const { contentSha256, ...payload } = value; value.contentSha256 = graphHash(payload); return value; };

// Only the OSV data/E2 below is actual retained diagnostic evidence. The other
// scanner envelopes are SYNTHETIC UNIT FIXTURES, never persisted or claimed as E4.
function fixture() {
  const e2 = structuredClone(partial.e2CurrentEvidence), head = e2.commitSha;
  const scanners = Object.fromEntries(Object.entries(baseline.scanners).map(([name, scanner]) => [name, {
    scanCompleted: true, version: scanner.version, sourceCommit: scanner.sourceCommit,
    rawReportRetained: false, findingCount: 0, findings: [],
  }]));
  Object.assign(scanners.osv, structuredClone(partial.osv), { scanCompleted: true,
    version: baseline.scanners.osv.version, sourceCommit: baseline.scanners.osv.sourceCommit,
    findingCount: partial.osv.findings.length, rawReportRetained: false });
  scanners.gitleaks.candidateSecretMaterialRetained = false;
  scanners.semgrep.sourceSnippetRetained = false;
  return resign({ schemaVersion: 'M6_PR_E_E4_SCANNER_EVIDENCE_V1', repository: baseline.repository,
    commitSha: head, e2CurrentEvidence: e2, e2CurrentContentSha256: e2.contentSha256,
    e2GraphDigest: SERVER_DEPENDENCY_GRAPH,
    e2GraphTransition: verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, head),
    checkout: { checkedOutSha: head, expectedHeadSha: head, checkedOutTreeSha: partial.treeSha,
      expectedHeadTreeSha: partial.treeSha, exactTreeMatches: true, trackedWorktreeClean: true },
    scannerBaselineSourceHead: baseline.sourceHead, scanners, totalFindingCount: scanners.osv.findingCount,
    allScannersCompleted: true, rawScannerReportsRetained: false, candidateSecretMaterialRetained: false,
    authoritativeGitHubInventoryStillUnavailable: true, workstreamReleaseBlocked: true });
}
const verify = (e4, suppliedPlan = plan, head = partial.commitSha) =>
  verifyServerDependencyRemediation(e4, suppliedPlan, { expectedCommitSha: head });

test('only the two reviewed Boot/Tomcat identities can gain fixed-component and current-absence lineage', () => {
  const e4 = fixture(), before = structuredClone(e4), receipt = verify(e4);
  assert.equal(receipt.historicalFindingCount, 2);
  assert.deepEqual(receipt.remediatedFindings.map(row => row.findingId), plan.remediatedFindings.map(row => row.findingId));
  assert.deepEqual(receipt.remediatedFindings.map(row => row.priorDisposition), ['NOT_APPLICABLE', 'UNRESOLVED']);
  assert.ok(receipt.remediatedFindings.every(row => row.reviewDispositionTransferred === false));
  assert.equal(receipt.findingReviewRequired, true); assert.equal(receipt.releaseBlocked, true);
  assert.equal(e4.scanners.osv.findings.length, 70);
  assert.equal(e4.scanners.osv.findings.filter(row => row.package.name.startsWith('tools.jackson.core:')
    && row.package.version === '3.1.5').length, 7);
  assert.deepEqual(e4, before);
  assert.deepEqual(requireServerDependencyRemediation(e4, receipt, { expectedCommitSha: e4.commitSha }), receipt);
});

test('retained actual OSV-only preparation cannot be promoted to full evidence', () => {
  assert.equal(partial.allScannersCompleted, false);
  assert.throws(() => verify(partial), /full current E4|current E4/);
  const changed = structuredClone(partial); changed.allScannersCompleted = true; resign(changed);
  assert.throws(() => verify(changed), /current E4/);
});

for (const [name, alter] of [
  ['foreign repository', e => { e.repository = 'foreign/repository'; }],
  ['wrong Head', e => { e.commitSha = 'f'.repeat(40); }],
  ['wrong schema', e => { e.schemaVersion = 'PARTIAL_OSV_ONLY'; }],
  ['partial scanners', e => { e.allScannersCompleted = false; }],
  ['incomplete OSV', e => { e.scanners.osv.scanCompleted = false; }],
  ['incomplete Semgrep', e => { e.scanners.semgrep.scanCompleted = false; }],
  ['missing scanner', e => { delete e.scanners.gitleaks; }],
  ['unpinned scanner', e => { e.scanners.osv.version = '0.0.0'; }],
  ['wrong scanner source', e => { e.scanners.osv.sourceCommit = '0'.repeat(40); }],
  ['missing scanner binary provenance', e => { delete e.scanners.osv.binarySha256; }],
  ['contradictory scanner input count', e => { e.scanners.osv.inputPackageCount--; }],
  ['scanner count drift', e => { e.scanners.osv.findingCount--; }],
  ['total count drift', e => { e.totalFindingCount++; }],
  ['dirty tracked source', e => { e.checkout.trackedWorktreeClean = false; }],
  ['wrong checkout tree', e => { e.checkout.expectedHeadTreeSha = '0'.repeat(40); }],
  ['missing checkout', e => { delete e.checkout; }],
  ['wrong E2 content', e => { e.e2CurrentContentSha256 = '0'.repeat(64); }],
  ['missing original E2', e => { delete e.e2CurrentEvidence; }],
  ['wrong E2 Head', e => { e.e2CurrentEvidence.commitSha = '0'.repeat(40); resign(e.e2CurrentEvidence); }],
  ['wrong graph', e => { e.e2GraphDigest = BASE_GRAPH; }],
  ['graph review waived', e => { e.e2GraphTransition.findingReviewRequired = false; }],
  ['missing OSV coverage', e => { delete e.scanners.osv.coverage; }],
  ['missing clean target', e => { e.scanners.osv.coverage.targets = e.scanners.osv.coverage.targets.filter(row => row.package.name !== 'org.springframework.boot:spring-boot'); resign(e.scanners.osv.coverage); }],
  ['missing plugin targets', e => { e.scanners.osv.coverage.targets = e.scanners.osv.coverage.targets.filter(row => !row.scopes.includes('build-plugin')); resign(e.scanners.osv.coverage); }],
  ['coverage Head', e => { e.scanners.osv.coverage.commitSha = '0'.repeat(40); resign(e.scanners.osv.coverage); }],
  ['waived release block', e => { e.workstreamReleaseBlocked = false; }],
  ['claimed inventory completeness', e => { e.authoritativeGitHubInventoryStillUnavailable = false; }],
  ['raw secret retention', e => { e.candidateSecretMaterialRetained = true; }],
]) test(`public remediation entry rejects ${name} after envelope rehash`, () => {
  const e4 = fixture(); alter(e4); resign(e4); assert.throws(() => verify(e4));
});
test('independent expected Head is mandatory', () => {
  assert.throws(() => verifyServerDependencyRemediation(fixture(), plan), /independent current Head/);
  assert.throws(() => verify(fixture(), plan, 'f'.repeat(40)), /independent current Head/);
});
for (const [name, alter] of [
  ['repository', p => { p.repository = 'foreign/repository'; }],
  ['review Head', p => { p.reviewBasisHead = 'f'.repeat(40); }],
  ['target graph', p => { p.targetE2GraphDigest = BASE_GRAPH; }],
  ['release flag', p => { p.releaseBlocked = false; }],
  ['review flag', p => { p.findingReviewRequired = false; }],
  ['count', p => { p.historicalFindingCount = 3; }],
  ['missing closure', p => { p.remediatedFindings.pop(); }],
  ['duplicate closure', p => { p.remediatedFindings.push(p.remediatedFindings[0]); }],
  ['wrong branch fix', p => { p.remediatedFindings[1].fixedSince = '9.0.118'; }],
  ['transplanted prior disposition', p => { p.remediatedFindings[0].priorDisposition = 'UNREACHABLE'; }],
  ['added suppression', p => { p.suppressions.push('build-plugin'); }],
]) test(`public remediation rejects forged ${name} plan with recomputed digest`, () => {
  const changed = structuredClone(plan); alter(changed); resign(changed); assert.throws(() => verify(fixture(), changed), /pinned review/);
});

function injectFinding(e4, packageName, version, advisory, aliases) {
  const scanner = e4.scanners.osv, coverage = scanner.coverage;
  const target = coverage.targets.find(row => row.package.name === packageName && row.package.version === version);
  const finding = { findingId: createHash('sha256').update(['OSV', advisory, target.package.ecosystem, packageName, version].join('\0')).digest('hex'),
    sourceClass: 'E4_OSV_SCANNER', upstreamFindingId: advisory, aliases, package: target.package,
    componentRefs: target.componentRefs, scopes: target.scopes, upstreamSeverity: [], fixedVersions: [] };
  target.advisoryIds.push(advisory); target.advisoryIds.sort();
  scanner.findings.push(finding); scanner.findings.sort((a, b) => a.findingId.localeCompare(b.findingId));
  scanner.findingCount++; e4.totalFindingCount++;
  coverage.findingCount = scanner.findingCount;
  coverage.findingPackageCount = coverage.targets.filter(row => row.advisoryIds.length).length;
  coverage.normalizedFindingsSha256 = graphHash(scanner.findings); resign(coverage); resign(e4);
}
for (const [name, packageName, version, advisory, aliases] of [
  ['Boot upstream', 'org.springframework.boot:spring-boot', '4.0.8', 'GHSA-8v8j-3hxp-93wr', []],
  ['Tomcat alias', 'org.apache.tomcat.embed:tomcat-embed-core', '11.0.26', 'GHSA-new-alias', ['CVE-2026-41293']],
  ['Tomcat regression', 'org.apache.tomcat.embed:tomcat-embed-core', '11.0.26', 'GHSA-new-regression', ['CVE-2026-86350']],
  ['alias in plugin realm', 'tools.jackson.core:jackson-core', '3.1.5', 'GHSA-plugin-alias', ['CVE-2026-40976']],
]) test(`current ${name} reappearance rejects closure even with consistent fresh coverage`, () => {
  const e4 = fixture(); injectFinding(e4, packageName, version, advisory, aliases);
  assert.throws(() => verify(e4), /advisory is still present/);
});
test('unrelated current finding remains untouched rather than gaining a historical disposition', () => {
  const e4 = fixture(); injectFinding(e4, 'org.springframework.boot:spring-boot', '4.0.8', 'GHSA-unrelated-current', []);
  const receipt = verify(e4); assert.equal(receipt.remediatedFindings.length, 2);
  assert.equal(e4.scanners.osv.findings.length, 71);
  assert.equal(receipt.remediatedFindings[0].absenceEvidence.otherCurrentAdvisoryCountForTarget, 1);
});
test('receipt flags and bindings cannot be forged or transplanted', () => {
  const e4 = fixture(), receipt = verify(e4);
  for (const field of ['releaseBlocked', 'findingReviewRequired', 'allScannersCompleted']) {
    const changed = structuredClone(receipt); changed[field] = false; resign(changed);
    assert.throws(() => requireServerDependencyRemediation(e4, changed, { expectedCommitSha: e4.commitSha }), /receipt mismatch/);
  }
});
test('I3 still rejects missing reviewed findings without explicit full-E4 remediation', () => {
  const e4 = fixture(), pg = verifyPgjdbcRemediation(e4, pgPlan, { expectedCommitSha: e4.commitSha }), server = verify(e4);
  const triage = { repository: e4.repository, commitSha: e4.commitSha, contentSha256: 'a'.repeat(64),
    decisions: e4.scanners.osv.findings.map(row => ({ sourceClass: row.sourceClass, findingId: row.findingId,
      severityBand: 'UNKNOWN', disposition: 'UNRESOLVED', componentRef: row.componentRefs[0] })) };
  assert.throws(() => applyRuntimeDeploymentReviews(triage, e4, review, pg), /reviewed OSV finding missing/);
  const result = applyRuntimeDeploymentReviews(triage, e4, review, pg, server);
  assert.equal(result.remediatedHistoricalFindingCount, 4);
  assert.equal(result.summary.releaseBlocked, true);
  assert.deepEqual(result.decisions, triage.decisions);
  assert.equal(result.summary.dispositionCounts.UNRESOLVED, 70);
  assert.equal(result.summary.dispositionCounts.NOT_APPLICABLE, undefined);
});

for (const [name, alter] of [
  ['historical Head', value => { value.reviewBasisHead = 'f'.repeat(40); }],
  ['historical row evidence', value => { value.reviewedFindings[0].evidence = 'forged history'; }],
  ['historical fixed versions', value => { value.reviewedFindings[3].fixedVersions = ['9.0.118']; }],
  ['historical row omission', value => { value.reviewedFindings.pop(); }],
  ['historical review count', value => { value.summary.reviewedCount = 0; }],
]) test(`R4-bearing I3 rejects relabelled ${name} even with a valid fresh R4 receipt`, () => {
  const e4 = fixture(), pg = verifyPgjdbcRemediation(e4, pgPlan, { expectedCommitSha: e4.commitSha });
  const server = verify(e4), changed = structuredClone(review); alter(changed);
  const triage = { repository: e4.repository, commitSha: e4.commitSha, contentSha256: 'a'.repeat(64),
    decisions: e4.scanners.osv.findings.map(row => ({ sourceClass: row.sourceClass, findingId: row.findingId,
      severityBand: 'UNKNOWN', disposition: 'UNRESOLVED', componentRef: row.componentRefs[0] })) };
  assert.throws(() => applyRuntimeDeploymentReviews(triage, e4, changed, pg, server), /historical review differs from pinned record/);
});
