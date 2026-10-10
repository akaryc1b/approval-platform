import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { SERVER_DEPENDENCY_GRAPH, SERVER_DEPENDENCY_MANIFEST_SHA256,
  readServerDependencyManifest, verifyServerDependencyDelta } from './server-dependency-graph-transition.mjs';
export { SERVER_DEPENDENCY_GRAPH, readServerDependencyManifest, verifyServerDependencyDelta,
  readServerDependencySourceWitness } from './server-dependency-graph-transition.mjs';

import { BUILD_PLUGIN_JACKSON_GRAPH, BUILD_PLUGIN_JACKSON_MANIFEST_SHA256,
  readBuildPluginJacksonManifest, verifyBuildPluginJacksonDelta } from './build-plugin-jackson-graph-transition.mjs';
export { BUILD_PLUGIN_JACKSON_GRAPH, readBuildPluginJacksonManifest, verifyBuildPluginJacksonDelta,
  readBuildPluginJacksonCandidate } from './build-plugin-jackson-graph-transition.mjs';

import { SITE_DEPENDENCY_PLUGIN_GRAPH, SITE_DEPENDENCY_PLUGIN_MANIFEST_SHA256,
  readSiteDependencyPluginManifest, verifySiteDependencyPluginDelta } from './site-dependency-plugin-graph-transition.mjs';
export { SITE_DEPENDENCY_PLUGIN_GRAPH, readSiteDependencyPluginManifest, verifySiteDependencyPluginDelta,
  readSiteDependencyPluginCandidate, readSiteDependencyPluginReport } from './site-dependency-plugin-graph-transition.mjs';

import { RELEASE_PLUGIN_GRAPH, RELEASE_PLUGIN_MANIFEST_SHA256,
  readReleasePluginManifest, verifyReleasePluginDelta } from './release-plugin-graph-transition.mjs';
export { RELEASE_PLUGIN_GRAPH, readReleasePluginManifest, verifyReleasePluginDelta,
  readReleasePluginCandidate, readReleasePluginReport } from './release-plugin-graph-transition.mjs';

import { CLEAN_PLUGIN_GRAPH, CLEAN_PLUGIN_MANIFEST_SHA256, readCleanPluginManifest,
  readCleanPluginPreservedReleaseLineage, verifyCleanPluginDelta, verifyCleanPluginCurrentEvidence }
  from './clean-plugin-graph-transition.mjs';
export { CLEAN_PLUGIN_GRAPH, readCleanPluginManifest, readCleanPluginCandidate, readCleanPluginReport,
  readCleanPluginBaseline, readCleanPluginSourceWitness, readCleanPluginPreservedReleaseLineage,
  verifyCleanPluginDelta, verifyCleanPluginOwnerDelta } from './clean-plugin-graph-transition.mjs';

import { COMPILER_PLUGIN_GRAPH, COMPILER_PLUGIN_MANIFEST_SHA256, readCompilerPluginManifest,
  readCompilerPluginPreservedCleanLineage, verifyCompilerPluginDelta, verifyCompilerPluginCurrentEvidence }
  from './compiler-plugin-graph-transition.mjs';
export { COMPILER_PLUGIN_GRAPH, readCompilerPluginManifest, readCompilerPluginCandidate, readCompilerPluginReport,
  readCompilerPluginBaseline, readCompilerPluginSourceWitness, readCompilerPluginPreservedCleanLineage,
  verifyCompilerPluginDelta, verifyCompilerPluginOwnerDelta } from './compiler-plugin-graph-transition.mjs';

export const BASE_GRAPH = '2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94';
export const OBSERVABILITY_GRAPH = '27bdcae01a4affff009d6b90ca04989bd14a3cb159bbef6a6a379fab84109a37';
export const OBSERVABILITY_OTEL_GRAPH = '390773d2aa746a2203eec870e5dd0a8f97f81e91913bb016c9ef95b749c0e7b3';
const OTEL_MANIFEST_SHA256 = '20e7bf496628c36b5afb66d593b57424bb092d80666c41b7097849380a98dc86';
const MANIFEST_SHA256 = '8aa64be5a9db6a6c7988226e5386592b94b91b034c10c6ccb914c2a171a26993';
const REPOSITORY = 'akaryc1b/approval-platform';
const stable = value => Array.isArray(value) ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])]))
    : value;
