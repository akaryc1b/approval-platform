import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { acceptedE2GraphProjection } from './m6-pr-e-e2-generate-sbom.mjs';
import { parseCheckstylePluginOwners, verifyCheckstyleCaptureProvenance } from './checkstyle-plugin-graph-transition.mjs';
import { readPreservedJarSource } from './jar-plugin-source-continuation.mjs';
import { osvInputFromE2 } from './osv-scan-coverage.mjs';

// Candidate pins bind retained actual captures. Accepted-main anchors below are
// independently fixed and are never selected or rewritten by candidate sealing.
export const ASSEMBLY_ENFORCER_LANG_GRAPH = 'c57035ab083aa9832d8d69b4dab2008f65d922ddec0edeb5b93742a10754f97d';
export const ASSEMBLY_ENFORCER_LANG_MANIFEST_SHA256 = '0022fed426ea61c886284b07a14b1b12df0d10ededfe76b891ee00f5cbc42120';
const CONTENT = '0f2c21a68066e6259b9a971595afc3673d8ef1e6d475427e259b49904d15d5ac';
const PRIOR_GRAPH = '73825d80bd2a55d0507742bac096e73309100911d69692cd34dc753674bdb9c5';
const BASE_GRAPH = '2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94';
const HEAD = '9e3489f2aef3ffb36508367591bc0d77ceb99bd1';
const TREE = 'e92e5cc524d4642dfbd4ddc9ba6980bd01adf6b2';
const ACCEPTED = {
  artifactInventory: ['aa255f37426944e075450d81bba492791decdc31fc6641af464d1a8d5df5d0f4',
    'a48a3cf3101abb1ce1822d37fe481eaeb5fab306d87567a345fae8325b2e54db'],
  e2: ['858adce49f6911cd8be21d9a22ef11e9368472182cf07b48f42980c0c9efbb07',
    '34ff00b96a5a2b5d1ce058d782292244bbfb9fee8677ed7d86e2acaa6dfeb3fc'],
  lineage: ['ce8b5417e522f0f7812842c09c84ab940c9a36214eb895f4089455a856283364',
    'ed72675193294f5eea39b351e80b1c061ce51136396e4b81cfdb0fc029f0227e'],
  serverRemediation: ['e6cb8e1ac51accea72444bf178661294955a3bb403d4421f7c9b940cda1b7ef1',
    '9e698ed3366bdaa3de9926911974b0eeef759bbd0c3912c39a9e56e5ffb9a471'],
};
const REPOSITORY = 'akaryc1b/approval-platform';
const OWNERS = ['org.apache.maven.plugins:maven-assembly-plugin:jar:3.7.1',
  'org.apache.maven.plugins:maven-enforcer-plugin:jar:3.5.0'];
const OLD = 'org.apache.commons:commons-lang3:jar:3.14.0';
const NEW = 'org.apache.commons:commons-lang3:jar:3.18.0';
const SHA256 = /^[0-9a-f]{64}$/;
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const sha = value => createHash('sha256').update(value).digest('hex');
const hash = value => sha(canonical(value));
const same = (a, b) => canonical(a) === canonical(b);
const requireValue = (ok, message) => { if (!ok) throw new Error(`Assembly/Enforcer Lang ${message}`); };
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
  requireValue([ASSEMBLY_ENFORCER_LANG_GRAPH, ASSEMBLY_ENFORCER_LANG_MANIFEST_SHA256, CONTENT].every(value => SHA256.test(value)),
    'actual graph capture is pending; transition is unsealed');
  content(manifest, CONTENT, 'manifest');
  requireValue(manifest.schemaVersion === 'APPROVAL_ASSEMBLY_ENFORCER_LANG_GRAPH_TRANSITION_V1'
    && manifest.repository === REPOSITORY && manifest.base.sourceHead === HEAD && manifest.base.sourceTree === TREE
    && manifest.base.sourceRunNumber === 1868 && manifest.base.graphDigest === PRIOR_GRAPH
    && manifest.base.sourceRunId === 38835342453
    && manifest.observed.graphDigest === ASSEMBLY_ENFORCER_LANG_GRAPH
    && manifest.ownerCount === 17 && manifest.unchangedOtherPluginOwnerCount === 15
    && manifest.runtimeComponentChangeCount === 0 && manifest.runtimeEdgeChangeCount === 0
    && manifest.importedBomChangeCount === 0 && manifest.scopeChangeCount === 0 && manifest.licenseChangeCount === 0
    && manifest.releaseBlocked === true && manifest.findingReviewRequired === true
    && same(manifest.suppressions, []) && same(manifest.exceptions, [])
    && manifest.capture.sourceBaseHead === HEAD && manifest.capture.sourceBaseTree === TREE
    && manifest.capture.exactHeadEvidence === false && manifest.capture.sourceWorktreeClean === false,
  'transition identity mismatch');
  for (const [name, expected] of Object.entries(ACCEPTED)) requireValue(
    same([manifest.base[name].rawSha256, manifest.base[name].contentSha256], expected), 'accepted main1868 trust anchor mismatch');
}

