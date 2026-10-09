import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

export const SERVER_DEPENDENCY_GRAPH = '6557cef5d2ef11c36a8efb89cf7c1c607c721e32cdbabf0228441bb585b3c829';
export const SERVER_DEPENDENCY_MANIFEST_SHA256 = '32f5da54a05cdaee6e5eaa555ffc3df99b088c4d4b6f214d874876eef41ab8df';
const MANIFEST_CONTENT_SHA256 = '5c23f2b6822852af39f7b106857fa0cb8a39365e42ade59e2f31513b0364f29f';
const SOURCE_SHA256 = '0975f7932690123120fd9dd11606b0a5d4a971d083b62d25985a0694b136e18c';
const SOURCE_CONTENT_SHA256 = '2d7fb41f863f0746ebeb7d300a48d45cf66d565f2b1477e3d01d0420b3e8b470';
const ARCHIVAL_E2_SHA256 = '1f5e32f60e4bde4a608a24decbf913779128e1627f9128c7e0267e3f1c04927f';
const ARCHIVAL_E2_CONTENT_SHA256 = '4c423ae6f24cbdd6a83c07f2176e254dfbee413a1e49ca9f28c3524a050b43b7';
const BASE_GRAPH = '390773d2aa746a2203eec870e5dd0a8f97f81e91913bb016c9ef95b749c0e7b3';
const BASE_HEAD = 'a0717a017e94840141fc6841ff7bfb1226a3da6e';
const BASE_TREE = '0c12c618fdd24d13caf5e37cc74fdb684a30fa0f';
const SOURCE_HEAD = '921d5ec5a770cccdfa24f9cf809b0a3a609f9cbe';
const SOURCE_TREE = '79c138d18075b54e800829eb903cb58a0b39b040';
const REPOSITORY = 'akaryc1b/approval-platform';
const SOURCE_PATH = 'docs/operations/server-dependency-source-witness.json';
const ARCHIVAL_E2_PATH = 'docs/operations/server-dependency-evidence-921d5ec5/M6_PR_E_E2_SBOM.json';
const stable = value => Array.isArray(value) ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const sha256 = value => createHash('sha256').update(value).digest('hex');
const hash = value => sha256(canonical(value));
const requireValue = (condition, message) => { if (!condition) throw new Error(message); };
const same = (a, b) => canonical(a) === canonical(b);
const ref = (change, version) => `pkg:maven/${change.group}/${change.name}@${version}?type=${change.type}`;
const edgeCompare = (a, b) => {
  const left = `${a.from}\0${a.to}`, right = `${b.from}\0${b.to}`;
  return left < right ? -1 : left > right ? 1 : 0;
};
function unique(values, name) {
  requireValue(Array.isArray(values) && new Set(values).size === values.length, `${name} must be unique`);
}
function contentDigest(value, expected, name) {
  const { contentSha256, ...payload } = value;
  requireValue(hash(payload) === contentSha256 && (!expected || contentSha256 === expected), `${name} content digest mismatch`);
}
function readPinned(relativePath, digest, name) {
  const bytes = readFileSync(new URL(`../../${relativePath}`, import.meta.url));
  requireValue(sha256(bytes) === digest, `${name} byte digest mismatch`);
  return JSON.parse(bytes);
}
function graphProjection(e2) {
  const accepted = e2.githubActions?.acceptedDependencyGraph;
  return { maven: e2.maven, pnpm: e2.pnpm,
    githubActions: accepted ? accepted.githubActions : e2.githubActions,
    limitations: accepted ? accepted.limitations : e2.limitations };
}

