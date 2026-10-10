import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { acceptedE2GraphProjection } from './m6-pr-e-e2-generate-sbom.mjs';
import { RELEASE_PLUGIN_GRAPH, readReleasePluginReport } from './release-plugin-graph-transition.mjs';

export const CLEAN_PLUGIN_GRAPH = '255008a8cf1b3d57182cdd47c680aad7e484b027f23ae5e3a19590791732d950';
export const CLEAN_PLUGIN_MANIFEST_SHA256 = '9d810b921b021ce10eca41c6ff278964ed16210285fbca0e9c8af0814464e7b1';
const CONTENT_SHA256 = '77b97890d4f686b10cecc5138f10dacea824bffddb9d41b19997a275be6e9c2e';
const REPOSITORY = 'akaryc1b/approval-platform';
const CLEAN = 'org.apache.maven.plugins:maven-clean-plugin:jar:3.2.0';
const OLD_IO = 'commons-io:commons-io:jar:2.6', NEW_IO = 'commons-io:commons-io:jar:2.20.0';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const sha256 = value => createHash('sha256').update(value).digest('hex');
const hash = value => sha256(canonical(value));
const same = (left, right) => canonical(left) === canonical(right);
const requireValue = (condition, message) => { if (!condition) throw new Error(message); };
function keys(value, allowed, label) {
  requireValue(value && typeof value === 'object' && !Array.isArray(value)
    && same(Object.keys(value).sort(), [...allowed].sort()), `Clean ${label} fields mismatch`);
}
function requireContent(value, expected, label) {
  const { contentSha256, ...payload } = value;
  requireValue(contentSha256 === expected && hash(payload) === expected, `Clean ${label} content digest mismatch`);
}
function bytes(path, expected, label) {
  const raw = readFileSync(new URL(`../../${path}`, import.meta.url));
  requireValue(sha256(raw) === expected, `Clean ${label} byte digest mismatch`);
  return raw;
}
const pinned = (path, expected, label) => JSON.parse(bytes(path, expected, label));
function uniqueSorted(values, label) {
  requireValue(Array.isArray(values) && values.every(v => typeof v === 'string')
    && new Set(values).size === values.length && same(values, [...values].sort()), `Clean ${label} must be unique and sorted`);
}
function validateManifest(manifest) {
  requireContent(manifest, CONTENT_SHA256, 'manifest');
  keys(manifest, ['schemaVersion', 'repository', 'base', 'observed', 'capture', 'pluginResolutionHashChange',
    'addedPluginCoordinates', 'removedPluginCoordinates', 'addedPluginCoordinateCount', 'removedPluginCoordinateCount',
    'pluginOwnerChanges', 'ownerCount', 'unchangedOtherPluginOwnerCount', 'previousOwnerInventorySha256',
    'currentOwnerInventorySha256', 'runtimeComponentChangeCount', 'runtimeEdgeChangeCount', 'importedBomChangeCount',
    'scopeChangeCount', 'licenseChangeCount', 'inventory', 'osvInput', 'findingReviewRequired', 'releaseBlocked',
    'findingDisposition', 'suppressions', 'exceptions', 'contentSha256'], 'manifest');
  keys(manifest.base, ['sourceHead', 'sourceTree', 'sourceRunId', 'sourceRunNumber', 'graphDigest', 'e2', 'releaseLineage'], 'base');
  keys(manifest.observed, ['graphDigest'], 'observation');
  keys(manifest.capture, ['kind', 'exactHeadEvidence', 'sourceWorktreeClean', 'sourceBaseHead', 'sourceBaseTree',
    'e2', 'pluginReport', 'sourcePom', 'sourceWitness'], 'capture');
  for (const [label, value] of Object.entries({ baseE2: manifest.base.e2, baseLineage: manifest.base.releaseLineage,
    candidateE2: manifest.capture.e2, sourceWitness: manifest.capture.sourceWitness })) {
    keys(value, ['path', 'rawSha256', 'contentSha256'], label);
  }
  keys(manifest.capture.pluginReport, ['path', 'rawSha256', 'sourceReportSha256'], 'report reference');
  keys(manifest.capture.sourcePom, ['path', 'rawSha256'], 'POM reference');
  keys(manifest.pluginResolutionHashChange, ['from', 'to'], 'report transition');
  keys(manifest.osvInput, ['prior', 'current'], 'OSV input');
  for (const value of Object.values(manifest.osvInput)) keys(value, ['packageCount', 'inputBytesSha256'], 'OSV input identity');
  keys(manifest.inventory, ['componentCount', 'edgeCount', 'reactorRootCount', 'importedBomCount', 'resolvedPluginCoordinateCount'], 'inventory');
  requireValue(manifest.schemaVersion === 'APPROVAL_CLEAN_PLUGIN_GRAPH_TRANSITION_V1'
    && manifest.repository === REPOSITORY && manifest.base.graphDigest === RELEASE_PLUGIN_GRAPH
    && manifest.observed.graphDigest === CLEAN_PLUGIN_GRAPH && manifest.ownerCount === 17
    && manifest.unchangedOtherPluginOwnerCount === 16 && manifest.addedPluginCoordinateCount === 0
    && same(manifest.addedPluginCoordinates, []) && manifest.removedPluginCoordinateCount === 1
    && same(manifest.removedPluginCoordinates, [OLD_IO]) && manifest.runtimeComponentChangeCount === 0
    && manifest.runtimeEdgeChangeCount === 0 && manifest.importedBomChangeCount === 0
    && manifest.scopeChangeCount === 0 && manifest.licenseChangeCount === 0
    && manifest.findingReviewRequired === true && manifest.releaseBlocked === true
    && manifest.findingDisposition === 'UNRESOLVED_UNTIL_FRESH_COMPLETE_SCANNER_AND_EXPLICIT_FINDING_REVIEW'
    && same(manifest.suppressions, []) && same(manifest.exceptions, []), 'Clean transition identity mismatch');
  requireValue(manifest.capture.kind === 'LOCAL_PRECOMMIT_POM_CANDIDATE'
    && manifest.capture.exactHeadEvidence === false && manifest.capture.sourceWorktreeClean === false
    && manifest.capture.sourceBaseHead === manifest.base.sourceHead
    && manifest.capture.sourceBaseTree === manifest.base.sourceTree, 'Clean capture cannot claim exact-head evidence');
}

