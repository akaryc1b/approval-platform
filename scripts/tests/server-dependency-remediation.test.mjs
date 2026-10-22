import { COMPILER_PLUGIN_GRAPH, readCompilerPluginCandidate, readCompilerPluginManifest } from '../security/observability-dependency-graph.mjs';
import { syntheticCompilerPluginOsv } from './fixtures/compiler-plugin-fixture.mjs';
import { CLEAN_PLUGIN_GRAPH, readCleanPluginCandidate, readCleanPluginManifest } from '../security/observability-dependency-graph.mjs';
import { syntheticCleanPluginOsv } from './fixtures/clean-plugin-fixture.mjs';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { syntheticSitePluginOsv } from './fixtures/site-dependency-plugin-fixture.mjs';
import { syntheticReleasePluginOsv } from './fixtures/release-plugin-fixture.mjs';
import { pluginOsvDiagnosticFixture } from './fixtures/build-plugin-jackson-fixture.mjs';
import { acceptedE2GraphProjection } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { BASE_GRAPH, SERVER_DEPENDENCY_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH, readBuildPluginJacksonCandidate, SITE_DEPENDENCY_PLUGIN_GRAPH, readSiteDependencyPluginCandidate, RELEASE_PLUGIN_GRAPH, readReleasePluginCandidate, readReleasePluginManifest, graphHash, verifyObservabilityGraph } from '../security/observability-dependency-graph.mjs';
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

// Every scanner/checkout claim here remains a SYNTHETIC UNIT FIXTURE.
// It tests the new graph path without claiming a new scan, clean source or absence.
function pluginDescendantFixture() {
  const e4 = fixture(), e2 = readBuildPluginJacksonCandidate();
  e4.commitSha = e2.commitSha;
  e4.e2CurrentEvidence = e2; e4.e2CurrentContentSha256 = e2.contentSha256;
  e4.e2GraphDigest = BUILD_PLUGIN_JACKSON_GRAPH;
  e4.e2GraphTransition = verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, e2.commitSha);
  e4.checkout.checkedOutSha = e2.commitSha; e4.checkout.expectedHeadSha = e2.commitSha;
  e4.scanners.osv = pluginOsvDiagnosticFixture(e2, e4.scanners.osv);
  e4.totalFindingCount = Object.values(e4.scanners).reduce((n,scanner) => n + scanner.findingCount,0);
  return resign(e4);
}
const verifyPlugin = e4 => verify(e4, plan, e4.commitSha);
test('R4 preserves its original target and input binding through the independently verified plugin-only descendant', () => {
  const e4 = pluginDescendantFixture(), original = structuredClone(e4), receipt = verifyPlugin(e4);
  assert.equal(receipt.schemaVersion, 'APPROVAL_SERVER_DEPENDENCY_REMEDIATION_EVIDENCE_V2');
  assert.equal(receipt.historicalTargetE2GraphDigest, SERVER_DEPENDENCY_GRAPH);
  assert.equal(receipt.currentE2GraphDigest, BUILD_PLUGIN_JACKSON_GRAPH);
  assert.equal(receipt.subsequentGraphTransition.priorE2GraphDigest, SERVER_DEPENDENCY_GRAPH);
  assert.equal(receipt.remediatedFindings.length, 2);
  assert.deepEqual(receipt.remediatedFindings.map(f => f.findingId), plan.remediatedFindings.map(f => f.findingId));
  assert.equal(e4.scanners.osv.coverage.inputPackageCount, 533);
  assert.equal(plan.expectedInputPackageCount, 535);
  assert.equal(receipt.releaseBlocked, true); assert.equal(receipt.findingReviewRequired, true);
  assert.deepEqual(e4, original);
});
for (const [name, alter] of [
  ['old graph receipt', e => { e.e2GraphTransition = fixture().e2GraphTransition; }],
  ['old input count', e => { e.scanners.osv.coverage.inputPackageCount = 535; resign(e.scanners.osv.coverage); }],
  ['old input bytes', e => { e.scanners.osv.coverage.inputBytesSha256 = plan.expectedInputBytesSha256; resign(e.scanners.osv.coverage); }],
  ['missing current Jackson target', e => { e.scanners.osv.coverage.targets = e.scanners.osv.coverage.targets.filter(t => t.package.name !== 'tools.jackson.core:jackson-core'); resign(e.scanners.osv.coverage); }],
  ['removed plugin component references', e => { const t=e.scanners.osv.coverage.targets.find(t => t.package.name === 'tools.jackson.core:jackson-core' && t.package.version === '3.1.7'); t.componentRefs=t.componentRefs.filter(ref => !ref.startsWith('maven-plugin:')); resign(e.scanners.osv.coverage); }],
  ['reintroduced old Jackson target', e => { e.scanners.osv.coverage.targets.push(partial.osv.coverage.targets.find(t => t.package.name === 'tools.jackson.core:jackson-core' && t.package.version === '3.1.5')); resign(e.scanners.osv.coverage); }],
  ['missing exact E2', e => { delete e.e2CurrentEvidence; }],
  ['incomplete scanner', e => { e.scanners.semgrep.scanCompleted = false; }],
]) test(`R4 plugin descendant rejects ${name} even after rehashing`, () => {
  const e4 = pluginDescendantFixture(); alter(e4); resign(e4); assert.throws(() => verifyPlugin(e4));
});
test('plugin graph cannot conceal a historically remediated advisory reappearing at the new Jackson target', () => {
  const e4 = pluginDescendantFixture();
  injectFinding(e4, 'tools.jackson.core:jackson-core', '3.1.7', 'GHSA-plugin-alias', ['CVE-2026-40976']);
  assert.throws(() => verifyPlugin(e4), /advisory is still present/);
});
test('a new Jackson finding keeps its current identity and remains unresolved after I3', () => {
  const e4 = pluginDescendantFixture();
  injectFinding(e4, 'tools.jackson.core:jackson-core', '3.1.7', 'GHSA-new-plugin-current', []);
  const before = structuredClone(e4), receipt = verifyPlugin(e4);
  const pg = verifyPgjdbcRemediation(e4, pgPlan, { expectedCommitSha: e4.commitSha });
  const triage = { repository: e4.repository, commitSha: e4.commitSha, contentSha256: 'a'.repeat(64),
    decisions: e4.scanners.osv.findings.map(f => ({ sourceClass: f.sourceClass, findingId: f.findingId,
      severityBand: 'UNKNOWN', disposition: 'UNRESOLVED', componentRef: f.componentRefs[0] })) };
  const result = applyRuntimeDeploymentReviews(triage, e4, review, pg, receipt);
  assert.deepEqual(result.decisions, triage.decisions);
  assert.equal(result.summary.dispositionCounts.UNRESOLVED, e4.scanners.osv.findingCount);
  assert.deepEqual(e4, before); assert.equal(result.summary.releaseBlocked, true);
});