/** A fixed archival witness, not the identity of a later scanner checkout. */
export function readServerDependencySourceWitness() {
  const witness = readPinned(SOURCE_PATH, SOURCE_SHA256, 'server dependency source witness');
  contentDigest(witness, SOURCE_CONTENT_SHA256, 'server dependency source witness');
  requireValue(witness.schemaVersion === 'APPROVAL_SERVER_DEPENDENCY_SOURCE_WITNESS_V1'
    && witness.repository === REPOSITORY && witness.findingReviewRequired === true
    && witness.releaseBlocked === true, 'server dependency source witness identity mismatch');
  const source = witness.sourceTransition;
  requireValue(source.schemaVersion === 'APPROVAL_SERVER_DEPENDENCY_SOURCE_TRANSITION_V1'
    && source.baseHead === BASE_HEAD && source.baseTree === BASE_TREE
    && source.candidateHead === SOURCE_HEAD && source.candidateTree === SOURCE_TREE
    && source.files.length === 7 && source.localCommitChain.length === 2
    && source.localCommitChain.at(-1) === SOURCE_HEAD && source.historicalEvidenceModified === false
    && source.graphAdmissionModified === false && source.releaseBlocked === true,
  'server dependency archival source identity mismatch');
  unique(source.files.map(value => value.path), 'source witness file paths');
  requireValue(same(witness.archivalE2, { path: ARCHIVAL_E2_PATH, rawSha256: ARCHIVAL_E2_SHA256,
    contentSha256: ARCHIVAL_E2_CONTENT_SHA256, commitSha: SOURCE_HEAD }), 'server dependency archival E2 identity mismatch');
  const e2 = readPinned(ARCHIVAL_E2_PATH, ARCHIVAL_E2_SHA256, 'server dependency archival E2');
  contentDigest(e2, ARCHIVAL_E2_CONTENT_SHA256, 'server dependency archival E2');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === REPOSITORY
    && e2.commitSha === SOURCE_HEAD && hash(graphProjection(e2)) === SERVER_DEPENDENCY_GRAPH,
  'server dependency archival E2 graph mismatch');
  requireValue(witness.captureInvocations.length === 3, 'server dependency capture count mismatch');
  unique(witness.captureInvocations.map(value => value.file), 'source capture invocations');
  for (const { invocation } of witness.captureInvocations) {
    requireValue(invocation.exitCode === 0, 'server dependency capture did not complete');
    for (const identity of [invocation.identityBefore, invocation.identityAfter]) {
      requireValue(identity.head === SOURCE_HEAD && identity.tree === SOURCE_TREE
        && identity.cleanWorktree === true && identity.porcelainStatus === '', 'server dependency capture source mismatch');
    }
  }
  const plugins = witness.captureInvocations.filter(value => value.invocation.command
    .includes('org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins'));
  requireValue(plugins.length === 1 && plugins[0].invocation.outputs.length === 1
    && plugins[0].invocation.outputs[0].sha256 === e2.maven.pluginResolutionSha256,
  'server dependency plugin capture hash mismatch');
  return witness;
}

