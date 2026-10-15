import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { acceptedE2GraphProjection } from './m6-pr-e-e2-generate-sbom.mjs';
import { parseCheckstylePluginOwners, verifyCheckstyleCaptureProvenance } from './checkstyle-plugin-graph-transition.mjs';
import { readPreservedAssemblySource } from './jar-plugin-source-continuation.mjs';
import { readPreservedCleanSource } from './compiler-plugin-source-continuation.mjs';
import { osvInputFromE2 } from './osv-scan-coverage.mjs';

// Sealed only from retained actual capture; an absent pin fails closed.
export const JAR_PLUGIN_GRAPH = '73825d80bd2a55d0507742bac096e73309100911d69692cd34dc753674bdb9c5';
export const JAR_PLUGIN_MANIFEST_SHA256 = 'bc674c02318cc1e217e9e041dc1ca894f186d40961f14a7a94672788ab6b8a9c';
const CONTENT = 'a70e0e2dd6c9a079abe44a82d40904a7b502eef1ca83fa2eb6bd77b1349eb1ba';
const PRIOR_GRAPH = '305364325fbd8a75f189fe50b708866e1f64cfc1a89f14847f67a087d131a94c';
const BASE_GRAPH = '2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94';
const HEAD = 'f1c99eaa998239044b164d32437a9f3c0765b321';
const TREE = 'bc30fc2b5c277e3b9cc7452af7616b56dd17868f';
// Independently retained from accepted main1862. The candidate sealing step does
// not select or regenerate these predecessor trust anchors.
const ACCEPTED = {
  "artifactInventory": [
    "c94a8c3c8087dbf2405ee7cdbcc097dc2acd9dc9860b8e95d762147718520d7e",
    "76c2c869605c91cc5bb15bd1bd1acea17ec7a71020d3a8b81b62f860f55c774d"
  ],
  "e2": [
    "7419ac55a1389e8b9f37c61cfa44e03e93851e66af56cf3fe7ad30b2b9f7fd24",
    "4de590bfba157701412bcef55bc5c6bd36ad244f483323ce87afface655703eb"
  ],
  "lineage": [
    "70994f05c7f06e25dc19d17e5068b9dc3fb227890f84ef5ac50536c4bd310f85",
    "e023394e7d33714bbfe1bceba72c5840b22e55ace9a4ffb6dee3fe4dd3bfcec6"
  ],
  "serverRemediation": [
    "f72b45ef7179d24c274e054f5a0036dc657bce2df9ab41c371dc8cb8712ad9b5",
    "0225b76ccf8d27a3130fe6cee1bf575af2c9a6262492af40302b8ca15b0f17e5"
  ]
};
const REPOSITORY = 'akaryc1b/approval-platform';
const OLD_OWNER = 'org.apache.maven.plugins:maven-jar-plugin:jar:3.5.0';
const NEW_OWNER = 'org.apache.maven.plugins:maven-jar-plugin:jar:3.5.1';
// The prior inventory is fixed independently; the actual candidate capture seals the new inventory.
const OWNER_BEFORE = [
  "com.github.luben:zstd-jni:jar:1.5.7-6",
  "commons-codec:commons-codec:jar:1.19.0",
  "commons-io:commons-io:jar:2.21.0",
  "io.airlift:aircompressor:jar:0.27",
  "javax.inject:javax.inject:jar:1",
  "org.apache.commons:commons-compress:jar:1.28.0",
  "org.apache.commons:commons-lang3:jar:3.18.0",
  "org.apache.maven.plugins:maven-jar-plugin:jar:3.5.0",
  "org.apache.maven.shared:file-management:jar:3.2.0",
  "org.apache.maven:maven-archiver:jar:3.6.5",
  "org.codehaus.plexus:plexus-archiver:jar:4.10.4",
  "org.codehaus.plexus:plexus-interpolation:jar:1.28",
  "org.codehaus.plexus:plexus-io:jar:3.6.0",
  "org.codehaus.plexus:plexus-utils:jar:4.0.2",
  "org.codehaus.plexus:plexus-xml:jar:3.0.1",
  "org.slf4j:slf4j-api:jar:1.7.36",
  "org.tukaani:xz:jar:1.10"
];
const OWNER_AFTER = [
  "com.github.luben:zstd-jni:jar:1.5.7-9",
  "commons-codec:commons-codec:jar:1.19.0",
  "commons-io:commons-io:jar:2.22.0",
  "javax.inject:javax.inject:jar:1",
  "org.apache.commons:commons-compress:jar:1.28.0",
  "org.apache.commons:commons-lang3:jar:3.18.0",
  "org.apache.maven.plugins:maven-jar-plugin:jar:3.5.1",
  "org.apache.maven.shared:file-management:jar:3.2.0",
  "org.apache.maven:maven-archiver:jar:3.6.6",
  "org.codehaus.plexus:plexus-archiver:jar:4.12.0",
  "org.codehaus.plexus:plexus-interpolation:jar:1.29",
  "org.codehaus.plexus:plexus-io:jar:3.6.0",
  "org.codehaus.plexus:plexus-utils:jar:4.0.3",
  "org.codehaus.plexus:plexus-xml:jar:3.0.1",
  "org.slf4j:slf4j-api:jar:1.7.36",
  "org.tukaani:xz:jar:1.12"
];
const SHA256 = /^[0-9a-f]{64}$/;
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const sha = value => createHash('sha256').update(value).digest('hex');
const hash = value => sha(canonical(value));
const same = (a, b) => canonical(a) === canonical(b);
const requireValue = (ok, message) => { if (!ok) throw new Error(`JAR ${message}`); };
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
  requireValue([JAR_PLUGIN_GRAPH, JAR_PLUGIN_MANIFEST_SHA256, CONTENT].every(value => SHA256.test(value)),
    'actual graph capture is pending; transition is unsealed');
  content(manifest, CONTENT, 'manifest');
  requireValue(manifest.schemaVersion === 'APPROVAL_JAR_PLUGIN_GRAPH_TRANSITION_V1'
    && manifest.repository === REPOSITORY && manifest.base.sourceHead === HEAD && manifest.base.sourceTree === TREE
    && manifest.base.sourceRunNumber === 1862 && manifest.base.graphDigest === PRIOR_GRAPH
    && manifest.base.sourceRunId === 38716770453
    && manifest.observed.graphDigest === JAR_PLUGIN_GRAPH
    && manifest.ownerCount === 17 && manifest.unchangedOtherPluginOwnerCount === 16
    && manifest.runtimeComponentChangeCount === 0 && manifest.runtimeEdgeChangeCount === 0
    && manifest.importedBomChangeCount === 0 && manifest.scopeChangeCount === 0 && manifest.licenseChangeCount === 0
    && manifest.releaseBlocked === true && manifest.findingReviewRequired === true
    && same(manifest.suppressions, []) && same(manifest.exceptions, [])
    && manifest.capture.sourceBaseHead === HEAD && manifest.capture.sourceBaseTree === TREE
    && manifest.capture.exactHeadEvidence === false && manifest.capture.sourceWorktreeClean === false,
  'transition identity mismatch');
  for (const [name, expected] of Object.entries(ACCEPTED)) requireValue(
    same([manifest.base[name].rawSha256, manifest.base[name].contentSha256], expected), 'accepted main1862 trust anchor mismatch');
}