// Entire scanner/checkout envelope is a synthetic unit fixture, including OSV.
function siteDescendantFixture() {
  const e4 = fixture(), e2 = readSiteDependencyPluginCandidate();
  e4.commitSha = e2.commitSha; e4.e2CurrentEvidence = e2; e4.e2CurrentContentSha256 = e2.contentSha256;
  e4.e2GraphDigest = SITE_DEPENDENCY_PLUGIN_GRAPH;
  e4.e2GraphTransition = verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, e2.commitSha);
  e4.checkout.checkedOutSha = e2.commitSha; e4.checkout.expectedHeadSha = e2.commitSha;
  e4.scanners.osv = syntheticSitePluginOsv(e2, e4.scanners.osv);
  e4.totalFindingCount = Object.values(e4.scanners).reduce((n, scanner) => n + scanner.findingCount, 0);
  return resign(e4);
}
test('R4 preserves both prior exact input contracts through the Site/Dependency descendant', () => {
  const e4 = siteDescendantFixture(), original = structuredClone(e4), receipt = verifyPlugin(e4);
  assert.equal(receipt.currentE2GraphDigest, SITE_DEPENDENCY_PLUGIN_GRAPH);
  assert.equal(receipt.historicalTargetE2GraphDigest, SERVER_DEPENDENCY_GRAPH);
  assert.equal(receipt.subsequentGraphTransition.priorE2GraphDigest, BUILD_PLUGIN_JACKSON_GRAPH);
  assert.equal(receipt.subsequentGraphTransition.preservedBuildPluginJacksonLineage.priorE2GraphDigest, SERVER_DEPENDENCY_GRAPH);
  assert.equal(receipt.remediatedFindings.length, 2); assert.equal(receipt.releaseBlocked, true);
  assert.equal(e4.scanners.osv.coverage.inputPackageCount, 486); assert.equal(plan.expectedInputPackageCount, 535);
  assert.deepEqual(e4, original);
});
for (const [name, alter] of [
  ['pre-Site receipt', e => { e.e2GraphTransition = pluginDescendantFixture().e2GraphTransition; }],
  ['prior input count', e => { e.scanners.osv.coverage.inputPackageCount = 533; resign(e.scanners.osv.coverage); }],
  ['prior input bytes', e => { e.scanners.osv.coverage.inputBytesSha256 = pluginDescendantFixture().scanners.osv.coverage.inputBytesSha256; resign(e.scanners.osv.coverage); }],
  ['missing fixed jsoup target', e => { e.scanners.osv.coverage.targets = e.scanners.osv.coverage.targets.filter(t => t.package.name !== 'org.jsoup:jsoup'); resign(e.scanners.osv.coverage); }],
  ['missing exact E2', e => { delete e.e2CurrentEvidence; }],
  ['incomplete scanner', e => { e.scanners.semgrep.scanCompleted = false; }],
]) test(`R4 Site/Dependency descendant rejects ${name} even after rehashing`, () => {
  const e4 = siteDescendantFixture(); alter(e4); resign(e4); assert.throws(() => verifyPlugin(e4));
});
test('Site plugin cannot conceal reappearance of a historically remediated advisory alias', () => {
  const e4 = siteDescendantFixture();
  injectFinding(e4, 'org.jsoup:jsoup', '1.23.2', 'GHSA-synthetic-returned-alias', ['CVE-2026-40976']);
  assert.throws(() => verifyPlugin(e4), /advisory is still present/);
});
test('a synthetic new Site finding retains its identity and unresolved disposition through I3', () => {
  const e4 = siteDescendantFixture(), receipt = verifyPlugin(e4);
  const pg = verifyPgjdbcRemediation(e4, pgPlan, { expectedCommitSha: e4.commitSha });
  const triage = { repository: e4.repository, commitSha: e4.commitSha, contentSha256: 'a'.repeat(64),
    decisions: e4.scanners.osv.findings.map(f => ({ sourceClass: f.sourceClass, findingId: f.findingId,
      severityBand: 'UNKNOWN', disposition: 'UNRESOLVED', componentRef: f.componentRefs[0] })) };
  const result = applyRuntimeDeploymentReviews(triage, e4, review, pg, receipt);
  assert.deepEqual(result.decisions, triage.decisions); assert.equal(result.summary.releaseBlocked, true);
  assert.equal(result.summary.dispositionCounts.UNRESOLVED, 1);
});