function validateManifest(manifest) {
  contentDigest(manifest, MANIFEST_CONTENT_SHA256, 'server dependency manifest');
  requireValue(manifest.schemaVersion === 'APPROVAL_SERVER_DEPENDENCY_GRAPH_TRANSITION_V1'
    && manifest.repository === REPOSITORY && manifest.base.graphDigest === BASE_GRAPH
    && manifest.base.sourceHead === BASE_HEAD && manifest.base.sourceTree === BASE_TREE
    && manifest.observed.graphDigest === SERVER_DEPENDENCY_GRAPH
    && manifest.observed.sourceHead === SOURCE_HEAD && manifest.observed.sourceTree === SOURCE_TREE
    && manifest.observed.e2ContentSha256 === ARCHIVAL_E2_CONTENT_SHA256,
  'server dependency transition identity mismatch');
  requireValue(same(manifest.sourceWitness, { path: SOURCE_PATH, rawSha256: SOURCE_SHA256,
    contentSha256: SOURCE_CONTENT_SHA256 }), 'server dependency source binding mismatch');
  requireValue(manifest.findingReviewRequired === true && manifest.releaseBlocked === true
    && manifest.findingDisposition === 'UNRESOLVED_UNTIL_EXPLICIT_FINDING_REVIEW',
  'server dependency transition cannot clear finding review or release blocks');
  requireValue(manifest.componentVersionChangeCount === 90 && manifest.componentVersionChanges.length === 90
    && manifest.rewrittenEdgeCount === 180 && manifest.edgeRewrites.length === 180
    && manifest.pluginCoordinateVersionChangeCount === 13 && manifest.pluginCoordinateVersionChanges.length === 13
    && manifest.addedImportedBomCount === 2 && manifest.addedImportedBoms.length === 2
    && manifest.importedBomVersionChangeCount === 1 && manifest.importedBomVersionChanges.length === 1
    && manifest.scopeChangeCount === 0 && manifest.licenseChangeCount === 0,
  'server dependency declaration counts mismatch');
  requireValue(same(manifest.inventory, { componentCount: 237, edgeCount: 345, reactorRootCount: 26,
    resolvedPluginCoordinateCount: 329, priorImportedBomCount: 4, importedBomCount: 6,
    unknownLicenseComponentCount: 78 }), 'server dependency inventory declaration mismatch');
  readServerDependencySourceWitness();
}

export function readServerDependencyManifest() {
  const manifest = readPinned('docs/operations/server-dependency-transition.json',
    SERVER_DEPENDENCY_MANIFEST_SHA256, 'server dependency manifest');
  validateManifest(manifest);
  return manifest;
}

