import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { acceptedE2GraphProjection } from './m6-pr-e-e2-generate-sbom.mjs';
import { BOOT_UTILITIES_GRAPH, readBootUtilitiesReport, readBootUtilitiesSourceWitness }
  from './boot-utilities-graph-transition.mjs';
import { parseCheckstylePluginOwners, verifyCheckstyleCaptureProvenance } from './checkstyle-plugin-graph-transition.mjs';
import { readPreservedBootUtilitiesSource } from './jacoco-antrun-source-continuation.mjs';
import { osvInputFromE2 } from './osv-scan-coverage.mjs';

// Identity pins come from the actual retained offline capture; archival evidence stays immutable.
export const JACOCO_ANTRUN_GRAPH = 'a338fa7e74474131fbd72f610f1be26cd7b676a46597aef5b04f5ef87a1dad92';
export const JACOCO_ANTRUN_MANIFEST_SHA256 = '6358651dc32375032f62f8c3f5197ad3849e4d1a1014911dd18ae4ed9993433a';
const CONTENT = '3dd6e8189df8d3c201a4583a6fd7f38aba9c8e8521f85064a33acdefb01eca9b';
const HEAD = 'e653583e5a012e502271b1cf7b4255cc95cf9d63';
const TREE = 'ace937abdc61e24534215d686ba96c7e8978cdb6';
const REPOSITORY = 'akaryc1b/approval-platform';
const CHANGES = [
  ['org.apache.maven.plugins:maven-antrun-plugin:jar:3.1.0', 'org.codehaus.plexus:plexus-utils:jar:3.4.1'],
  ['org.jacoco:jacoco-maven-plugin:jar:0.8.15', 'org.codehaus.plexus:plexus-utils:jar:3.0.24'],
];
const NEW = 'org.codehaus.plexus:plexus-utils:jar:3.6.1';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const sha = value => createHash('sha256').update(value).digest('hex');
const hash = value => sha(canonical(value));
const same = (a, b) => canonical(a) === canonical(b);
const requireValue = (ok, message) => { if (!ok) throw new Error(`JaCoCo/AntRun ${message}`); };
const SHA256 = /^[0-9a-f]{64}$/;
const bytes = (path, expected) => {
  requireValue(SHA256.test(expected || ''), 'evidence pin is unsealed');
  const raw = readFileSync(new URL(`../../${path}`, import.meta.url));
  requireValue(sha(raw) === expected, `byte digest mismatch: ${path}`); return raw;
};
const pinned = ref => JSON.parse(bytes(ref.path, ref.rawSha256));
function content(value, expected, label) {
  const { contentSha256, ...payload } = value;
  requireValue(SHA256.test(expected || '') && contentSha256 === expected && hash(payload) === expected,
    `${label} content digest mismatch`);
}
function validate(manifest) {
  requireValue([JACOCO_ANTRUN_GRAPH, JACOCO_ANTRUN_MANIFEST_SHA256, CONTENT].every(value => SHA256.test(value || '')),
    'actual graph capture is pending; transition is unsealed');
  content(manifest, CONTENT, 'manifest');
  requireValue(manifest.schemaVersion === 'APPROVAL_JACOCO_ANTRUN_GRAPH_TRANSITION_V1'
    && manifest.repository === REPOSITORY && manifest.base.sourceHead === HEAD && manifest.base.sourceTree === TREE
    && manifest.base.graphDigest === BOOT_UTILITIES_GRAPH && manifest.observed.graphDigest === JACOCO_ANTRUN_GRAPH
    && manifest.ownerCount === 17 && manifest.unchangedOtherPluginOwnerCount === 15
    && manifest.runtimeComponentChangeCount === 0 && manifest.runtimeEdgeChangeCount === 0
    && manifest.importedBomChangeCount === 0 && manifest.scopeChangeCount === 0 && manifest.licenseChangeCount === 0
    && manifest.releaseBlocked === true && manifest.findingReviewRequired === true
    && same(manifest.suppressions, []) && same(manifest.exceptions, [])
    && manifest.capture.sourceBaseHead === HEAD && manifest.capture.sourceBaseTree === TREE
    && manifest.capture.exactHeadEvidence === false && manifest.capture.sourceWorktreeClean === false,
  'transition identity mismatch');
}

