import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { acceptedE2GraphProjection } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { BOOT_UTILITIES_GRAPH, readBootUtilitiesManifest, readBootUtilitiesBaseline, readBootUtilitiesCandidate,
  readBootUtilitiesSourceWitness, readBootUtilitiesReport, readBootUtilitiesPreservedBootHttpLineage,
  verifyBootUtilitiesDelta, verifyBootUtilitiesOwnerSubstitution, verifyBootUtilitiesGraphSubstitution }
  from '../security/boot-utilities-graph-transition.mjs';
import { BOOT_HTTP_GRAPH, readBootHttpReport, readBootHttpCandidate,
  BASE_GRAPH, graphHash, verifyObservabilityGraph, requirePreservedGraph }
  from '../security/observability-dependency-graph.mjs';
import { osvInputFromE2, verifyOsvCoverage } from '../security/osv-scan-coverage.mjs';
import { syntheticBootUtilitiesOsv } from './fixtures/boot-utilities-fixture.mjs';

const sha = value => createHash('sha256').update(value).digest('hex');
const OWNER = 'org.springframework.boot:spring-boot-maven-plugin:jar:4.0.8';
const OLD = 'org.codehaus.plexus:plexus-utils:jar:3.5.1';
const NEW = 'org.codehaus.plexus:plexus-utils:jar:3.6.1';
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: graphHash(payload) }; };
const admit = e2 => verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, e2.commitSha);
// These negative cases exercise pure substitutions. Validate their retained
// source/evidence once, then isolate each mutation with a fresh deep copy.
const retainedFixture = {
  beforeRaw: readBootHttpReport(), afterRaw: readBootUtilitiesReport(),
  before: acceptedE2GraphProjection(readBootUtilitiesBaseline()), after: acceptedE2GraphProjection(readBootUtilitiesCandidate()),
};
function fixture() {
  return structuredClone(retainedFixture);
}
test('actual Boot utility capture has precisely two substitutions in one owner and reverses to accepted main1846', () => {
  const f = fixture(), manifest = readBootUtilitiesManifest(), owners = verifyBootUtilitiesOwnerSubstitution(f.beforeRaw, f.afterRaw);
  assert.equal(owners.ownerCount, 17); assert.equal(owners.unchangedOtherPluginOwnerCount, 16);
  assert.deepEqual(owners.changes, manifest.pluginOwnerChanges);
  const beforeUnion = new Set(Object.values(owners.before.owners).flat());
  const afterUnion = new Set(Object.values(owners.after.owners).flat());
  assert.deepEqual(manifest.addedPluginCoordinates, [...afterUnion].filter(value => !beforeUnion.has(value)).sort());
  assert.deepEqual(manifest.removedPluginCoordinates, [...beforeUnion].filter(value => !afterUnion.has(value)).sort());
  assert.equal(manifest.addedPluginCoordinateCount, manifest.addedPluginCoordinates.length);
  assert.equal(manifest.removedPluginCoordinateCount, manifest.removedPluginCoordinates.length);
  assert.ok(beforeUnion.has('org.apache.commons:commons-lang3:jar:3.18.0'));
  assert.ok(afterUnion.has('org.apache.commons:commons-lang3:jar:3.18.0'));
  assert.equal(manifest.addedPluginCoordinates.includes('org.apache.commons:commons-lang3:jar:3.18.0'), false);
  assert.equal(owners.after.owners[OWNER].length, 39);
  assert.deepEqual(verifyBootUtilitiesGraphSubstitution(f.after, f.before, f.beforeRaw, f.afterRaw), f.before);
  assert.deepEqual(verifyBootUtilitiesDelta(f.after), f.before);
  assert.equal(graphHash(f.before), BOOT_HTTP_GRAPH); assert.equal(graphHash(f.after), BOOT_UTILITIES_GRAPH);
  assert.equal(f.after.maven.components.length, 237); assert.equal(f.after.maven.edges.length, 345);
  assert.equal(f.after.maven.resolvedPluginCoordinates.length, manifest.inventory.resolvedPluginCoordinateCount);
  for (const [name, evidence] of [['prior', f.before], ['current', f.after]]) {
    const input = osvInputFromE2(evidence);
    assert.equal(input.packageCount, manifest.osvInput[name].packageCount);
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
  assert.throws(() => verifyBootUtilitiesGraphSubstitution(f.after, f.before, f.beforeRaw, f.afterRaw));
});
for (const [name, alter] of [
  ['no substitution', f => f.beforeRaw],
  ['unrelated owner with unchanged global union', f => f.afterRaw.replace(/^(   \S+\n)/m, `$1      ${NEW}\n`)],
  ['duplicate owner occurrence', f => f.afterRaw + `\n   ${OWNER}\n      ${OWNER}\n      ${NEW}\n`],
  ['extra dependency', f => f.afterRaw.replace(`   ${OWNER}\n`, `   ${OWNER}\n      foreign:dependency:jar:1\n`)],
  ['Client regression', f => f.afterRaw.replaceAll('org.apache.httpcomponents.client5:httpclient5:jar:5.6.3', 'org.apache.httpcomponents.client5:httpclient5:jar:5.5.2')],
  ['Core regression', f => f.afterRaw.replaceAll('org.apache.httpcomponents.core5:httpcore5:jar:5.4.3', 'org.apache.httpcomponents.core5:httpcore5:jar:5.3.6')],
  ['Plexus regression', f => f.afterRaw.replaceAll(NEW, OLD)],
  ['Lang regression', f => f.afterRaw.replaceAll('org.apache.commons:commons-lang3:jar:3.18.0', 'org.apache.commons:commons-lang3:jar:3.16.0')],
  ['Core-H2 regression', f => f.afterRaw.replaceAll('org.apache.httpcomponents.core5:httpcore5-h2:jar:5.4.3', 'org.apache.httpcomponents.core5:httpcore5-h2:jar:5.3.6')],
  ['Jackson regression', f => f.afterRaw.replaceAll('tools.jackson.core:jackson-core:jar:3.1.7', 'tools.jackson.core:jackson-core:jar:3.1.6')],
  ['foreign report line', f => f.afterRaw + '\nforged report\n'],
]) test(`Boot owner evidence rejects ${name}`, () => {
  const f = fixture(), changed = alter(f); assert.notEqual(changed, f.afterRaw);
  assert.throws(() => verifyBootUtilitiesOwnerSubstitution(f.beforeRaw, changed));
});
test('Boot source witness makes only the actual capture claim', () => {
  const witness = readBootUtilitiesSourceWitness();
  assert.equal(witness.sourcePomFiles.length, 28); assert.equal(Object.keys(witness.generatorSources).length, 4);
  for (const key of ['compatibilityAcceptanceClaimed', 'scannerDispositionClaimed', 'reviewerAcceptanceClaimed', 'exactHeadEvidence', 'sourceWorktreeClean'])
    assert.equal(witness[key], false);
  assert.equal(witness.releaseBlocked, true);
});
test('Boot utility receipt preserves complete accepted HTTP and older Guava/Checkstyle/Compiler lineage', () => {
  const e2 = readBootUtilitiesCandidate(), receipt = admit(e2), preserved = readBootUtilitiesPreservedBootHttpLineage();
  assert.equal(receipt.schemaVersion, 'APPROVAL_BOOT_UTILITIES_GRAPH_LINEAGE_V1');
  assert.deepEqual(receipt.preservedBootHttpLineage, preserved);
  assert.deepEqual(admit(readBootUtilitiesBaseline()), preserved);
  assert.equal(admit(readBootHttpCandidate()).currentE2GraphDigest, BOOT_HTTP_GRAPH);
  const envelope = { repository: e2.repository, commitSha: e2.commitSha, e2GraphDigest: BOOT_UTILITIES_GRAPH,
    e2CurrentContentSha256: e2.contentSha256, e2GraphTransition: receipt };
  assert.deepEqual(requirePreservedGraph(envelope, BASE_GRAPH, e2.commitSha), receipt);
  assert.throws(() => requirePreservedGraph(envelope, BASE_GRAPH), /current verified head/);
  envelope.e2GraphTransition.preservedBootHttpLineage.releaseBlocked = false;
  assert.throws(() => requirePreservedGraph(envelope, BASE_GRAPH, e2.commitSha), /lineage mismatch/);
});
test('later-head synthetic evidence keeps all current findings and full coverage', () => {
  const e2 = seal({ ...readBootUtilitiesCandidate(), commitSha: 'a'.repeat(40) });
  assert.equal(admit(e2).commitSha, e2.commitSha);
  const scan = syntheticBootUtilitiesOsv(e2, {});
  verifyOsvCoverage(scan.coverage, scan.findings, e2, { head: e2.commitSha, graphDigest: BOOT_UTILITIES_GRAPH });
  assert.equal(scan.findings.length, 1); assert.equal(scan.findings[0].package.version, '3.6.1');
  assert.throws(() => verifyOsvCoverage(scan.coverage, [], e2, { head: e2.commitSha, graphDigest: BOOT_UTILITIES_GRAPH }));
  const altered = structuredClone(e2); altered.toolchain = { forged: true };
  assert.throws(() => admit(seal(altered)), /source\/Actions\/toolchain drift/);
});