// Entire scanner/checkout envelope remains synthetic unit-only, including OSV.
// No fresh scan, absence, exact-head execution, or release admission is claimed.
function releaseDescendantFixture() {
  const e4 = fixture(), e2 = readReleasePluginCandidate();
  e4.commitSha = e2.commitSha; e4.e2CurrentEvidence = e2; e4.e2CurrentContentSha256 = e2.contentSha256;
  e4.e2GraphDigest = RELEASE_PLUGIN_GRAPH;
  e4.e2GraphTransition = verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, e2.commitSha);
  e4.checkout.checkedOutSha = e2.commitSha; e4.checkout.expectedHeadSha = e2.commitSha;
  e4.scanners.osv = syntheticReleasePluginOsv(e2, e4.scanners.osv);
  e4.totalFindingCount = Object.values(e4.scanners).reduce((n, scanner) => n + scanner.findingCount, 0);
  return resign(e4);
}
test('R4 and pgjdbc preserve all prior contracts through the Release descendant and full current coverage', () => {
  const e4 = releaseDescendantFixture(), original = structuredClone(e4), receipt = verifyPlugin(e4);
  assert.equal(receipt.currentE2GraphDigest, RELEASE_PLUGIN_GRAPH);
  assert.equal(receipt.historicalTargetE2GraphDigest, SERVER_DEPENDENCY_GRAPH);
  assert.equal(receipt.subsequentGraphTransition.priorE2GraphDigest, SITE_DEPENDENCY_PLUGIN_GRAPH);
  const site = receipt.subsequentGraphTransition.preservedSiteDependencyPluginLineage;
  assert.equal(site.priorE2GraphDigest, BUILD_PLUGIN_JACKSON_GRAPH);
  assert.equal(site.preservedBuildPluginJacksonLineage.priorE2GraphDigest, SERVER_DEPENDENCY_GRAPH);
  assert.equal(receipt.remediatedFindings.length, 2);
  assert.deepEqual(receipt.remediatedFindings.map(finding => finding.findingId), plan.remediatedFindings.map(finding => finding.findingId));
  assert.equal(receipt.releaseBlocked, true); assert.equal(receipt.findingReviewRequired, true);
  assert.equal(e4.scanners.osv.coverage.inputPackageCount, 475); assert.equal(plan.expectedInputPackageCount, 535);
  assert.equal(e4.scanners.osv.coverage.inputBytesSha256, readReleasePluginManifest().osvInput.current.inputBytesSha256);
  assert.deepEqual(requireServerDependencyRemediation(e4, receipt, { expectedCommitSha: e4.commitSha }), receipt);
  const pg = verifyPgjdbcRemediation(e4, pgPlan, { expectedCommitSha: e4.commitSha });
  assert.equal(pg.currentE2GraphDigest, RELEASE_PLUGIN_GRAPH);
  assert.deepEqual(pg.subsequentGraphTransition, receipt.subsequentGraphTransition);
  assert.equal(pg.remediatedFindings.length, 2); assert.equal(pg.releaseBlocked, true);
  assert.deepEqual(e4, original);
});
for (const [name, alter] of [
  ['pre-Release receipt', e => { e.e2GraphTransition = siteDescendantFixture().e2GraphTransition; }],
  ['prior input count', e => { e.scanners.osv.coverage.inputPackageCount = 486; resign(e.scanners.osv.coverage); }],
  ['prior input bytes', e => { e.scanners.osv.coverage.inputBytesSha256 = readReleasePluginManifest().osvInput.prior.inputBytesSha256; resign(e.scanners.osv.coverage); }],
  ['missing clean target', e => { e.scanners.osv.coverage.targets = e.scanners.osv.coverage.targets.filter(target => target.package.name !== 'org.springframework.boot:spring-boot'); resign(e.scanners.osv.coverage); }],
  ['missing current JGit target', e => { e.scanners.osv.coverage.targets = e.scanners.osv.coverage.targets.filter(target => target.package.name !== 'org.eclipse.jgit:org.eclipse.jgit'); resign(e.scanners.osv.coverage); }],
  ['missing build-plugin targets', e => { e.scanners.osv.coverage.targets = e.scanners.osv.coverage.targets.filter(target => !target.scopes.includes('build-plugin')); resign(e.scanners.osv.coverage); }],
  ['missing exact E2', e => { delete e.e2CurrentEvidence; }],
  ['missing coverage', e => { delete e.scanners.osv.coverage; }],
  ['incomplete scanner', e => { e.scanners.semgrep.scanCompleted = false; }],
  ['stale coverage head', e => { e.scanners.osv.coverage.commitSha = 'f'.repeat(40); resign(e.scanners.osv.coverage); }],
  ['stale nested Site receipt', e => { e.e2GraphTransition.preservedSiteDependencyPluginLineage.commitSha = 'f'.repeat(40); resign(e.e2GraphTransition.preservedSiteDependencyPluginLineage); resign(e.e2GraphTransition); }],
]) test(`R4 Release descendant rejects ${name} even after rehashing`, () => {
  const e4 = releaseDescendantFixture(); alter(e4); resign(e4); assert.throws(() => verifyPlugin(e4));
});
test('Release descendant requires the independently verified head in both R4 and pgjdbc', () => {
  const e4 = releaseDescendantFixture();
  for (const expectedCommitSha of [undefined, 'f'.repeat(40)]) {
    assert.throws(() => verifyServerDependencyRemediation(e4, plan, { expectedCommitSha }));
    assert.throws(() => verifyPgjdbcRemediation(e4, pgPlan, { expectedCommitSha }));
  }
});
test('Release descendant cannot conceal a remediated advisory alias at the new JGit target', () => {
  const e4 = releaseDescendantFixture();
  injectFinding(e4, 'org.eclipse.jgit:org.eclipse.jgit', '5.13.5.202508271544-r', 'GHSA-synthetic-unit-returned-alias', ['CVE-2026-40976']);
  assert.throws(() => verifyPlugin(e4), /advisory is still present/);
});
test('a synthetic new Release finding keeps its exact identity and unresolved disposition through I3', () => {
  const e4 = releaseDescendantFixture(), before = structuredClone(e4), receipt = verifyPlugin(e4);
  const pg = verifyPgjdbcRemediation(e4, pgPlan, { expectedCommitSha: e4.commitSha });
  const triage = { repository: e4.repository, commitSha: e4.commitSha, contentSha256: 'a'.repeat(64),
    decisions: e4.scanners.osv.findings.map(f => ({ sourceClass: f.sourceClass, findingId: f.findingId,
      severityBand: 'UNKNOWN', disposition: 'UNRESOLVED', componentRef: f.componentRefs[0] })) };
  const result = applyRuntimeDeploymentReviews(triage, e4, review, pg, receipt);
  assert.deepEqual(result.decisions, triage.decisions); assert.equal(result.summary.releaseBlocked, true);
  assert.equal(result.summary.dispositionCounts.UNRESOLVED, 1); assert.deepEqual(e4, before);
});