export const canonicalGraph = value => JSON.stringify(stable(value));
export const graphHash = value => createHash('sha256').update(canonicalGraph(value)).digest('hex');
const requireValue = (condition, message) => { if (!condition) throw new Error(message); };

export function readObservabilityManifest() {
  const raw = readFileSync(new URL('../../docs/operations/observability-dependency-transition.json', import.meta.url));
  requireValue(createHash('sha256').update(raw).digest('hex') === MANIFEST_SHA256,
    'observability dependency manifest drift');
  return JSON.parse(raw);
}

function unique(values, name) {
  requireValue(Array.isArray(values) && new Set(values).size === values.length, `${name} must be unique`);
  return new Set(values);
}

/** Reverse only declared additions and scope changes; every other graph byte is retained. */
export function verifyDependencyDelta(projection, manifest) {
  requireValue(graphHash(projection) === manifest.observed.graphDigest, 'current dependency graph drift');
  const prior = structuredClone(projection);
  const componentIds = unique(prior.maven.components.map(value => value.bomRef), 'components');
  const addedComponents = unique(manifest.addedComponents, 'added components');
  for (const id of addedComponents) requireValue(componentIds.has(id), 'declared component addition missing');
  prior.maven.components = prior.maven.components.filter(value => !addedComponents.has(value.bomRef));
  unique(manifest.scopeChanges.map(value => value.bomRef), 'scope changes');
  for (const change of manifest.scopeChanges) {
    const component = prior.maven.components.find(value => value.bomRef === change.bomRef);
    requireValue(component && component.scope === change.to && change.from !== change.to, 'component scope drift');
    component.scope = change.from;
  }
  const edges = unique(prior.maven.edges.map(canonicalGraph), 'edges');
  const additions = unique(manifest.addedEdges.map(canonicalGraph), 'added edges');
  for (const edge of additions) requireValue(edges.has(edge), 'declared edge addition missing');
  prior.maven.edges = prior.maven.edges.filter(value => !additions.has(canonicalGraph(value)));
  requireValue(graphHash(prior) === manifest.base.graphDigest, 'undeclared dependency graph change');
  return prior;
}

export function readOtelUpgradeManifest() {
  const raw = readFileSync(new URL('../../docs/operations/observability-otel-upgrade.json', import.meta.url));
  requireValue(createHash('sha256').update(raw).digest('hex') === OTEL_MANIFEST_SHA256,
    'OTel dependency manifest drift');
  const manifest = JSON.parse(raw);
  requireValue(manifest.schemaVersion === 'APPROVAL_OBSERVABILITY_OTEL_UPGRADE_V1'
    && manifest.repository === REPOSITORY && manifest.base.graphDigest === OBSERVABILITY_GRAPH
    && manifest.observed.graphDigest === OBSERVABILITY_OTEL_GRAPH, 'OTel transition identity drift');
  return manifest;
}

