import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { canonical, sha256, readCurrentSourceTransition, evaluateCurrentEvidence }
  from '../security/m6-pr-e-e3-r3a-review-osv-drift.mjs';

const read = file => JSON.parse(readFileSync(new URL(`../../${file}`, import.meta.url)));
const contract = read('docs/m6/m6-pr-e-e3-r3a-osv-drift-review.json');
const transition = readCurrentSourceTransition();
const e2 = read('docs/operations/server-dependency-evidence-921d5ec5/M6_PR_E_E2_SBOM.json');
const pluginFixture = read('scripts/tests/fixtures/r3a-current-plugin-report.json');
const plugins = pluginFixture.segmentOrder.map(index => pluginFixture.segments[index]).join(pluginFixture.delimiter);
assert.equal(sha256(plugins), pluginFixture.sourceReportSha256);
assert.equal(sha256(plugins), e2.maven.pluginResolutionSha256);
// Explicit unit fixture: actual Maven/JAR collection is exercised separately by the CI entrypoint.
function fixture() {
  return structuredClone({ contract, transition, commitSha: e2.commitSha, currentE2: e2,
    checkout: { expectedHeadSha: e2.commitSha, checkedOutSha: e2.commitSha,
      expectedHeadTreeSha: 'a'.repeat(40), checkedOutTreeSha: 'a'.repeat(40), exactTreeMatches: true, trackedWorktreeClean: true },
    runtimeComponents: [{ groupId: 'org.apache.tomcat.embed', artifactId: 'tomcat-embed-core', version: '11.0.26',
      type: 'jar', scope: 'compile', path: ['io.github.akaryc1b.approval:approval-server:0.1.0-SNAPSHOT',
        'org.springframework.boot:spring-boot-starter-web:4.0.8',
        'org.springframework.boot:spring-boot-starter-tomcat:4.0.8',
        'org.springframework.boot:spring-boot-starter-tomcat-runtime:4.0.8', 'org.apache.tomcat.embed:tomcat-embed-core:11.0.26'] }],
    jarEvidence: { jar: transition.tomcat.jarFileName, jarSha256: transition.tomcat.jarSha256,
      jarBytes: transition.tomcat.jarBytes, entryCount: 1615, vulnerableCloudMembershipEntryCount: 0, vulnerableCloudMembershipEntries: [] },
    sourceMatches: [], pluginReport: plugins });
}

test('R3A current observation keeps the entire historical review separate without transferring old counts or disposition', () => {
  const input = fixture(), before = canonical(input), result = evaluateCurrentEvidence(input);
  assert.equal(canonical(input), before);
  assert.equal(result.schemaVersion, 'M6_PR_E_E3_R3A_OSV_DRIFT_EVIDENCE_V2');
  assert.deepEqual(result.historicalReview.findings, contract.findings);
  assert.deepEqual(result.historicalReview.decision, contract.decision);
  assert.equal(result.historicalReview.findings[0].package.version, '11.0.15');
  assert.equal(result.currentTomcatObservation.package.version, '11.0.26');
  assert.equal(result.currentTomcatObservation.currentFindingPresenceClaimed, false);
  assert.equal(result.currentTomcatObservation.dispositionTransferred, false);
  assert.equal(result.decision.currentOsvTotalsClaimed, false);
  assert.equal(result.decision.cumulativeNotApplicableFindings, undefined);
  assert.equal(result.decision.cumulativeUnresolvedFindings, undefined);
  assert.equal(result.findings.length, 1);
  assert.equal(result.findings[0].findingId, contract.findings[1].findingId);
  assert.equal(result.findings[0].package.version, '5.3.6');
  assert.equal(result.findings[0].disposition, 'UNRESOLVED');
  assert.equal(result.findings[0].evidence.pluginResolutionPaths[0].pluginOwner, 'org.springframework.boot:spring-boot-maven-plugin:4.0.8');
  assert.equal(result.decision.releaseBlocked, true);
});