// Fully synthetic scanner/checkout envelope; actual retained E2 does not establish scan absence.
function cleanDescendantFixture() {
  const e4 = fixture(), e2 = readCleanPluginCandidate();
  e4.commitSha = e2.commitSha; e4.e2CurrentEvidence = e2; e4.e2CurrentContentSha256 = e2.contentSha256;
  e4.e2GraphDigest = CLEAN_PLUGIN_GRAPH;
  e4.e2GraphTransition = verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, e2.commitSha);
  e4.checkout.checkedOutSha = e2.commitSha; e4.checkout.expectedHeadSha = e2.commitSha;
  e4.scanners.osv = syntheticCleanPluginOsv(e2, e4.scanners.osv);
  e4.totalFindingCount = Object.values(e4.scanners).reduce((n, scanner) => n + scanner.findingCount, 0);
  return resign(e4);
}
test('R4 and pgjdbc preserve every prior input contract and exact main Release receipt through Clean', () => {
  const e4 = cleanDescendantFixture(), original = structuredClone(e4), receipt = verifyPlugin(e4);
  assert.equal(receipt.currentE2GraphDigest, CLEAN_PLUGIN_GRAPH);
  assert.equal(receipt.historicalTargetE2GraphDigest, SERVER_DEPENDENCY_GRAPH);
  assert.equal(receipt.subsequentGraphTransition.priorE2GraphDigest, RELEASE_PLUGIN_GRAPH);
  const release = receipt.subsequentGraphTransition.preservedReleasePluginLineage;
  assert.equal(release.priorE2GraphDigest, SITE_DEPENDENCY_PLUGIN_GRAPH);
  assert.equal(release.commitSha, '757fe355b2d868916e112f2fcbbe093b865e0669');
  assert.equal(release.contentSha256, 'b99400f63544b6832406a7394148c0b794e6b53c92f6010cf4bfeff53ce8db69');
  assert.deepEqual(receipt.remediatedFindings.map(f => f.findingId), plan.remediatedFindings.map(f => f.findingId));
  assert.equal(receipt.releaseBlocked, true); assert.equal(receipt.findingReviewRequired, true);
  assert.equal(e4.scanners.osv.coverage.inputPackageCount, 474); assert.equal(plan.expectedInputPackageCount, 535);
  assert.equal(e4.scanners.osv.coverage.inputBytesSha256, readCleanPluginManifest().osvInput.current.inputBytesSha256);
  assert.deepEqual(requireServerDependencyRemediation(e4, receipt, { expectedCommitSha: e4.commitSha }), receipt);
  const pg = verifyPgjdbcRemediation(e4, pgPlan, { expectedCommitSha: e4.commitSha });
  assert.equal(pg.currentE2GraphDigest, CLEAN_PLUGIN_GRAPH); assert.deepEqual(pg.subsequentGraphTransition, receipt.subsequentGraphTransition);
  assert.equal(pg.remediatedFindings.length, 2); assert.equal(pg.releaseBlocked, true); assert.deepEqual(e4, original);
});
for (const [name, alter] of [
  ['pre-Clean receipt', e => { e.e2GraphTransition = releaseDescendantFixture().e2GraphTransition; }],
  ['prior input count', e => { e.scanners.osv.coverage.inputPackageCount = 475; resign(e.scanners.osv.coverage); }],
  ['prior input bytes', e => { e.scanners.osv.coverage.inputBytesSha256 = readCleanPluginManifest().osvInput.prior.inputBytesSha256; resign(e.scanners.osv.coverage); }],
  ['missing non-finding target', e => { e.scanners.osv.coverage.targets.splice(e.scanners.osv.coverage.targets.findIndex(t => !t.advisoryIds.length), 1); resign(e.scanners.osv.coverage); }],
  ['missing build-plugin targets', e => { e.scanners.osv.coverage.targets = e.scanners.osv.coverage.targets.filter(t => !t.scopes.includes('build-plugin')); resign(e.scanners.osv.coverage); }],
  ['missing exact E2', e => { delete e.e2CurrentEvidence; }],
  ['missing coverage', e => { delete e.scanners.osv.coverage; }],
  ['incomplete scanner', e => { e.scanners.semgrep.scanCompleted = false; }],
  ['stale coverage head', e => { e.scanners.osv.coverage.commitSha = 'f'.repeat(40); resign(e.scanners.osv.coverage); }],
  ['stale nested Release receipt', e => { e.e2GraphTransition.preservedReleasePluginLineage.commitSha = 'f'.repeat(40); resign(e.e2GraphTransition.preservedReleasePluginLineage); resign(e.e2GraphTransition); }],
]) test(`R4 Clean descendant rejects ${name} even after rehashing`, () => {
  const e4 = cleanDescendantFixture(); alter(e4); resign(e4); assert.throws(() => verifyPlugin(e4));
});
test('Clean descendant requires independent current head and rejects any returned historical advisory alias', () => {
  const e4 = cleanDescendantFixture();
  for (const expectedCommitSha of [undefined, 'f'.repeat(40)]) {
    assert.throws(() => verifyServerDependencyRemediation(e4, plan, { expectedCommitSha }));
    assert.throws(() => verifyPgjdbcRemediation(e4, pgPlan, { expectedCommitSha }));
  }
  injectFinding(e4, 'commons-io:commons-io', '2.20.0', 'GHSA-synthetic-unit-returned-alias', ['CVE-2026-40976']);
  assert.throws(() => verifyPlugin(e4), /advisory is still present/);
});
test('new synthetic Clean finding retains its exact identity and unresolved disposition through I3', () => {
  const e4 = cleanDescendantFixture(), before = structuredClone(e4), receipt = verifyPlugin(e4);
  const pg = verifyPgjdbcRemediation(e4, pgPlan, { expectedCommitSha: e4.commitSha });
  const triage = { repository: e4.repository, commitSha: e4.commitSha, contentSha256: 'a'.repeat(64),
    decisions: e4.scanners.osv.findings.map(f => ({ sourceClass: f.sourceClass, findingId: f.findingId,
      severityBand: 'UNKNOWN', disposition: 'UNRESOLVED', componentRef: f.componentRefs[0] })) };
  const result = applyRuntimeDeploymentReviews(triage, e4, review, pg, receipt);
  assert.deepEqual(result.decisions, triage.decisions); assert.equal(result.summary.releaseBlocked, true);
  assert.equal(result.summary.dispositionCounts.UNRESOLVED, 1); assert.deepEqual(e4, before);
});

