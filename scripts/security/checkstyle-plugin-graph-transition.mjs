import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { acceptedE2GraphProjection } from './m6-pr-e-e2-generate-sbom.mjs';
import { COMPILER_PLUGIN_GRAPH, readCompilerPluginReport } from './compiler-plugin-graph-transition.mjs';
import { readPreservedCompilerSource } from './checkstyle-plugin-source-continuation.mjs';
import { osvInputFromE2 } from './osv-scan-coverage.mjs';

// Fresh retained capture bytes seal these identities; archival evidence stays immutable.
export const CHECKSTYLE_PLUGIN_GRAPH = '7a6b917477f2a9b4e1fea9eac40ae24a09af59cf93fe59737a08e3c8ff6868b0';
export const CHECKSTYLE_PLUGIN_MANIFEST_SHA256 = 'a8b949328a79590b69de4e1aa5e88590432aeb63907d28178c4e6a792e457d2a';
const CONTENT = '2c3c6add57fe9f570d77b0725cc50cc25f24de5865bc81d63bf2131da1cda594';
const ACCEPTED_MAIN = '4d685762d2db10497abe7668d1009b7e1a056796';
const REPOSITORY = 'akaryc1b/approval-platform';
const OWNER = 'org.apache.maven.plugins:maven-checkstyle-plugin:jar:3.6.0';
const OLD = 'org.apache.commons:commons-lang3:jar:3.17.0';
const NEW = 'org.apache.commons:commons-lang3:jar:3.18.0';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const sha = value => createHash('sha256').update(value).digest('hex');
const hash = value => sha(canonical(value));
const same = (a, b) => canonical(a) === canonical(b);
const requireValue = (ok, message) => { if (!ok) throw new Error(message); };
const SHA256 = /^[0-9a-f]{64}$/;
const bytes = (path, expected) => {
  requireValue(SHA256.test(expected || ''), 'Checkstyle evidence pin is unsealed');
  const raw = readFileSync(new URL(`../../${path}`, import.meta.url));
  requireValue(sha(raw) === expected, `Checkstyle byte digest mismatch: ${path}`); return raw;
};
const pinned = ref => JSON.parse(bytes(ref.path, ref.rawSha256));
function content(value, expected, label) {
  const { contentSha256, ...payload } = value;
  requireValue(SHA256.test(expected || '') && contentSha256 === expected && hash(payload) === expected,
    `Checkstyle ${label} content digest mismatch`);
}
function validate(manifest) {
  requireValue([CHECKSTYLE_PLUGIN_GRAPH, CHECKSTYLE_PLUGIN_MANIFEST_SHA256, CONTENT].every(v => SHA256.test(v || '')),
    'Checkstyle fresh capture evidence is unsealed');
  content(manifest, CONTENT, 'manifest');
  requireValue(manifest.schemaVersion === 'APPROVAL_CHECKSTYLE_PLUGIN_GRAPH_TRANSITION_V1'
    && manifest.repository === REPOSITORY && manifest.base.sourceHead === ACCEPTED_MAIN
    && manifest.base.sourceTree === 'f08f7160f69b93b29c881ee6b5f340611444d6dc'
    && manifest.base.graphDigest === COMPILER_PLUGIN_GRAPH && manifest.observed.graphDigest === CHECKSTYLE_PLUGIN_GRAPH
    && manifest.ownerCount === 17 && manifest.unchangedOtherPluginOwnerCount === 16
    && manifest.runtimeComponentChangeCount === 0 && manifest.runtimeEdgeChangeCount === 0
    && manifest.importedBomChangeCount === 0 && manifest.scopeChangeCount === 0 && manifest.licenseChangeCount === 0
    && manifest.releaseBlocked === true && manifest.findingReviewRequired === true
    && same(manifest.suppressions, []) && same(manifest.exceptions, [])
    && manifest.capture.sourceBaseHead === ACCEPTED_MAIN && manifest.capture.sourceBaseTree === manifest.base.sourceTree
    && manifest.capture.exactHeadEvidence === false && manifest.capture.sourceWorktreeClean === false,
  'Checkstyle transition identity mismatch');
}

/** Strict owner evidence parser; repeated modules must retain identical inventories. */
function owners(raw) {
  const result = {}, occurrences = {}; let owner, coordinates;
  const finish = () => {
    if (!owner) return;
    const sorted = [...coordinates].sort();
    requireValue(sorted.includes(owner) && new Set(sorted).size === sorted.length, 'Checkstyle report invalid owner membership');
    requireValue(!result[owner] || same(result[owner], sorted), 'Checkstyle repeated owner drift');
    result[owner] = sorted; occurrences[owner] = (occurrences[owner] || 0) + 1;
  };
  for (const line of raw.split(/\r?\n/)) {
    if (!line.trim() || line === 'The following plugins have been resolved:') continue;
    const match = line.match(/^( {3}| {6})([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+){1,2})$/);
    requireValue(match, 'Checkstyle report unrecognized line');
    if (match[1].length === 3) { finish(); owner = match[2]; coordinates = []; }
    else { requireValue(owner, 'Checkstyle report dependency without owner'); coordinates.push(match[2]); }
  }
  finish(); return { owners: result, occurrences };
}

