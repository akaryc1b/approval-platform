import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { acceptedE2GraphProjection } from './m6-pr-e-e2-generate-sbom.mjs';
import { parseCheckstylePluginOwners, verifyCheckstyleCaptureProvenance } from './checkstyle-plugin-graph-transition.mjs';
import { readPreservedJacocoAntrunSource } from './assembly-plexus-source-continuation.mjs';
import { readPreservedAssemblySource } from './jar-plugin-source-continuation.mjs';
import { readPreservedCleanSource } from './compiler-plugin-source-continuation.mjs';
import { osvInputFromE2 } from './osv-scan-coverage.mjs';

// Sealed only from retained actual capture; an absent pin fails closed.
export const ASSEMBLY_PLEXUS_GRAPH = '305364325fbd8a75f189fe50b708866e1f64cfc1a89f14847f67a087d131a94c';
export const ASSEMBLY_PLEXUS_MANIFEST_SHA256 = '714f8ced1b3fb73fc33793382de056e21bee5a8d642eff45d44647d5238ef106';
const CONTENT = '95fca79488a849c616cf91873aba539936fb519d0a7a05b26846cd7741d4b55d';
const PRIOR_GRAPH = 'a338fa7e74474131fbd72f610f1be26cd7b676a46597aef5b04f5ef87a1dad92';
const BASE_GRAPH = '2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94';
const HEAD = '9b03965b8c7f6015301fd3db729172cd0f2cca3b';
const TREE = '285fe452f197c222940b190f7d417bc1b55d5178';
// Independently retained from accepted main1857. The candidate sealing step does
// not select or regenerate these predecessor trust anchors.
const ACCEPTED = {
  e2: ['b9fdf04ac8aa86dbf606965a4fc5edea8e9d60b034a39093fb73f78529e14ec4', '72c4eccff422404896634bc5f6da7da46a354e8adc99d43352efbef03581e8c5'],
  lineage: ['af1612eb31d970eeeedb0ee5d7fe643245dbdca644fa1b2f27e136c2a30794b4', '0083099b9d24edb7262edb1a7df929f7649157856698f81074ff596308919464'],
  artifactInventory: ['5defd3747e4d0833c1c2d7778b2254eeba26af5516cdc460c67570f8dab4c5db', '1260140e4902e0f54ec32a61ac67739524b92899395e4ada03ad7857c9df194d'],
  serverRemediation: ['c6b804c63345b9fbaca36adfeb540a3a3238d8b5e57a534407782a5797955104', 'ccf47002ec55b7cc6b14c2730ac6da65c8a37e6498e5aa0ad437f0d3d1c5451a'],
};
const REPOSITORY = 'akaryc1b/approval-platform';
const OWNER = 'org.apache.maven.plugins:maven-assembly-plugin:jar:3.7.1';
const OLD = 'org.codehaus.plexus:plexus-utils:jar:4.0.0';
const NEW = 'org.codehaus.plexus:plexus-utils:jar:4.0.3';
const SHA256 = /^[0-9a-f]{64}$/;
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const sha = value => createHash('sha256').update(value).digest('hex');
const hash = value => sha(canonical(value));
const same = (a, b) => canonical(a) === canonical(b);
const requireValue = (ok, message) => { if (!ok) throw new Error(`Assembly ${message}`); };
function bytes(path, expected) {
  requireValue(typeof path === 'string' && /^[A-Za-z0-9_./-]+$/.test(path) && !path.startsWith('/')
    && !path.split('/').some(part => !part || part === '.' || part === '..'), 'unsafe evidence path');
  requireValue(SHA256.test(expected || ''), 'evidence pin is unsealed');
  const raw = readFileSync(new URL(`../../${path}`, import.meta.url));
  requireValue(sha(raw) === expected, `byte digest mismatch: ${path}`); return raw;
}
const pinned = ref => JSON.parse(bytes(ref.path, ref.rawSha256));
function content(value, expected, label) {
  const { contentSha256, ...payload } = value;
  requireValue(SHA256.test(expected || '') && contentSha256 === expected && hash(payload) === expected,
    `${label} content digest mismatch`);
}
function validate(manifest) {
  requireValue([ASSEMBLY_PLEXUS_GRAPH, ASSEMBLY_PLEXUS_MANIFEST_SHA256, CONTENT].every(value => SHA256.test(value)),
    'actual graph capture is pending; transition is unsealed');
  content(manifest, CONTENT, 'manifest');
  requireValue(manifest.schemaVersion === 'APPROVAL_ASSEMBLY_PLEXUS_GRAPH_TRANSITION_V1'
    && manifest.repository === REPOSITORY && manifest.base.sourceHead === HEAD && manifest.base.sourceTree === TREE
    && manifest.base.sourceRunNumber === 1857 && manifest.base.graphDigest === PRIOR_GRAPH
    && manifest.observed.graphDigest === ASSEMBLY_PLEXUS_GRAPH
    && manifest.ownerCount === 17 && manifest.unchangedOtherPluginOwnerCount === 16
    && manifest.runtimeComponentChangeCount === 0 && manifest.runtimeEdgeChangeCount === 0
    && manifest.importedBomChangeCount === 0 && manifest.scopeChangeCount === 0 && manifest.licenseChangeCount === 0
    && manifest.releaseBlocked === true && manifest.findingReviewRequired === true
    && same(manifest.suppressions, []) && same(manifest.exceptions, [])
    && manifest.capture.sourceBaseHead === HEAD && manifest.capture.sourceBaseTree === TREE
    && manifest.capture.exactHeadEvidence === false && manifest.capture.sourceWorktreeClean === false,
  'transition identity mismatch');
  for (const [name, expected] of Object.entries(ACCEPTED)) requireValue(
    same([manifest.base[name].rawSha256, manifest.base[name].contentSha256], expected), 'accepted main1857 trust anchor mismatch');
}