/** Reconstruct an evidence graph only; the scanner still receives the current, unmodified E2. */
export function verifyOtelUpgradeDelta(projection, manifest) {
  requireValue(graphHash(projection) === manifest.observed.graphDigest, 'current OTel graph drift');
  const prior = structuredClone(projection);
  unique(prior.maven.components.map(value => value.bomRef), 'components');
  unique(prior.maven.edges.map(canonicalGraph), 'edges');
  unique(prior.maven.importedBoms.map(canonicalGraph), 'imported BOMs');
  unique(manifest.versionChanges.map(value => `${value.group}:${value.name}`), 'version changes');
  const reverse = new Map();
  for (const change of manifest.versionChanges) {
    const { group, name, fromVersion, toVersion } = change;
    requireValue(typeof fromVersion === 'string' && typeof toVersion === 'string'
      && fromVersion !== toVersion, 'OTel version transition invalid');
    const currentRef = `pkg:maven/${group}/${name}@${toVersion}?type=jar`;
    const matches = prior.maven.components.filter(value => value.group === group && value.name === name);
    const component = matches[0];
    requireValue(matches.length === 1 && component.type === 'jar' && component.bomRef === currentRef
      && component.version === toVersion && component.source === `maven:${group}:${name}:${toVersion}`,
    'OTel component transition mismatch');
    const priorRef = `pkg:maven/${group}/${name}@${fromVersion}?type=jar`;
    reverse.set(currentRef, priorRef);
    component.bomRef = priorRef; component.version = fromVersion;
    component.source = `maven:${group}:${name}:${fromVersion}`;
  }
  let rewrittenEdges = 0;
  for (const edge of prior.maven.edges) {
    if (reverse.has(edge.from) || reverse.has(edge.to)) rewrittenEdges += 1;
    edge.from = reverse.get(edge.from) ?? edge.from;
    edge.to = reverse.get(edge.to) ?? edge.to;
  }
  requireValue(rewrittenEdges === manifest.rewrittenEdgeCount, 'OTel edge transition mismatch');
  const { index, value } = manifest.addedImportedBom;
  requireValue(Number.isSafeInteger(index) && index >= 0 && index < prior.maven.importedBoms.length
    && canonicalGraph(prior.maven.importedBoms[index]) === canonicalGraph(value), 'OTel BOM transition mismatch');
  prior.maven.importedBoms.splice(index, 1);
  requireValue(graphHash(prior) === manifest.base.graphDigest, 'undeclared OTel dependency change');
  return prior;
}

function otelReceipt(identity) {
  const payload = {
    schemaVersion: 'APPROVAL_OBSERVABILITY_GRAPH_LINEAGE_V2',
    repository: REPOSITORY,
    commitSha: identity.commitSha,
    sourceE2ContentSha256: identity.contentSha256,
    baseE2GraphDigest: BASE_GRAPH,
    intermediateE2GraphDigest: OBSERVABILITY_GRAPH,
    currentE2GraphDigest: OBSERVABILITY_OTEL_GRAPH,
    foundationManifestSha256: MANIFEST_SHA256,
    manifestSha256: OTEL_MANIFEST_SHA256,
    versionChangeCount: 16,
    rewrittenEdgeCount: 17,
    addedImportedBomCount: 1,
    findingReviewRequired: true,
    releaseBlocked: true,
  };
  return { ...payload, contentSha256: graphHash(payload) };
}

function receipt(identity) {
  const payload = {
    schemaVersion: 'APPROVAL_OBSERVABILITY_GRAPH_LINEAGE_V1',
    repository: REPOSITORY,
    commitSha: identity.commitSha,
    sourceE2ContentSha256: identity.contentSha256,
    baseE2GraphDigest: BASE_GRAPH,
    currentE2GraphDigest: OBSERVABILITY_GRAPH,
    manifestSha256: MANIFEST_SHA256,
    addedComponentCount: 35,
    addedEdgeCount: 39,
    scopeChangeCount: 1,
    findingReviewRequired: true,
    releaseBlocked: true,
  };
  return { ...payload, contentSha256: graphHash(payload) };
}

function serverDependencyReceipt(identity) {
  const manifest = readServerDependencyManifest();
  const payload = {
    schemaVersion: 'APPROVAL_SERVER_DEPENDENCY_GRAPH_LINEAGE_V1',
    repository: REPOSITORY,
    commitSha: identity.commitSha,
    sourceE2ContentSha256: identity.contentSha256,
    baseE2GraphDigest: BASE_GRAPH,
    intermediateE2GraphDigest: OBSERVABILITY_GRAPH,
    priorE2GraphDigest: OBSERVABILITY_OTEL_GRAPH,
    currentE2GraphDigest: SERVER_DEPENDENCY_GRAPH,
    foundationManifestSha256: MANIFEST_SHA256,
    otelManifestSha256: OTEL_MANIFEST_SHA256,
    manifestSha256: SERVER_DEPENDENCY_MANIFEST_SHA256,
    sourceWitnessSha256: manifest.sourceWitness.rawSha256,
    archivalSourceHead: manifest.observed.sourceHead,
    archivalSourceTree: manifest.observed.sourceTree,
    archivalE2ContentSha256: manifest.observed.e2ContentSha256,
    componentVersionChangeCount: 90,
    rewrittenEdgeCount: 180,
    addedImportedBomCount: 2,
    importedBomVersionChangeCount: 1,
    pluginCoordinateVersionChangeCount: 13,
    scopeChangeCount: 0,
    licenseChangeCount: 0,
    inventory: manifest.inventory,
    findingReviewRequired: true,
    releaseBlocked: true,
  };
  return { ...payload, contentSha256: graphHash(payload) };
}