/** Pinned capture evidence is separate from model, compatibility and scanner acceptance. */
export function readCleanPluginSourceWitness(manifest = readCleanPluginManifest()) {
  validateManifest(manifest);
  const capture = manifest.capture.sourceWitness, witness = pinned(capture.path, capture.rawSha256, 'source witness');
  requireContent(witness, capture.contentSha256, 'source witness');
  keys(witness, ['schemaVersion', 'repository', 'sourceBaseHead', 'sourceBaseTree', 'captureKind', 'sourceWorktreeClean',
    'exactHeadEvidence', 'rootPom', 'baselineRootPom', 'sourcePomFiles', 'reactorPaths', 'trackedPomCount',
    'reactorProjectCount', 'captureExecution', 'collector', 'effectiveModelCapture', 'compatibilityAcceptanceClaimed',
    'scannerDispositionClaimed', 'reviewerAcceptanceClaimed', 'releaseBlocked', 'contentSha256'], 'source witness');
  requireValue(witness.schemaVersion === 'APPROVAL_CLEAN_PLUGIN_SOURCE_WITNESS_V1' && witness.repository === REPOSITORY
    && witness.sourceBaseHead === manifest.base.sourceHead && witness.sourceBaseTree === manifest.base.sourceTree
    && witness.captureKind === manifest.capture.kind && witness.sourceWorktreeClean === false && witness.exactHeadEvidence === false
    && witness.compatibilityAcceptanceClaimed === false && witness.scannerDispositionClaimed === false
    && witness.reviewerAcceptanceClaimed === false && witness.releaseBlocked === true
    && witness.effectiveModelCapture.reviewedModelAcceptanceClaimed === false
    && witness.effectiveModelCapture.captureStatus === 'PASSED_CAPTURE_ONLY', 'Clean source witness provenance mismatch');
  for (const [name, ref] of Object.entries({ rootPom: witness.rootPom, baselineRootPom: witness.baselineRootPom,
    captureExecution: witness.captureExecution, collector: witness.collector })) {
    keys(ref, ['path', 'rawSha256'], `${name} reference`); bytes(ref.path, ref.rawSha256, name);
  }
  requireValue(same(witness.rootPom, manifest.capture.sourcePom), 'Clean source POM reference mismatch');
  const execution = pinned(witness.captureExecution.path, witness.captureExecution.rawSha256, 'capture execution');
  requireValue(execution.status === 'PASSED_CAPTURE_ONLY' && execution.sourceDirty === true
    && execution.diagnosticPrecommit === true && execution.sourceHead === witness.sourceBaseHead
    && execution.sourcePomSha256 === witness.rootPom.rawSha256
    && execution.e2Sha256 === manifest.capture.e2.rawSha256 && execution.reportMatchesE2 === true
    && execution.pomStable === true, 'Clean capture execution/source mismatch');
  keys(witness.effectiveModelCapture, ['rawSha256', 'bytes', 'sourcePomSha256', 'captureCommand', 'captureExitStatus',
    'captureStatus', 'reviewedModelAcceptanceClaimed', 'rawModelsRetainedInExecutionEvidence'], 'effective-model capture');
  const modelCommand = execution.commands.filter(row => row.label === 'effective');
  requireValue(modelCommand.length === 1 && modelCommand[0].status === 'PASSED' && modelCommand[0].exitStatus === 0
    && same(modelCommand[0].command, witness.effectiveModelCapture.captureCommand)
    && witness.effectiveModelCapture.rawSha256 === execution.effectivePomSha256
    && witness.effectiveModelCapture.sourcePomSha256 === execution.sourcePomSha256
    && witness.effectiveModelCapture.captureExitStatus === 0 && Number.isSafeInteger(witness.effectiveModelCapture.bytes)
    && witness.effectiveModelCapture.bytes > 0 && witness.effectiveModelCapture.rawModelsRetainedInExecutionEvidence === true,
  'Clean effective-model capture binding mismatch');
  requireValue(witness.trackedPomCount === 28 && witness.sourcePomFiles.length === 28
    && witness.reactorProjectCount === 26 && witness.reactorPaths.length === 26
    && new Set(witness.reactorPaths).size === 26
    && same(witness.reactorPaths, execution.sourceAdmission.reactorPaths), 'Clean source POM inventory mismatch');
  const paths = witness.sourcePomFiles.map(row => row.repoPath);
  requireValue(new Set(paths).size === 28 && witness.reactorPaths.every(path => paths.includes(path))
    && same([...paths].sort(), Object.keys(execution.sourceAdmission.sourcePomHashes).sort()), 'Clean source POM membership mismatch');
  const changed = [];
  for (const row of witness.sourcePomFiles) {
    keys(row, ['repoPath', 'candidateSha256', 'baselineSha256', 'changed'], 'source POM row');
    requireValue(row.candidateSha256 === execution.sourceAdmission.sourcePomHashes[row.repoPath]
      && row.changed === (row.candidateSha256 !== row.baselineSha256), 'Clean source POM hash binding mismatch');
    bytes(row.repoPath, row.candidateSha256, `current source ${row.repoPath}`);
    if (row.changed) changed.push(row.repoPath);
  }
  requireValue(same(changed, ['pom.xml']) && witness.sourcePomFiles.find(row => row.repoPath === 'pom.xml').baselineSha256
    === witness.baselineRootPom.rawSha256, 'Clean source change escaped root POM');
  return witness;
}