/** Pure bounded comparison, including per-owner occurrence counts and all 21 Assembly entries. */
export function verifyAssemblyPlexusOwnerSubstitution(beforeRaw, afterRaw) {
  const before = parseCheckstylePluginOwners(beforeRaw), after = parseCheckstylePluginOwners(afterRaw);
  const names = Object.keys(before.owners).sort();
  requireValue(names.length === 17 && same(names, Object.keys(after.owners).sort())
    && same(before.occurrences, after.occurrences), 'owner membership/count drift');
  requireValue(same(names.filter(name => !same(before.owners[name], after.owners[name])), [OWNER])
    && before.owners[OWNER].length === 21 && before.occurrences[OWNER] === 26
    && before.owners[OWNER].includes(OLD) && !before.owners[OWNER].includes(NEW)
    && same(after.owners[OWNER], before.owners[OWNER].map(value => value === OLD ? NEW : value).sort()),
  'unrelated owner or dependency changed');
  return { before, after, ownerCount: 17, unchangedOtherPluginOwnerCount: 16,
    changes: [{ owner: OWNER, before: before.owners[OWNER], after: after.owners[OWNER] }] };
}
const union = report => [...new Set(Object.values(report.owners).flat())].sort();

/** The existing 4.0.3 union member stays; only 4.0.0 and the owner-report hash disappear. */
export function verifyAssemblyPlexusGraphSubstitution(projection, baseline, beforeRaw, afterRaw) {
  const result = verifyAssemblyPlexusOwnerSubstitution(beforeRaw, afterRaw);
  const previousUnion = union(result.before), currentUnion = union(result.after);
  requireValue(same(previousUnion.filter(value => !currentUnion.includes(value)), [OLD])
    && same(currentUnion.filter(value => !previousUnion.includes(value)), [])
    && same(baseline.maven.resolvedPluginCoordinates, previousUnion)
    && same(projection.maven.resolvedPluginCoordinates, currentUnion)
    && baseline.maven.pluginResolutionSha256 === sha(beforeRaw)
    && projection.maven.pluginResolutionSha256 === sha(afterRaw), 'global owner union/report mismatch');
  const previous = structuredClone(projection);
  previous.maven.resolvedPluginCoordinates.push(OLD); previous.maven.resolvedPluginCoordinates.sort();
  previous.maven.pluginResolutionSha256 = sha(beforeRaw);
  requireValue(same(previous, baseline), 'undeclared graph change'); return previous;
}