function buildPluginJacksonReceipt(identity) {
  const manifest = readBuildPluginJacksonManifest();
  const payload = {
    schemaVersion: 'APPROVAL_BUILD_PLUGIN_JACKSON_GRAPH_LINEAGE_V1',
    repository: REPOSITORY, commitSha: identity.commitSha, sourceE2ContentSha256: identity.contentSha256,
    baseE2GraphDigest: BASE_GRAPH, priorE2GraphDigest: SERVER_DEPENDENCY_GRAPH,
    currentE2GraphDigest: BUILD_PLUGIN_JACKSON_GRAPH,
    manifestSha256: BUILD_PLUGIN_JACKSON_MANIFEST_SHA256,
    preservedServerGraphLineage: serverDependencyReceipt({ commitSha: manifest.base.sourceHead,
      contentSha256: manifest.base.e2ContentSha256 }),
    archivalCandidate: manifest.capture,
    pluginCoordinateVersionChangeCount: 2, runtimeComponentChangeCount: 0,
    runtimeEdgeChangeCount: 0, importedBomChangeCount: 0, scopeChangeCount: 0, licenseChangeCount: 0,
    inventory: manifest.inventory, findingReviewRequired: true, releaseBlocked: true,
  };
  return { ...payload, contentSha256: graphHash(payload) };
}

function siteDependencyPluginReceipt(identity) {
  const manifest = readSiteDependencyPluginManifest();
  const payload = {
    schemaVersion: 'APPROVAL_SITE_DEPENDENCY_PLUGIN_GRAPH_LINEAGE_V1',
    repository: REPOSITORY, commitSha: identity.commitSha, sourceE2ContentSha256: identity.contentSha256,
    baseE2GraphDigest: BASE_GRAPH, priorE2GraphDigest: BUILD_PLUGIN_JACKSON_GRAPH,
    currentE2GraphDigest: SITE_DEPENDENCY_PLUGIN_GRAPH,
    manifestSha256: SITE_DEPENDENCY_PLUGIN_MANIFEST_SHA256,
    preservedBuildPluginJacksonLineage: buildPluginJacksonReceipt({ commitSha: manifest.base.sourceHead,
      contentSha256: manifest.base.e2ContentSha256 }),
    archivalCandidate: manifest.capture,
    addedPluginCoordinateCount: manifest.addedPluginCoordinateCount,
    removedPluginCoordinateCount: manifest.removedPluginCoordinateCount,
    unchangedOtherPluginOwnerCount: manifest.unchangedOtherPluginOwnerCount,
    runtimeComponentChangeCount: 0, runtimeEdgeChangeCount: 0, importedBomChangeCount: 0,
    scopeChangeCount: 0, licenseChangeCount: 0,
    inventory: manifest.inventory, findingReviewRequired: true, releaseBlocked: true,
  };
  return { ...payload, contentSha256: graphHash(payload) };
}