function compilerDescendantFixture() {
  const e4 = fixture(), e2 = readCompilerPluginCandidate();
  e4.commitSha = e2.commitSha; e4.e2CurrentEvidence = e2; e4.e2CurrentContentSha256 = e2.contentSha256;
  e4.e2GraphDigest = COMPILER_PLUGIN_GRAPH;
  e4.e2GraphTransition = verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, e2.commitSha);
  e4.checkout.checkedOutSha = e2.commitSha; e4.checkout.expectedHeadSha = e2.commitSha;
  e4.scanners.osv = syntheticCompilerPluginOsv(e2, e4.scanners.osv);
  e4.totalFindingCount = Object.values(e4.scanners).reduce((n, scanner) => n + scanner.findingCount, 0);
  return resign(e4);
}
test('R4 and pgjdbc preserve every prior input contract and exact main Clean receipt through Compiler', () => {
  const e4 = compilerDescendantFixture(), original = structuredClone(e4), receipt = verifyPlugin(e4);
  assert.equal(receipt.currentE2GraphDigest, COMPILER_PLUGIN_GRAPH);
  assert.equal(receipt.historicalTargetE2GraphDigest, SERVER_DEPENDENCY_GRAPH);
  assert.equal(receipt.subsequentGraphTransition.priorE2GraphDigest, CLEAN_PLUGIN_GRAPH);
  const release = receipt.subsequentGraphTransition.preservedCleanPluginLineage.preservedReleasePluginLineage;
  assert.equal(release.priorE2GraphDigest, SITE_DEPENDENCY_PLUGIN_GRAPH);
  assert.equal(release.commitSha, '757fe355b2d868916e112f2fcbbe093b865e0669');
  assert.equal(release.contentSha256, 'b99400f63544b6832406a7394148c0b794e6b53c92f6010cf4bfeff53ce8db69');
  assert.deepEqual(receipt.remediatedFindings.map(f => f.findingId), plan.remediatedFindings.map(f => f.findingId));
  assert.equal(receipt.releaseBlocked, true); assert.equal(receipt.findingReviewRequired, true);
  assert.equal(e4.scanners.osv.coverage.inputPackageCount, 473); assert.equal(plan.expectedInputPackageCount, 535);
  assert.equal(e4.scanners.osv.coverage.inputBytesSha256, readCompilerPluginManifest().osvInput.current.inputBytesSha256);
  assert.deepEqual(requireServerDependencyRemediation(e4, receipt, { expectedCommitSha: e4.commitSha }), receipt);
  const pg = verifyPgjdbcRemediation(e4, pgPlan, { expectedCommitSha: e4.commitSha });
  assert.equal(pg.currentE2GraphDigest, COMPILER_PLUGIN_GRAPH); assert.deepEqual(pg.subsequentGraphTransition, receipt.subsequentGraphTransition);
  assert.equal(pg.remediatedFindings.length, 2); assert.equal(pg.releaseBlocked, true); assert.deepEqual(e4, original);
});
for (const [name, alter] of [
  ['pre-Compiler receipt', e => { e.e2GraphTransition = cleanDescendantFixture().e2GraphTransition; }],
  ['prior input count', e => { e.scanners.osv.coverage.inputPackageCount = 475; resign(e.scanners.osv.coverage); }],
  ['prior input bytes', e => { e.scanners.osv.coverage.inputBytesSha256 = readCompilerPluginManifest().osvInput.prior.inputBytesSha256; resign(e.scanners.osv.coverage); }],
  ['missing non-finding target', e => { e.scanners.osv.coverage.targets.splice(e.scanners.osv.coverage.targets.findIndex(t => !t.advisoryIds.length), 1); resign(e.scanners.osv.coverage); }],
  ['missing build-plugin targets', e => { e.scanners.osv.coverage.targets = e.scanners.osv.coverage.targets.filter(t => !t.scopes.includes('build-plugin')); resign(e.scanners.osv.coverage); }],
  ['missing exact E2', e => { delete e.e2CurrentEvidence; }],
  ['missing coverage', e => { delete e.scanners.osv.coverage; }],
  ['incomplete scanner', e => { e.scanners.semgrep.scanCompleted = false; }],
  ['stale coverage head', e => { e.scanners.osv.coverage.commitSha = 'f'.repeat(40); resign(e.scanners.osv.coverage); }],
  ['stale nested Release receipt', e => { e.e2GraphTransition.preservedCleanPluginLineage.preservedReleasePluginLineage.commitSha = 'f'.repeat(40); resign(e.e2GraphTransition.preservedCleanPluginLineage.preservedReleasePluginLineage); resign(e.e2GraphTransition); }],
]) test(`R4 Compiler descendant rejects ${name} even after rehashing`, () => {
  const e4 = compilerDescendantFixture(); alter(e4); resign(e4); assert.throws(() => verifyPlugin(e4));
});
test('Compiler descendant requires independent current head and rejects any returned historical advisory alias', () => {
  const e4 = compilerDescendantFixture();
  for (const expectedCommitSha of [undefined, 'f'.repeat(40)]) {
    assert.throws(() => verifyServerDependencyRemediation(e4, plan, { expectedCommitSha }));
    assert.throws(() => verifyPgjdbcRemediation(e4, pgPlan, { expectedCommitSha }));
  }
  injectFinding(e4, 'commons-io:commons-io', '2.20.0', 'GHSA-synthetic-unit-returned-alias', ['CVE-2026-40976']);
  assert.throws(() => verifyPlugin(e4), /advisory is still present/);
});
test('new synthetic Compiler finding retains its exact identity and unresolved disposition through I3', () => {
  const e4 = compilerDescendantFixture(), before = structuredClone(e4), receipt = verifyPlugin(e4);
  const pg = verifyPgjdbcRemediation(e4, pgPlan, { expectedCommitSha: e4.commitSha });
  const triage = { repository: e4.repository, commitSha: e4.commitSha, contentSha256: 'a'.repeat(64),
    decisions: e4.scanners.osv.findings.map(f => ({ sourceClass: f.sourceClass, findingId: f.findingId,
      severityBand: 'UNKNOWN', disposition: 'UNRESOLVED', componentRef: f.componentRefs[0] })) };
  const result = applyRuntimeDeploymentReviews(triage, e4, review, pg, receipt);
  assert.deepEqual(result.decisions, triage.decisions); assert.equal(result.summary.releaseBlocked, true);
  assert.equal(result.summary.dispositionCounts.UNRESOLVED, 1); assert.deepEqual(e4, before);
});