// Reading a manifest only checks that manifest. Callers pass it through one bounded operation.
// No helper defaults to full admission, and no process-wide validation result is retained.
export function readAssemblyPlexusManifest() {
  const manifest = pinned({ path: 'docs/operations/assembly-plexus-transition.json', rawSha256: ASSEMBLY_PLEXUS_MANIFEST_SHA256 });
  validate(manifest); return manifest;
}
export function readAssemblyPlexusBaseline(manifest = readAssemblyPlexusManifest()) {
  validate(manifest); const e2 = pinned(manifest.base.e2); content(e2, manifest.base.e2.contentSha256, 'baseline');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === HEAD
    && hash(acceptedE2GraphProjection(e2)) === PRIOR_GRAPH, 'accepted baseline mismatch'); return e2;
}
export function readAssemblyPlexusCandidate(manifest = readAssemblyPlexusManifest()) {
  validate(manifest); const e2 = pinned(manifest.capture.e2); content(e2, manifest.capture.e2.contentSha256, 'candidate');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === HEAD
    && hash(acceptedE2GraphProjection(e2)) === ASSEMBLY_PLEXUS_GRAPH, 'candidate mismatch'); return e2;
}
export function readAssemblyPlexusReport(manifest = readAssemblyPlexusManifest()) {
  validate(manifest); return bytes(manifest.capture.pluginReport.path, manifest.capture.pluginReport.rawSha256).toString('utf8');
}
export function readAssemblyPlexusSourceWitness(manifest = readAssemblyPlexusManifest()) {
  validate(manifest); const witness = pinned(manifest.capture.sourceWitness);
  content(witness, manifest.capture.sourceWitness.contentSha256, 'source witness');
  const execution = pinned(witness.captureExecution), historical = pinned(manifest.base.sourceWitness);
  content(historical, manifest.base.sourceWitness.contentSha256, 'accepted source witness');
  verifyCheckstyleCaptureProvenance(execution);
  requireValue(witness.schemaVersion === 'APPROVAL_ASSEMBLY_PLEXUS_SOURCE_WITNESS_V1'
    && witness.repository === REPOSITORY && witness.sourceBaseHead === HEAD && witness.sourceBaseTree === TREE
    && execution.schemaVersion === 'APPROVAL_ASSEMBLY_PLEXUS_CAPTURE_EXECUTION_V1'
    && execution.sourceHead === HEAD && execution.sourceTree === TREE && execution.offline === true
    && witness.sourcePomFiles.length === 28 && new Set(witness.sourcePomFiles.map(row => row.path)).size === 28
    && same(witness.rootPom, manifest.capture.sourcePom) && witness.exactHeadEvidence === false && witness.sourceWorktreeClean === false
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
    if (path === 'pom.xml') readPreservedAssemblySource(path, expected); else bytes(path, expected);
  }
  for (const [path, expected] of Object.entries(witness.generatorSources))
    requireValue(execution.captureInputs[path] === expected, 'generator capture binding mismatch');
  requireValue(same(witness.sourcePomFiles.map(row => row.path).sort(), historical.sourcePomFiles.map(row => row.path).sort())
    && same(Object.keys(execution.sourcePomHashes).sort(), witness.sourcePomFiles.map(row => row.path).sort()),
  'source POM membership drift');
  const changed = [];
  for (const row of witness.sourcePomFiles) {
    if (row.path === 'pom.xml') readPreservedAssemblySource(row.path, row.candidateSha256);
    else bytes(row.path, row.candidateSha256);
    requireValue(execution.sourcePomHashes[row.path] === row.candidateSha256
      && historical.sourcePomFiles.find(prior => prior.path === row.path).candidateSha256 === row.baselineSha256,
    'source POM baseline/capture mismatch');
    if (row.candidateSha256 !== row.baselineSha256) changed.push(row.path);
  }
  const root = witness.sourcePomFiles.find(row => row.path === 'pom.xml');
  requireValue(same(changed, ['pom.xml']) && root.candidateSha256 === witness.rootPom.rawSha256
    && root.baselineSha256 === witness.baselineRootPom.rawSha256
    && sha(readPreservedJacocoAntrunSource('pom.xml', root.baselineSha256)) === root.baselineSha256,
  'exact source reversal mismatch');
  requireValue(execution.commands.filter(row => ['e2', 'owners'].includes(row.label)).length === 2
    && execution.commands.every(row => row.status === 'PASSED' && row.exitStatus === 0), 'capture commands incomplete');
  return witness;
}