function releasePluginReceipt(identity) {
  const manifest = readReleasePluginManifest();
  const payload = {
    schemaVersion: 'APPROVAL_RELEASE_PLUGIN_GRAPH_LINEAGE_V1',
    repository: REPOSITORY, commitSha: identity.commitSha, sourceE2ContentSha256: identity.contentSha256,
    baseE2GraphDigest: BASE_GRAPH, priorE2GraphDigest: SITE_DEPENDENCY_PLUGIN_GRAPH,
    currentE2GraphDigest: RELEASE_PLUGIN_GRAPH,
    manifestSha256: RELEASE_PLUGIN_MANIFEST_SHA256,
    preservedSiteDependencyPluginLineage: siteDependencyPluginReceipt({ commitSha: manifest.base.sourceHead,
      contentSha256: manifest.base.e2ContentSha256 }),
    archivalCandidate: manifest.capture,
    addedPluginCoordinateCount: manifest.addedPluginCoordinateCount,
    removedPluginCoordinateCount: manifest.removedPluginCoordinateCount,
    unchangedOtherPluginOwnerCount: manifest.unchangedOtherPluginOwnerCount,
    runtimeComponentChangeCount: 0, runtimeEdgeChangeCount: 0, importedBomChangeCount: 0,
    scopeChangeCount: 0, licenseChangeCount: 0,
    inventory: manifest.inventory, findingReviewRequired: true, releaseBlocked: true,
  };
  return { ...payload, contentSha256: graphHash(payload) };
}

function cleanPluginReceipt(identity) {
  const manifest = readCleanPluginManifest(), preserved = readCleanPluginPreservedReleaseLineage(manifest);
  requireValue(canonicalGraph(preserved) === canonicalGraph(releasePluginReceipt({
    commitSha: manifest.base.sourceHead, contentSha256: manifest.base.e2.contentSha256,
  })), 'Clean preserved Release receipt mismatch');
  const payload = {
    schemaVersion: 'APPROVAL_CLEAN_PLUGIN_GRAPH_LINEAGE_V1',
    repository: REPOSITORY, commitSha: identity.commitSha, sourceE2ContentSha256: identity.contentSha256,
    baseE2GraphDigest: BASE_GRAPH, priorE2GraphDigest: RELEASE_PLUGIN_GRAPH,
    currentE2GraphDigest: CLEAN_PLUGIN_GRAPH, manifestSha256: CLEAN_PLUGIN_MANIFEST_SHA256,
    sourceWitnessSha256: manifest.capture.sourceWitness.rawSha256,
    preservedReleasePluginLineage: preserved, archivalCandidate: manifest.capture,
    addedPluginCoordinateCount: 0, removedPluginCoordinateCount: 1,
    unchangedOtherPluginOwnerCount: 16, runtimeComponentChangeCount: 0, runtimeEdgeChangeCount: 0,
    importedBomChangeCount: 0, scopeChangeCount: 0, licenseChangeCount: 0,
    inventory: manifest.inventory, findingReviewRequired: true, releaseBlocked: true,
  };
  return { ...payload, contentSha256: graphHash(payload) };
}

function compilerPluginReceipt(identity) {
  const manifest = readCompilerPluginManifest(), preserved = readCompilerPluginPreservedCleanLineage(manifest);
  requireValue(canonicalGraph(preserved) === canonicalGraph(cleanPluginReceipt({
    commitSha: manifest.base.sourceHead, contentSha256: manifest.base.e2.contentSha256,
  })), 'Compiler preserved Clean receipt mismatch');
  const payload = {
    schemaVersion: 'APPROVAL_COMPILER_PLUGIN_GRAPH_LINEAGE_V1',
    repository: REPOSITORY, commitSha: identity.commitSha, sourceE2ContentSha256: identity.contentSha256,
    baseE2GraphDigest: BASE_GRAPH, priorE2GraphDigest: CLEAN_PLUGIN_GRAPH,
    currentE2GraphDigest: COMPILER_PLUGIN_GRAPH, manifestSha256: COMPILER_PLUGIN_MANIFEST_SHA256,
    sourceWitnessSha256: manifest.capture.sourceWitness.rawSha256,
    preservedCleanPluginLineage: preserved, archivalCandidate: manifest.capture,
    addedPluginCoordinateCount: 0, removedPluginCoordinateCount: 1,
    unchangedOtherPluginOwnerCount: 16, runtimeComponentChangeCount: 0, runtimeEdgeChangeCount: 0,
    importedBomChangeCount: 0, scopeChangeCount: 0, licenseChangeCount: 0,
    inventory: manifest.inventory, findingReviewRequired: true, releaseBlocked: true,
  };
  return { ...payload, contentSha256: graphHash(payload) };
}

