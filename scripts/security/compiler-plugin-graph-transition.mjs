import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { acceptedE2GraphProjection } from './m6-pr-e-e2-generate-sbom.mjs';
import { CLEAN_PLUGIN_GRAPH, readCleanPluginReport } from './clean-plugin-graph-transition.mjs';
import { readPreservedCleanSource } from './compiler-plugin-source-continuation.mjs';
import { osvInputFromE2 } from './osv-scan-coverage.mjs';

export const COMPILER_PLUGIN_GRAPH = '92f6b10a9fd199bbea69ce3a1bc743b77b00990b1e7ec4b1ca695407da7c91fe';
export const COMPILER_PLUGIN_MANIFEST_SHA256 = '30271a5c58a1a50cdaa9d044d59f5caf5cb4e79fd9cca3921c4b7b9127d8a109';
const CONTENT = 'f6804110241d72bec82e88171a068047f08e4d640514a7ca0308f9d8e36f6f8c';
const OWNER = 'org.apache.maven.plugins:maven-compiler-plugin:jar:3.14.0';
const OLD = 'commons-io:commons-io:jar:2.11.0', NEW = 'commons-io:commons-io:jar:2.20.0';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const sha = value => createHash('sha256').update(value).digest('hex');
const hash = value => sha(canonical(value));
const same = (a, b) => canonical(a) === canonical(b);
const requireValue = (ok, message) => { if (!ok) throw new Error(message); };
const bytes = (path, expected) => {
  const raw = readFileSync(new URL(`../../${path}`, import.meta.url));
  requireValue(sha(raw) === expected, `Compiler byte digest mismatch: ${path}`); return raw;
};
const pinned = ref => JSON.parse(bytes(ref.path, ref.rawSha256));
function content(value, expected, label) {
  const { contentSha256, ...payload } = value;
  requireValue(contentSha256 === expected && hash(payload) === expected, `Compiler ${label} content digest mismatch`);
}
function validate(manifest) {
  content(manifest, CONTENT, 'manifest');
  requireValue(manifest.schemaVersion === 'APPROVAL_COMPILER_PLUGIN_GRAPH_TRANSITION_V1'
    && manifest.base.sourceHead === '0c55ff5e7d974022e5c76d9a88a4312d2af81827'
    && manifest.base.sourceTree === '2b378b6654f2bc246df7618b35db63cb9d817948'
    && manifest.base.graphDigest === CLEAN_PLUGIN_GRAPH && manifest.observed.graphDigest === COMPILER_PLUGIN_GRAPH
    && manifest.releaseBlocked === true && manifest.findingReviewRequired === true
    && manifest.capture.exactHeadEvidence === false && manifest.capture.sourceWorktreeClean === false,
  'Compiler transition identity mismatch');
}

export function readCompilerPluginSourceWitness(manifest = readCompilerPluginManifest()) {
  validate(manifest); const witness = pinned(manifest.capture.sourceWitness);
  content(witness, manifest.capture.sourceWitness.contentSha256, 'source witness');
  const execution = pinned(witness.captureExecution);
  requireValue(execution.status === 'PASSED_CAPTURE_ONLY' && execution.canonicalCiEvidence === false
    && execution.sourceDirty === true && execution.captureInputsStable === true
    && execution.sourceHead === witness.sourceBaseHead && execution.sourceTree === witness.sourceBaseTree
    && witness.sourceBaseHead === manifest.base.sourceHead && witness.sourceBaseTree === manifest.base.sourceTree
    && witness.sourcePomFiles.length === 28 && new Set(witness.sourcePomFiles.map(row => row.path)).size === 28
    && same(witness.rootPom, manifest.capture.sourcePom) && witness.exactHeadEvidence === false
    && witness.compatibilityAcceptanceClaimed === false && witness.scannerDispositionClaimed === false
    && witness.reviewerAcceptanceClaimed === false && witness.releaseBlocked === true,
  'Compiler source/capture provenance mismatch');
  bytes(witness.collector.path, witness.collector.rawSha256);
  requireValue(witness.collector.rawSha256 === execution.collectorSha256, 'Compiler collector witness mismatch');
  bytes(witness.rootPom.path, witness.rootPom.rawSha256); bytes(witness.baselineRootPom.path, witness.baselineRootPom.rawSha256);
  requireValue(execution.e2Sha256 === manifest.capture.e2.rawSha256 && execution.ownerReportSha256 === manifest.capture.pluginReport.rawSha256,
    'Compiler captured report/E2 witness mismatch');
  for (const [path, expected] of Object.entries(witness.generatorSources)) {
    requireValue(execution.captureInputs[path] === expected, 'Compiler generator capture hash mismatch'); bytes(path, expected);
  }
  const changed = [];
  for (const row of witness.sourcePomFiles) {
    bytes(row.path, row.candidateSha256);
    requireValue(execution.sourcePomHashes[row.path] === row.candidateSha256, 'Compiler source POM capture mismatch');
    if (row.candidateSha256 !== row.baselineSha256) changed.push(row.path);
  }
  requireValue(same(changed, ['pom.xml']), 'Compiler source changes escaped root POM');
  requireValue(sha(readPreservedCleanSource('pom.xml', witness.baselineRootPom.rawSha256)) === witness.baselineRootPom.rawSha256,
    'Compiler exact source reversal mismatch');
  return witness;
}