/** Pure bounded owner comparison. The sole report occurrence exception is JAR 24 → 26. */
export function verifyJarPluginOwnerSubstitution(beforeRaw, afterRaw) {
  const before = parseCheckstylePluginOwners(beforeRaw), after = parseCheckstylePluginOwners(afterRaw);
  const names = Object.keys(before.owners).sort(), currentNames = Object.keys(after.owners).sort();
  requireValue(names.length === 17 && names.includes(OLD_OWNER) && !names.includes(NEW_OWNER)
    && same(currentNames, names.map(name => name === OLD_OWNER ? NEW_OWNER : name).sort()), 'owner membership drift');
  requireValue(Array.isArray(OWNER_AFTER) && same(before.owners[OLD_OWNER], OWNER_BEFORE)
    && same(after.owners[NEW_OWNER], OWNER_AFTER), 'JAR owner dependency capture is unsealed or changed');
  requireValue(before.occurrences[OLD_OWNER] === 24 && after.occurrences[NEW_OWNER] === 26,
    'JAR owner occurrence drift outside declared 24 to 26 report change');
  for (const name of names.filter(name => name !== OLD_OWNER)) requireValue(
    same(before.owners[name], after.owners[name]) && before.occurrences[name] === after.occurrences[name],
    'unrelated owner or occurrence changed');
  return { before, after, ownerCount: 17, unchangedOtherPluginOwnerCount: 16,
    changes: [{ previousOwner: OLD_OWNER, currentOwner: NEW_OWNER,
      previousOccurrences: 24, currentOccurrences: 26, before: before.owners[OLD_OWNER], after: after.owners[NEW_OWNER] }] };
}
const union = report => [...new Set(Object.values(report.owners).flat())].sort();