/** Compare all 17 inventories and occurrences; exactly two private Lang replacements. */
export function verifyAssemblyEnforcerLangOwnerSubstitution(beforeRaw, afterRaw) {
  const before = parseCheckstylePluginOwners(beforeRaw), after = parseCheckstylePluginOwners(afterRaw);
  const names = Object.keys(before.owners).sort();
  requireValue(names.length === 17 && same(names, Object.keys(after.owners).sort())
    && same(before.occurrences, after.occurrences), 'owner membership/count drift');
  const changed = names.filter(name => !same(before.owners[name], after.owners[name]));
  requireValue(same(changed, OWNERS), 'unrelated owner or missing replacement');
  for (const owner of OWNERS) requireValue(before.occurrences[owner] === 26
    && before.owners[owner].includes(OLD) && !before.owners[owner].includes(NEW)
    && same(after.owners[owner], before.owners[owner].map(value => value === OLD ? NEW : value).sort()),
  'owner dependency replacement drift');
  return { before, after, ownerCount: 17, unchangedOtherPluginOwnerCount: 15,
    changes: OWNERS.map(owner => ({ owner, previousOccurrences: 26, currentOccurrences: 26,
      before: before.owners[owner], after: after.owners[owner] })) };
}
const union = report => [...new Set(Object.values(report.owners).flat())].sort();

