import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { acceptedE2GraphProjection } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { CHECKSTYLE_GUAVA_GRAPH, readCheckstyleGuavaManifest, readCheckstyleGuavaBaseline,
  readCheckstyleGuavaCandidate, readCheckstyleGuavaSourceWitness, readCheckstyleGuavaReport,
  readCheckstyleGuavaPreservedCheckstyleLineage, verifyCheckstyleGuavaDelta,
  verifyCheckstyleGuavaOwnerSubstitution, verifyCheckstyleGuavaGraphSubstitution }
  from '../security/checkstyle-guava-graph-transition.mjs';
import { CHECKSTYLE_PLUGIN_GRAPH, readCheckstylePluginReport, readCheckstylePluginCandidate,
  BASE_GRAPH, graphHash, verifyObservabilityGraph, requirePreservedGraph }
  from '../security/observability-dependency-graph.mjs';
import { osvInputFromE2, verifyOsvCoverage } from '../security/osv-scan-coverage.mjs';
import { syntheticCheckstyleGuavaOsv } from './fixtures/checkstyle-guava-fixture.mjs';

const sha = value => createHash('sha256').update(value).digest('hex');
const OWNER = 'org.apache.maven.plugins:maven-checkstyle-plugin:jar:3.6.0';
const OLD = 'com.google.guava:guava:jar:31.0.1-jre', NEW = 'com.google.guava:guava:jar:32.0.1-jre';
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: graphHash(payload) }; };
const admit = e2 => verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, e2.commitSha);
function fixture() {
  return { beforeRaw: readCheckstylePluginReport(), afterRaw: readCheckstyleGuavaReport(),
    before: acceptedE2GraphProjection(readCheckstyleGuavaBaseline()),
    after: acceptedE2GraphProjection(readCheckstyleGuavaCandidate()) };
}
test('actual Guava capture changes exactly four coordinates in one owner and reverses to accepted main1841', () => {
  const f = fixture(), manifest = readCheckstyleGuavaManifest();
  const owners = verifyCheckstyleGuavaOwnerSubstitution(f.beforeRaw, f.afterRaw);
  assert.equal(owners.ownerCount, 17); assert.equal(owners.unchangedOtherPluginOwnerCount, 16);
  assert.deepEqual(owners.changes, manifest.pluginOwnerChanges);
  assert.equal(manifest.addedPluginCoordinateCount, 4); assert.equal(manifest.removedPluginCoordinateCount, 4);
  assert.equal(f.after.maven.resolvedPluginCoordinates.length, 269);
  assert.deepEqual(verifyCheckstyleGuavaGraphSubstitution(f.after, f.before, f.beforeRaw, f.afterRaw), f.before);
  assert.deepEqual(verifyCheckstyleGuavaDelta(f.after), f.before);
  assert.equal(graphHash(f.before), CHECKSTYLE_PLUGIN_GRAPH); assert.equal(graphHash(f.after), CHECKSTYLE_GUAVA_GRAPH);
  assert.equal(f.after.maven.components.length, 237); assert.equal(f.after.maven.edges.length, 345);
  for (const [name, evidence] of [['prior', f.before], ['current', f.after]]) {
    const input = osvInputFromE2(evidence);
    assert.equal(input.packageCount, 472); assert.equal(manifest.osvInput[name].packageCount, 472);
    assert.equal(sha(JSON.stringify(input.scannerInput)), manifest.osvInput[name].inputBytesSha256);
  }
});
for (const [name, alter] of [
  ['runtime component', p => { p.maven.components[0].version = 'forged'; }],
  ['runtime edge', p => { p.maven.edges.pop(); }],
  ['imported BOM', p => { p.maven.importedBoms.reverse(); }],
  ['scope', p => { p.maven.components[0].scope = 'forged'; }],
  ['license', p => { p.maven.components[0].licenses = ['forged']; }],
  ['root identity', p => { p.maven.rootProject = 'forged'; }],
  ['Actions', p => { p.githubActions = { forged: true }; }],
  ['limitations', p => { p.limitations = ['forged']; }],
  ['pnpm graph', p => { p.pnpm = { forged: true }; }],
  ['old coordinate injected', p => { p.maven.resolvedPluginCoordinates.push(OLD); }],
  ['duplicate coordinate', p => { p.maven.resolvedPluginCoordinates.push(p.maven.resolvedPluginCoordinates[0]); }],
  ['report hash', p => { p.maven.pluginResolutionSha256 = '0'.repeat(64); }],
]) test(`Guava full graph rejects ${name}`, () => {
  const f = fixture(); alter(f.after);
  assert.throws(() => verifyCheckstyleGuavaGraphSubstitution(f.after, f.before, f.beforeRaw, f.afterRaw));
});
for (const [name, alter] of [
  ['no substitution', f => f.beforeRaw],
  ['unrelated owner with unchanged global union', f => f.afterRaw.replace(/^(   \S+\n)/m, `$1      ${NEW}\n`)],
  ['duplicate owner occurrence', f => f.afterRaw + `\n   ${OWNER}\n      ${OWNER}\n      ${NEW}\n`],
  ['extra Checkstyle dependency', f => f.afterRaw.replace(`   ${OWNER}\n`, `   ${OWNER}\n      foreign:dependency:jar:1\n`)],
  ['Lang regression', f => f.afterRaw.replaceAll('org.apache.commons:commons-lang3:jar:3.18.0', 'org.apache.commons:commons-lang3:jar:3.17.0')],
  ['missing checker substitution', f => f.afterRaw.replaceAll('org.checkerframework:checker-qual:jar:3.33.0', 'org.checkerframework:checker-qual:jar:3.12.0')],
  ['missing error-prone substitution', f => f.afterRaw.replaceAll('com.google.errorprone:error_prone_annotations:jar:2.18.0', 'com.google.errorprone:error_prone_annotations:jar:2.7.1')],
  ['missing j2objc substitution', f => f.afterRaw.replaceAll('com.google.j2objc:j2objc-annotations:jar:2.8', 'com.google.j2objc:j2objc-annotations:jar:1.3')],
  ['foreign line', f => f.afterRaw + '\nforged report\n'],
]) test(`Guava owner evidence rejects ${name}`, () => {
  const f = fixture(), changed = alter(f); assert.notEqual(changed, f.afterRaw);
  assert.throws(() => verifyCheckstyleGuavaOwnerSubstitution(f.beforeRaw, changed));
});
test('Guava source witness binds actual offline source closure without claiming compatibility or scanner acceptance', () => {
  const witness = readCheckstyleGuavaSourceWitness();
  assert.equal(witness.sourcePomFiles.length, 28); assert.equal(Object.keys(witness.generatorSources).length, 4);
  assert.equal(witness.compatibilityAcceptanceClaimed, false); assert.equal(witness.scannerDispositionClaimed, false);
  assert.equal(witness.reviewerAcceptanceClaimed, false); assert.equal(witness.exactHeadEvidence, false);
  assert.equal(witness.sourceWorktreeClean, false); assert.equal(witness.releaseBlocked, true);
});
test('Guava lineage nests the complete accepted Checkstyle receipt and preserves historical admission', () => {
  const e2 = readCheckstyleGuavaCandidate(), lineage = admit(e2), preserved = readCheckstyleGuavaPreservedCheckstyleLineage();
  assert.equal(lineage.schemaVersion, 'APPROVAL_CHECKSTYLE_GUAVA_GRAPH_LINEAGE_V1');
  assert.deepEqual(lineage.preservedCheckstylePluginLineage, preserved);
  assert.deepEqual(admit(readCheckstyleGuavaBaseline()), preserved);
  assert.equal(admit(readCheckstylePluginCandidate()).currentE2GraphDigest, CHECKSTYLE_PLUGIN_GRAPH);
  const envelope = { repository: e2.repository, commitSha: e2.commitSha, e2GraphDigest: CHECKSTYLE_GUAVA_GRAPH,
    e2CurrentContentSha256: e2.contentSha256, e2GraphTransition: lineage };
  assert.deepEqual(requirePreservedGraph(envelope, BASE_GRAPH, e2.commitSha), lineage);
  assert.throws(() => requirePreservedGraph(envelope, BASE_GRAPH), /current verified head/);
  envelope.e2GraphTransition.preservedCheckstylePluginLineage.releaseBlocked = false;
  assert.throws(() => requirePreservedGraph(envelope, BASE_GRAPH, e2.commitSha), /lineage mismatch/);
});
test('synthetic later-head E2 preserves capture identity and every current finding remains present', () => {
  const e2 = seal({ ...readCheckstyleGuavaCandidate(), commitSha: 'a'.repeat(40) });
  const lineage = admit(e2); assert.equal(lineage.commitSha, e2.commitSha);
  const scan = syntheticCheckstyleGuavaOsv(e2, {});
  verifyOsvCoverage(scan.coverage, scan.findings, e2, { head: e2.commitSha, graphDigest: CHECKSTYLE_GUAVA_GRAPH });
  assert.equal(scan.findings.length, 1); assert.equal(scan.findings[0].package.version, '32.0.1-jre');
  assert.throws(() => verifyOsvCoverage(scan.coverage, [], e2, { head: e2.commitSha, graphDigest: CHECKSTYLE_GUAVA_GRAPH }));
  const altered = structuredClone(e2); altered.toolchain = { forged: true };
  assert.throws(() => admit(seal(altered)), /source\/Actions\/toolchain drift/);
});