export function readCompilerPluginBaseline(manifest = readCompilerPluginManifest()) {
  validate(manifest); const e2 = pinned(manifest.base.e2);
  content(e2, manifest.base.e2.contentSha256, 'baseline');
  requireValue(e2.commitSha === manifest.base.sourceHead && hash(acceptedE2GraphProjection(e2)) === CLEAN_PLUGIN_GRAPH
    && e2.maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.from, 'Compiler baseline mismatch');
  return e2;
}
export function readCompilerPluginCandidate(manifest = readCompilerPluginManifest()) {
  validate(manifest); const e2 = pinned(manifest.capture.e2);
  content(e2, manifest.capture.e2.contentSha256, 'candidate');
  requireValue(e2.commitSha === manifest.capture.sourceBaseHead && hash(acceptedE2GraphProjection(e2)) === COMPILER_PLUGIN_GRAPH
    && e2.maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.to, 'Compiler candidate mismatch'); return e2;
}
export function readCompilerPluginReport(manifest = readCompilerPluginManifest()) {
  validate(manifest); return bytes(manifest.capture.pluginReport.path, manifest.capture.pluginReport.rawSha256).toString('utf8');
}
export function readCompilerPluginPreservedCleanLineage(manifest = readCompilerPluginManifest()) {
  validate(manifest); const lineage = pinned(manifest.base.cleanLineage);
  content(lineage, manifest.base.cleanLineage.contentSha256, 'preserved Clean lineage');
  requireValue(lineage.schemaVersion === 'APPROVAL_CLEAN_PLUGIN_GRAPH_LINEAGE_V1'
    && lineage.commitSha === manifest.base.sourceHead && lineage.sourceE2ContentSha256 === manifest.base.e2.contentSha256
    && lineage.currentE2GraphDigest === CLEAN_PLUGIN_GRAPH, 'Compiler preserved Clean identity mismatch'); return lineage;
}