/** Reverse exactly the owner-derived union changes and report digest, preserving every other graph field. */
export function verifyAssemblyEnforcerLangGraphSubstitution(projection, baseline, beforeRaw, afterRaw) {
  const result = verifyAssemblyEnforcerLangOwnerSubstitution(beforeRaw, afterRaw);
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
export function readAssemblyEnforcerLangManifest() {
  const manifest = pinned({ path: 'docs/operations/assembly-enforcer-lang-transition.json', rawSha256: ASSEMBLY_ENFORCER_LANG_MANIFEST_SHA256 });
  validate(manifest); return manifest;
}
export function readAssemblyEnforcerLangBaseline(manifest = readAssemblyEnforcerLangManifest()) {
  validate(manifest); const e2 = pinned(manifest.base.e2); content(e2, manifest.base.e2.contentSha256, 'baseline');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === HEAD
    && hash(acceptedE2GraphProjection(e2)) === PRIOR_GRAPH, 'accepted baseline mismatch'); return e2;
}
export function readAssemblyEnforcerLangCandidate(manifest = readAssemblyEnforcerLangManifest()) {
  validate(manifest); const e2 = pinned(manifest.capture.e2); content(e2, manifest.capture.e2.contentSha256, 'candidate');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === HEAD
    && hash(acceptedE2GraphProjection(e2)) === ASSEMBLY_ENFORCER_LANG_GRAPH, 'candidate mismatch'); return e2;
}
export function readAssemblyEnforcerLangReport(manifest = readAssemblyEnforcerLangManifest()) {
  validate(manifest); return bytes(manifest.capture.pluginReport.path, manifest.capture.pluginReport.rawSha256).toString('utf8');
}
export function readAssemblyEnforcerLangSourceWitness(manifest = readAssemblyEnforcerLangManifest()) {
  validate(manifest); const witness = pinned(manifest.capture.sourceWitness);
  content(witness, manifest.capture.sourceWitness.contentSha256, 'source witness');
  const execution = pinned(witness.captureExecution), historical = pinned(manifest.base.sourceWitness);
  content(historical, manifest.base.sourceWitness.contentSha256, 'accepted source witness');
  verifyCheckstyleCaptureProvenance(execution);
  requireValue(witness.schemaVersion === 'APPROVAL_ASSEMBLY_ENFORCER_LANG_SOURCE_WITNESS_V1'
    && witness.repository === REPOSITORY && witness.sourceBaseHead === HEAD && witness.sourceBaseTree === TREE
    && execution.schemaVersion === 'APPROVAL_ASSEMBLY_ENFORCER_LANG_CAPTURE_EXECUTION_V1'
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
    && execution.sourcePomSha256 === witness.rootPom.rawSha256
    && Object.keys(execution.captureInputs).length === 55, 'capture report/E2/source mismatch');
  requireValue(same(witness.generatorSources, historical.generatorSources)
    && same(execution.generatorSources, historical.generatorSources), 'unchanged generator closure mismatch');
  for (const [path, expected] of Object.entries(execution.captureInputs)) {
    bytes(path, expected);
  }
  for (const [path, expected] of Object.entries(witness.generatorSources))
    requireValue(execution.captureInputs[path] === expected, 'generator capture binding mismatch');
  requireValue(same(witness.sourcePomFiles.map(row => row.path).sort(), historical.sourcePomFiles.map(row => row.path).sort())
    && same(Object.keys(execution.sourcePomHashes).sort(), witness.sourcePomFiles.map(row => row.path).sort()),
  'source POM membership drift');
  const changed = [];
  for (const row of witness.sourcePomFiles) {
    bytes(row.path, row.candidateSha256);
    requireValue(execution.sourcePomHashes[row.path] === row.candidateSha256
      && execution.captureInputs[row.path] === row.candidateSha256
      && historical.sourcePomFiles.find(prior => prior.path === row.path).candidateSha256 === row.baselineSha256,
    'source POM baseline/capture mismatch');
    if (row.candidateSha256 !== row.baselineSha256) changed.push(row.path);
  }
  const root = witness.sourcePomFiles.find(row => row.path === 'pom.xml');
  requireValue(same(changed, ['pom.xml']) && root.candidateSha256 === witness.rootPom.rawSha256
    && root.baselineSha256 === witness.baselineRootPom.rawSha256
    && sha(readPreservedJarSource('pom.xml', root.baselineSha256)) === root.baselineSha256,
  'exact source reversal mismatch');
  const modelExecution = pinned(witness.modelCaptureExecution), modelComparison = pinned(witness.modelComparison);
  verifyCheckstyleCaptureProvenance(modelExecution);
  const modelRuntimeInputs = pinned(witness.modelRuntimeInputs);
  bytes(witness.modelContract.path, witness.modelContract.rawSha256);
  bytes(witness.graphComparator.path, witness.graphComparator.rawSha256);
  bytes(witness.cacheInputs.path, witness.cacheInputs.rawSha256);
  requireValue(modelExecution.runtimeInputManifestSha256 === witness.modelRuntimeInputs.rawSha256
    && execution.runtimeInputManifestSha256 === witness.modelRuntimeInputs.rawSha256
    && execution.cacheInputManifestSha256 === witness.cacheInputs.rawSha256
    && modelExecution.cacheInputManifestSha256 === witness.cacheInputs.rawSha256
    && modelRuntimeInputs[witness.modelContract.capturedPath] === witness.modelContract.rawSha256
    && modelRuntimeInputs[witness.graphComparator.capturedPath] === witness.graphComparator.rawSha256,
  'paired model contract/runtime binding mismatch');
  requireValue(modelExecution.schemaVersion === 'APPROVAL_ASSEMBLY_ENFORCER_LANG_CAPTURE_EXECUTION_V1'
    && modelExecution.sourceHead === HEAD && modelExecution.sourceTree === TREE && modelExecution.offline === true
    && same(modelExecution.sourcePomHashes, execution.sourcePomHashes)
    && modelExecution.collectorSha256 === witness.collector.rawSha256
    && same(modelExecution.modelComparison, modelComparison)
    && same(modelComparison, { modelCount: 26, ownerModelCounts: { 'maven-assembly-plugin': 26, 'maven-enforcer-plugin': 26 },
      allOtherModelFieldsUnchanged: true, nonReactorOverlaysSourceBoundOnly: 2 }),
  'paired model proof must retain both 26-model owner inventories');
  requireValue(same(Object.keys(modelExecution.captureInputs).sort(),
    [...Object.keys(execution.captureInputs), 'scripts/ci/checkstyle_plugin_contract.py', 'scripts/ci/clean_plugin_contract.py'].sort()),
  'paired model input closure mismatch');
  for (const [path, expected] of Object.entries(modelExecution.captureInputs)) {
    bytes(path, expected);
  }
  for (const [path, expected] of Object.entries(execution.captureInputs))
    requireValue(modelExecution.captureInputs[path] === expected, 'paired model source binding mismatch');
  const sourceProjection = pinned(witness.modelSourceProjection);
  const suppliedSourceHashes = { ...execution.sourcePomHashes,
    'config/checkstyle/checkstyle.xml': execution.captureInputs['config/checkstyle/checkstyle.xml'],
    'docs/operations/assembly-plexus-evidence/source-witness.json':
      execution.captureInputs['docs/operations/assembly-plexus-evidence/source-witness.json'] };
  requireValue(sourceProjection.schemaVersion === 'ASSEMBLY_ENFORCER_LANG_RETAINED_SOURCE_VIEW_V1'
    && sourceProjection.adapterSha256 === witness.modelContract.rawSha256
    && sourceProjection.suppliedRootPomSha256 === root.candidateSha256
    && sourceProjection.retainedRootPomSha256 === root.baselineSha256
    && same(sourceProjection.suppliedSourceHashes, suppliedSourceHashes)
    && same(sourceProjection.retainedSourceHashes, { ...suppliedSourceHashes, 'pom.xml': root.baselineSha256 }),
  'paired model exact source projection mismatch');
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
export function readAssemblyEnforcerLangAcceptedLineage(manifest = readAssemblyEnforcerLangManifest()) {
  validate(manifest); const lineage = pinned(manifest.base.lineage), inventory = pinned(manifest.base.artifactInventory);
  content(lineage, manifest.base.lineage.contentSha256, 'accepted lineage');
  content(inventory, manifest.base.artifactInventory.contentSha256, 'accepted artifact inventory');
  // Actual main1868 E4 extraction and embedded-E2/R4 equivalence were independently
  // verified before these accepted pins were fixed. The complete E4 remains retained externally.
  requireValue(lineage.schemaVersion === 'APPROVAL_JAR_PLUGIN_GRAPH_LINEAGE_V1' && lineage.repository === REPOSITORY
    && lineage.commitSha === HEAD && lineage.sourceE2ContentSha256 === manifest.base.e2.contentSha256
    && lineage.baseE2GraphDigest === BASE_GRAPH && lineage.currentE2GraphDigest === PRIOR_GRAPH
    && lineage.findingReviewRequired === true && lineage.releaseBlocked === true
    && inventory.schemaVersion === 'APPROVAL_ASSEMBLY_ENFORCER_LANG_ACCEPTED_ARTIFACT_INVENTORY_V1'
    && inventory.repository === REPOSITORY && inventory.sourceHead === HEAD
    && inventory.sourceE2ContentSha256 === manifest.base.e2.contentSha256
    && same(inventory.lineage, manifest.base.lineage) && inventory.inheritedFileCount === 152
    && inventory.fileCount === inventory.files.length && inventory.fileCount === 171
    && new Set(inventory.files.map(row => row.path)).size === inventory.fileCount, 'accepted lineage/inventory identity mismatch');
  let total = 0;
  for (const row of inventory.files) {
    const raw = bytes(row.path, row.rawSha256);
    requireValue(raw.length === row.bytes, 'accepted artifact size mismatch'); total += raw.length;
  }
  requireValue(total === inventory.totalBytes && total === 30_554_782 && total <= 32 * 1024 * 1024, 'accepted artifact inventory size mismatch');
  // Recheck the actual current root and retained JAR archive without ancestor admission.
  readPreservedJarSource('pom.xml', '92694a65be5d42493864470184469b465df8107bfd98388b8b0db15292c54c7f');
  return lineage;
}

/** Bind the accepted input bridge; current full-scanner/target/absence gates remain in the server verifier. */
export function readAssemblyEnforcerLangAcceptedServerRemediation(manifest = readAssemblyEnforcerLangManifest()) {
  validate(manifest); const receipt = pinned(manifest.base.serverRemediation);
  content(receipt, manifest.base.serverRemediation.contentSha256, 'accepted server remediation');
  requireValue(receipt.schemaVersion === 'APPROVAL_SERVER_DEPENDENCY_REMEDIATION_EVIDENCE_V2'
    && receipt.repository === REPOSITORY && receipt.commitSha === HEAD
    && receipt.sourceE2ContentSha256 === manifest.base.e2.contentSha256
    && receipt.currentE2GraphDigest === PRIOR_GRAPH && receipt.allScannersCompleted === true
    && receipt.findingReviewRequired === true && receipt.releaseBlocked === true
    && same(receipt.subsequentGraphTransition, readAssemblyEnforcerLangAcceptedLineage(manifest)),
  'accepted server remediation binding mismatch'); return receipt;
}
export function verifyAssemblyEnforcerLangDelta(projection, manifest = readAssemblyEnforcerLangManifest()) {
  validate(manifest);
  const baseline = readAssemblyEnforcerLangBaseline(manifest);
  const beforeRaw = bytes(manifest.base.pluginReport.path, manifest.base.pluginReport.rawSha256).toString('utf8');
  const afterRaw = readAssemblyEnforcerLangReport(manifest), owners = verifyAssemblyEnforcerLangOwnerSubstitution(beforeRaw, afterRaw);
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
  requireValue(hash(projection) === ASSEMBLY_ENFORCER_LANG_GRAPH, 'current graph drift');
  const previous = verifyAssemblyEnforcerLangGraphSubstitution(projection, acceptedE2GraphProjection(baseline), beforeRaw, afterRaw);
  for (const [name, evidence] of [['prior', previous], ['current', projection]]) {
    const input = osvInputFromE2(evidence);
    requireValue(input.packageCount === manifest.osvInput[name].packageCount
      && sha(JSON.stringify(input.scannerInput)) === manifest.osvInput[name].inputBytesSha256, 'exact OSV input identity drift');
  }
  return previous;
}
export function verifyAssemblyEnforcerLangCurrentEvidence(e2, manifest = readAssemblyEnforcerLangManifest()) {
  validate(manifest); const candidate = readAssemblyEnforcerLangCandidate(manifest);
  const { commitSha, contentSha256, ...payload } = e2;
  const { commitSha: base, contentSha256: capturedHash, ...captured } = candidate;
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY
    && /^[0-9a-f]{40}$/.test(commitSha || '') && hash({ ...payload, commitSha }) === contentSha256
    && same(payload, captured), 'current E2 source/Actions/toolchain drift');
  readAssemblyEnforcerLangSourceWitness(manifest);
}