/** Graph admission is not finding disposition or production authorization. */
export function verifyObservabilityGraph(e2, projection, expectedBaseDigest, expectedCommitSha) {
  requireValue(e2.repository === REPOSITORY && /^[0-9a-f]{40}$/.test(e2.commitSha || ''),
    'E2 graph identity mismatch');
  if (expectedCommitSha !== undefined) requireValue(/^[0-9a-f]{40}$/.test(expectedCommitSha)
    && e2.commitSha === expectedCommitSha, 'E2 current verified head mismatch');
  const { contentSha256, ...payload } = e2;
  requireValue(graphHash(payload) === contentSha256, 'E2 content digest mismatch');
  const accepted = e2.githubActions?.acceptedDependencyGraph;
  requireValue(canonicalGraph(projection) === canonicalGraph({
    maven: e2.maven, pnpm: e2.pnpm,
    githubActions: accepted ? accepted.githubActions : e2.githubActions,
    limitations: accepted ? accepted.limitations : e2.limitations,
  }), 'E2 graph projection mismatch');
  const digest = graphHash(projection);
  requireValue(expectedBaseDigest === BASE_GRAPH, 'unrecognized dependency graph baseline');
  if ([SERVER_DEPENDENCY_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH, SITE_DEPENDENCY_PLUGIN_GRAPH, RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH, COMPILER_PLUGIN_GRAPH].includes(digest)) requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1'
    && /^[0-9a-f]{40}$/.test(expectedCommitSha || '') && e2.commitSha === expectedCommitSha,
  'server dependency E2 requires current verified head and schema');
  if (digest === expectedBaseDigest) return null;
  requireValue(expectedBaseDigest === BASE_GRAPH
    && [OBSERVABILITY_GRAPH, OBSERVABILITY_OTEL_GRAPH, SERVER_DEPENDENCY_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH, SITE_DEPENDENCY_PLUGIN_GRAPH, RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH, COMPILER_PLUGIN_GRAPH].includes(digest),
    `E2 graph drift ${digest}`);
  const manifest = readObservabilityManifest();
  requireValue(manifest.repository === REPOSITORY && manifest.base.graphDigest === BASE_GRAPH
    && manifest.observed.graphDigest === OBSERVABILITY_GRAPH, 'graph transition identity drift');
  if (digest === CLEAN_PLUGIN_GRAPH) verifyCleanPluginCurrentEvidence(e2);
  if (digest === COMPILER_PLUGIN_GRAPH) verifyCompilerPluginCurrentEvidence(e2);
  const beforeCompiler = digest === COMPILER_PLUGIN_GRAPH ? verifyCompilerPluginDelta(projection) : projection;
  const beforeClean = [CLEAN_PLUGIN_GRAPH, COMPILER_PLUGIN_GRAPH].includes(digest) ? verifyCleanPluginDelta(beforeCompiler) : beforeCompiler;
  const beforeRelease = [RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH, COMPILER_PLUGIN_GRAPH].includes(digest) ? verifyReleasePluginDelta(beforeClean) : beforeClean;
  const beforeSite = [SITE_DEPENDENCY_PLUGIN_GRAPH, RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH, COMPILER_PLUGIN_GRAPH].includes(digest)
    ? verifySiteDependencyPluginDelta(beforeRelease) : beforeRelease;
  const beforePlugin = [BUILD_PLUGIN_JACKSON_GRAPH, SITE_DEPENDENCY_PLUGIN_GRAPH, RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH, COMPILER_PLUGIN_GRAPH].includes(digest)
    ? verifyBuildPluginJacksonDelta(beforeSite) : beforeSite;
  const beforeServer = [SERVER_DEPENDENCY_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH, SITE_DEPENDENCY_PLUGIN_GRAPH, RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH, COMPILER_PLUGIN_GRAPH].includes(digest)
    ? verifyServerDependencyDelta(beforePlugin) : beforePlugin;
  const previous = [OBSERVABILITY_OTEL_GRAPH, SERVER_DEPENDENCY_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH, SITE_DEPENDENCY_PLUGIN_GRAPH, RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH, COMPILER_PLUGIN_GRAPH].includes(digest)
    ? verifyOtelUpgradeDelta(beforeServer, readOtelUpgradeManifest()) : beforeServer;
  verifyDependencyDelta(previous, manifest);
  if (digest === COMPILER_PLUGIN_GRAPH) return compilerPluginReceipt(e2);
  if (digest === CLEAN_PLUGIN_GRAPH) return cleanPluginReceipt(e2);
  if (digest === RELEASE_PLUGIN_GRAPH) return releasePluginReceipt(e2);
  if (digest === SITE_DEPENDENCY_PLUGIN_GRAPH) return siteDependencyPluginReceipt(e2);
  if (digest === BUILD_PLUGIN_JACKSON_GRAPH) return buildPluginJacksonReceipt(e2);
  if (digest === SERVER_DEPENDENCY_GRAPH) return serverDependencyReceipt(e2);
  return digest === OBSERVABILITY_OTEL_GRAPH ? otelReceipt(e2) : receipt(e2);
}