/** Pure bounded policy; an unchanged union cannot conceal another changed owner. */
export function verifyJacocoAntrunOwnerSubstitution(beforeRaw, afterRaw) {
  const before = parseCheckstylePluginOwners(beforeRaw), after = parseCheckstylePluginOwners(afterRaw);
  const names = Object.keys(before.owners).sort(), changedOwners = CHANGES.map(([owner]) => owner).sort();
  requireValue(names.length === 17 && same(names, Object.keys(after.owners).sort())
    && same(before.occurrences, after.occurrences), 'owner membership/count drift');
  requireValue(same(names.filter(name => !same(before.owners[name], after.owners[name])), changedOwners),
    'unrelated owner or dependency changed');
  for (const [owner, old] of CHANGES) requireValue(before.owners[owner].includes(old)
    && !before.owners[owner].includes(NEW)
    && same(after.owners[owner], before.owners[owner].map(value => value === old ? NEW : value).sort()),
  'unrelated owner or dependency changed');
  return { before, after, ownerCount: 17, unchangedOtherPluginOwnerCount: 15,
    changes: CHANGES.map(([owner]) => ({ owner, before: before.owners[owner], after: after.owners[owner] })) };
}
const union = report => [...new Set(Object.values(report.owners).flat())].sort();

/** Reverse the validated owner union delta and report hash, preserving every other graph byte. */
export function verifyJacocoAntrunGraphSubstitution(projection, baseline, beforeRaw, afterRaw) {
  const result = verifyJacocoAntrunOwnerSubstitution(beforeRaw, afterRaw);
  const priorUnion = union(result.before), currentUnion = union(result.after);
  requireValue(same(baseline.maven.resolvedPluginCoordinates, priorUnion)
    && same(projection.maven.resolvedPluginCoordinates, currentUnion)
    && baseline.maven.pluginResolutionSha256 === sha(beforeRaw)
    && projection.maven.pluginResolutionSha256 === sha(afterRaw), 'global owner union/report mismatch');
  const previous = structuredClone(projection);
  const added = currentUnion.filter(value => !priorUnion.includes(value));
  const removed = priorUnion.filter(value => !currentUnion.includes(value));
  previous.maven.resolvedPluginCoordinates = previous.maven.resolvedPluginCoordinates.filter(value => !added.includes(value));
  previous.maven.resolvedPluginCoordinates.push(...removed); previous.maven.resolvedPluginCoordinates.sort();
  previous.maven.pluginResolutionSha256 = sha(beforeRaw);
  requireValue(same(previous, baseline), 'undeclared graph change'); return previous;
}