/** Reverse only typed, declared changes. No field, graph or inventory snapshot is substituted. */
export function verifyServerDependencyDelta(projection, manifest = readServerDependencyManifest()) {
  validateManifest(manifest);
  requireValue(hash(projection) === SERVER_DEPENDENCY_GRAPH, 'current server dependency graph drift');
  const prior = structuredClone(projection), maven = prior.maven;
  requireValue(maven.components.length === 237 && maven.edges.length === 345
    && maven.reactorRoots.length === 26 && maven.reactorProjectCount === 26
    && maven.resolvedPluginCoordinates.length === 329 && maven.importedBoms.length === 6
    && maven.components.filter(value => value.licenses.includes('EVIDENCE_UNAVAILABLE')).length === 78,
  'server dependency inventory mismatch');
  unique(maven.components.map(value => value.bomRef), 'components');
  unique(maven.edges.map(canonical), 'edges');
  unique(maven.reactorRoots, 'reactor roots');
  unique(maven.resolvedPluginCoordinates, 'plugin coordinates');
  unique(maven.importedBoms.map(value => `${value.group}:${value.name}`), 'imported BOMs');
  requireValue(same(maven.components, [...maven.components].sort((a, b) => a.bomRef.localeCompare(b.bomRef)))
    && same(maven.edges, [...maven.edges].sort(edgeCompare))
    && same(maven.resolvedPluginCoordinates, [...maven.resolvedPluginCoordinates].sort()),
  'server dependency inventory order drift');
  unique(manifest.componentVersionChanges.map(value => `${value.group}:${value.name}:${value.type}`), 'component declarations');
  const reverse = new Map();
  for (const change of manifest.componentVersionChanges) {
    requireValue(typeof change.fromVersion === 'string' && typeof change.toVersion === 'string'
      && change.fromVersion !== change.toVersion && change.type === 'jar', 'server dependency version transition invalid');
    const now = ref(change, change.toVersion), then = ref(change, change.fromVersion);
    const matches = maven.components.filter(value => value.bomRef === now), component = matches[0];
    requireValue(matches.length === 1 && component.group === change.group && component.name === change.name
      && component.type === change.type && component.version === change.toVersion
      && component.source === `maven:${change.group}:${change.name}:${change.toVersion}`
      && !maven.components.some(value => value.bomRef === then), 'server dependency component transition mismatch');
    reverse.set(now, then);
    component.bomRef = then;
    component.version = change.fromVersion;
    component.source = `maven:${change.group}:${change.name}:${change.fromVersion}`;
  }
  const changedEdges = maven.edges.filter(value => reverse.has(value.from) || reverse.has(value.to));
  unique(manifest.edgeRewrites.map(value => canonical(value.before)), 'prior edge declarations');
  unique(manifest.edgeRewrites.map(value => canonical(value.after)), 'current edge declarations');
  requireValue(changedEdges.length === 180 && same(changedEdges.map(edge => ({
    before: { from: reverse.get(edge.from) ?? edge.from, to: reverse.get(edge.to) ?? edge.to }, after: edge,
  })).sort((a, b) => edgeCompare(a.before, b.before)), manifest.edgeRewrites), 'server dependency exact edge transition mismatch');
  for (const edge of maven.edges) {
    edge.from = reverse.get(edge.from) ?? edge.from;
    edge.to = reverse.get(edge.to) ?? edge.to;
  }
  maven.components.sort((a, b) => a.bomRef.localeCompare(b.bomRef));
  maven.edges.sort(edgeCompare);
  unique(maven.components.map(value => value.bomRef), 'reversed components');
  unique(maven.edges.map(canonical), 'reversed edges');
  unique(manifest.importedBomVersionChanges.map(value => value.currentIndex), 'BOM version declarations');
  for (const change of manifest.importedBomVersionChanges) {
    requireValue(Number.isSafeInteger(change.currentIndex) && change.currentIndex >= 0
      && Number.isSafeInteger(change.priorIndex) && change.priorIndex >= 0
      && change.fromVersion !== change.toVersion
      && same(maven.importedBoms[change.currentIndex], { group: change.group, name: change.name,
        scope: change.scope, version: change.toVersion }), 'server dependency BOM version transition mismatch');
    maven.importedBoms[change.currentIndex].version = change.fromVersion;
  }
  unique(manifest.addedImportedBoms.map(value => value.index), 'BOM addition indices');
  unique(manifest.addedImportedBoms.map(value => canonical(value.value)), 'BOM addition declarations');
  for (const addition of [...manifest.addedImportedBoms].sort((a, b) => b.index - a.index)) {
    requireValue(Number.isSafeInteger(addition.index) && addition.index >= 0
      && same(maven.importedBoms[addition.index], addition.value), 'server dependency BOM addition mismatch');
    maven.importedBoms.splice(addition.index, 1);
  }
  for (const change of manifest.importedBomVersionChanges) {
    requireValue(same(maven.importedBoms[change.priorIndex], { group: change.group, name: change.name,
      scope: change.scope, version: change.fromVersion }), 'server dependency prior BOM order mismatch');
  }
  unique(manifest.pluginCoordinateVersionChanges.map(value => `${value.group}:${value.name}:${value.type}`), 'plugin declarations');
  for (const change of manifest.pluginCoordinateVersionChanges) {
    requireValue(change.fromVersion !== change.toVersion, 'server dependency empty plugin transition');
    const prefix = `${change.group}:${change.name}:${change.type}:`;
    const now = prefix + change.toVersion, then = prefix + change.fromVersion;
    const index = maven.resolvedPluginCoordinates.indexOf(now);
    requireValue(index >= 0 && !maven.resolvedPluginCoordinates.includes(then), 'server dependency plugin transition mismatch');
    maven.resolvedPluginCoordinates[index] = then;
  }
  maven.resolvedPluginCoordinates.sort();
  unique(maven.resolvedPluginCoordinates, 'reversed plugin coordinates');
  requireValue(maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.to,
    'server dependency current plugin hash mismatch');
  maven.pluginResolutionSha256 = manifest.pluginResolutionHashChange.from;
  requireValue(maven.importedBoms.length === 4 && hash(prior) === BASE_GRAPH,
    'undeclared server dependency graph change');
  return prior;
}