// Every E4/OSV/checkout claim in these Resources/Flatten cases is SYNTHETIC.
// These pure tests execute no scanner and prove no current finding absence.
import rfFs from 'node:fs';
import { tmpdir as rfTmpdir } from 'node:os';
import { dirname as rfDirname, join as rfJoin } from 'node:path';
import { fileURLToPath as rfFilePath, pathToFileURL as rfFileUrl } from 'node:url';
import { syncBuiltinESMExports as rfSyncBuiltins } from 'node:module';
import rfChildProcess from 'node:child_process';
import rfHttp from 'node:http';
import rfHttps from 'node:https';
import rfNet from 'node:net';
import { buildOsvCoverage as rfBuildCoverage, osvInputFromE2 as rfInput } from '../security/osv-scan-coverage.mjs';

const rfRoot = rfFilePath(new URL('../../', import.meta.url));
const rfReadJson = path => JSON.parse(rfFs.readFileSync(rfJoin(rfRoot, path)));
function rfCopyFixture(directory) {
  const manifest = rfReadJson('docs/operations/resources-flatten-transition.json');
  const capture = rfReadJson(manifest.archives.modelExecution.path);
  const plan = rfReadJson('docs/m6/m6-pr-e-e3-r4-server-dependency-remediation.json');
  const paths = [...Object.keys(capture.captureInputs), 'docs/operations/resources-flatten-transition.json',
    ...Object.values(manifest.archives).map(row => row.path), 'docs/m6/m6-pr-e-e3-r4-server-dependency-remediation.json',
    'docs/m6/m6-pr-e-e4-scanner-baseline.json', plan.historicalReviewPath, plan.advisoryEvidencePath];
  paths.push('docs/operations/assembly-enforcer-lang-transition.json',
    ...rfReadJson('docs/operations/assembly-enforcer-lang-evidence/accepted-artifact-inventory.json').files.map(row => row.path));
  function refs(value) {
    if (!value || typeof value !== 'object') return;
    if (typeof value.path === 'string' && typeof value.rawSha256 === 'string') paths.push(value.path);
    for (const child of Object.values(value)) refs(child);
  }
  refs(rfReadJson('docs/operations/assembly-enforcer-lang-transition.json'));
  for (const path of new Set(paths)) {
    const target = rfJoin(directory, path); rfFs.mkdirSync(rfDirname(target), { recursive: true });
    rfFs.copyFileSync(rfJoin(rfRoot, path), target);
  }
  rfFs.cpSync(rfJoin(rfRoot, 'scripts/security'), rfJoin(directory, 'scripts/security'), { recursive: true });
  return manifest;
}
function rfSyntheticE4(api, router) {
  const e2 = resign({ ...api.readResourcesFlattenCandidate(), commitSha: 'a'.repeat(40) });
  const baseline = rfReadJson('docs/m6/m6-pr-e-e4-scanner-baseline.json');
  const scanners = Object.fromEntries(Object.entries(baseline.scanners).map(([name, scanner]) => [name, {
    scanCompleted: true, version: scanner.version, sourceCommit: scanner.sourceCommit,
    rawReportRetained: false, findingCount: 0, findings: [],
  }]));
  const input = rfInput(e2), inputPath = '/synthetic-unit-only-resources-flatten-input.json';
  const selected = [...input.lookup.values()].find(row => row.package.name === 'org.codehaus.plexus:plexus-utils' && row.package.version === '4.0.3');
  assert.ok(selected);
  const advisory = 'GHSA-synthetic-unit-only-resources-flatten';
  const finding = { sourceClass: 'E4_OSV_SCANNER', findingId: createHash('sha256').update([
    'OSV', advisory, selected.package.ecosystem, selected.package.name, selected.package.version].join('\0')).digest('hex'),
    upstreamFindingId: advisory, aliases: [], package: selected.package, componentRefs: [...selected.componentRefs].sort(),
    scopes: [...selected.scopes].sort(), upstreamSeverity: [], fixedVersions: [] };
  const raw = { results: [{ source: { path: inputPath, type: 'lockfile' },
    packages: input.scannerInput.results[0].packages.map(({ package: identity }) => ({ package: identity,
      vulnerabilities: identity.name === selected.package.name && identity.version === selected.package.version ? [{ id: advisory }] : [] })) }] };
  Object.assign(scanners.osv, { findings: [finding], findingCount: 1, inputPackageCount: input.packageCount,
    binarySha256: 'a'.repeat(64), coverage: rfBuildCoverage(raw, e2, { head: e2.commitSha,
      graphDigest: api.RESOURCES_FLATTEN_GRAPH, inputBytes: JSON.stringify(input.scannerInput), inputPath, findings: [finding], exitStatus: 1,
      stderr: `Starting filesystem walk for root: /\nScanned ${inputPath} file and found ${input.packageCount} packages\nEnd status: 0 dirs visited, 1 inodes visited, 1 Extract calls, 1ms elapsed, 1ms wall time\n` }) });
  scanners.gitleaks.candidateSecretMaterialRetained = false; scanners.semgrep.sourceSnippetRetained = false;
  const head = e2.commitSha, tree = '9'.repeat(40);
  return resign({ schemaVersion: 'M6_PR_E_E4_SCANNER_EVIDENCE_V1', repository: e2.repository, commitSha: head,
    e2CurrentEvidence: e2, e2CurrentContentSha256: e2.contentSha256, e2GraphDigest: api.RESOURCES_FLATTEN_GRAPH,
    e2GraphTransition: router.verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, head),
    checkout: { checkedOutSha: head, expectedHeadSha: head, checkedOutTreeSha: tree, expectedHeadTreeSha: tree,
      exactTreeMatches: true, trackedWorktreeClean: true }, scannerBaselineSourceHead: baseline.sourceHead,
    scanners, totalFindingCount: 1, allScannersCompleted: true, rawScannerReportsRetained: false,
    candidateSecretMaterialRetained: false, authoritativeGitHubInventoryStillUnavailable: true, workstreamReleaseBlocked: true });
}