for (const [name, change] of [
  ['old runtime version', x => { x.runtimeComponents[0].version = '11.0.15'; }],
  ['unknown runtime version', x => { x.runtimeComponents[0].version = '11.0.27'; }],
  ['duplicate stale runtime version', x => { x.runtimeComponents.push({ ...x.runtimeComponents[0], version: '11.0.15' }); }],
  ['missing runtime component', x => { x.runtimeComponents = []; }],
  ['wrong runtime root', x => { x.runtimeComponents[0].path[0] = 'unrelated:root:1'; }],
  ['forged intermediate coordinate', x => { x.runtimeComponents[0].path[1] = 'forged:middle-coordinate:1'; }],
  ['unconnected known intermediate', x => { x.runtimeComponents[0].path[1] = 'org.springframework.boot:spring-boot:4.0.8'; }],
  ['wrong runtime scope', x => { x.runtimeComponents[0].scope = 'test'; }],
  ['tribes added', x => { x.runtimeComponents.push({ groupId: 'org.apache.tomcat', artifactId: 'tomcat-tribes', version: '11.0.26' }); }],
  ['old jar name', x => { x.jarEvidence.jar = 'tomcat-embed-core-11.0.15.jar'; }],
  ['jar byte drift', x => { x.jarEvidence.jarSha256 = '0'.repeat(64); }],
  ['missing jar identity', x => { delete x.jarEvidence.jarSha256; }],
  ['empty jar', x => { x.jarEvidence.entryCount = 0; }],
  ['jar size drift', x => { x.jarEvidence.jarBytes++; }],
  ['vulnerable cloud class', x => { x.jarEvidence.vulnerableCloudMembershipEntryCount = 1; }],
  ['contradictory cloud listing', x => { x.jarEvidence.vulnerableCloudMembershipEntries.push('org/apache/catalina/tribes/membership/cloud/Injected.class'); }],
  ['production activation marker', x => { x.sourceMatches.push({ file: 'apps/server/src/main/resources/application.yml', marker: 'CLOUD_MEMBERSHIP_SERVICE' }); }],
  ['old Boot owner', x => { x.pluginReport = plugins.replaceAll('4.0.8', '4.0.2'); }],
  ['old co-resolved buildpack', x => { x.pluginReport = plugins.replaceAll('spring-boot-buildpack-platform:jar:4.0.8', 'spring-boot-buildpack-platform:jar:4.0.2'); }],
  ['missing HTTP component', x => { x.pluginReport = plugins.replaceAll('      org.apache.httpcomponents.core5:httpcore5:jar:5.3.6\n', ''); }],
  ['forged plugin ownership with old declared hash', x => { x.pluginReport += '\n   forged:plugin:jar:1\n      org.apache.httpcomponents.core5:httpcore5:jar:5.3.6\n'; x.pluginReportSha256 = e2.maven.pluginResolutionSha256; }],
  ['forged co-resolved component', x => { x.pluginReport = plugins.replaceAll('      org.apache.httpcomponents.core5:httpcore5:jar:5.3.6\n', '      org.apache.httpcomponents.core5:httpcore5:jar:5.3.6\n      forged:extra-component:jar:1\n'); }],
  ['missing raw report', x => { delete x.pluginReport; }],
  ['wrong E2 Head', x => { x.currentE2.commitSha = 'f'.repeat(40); }],
  ['wrong expected Head', x => { x.commitSha = 'f'.repeat(40); }],
  ['dirty checkout', x => { x.checkout.trackedWorktreeClean = false; }],
  ['mismatched source tree', x => { x.checkout.expectedHeadTreeSha = 'f'.repeat(40); }],
  ['rehashed historical metadata', x => { x.contract.decision.cumulativeNotApplicableFindings = 3; const { contentSha256, ...p } = x.contract; x.contract.contentSha256 = sha256(canonical(p)); }],
  ['forged transition', x => { x.transition.tomcat.version = '11.0.15'; const { contentSha256, ...p } = x.transition; x.transition.contentSha256 = sha256(canonical(p)); }],
]) test(`R3A current path rejects ${name}`, () => {
  const input = fixture(); change(input); assert.throws(() => evaluateCurrentEvidence(input));
});

test('R3A collector preserves license repository isolation and current source/JAR checks', () => {
  const source = readFileSync(new URL('../security/m6-pr-e-e3-r3a-review-osv-drift.mjs', import.meta.url), 'utf8');
  assert.ok(source.includes('generateE2Evidence(root, { fullMaven: true })'));
  assert.ok(source.includes('jarEntryEvidence(transition.tomcat.version)'));
  assert.ok(source.includes('requireScannerCheckoutUnchanged(root, commitSha, checkout)'));
  const currentCallback = readFileSync(new URL('./m6-pr-e-e3-r3a-osv-drift-applicability-boundary.test.mjs', import.meta.url), 'utf8');
  assert.ok(currentCallback.includes('M6_PR_E_E3_R3A_JAR_REPOSITORY: repository'));
  assert.ok(!currentCallback.includes('M6_PR_E_E2_MAVEN_REPOSITORY: repository'));
});
