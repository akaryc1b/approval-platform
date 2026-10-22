import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, realpathSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { acceptedE2GraphProjection, resolveGitHubActionsEvidence } from './m6-pr-e-e2-generate-sbom.mjs';
import { osvInputFromE2 } from './osv-scan-coverage.mjs';
import { ASSEMBLY_ENFORCER_LANG_MANIFEST_SHA256, readAssemblyEnforcerLangManifest,
  readAssemblyEnforcerLangCandidate, readAssemblyEnforcerLangAcceptedServerRemediation,
  verifyAssemblyEnforcerLangDelta } from './assembly-enforcer-lang-graph-transition.mjs';

// Inert imports: archived data is opened only when this exact branch is selected.
// Accepted main905 and actual capture pins are independent of the transition
// manifest. Rehashing or replacing that manifest cannot nominate another base.
export const RESOURCES_FLATTEN_GRAPH = 'c1b04674b8dd67b29e0b720e793b62fe354be6a2cedef544e44abaa8614cf062';
export const RESOURCES_FLATTEN_MANIFEST_SHA256 = '0311fc0dbea67f6f82fe58610893847b547bb712207fbf6098d72815bc6bbfe7';
const MANIFEST_CONTENT = '5e49e65291505b1fe77612807a7fc4c8e8d6bcfba1759df82c51fd2705708bb1';
const HEAD = '905cd30fde430972da58203a61136d50a28f81e8';
const TREE = '8b5d522974a5547a7d990f42b6ce386dadad0941';
const PRIOR_GRAPH = 'c57035ab083aa9832d8d69b4dab2008f65d922ddec0edeb5b93742a10754f97d';
const BASE_GRAPH = '2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94';
const REPOSITORY = 'akaryc1b/approval-platform';
const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const MANIFEST_PATH = 'docs/operations/resources-flatten-transition.json';
const OWNERS = ['org.apache.maven.plugins:maven-resources-plugin:jar:3.4.0', 'org.codehaus.mojo:flatten-maven-plugin:jar:1.7.3'];
const OLD = 'org.codehaus.plexus:plexus-utils:jar:4.0.2';
const NEW = 'org.codehaus.plexus:plexus-utils:jar:4.0.3';
const CURRENT_POMS = { 'pom.xml': '7aaef759cbdebaafde2383186cbb90861c23f51bd4fe3219614ed0fa8c22a9ab',
  'integrations/host-sdk/pom.xml': '2d933d9c89c7a0ee99b4870091a880ac086b295501914297c9bb6a21dd751078' };