/** Pure comparison for mutation fixtures; it is not production evidence admission. */
export function verifyCheckstyleOwnerSubstitution(beforeRaw, afterRaw) {
  const before = owners(beforeRaw), after = owners(afterRaw), names = Object.keys(before.owners).sort();
  requireValue(names.length === 17 && same(names, Object.keys(after.owners).sort())
    && same(before.occurrences, after.occurrences), 'Checkstyle owner membership/count drift');
  const changed = names.filter(name => !same(before.owners[name], after.owners[name]));
  requireValue(same(changed, [OWNER]) && before.owners[OWNER].includes(OLD) && !before.owners[OWNER].includes(NEW)
    && same(after.owners[OWNER], before.owners[OWNER].map(value => value === OLD ? NEW : value).sort()),
  'Checkstyle unrelated owner or dependency changed');
  return { before, after, ownerCount: 17, unchangedOtherPluginOwnerCount: 16,
    changes: [{ owner: OWNER, before: before.owners[OWNER], after: after.owners[OWNER] }] };
}

/** Reverse only the owner-derived union delta and report hash, preserving the full graph. */
export function verifyCheckstyleGraphSubstitution(projection, baseline, beforeRaw, afterRaw) {
  const result = verifyCheckstyleOwnerSubstitution(beforeRaw, afterRaw);
  const union = report => [...new Set(Object.values(report.owners).flat())].sort();
  const priorUnion = union(result.before), currentUnion = union(result.after);
  requireValue(same(baseline.maven.resolvedPluginCoordinates, priorUnion)
    && same(projection.maven.resolvedPluginCoordinates, currentUnion)
    && baseline.maven.pluginResolutionSha256 === sha(beforeRaw)
    && projection.maven.pluginResolutionSha256 === sha(afterRaw), 'Checkstyle global owner union/report mismatch');
  const previous = structuredClone(projection);
  // Calculate only the precise set differences from the already validated owner
  // substitution. A version still used by another owner must stay in the union.
  const added = currentUnion.filter(v => !priorUnion.includes(v));
  const removed = priorUnion.filter(v => !currentUnion.includes(v));
  previous.maven.resolvedPluginCoordinates = previous.maven.resolvedPluginCoordinates.filter(v => !added.includes(v));
  previous.maven.resolvedPluginCoordinates.push(...removed); previous.maven.resolvedPluginCoordinates.sort();
  previous.maven.pluginResolutionSha256 = sha(beforeRaw);
  requireValue(same(previous, baseline), 'undeclared Checkstyle graph change');
  return previous;
}

/** Receipt flags alone are not admission; the caller also verifies byte/source pins. */
export function verifyCheckstyleCaptureProvenance(execution) {
  requireValue(execution.status === 'PASSED_CAPTURE_ONLY' && execution.canonicalCiEvidence === false
    && execution.exactHeadEvidence === false && execution.sourceDirty === true
    && execution.captureInputsStable === true && execution.runtimeInputsStable === true
    && execution.cacheInputsStable === true, 'Checkstyle capture provenance/stability flags mismatch');
}