function owners(raw) {
  const result = {}, occurrences = {}; let owner, coordinates;
  const finish = () => {
    if (!owner) return;
    const sorted = [...coordinates].sort();
    requireValue(sorted.includes(owner) && new Set(sorted).size === sorted.length, 'Compiler report invalid owner membership');
    requireValue(!result[owner] || same(result[owner], sorted), 'Compiler repeated owner drift');
    result[owner] = sorted; occurrences[owner] = (occurrences[owner] || 0) + 1;
  };
  for (const line of raw.split(/\r?\n/)) {
    if (!line.trim() || line === 'The following plugins have been resolved:') continue;
    const match = line.match(/^( {3}| {6})([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+){1,2})$/);
    requireValue(match, 'Compiler report unrecognized line');
    if (match[1].length === 3) { finish(); owner = match[2]; coordinates = []; }
    else { requireValue(owner, 'Compiler report dependency without owner'); coordinates.push(match[2]); }
  }
  finish(); return { owners: result, occurrences };
}
export function verifyCompilerPluginOwnerDelta(beforeRaw, afterRaw, manifest = readCompilerPluginManifest()) {
  validate(manifest); const before = owners(beforeRaw), after = owners(afterRaw), names = Object.keys(before.owners).sort();
  requireValue(names.length === 17 && same(names, Object.keys(after.owners).sort())
    && same(before.occurrences, after.occurrences), 'Compiler owner membership/count drift');
  const changed = names.filter(name => !same(before.owners[name], after.owners[name]));
  requireValue(same(changed, [OWNER]) && before.owners[OWNER].includes(OLD) && !before.owners[OWNER].includes(NEW)
    && same(after.owners[OWNER], before.owners[OWNER].map(value => value === OLD ? NEW : value).sort()),
  'Compiler unrelated owner or dependency changed');
  requireValue(same(manifest.pluginOwnerChanges, [{ owner: OWNER, before: before.owners[OWNER], after: after.owners[OWNER] }])
    && hash(before.owners) === manifest.previousOwnerInventorySha256 && hash(after.owners) === manifest.currentOwnerInventorySha256,
  'Compiler owner manifest mismatch');
  for (const [report, e2] of [[before, readCompilerPluginBaseline(manifest)], [after, readCompilerPluginCandidate(manifest)]])
    requireValue(same([...new Set(Object.values(report.owners).flat())].sort(), e2.maven.resolvedPluginCoordinates), 'Compiler global owner union mismatch');
  requireValue(sha(beforeRaw) === manifest.pluginResolutionHashChange.from && sha(afterRaw) === manifest.pluginResolutionHashChange.to,
    'Compiler owner report digest mismatch');
  return { ownerCount: 17, unchangedOtherPluginOwnerCount: 16, changes: manifest.pluginOwnerChanges };
}
export function readCompilerPluginManifest() {
  const manifest = pinned({ path: 'docs/operations/compiler-plugin-transition.json', rawSha256: COMPILER_PLUGIN_MANIFEST_SHA256 });
  validate(manifest); readCompilerPluginSourceWitness(manifest); readCompilerPluginBaseline(manifest);
  readCompilerPluginCandidate(manifest); readCompilerPluginPreservedCleanLineage(manifest);
  verifyCompilerPluginOwnerDelta(readCleanPluginReport(), readCompilerPluginReport(manifest), manifest); return manifest;
}
export function verifyCompilerPluginCurrentEvidence(e2, manifest = readCompilerPluginManifest()) {
  validate(manifest); const candidate = readCompilerPluginCandidate(manifest);
  const { commitSha, contentSha256, ...payload } = e2;
  const { commitSha: base, contentSha256: capturedHash, ...captured } = candidate;
  requireValue(/^[0-9a-f]{40}$/.test(commitSha || '') && hash({ ...payload, commitSha }) === contentSha256
    && same(payload, captured), 'Compiler current E2 source/Actions/toolchain drift'); readCompilerPluginSourceWitness(manifest);
}
export function verifyCompilerPluginDelta(projection, manifest = readCompilerPluginManifest()) {
  validate(manifest); readCompilerPluginSourceWitness(manifest);
  verifyCompilerPluginOwnerDelta(readCleanPluginReport(), readCompilerPluginReport(manifest), manifest);
  requireValue(hash(projection) === COMPILER_PLUGIN_GRAPH, 'current Compiler graph drift');
  const previous = structuredClone(projection);
  requireValue(!previous.maven.resolvedPluginCoordinates.includes(OLD) && previous.maven.resolvedPluginCoordinates.includes(NEW), 'Compiler IO delta mismatch');
  previous.maven.resolvedPluginCoordinates.push(OLD); previous.maven.resolvedPluginCoordinates.sort();
  previous.maven.pluginResolutionSha256 = manifest.pluginResolutionHashChange.from;
  requireValue(hash(previous) === CLEAN_PLUGIN_GRAPH && same(previous, acceptedE2GraphProjection(readCompilerPluginBaseline(manifest))), 'undeclared Compiler graph change');
  for (const [name, evidence] of [['prior', previous], ['current', projection]]) {
    const input = osvInputFromE2(evidence);
    requireValue(input.packageCount === manifest.osvInput[name].packageCount
      && sha(JSON.stringify(input.scannerInput)) === manifest.osvInput[name].inputBytesSha256, 'Compiler exact OSV input identity drift');
  }
  return previous;
}