export function readCleanPluginBaseline(manifest = readCleanPluginManifest()) {
  validateManifest(manifest);
  const ref = manifest.base.e2, e2 = pinned(ref.path, ref.rawSha256, 'accepted main E2');
  requireContent(e2, ref.contentSha256, 'accepted main E2');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY
    && e2.commitSha === manifest.base.sourceHead && hash(acceptedE2GraphProjection(e2)) === RELEASE_PLUGIN_GRAPH
    && e2.maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.from, 'Clean baseline identity mismatch');
  return e2;
}

export function readCleanPluginCandidate(manifest = readCleanPluginManifest()) {
  validateManifest(manifest);
  const ref = manifest.capture.e2, e2 = pinned(ref.path, ref.rawSha256, 'candidate E2');
  requireContent(e2, ref.contentSha256, 'candidate E2');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY
    && e2.commitSha === manifest.capture.sourceBaseHead && hash(acceptedE2GraphProjection(e2)) === CLEAN_PLUGIN_GRAPH
    && e2.maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.to, 'Clean candidate identity mismatch');
  return e2;
}

export function readCleanPluginReport(manifest = readCleanPluginManifest()) {
  validateManifest(manifest);
  const ref = manifest.capture.pluginReport, raw = bytes(ref.path, ref.rawSha256, 'plugin report');
  requireValue(sha256(raw) === ref.sourceReportSha256 && ref.sourceReportSha256 === manifest.pluginResolutionHashChange.to,
    'Clean plugin report identity mismatch');
  return raw.toString('utf8');
}