/** Reverse exactly the owner-derived union changes and report digest, preserving every other graph field. */
export function verifyJarPluginGraphSubstitution(projection, baseline, beforeRaw, afterRaw) {
  const result = verifyJarPluginOwnerSubstitution(beforeRaw, afterRaw);
  const previousUnion = union(result.before), currentUnion = union(result.after);
  requireValue(same(baseline.maven.resolvedPluginCoordinates, previousUnion)
    && same(projection.maven.resolvedPluginCoordinates, currentUnion)
    && baseline.maven.pluginResolutionSha256 === sha(beforeRaw)
    && projection.maven.pluginResolutionSha256 === sha(afterRaw), 'global owner union/report mismatch');
  const added = currentUnion.filter(value => !previousUnion.includes(value));
  const removed = previousUnion.filter(value => !currentUnion.includes(value));
  const previous = structuredClone(projection);
  previous.maven.resolvedPluginCoordinates = previous.maven.resolvedPluginCoordinates.filter(value => !added.includes(value)).concat(removed).sort();
  previous.maven.pluginResolutionSha256 = sha(beforeRaw);
  requireValue(same(previous, baseline), 'undeclared graph change'); return previous;
}

// Reading a manifest only checks that manifest. Callers pass it through one bounded operation.
// No helper defaults to full admission, and no process-wide validation result is retained.
export function readJarPluginManifest() {
  const manifest = pinned({ path: 'docs/operations/jar-plugin-transition.json', rawSha256: JAR_PLUGIN_MANIFEST_SHA256 });
  validate(manifest); return manifest;
}
export function readJarPluginBaseline(manifest = readJarPluginManifest()) {
  validate(manifest); const e2 = pinned(manifest.base.e2); content(e2, manifest.base.e2.contentSha256, 'baseline');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === HEAD
    && hash(acceptedE2GraphProjection(e2)) === PRIOR_GRAPH, 'accepted baseline mismatch'); return e2;
}
export function readJarPluginCandidate(manifest = readJarPluginManifest()) {
  validate(manifest); const e2 = pinned(manifest.capture.e2); content(e2, manifest.capture.e2.contentSha256, 'candidate');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === HEAD
    && hash(acceptedE2GraphProjection(e2)) === JAR_PLUGIN_GRAPH, 'candidate mismatch'); return e2;
}
export function readJarPluginReport(manifest = readJarPluginManifest()) {
  validate(manifest); return bytes(manifest.capture.pluginReport.path, manifest.capture.pluginReport.rawSha256).toString('utf8');
}
export function readJarPluginSourceWitness(manifest = readJarPluginManifest()) {
  validate(manifest); const witness = pinned(manifest.capture.sourceWitness);
  content(witness, manifest.capture.sourceWitness.contentSha256, 'source witness');
  const execution = pinned(witness.captureExecution), historical = pinned(manifest.base.sourceWitness);
  content(historical, manifest.base.sourceWitness.contentSha256, 'accepted source witness');
  verifyCheckstyleCaptureProvenance(execution);
  requireValue(witness.schemaVersion === 'APPROVAL_JAR_PLUGIN_SOURCE_WITNESS_V1'
    && witness.repository === REPOSITORY && witness.sourceBaseHead === HEAD && witness.sourceBaseTree === TREE
    && execution.schemaVersion === 'APPROVAL_JAR_PLUGIN_CAPTURE_EXECUTION_V1'
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
    && sha(readPreservedAssemblySource('pom.xml', root.baselineSha256)) === root.baselineSha256,
  'exact source reversal mismatch');
  const modelExecution = pinned(witness.modelCaptureExecution), modelComparison = pinned(witness.modelComparison);
  verifyCheckstyleCaptureProvenance(modelExecution);
  const modelRuntimeInputs = pinned(witness.modelRuntimeInputs);
  bytes(witness.modelContract.path, witness.modelContract.rawSha256);
  requireValue(modelExecution.runtimeInputManifestSha256 === witness.modelRuntimeInputs.rawSha256
    && modelRuntimeInputs[witness.modelContract.capturedPath] === witness.modelContract.rawSha256,
  'paired model contract/runtime binding mismatch');
  requireValue(modelExecution.schemaVersion === 'APPROVAL_JAR_PLUGIN_CAPTURE_EXECUTION_V1'
    && modelExecution.sourceHead === HEAD && modelExecution.sourceTree === TREE && modelExecution.offline === true
    && same(modelExecution.sourcePomHashes, execution.sourcePomHashes)
    && modelExecution.collectorSha256 === witness.collector.rawSha256
    && same(modelExecution.modelComparison, modelComparison)
    && same(modelComparison, { modelCount: 26, managedJarModelCount: 26, activeJarModelCount: 24,
      jarExecutionCount: 24, allOtherModelFieldsUnchanged: true, nonReactorOverlaysSourceBoundOnly: 2 }),
  'paired model proof must retain exactly 24 JAR lifecycle bindings');
  requireValue(same(Object.keys(modelExecution.captureInputs).sort(),
    [...Object.keys(execution.captureInputs), 'scripts/ci/checkstyle_plugin_contract.py', 'scripts/ci/clean_plugin_contract.py'].sort()),
  'paired model input closure mismatch');
  for (const [path, expected] of Object.entries(modelExecution.captureInputs)) bytes(path, expected);
  for (const [path, expected] of Object.entries(execution.captureInputs))
    requireValue(modelExecution.captureInputs[path] === expected, 'paired model source binding mismatch');
  for (const label of ['baseline', 'candidate']) {
    bytes(witness.effectiveModels[label].path, witness.effectiveModels[label].rawSha256);
    requireValue(modelExecution.effectiveModelHashes[label] === witness.effectiveModels[label].rawSha256,
      'paired model byte binding mismatch');
  }
  requireValue(modelExecution.commands.filter(row => ['baseline-models', 'candidate-models'].includes(row.label)).length === 2
    && modelExecution.commands.every(row => row.status === 'PASSED' && row.exitStatus === 0), 'paired model commands incomplete');
  requireValue(execution.commands.filter(row => ['e2', 'owners'].includes(row.label)).length === 2
    && execution.commands.every(row => row.status === 'PASSED' && row.exitStatus === 0), 'capture commands incomplete');
  return witness;
}

