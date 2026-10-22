import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { acceptedE2GraphProjection } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { JACOCO_ANTRUN_GRAPH, readJacocoAntrunManifest, readJacocoAntrunBaseline, readJacocoAntrunCandidate,
  readJacocoAntrunSourceWitness, readJacocoAntrunReport, readJacocoAntrunPreservedBootUtilitiesLineage,
  verifyJacocoAntrunDelta, verifyJacocoAntrunOwnerSubstitution, verifyJacocoAntrunGraphSubstitution }
  from '../security/jacoco-antrun-graph-transition.mjs';
import { BOOT_UTILITIES_GRAPH, readBootUtilitiesReport, readBootUtilitiesCandidate,
  BASE_GRAPH, graphHash, verifyObservabilityGraph, requirePreservedGraph }
  from '../security/observability-dependency-graph.mjs';
import { osvInputFromE2, verifyOsvCoverage } from '../security/osv-scan-coverage.mjs';
import { syntheticJacocoAntrunOsv } from './fixtures/jacoco-antrun-fixture.mjs';

const sha = value => createHash('sha256').update(value).digest('hex');
const JACOCO = 'org.jacoco:jacoco-maven-plugin:jar:0.8.15';
const ANTRUN = 'org.apache.maven.plugins:maven-antrun-plugin:jar:3.1.0';
const OLD = 'org.codehaus.plexus:plexus-utils:jar:3.0.24';
const OLD_ANT = 'org.codehaus.plexus:plexus-utils:jar:3.4.1';
const NEW = 'org.codehaus.plexus:plexus-utils:jar:3.6.1';
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: graphHash(payload) }; };
const admit = e2 => verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, e2.commitSha);
// These negative cases exercise pure substitutions. Validate their retained
// source/evidence once, then isolate each mutation with a fresh deep copy.
const retainedManifest = readJacocoAntrunManifest();
const retainedFixture = {
  beforeRaw: readBootUtilitiesReport(), afterRaw: readJacocoAntrunReport(retainedManifest),
  before: acceptedE2GraphProjection(readJacocoAntrunBaseline(retainedManifest)), after: acceptedE2GraphProjection(readJacocoAntrunCandidate(retainedManifest)),
};
function mutateOwner(raw, owner, change) {
  let current;
  return raw.split('\n').map(line => {
    if (/^   \S/.test(line)) current = line.trim();
    return current === owner && /^      \S/.test(line) ? change(line) : line;
  }).join('\n');
}
function fixture() {
  return structuredClone(retainedFixture);
}
test('actual JaCoCo/AntRun capture changes only the two declared owners and reverses to accepted main1852', () => {
  const f = fixture(), manifest = readJacocoAntrunManifest(), owners = verifyJacocoAntrunOwnerSubstitution(f.beforeRaw, f.afterRaw);
  assert.equal(owners.ownerCount, 17); assert.equal(owners.unchangedOtherPluginOwnerCount, 15);
  assert.deepEqual(owners.changes, manifest.pluginOwnerChanges);
  const beforeUnion = new Set(Object.values(owners.before.owners).flat());
  const afterUnion = new Set(Object.values(owners.after.owners).flat());
  assert.deepEqual(manifest.addedPluginCoordinates, [...afterUnion].filter(value => !beforeUnion.has(value)).sort());
  assert.deepEqual(manifest.removedPluginCoordinates, [...beforeUnion].filter(value => !afterUnion.has(value)).sort());
  assert.equal(manifest.addedPluginCoordinateCount, manifest.addedPluginCoordinates.length);
  assert.equal(manifest.removedPluginCoordinateCount, manifest.removedPluginCoordinates.length);
  assert.ok(beforeUnion.has(NEW)); assert.ok(afterUnion.has(NEW));
  assert.deepEqual(manifest.addedPluginCoordinates, []);
  assert.deepEqual(manifest.removedPluginCoordinates, [OLD, OLD_ANT].sort());
  assert.equal(owners.after.owners[JACOCO].length, 13);
  assert.equal(owners.after.owners[ANTRUN].length, 4);
  assert.equal(owners.after.occurrences[JACOCO], 26);
  assert.equal(owners.after.occurrences[ANTRUN], 26);
  assert.deepEqual(verifyJacocoAntrunGraphSubstitution(f.after, f.before, f.beforeRaw, f.afterRaw), f.before);
  assert.deepEqual(verifyJacocoAntrunDelta(f.after), f.before);
  assert.equal(graphHash(f.before), BOOT_UTILITIES_GRAPH); assert.equal(graphHash(f.after), JACOCO_ANTRUN_GRAPH);
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
]) test(`JaCoCo/AntRun full graph rejects ${name}`, () => {
  const f = fixture(); mutate(f.after);
  assert.throws(() => verifyJacocoAntrunGraphSubstitution(f.after, f.before, f.beforeRaw, f.afterRaw));
});
for (const [name, alter] of [
  ['no substitution', f => f.beforeRaw],
  ['unrelated owner with unchanged global union', f => f.afterRaw.replace(/^(   \S+\n)/m, `$1      ${NEW}\n`)],
  ['duplicate owner occurrence', f => f.afterRaw + `\n   ${JACOCO}\n      ${JACOCO}\n      ${NEW}\n`],
  ['extra dependency', f => f.afterRaw.replace(`   ${JACOCO}\n`, `   ${JACOCO}\n      foreign:dependency:jar:1\n`)],
  ['Client regression', f => f.afterRaw.replaceAll('org.apache.httpcomponents.client5:httpclient5:jar:5.6.3', 'org.apache.httpcomponents.client5:httpclient5:jar:5.5.2')],
  ['Core regression', f => f.afterRaw.replaceAll('org.apache.httpcomponents.core5:httpcore5:jar:5.4.3', 'org.apache.httpcomponents.core5:httpcore5:jar:5.3.6')],
  ['Plexus regression', f => f.afterRaw.replaceAll(NEW, OLD)],
  ['only JaCoCo changed', f => mutateOwner(f.afterRaw, ANTRUN, body => body.replace(NEW, OLD_ANT))],
  ['only AntRun changed', f => mutateOwner(f.afterRaw, JACOCO, body => body.replace(NEW, OLD))],
  ['Lang regression', f => f.afterRaw.replaceAll('org.apache.commons:commons-lang3:jar:3.18.0', 'org.apache.commons:commons-lang3:jar:3.16.0')],
  ['Core-H2 regression', f => f.afterRaw.replaceAll('org.apache.httpcomponents.core5:httpcore5-h2:jar:5.4.3', 'org.apache.httpcomponents.core5:httpcore5-h2:jar:5.3.6')],
  ['Jackson regression', f => f.afterRaw.replaceAll('tools.jackson.core:jackson-core:jar:3.1.7', 'tools.jackson.core:jackson-core:jar:3.1.6')],
  ['foreign report line', f => f.afterRaw + '\nforged report\n'],
]) test(`JaCoCo/AntRun owner evidence rejects ${name}`, () => {
  const f = fixture(), changed = alter(f); assert.notEqual(changed, f.afterRaw);
  assert.throws(() => verifyJacocoAntrunOwnerSubstitution(f.beforeRaw, changed));
});
test('JaCoCo/AntRun source witness makes only the actual capture claim', () => {
  const witness = readJacocoAntrunSourceWitness();
  assert.equal(witness.sourcePomFiles.length, 28); assert.equal(Object.keys(witness.generatorSources).length, 4);
  for (const key of ['compatibilityAcceptanceClaimed', 'scannerDispositionClaimed', 'reviewerAcceptanceClaimed', 'exactHeadEvidence', 'sourceWorktreeClean'])
    assert.equal(witness[key], false);
  assert.equal(witness.releaseBlocked, true);
});
test('JaCoCo/AntRun receipt preserves complete accepted Boot utilities and older lineage', () => {
  const e2 = readJacocoAntrunCandidate(), receipt = admit(e2), preserved = readJacocoAntrunPreservedBootUtilitiesLineage();
  assert.equal(receipt.schemaVersion, 'APPROVAL_JACOCO_ANTRUN_GRAPH_LINEAGE_V1');
  assert.deepEqual(receipt.preservedBootUtilitiesLineage, preserved);
  assert.deepEqual(admit(readJacocoAntrunBaseline()), preserved);
  assert.equal(admit(readBootUtilitiesCandidate()).currentE2GraphDigest, BOOT_UTILITIES_GRAPH);
  const envelope = { repository: e2.repository, commitSha: e2.commitSha, e2GraphDigest: JACOCO_ANTRUN_GRAPH,
    e2CurrentContentSha256: e2.contentSha256, e2GraphTransition: receipt };
  assert.deepEqual(requirePreservedGraph(envelope, BASE_GRAPH, e2.commitSha), receipt);
  assert.throws(() => requirePreservedGraph(envelope, BASE_GRAPH), /current verified head/);
  envelope.e2GraphTransition.preservedBootUtilitiesLineage.releaseBlocked = false;
  assert.throws(() => requirePreservedGraph(envelope, BASE_GRAPH, e2.commitSha), /lineage mismatch/);
});
test('later-head synthetic evidence keeps all current findings and full coverage', () => {
  const e2 = seal({ ...readJacocoAntrunCandidate(), commitSha: 'a'.repeat(40) });
  assert.equal(admit(e2).commitSha, e2.commitSha);
  const scan = syntheticJacocoAntrunOsv(e2, {});
  verifyOsvCoverage(scan.coverage, scan.findings, e2, { head: e2.commitSha, graphDigest: JACOCO_ANTRUN_GRAPH });
  assert.equal(scan.findings.length, 1); assert.equal(scan.findings[0].package.version, '3.6.1');
  assert.throws(() => verifyOsvCoverage(scan.coverage, [], e2, { head: e2.commitSha, graphDigest: JACOCO_ANTRUN_GRAPH }));
  const altered = structuredClone(e2); altered.toolchain = { forged: true };
  assert.throws(() => admit(seal(altered)), /source\/Actions\/toolchain drift/);
});