test('Resources/Flatten public R4 uses two complete admissions and preserves current E4 gates', async t => {
  const directory = rfFs.mkdtempSync(rfJoin(rfTmpdir(), 'resources-flatten-r4-'));
  t.after(() => rfFs.rmSync(directory, { recursive: true, force: true }));
  const manifest = rfCopyFixture(directory);
  const api = await import(rfFileUrl(rfJoin(directory, 'scripts/security/resources-flatten-graph-transition.mjs')));
  const router = await import(rfFileUrl(rfJoin(directory, 'scripts/security/observability-dependency-graph.mjs')));
  const r4 = await import(rfFileUrl(rfJoin(directory, 'scripts/security/m6-pr-e-e3-verify-server-dependency-remediation.mjs')));
  const e4 = rfSyntheticE4(api, router), plan = r4.readServerDependencyRemediationPlan();
  const invoke = (value = e4, head = e4.commitSha) => r4.verifyServerDependencyRemediation(value, plan, { expectedCommitSha: head });
  const forbidden = () => { throw Error('Unexpected subprocess or network execution'); }, originals = [];
  for (const [object, keys] of [[rfChildProcess, ['spawn', 'spawnSync', 'exec', 'execSync', 'execFile', 'execFileSync', 'fork']],
    [rfHttp, ['request', 'get']], [rfHttps, ['request', 'get']], [rfNet, ['connect', 'createConnection']], [globalThis, ['fetch']]])
    for (const key of keys) { originals.push([object, key, object[key]]); object[key] = forbidden; }
  rfSyncBuiltins(); t.after(() => { for (const [object, key, value] of originals) object[key] = value; rfSyncBuiltins(); });
  const originalRead = rfFs.readFileSync;
  const normalize = value => value instanceof URL ? rfFilePath(value) : String(value);
  function withHook(hook, callback) {
    rfFs.readFileSync = function(path, ...args) { const raw = originalRead.call(this, path, ...args); hook(normalize(path), raw); return raw; };
    rfSyncBuiltins();
    try { return callback(); } finally { rfFs.readFileSync = originalRead; rfSyncBuiltins(); }
  }
  const models = ['baselineNormalModel', 'candidateNormalModel', 'baselineDemoModel', 'candidateDemoModel'];
  await t.test('synthetic success retains the unknown finding and independently reads each XML four times', () => {
    const reads = new Map(), before = structuredClone(e4); let totalBytes = 0, jsonParses = 0, result;
    const originalParse = JSON.parse; JSON.parse = (...args) => { jsonParses++; return originalParse(...args); };
    try { result = withHook((path, raw) => { totalBytes += Buffer.byteLength(raw); reads.set(path, (reads.get(path) || 0) + 1); }, () => invoke()); }
    finally { JSON.parse = originalParse; }
    t.diagnostic(JSON.stringify({ scope: 'PURE_SYNTHETIC_PUBLIC_R4_NOT_SCANNER_EVIDENCE', totalReads: [...reads.values()].reduce((a, b) => a + b, 0), totalBytes, jsonParses, xmlParses: 0, eachModelReads: models.map(key => reads.get(rfJoin(directory, manifest.archives[key].path))) }));
    for (const key of models) assert.equal(reads.get(rfJoin(directory, manifest.archives[key].path)), 4, key);
    assert.equal(result.releaseBlocked, true); assert.equal(result.findingReviewRequired, true);
    assert.equal(result.remediatedFindings.length, 2); assert.equal(result.subsequentGraphTransition.currentE2GraphDigest, api.RESOURCES_FLATTEN_GRAPH);
    assert.deepEqual(e4, before); assert.equal(e4.scanners.osv.coverage.inputPackageCount, 465);
    assert.equal(e4.scanners.osv.findings[0].upstreamFindingId, 'GHSA-synthetic-unit-only-resources-flatten');
  });
  for (const [name, edit] of [
    ['forged external verified receipt', value => { value.e2GraphTransition = resign({ ...value.e2GraphTransition, verified: true }); }],
    ['missing scanner', value => { delete value.scanners.semgrep; }],
    ['incomplete scanner', value => { value.scanners.osv.scanCompleted = false; }],
    ['missing coverage target', value => { value.scanners.osv.coverage.targets.pop(); resign(value.scanners.osv.coverage); }],
    ['changed target refs', value => { value.scanners.osv.coverage.targets[0].componentRefs = ['forged']; resign(value.scanners.osv.coverage); }],
    ['changed target scopes', value => { value.scanners.osv.coverage.targets[0].scopes = ['forged']; resign(value.scanners.osv.coverage); }],
    ['retained advisory alias reappears', value => { value.scanners.osv.findings[0].aliases = [plan.remediatedFindings[0].aliases[0]];
      value.scanners.osv.coverage.normalizedFindingsSha256 = graphHash(value.scanners.osv.findings); resign(value.scanners.osv.coverage); }],
  ]) await t.test(`rejects ${name} after envelope rehash`, () => {
    const value = structuredClone(e4); edit(value); resign(value); assert.throws(() => invoke(value));
  });
  await t.test('rejects an absent or wrong independently supplied head', () => {
    assert.throws(() => r4.verifyServerDependencyRemediation(e4, plan), /independent current Head/);
    assert.throws(() => invoke(e4, 'b'.repeat(40)), /independent current Head/);
  });
  for (const path of ['pom.xml', 'integrations/host-sdk/pom.xml', 'docs/operations/assembly-enforcer-lang-evidence/preserved-server-remediation.json',
    'docs/operations/assembly-enforcer-lang-evidence/preserved-jar-lineage.json', ...['oldRoot', 'oldSdk', 'modelData', ...models].map(key => manifest.archives[key].path)]) {
    await t.test(`final admission rejects mutation after coverage: ${path}`, () => {
      const file = rfJoin(directory, path), original = originalRead(file); let bridgeReads = 0, changed = false;
      try {
        withHook(readPath => {
          // Each initial proof reads the public bridge directly and through its
          // public inventory; two proofs mean four reads. The fifth is the
          // R4 bridge after verifyOsvCoverage, before target/absence/final admission.
          if (readPath === rfJoin(directory, 'docs/operations/assembly-enforcer-lang-evidence/preserved-server-remediation.json') && ++bridgeReads === 5) {
            rfFs.writeFileSync(file, Buffer.concat([original, Buffer.from('\n')])); changed = true;
          }
        }, () => assert.throws(() => invoke(), /byte digest mismatch/));
        assert.equal(changed, true);
      } finally { rfFs.writeFileSync(file, original); }
    });
  }
  for (const path of ['pom.xml', 'integrations/host-sdk/pom.xml', manifest.archives.candidateNormalModel.path, manifest.archives.modelData.path]) {
    await t.test(`final admission rejects a second mutation during its own reads: ${path}`, () => {
      const file = rfJoin(directory, path), original = originalRead(file); let modelReads = 0, finalSourceReads = 0, changed = false;
      try {
        withHook(readPath => {
          if (readPath === rfJoin(directory, manifest.archives.candidateNormalModel.path)) modelReads++;
          // Third model read identifies the start of the final complete admission.
          // Its second root read completes the first raw POM reversal; finalFresh
          // must see a source/model changed immediately after that accepted read.
          if (modelReads === 3 && readPath === rfJoin(directory, 'pom.xml') && ++finalSourceReads === 2) {
            rfFs.writeFileSync(file, Buffer.concat([original, Buffer.from('\n')])); changed = true;
          }
        }, () => assert.throws(() => invoke(), /byte digest mismatch/));
        assert.equal(changed, true);
      } finally { rfFs.writeFileSync(file, original); }
    });
  }
  for (const [name, edit, pattern] of [
    ['current head', value => { value.commitSha = 'b'.repeat(40); }, /current verified head/],
    ['current E2 content identity', value => { value.e2CurrentContentSha256 = 'f'.repeat(64); }, /identity mismatch/],
    ['external receipt', value => { value.e2GraphTransition.verified = true; }, /lineage mismatch/],
  ]) await t.test(`final admission rejects post-coverage ${name} mutation`, () => {
    const value = structuredClone(e4); let bridgeReads = 0, changed = false;
    withHook(readPath => {
      if (readPath === rfJoin(directory, 'docs/operations/assembly-enforcer-lang-evidence/preserved-server-remediation.json') && ++bridgeReads === 5) { edit(value); changed = true; }
    }, () => assert.throws(() => invoke(value), pattern));
    assert.equal(changed, true);
  });
  await t.test('final admission repeats current Actions identity checks', () => {
    const actionPath = Object.keys(rfReadJson(manifest.archives.modelExecution.path).captureInputs).find(path => path.startsWith('.github/workflows/'));
    assert.ok(actionPath); const file = rfJoin(directory, actionPath), original = originalRead(file); let bridgeReads = 0;
    try {
      withHook(readPath => {
        if (readPath === rfJoin(directory, 'docs/operations/assembly-enforcer-lang-evidence/preserved-server-remediation.json') && ++bridgeReads === 5)
          rfFs.writeFileSync(file, Buffer.concat([original, Buffer.from('\n')]));
      }, () => assert.throws(() => invoke(), /byte digest mismatch|Actions source drift/));
    } finally { rfFs.writeFileSync(file, original); }
  });
});