export function readCleanPluginPreservedReleaseLineage(manifest = readCleanPluginManifest()) {
  validateManifest(manifest);
  const ref = manifest.base.releaseLineage, value = pinned(ref.path, ref.rawSha256, 'preserved Release lineage');
  requireContent(value, ref.contentSha256, 'preserved Release lineage');
  requireValue(value.schemaVersion === 'APPROVAL_RELEASE_PLUGIN_GRAPH_LINEAGE_V1' && value.repository === REPOSITORY
    && value.commitSha === manifest.base.sourceHead && value.sourceE2ContentSha256 === manifest.base.e2.contentSha256
    && value.currentE2GraphDigest === RELEASE_PLUGIN_GRAPH, 'Clean preserved Release identity mismatch');
  return value;
}

function owners(raw) {
  const result = {}, occurrences = {}; let owner, coordinates;
  const finish = () => {
    if (!owner) return;
    const sorted = [...coordinates].sort(); uniqueSorted(sorted, `${owner} coordinates`);
    requireValue(sorted.includes(owner), 'Clean report owner self coordinate missing');
    requireValue(!result[owner] || same(result[owner], sorted), 'Clean repeated owner inventory drift');
    result[owner] = sorted; occurrences[owner] = (occurrences[owner] || 0) + 1;
  };
  for (const line of raw.split(/\r?\n/)) {
    if (!line.trim() || line === 'The following plugins have been resolved:') continue;
    const match = line.match(/^( {3}| {6})([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+){1,2})$/);
    requireValue(match, 'Clean plugin report unrecognized line');
    if (match[1].length === 3) { finish(); owner = match[2]; coordinates = []; }
    else { requireValue(owner, 'Clean plugin dependency missing owner'); coordinates.push(match[2]); }
  }
  finish(); return { owners: result, occurrences };
}