export function readJacocoAntrunSourceWitness(manifest = readJacocoAntrunManifest()) {
  validate(manifest); const witness = pinned(manifest.capture.sourceWitness);
  content(witness, manifest.capture.sourceWitness.contentSha256, 'source witness');
  const execution = pinned(witness.captureExecution), historical = readBootUtilitiesSourceWitness();
  verifyCheckstyleCaptureProvenance(execution);
  requireValue(witness.schemaVersion === 'APPROVAL_JACOCO_ANTRUN_SOURCE_WITNESS_V1'
    && witness.repository === REPOSITORY && witness.sourceBaseHead === HEAD && witness.sourceBaseTree === TREE
    && execution.schemaVersion === 'APPROVAL_JACOCO_ANTRUN_CAPTURE_EXECUTION_V1'
    && execution.sourceHead === HEAD && execution.sourceTree === TREE && execution.offline === true
    && witness.sourcePomFiles.length === 28 && new Set(witness.sourcePomFiles.map(row => row.path)).size === 28
    && same(witness.rootPom, manifest.capture.sourcePom) && witness.exactHeadEvidence === false
    && witness.compatibilityAcceptanceClaimed === false && witness.scannerDispositionClaimed === false
    && witness.reviewerAcceptanceClaimed === false && witness.releaseBlocked === true,
  'source/capture provenance mismatch');
  bytes(witness.collector.path, witness.collector.rawSha256);
  requireValue(witness.collector.rawSha256 === execution.collectorSha256, 'collector witness mismatch');
  bytes(witness.rootPom.path, witness.rootPom.rawSha256); bytes(witness.baselineRootPom.path, witness.baselineRootPom.rawSha256);
  requireValue(execution.e2Sha256 === manifest.capture.e2.rawSha256
    && execution.ownerReportSha256 === manifest.capture.pluginReport.rawSha256
    && execution.sourcePomSha256 === witness.rootPom.rawSha256, 'capture report/E2/source mismatch');
  requireValue(same(witness.generatorSources, historical.generatorSources)
    && same(execution.generatorSources, historical.generatorSources), 'unchanged generator closure mismatch');
  for (const [path, expected] of Object.entries(execution.captureInputs)) bytes(path, expected);
  for (const [path, expected] of Object.entries(witness.generatorSources))
    requireValue(execution.captureInputs[path] === expected, 'generator capture binding mismatch');
  requireValue(same(witness.sourcePomFiles.map(row => row.path).sort(), historical.sourcePomFiles.map(row => row.path).sort())
    && same(Object.keys(execution.sourcePomHashes).sort(), witness.sourcePomFiles.map(row => row.path).sort()),
  'source POM membership drift');
  const changed = [];
  for (const row of witness.sourcePomFiles) {
    bytes(row.path, row.candidateSha256);
    requireValue(execution.sourcePomHashes[row.path] === row.candidateSha256
      && historical.sourcePomFiles.find(prior => prior.path === row.path).candidateSha256 === row.baselineSha256,
    'source POM baseline/capture mismatch');
    if (row.candidateSha256 !== row.baselineSha256) changed.push(row.path);
  }
  const root = witness.sourcePomFiles.find(row => row.path === 'pom.xml');
  requireValue(same(changed, ['pom.xml']) && root.candidateSha256 === witness.rootPom.rawSha256
    && root.baselineSha256 === witness.baselineRootPom.rawSha256
    && sha(readPreservedBootUtilitiesSource('pom.xml', root.baselineSha256)) === root.baselineSha256,
  'exact source reversal mismatch');
  requireValue(execution.commands.filter(row => ['e2', 'owners'].includes(row.label)).length === 2
    && execution.commands.every(row => row.status === 'PASSED' && row.exitStatus === 0), 'capture commands incomplete');
  return witness;
}