/** The accepted receipt is immutable data; validate its bounded artifact closure once, without replaying ancestors. */
export function readAssemblyPlexusAcceptedLineage(manifest = readAssemblyPlexusManifest()) {
  validate(manifest); const lineage = pinned(manifest.base.lineage), inventory = pinned(manifest.base.artifactInventory);
  content(lineage, manifest.base.lineage.contentSha256, 'accepted lineage');
  content(inventory, manifest.base.artifactInventory.contentSha256, 'accepted artifact inventory');
  requireValue(lineage.schemaVersion === 'APPROVAL_JACOCO_ANTRUN_GRAPH_LINEAGE_V1' && lineage.repository === REPOSITORY
    && lineage.commitSha === HEAD && lineage.sourceE2ContentSha256 === manifest.base.e2.contentSha256
    && lineage.baseE2GraphDigest === BASE_GRAPH && lineage.currentE2GraphDigest === PRIOR_GRAPH
    && lineage.findingReviewRequired === true && lineage.releaseBlocked === true
    && inventory.schemaVersion === 'APPROVAL_ASSEMBLY_ACCEPTED_ARTIFACT_INVENTORY_V1'
    && inventory.repository === REPOSITORY && inventory.sourceHead === HEAD
    && inventory.sourceE2ContentSha256 === manifest.base.e2.contentSha256
    && same(inventory.lineage, manifest.base.lineage)
    && inventory.fileCount === inventory.files.length && inventory.fileCount > 0 && inventory.fileCount <= 200
    && new Set(inventory.files.map(row => row.path)).size === inventory.fileCount, 'accepted lineage/inventory identity mismatch');
  let total = 0;
  for (const row of inventory.files) {
    const raw = bytes(row.path, row.rawSha256);
    requireValue(raw.length === row.bytes, 'accepted artifact size mismatch'); total += raw.length;
  }
  requireValue(total === inventory.totalBytes && total <= 16 * 1024 * 1024, 'accepted artifact inventory size mismatch');
  // All predecessor POM expectations share this existing narrow reversal chain.
  // Keep its current-source and archived-byte checks, without historical graph callbacks.
  readPreservedCleanSource('pom.xml', 'e5637b8d5752474f9d1940f7b295ef0d3046bc6460391992f1936d4b306f40f2');
  return lineage;
}