/** Bind the historical pgjdbc proof to the separately validated current E4 graph. */
export function requirePreservedGraph(e4, historicalGraph, expectedCommitSha) {
  requireValue(historicalGraph === BASE_GRAPH, 'unrecognized preserved dependency graph baseline');
  if (expectedCommitSha !== undefined) requireValue(/^[0-9a-f]{40}$/.test(expectedCommitSha)
    && e4.commitSha === expectedCommitSha, 'remediation current verified head mismatch');
  if (e4.e2GraphDigest === historicalGraph) {
    requireValue(e4.e2GraphTransition === undefined, 'unexpected dependency graph lineage');
    return null;
  }
  requireValue(e4.repository === REPOSITORY && historicalGraph === BASE_GRAPH
    && [OBSERVABILITY_GRAPH, OBSERVABILITY_OTEL_GRAPH, SERVER_DEPENDENCY_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH, SITE_DEPENDENCY_PLUGIN_GRAPH, RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH, COMPILER_PLUGIN_GRAPH].includes(e4.e2GraphDigest)
    && /^[0-9a-f]{40}$/.test(e4.commitSha || '')
    && /^[0-9a-f]{64}$/.test(e4.e2CurrentContentSha256 || ''), 'remediation E2 graph mismatch');
  readObservabilityManifest();
  const compiler = e4.e2GraphDigest === COMPILER_PLUGIN_GRAPH;
  const clean = compiler || e4.e2GraphDigest === CLEAN_PLUGIN_GRAPH;
  const release = clean || e4.e2GraphDigest === RELEASE_PLUGIN_GRAPH;
  const site = release || e4.e2GraphDigest === SITE_DEPENDENCY_PLUGIN_GRAPH;
  const plugin = site || e4.e2GraphDigest === BUILD_PLUGIN_JACKSON_GRAPH;
  const server = plugin || e4.e2GraphDigest === SERVER_DEPENDENCY_GRAPH;
  if (server) requireValue(/^[0-9a-f]{40}$/.test(expectedCommitSha || '')
    && e4.commitSha === expectedCommitSha, 'server dependency receipt requires current verified head');
  const upgraded = server || e4.e2GraphDigest === OBSERVABILITY_OTEL_GRAPH;
  if (upgraded) readOtelUpgradeManifest();
  const expected = (compiler ? compilerPluginReceipt : clean ? cleanPluginReceipt : release ? releasePluginReceipt : site ? siteDependencyPluginReceipt : plugin ? buildPluginJacksonReceipt : server ? serverDependencyReceipt : upgraded ? otelReceipt : receipt)(
    { commitSha: e4.commitSha, contentSha256: e4.e2CurrentContentSha256 });
  requireValue(canonicalGraph(e4.e2GraphTransition) === canonicalGraph(expected),
    'remediation E2 graph lineage mismatch');
  return expected;
}