export function readJacocoAntrunBaseline(manifest = readJacocoAntrunManifest()) {
  validate(manifest); const e2 = pinned(manifest.base.e2); content(e2, manifest.base.e2.contentSha256, 'baseline');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === HEAD
    && hash(acceptedE2GraphProjection(e2)) === BOOT_UTILITIES_GRAPH
    && e2.maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.from, 'accepted baseline mismatch'); return e2;
}
export function readJacocoAntrunCandidate(manifest = readJacocoAntrunManifest()) {
  validate(manifest); const e2 = pinned(manifest.capture.e2); content(e2, manifest.capture.e2.contentSha256, 'candidate');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === HEAD
    && hash(acceptedE2GraphProjection(e2)) === JACOCO_ANTRUN_GRAPH
    && e2.maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.to, 'candidate mismatch'); return e2;
}
export function readJacocoAntrunReport(manifest = readJacocoAntrunManifest()) {
  validate(manifest); return bytes(manifest.capture.pluginReport.path, manifest.capture.pluginReport.rawSha256).toString('utf8');
}
export function readJacocoAntrunPreservedBootUtilitiesLineage(manifest = readJacocoAntrunManifest()) {
  validate(manifest); const lineage = pinned(manifest.base.bootUtilitiesLineage);
  content(lineage, manifest.base.bootUtilitiesLineage.contentSha256, 'preserved Boot utilities lineage');
  requireValue(lineage.schemaVersion === 'APPROVAL_BOOT_UTILITIES_GRAPH_LINEAGE_V1' && lineage.repository === REPOSITORY
    && lineage.commitSha === HEAD && lineage.sourceE2ContentSha256 === manifest.base.e2.contentSha256
    && lineage.currentE2GraphDigest === BOOT_UTILITIES_GRAPH, 'preserved accepted lineage mismatch'); return lineage;
}
export function verifyJacocoAntrunOwnerDelta(beforeRaw, afterRaw, manifest = readJacocoAntrunManifest()) {
  validate(manifest); const result = verifyJacocoAntrunOwnerSubstitution(beforeRaw, afterRaw);
  const before = union(result.before), after = union(result.after);
  const added = after.filter(value => !before.includes(value)), removed = before.filter(value => !after.includes(value));
  requireValue(same(manifest.pluginOwnerChanges, result.changes)
    && hash(result.before.owners) === manifest.previousOwnerInventorySha256
    && hash(result.after.owners) === manifest.currentOwnerInventorySha256
    && same(manifest.addedPluginCoordinates, added) && manifest.addedPluginCoordinateCount === added.length
    && same(manifest.removedPluginCoordinates, removed) && manifest.removedPluginCoordinateCount === removed.length,
  'owner manifest mismatch');
  verifyJacocoAntrunGraphSubstitution(acceptedE2GraphProjection(readJacocoAntrunCandidate(manifest)),
    acceptedE2GraphProjection(readJacocoAntrunBaseline(manifest)), beforeRaw, afterRaw); return result;
}
export function readJacocoAntrunManifest() {
  const manifest = pinned({ path: 'docs/operations/jacoco-antrun-plugin-transition.json', rawSha256: JACOCO_ANTRUN_MANIFEST_SHA256 });
  validate(manifest); readJacocoAntrunSourceWitness(manifest); readJacocoAntrunBaseline(manifest);
  readJacocoAntrunCandidate(manifest); readJacocoAntrunPreservedBootUtilitiesLineage(manifest);
  verifyJacocoAntrunOwnerDelta(readBootUtilitiesReport(), readJacocoAntrunReport(manifest), manifest); return manifest;
}
export function verifyJacocoAntrunCurrentEvidence(e2, manifest = readJacocoAntrunManifest()) {
  validate(manifest); const candidate = readJacocoAntrunCandidate(manifest);
  const { commitSha, contentSha256, ...payload } = e2;
  const { commitSha: base, contentSha256: capturedHash, ...captured } = candidate;
  requireValue(/^[0-9a-f]{40}$/.test(commitSha || '') && hash({ ...payload, commitSha }) === contentSha256
    && same(payload, captured), 'current E2 source/Actions/toolchain drift'); readJacocoAntrunSourceWitness(manifest);
}
export function verifyJacocoAntrunDelta(projection, manifest = readJacocoAntrunManifest()) {
  validate(manifest); readJacocoAntrunSourceWitness(manifest);
  verifyJacocoAntrunOwnerDelta(readBootUtilitiesReport(), readJacocoAntrunReport(manifest), manifest);
  requireValue(hash(projection) === JACOCO_ANTRUN_GRAPH, 'current graph drift');
  const previous = verifyJacocoAntrunGraphSubstitution(projection, acceptedE2GraphProjection(readJacocoAntrunBaseline(manifest)),
    readBootUtilitiesReport(), readJacocoAntrunReport(manifest));
  requireValue(hash(previous) === BOOT_UTILITIES_GRAPH, 'prior graph drift');
  for (const [name, evidence] of [['prior', previous], ['current', projection]]) {
    const input = osvInputFromE2(evidence);
    requireValue(input.packageCount === manifest.osvInput[name].packageCount
      && sha(JSON.stringify(input.scannerInput)) === manifest.osvInput[name].inputBytesSha256, 'exact OSV input identity drift');
  }
  return previous;
}