/** Check membership before byte hashes so equal global unions cannot conceal another changed owner. */
export function verifyCleanPluginOwnerDelta(beforeRaw, afterRaw, manifest = readCleanPluginManifest()) {
  validateManifest(manifest);
  const before = owners(beforeRaw), after = owners(afterRaw), names = Object.keys(before.owners).sort();
  requireValue(names.length === 17 && same(names, Object.keys(after.owners).sort())
    && same(before.occurrences, after.occurrences), 'Clean plugin owner inventory mismatch');
  const changed = names.filter(name => !same(before.owners[name], after.owners[name]));
  requireValue(same(changed, [CLEAN]), 'Clean unrelated plugin owner changed');
  requireValue(same(before.owners[CLEAN], [OLD_IO, CLEAN, 'org.apache.maven.shared:maven-shared-utils:jar:3.3.4'].sort())
    && same(after.owners[CLEAN], [NEW_IO, CLEAN, 'org.apache.maven.shared:maven-shared-utils:jar:3.3.4'].sort()), 'Clean owner dependency delta mismatch');
  requireValue(same(manifest.pluginOwnerChanges, [{ owner: CLEAN, before: before.owners[CLEAN], after: after.owners[CLEAN] }])
    && hash(before.owners) === manifest.previousOwnerInventorySha256 && hash(after.owners) === manifest.currentOwnerInventorySha256,
  'Clean owner manifest binding mismatch');
  for (const [name, report, e2] of [['prior', before, readCleanPluginBaseline(manifest)], ['current', after, readCleanPluginCandidate(manifest)]]) {
    const union = [...new Set(Object.values(report.owners).flat())].sort();
    requireValue(same(union, e2.maven.resolvedPluginCoordinates), `Clean ${name} owner union mismatch`);
  }
  requireValue(sha256(beforeRaw) === manifest.pluginResolutionHashChange.from
    && sha256(afterRaw) === manifest.pluginResolutionHashChange.to, 'Clean owner report digest mismatch');
  return { ownerCount: names.length, unchangedOtherPluginOwnerCount: names.length - changed.length, changes: manifest.pluginOwnerChanges };
}

export function readCleanPluginManifest() {
  const manifest = pinned('docs/operations/clean-plugin-transition.json', CLEAN_PLUGIN_MANIFEST_SHA256, 'manifest');
  validateManifest(manifest); readCleanPluginSourceWitness(manifest); readCleanPluginBaseline(manifest);
  readCleanPluginCandidate(manifest); readCleanPluginPreservedReleaseLineage(manifest);
  verifyCleanPluginOwnerDelta(readReleasePluginReport(), readCleanPluginReport(manifest), manifest);
  return manifest;
}

/** Bind fields omitted by accepted Action projection, retaining only current E2 identity changes. */
export function verifyCleanPluginCurrentEvidence(e2, manifest = readCleanPluginManifest()) {
  validateManifest(manifest);
  const candidate = readCleanPluginCandidate(manifest);
  keys(e2, Object.keys(candidate), 'current E2');
  const { commitSha, contentSha256, ...payload } = e2;
  const { commitSha: baseHead, contentSha256: captureHash, ...captured } = candidate;
  requireValue(/^[0-9a-f]{40}$/.test(commitSha || '') && hash({ ...payload, commitSha }) === contentSha256
    && same(payload, captured), 'Clean current E2 source/Actions/toolchain drift');
  readCleanPluginSourceWitness(manifest);
}

/** Reverse only the observed global IO removal and report hash; never replace the candidate graph. */
export function verifyCleanPluginDelta(projection, manifest = readCleanPluginManifest()) {
  validateManifest(manifest); readCleanPluginSourceWitness(manifest);
  verifyCleanPluginOwnerDelta(readReleasePluginReport(), readCleanPluginReport(manifest), manifest);
  requireValue(hash(projection) === CLEAN_PLUGIN_GRAPH, 'current Clean plugin graph drift');
  const previous = structuredClone(projection), maven = previous.maven;
  uniqueSorted(maven.resolvedPluginCoordinates, 'resolved plugin coordinates');
  requireValue(!maven.resolvedPluginCoordinates.includes(OLD_IO) && maven.resolvedPluginCoordinates.includes(NEW_IO), 'Clean IO coordinate delta mismatch');
  maven.resolvedPluginCoordinates.push(OLD_IO); maven.resolvedPluginCoordinates.sort();
  requireValue(maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.to, 'Clean plugin report hash mismatch');
  maven.pluginResolutionSha256 = manifest.pluginResolutionHashChange.from;
  requireValue(hash(previous) === RELEASE_PLUGIN_GRAPH
    && same(previous, acceptedE2GraphProjection(readCleanPluginBaseline(manifest))), 'undeclared Clean plugin graph change');
  return previous;
}
