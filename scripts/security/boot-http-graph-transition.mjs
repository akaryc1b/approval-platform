import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { acceptedE2GraphProjection } from './m6-pr-e-e2-generate-sbom.mjs';
import { CHECKSTYLE_GUAVA_GRAPH, readCheckstyleGuavaReport, readCheckstyleGuavaSourceWitness }
  from './checkstyle-guava-graph-transition.mjs';
import { parseCheckstylePluginOwners, verifyCheckstyleCaptureProvenance } from './checkstyle-plugin-graph-transition.mjs';
import { readPreservedGuavaSource } from './boot-http-source-continuation.mjs';
import { osvInputFromE2 } from './osv-scan-coverage.mjs';

export const BOOT_HTTP_GRAPH = '5760ae70a04b967548d991397b14076d8aaf45bb4bd3bd00e917317fdd649867';
export const BOOT_HTTP_MANIFEST_SHA256 = '3a2d6028aff6374f0ecb0ed537474d2b8f309289d5c2e36bf8590dc3a8fc94db';
const CONTENT = '256e03ca8f616ee5b811e1437db4182b9bf79871b2defe128770d32bc6a068b8';
const HEAD = '88a7fb07a90fc1cef2b8185e88ed60aa1f83d2be';
const TREE = '81e2b07842eac26ce7936a6cf5452e5ebdd7d83d';
const REPOSITORY = 'akaryc1b/approval-platform';
const OWNER = 'org.springframework.boot:spring-boot-maven-plugin:jar:4.0.8';
const CHANGES = [
  ['org.apache.httpcomponents.client5:httpclient5:jar:5.5.2', 'org.apache.httpcomponents.client5:httpclient5:jar:5.6.3'],
  ['org.apache.httpcomponents.core5:httpcore5-h2:jar:5.3.6', 'org.apache.httpcomponents.core5:httpcore5-h2:jar:5.4.3'],
  ['org.apache.httpcomponents.core5:httpcore5:jar:5.3.6', 'org.apache.httpcomponents.core5:httpcore5:jar:5.4.3'],
];
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const sha = value => createHash('sha256').update(value).digest('hex');
const hash = value => sha(canonical(value));
const same = (a, b) => canonical(a) === canonical(b);
const requireValue = (ok, message) => { if (!ok) throw new Error(`Boot HTTP ${message}`); };
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
  content(manifest, CONTENT, 'manifest');
  requireValue(manifest.schemaVersion === 'APPROVAL_BOOT_HTTP_GRAPH_TRANSITION_V1'
    && manifest.repository === REPOSITORY && manifest.base.sourceHead === HEAD && manifest.base.sourceTree === TREE
    && manifest.base.graphDigest === CHECKSTYLE_GUAVA_GRAPH && manifest.observed.graphDigest === BOOT_HTTP_GRAPH
    && manifest.ownerCount === 17 && manifest.unchangedOtherPluginOwnerCount === 16
    && manifest.runtimeComponentChangeCount === 0 && manifest.runtimeEdgeChangeCount === 0
    && manifest.importedBomChangeCount === 0 && manifest.scopeChangeCount === 0 && manifest.licenseChangeCount === 0
    && manifest.releaseBlocked === true && manifest.findingReviewRequired === true
    && same(manifest.suppressions, []) && same(manifest.exceptions, [])
    && manifest.capture.sourceBaseHead === HEAD && manifest.capture.sourceBaseTree === TREE
    && manifest.capture.exactHeadEvidence === false && manifest.capture.sourceWorktreeClean === false,
  'transition identity mismatch');
}

/** Pure bounded policy; an unchanged union cannot conceal another changed owner. */
export function verifyBootHttpOwnerSubstitution(beforeRaw, afterRaw) {
  const before = parseCheckstylePluginOwners(beforeRaw), after = parseCheckstylePluginOwners(afterRaw);
  const names = Object.keys(before.owners).sort(), replacement = new Map(CHANGES);
  requireValue(names.length === 17 && same(names, Object.keys(after.owners).sort())
    && same(before.occurrences, after.occurrences), 'owner membership/count drift');
  requireValue(same(names.filter(name => !same(before.owners[name], after.owners[name])), [OWNER])
    && CHANGES.every(([old, current]) => before.owners[OWNER].includes(old) && !before.owners[OWNER].includes(current))
    && same(after.owners[OWNER], before.owners[OWNER].map(value => replacement.get(value) || value).sort()),
  'unrelated owner or dependency changed');
  return { before, after, ownerCount: 17, unchangedOtherPluginOwnerCount: 16,
    changes: [{ owner: OWNER, before: before.owners[OWNER], after: after.owners[OWNER] }] };
}
const union = report => [...new Set(Object.values(report.owners).flat())].sort();

