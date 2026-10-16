import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { preservedHygieneJava21Source } from './jar-plugin-source-continuation.mjs';
import { acceptedE2GraphProjection } from './m6-pr-e-e2-generate-sbom.mjs';
import { BOOT_HTTP_GRAPH, readBootHttpReport, readBootHttpSourceWitness }
  from './boot-http-graph-transition.mjs';
import { parseCheckstylePluginOwners, verifyCheckstyleCaptureProvenance } from './checkstyle-plugin-graph-transition.mjs';
import { readPreservedBootHttpSource } from './boot-utilities-source-continuation.mjs';
import { readPreservedBootUtilitiesSource } from './jacoco-antrun-source-continuation.mjs';
import { osvInputFromE2 } from './osv-scan-coverage.mjs';

export const BOOT_UTILITIES_GRAPH = '454934f756eb08a49bc6d74435f12064df6c74286cad358558b0e73dda78c9ca';
export const BOOT_UTILITIES_MANIFEST_SHA256 = '1e00bf4e04f0c99c19e00cb806b48b8deac8cc9f948aa7f498c406b14a29fc93';
const CONTENT = '0cbac958bdf13970265a30923ec321915525da0bade6529e6b9640b9bbe4e822';
const HEAD = 'd38f57a82b13058d0e09839bdfb9a2ddf99d4508';
const TREE = '77919616340579ab1d00ce5d85127e5638bb9f09';
const REPOSITORY = 'akaryc1b/approval-platform';
const OWNER = 'org.springframework.boot:spring-boot-maven-plugin:jar:4.0.8';
const CHANGES = [
  ['org.codehaus.plexus:plexus-utils:jar:3.5.1', 'org.codehaus.plexus:plexus-utils:jar:3.6.1'],
  ['org.apache.commons:commons-lang3:jar:3.16.0', 'org.apache.commons:commons-lang3:jar:3.18.0'],
];
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const sha = value => createHash('sha256').update(value).digest('hex');
const hash = value => sha(canonical(value));
const same = (a, b) => canonical(a) === canonical(b);
const requireValue = (ok, message) => { if (!ok) throw new Error(`Boot utilities ${message}`); };
const SHA256 = /^[0-9a-f]{64}$/;
const bytes = (path, expected) => {
  requireValue(SHA256.test(expected || ''), 'evidence pin is unsealed');
  const raw = preservedHygieneJava21Source(path, expected, readFileSync(new URL(`../../${path}`, import.meta.url)));
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
  requireValue(manifest.schemaVersion === 'APPROVAL_BOOT_UTILITIES_GRAPH_TRANSITION_V1'
    && manifest.repository === REPOSITORY && manifest.base.sourceHead === HEAD && manifest.base.sourceTree === TREE
    && manifest.base.graphDigest === BOOT_HTTP_GRAPH && manifest.observed.graphDigest === BOOT_UTILITIES_GRAPH
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
export function verifyBootUtilitiesOwnerSubstitution(beforeRaw, afterRaw) {
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
export function verifyBootUtilitiesGraphSubstitution(projection, baseline, beforeRaw, afterRaw) {
  const result = verifyBootUtilitiesOwnerSubstitution(beforeRaw, afterRaw);
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

export function readBootUtilitiesSourceWitness(manifest = readBootUtilitiesManifest()) {
  validate(manifest); const witness = pinned(manifest.capture.sourceWitness);
  content(witness, manifest.capture.sourceWitness.contentSha256, 'source witness');
  const execution = pinned(witness.captureExecution), historical = readBootHttpSourceWitness();
  verifyCheckstyleCaptureProvenance(execution);
  requireValue(witness.schemaVersion === 'APPROVAL_BOOT_UTILITIES_SOURCE_WITNESS_V1'
    && witness.repository === REPOSITORY && witness.sourceBaseHead === HEAD && witness.sourceBaseTree === TREE
    && execution.schemaVersion === 'APPROVAL_BOOT_UTILITIES_CAPTURE_EXECUTION_V1'
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
  for (const [path, expected] of Object.entries(execution.captureInputs)) {
    if (path === 'pom.xml') readPreservedBootUtilitiesSource(path, expected); else bytes(path, expected);
  }
  for (const [path, expected] of Object.entries(witness.generatorSources))
    requireValue(execution.captureInputs[path] === expected, 'generator capture binding mismatch');
  requireValue(same(witness.sourcePomFiles.map(row => row.path).sort(), historical.sourcePomFiles.map(row => row.path).sort())
    && same(Object.keys(execution.sourcePomHashes).sort(), witness.sourcePomFiles.map(row => row.path).sort()),
  'source POM membership drift');
  const changed = [];
  for (const row of witness.sourcePomFiles) {
    readPreservedBootUtilitiesSource(row.path, row.candidateSha256);
    requireValue(execution.sourcePomHashes[row.path] === row.candidateSha256
      && historical.sourcePomFiles.find(prior => prior.path === row.path).candidateSha256 === row.baselineSha256,
    'source POM baseline/capture mismatch');
    if (row.candidateSha256 !== row.baselineSha256) changed.push(row.path);
  }
  const root = witness.sourcePomFiles.find(row => row.path === 'pom.xml');
  requireValue(same(changed, ['pom.xml']) && root.candidateSha256 === witness.rootPom.rawSha256
    && root.baselineSha256 === witness.baselineRootPom.rawSha256
    && sha(readPreservedBootHttpSource('pom.xml', root.baselineSha256)) === root.baselineSha256,
  'exact source reversal mismatch');
  requireValue(execution.commands.filter(row => ['e2', 'owners'].includes(row.label)).length === 2
    && execution.commands.every(row => row.status === 'PASSED' && row.exitStatus === 0), 'capture commands incomplete');
  return witness;
}

export function readBootUtilitiesBaseline(manifest = readBootUtilitiesManifest()) {
  validate(manifest); const e2 = pinned(manifest.base.e2); content(e2, manifest.base.e2.contentSha256, 'baseline');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === HEAD
    && hash(acceptedE2GraphProjection(e2)) === BOOT_HTTP_GRAPH
    && e2.maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.from, 'accepted baseline mismatch'); return e2;
}
export function readBootUtilitiesCandidate(manifest = readBootUtilitiesManifest()) {
  validate(manifest); const e2 = pinned(manifest.capture.e2); content(e2, manifest.capture.e2.contentSha256, 'candidate');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === HEAD
    && hash(acceptedE2GraphProjection(e2)) === BOOT_UTILITIES_GRAPH
    && e2.maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.to, 'candidate mismatch'); return e2;
}
export function readBootUtilitiesReport(manifest = readBootUtilitiesManifest()) {
  validate(manifest); return bytes(manifest.capture.pluginReport.path, manifest.capture.pluginReport.rawSha256).toString('utf8');
}
export function readBootUtilitiesPreservedBootHttpLineage(manifest = readBootUtilitiesManifest()) {
  validate(manifest); const lineage = pinned(manifest.base.bootHttpLineage);
  content(lineage, manifest.base.bootHttpLineage.contentSha256, 'preserved Boot HTTP lineage');
  requireValue(lineage.schemaVersion === 'APPROVAL_BOOT_HTTP_GRAPH_LINEAGE_V1' && lineage.repository === REPOSITORY
    && lineage.commitSha === HEAD && lineage.sourceE2ContentSha256 === manifest.base.e2.contentSha256
    && lineage.currentE2GraphDigest === BOOT_HTTP_GRAPH, 'preserved accepted lineage mismatch'); return lineage;
}
export function verifyBootUtilitiesOwnerDelta(beforeRaw, afterRaw, manifest = readBootUtilitiesManifest()) {
  validate(manifest); const result = verifyBootUtilitiesOwnerSubstitution(beforeRaw, afterRaw);
  const before = union(result.before), after = union(result.after);
  const added = after.filter(value => !before.includes(value)), removed = before.filter(value => !after.includes(value));
  requireValue(same(manifest.pluginOwnerChanges, result.changes)
    && hash(result.before.owners) === manifest.previousOwnerInventorySha256
    && hash(result.after.owners) === manifest.currentOwnerInventorySha256
    && same(manifest.addedPluginCoordinates, added) && manifest.addedPluginCoordinateCount === added.length
    && same(manifest.removedPluginCoordinates, removed) && manifest.removedPluginCoordinateCount === removed.length,
  'owner manifest mismatch');
  verifyBootUtilitiesGraphSubstitution(acceptedE2GraphProjection(readBootUtilitiesCandidate(manifest)),
    acceptedE2GraphProjection(readBootUtilitiesBaseline(manifest)), beforeRaw, afterRaw); return result;
}
export function readBootUtilitiesManifest() {
  const manifest = pinned({ path: 'docs/operations/boot-utilities-plugin-transition.json', rawSha256: BOOT_UTILITIES_MANIFEST_SHA256 });
  validate(manifest); readBootUtilitiesSourceWitness(manifest); readBootUtilitiesBaseline(manifest);
  readBootUtilitiesCandidate(manifest); readBootUtilitiesPreservedBootHttpLineage(manifest);
  verifyBootUtilitiesOwnerDelta(readBootHttpReport(), readBootUtilitiesReport(manifest), manifest); return manifest;
}
export function verifyBootUtilitiesCurrentEvidence(e2, manifest = readBootUtilitiesManifest()) {
  validate(manifest); const candidate = readBootUtilitiesCandidate(manifest);
  const { commitSha, contentSha256, ...payload } = e2;
  const { commitSha: base, contentSha256: capturedHash, ...captured } = candidate;
  requireValue(/^[0-9a-f]{40}$/.test(commitSha || '') && hash({ ...payload, commitSha }) === contentSha256
    && same(payload, captured), 'current E2 source/Actions/toolchain drift'); readBootUtilitiesSourceWitness(manifest);
}
export function verifyBootUtilitiesDelta(projection, manifest = readBootUtilitiesManifest()) {
  validate(manifest); readBootUtilitiesSourceWitness(manifest);
  verifyBootUtilitiesOwnerDelta(readBootHttpReport(), readBootUtilitiesReport(manifest), manifest);
  requireValue(hash(projection) === BOOT_UTILITIES_GRAPH, 'current graph drift');
  const previous = verifyBootUtilitiesGraphSubstitution(projection, acceptedE2GraphProjection(readBootUtilitiesBaseline(manifest)),
    readBootHttpReport(), readBootUtilitiesReport(manifest));
  requireValue(hash(previous) === BOOT_HTTP_GRAPH, 'prior graph drift');
  for (const [name, evidence] of [['prior', previous], ['current', projection]]) {
    const input = osvInputFromE2(evidence);
    requireValue(input.packageCount === manifest.osvInput[name].packageCount
      && sha(JSON.stringify(input.scannerInput)) === manifest.osvInput[name].inputBytesSha256, 'exact OSV input identity drift');
  }
  return previous;
}