/** Fresh final read is intentional: edits after admission cannot reuse an earlier successful check. */
export function assemblyEnforcerLangReceipt(identity) {
  const manifest = readAssemblyEnforcerLangManifest(), candidate = readAssemblyEnforcerLangCandidate(manifest);
  const { contentSha256, ...candidatePayload } = candidate;
  const current = { ...candidatePayload, commitSha: identity.commitSha };
  requireValue(hash(current) === identity.contentSha256, 'receipt current E2 identity mismatch');
  // Admission passes the full E2; preserved-receipt verification supplies only its identity.
  // In either case the identity must reconstruct the exact full captured E2 at this head.
  const evidence = Object.keys(identity).length === 2 ? { ...current, contentSha256: identity.contentSha256 } : identity;
  verifyAssemblyEnforcerLangCurrentEvidence(evidence, manifest);
  verifyAssemblyEnforcerLangDelta(acceptedE2GraphProjection(candidate), manifest);
  const preserved = readAssemblyEnforcerLangAcceptedLineage(manifest);
  const payload = {
    schemaVersion: 'APPROVAL_ASSEMBLY_ENFORCER_LANG_GRAPH_LINEAGE_V1',
    repository: REPOSITORY, commitSha: identity.commitSha, sourceE2ContentSha256: identity.contentSha256,
    baseE2GraphDigest: BASE_GRAPH, priorE2GraphDigest: PRIOR_GRAPH,
    currentE2GraphDigest: ASSEMBLY_ENFORCER_LANG_GRAPH, manifestSha256: ASSEMBLY_ENFORCER_LANG_MANIFEST_SHA256,
    sourceWitnessSha256: manifest.capture.sourceWitness.rawSha256,
    acceptedArtifactInventorySha256: manifest.base.artifactInventory.rawSha256,
    preservedJarLineage: preserved, archivalCandidate: manifest.capture,
    addedPluginCoordinateCount: manifest.addedPluginCoordinateCount, removedPluginCoordinateCount: manifest.removedPluginCoordinateCount,
    unchangedOtherPluginOwnerCount: 15, runtimeComponentChangeCount: 0, runtimeEdgeChangeCount: 0,
    importedBomChangeCount: 0, scopeChangeCount: 0, licenseChangeCount: 0,
    inventory: manifest.inventory, findingReviewRequired: true, releaseBlocked: true,
  };
  return { ...payload, contentSha256: hash(payload) };
}
