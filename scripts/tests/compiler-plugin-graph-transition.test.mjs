import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { BASE_GRAPH, CLEAN_PLUGIN_GRAPH, COMPILER_PLUGIN_GRAPH, graphHash,
  readCompilerPluginManifest, readCompilerPluginCandidate, readCompilerPluginReport, readCompilerPluginBaseline,
  readCompilerPluginSourceWitness, readCompilerPluginPreservedCleanLineage, verifyCompilerPluginDelta,
  verifyCompilerPluginOwnerDelta, readCleanPluginReport, verifyCleanPluginDelta,
  verifyObservabilityGraph, requirePreservedGraph } from '../security/observability-dependency-graph.mjs';
import { acceptedE2GraphProjection } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { osvInputFromE2, verifyOsvCoverage } from '../security/osv-scan-coverage.mjs';
import { syntheticCompilerPluginOsv } from './fixtures/compiler-plugin-fixture.mjs';

const sha = value => createHash('sha256').update(value).digest('hex');
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: graphHash(payload) }; };
const admit = e2 => verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, e2.commitSha);
test('Compiler reverses only its single IO coordinate removal/report bytes into accepted main 1836', () => {
  const candidate = readCompilerPluginCandidate(), manifest = readCompilerPluginManifest();
  const projection = acceptedE2GraphProjection(candidate), previous = verifyCompilerPluginDelta(projection);
  assert.equal(graphHash(projection), COMPILER_PLUGIN_GRAPH); assert.equal(graphHash(previous), CLEAN_PLUGIN_GRAPH);
  assert.deepEqual(previous, acceptedE2GraphProjection(readCompilerPluginBaseline())); verifyCleanPluginDelta(previous);
  assert.equal(projection.maven.resolvedPluginCoordinates.length, 270);
  assert.equal(previous.maven.resolvedPluginCoordinates.length, 271);
  for (const key of Object.keys(projection.maven)) if (!['resolvedPluginCoordinates', 'pluginResolutionSha256'].includes(key))
    assert.deepEqual(previous.maven[key], projection.maven[key]);
  for (const [name, e2] of [['prior', previous], ['current', projection]]) {
    const input = osvInputFromE2(e2); assert.equal(input.packageCount, manifest.osvInput[name].packageCount);
    assert.equal(sha(JSON.stringify(input.scannerInput)), manifest.osvInput[name].inputBytesSha256);
  }
  assert.equal(manifest.osvInput.prior.packageCount, 474); assert.equal(manifest.osvInput.current.packageCount, 473);
});
test('Compiler binds actual source/capture provenance and every unchanged owner to retained evidence', () => {
  const witness = readCompilerPluginSourceWitness(), manifest = readCompilerPluginManifest();
  assert.equal(witness.sourcePomFiles.length, 28); assert.equal(witness.captureInputsStable, true);
  assert.equal(Object.keys(witness.generatorSources).length, 4);
  assert.equal(witness.exactHeadEvidence, false); assert.equal(witness.scannerDispositionClaimed, false);
  assert.equal(witness.compatibilityAcceptanceClaimed, false);
  const owners = verifyCompilerPluginOwnerDelta(readCleanPluginReport(), readCompilerPluginReport());
  assert.equal(owners.ownerCount, 17); assert.equal(owners.unchangedOtherPluginOwnerCount, 16);
  assert.deepEqual(owners.changes, manifest.pluginOwnerChanges);
});
test('Compiler preserves exact natural-main Clean/Release receipts and all older historical decisions', () => {
  const e2 = readCompilerPluginCandidate(), lineage = admit(e2);
  assert.equal(lineage.schemaVersion, 'APPROVAL_COMPILER_PLUGIN_GRAPH_LINEAGE_V1');
  assert.deepEqual(lineage.preservedCleanPluginLineage, readCompilerPluginPreservedCleanLineage());
  assert.equal(lineage.preservedCleanPluginLineage.commitSha, '0c55ff5e7d974022e5c76d9a88a4312d2af81827');
  assert.equal(lineage.releaseBlocked, true); assert.equal(lineage.findingReviewRequired, true);
  const envelope = { repository: e2.repository, commitSha: e2.commitSha, e2GraphDigest: COMPILER_PLUGIN_GRAPH,
    e2CurrentContentSha256: e2.contentSha256, e2GraphTransition: lineage };
  assert.deepEqual(requirePreservedGraph(envelope, BASE_GRAPH, e2.commitSha), lineage);
  assert.throws(() => requirePreservedGraph(envelope, BASE_GRAPH), /verified head/);
  const tampered = structuredClone(envelope);
  tampered.e2GraphTransition.preservedCleanPluginLineage.commitSha = 'f'.repeat(40);
  tampered.e2GraphTransition.preservedCleanPluginLineage = seal(tampered.e2GraphTransition.preservedCleanPluginLineage);
  tampered.e2GraphTransition = seal(tampered.e2GraphTransition);
  assert.throws(() => requirePreservedGraph(tampered, BASE_GRAPH, e2.commitSha), /lineage mismatch/);
});
for (const [name, mutate] of [
  ['runtime component', e => { e.maven.components[0].version = 'forged'; }],
  ['edge', e => { e.maven.edges.pop(); }], ['BOM order', e => { e.maven.importedBoms.reverse(); }],
  ['scope', e => { e.maven.components[0].scope = 'forged'; }], ['license', e => { e.maven.components[0].licenses = []; }],
  ['report hash', e => { e.maven.pluginResolutionSha256 = '0'.repeat(64); }],
  ['old IO', e => { e.maven.resolvedPluginCoordinates.push('commons-io:commons-io:jar:2.11.0'); }],
  ['duplicate coordinate', e => { e.maven.resolvedPluginCoordinates.push(e.maven.resolvedPluginCoordinates[0]); }],
  ['current Actions omitted from projection', e => { e.githubActions.workflows[0].blobSha = 'f'.repeat(40); }],
  ['toolchain', e => { e.toolchain.extra = true; }], ['unknown E2 field', e => { e.extra = true; }],
]) test(`Compiler rejects rehashed ${name}`, () => {
  const e2 = readCompilerPluginCandidate(); mutate(e2); assert.throws(() => admit(seal(e2)));
});
for (const [name, mutate] of [
  ['other owner, equal global union', raw => raw.replace('   org.apache.maven.plugins:maven-clean-plugin:jar:3.2.0\n      org.apache.maven.plugins:maven-clean-plugin:jar:3.2.0\n      commons-io:commons-io:jar:2.20.0',
    '   org.apache.maven.plugins:maven-clean-plugin:jar:3.2.0\n      org.apache.maven.plugins:maven-clean-plugin:jar:3.2.0\n      commons-io:commons-io:jar:2.21.0')],
  ['coordinate/report drift', raw => raw.replace('commons-io:commons-io:jar:2.20.0', 'commons-io:commons-io:jar:2.11.0')],
  ['extra line', raw => raw + '\nunknown\n'], ['extra whitespace', raw => raw + '\n'],
]) test(`Compiler owner verifier rejects ${name}`, () => {
  const original = readCompilerPluginReport(), changed = mutate(original); assert.notEqual(changed, original);
  assert.throws(() => verifyCompilerPluginOwnerDelta(readCleanPluginReport(), changed));
});
for (const field of [...Object.keys(readCompilerPluginManifest()).filter(k => k !== 'contentSha256'), 'unknown'])
  test(`Compiler refuses rehashed manifest ${field}`, () => {
    const manifest = readCompilerPluginManifest(); manifest[field] = 'forged';
    assert.throws(() => verifyCompilerPluginDelta(acceptedE2GraphProjection(readCompilerPluginCandidate()), seal(manifest)), /content digest mismatch/);
  });
test('synthetic coverage includes all 473 candidate targets and retains a new unresolved advisory', () => {
  const e2 = readCompilerPluginCandidate(), scan = syntheticCompilerPluginOsv(e2, {});
  verifyOsvCoverage(scan.coverage, scan.findings, e2, { head: e2.commitSha, graphDigest: COMPILER_PLUGIN_GRAPH });
  assert.equal(scan.coverage.inputPackageCount, 473); assert.equal(scan.coverage.reportedPackageCount, 473);
  assert.equal(scan.findings.length, 1);
  const coverage = structuredClone(scan.coverage); coverage.targets.pop();
  assert.throws(() => verifyOsvCoverage(seal(coverage), scan.findings, e2, { head: e2.commitSha, graphDigest: COMPILER_PLUGIN_GRAPH }));
});
