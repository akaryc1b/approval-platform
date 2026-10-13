import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { acceptedE2GraphProjection } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { BOOT_HTTP_GRAPH, readBootHttpManifest, readBootHttpBaseline, readBootHttpCandidate,
  readBootHttpSourceWitness, readBootHttpReport, readBootHttpPreservedGuavaLineage,
  verifyBootHttpDelta, verifyBootHttpOwnerSubstitution, verifyBootHttpGraphSubstitution }
  from '../security/boot-http-graph-transition.mjs';
import { CHECKSTYLE_GUAVA_GRAPH, readCheckstyleGuavaReport, readCheckstyleGuavaCandidate,
  BASE_GRAPH, graphHash, verifyObservabilityGraph, requirePreservedGraph }
  from '../security/observability-dependency-graph.mjs';
import { osvInputFromE2, verifyOsvCoverage } from '../security/osv-scan-coverage.mjs';
import { syntheticBootHttpOsv } from './fixtures/boot-http-fixture.mjs';

const sha = value => createHash('sha256').update(value).digest('hex');
const OWNER = 'org.springframework.boot:spring-boot-maven-plugin:jar:4.0.8';
const OLD = 'org.apache.httpcomponents.core5:httpcore5:jar:5.3.6';
const NEW = 'org.apache.httpcomponents.core5:httpcore5:jar:5.4.3';
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: graphHash(payload) }; };
const admit = e2 => verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, e2.commitSha);
// These negative cases exercise pure substitutions. Validate their retained
// source/evidence once, then isolate each mutation with a fresh deep copy.
const retainedFixture = {
  beforeRaw: readCheckstyleGuavaReport(), afterRaw: readBootHttpReport(),
  before: acceptedE2GraphProjection(readBootHttpBaseline()), after: acceptedE2GraphProjection(readBootHttpCandidate()),
};
function fixture() {
  return structuredClone(retainedFixture);
}
test('actual Boot capture has precisely three substitutions in one owner and reverses to accepted main1843', () => {
  const f = fixture(), manifest = readBootHttpManifest(), owners = verifyBootHttpOwnerSubstitution(f.beforeRaw, f.afterRaw);
  assert.equal(owners.ownerCount, 17); assert.equal(owners.unchangedOtherPluginOwnerCount, 16);
  assert.deepEqual(owners.changes, manifest.pluginOwnerChanges);
  assert.equal(manifest.addedPluginCoordinateCount, 3); assert.equal(manifest.removedPluginCoordinateCount, 3);
  assert.equal(owners.after.owners[OWNER].length, 39);
  assert.deepEqual(verifyBootHttpGraphSubstitution(f.after, f.before, f.beforeRaw, f.afterRaw), f.before);
  assert.deepEqual(verifyBootHttpDelta(f.after), f.before);
  assert.equal(graphHash(f.before), CHECKSTYLE_GUAVA_GRAPH); assert.equal(graphHash(f.after), BOOT_HTTP_GRAPH);
  assert.equal(f.after.maven.components.length, 237); assert.equal(f.after.maven.edges.length, 345);
  assert.equal(f.after.maven.resolvedPluginCoordinates.length, 269);
  for (const [name, evidence] of [['prior', f.before], ['current', f.after]]) {
    const input = osvInputFromE2(evidence);
    assert.equal(input.packageCount, 472); assert.equal(manifest.osvInput[name].packageCount, 472);
    assert.equal(sha(JSON.stringify(input.scannerInput)), manifest.osvInput[name].inputBytesSha256);
  }
});
for (const [name, mutate] of [
  ['runtime component', p => { p.maven.components[0].version = 'forged'; }],
  ['runtime edge', p => { p.maven.edges.pop(); }],
  ['imported BOM', p => { p.maven.importedBoms.reverse(); }],
  ['scope', p => { p.maven.components[0].scope = 'forged'; }],
  ['license', p => { p.maven.components[0].licenses = ['forged']; }],
  ['Actions', p => { p.githubActions = { forged: true }; }],
  ['limitations', p => { p.limitations = ['forged']; }],
  ['pnpm', p => { p.pnpm = { forged: true }; }],
  ['old coordinate injected', p => { p.maven.resolvedPluginCoordinates.push(OLD); }],
  ['duplicate coordinate', p => { p.maven.resolvedPluginCoordinates.push(p.maven.resolvedPluginCoordinates[0]); }],
  ['report hash', p => { p.maven.pluginResolutionSha256 = '0'.repeat(64); }],
]) test(`Boot full graph rejects ${name}`, () => {
  const f = fixture(); mutate(f.after);
  assert.throws(() => verifyBootHttpGraphSubstitution(f.after, f.before, f.beforeRaw, f.afterRaw));
});
for (const [name, alter] of [
  ['no substitution', f => f.beforeRaw],
  ['unrelated owner with unchanged global union', f => f.afterRaw.replace(/^(   \S+\n)/m, `$1      ${NEW}\n`)],
  ['duplicate owner occurrence', f => f.afterRaw + `\n   ${OWNER}\n      ${OWNER}\n      ${NEW}\n`],
  ['extra dependency', f => f.afterRaw.replace(`   ${OWNER}\n`, `   ${OWNER}\n      foreign:dependency:jar:1\n`)],
  ['Client regression', f => f.afterRaw.replaceAll('org.apache.httpcomponents.client5:httpclient5:jar:5.6.3', 'org.apache.httpcomponents.client5:httpclient5:jar:5.5.2')],
  ['Core regression', f => f.afterRaw.replaceAll(NEW, OLD)],
  ['Core-H2 regression', f => f.afterRaw.replaceAll('org.apache.httpcomponents.core5:httpcore5-h2:jar:5.4.3', 'org.apache.httpcomponents.core5:httpcore5-h2:jar:5.3.6')],
  ['Jackson regression', f => f.afterRaw.replaceAll('tools.jackson.core:jackson-core:jar:3.1.7', 'tools.jackson.core:jackson-core:jar:3.1.6')],
  ['foreign report line', f => f.afterRaw + '\nforged report\n'],
]) test(`Boot owner evidence rejects ${name}`, () => {
  const f = fixture(), changed = alter(f); assert.notEqual(changed, f.afterRaw);
  assert.throws(() => verifyBootHttpOwnerSubstitution(f.beforeRaw, changed));
});
test('Boot source witness makes only the actual capture claim', () => {
  const witness = readBootHttpSourceWitness();
  assert.equal(witness.sourcePomFiles.length, 28); assert.equal(Object.keys(witness.generatorSources).length, 4);
  for (const key of ['compatibilityAcceptanceClaimed', 'scannerDispositionClaimed', 'reviewerAcceptanceClaimed', 'exactHeadEvidence', 'sourceWorktreeClean'])
    assert.equal(witness[key], false);
  assert.equal(witness.releaseBlocked, true);
});
test('Boot receipt preserves complete accepted Guava and older Checkstyle/Compiler lineage', () => {
  const e2 = readBootHttpCandidate(), receipt = admit(e2), preserved = readBootHttpPreservedGuavaLineage();
  assert.equal(receipt.schemaVersion, 'APPROVAL_BOOT_HTTP_GRAPH_LINEAGE_V1');
  assert.deepEqual(receipt.preservedCheckstyleGuavaLineage, preserved);
  assert.deepEqual(admit(readBootHttpBaseline()), preserved);
  assert.equal(admit(readCheckstyleGuavaCandidate()).currentE2GraphDigest, CHECKSTYLE_GUAVA_GRAPH);
  const envelope = { repository: e2.repository, commitSha: e2.commitSha, e2GraphDigest: BOOT_HTTP_GRAPH,
    e2CurrentContentSha256: e2.contentSha256, e2GraphTransition: receipt };
  assert.deepEqual(requirePreservedGraph(envelope, BASE_GRAPH, e2.commitSha), receipt);
  assert.throws(() => requirePreservedGraph(envelope, BASE_GRAPH), /current verified head/);
  envelope.e2GraphTransition.preservedCheckstyleGuavaLineage.releaseBlocked = false;
  assert.throws(() => requirePreservedGraph(envelope, BASE_GRAPH, e2.commitSha), /lineage mismatch/);
});
test('later-head synthetic evidence keeps all current findings and full coverage', () => {
  const e2 = seal({ ...readBootHttpCandidate(), commitSha: 'a'.repeat(40) });
  assert.equal(admit(e2).commitSha, e2.commitSha);
  const scan = syntheticBootHttpOsv(e2, {});
  verifyOsvCoverage(scan.coverage, scan.findings, e2, { head: e2.commitSha, graphDigest: BOOT_HTTP_GRAPH });
  assert.equal(scan.findings.length, 1); assert.equal(scan.findings[0].package.version, '5.4.3');
  assert.throws(() => verifyOsvCoverage(scan.coverage, [], e2, { head: e2.commitSha, graphDigest: BOOT_HTTP_GRAPH }));
  const altered = structuredClone(e2); altered.toolchain = { forged: true };
  assert.throws(() => admit(seal(altered)), /source\/Actions\/toolchain drift/);
});