export function readCheckstylePluginSourceWitness(manifest = readCheckstylePluginManifest()) {
  validate(manifest); const witness = pinned(manifest.capture.sourceWitness);
  content(witness, manifest.capture.sourceWitness.contentSha256, 'source witness');
  const execution = pinned(witness.captureExecution);
  verifyCheckstyleCaptureProvenance(execution);
  requireValue(witness.schemaVersion === 'APPROVAL_CHECKSTYLE_PLUGIN_SOURCE_WITNESS_V1'
    && witness.sourceBaseHead === ACCEPTED_MAIN && witness.sourceBaseTree === manifest.base.sourceTree
    && execution.sourceHead === witness.sourceBaseHead && execution.sourceTree === witness.sourceBaseTree
    && witness.sourcePomFiles.length === 28 && new Set(witness.sourcePomFiles.map(row => row.path)).size === 28
    && same(witness.rootPom, manifest.capture.sourcePom) && witness.exactHeadEvidence === false
    && witness.compatibilityAcceptanceClaimed === false && witness.scannerDispositionClaimed === false
    && witness.reviewerAcceptanceClaimed === false && witness.releaseBlocked === true,
  'Checkstyle source/capture provenance mismatch');
  bytes(witness.collector.path, witness.collector.rawSha256);
  requireValue(witness.collector.rawSha256 === execution.collectorSha256, 'Checkstyle collector witness mismatch');
  bytes(witness.rootPom.path, witness.rootPom.rawSha256); bytes(witness.baselineRootPom.path, witness.baselineRootPom.rawSha256);
  bytes(witness.rules.path, witness.rules.rawSha256);
  requireValue(witness.rules.path === 'config/checkstyle/checkstyle.xml'
    && witness.rules.rawSha256 === 'da9bd87aaf229d4cfd25217910fe806785d3631ed3383748582f5d58ddf870c0'
    && execution.e2Sha256 === manifest.capture.e2.rawSha256 && execution.ownerReportSha256 === manifest.capture.pluginReport.rawSha256,
  'Checkstyle captured report/E2/rules witness mismatch');
  for (const [path, expected] of Object.entries(witness.generatorSources)) {
    requireValue(execution.captureInputs[path] === expected, 'Checkstyle generator capture hash mismatch'); bytes(path, expected);
  }
  const historical = JSON.parse(bytes('docs/operations/compiler-plugin-evidence/source-witness.json',
    '24fef7f9bccdc52e59d9f22c0048e1d4a306a1e07c79d0294c271190538b125a'));
  requireValue(same(witness.generatorSources, historical.generatorSources), 'Checkstyle full unchanged generator closure mismatch');
  requireValue(same(witness.sourcePomFiles.map(row => row.path).sort(), historical.sourcePomFiles.map(row => row.path).sort()),
    'Checkstyle source POM membership drift');
  const changed = [];
  for (const row of witness.sourcePomFiles) {
    bytes(row.path, row.candidateSha256);
    requireValue(execution.sourcePomHashes[row.path] === row.candidateSha256
      && historical.sourcePomFiles.find(prior => prior.path === row.path).candidateSha256 === row.baselineSha256,
    'Checkstyle source POM capture/baseline mismatch');
    if (row.candidateSha256 !== row.baselineSha256) changed.push(row.path);
  }
  const rootRow = witness.sourcePomFiles.find(row => row.path === 'pom.xml');
  requireValue(same(changed, ['pom.xml']) && rootRow.candidateSha256 === witness.rootPom.rawSha256
    && rootRow.baselineSha256 === witness.baselineRootPom.rawSha256
    && sha(readPreservedCompilerSource('pom.xml', witness.baselineRootPom.rawSha256))
    === witness.baselineRootPom.rawSha256, 'Checkstyle exact source reversal mismatch');
  const modelCapture = witness.effectiveModelCapture, models = pinned(modelCapture.verification);
  requireValue(models.status === 'PASSED' && models.stage === 'models' && models.offline === true
    && models.modelCount === 26 && models.canonicalCiEvidence === false && models.scannerEvidence === false
    && modelCapture.modelCount === 26 && modelCapture.orderSource === 'ACTUAL_MAVEN_EMITTED_MODELS'
    && modelCapture.modelOrder.length === 26 && new Set(modelCapture.modelOrder).size === 26
    && models.effectiveModelSha256 === modelCapture.effectiveModelSha256
    && models.baselineEffectiveModelSha256 === modelCapture.baselineEffectiveModelSha256
    && models.sourceHashes['pom.xml'] === witness.rootPom.rawSha256
    && models.baselineSourceHashes['pom.xml'] === witness.baselineRootPom.rawSha256,
  'Checkstyle paired actual model provenance mismatch');
  const captures = models.commands.filter(row => ['baseline-effective-models', 'candidate-effective-models'].includes(row.case));
  requireValue(captures.length === 2 && captures.every(row => row.exitStatus === 0 && row.captureComplete === true),
    'Checkstyle paired actual model command mismatch');
  for (const [path, expected] of Object.entries(models.contractSourceHashes)) bytes(path, expected);
  return witness;
}

