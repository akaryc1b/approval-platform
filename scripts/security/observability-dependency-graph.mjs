import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

export const BASE_GRAPH = '2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94';
export const OBSERVABILITY_GRAPH = '27bdcae01a4affff009d6b90ca04989bd14a3cb159bbef6a6a379fab84109a37';
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

/** Graph admission is not finding disposition or production authorization. */
export function verifyObservabilityGraph(e2, projection, expectedBaseDigest) {
  requireValue(e2.repository === REPOSITORY && /^[0-9a-f]{40}$/.test(e2.commitSha || ''),
    'E2 graph identity mismatch');
  const { contentSha256, ...payload } = e2;
  requireValue(graphHash(payload) === contentSha256, 'E2 content digest mismatch');
  const accepted = e2.githubActions?.acceptedDependencyGraph;
  requireValue(canonicalGraph(projection) === canonicalGraph({
    maven: e2.maven, pnpm: e2.pnpm,
    githubActions: accepted ? accepted.githubActions : e2.githubActions,
    limitations: accepted ? accepted.limitations : e2.limitations,
  }), 'E2 graph projection mismatch');
  const digest = graphHash(projection);
  if (digest === expectedBaseDigest) return null;
  requireValue(expectedBaseDigest === BASE_GRAPH && digest === OBSERVABILITY_GRAPH,
    `E2 graph drift ${digest}`);
  const manifest = readObservabilityManifest();
  requireValue(manifest.repository === REPOSITORY && manifest.base.graphDigest === BASE_GRAPH
    && manifest.observed.graphDigest === OBSERVABILITY_GRAPH, 'graph transition identity drift');
  verifyDependencyDelta(projection, manifest);
  return receipt(e2);
}

/** Bind the historical pgjdbc proof to the separately validated current E4 graph. */
export function requirePreservedGraph(e4, historicalGraph) {
  if (e4.e2GraphDigest === historicalGraph) {
    requireValue(e4.e2GraphTransition === undefined, 'unexpected dependency graph lineage');
    return null;
  }
  requireValue(e4.repository === REPOSITORY && historicalGraph === BASE_GRAPH
    && e4.e2GraphDigest === OBSERVABILITY_GRAPH
    && /^[0-9a-f]{40}$/.test(e4.commitSha || '')
    && /^[0-9a-f]{64}$/.test(e4.e2CurrentContentSha256 || ''), 'remediation E2 graph mismatch');
  readObservabilityManifest();
  const expected = receipt({ commitSha: e4.commitSha, contentSha256: e4.e2CurrentContentSha256 });
  requireValue(canonicalGraph(e4.e2GraphTransition) === canonicalGraph(expected),
    'remediation E2 graph lineage mismatch');
  return expected;
}