const ANCHORS = {
  "acceptedE2": {
    "path": "docs/operations/resources-flatten-evidence/accepted-main905-E2.json",
    "rawSha256": "b08ebf666a1c9b8f8450b65499c282f6b4fdf64c5ce8002ebc37fcd25b68e451",
    "bytes": 163876
  },
  "acceptedReport": {
    "path": "docs/operations/resources-flatten-evidence/accepted-main905-plugin-owner-report.txt",
    "rawSha256": "458c4ebe80574aeacc77202ecee4c133aa209f3b15eb6163d80633998df9ac29",
    "bytes": 558965
  },
  "oldSdk": {
    "path": "docs/operations/resources-flatten-evidence/accepted-main905-host-sdk-pom.xml",
    "rawSha256": "e755e3a2abd6d2df15b7e5739b10cd81f9a59476d8c21e6dc231961145b2d701",
    "bytes": 1922
  },
  "baselineE2": {
    "path": "docs/operations/resources-flatten-evidence/baseline-E2.json",
    "rawSha256": "28b73be83b99c94a2365134b3bb83f3d7ada3081000eaada3a0e8c9bdb9dd564",
    "bytes": 163877
  },
  "candidateE2": {
    "path": "docs/operations/resources-flatten-evidence/candidate-E2.json",
    "rawSha256": "a3944b822688cb8290a4b30d7ec04c4eca282b45376df5cf1fb86f673d63fccd",
    "bytes": 163832
  },
  "candidateReport": {
    "path": "docs/operations/resources-flatten-evidence/plugin-owner-report.txt",
    "rawSha256": "9ba9cf29ea27b67e5fcdff906b49d737580bc89f3fce041fd7226b368dba4e4a",
    "bytes": 560065
  },
  "baselineExecution": {
    "path": "docs/operations/resources-flatten-evidence/baseline-capture-execution.json",
    "rawSha256": "ee5e749ea1135eccf51b6f13ca3def6950f5482f862e270fb6de290a8ee7f26a",
    "bytes": 300404
  },
  "candidateExecution": {
    "path": "docs/operations/resources-flatten-evidence/capture-execution.json",
    "rawSha256": "af0cf1434e4ad1e02ee0fe42a78889b04aaad500b41dc956e25ec186408ec935",
    "bytes": 300571
  },
  "modelExecution": {
    "path": "docs/operations/resources-flatten-evidence/model-capture-execution.json",
    "rawSha256": "28229842906bf622c7cd76b9e8cbfbf592e1fc025625eabb60ed0a21b319348a",
    "bytes": 588099
  },
  "sourceWitness": {
    "path": "docs/operations/resources-flatten-evidence/source-pom-witness.json",
    "rawSha256": "8e60a33de11560f158d0bb42830be76e91d6bcd0dd25c085f53d57310314bfbf",
    "bytes": 7646
  },
  "additions": {
    "path": "docs/operations/resources-flatten-evidence/approved-additions.json",
    "rawSha256": "dffad78082b0e7d4d7b1e743c4d8da28265daf3ae7aad0666a7176cb2a2ae7ae",
    "bytes": 1444
  },
  "baselineLog": {
    "path": "docs/operations/resources-flatten-evidence/baseline-owners.stdout",
    "rawSha256": "6e858456e0c95e1d0c194f0dbd2cb8df6f73ce47c0f390c189833190e027d0fa",
    "bytes": 15313
  },
  "candidateLog": {
    "path": "docs/operations/resources-flatten-evidence/candidate-owners.stdout",
    "rawSha256": "31c3e26ce86924668b110b6a269ff6f19176d5556a5d00b40a0064a95d4d0092",
    "bytes": 15313
  },
  "runtimeInputs": {
    "path": "docs/operations/resources-flatten-evidence/runtime-inputs.json",
    "rawSha256": "50ebd07d1b0e146679df59a7c529ff8e9ca698c959f0ce8d206f542316190f55",
    "bytes": 29805
  },
  "cacheInputs": {
    "path": "docs/operations/resources-flatten-evidence/cache-inputs.json",
    "rawSha256": "5fc4c1ab37d8896599b05c39a191fad18b824174da6788375d75afaf6f8ed1c6",
    "bytes": 884371
  },
  "collector": {
    "path": "docs/operations/resources-flatten-evidence/capture.py",
    "rawSha256": "7fe48c76536050e6666db8f70fc9f222cabd3b31dbbd9641b8bd431bd1acb72e",
    "bytes": 21918
  },
  "occurrences": {
    "path": "docs/operations/resources-flatten-evidence/resource-occurrence-explanation.json",
    "rawSha256": "479ebc9a94429423b2add53ceeb8e67b876280437921925a6ecd248b2e28f1e4",
    "bytes": 21489
  },
  "baselineNormalModel": {
    "path": "docs/operations/resources-flatten-evidence/baseline-normal-effective-pom.xml",
    "rawSha256": "82876bc92cce778558d6f06f889a99f2bd0653ab54dd2f782c5dd13f04d88b7d",
    "bytes": 9985524
  },
  "baselineDemoModel": {
    "path": "docs/operations/resources-flatten-evidence/baseline-demo-effective-pom.xml",
    "rawSha256": "3363ab20cad945472e88b610bc52245b2477e7f270780b209669dab16deb75e8",
    "bytes": 10037347
  },
  "candidateNormalModel": {
    "path": "docs/operations/resources-flatten-evidence/candidate-normal-effective-pom.xml",
    "rawSha256": "a0c904475feb6dd14f6a8818ebb6972f50ab7d04fe03d1dfb33d29a614161eba",
    "bytes": 10003536
  },
  "candidateDemoModel": {
    "path": "docs/operations/resources-flatten-evidence/candidate-demo-effective-pom.xml",
    "rawSha256": "f8ac3e5e3c42d189f175bfce4b73c7f3ab43d98e4af62156e65706fbdda308e3",
    "bytes": 10062513
  },
  "oldRoot": {
    "path": "docs/operations/assembly-enforcer-lang-evidence/candidate-root-pom.xml",
    "rawSha256": "ddb286b44b1976dd19ad74b06f3ceef86e19d0fbee87dd27dc87985ffb1f7932",
    "bytes": 21539
  },
  "sourceManifest": {
    "path": "docs/operations/resources-flatten-evidence/accepted-main905-source-manifest.json",
    "rawSha256": "f28e7012dd8638a9b219e00aa16ade8357c771ff249f2b753755579df838120c",
    "bytes": 5703
  },
  "modelData": {
    "path": "docs/operations/resources-flatten-evidence/archival-source-model-data.json",
    "rawSha256": "0913a219b32ca5a9b3200a587bcf80765c92243b2d8373f898ab9248a68d3a53",
    "bytes": 9658
  }
};
const SHA256 = /^[0-9a-f]{64}$/;
const SHA40 = /^[0-9a-f]{40}$/;
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const sha = value => createHash('sha256').update(value).digest('hex');
const hash = value => sha(canonical(value));
const same = (a, b) => canonical(a) === canonical(b);
const requireValue = (ok, message) => { if (!ok) throw new Error(`Resources/Flatten ${message}`); };
function entryExists(path) {
  try { lstatSync(path); return true; }
  catch (error) { if (error.code === 'ENOENT') return false; throw error; }
}
function bytes(path, expected, size) {
  requireValue(typeof path === 'string' && /^[A-Za-z0-9_./-]+$/.test(path)
    && !path.startsWith('/') && path.split('/').every(part => part && part !== '.' && part !== '..'), 'unsafe evidence path');
  requireValue(SHA256.test(expected || ''), 'unsealed evidence');
  const root = resolve(ROOT), file = join(root, path);
  requireValue(realpathSync(root) === root && realpathSync(file) === file
    && lstatSync(file).isFile() && !lstatSync(file).isSymbolicLink()
    && lstatSync(file).size <= 16 * 1024 * 1024, `bounded real file required: ${path}`);
  // Never normalize current POM bytes or call an ancestor source/graph validator.
  const raw = readFileSync(file);
  requireValue(raw.length <= 16 * 1024 * 1024 && (size === undefined || raw.length === size)
    && sha(raw) === expected, `byte digest mismatch: ${path}`);
  return raw;
}
const archiveBytes = name => { const a = ANCHORS[name]; return bytes(a.path, a.rawSha256, a.bytes); };
const archive = name => JSON.parse(archiveBytes(name));
function sealed(value, label) {
  const { contentSha256, ...payload } = value;
  requireValue(SHA256.test(contentSha256 || '') && hash(payload) === contentSha256, `${label} content digest mismatch`);
  return value;
}
function validate(manifest) {
  sealed(manifest, 'manifest');
  requireValue(manifest.contentSha256 === MANIFEST_CONTENT
    && manifest.schemaVersion === 'APPROVAL_RESOURCES_FLATTEN_GRAPH_TRANSITION_V1'
    && manifest.repository === REPOSITORY && manifest.base.sourceHead === HEAD && manifest.base.sourceTree === TREE
    && manifest.base.graphDigest === PRIOR_GRAPH && manifest.observed.graphDigest === RESOURCES_FLATTEN_GRAPH
    && manifest.ownerCount === 17 && manifest.unchangedOtherPluginOwnerCount === 15
    && manifest.releaseBlocked === true && manifest.findingReviewRequired === true
    && manifest.scannerDispositionClaimed === false && manifest.canonicalCiEvidence === false
    && same(manifest.suppressions, []) && same(manifest.exceptions, []), 'transition identity mismatch');
  requireValue(same(manifest.archives, ANCHORS), 'independent main905/capture trust anchor mismatch');
}
export function readResourcesFlattenManifest() {
  const manifest = JSON.parse(bytes(MANIFEST_PATH, RESOURCES_FLATTEN_MANIFEST_SHA256));
  validate(manifest); return manifest;
}
function baselineIdentity(e2, captured) {
  sealed(e2, 'accepted E2');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === HEAD
    && hash(acceptedE2GraphProjection(e2)) === PRIOR_GRAPH, 'accepted main905 identity mismatch');
  requireValue(same(e2, sealed(captured, 'baseline capture')), 'actual baseline differs from accepted main905');
  return e2;
}
export function readResourcesFlattenBaseline(manifest = readResourcesFlattenManifest()) {
  validate(manifest); return baselineIdentity(archive('acceptedE2'), archive('baselineE2'));
}
function candidateIdentity(e2) {
  sealed(e2, 'candidate E2');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && e2.commitSha === HEAD
    && hash(acceptedE2GraphProjection(e2)) === RESOURCES_FLATTEN_GRAPH, 'actual candidate identity mismatch');
  return e2;
}
export function readResourcesFlattenCandidate(manifest = readResourcesFlattenManifest()) {
  validate(manifest); return candidateIdentity(archive('candidateE2'));
}
export function readResourcesFlattenReport(manifest = readResourcesFlattenManifest()) {
  validate(manifest); return archiveBytes('candidateReport').toString('utf8');
}
// This local strict parser is pure. Importing an ancestor admission module to
// obtain its parser would unnecessarily couple the new direct boundary to it.
function parseOwners(raw) {
  const owners = {}, occurrences = {}; let owner, coordinates;
  const finish = () => {
    if (!owner) return;
    const sorted = [...coordinates].sort();
    requireValue(sorted.includes(owner) && new Set(sorted).size === sorted.length, 'report owner membership');
    requireValue(!owners[owner] || same(owners[owner], sorted), 'repeated owner closure drift');
    owners[owner] = sorted; occurrences[owner] = (occurrences[owner] || 0) + 1;
  };
  for (const line of raw.split(/\r?\n/)) {
    if (!line.trim() || line === 'The following plugins have been resolved:') continue;
    const match = line.match(/^( {3}| {6})([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+){1,2})$/);
    requireValue(match, 'report unrecognized line');
    if (match[1].length === 3) { finish(); owner = match[2]; coordinates = []; }
    else { requireValue(owner, 'report dependency without owner'); coordinates.push(match[2]); }
  }
  finish(); return { owners, occurrences };
}
export function verifyResourcesFlattenOwnerSubstitution(beforeRaw, afterRaw) {
  const before = parseOwners(beforeRaw), after = parseOwners(afterRaw), names = Object.keys(before.owners).sort();
  requireValue(names.length === 17 && same(names, Object.keys(after.owners).sort()), 'owner membership drift');
  requireValue(same(names.filter(name => !same(before.owners[name], after.owners[name])), OWNERS), 'unrelated owner or missing replacement');
  requireValue(same(after.occurrences, { ...before.occurrences, [OWNERS[0]]: 26 })
    && before.occurrences[OWNERS[0]] === 24 && before.occurrences[OWNERS[1]] === 1, 'owner occurrence drift');
  for (const owner of OWNERS) requireValue(before.owners[owner].includes(OLD) && !before.owners[owner].includes(NEW)
    && same(after.owners[owner], before.owners[owner].map(value => value === OLD ? NEW : value).sort()), 'private dependency replacement drift');
  return { before, after, ownerCount: 17, unchangedOtherPluginOwnerCount: 15,
    changes: OWNERS.map(owner => ({ owner, previousOccurrences: before.occurrences[owner],
      currentOccurrences: after.occurrences[owner], before: before.owners[owner], after: after.owners[owner] })) };
}
const union = report => [...new Set(Object.values(report.owners).flat())].sort();
export function verifyResourcesFlattenGraphSubstitution(projection, baseline, beforeRaw, afterRaw) {
  const owners = verifyResourcesFlattenOwnerSubstitution(beforeRaw, afterRaw), prior = union(owners.before), current = union(owners.after);
  requireValue(same(baseline.maven.resolvedPluginCoordinates, prior) && same(projection.maven.resolvedPluginCoordinates, current)
    && baseline.maven.pluginResolutionSha256 === sha(beforeRaw) && projection.maven.pluginResolutionSha256 === sha(afterRaw),
  'global owner union/report mismatch');
  requireValue(prior.length === 263 && current.length === 262
    && same(prior.filter(v => !current.includes(v)), [OLD]) && same(current.filter(v => !prior.includes(v)), []), 'global coordinate delta drift');
  const reversed = structuredClone(projection);
  reversed.maven.resolvedPluginCoordinates = [...reversed.maven.resolvedPluginCoordinates, OLD].sort();
  reversed.maven.pluginResolutionSha256 = sha(beforeRaw);
  requireValue(same(reversed, baseline), 'undeclared graph change'); return reversed;
}
function captureIdentity(value, dirty, labels) {
  requireValue(value.schemaVersion === 'APPROVAL_RESOURCES_FLATTEN_CAPTURE_EXECUTION_V1'
    && value.sourceHead === HEAD && value.sourceTree === TREE && value.sourceDirty === dirty
    && value.status === 'PASSED_CAPTURE_ONLY' && value.offline === true
    && value.exactHeadEvidence === false && value.canonicalCiEvidence === false
    && value.captureInputsStable === true && value.runtimeInputsStable === true && value.cacheInputsStable === true
    && value.scannerDispositionClaimed === false && value.compatibilityAcceptanceClaimed === false
    && value.releaseBlocked === true && value.collectorSha256 === ANCHORS.collector.rawSha256
    && value.runtimeInputManifestSha256 === ANCHORS.runtimeInputs.rawSha256
    && value.cacheInputManifestSha256 === ANCHORS.cacheInputs.rawSha256
    && same(value.commands.map(row => row.label), ['head-before', 'tree-before', 'status-before', ...labels, 'head-after', 'tree-after', 'status-after'])
    && value.commands.every(row => row.status === 'PASSED' && row.exitStatus === 0), 'actual capture provenance mismatch');
}
function sourceCheck(witness, before, after, models, additions) {
  const original = archive('sourceManifest');
  requireValue(original.head === HEAD && original.tree === TREE && original.checkedFileCount === 32
    && Object.keys(original.files).length === 32
    && original.extractedFromManifestSha256 === '920e81c1d2cb1a1377aba9107619ea8fa331578476d8e275722a592bcef79fd2', 'independent source manifest mismatch');
  requireValue(witness.sourceBaseHead === HEAD && witness.sourceBaseTree === TREE
    && witness.sourcePomFiles.length === 28 && new Set(witness.sourcePomFiles.map(row => row.path)).size === 28
    && witness.modelCountPerVariantAndProfile === 26 && witness.nonReactorSourceOnlyPomCount === 2
    && witness.exactHeadEvidence === false && witness.canonicalCiEvidence === false && witness.releaseBlocked === true,
  '28-POM source witness identity mismatch');
  const oldHashes = Object.fromEntries(witness.sourcePomFiles.map(row => [row.path, row.baselineSha256]));
  const newHashes = Object.fromEntries(witness.sourcePomFiles.map(row => [row.path, row.candidateSha256]));
  requireValue(same(Object.keys(original.files).sort(), [...Object.keys(oldHashes), 'config/checkstyle/checkstyle.xml',
    'docs/operations/assembly-enforcer-lang-evidence/source-witness.json', 'scripts/ci/checkstyle_plugin_contract.py',
    'scripts/ci/clean_plugin_contract.py'].sort())
    && Object.entries(original.files).every(([p, ref]) => ref.sha256 === models.baselineCaptureInputs[p]),
  'independent source manifest membership drift');
  requireValue(same(before.sourcePomHashes, oldHashes) && same(after.sourcePomHashes, newHashes)
    && same(models.sourcePomHashes, newHashes)
    && same(Object.keys(newHashes).filter(p => oldHashes[p] !== newHashes[p]).sort(), Object.keys(CURRENT_POMS).sort())
    && Object.entries(CURRENT_POMS).every(([p, h]) => newHashes[p] === h)
    && Object.keys(after.captureInputs).length === 56 && Object.keys(models.captureInputs).length === 58,
  'actual source/capture membership mismatch');
  requireValue(same(before.captureInputs, { ...after.captureInputs, ...oldHashes })
    && same(models.baselineCaptureInputs, { ...models.captureInputs, ...oldHashes })
    && Object.entries(after.captureInputs).every(([p, h]) => models.captureInputs[p] === h), 'paired input closure drift');
  requireValue(same(Object.keys(additions).sort(), Object.keys(CURRENT_POMS).sort())
    && additions['pom.xml'].length === 2 && additions['integrations/host-sdk/pom.xml'].length === 1, 'exact three additions required');
  for (const [path, expected] of Object.entries(models.captureInputs)) bytes(path, expected);
  const oldRoot = archiveBytes('oldRoot'), oldSdk = archiveBytes('oldSdk');
  for (const [path, original] of [['pom.xml', oldRoot], ['integrations/host-sdk/pom.xml', oldSdk]]) {
    let current = bytes(path, CURRENT_POMS[path]).toString('utf8');
    for (const block of additions[path]) {
      requireValue(typeof block === 'string' && block.length > 0 && current.split(block).length === 2, `exact source reversal mismatch: ${path}`);
      current = current.replace(block, '');
    }
    requireValue(Buffer.from(current).equals(original) && sha(original) === oldHashes[path], `independent old archive mismatch: ${path}`);
  }
  // The old witness independently fixes all 28 original POM identities.
  const historical = JSON.parse(bytes('docs/operations/assembly-enforcer-lang-evidence/source-witness.json',
    '7e5b4ee22444b7f76334027fcb878e777854d05502ba24312dcb8e20be000a38'));
  requireValue(same(Object.fromEntries(historical.sourcePomFiles.map(row => [row.path, row.candidateSha256])), oldHashes), 'accepted 28-POM membership drift');
  const directories = new Set([resolve(ROOT)]);
  for (const p of Object.keys(newHashes)) { let d = dirname(join(ROOT, p)); while (d.startsWith(resolve(ROOT))) { directories.add(d); d = dirname(d); } }
  let d = dirname(resolve(ROOT)); while (d !== dirname(d)) { directories.add(d); d = dirname(d); } directories.add(d);
  requireValue([...directories].every(p => !entryExists(join(p, '.mvn'))) && !entryExists(join(ROOT, 'pnpm-lock.yaml')), 'unreviewed Maven configuration or pnpm lock');
  return { sourceHead: HEAD, sourceTree: TREE, sourcePomCount: 28, sourceHashes: newHashes };
}
function validateModels(data) {
  const { modelExecution: execution, modelData, occurrences } = data;
  requireValue(modelData.schemaVersion === 'APPROVAL_RESOURCES_FLATTEN_ARCHIVAL_SOURCE_MODEL_DATA_V1'
    && modelData.repository === REPOSITORY && modelData.dataKind === 'REORGANIZED_LOCAL_CAPTURE_AND_COMPLETE_MODEL_COMPARISON_DATA'
    && modelData.source.sourceHead === HEAD && modelData.source.sourceTree === TREE
    && modelData.source.sourcePomWitnesses === 28 && modelData.source.reactorModelsPerDocument === 26
    && modelData.source.nonReactorOverlaySourceOnly === 2
    && same(modelData.scope, { originalReceipt: false, newlyOrganizedArchivalData: true,
      currentHeadExecutionClaimed: false, runtimeCompatibilityClaimed: false, freshOsvResultsClaimed: false,
      canonicalScannerOrCiClaimed: false, findingDispositionClaimed: false, releaseAccepted: false, releaseBlocked: true }),
  'independently bound archival model data mismatch');
  requireValue(same(modelData.modelComparisonOrigin, {
    modelSemantics: '8d0dab3e82979f31b19a280e5a1c414f1a80703c83d58401fdee06d8848c5371',
    modelBinding: 'fd7e999e866812243aa0474200b9b9c8206370331f4fd31a88944c4b282c0a3b',
    archivedModelContract: '7d5a774eaf178e6830eabf8c09460292ced764f2f601bb38253cbc61a886cfe4',
  }) && Object.entries(modelData.files).every(([name, ref]) => same(ref, ANCHORS[name])),
  'archival model data original-byte binding mismatch');
  requireValue(same(modelData.resourcesReportOccurrences.addedManagedDeclarationProjects, occurrences.addedOccurrenceProjects),
    'managed-only occurrence data mismatch');
  for (const profile of ['normal', 'demo']) {
    const report = modelData.models[profile];
    requireValue(report.modelCount === 26 && report.allOtherModelFieldsUnchanged === true
      && report.ownerDeclarationCounts['project/build/pluginManagement/plugins:maven-resources-plugin'] === 26
      && report.ownerDeclarationCounts['project/build/plugins:flatten-maven-plugin'] === (profile === 'normal' ? 1 : 26), 'complete owner model coverage drift');
    for (const variant of ['baseline', 'candidate']) {
      const name = `${variant}${profile[0].toUpperCase()}${profile.slice(1)}Model`, digest = ANCHORS[name].rawSha256;
      requireValue(execution.effectiveModelHashes[`${variant}-${profile}`] === digest
        && execution.effectiveModelCounts[`${variant}-${profile}`] === 26 && report[`${variant}Sha256`] === digest,
      'actual full XML output binding mismatch');
      // The independently reviewed exact XML bytes bind the complete semantic
      // result. This is archival data validation, not a fresh model execution
      // or an XML parser accepting arbitrary caller-provided documents.
    }
  }
  // Strictly bind the accepted occurrence explanation to both original replay
  // logs, actual reports and four XML files. Do not infer extra executions from
  // a predeclared count: these exact original bytes were independently reviewed.
  requireValue(occurrences.status === 'PASSED_ACTUAL_RESOURCE_OCCURRENCE_MODEL_AND_LOG_EXPLANATION'
    && occurrences.all26ReportBlocksMatchActualMavenReplayOrderAndNormalModelOrder === true
    && occurrences.baselineOwnerOccurrences === 24 && occurrences.candidateOwnerOccurrences === 26
    && occurrences.activeResourcePluginCountUnchanged === 24 && occurrences.addedExecutionCount === 0
    && same(occurrences.addedOccurrenceProjects.map(row => row.sourcePom), ['pom.xml', 'server-modules/pom.xml'])
    && occurrences.addedOccurrenceProjects.every(row => row.baseline.managedCount === 0 && row.candidate.managedCount === 1
      && row.baseline.activeCount === 0 && row.candidate.activeCount === 0
      && row.baseline.activeExecutionCount === 0 && row.candidate.activeExecutionCount === 0
      && row.baseline.managedExecutionCount === 0 && row.candidate.managedExecutionCount === 0), 'Resources managed-only report occurrence attribution drift');
  const pinnedInputs = new Set(Object.values(occurrences.inputSha256));
  for (const name of ['baselineLog', 'candidateLog', 'acceptedReport', 'candidateReport', 'baselineNormalModel', 'candidateNormalModel', 'modelExecution'])
    requireValue(pinnedInputs.has(ANCHORS[name].rawSha256), 'actual replay/model occurrence binding mismatch');
}
function directEvidence(manifest) {
  validate(manifest); const raw = {}, data = {}; let total = 0;
  for (const name of Object.keys(ANCHORS)) {
    raw[name] = archiveBytes(name); total += raw[name].length;
    requireValue(total <= 48 * 1024 * 1024, 'direct evidence byte budget exceeded');
    if (ANCHORS[name].path.endsWith('.json')) data[name] = JSON.parse(raw[name]);
  }
  captureIdentity(data.baselineExecution, false, ['e2', 'owners']);
  captureIdentity(data.candidateExecution, true, ['e2', 'owners']);
  captureIdentity(data.modelExecution, true, ['baseline-normal-models', 'candidate-normal-models', 'baseline-demo-models', 'candidate-demo-models']);
  requireValue(data.baselineExecution.e2Sha256 === ANCHORS.baselineE2.rawSha256
    && data.baselineExecution.ownerReportSha256 === ANCHORS.acceptedReport.rawSha256
    && data.candidateExecution.e2Sha256 === ANCHORS.candidateE2.rawSha256
    && data.candidateExecution.ownerReportSha256 === ANCHORS.candidateReport.rawSha256, 'actual E2/report capture mismatch');
  const source = sourceCheck(data.sourceWitness, data.baselineExecution, data.candidateExecution, data.modelExecution, data.additions);
  validateModels(data);
  const baseline = baselineIdentity(data.acceptedE2, data.baselineE2), candidate = candidateIdentity(data.candidateE2);
  const beforeRaw = raw.acceptedReport.toString('utf8'), afterRaw = raw.candidateReport.toString('utf8');
  const { contentSha256: oldContent, ...oldFull } = baseline;
  const { contentSha256: newContent, ...newFull } = candidate;
  const reversedFull = structuredClone(newFull);
  reversedFull.maven.resolvedPluginCoordinates = [...reversedFull.maven.resolvedPluginCoordinates, OLD].sort();
  reversedFull.maven.pluginResolutionSha256 = sha(beforeRaw);
  requireValue(same(reversedFull, oldFull), 'complete captured E2 differs beyond exact owner delta');
  return { data, source, baseline, candidate, beforeRaw, afterRaw };
}
function finalFresh(evidence) {
  sourceCheck(evidence.data.sourceWitness, evidence.data.baselineExecution, evidence.data.candidateExecution,
    evidence.data.modelExecution, evidence.data.additions);
  for (const name of ['baselineNormalModel', 'candidateNormalModel', 'baselineDemoModel', 'candidateDemoModel']) archiveBytes(name);
  archiveBytes('modelData');
  publicLangProof(evidence.baseline);
  bytes(MANIFEST_PATH, RESOURCES_FLATTEN_MANIFEST_SHA256);
}
export function readResourcesFlattenSourceWitness(manifest = readResourcesFlattenManifest()) {
  validate(manifest);
  const before = archive('baselineExecution'), after = archive('candidateExecution'), models = archive('modelExecution');
  captureIdentity(before, false, ['e2', 'owners']); captureIdentity(after, true, ['e2', 'owners']);
  captureIdentity(models, true, ['baseline-normal-models', 'candidate-normal-models', 'baseline-demo-models', 'candidate-demo-models']);
  return sourceCheck(archive('sourceWitness'), before, after, models, archive('additions'));
}
// This proof is recomputed only from code and data already present in public
// main905. It is not an extraction from, or a substitute scan of, an old E4.
// Current findings and scanner completeness remain exclusively current-E4 gates.
function publicLangProof(baseline) {
  // Independent public-base pins prevent imported code from nominating its own
  // replacement manifest. The RF helper pin binds the reviewed source reversal.
  bytes('scripts/security/assembly-enforcer-lang-graph-transition.mjs', 'a8dc708fefafffddee4e5e6de92daee61bc65e636d82370bc0ce84fef266a0e9');
  bytes('docs/operations/assembly-enforcer-lang-transition.json', '0022fed426ea61c886284b07a14b1b12df0d10ededfe76b891ee00f5cbc42120');
  // Reverse only the reviewed pure-parser wrapper; retain the original public source pin.
  bytes('scripts/security/plugin-owner-parser-cache.mjs', 'cda387a079f64f1de892566baf720c4eaa9f83258313fbb0b12ebdad95b3fc5c');
  const parser = bytes('scripts/security/checkstyle-plugin-graph-transition.mjs', '489c3dd6ab7e8c94067168f7502a938526782335282a423058d71cde40260bef').toString('utf8');
  const parserImport = "import { memoizePluginOwnerParser } from './plugin-owner-parser-cache.mjs';\n";
  const parserWrapper = "const cachedCheckstylePluginOwners = memoizePluginOwnerParser(parseCheckstylePluginOwnersUncached);\nexport function parseCheckstylePluginOwners(raw) {\n  return cachedCheckstylePluginOwners(raw);\n}\nfunction parseCheckstylePluginOwnersUncached(raw) {";
  requireValue(parser.split(parserImport).length === 2 && parser.split(parserWrapper).length === 2
    && sha(parser.replace(parserImport, '').replace(parserWrapper,
      'export function parseCheckstylePluginOwners(raw) {')) === '30b16c74a6b181e363f2d3a4d9c266727a5786ff2bb207fdb309a74d2d997801',
  'public Checkstyle parser source continuation mismatch');
  bytes('scripts/security/jar-plugin-source-continuation.mjs', 'f90cccf14fd250a9d600a026aeb4fcc7b883072be2bf9be14eb7b180e19fd2ea');
  const manifest = readAssemblyEnforcerLangManifest();
  const candidate = readAssemblyEnforcerLangCandidate(manifest);
  const { commitSha: oldHead, contentSha256: oldHash, githubActions: oldActions, ...oldPayload } = candidate;
  const { commitSha, contentSha256, githubActions, ...payload } = baseline;
  requireValue(commitSha === HEAD && same(payload, oldPayload)
    && same(githubActions, resolveGitHubActionsEvidence(ROOT)), 'public Lang baseline source/Actions mismatch');
  verifyAssemblyEnforcerLangDelta(acceptedE2GraphProjection(baseline), manifest);
  // This validates the pinned public receipt and the complete public JAR
  // artifact closure, including its current-source reversal, without old scans.
  const server = readAssemblyEnforcerLangAcceptedServerRemediation(manifest);
  const lineage = {
    schemaVersion: 'APPROVAL_ASSEMBLY_ENFORCER_LANG_GRAPH_LINEAGE_V1',
    repository: REPOSITORY, commitSha, sourceE2ContentSha256: contentSha256,
    baseE2GraphDigest: BASE_GRAPH, priorE2GraphDigest: manifest.base.graphDigest,
    currentE2GraphDigest: PRIOR_GRAPH, manifestSha256: ASSEMBLY_ENFORCER_LANG_MANIFEST_SHA256,
    sourceWitnessSha256: manifest.capture.sourceWitness.rawSha256,
    acceptedArtifactInventorySha256: manifest.base.artifactInventory.rawSha256,
    preservedJarLineage: server.subsequentGraphTransition, archivalCandidate: manifest.capture,
    addedPluginCoordinateCount: manifest.addedPluginCoordinateCount,
    removedPluginCoordinateCount: manifest.removedPluginCoordinateCount,
    unchangedOtherPluginOwnerCount: 15, runtimeComponentChangeCount: 0, runtimeEdgeChangeCount: 0,
    importedBomChangeCount: 0, scopeChangeCount: 0, licenseChangeCount: 0,
    inventory: manifest.inventory, findingReviewRequired: true, releaseBlocked: true,
  };
  return { lineage: { ...lineage, contentSha256: hash(lineage) }, server };
}
export function readResourcesFlattenAcceptedLineage(manifest = readResourcesFlattenManifest()) {
  validate(manifest); return publicLangProof(readResourcesFlattenBaseline(manifest)).lineage;
}
// This is the earlier public plan bridge, not evidence of a main905 scan.
export function readResourcesFlattenAcceptedServerRemediation(manifest = readResourcesFlattenManifest()) {
  validate(manifest); return publicLangProof(readResourcesFlattenBaseline(manifest)).server;
}
function delta(projection, evidence, manifest) {
  requireValue(hash(projection) === RESOURCES_FLATTEN_GRAPH, 'current graph drift');
  const previous = verifyResourcesFlattenGraphSubstitution(projection, acceptedE2GraphProjection(evidence.baseline), evidence.beforeRaw, evidence.afterRaw);
  requireValue(projection.maven.components.length === 237 && projection.maven.edges.length === 345
    && projection.maven.importedBoms.length === 6 && projection.maven.reactorRoots.length === 26, 'runtime inventory drift');
  for (const [label, graph] of [['prior', previous], ['current', projection]]) {
    const input = osvInputFromE2(graph);
    requireValue(input.packageCount === manifest.osvInput[label].packageCount
      && sha(JSON.stringify(input.scannerInput)) === manifest.osvInput[label].inputBytesSha256, 'exact OSV target identity drift');
  }
  return previous;
}
export function verifyResourcesFlattenDelta(projection, manifest = readResourcesFlattenManifest()) {
  requireValue(hash(projection) === RESOURCES_FLATTEN_GRAPH, 'current graph drift');
  const evidence = directEvidence(manifest), previous = delta(projection, evidence, manifest);
  finalFresh(evidence); return previous;
}
function currentIdentity(e2, expectedCommitSha, evidence) {
  requireValue(SHA40.test(expectedCommitSha || '') && e2?.commitSha === expectedCommitSha, 'current verified head is required');
  sealed(e2, 'current E2');
  const { commitSha, contentSha256, ...payload } = e2;
  const { commitSha: capturedHead, contentSha256: capturedHash, ...captured } = evidence.candidate;
  requireValue(same(resolveGitHubActionsEvidence(ROOT), captured.githubActions), 'current Actions source drift');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY && same(payload, captured), 'current E2 source/Actions/toolchain drift');
}
export function verifyResourcesFlattenCurrentEvidence(e2, expectedCommitSha, manifest = readResourcesFlattenManifest()) {
  requireValue(SHA40.test(expectedCommitSha || '') && e2?.commitSha === expectedCommitSha, 'current verified head is required');
  const evidence = directEvidence(manifest);
  currentIdentity(e2, expectedCommitSha, evidence);
  delta(acceptedE2GraphProjection(e2), evidence, manifest); finalFresh(evidence);
}
/** expectedCommitSha must come from the existing caller's verified checkout. */
export function resourcesFlattenReceipt(identity, expectedCommitSha) {
  requireValue(SHA40.test(expectedCommitSha || '') && identity?.commitSha === expectedCommitSha, 'current verified head is required');
  const manifest = readResourcesFlattenManifest(), evidence = directEvidence(manifest);
  const { contentSha256, ...captured } = evidence.candidate;
  const payload = { ...captured, commitSha: expectedCommitSha };
  requireValue(hash(payload) === identity.contentSha256, 'receipt current E2 identity mismatch');
  const e2 = same(Object.keys(identity).sort(), ['commitSha', 'contentSha256']) ? { ...payload, contentSha256: identity.contentSha256 } : identity;
  currentIdentity(e2, expectedCommitSha, evidence); delta(acceptedE2GraphProjection(e2), evidence, manifest);
  const { lineage: preserved } = publicLangProof(evidence.baseline);
  // Per-invocation reuse only: source is checked again and all four actual XML
  // byte digests are reopened at the end. No XML reparse and no success cache.
  finalFresh(evidence);
  const receipt = {
    schemaVersion: 'APPROVAL_RESOURCES_FLATTEN_GRAPH_LINEAGE_V1', repository: REPOSITORY,
    commitSha: expectedCommitSha, sourceE2ContentSha256: identity.contentSha256,
    baseE2GraphDigest: BASE_GRAPH, priorE2GraphDigest: PRIOR_GRAPH, currentE2GraphDigest: RESOURCES_FLATTEN_GRAPH,
    manifestSha256: RESOURCES_FLATTEN_MANIFEST_SHA256,
    acceptedMain905E2Sha256: ANCHORS.acceptedE2.rawSha256,
    preservedLangProofSource: 'RECOMPUTED_FROM_PUBLIC_SOURCE_AND_ARCHIVES',
    preservedLangLineage: preserved, sourceWitnessSha256: ANCHORS.sourceWitness.rawSha256,
    modelCaptureSha256: ANCHORS.modelExecution.rawSha256, archivalSourceModelDataSha256: ANCHORS.modelData.rawSha256,
    addedPluginCoordinateCount: 0, removedPluginCoordinateCount: 1, unchangedOtherPluginOwnerCount: 15,
    runtimeComponentChangeCount: 0, runtimeEdgeChangeCount: 0, importedBomChangeCount: 0, scopeChangeCount: 0, licenseChangeCount: 0,
    inventory: { componentCount: 237, edgeCount: 345, reactorRootCount: 26, importedBomCount: 6, resolvedPluginCoordinateCount: 262 },
    findingReviewRequired: true, releaseBlocked: true,
  };
  return { ...receipt, contentSha256: hash(receipt) };
}