/** Reverse the validated owner union delta and report hash, preserving every other graph byte. */
export function verifyBootHttpGraphSubstitution(projection, baseline, beforeRaw, afterRaw) {
  const result = verifyBootHttpOwnerSubstitution(beforeRaw, afterRaw);
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

export function readBootHttpSourceWitness(manifest = readBootHttpManifest()) {
  validate(manifest); const witness = pinned(manifest.capture.sourceWitness);
  content(witness, manifest.capture.sourceWitness.contentSha256, 'source witness');
  const execution = pinned(witness.captureExecution), historical = readCheckstyleGuavaSourceWitness();
  verifyCheckstyleCaptureProvenance(execution);
  requireValue(witness.schemaVersion === 'APPROVAL_BOOT_HTTP_SOURCE_WITNESS_V1'
    && witness.repository === REPOSITORY && witness.sourceBaseHead === HEAD && witness.sourceBaseTree === TREE
    && execution.schemaVersion === 'APPROVAL_BOOT_HTTP_CAPTURE_EXECUTION_V1'
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
    && sha(readPreservedGuavaSource('pom.xml', root.baselineSha256)) === root.baselineSha256,
  'exact source reversal mismatch');
  requireValue(execution.commands.filter(row => ['e2', 'owners'].includes(row.label)).length === 2
    && execution.commands.every(row => row.status === 'PASSED' && row.exitStatus === 0), 'capture commands incomplete');
  return witness;
}

export function readBootHttpBaseline(manifest = readBootHttpManifest()) {
  validate(manifest); const e2 = pinned(manifest.base.e2); content(e2, manifest.base.e2.contentSha256, 'baseline');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === HEAD
    && hash(acceptedE2GraphProjection(e2)) === CHECKSTYLE_GUAVA_GRAPH
    && e2.maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.from, 'accepted baseline mismatch'); return e2;
}
export function readBootHttpCandidate(manifest = readBootHttpManifest()) {
  validate(manifest); const e2 = pinned(manifest.capture.e2); content(e2, manifest.capture.e2.contentSha256, 'candidate');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === HEAD
    && hash(acceptedE2GraphProjection(e2)) === BOOT_HTTP_GRAPH
    && e2.maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.to, 'candidate mismatch'); return e2;
}
export function readBootHttpReport(manifest = readBootHttpManifest()) {
  validate(manifest); return bytes(manifest.capture.pluginReport.path, manifest.capture.pluginReport.rawSha256).toString('utf8');
}
export function readBootHttpPreservedGuavaLineage(manifest = readBootHttpManifest()) {
  validate(manifest); const lineage = pinned(manifest.base.guavaLineage);
  content(lineage, manifest.base.guavaLineage.contentSha256, 'preserved Guava lineage');
  requireValue(lineage.schemaVersion === 'APPROVAL_CHECKSTYLE_GUAVA_GRAPH_LINEAGE_V1' && lineage.repository === REPOSITORY
    && lineage.commitSha === HEAD && lineage.sourceE2ContentSha256 === manifest.base.e2.contentSha256
    && lineage.currentE2GraphDigest === CHECKSTYLE_GUAVA_GRAPH, 'preserved accepted lineage mismatch'); return lineage;
}
export function verifyBootHttpOwnerDelta(beforeRaw, afterRaw, manifest = readBootHttpManifest()) {
  validate(manifest); const result = verifyBootHttpOwnerSubstitution(beforeRaw, afterRaw);
  const before = union(result.before), after = union(result.after);
  const added = after.filter(value => !before.includes(value)), removed = before.filter(value => !after.includes(value));
  requireValue(same(manifest.pluginOwnerChanges, result.changes)
    && hash(result.before.owners) === manifest.previousOwnerInventorySha256
    && hash(result.after.owners) === manifest.currentOwnerInventorySha256
    && same(manifest.addedPluginCoordinates, added) && manifest.addedPluginCoordinateCount === added.length
    && same(manifest.removedPluginCoordinates, removed) && manifest.removedPluginCoordinateCount === removed.length,
  'owner manifest mismatch');
  verifyBootHttpGraphSubstitution(acceptedE2GraphProjection(readBootHttpCandidate(manifest)),
    acceptedE2GraphProjection(readBootHttpBaseline(manifest)), beforeRaw, afterRaw); return result;
}
export function readBootHttpManifest() {
  const manifest = pinned({ path: 'docs/operations/boot-http-plugin-transition.json', rawSha256: BOOT_HTTP_MANIFEST_SHA256 });
  validate(manifest); readBootHttpSourceWitness(manifest); readBootHttpBaseline(manifest);
  readBootHttpCandidate(manifest); readBootHttpPreservedGuavaLineage(manifest);
  verifyBootHttpOwnerDelta(readCheckstyleGuavaReport(), readBootHttpReport(manifest), manifest); return manifest;
}
export function verifyBootHttpCurrentEvidence(e2, manifest = readBootHttpManifest()) {
  validate(manifest); const candidate = readBootHttpCandidate(manifest);
  const { commitSha, contentSha256, ...payload } = e2;
  const { commitSha: base, contentSha256: capturedHash, ...captured } = candidate;
  requireValue(/^[0-9a-f]{40}$/.test(commitSha || '') && hash({ ...payload, commitSha }) === contentSha256
    && same(payload, captured), 'current E2 source/Actions/toolchain drift'); readBootHttpSourceWitness(manifest);
}
export function verifyBootHttpDelta(projection, manifest = readBootHttpManifest()) {
  validate(manifest); readBootHttpSourceWitness(manifest);
  verifyBootHttpOwnerDelta(readCheckstyleGuavaReport(), readBootHttpReport(manifest), manifest);
  requireValue(hash(projection) === BOOT_HTTP_GRAPH, 'current graph drift');
  const previous = verifyBootHttpGraphSubstitution(projection, acceptedE2GraphProjection(readBootHttpBaseline(manifest)),
    readCheckstyleGuavaReport(), readBootHttpReport(manifest));
  requireValue(hash(previous) === CHECKSTYLE_GUAVA_GRAPH, 'prior graph drift');
  for (const [name, evidence] of [['prior', previous], ['current', projection]]) {
    const input = osvInputFromE2(evidence);
    requireValue(input.packageCount === manifest.osvInput[name].packageCount
      && sha(JSON.stringify(input.scannerInput)) === manifest.osvInput[name].inputBytesSha256, 'exact OSV input identity drift');
  }
  return previous;
}