/** The accepted receipt is immutable data; validate its bounded artifact closure once, without replaying ancestors. */
export function readJarPluginAcceptedLineage(manifest = readJarPluginManifest()) {
  validate(manifest); const lineage = pinned(manifest.base.lineage), inventory = pinned(manifest.base.artifactInventory);
  content(lineage, manifest.base.lineage.contentSha256, 'accepted lineage');
  content(inventory, manifest.base.artifactInventory.contentSha256, 'accepted artifact inventory');
  // Actual main1862 E4 extraction and embedded-E2/R4 equivalence were independently
  // verified before these accepted pins were fixed. The complete E4 remains retained externally.
  requireValue(lineage.schemaVersion === 'APPROVAL_ASSEMBLY_PLEXUS_GRAPH_LINEAGE_V1' && lineage.repository === REPOSITORY
    && lineage.commitSha === HEAD && lineage.sourceE2ContentSha256 === manifest.base.e2.contentSha256
    && lineage.baseE2GraphDigest === BASE_GRAPH && lineage.currentE2GraphDigest === PRIOR_GRAPH
    && lineage.findingReviewRequired === true && lineage.releaseBlocked === true
    && inventory.schemaVersion === 'APPROVAL_JAR_ACCEPTED_ARTIFACT_INVENTORY_V1'
    && inventory.repository === REPOSITORY && inventory.sourceHead === HEAD
    && inventory.sourceE2ContentSha256 === manifest.base.e2.contentSha256
    && same(inventory.lineage, manifest.base.lineage) && inventory.inheritedFileCount === 137
    && inventory.fileCount === inventory.files.length && inventory.fileCount === 152
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
export function readJarPluginAcceptedServerRemediation(manifest = readJarPluginManifest()) {
  validate(manifest); const receipt = pinned(manifest.base.serverRemediation);
  content(receipt, manifest.base.serverRemediation.contentSha256, 'accepted server remediation');
  requireValue(receipt.schemaVersion === 'APPROVAL_SERVER_DEPENDENCY_REMEDIATION_EVIDENCE_V2'
    && receipt.repository === REPOSITORY && receipt.commitSha === HEAD
    && receipt.sourceE2ContentSha256 === manifest.base.e2.contentSha256
    && receipt.currentE2GraphDigest === PRIOR_GRAPH && receipt.allScannersCompleted === true
    && receipt.findingReviewRequired === true && receipt.releaseBlocked === true
    && same(receipt.subsequentGraphTransition, readJarPluginAcceptedLineage(manifest)),
  'accepted server remediation binding mismatch'); return receipt;
}
export function verifyJarPluginDelta(projection, manifest = readJarPluginManifest()) {
  validate(manifest);
  const baseline = readJarPluginBaseline(manifest);
  const beforeRaw = bytes(manifest.base.pluginReport.path, manifest.base.pluginReport.rawSha256).toString('utf8');
  const afterRaw = readJarPluginReport(manifest), owners = verifyJarPluginOwnerSubstitution(beforeRaw, afterRaw);
  requireValue(same(owners.changes, manifest.pluginOwnerChanges)
    && hash(owners.before.owners) === manifest.previousOwnerInventorySha256
    && hash(owners.after.owners) === manifest.currentOwnerInventorySha256
    && same(manifest.addedPluginCoordinates, union(owners.after).filter(value => !union(owners.before).includes(value)))
    && manifest.addedPluginCoordinateCount === manifest.addedPluginCoordinates.length
    && same(manifest.removedPluginCoordinates, union(owners.before).filter(value => !union(owners.after).includes(value)))
    && manifest.removedPluginCoordinateCount === manifest.removedPluginCoordinates.length,
  'owner manifest mismatch');
  requireValue(same(manifest.pluginResolutionHashChange, { from: sha(beforeRaw), to: sha(afterRaw) })
    && same(manifest.inventory, { componentCount: projection.maven.components.length, edgeCount: projection.maven.edges.length,
      reactorRootCount: projection.maven.reactorRoots.length, importedBomCount: projection.maven.importedBoms.length,
      resolvedPluginCoordinateCount: projection.maven.resolvedPluginCoordinates.length })
    && projection.maven.components.length === 237 && projection.maven.edges.length === 345
    && projection.maven.reactorRoots.length === 26 && projection.maven.importedBoms.length === 6, 'declared capture inventory mismatch');
  requireValue(hash(projection) === JAR_PLUGIN_GRAPH, 'current graph drift');
  const previous = verifyJarPluginGraphSubstitution(projection, acceptedE2GraphProjection(baseline), beforeRaw, afterRaw);
  for (const [name, evidence] of [['prior', previous], ['current', projection]]) {
    const input = osvInputFromE2(evidence);
    requireValue(input.packageCount === manifest.osvInput[name].packageCount
      && sha(JSON.stringify(input.scannerInput)) === manifest.osvInput[name].inputBytesSha256, 'exact OSV input identity drift');
  }
  return previous;
}
export function verifyJarPluginCurrentEvidence(e2, manifest = readJarPluginManifest()) {
  validate(manifest); const candidate = readJarPluginCandidate(manifest);
  const { commitSha, contentSha256, ...payload } = e2;
  const { commitSha: base, contentSha256: capturedHash, ...captured } = candidate;
  requireValue(/^[0-9a-f]{40}$/.test(commitSha || '') && hash({ ...payload, commitSha }) === contentSha256
    && same(payload, captured), 'current E2 source/Actions/toolchain drift');
  readJarPluginSourceWitness(manifest);
}

/** Fresh final read is intentional: edits after admission cannot reuse an earlier successful check. */
export function jarPluginReceipt(identity) {
  const manifest = readJarPluginManifest(), candidate = readJarPluginCandidate(manifest);
  const { contentSha256, ...candidatePayload } = candidate;
  const current = { ...candidatePayload, commitSha: identity.commitSha };
  requireValue(hash(current) === identity.contentSha256, 'receipt current E2 identity mismatch');
  verifyJarPluginCurrentEvidence({ ...current, contentSha256: identity.contentSha256 }, manifest);
  verifyJarPluginDelta(acceptedE2GraphProjection(candidate), manifest);
  const preserved = readJarPluginAcceptedLineage(manifest);
  const payload = {
    schemaVersion: 'APPROVAL_JAR_PLUGIN_GRAPH_LINEAGE_V1',
    repository: REPOSITORY, commitSha: identity.commitSha, sourceE2ContentSha256: identity.contentSha256,
    baseE2GraphDigest: BASE_GRAPH, priorE2GraphDigest: PRIOR_GRAPH,
    currentE2GraphDigest: JAR_PLUGIN_GRAPH, manifestSha256: JAR_PLUGIN_MANIFEST_SHA256,
    sourceWitnessSha256: manifest.capture.sourceWitness.rawSha256,
    acceptedArtifactInventorySha256: manifest.base.artifactInventory.rawSha256,
    preservedAssemblyLineage: preserved, archivalCandidate: manifest.capture,
    addedPluginCoordinateCount: manifest.addedPluginCoordinateCount, removedPluginCoordinateCount: manifest.removedPluginCoordinateCount,
    unchangedOtherPluginOwnerCount: 16, runtimeComponentChangeCount: 0, runtimeEdgeChangeCount: 0,
    importedBomChangeCount: 0, scopeChangeCount: 0, licenseChangeCount: 0,
    inventory: manifest.inventory, findingReviewRequired: true, releaseBlocked: true,
  };
  return { ...payload, contentSha256: hash(payload) };
}