/** Bind the accepted input bridge; current full-scanner/target/absence gates remain in the server verifier. */
export function readAssemblyPlexusAcceptedServerRemediation(manifest = readAssemblyPlexusManifest()) {
  validate(manifest); const receipt = pinned(manifest.base.serverRemediation);
  content(receipt, manifest.base.serverRemediation.contentSha256, 'accepted server remediation');
  requireValue(receipt.schemaVersion === 'APPROVAL_SERVER_DEPENDENCY_REMEDIATION_EVIDENCE_V2'
    && receipt.repository === REPOSITORY && receipt.commitSha === HEAD
    && receipt.sourceE2ContentSha256 === manifest.base.e2.contentSha256
    && receipt.currentE2GraphDigest === PRIOR_GRAPH && receipt.allScannersCompleted === true
    && receipt.findingReviewRequired === true && receipt.releaseBlocked === true
    && same(receipt.subsequentGraphTransition, readAssemblyPlexusAcceptedLineage(manifest)),
  'accepted server remediation binding mismatch'); return receipt;
}
export function verifyAssemblyPlexusDelta(projection, manifest = readAssemblyPlexusManifest()) {
  validate(manifest);
  const baseline = readAssemblyPlexusBaseline(manifest);
  const beforeRaw = bytes(manifest.base.pluginReport.path, manifest.base.pluginReport.rawSha256).toString('utf8');
  const afterRaw = readAssemblyPlexusReport(manifest), owners = verifyAssemblyPlexusOwnerSubstitution(beforeRaw, afterRaw);
  requireValue(same(owners.changes, manifest.pluginOwnerChanges)
    && hash(owners.before.owners) === manifest.previousOwnerInventorySha256
    && hash(owners.after.owners) === manifest.currentOwnerInventorySha256
    && same(manifest.addedPluginCoordinates, []) && manifest.addedPluginCoordinateCount === 0
    && same(manifest.removedPluginCoordinates, [OLD]) && manifest.removedPluginCoordinateCount === 1,
  'owner manifest mismatch');
  requireValue(hash(projection) === ASSEMBLY_PLEXUS_GRAPH, 'current graph drift');
  const previous = verifyAssemblyPlexusGraphSubstitution(projection, acceptedE2GraphProjection(baseline), beforeRaw, afterRaw);
  for (const [name, evidence] of [['prior', previous], ['current', projection]]) {
    const input = osvInputFromE2(evidence);
    requireValue(input.packageCount === manifest.osvInput[name].packageCount
      && sha(JSON.stringify(input.scannerInput)) === manifest.osvInput[name].inputBytesSha256, 'exact OSV input identity drift');
  }
  return previous;
}
export function verifyAssemblyPlexusCurrentEvidence(e2, manifest = readAssemblyPlexusManifest()) {
  validate(manifest); const candidate = readAssemblyPlexusCandidate(manifest);
  const { commitSha, contentSha256, ...payload } = e2;
  const { commitSha: base, contentSha256: capturedHash, ...captured } = candidate;
  requireValue(/^[0-9a-f]{40}$/.test(commitSha || '') && hash({ ...payload, commitSha }) === contentSha256
    && same(payload, captured), 'current E2 source/Actions/toolchain drift');
  readAssemblyPlexusSourceWitness(manifest);
}

/** Fresh final read is intentional: edits after admission cannot reuse an earlier successful check. */
export function assemblyPlexusReceipt(identity) {
  const manifest = readAssemblyPlexusManifest(), candidate = readAssemblyPlexusCandidate(manifest);
  const { contentSha256, ...candidatePayload } = candidate;
  const current = { ...candidatePayload, commitSha: identity.commitSha };
  requireValue(hash(current) === identity.contentSha256, 'receipt current E2 identity mismatch');
  verifyAssemblyPlexusCurrentEvidence({ ...current, contentSha256: identity.contentSha256 }, manifest);
  verifyAssemblyPlexusDelta(acceptedE2GraphProjection(candidate), manifest);
  const preserved = readAssemblyPlexusAcceptedLineage(manifest);
  const payload = {
    schemaVersion: 'APPROVAL_ASSEMBLY_PLEXUS_GRAPH_LINEAGE_V1',
    repository: REPOSITORY, commitSha: identity.commitSha, sourceE2ContentSha256: identity.contentSha256,
    baseE2GraphDigest: BASE_GRAPH, priorE2GraphDigest: PRIOR_GRAPH,
    currentE2GraphDigest: ASSEMBLY_PLEXUS_GRAPH, manifestSha256: ASSEMBLY_PLEXUS_MANIFEST_SHA256,
    sourceWitnessSha256: manifest.capture.sourceWitness.rawSha256,
    acceptedArtifactInventorySha256: manifest.base.artifactInventory.rawSha256,
    preservedJacocoAntrunLineage: preserved, archivalCandidate: manifest.capture,
    addedPluginCoordinateCount: 0, removedPluginCoordinateCount: 1,
    unchangedOtherPluginOwnerCount: 16, runtimeComponentChangeCount: 0, runtimeEdgeChangeCount: 0,
    importedBomChangeCount: 0, scopeChangeCount: 0, licenseChangeCount: 0,
    inventory: manifest.inventory, findingReviewRequired: true, releaseBlocked: true,
  };
  return { ...payload, contentSha256: hash(payload) };
}