export function readCheckstylePluginBaseline(manifest = readCheckstylePluginManifest()) {
  validate(manifest); const e2 = pinned(manifest.base.e2);
  content(e2, manifest.base.e2.contentSha256, 'baseline');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === ACCEPTED_MAIN
    && hash(acceptedE2GraphProjection(e2)) === COMPILER_PLUGIN_GRAPH
    && e2.maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.from, 'Checkstyle accepted main baseline mismatch');
  return e2;
}
export function readCheckstylePluginCandidate(manifest = readCheckstylePluginManifest()) {
  validate(manifest); const e2 = pinned(manifest.capture.e2);
  content(e2, manifest.capture.e2.contentSha256, 'candidate');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === ACCEPTED_MAIN
    && hash(acceptedE2GraphProjection(e2)) === CHECKSTYLE_PLUGIN_GRAPH
    && e2.maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.to, 'Checkstyle candidate mismatch'); return e2;
}
export function readCheckstylePluginReport(manifest = readCheckstylePluginManifest()) {
  validate(manifest); return bytes(manifest.capture.pluginReport.path, manifest.capture.pluginReport.rawSha256).toString('utf8');
}
export function readCheckstylePluginPreservedCompilerLineage(manifest = readCheckstylePluginManifest()) {
  validate(manifest); const lineage = pinned(manifest.base.compilerLineage);
  content(lineage, manifest.base.compilerLineage.contentSha256, 'preserved Compiler lineage');
  requireValue(lineage.schemaVersion === 'APPROVAL_COMPILER_PLUGIN_GRAPH_LINEAGE_V1' && lineage.repository === REPOSITORY
    && lineage.commitSha === ACCEPTED_MAIN && lineage.sourceE2ContentSha256 === manifest.base.e2.contentSha256
    && lineage.currentE2GraphDigest === COMPILER_PLUGIN_GRAPH, 'Checkstyle preserved accepted main Compiler identity mismatch');
  return lineage;
}
export function verifyCheckstylePluginOwnerDelta(beforeRaw, afterRaw, manifest = readCheckstylePluginManifest()) {
  validate(manifest); const result = verifyCheckstyleOwnerSubstitution(beforeRaw, afterRaw);
  const beforeUnion = [...new Set(Object.values(result.before.owners).flat())].sort();
  const afterUnion = [...new Set(Object.values(result.after.owners).flat())].sort();
  const added = afterUnion.filter(v => !beforeUnion.includes(v)), removed = beforeUnion.filter(v => !afterUnion.includes(v));
  requireValue(same(manifest.pluginOwnerChanges, result.changes)
    && hash(result.before.owners) === manifest.previousOwnerInventorySha256
    && hash(result.after.owners) === manifest.currentOwnerInventorySha256
    && same(manifest.addedPluginCoordinates, added) && manifest.addedPluginCoordinateCount === added.length
    && same(manifest.removedPluginCoordinates, removed) && manifest.removedPluginCoordinateCount === removed.length,
  'Checkstyle owner manifest mismatch');
  verifyCheckstyleGraphSubstitution(acceptedE2GraphProjection(readCheckstylePluginCandidate(manifest)),
    acceptedE2GraphProjection(readCheckstylePluginBaseline(manifest)), beforeRaw, afterRaw);
  return { ownerCount: result.ownerCount, unchangedOtherPluginOwnerCount: result.unchangedOtherPluginOwnerCount, changes: result.changes };
}
export function readCheckstylePluginManifest() {
  const manifest = pinned({ path: 'docs/operations/checkstyle-plugin-transition.json', rawSha256: CHECKSTYLE_PLUGIN_MANIFEST_SHA256 });
  validate(manifest); readCheckstylePluginSourceWitness(manifest); readCheckstylePluginBaseline(manifest);
  readCheckstylePluginCandidate(manifest); readCheckstylePluginPreservedCompilerLineage(manifest);
  verifyCheckstylePluginOwnerDelta(readCompilerPluginReport(), readCheckstylePluginReport(manifest), manifest); return manifest;
}
export function verifyCheckstylePluginCurrentEvidence(e2, manifest = readCheckstylePluginManifest()) {
  validate(manifest); const candidate = readCheckstylePluginCandidate(manifest);
  const { commitSha, contentSha256, ...payload } = e2;
  const { commitSha: base, contentSha256: capturedHash, ...captured } = candidate;
  requireValue(/^[0-9a-f]{40}$/.test(commitSha || '') && hash({ ...payload, commitSha }) === contentSha256
    && same(payload, captured), 'Checkstyle current E2 source/Actions/toolchain drift'); readCheckstylePluginSourceWitness(manifest);
}
export function verifyCheckstylePluginDelta(projection, manifest = readCheckstylePluginManifest()) {
  validate(manifest); readCheckstylePluginSourceWitness(manifest);
  verifyCheckstylePluginOwnerDelta(readCompilerPluginReport(), readCheckstylePluginReport(manifest), manifest);
  requireValue(hash(projection) === CHECKSTYLE_PLUGIN_GRAPH, 'current Checkstyle graph drift');
  const previous = verifyCheckstyleGraphSubstitution(projection, acceptedE2GraphProjection(readCheckstylePluginBaseline(manifest)),
    readCompilerPluginReport(), readCheckstylePluginReport(manifest));
  requireValue(hash(previous) === COMPILER_PLUGIN_GRAPH, 'Checkstyle prior Compiler graph drift');
  for (const [name, evidence] of [['prior', previous], ['current', projection]]) {
    const input = osvInputFromE2(evidence);
    requireValue(input.packageCount === manifest.osvInput[name].packageCount
      && sha(JSON.stringify(input.scannerInput)) === manifest.osvInput[name].inputBytesSha256, 'Checkstyle exact OSV input identity drift');
  }
  return previous;
}
