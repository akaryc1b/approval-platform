import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { acceptedE2GraphProjection } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { CHECKSTYLE_PLUGIN_GRAPH, readCheckstylePluginManifest, verifyCheckstyleOwnerSubstitution,
  verifyCheckstyleGraphSubstitution, verifyCheckstyleCaptureProvenance, readCheckstylePluginCandidate,
  readCheckstylePluginBaseline, readCheckstylePluginSourceWitness, readCheckstylePluginReport,
  readCheckstylePluginPreservedCompilerLineage, verifyCheckstylePluginDelta }
  from '../security/checkstyle-plugin-graph-transition.mjs';
import { COMPILER_PLUGIN_GRAPH, graphHash, readCompilerPluginCandidate, readCompilerPluginReport,
  readCompilerPluginSourceWitness, BASE_GRAPH, verifyObservabilityGraph, requirePreservedGraph }
  from '../security/observability-dependency-graph.mjs';
import { readPreservedCompilerSource } from '../security/checkstyle-plugin-source-continuation.mjs';
import { osvInputFromE2, verifyOsvCoverage } from '../security/osv-scan-coverage.mjs';
import { syntheticCheckstylePluginOsv } from './fixtures/checkstyle-plugin-fixture.mjs';

const sha = value => createHash('sha256').update(value).digest('hex');
const OWNER = 'org.apache.maven.plugins:maven-checkstyle-plugin:jar:3.6.0';
const OLD = 'org.apache.commons:commons-lang3:jar:3.17.0', NEW = 'org.apache.commons:commons-lang3:jar:3.18.0';
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: graphHash(payload) }; };
const admit = e2 => verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, e2.commitSha);
// Synthetic owner mutations stay separate from the actual sealed capture below.
function fixture() {
  const beforeRaw = readCompilerPluginReport(); let selected = false, changes = 0;
  const afterRaw = beforeRaw.split('\n').map(line => {
    if (/^   \S/.test(line)) selected = line.trim() === OWNER;
    if (selected && line.trim() === OLD) { changes++; return line.replace(OLD, NEW); } return line;
  }).join('\n');
  assert.ok(changes > 0);
  const before = acceptedE2GraphProjection(readCompilerPluginCandidate());
  const after = structuredClone(before);
  after.maven.resolvedPluginCoordinates = [...new Set(afterRaw.split('\n').filter(line => /^ {3,6}\S/.test(line)).map(line => line.trim()))].sort();
  after.maven.pluginResolutionSha256 = sha(afterRaw);
  return { beforeRaw, afterRaw, before, after };
}
test('Checkstyle fixtures reverse only the exact sole-owner Lang substitution and full graph stays intact', () => {
  const f = fixture();
  const owners = verifyCheckstyleOwnerSubstitution(f.beforeRaw, f.afterRaw);
  assert.equal(owners.ownerCount, 17); assert.equal(owners.unchangedOtherPluginOwnerCount, 16);
  assert.deepEqual(owners.changes.map(row => row.owner), [OWNER]);
  assert.deepEqual(verifyCheckstyleGraphSubstitution(f.after, f.before, f.beforeRaw, f.afterRaw), f.before);
  assert.equal(graphHash(f.before), COMPILER_PLUGIN_GRAPH);
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
]) test(`Checkstyle graph fixture rejects ${name}`, () => {
  const f = fixture(); alter(f.after);
  assert.throws(() => verifyCheckstyleGraphSubstitution(f.after, f.before, f.beforeRaw, f.afterRaw));
});
for (const [name, alter] of [
  ['no substitution', f => f.beforeRaw],
  ['unrelated owner with unchanged global union', f => f.afterRaw.replace(/^(   \S+\n)/m, `$1      ${NEW}\n`)],
  ['duplicate owner occurrence', f => f.afterRaw + `\n   ${OWNER}\n      ${OWNER}\n      ${NEW}\n`],
  ['extra Checkstyle dependency', f => f.afterRaw.replace(`   ${OWNER}\n`, `   ${OWNER}\n      foreign:dependency:jar:1\n`)],
  ['foreign line', f => f.afterRaw + '\nforged report\n'],
]) test(`Checkstyle owner fixtures reject ${name}`, () => {
  const f = fixture(), changed = alter(f); assert.notEqual(changed, f.afterRaw);
  assert.throws(() => verifyCheckstyleOwnerSubstitution(f.beforeRaw, changed));
});
test('historical Compiler source and graph remain valid through the exact Checkstyle source adapter', () => {
  const expected = readFileSync(new URL('../../docs/operations/compiler-plugin-evidence/candidate-root-pom.xml', import.meta.url));
  assert.deepEqual(readPreservedCompilerSource('pom.xml', sha(expected)), expected);
  assert.equal(readCompilerPluginSourceWitness().sourcePomFiles.length, 28);
  const candidate = readCompilerPluginCandidate();
  assert.equal(verifyObservabilityGraph(candidate, acceptedE2GraphProjection(candidate), BASE_GRAPH, candidate.commitSha).currentE2GraphDigest, COMPILER_PLUGIN_GRAPH);
});
test('Checkstyle actual capture reverses exactly to accepted main 1838 and retains all 16 other owners', () => {
  const manifest = readCheckstylePluginManifest(), candidate = readCheckstylePluginCandidate();
  const projection = acceptedE2GraphProjection(candidate), previous = verifyCheckstylePluginDelta(projection);
  assert.equal(graphHash(projection), CHECKSTYLE_PLUGIN_GRAPH);
  assert.equal(graphHash(previous), COMPILER_PLUGIN_GRAPH);
  assert.deepEqual(previous, acceptedE2GraphProjection(readCheckstylePluginBaseline()));
  const owners = verifyCheckstyleOwnerSubstitution(readCompilerPluginReport(), readCheckstylePluginReport());
  assert.deepEqual(owners.changes, manifest.pluginOwnerChanges);
  assert.equal(owners.ownerCount, 17); assert.equal(owners.unchangedOtherPluginOwnerCount, 16);
  assert.deepEqual(manifest.addedPluginCoordinates, []); assert.deepEqual(manifest.removedPluginCoordinates, [OLD]);
  assert.equal(projection.maven.resolvedPluginCoordinates.length, 269);
  assert.equal(previous.maven.resolvedPluginCoordinates.length, 270);
  for (const [name, evidence] of [['prior', previous], ['current', projection]]) {
    const input = osvInputFromE2(evidence);
    assert.equal(input.packageCount, manifest.osvInput[name].packageCount);
    assert.equal(sha(JSON.stringify(input.scannerInput)), manifest.osvInput[name].inputBytesSha256);
  }
  assert.equal(manifest.osvInput.prior.packageCount, 473); assert.equal(manifest.osvInput.current.packageCount, 472);
});
test('Checkstyle source witness binds the actual paired 26-model capture and source stability', () => {
  const witness = readCheckstylePluginSourceWitness();
  assert.equal(witness.sourcePomFiles.length, 28); assert.equal(Object.keys(witness.generatorSources).length, 4);
  assert.equal(witness.exactHeadEvidence, false); assert.equal(witness.captureInputsStable, true);
  assert.equal(witness.effectiveModelCapture.modelCount, 26);
  assert.equal(witness.effectiveModelCapture.orderSource, 'ACTUAL_MAVEN_EMITTED_MODELS');
  assert.equal(witness.effectiveModelCapture.modelOrder.length, 26);
  assert.equal(witness.effectiveModelCapture.rawModelsRetainedInExecutionEvidence, true);
  const execution = JSON.parse(readFileSync(new URL(`../../${witness.captureExecution.path}`, import.meta.url)));
  verifyCheckstyleCaptureProvenance(execution);
  assert.equal(witness.scannerDispositionClaimed, false); assert.equal(witness.releaseBlocked, true);
});
for (const field of ['canonicalCiEvidence', 'exactHeadEvidence', 'sourceDirty', 'captureInputsStable',
  'runtimeInputsStable', 'cacheInputsStable', 'status']) test(`Checkstyle capture rejects changed ${field}`, () => {
  const witness = readCheckstylePluginSourceWitness();
  const execution = JSON.parse(readFileSync(new URL(`../../${witness.captureExecution.path}`, import.meta.url)));
  verifyCheckstyleCaptureProvenance(execution);
  execution[field] = typeof execution[field] === 'boolean' ? !execution[field] : 'FAILED';
  assert.throws(() => verifyCheckstyleCaptureProvenance(execution), /capture provenance\/stability flags mismatch/);
});
test('Checkstyle preserves the exact accepted-main Compiler receipt and all historical gates', () => {
  const e2 = readCheckstylePluginCandidate(), lineage = admit(e2);
  assert.equal(lineage.schemaVersion, 'APPROVAL_CHECKSTYLE_PLUGIN_GRAPH_LINEAGE_V1');
  assert.deepEqual(lineage.preservedCompilerPluginLineage, readCheckstylePluginPreservedCompilerLineage());
  assert.equal(lineage.preservedCompilerPluginLineage.commitSha, '4d685762d2db10497abe7668d1009b7e1a056796');
  assert.equal(lineage.releaseBlocked, true); assert.equal(lineage.findingReviewRequired, true);
  const envelope = { repository: e2.repository, commitSha: e2.commitSha, e2GraphDigest: CHECKSTYLE_PLUGIN_GRAPH,
    e2CurrentContentSha256: e2.contentSha256, e2GraphTransition: lineage };
  assert.deepEqual(requirePreservedGraph(envelope, BASE_GRAPH, e2.commitSha), lineage);
  assert.throws(() => requirePreservedGraph(envelope, BASE_GRAPH), /verified head/);
  const changed = structuredClone(envelope);
  changed.e2GraphTransition.preservedCompilerPluginLineage.commitSha = 'f'.repeat(40);
  changed.e2GraphTransition.preservedCompilerPluginLineage = seal(changed.e2GraphTransition.preservedCompilerPluginLineage);
  changed.e2GraphTransition = seal(changed.e2GraphTransition);
  assert.throws(() => requirePreservedGraph(changed, BASE_GRAPH, e2.commitSha), /lineage mismatch/);
});
for (const [name, alter] of [
  ['runtime component', e => { e.maven.components[0].version = 'forged'; }],
  ['edge', e => { e.maven.edges.pop(); }], ['BOM order', e => { e.maven.importedBoms.reverse(); }],
  ['scope', e => { e.maven.components[0].scope = 'forged'; }], ['license', e => { e.maven.components[0].licenses = []; }],
  ['report hash', e => { e.maven.pluginResolutionSha256 = '0'.repeat(64); }],
  ['old Lang', e => { e.maven.resolvedPluginCoordinates.push(OLD); }],
  ['duplicate coordinate', e => { e.maven.resolvedPluginCoordinates.push(e.maven.resolvedPluginCoordinates[0]); }],
  ['current Actions omitted from projection', e => { e.githubActions.workflows[0].blobSha = 'f'.repeat(40); }],
  ['toolchain', e => { e.toolchain.extra = true; }], ['unknown E2 field', e => { e.extra = true; }],
]) test(`Checkstyle admission rejects rehashed ${name}`, () => {
  const e2 = readCheckstylePluginCandidate(); admit(e2); alter(e2);
  assert.throws(() => admit(seal(e2)), /Checkstyle|preserve|graph/);
});
test('synthetic coverage binds all 472 current targets and retains a new unresolved advisory', () => {
  const e2 = readCheckstylePluginCandidate(), scan = syntheticCheckstylePluginOsv(e2, {});
  verifyOsvCoverage(scan.coverage, scan.findings, e2, { head: e2.commitSha, graphDigest: CHECKSTYLE_PLUGIN_GRAPH });
  assert.equal(scan.coverage.inputPackageCount, 472); assert.equal(scan.coverage.reportedPackageCount, 472);
  assert.equal(scan.findings.length, 1);
  assert.deepEqual(scan.findings[0].package, { name: 'org.apache.commons:commons-lang3', version: '3.18.0', ecosystem: 'Maven' });
  const coverage = structuredClone(scan.coverage); coverage.targets.pop();
  assert.throws(() => verifyOsvCoverage(seal(coverage), scan.findings, e2,
    { head: e2.commitSha, graphDigest: CHECKSTYLE_PLUGIN_GRAPH }));
});
