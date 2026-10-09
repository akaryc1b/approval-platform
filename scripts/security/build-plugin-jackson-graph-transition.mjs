import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { SERVER_DEPENDENCY_GRAPH } from './server-dependency-graph-transition.mjs';

export const BUILD_PLUGIN_JACKSON_GRAPH = 'f2e4ca66b29bd4e819f8751117e56900416d47c474de270d84e9d2ef552c92c3';
export const BUILD_PLUGIN_JACKSON_MANIFEST_SHA256 = '315f897668e2ec542fd00656413d7bc369af5b976879f3088869a6ce8b3aed52';
const CONTENT_SHA256 = '363730d08217e0cad2b0569579c6a23c2187452c4f54857f0134825addfc863d';
const REPOSITORY = 'akaryc1b/approval-platform';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const sha256 = value => createHash('sha256').update(value).digest('hex');
const hash = value => sha256(canonical(value));
const requireValue = (condition, message) => { if (!condition) throw new Error(message); };
function requireContent(value, expected, label) {
  const { contentSha256, ...payload } = value;
  requireValue(contentSha256 === expected && hash(payload) === expected, `${label} content digest mismatch`);
}
function pinned(path, expected, label) {
  const raw = readFileSync(new URL(`../../${path}`, import.meta.url));
  requireValue(sha256(raw) === expected, `${label} byte digest mismatch`);
  return JSON.parse(raw);
}
function validateManifest(manifest) {
  requireContent(manifest, CONTENT_SHA256, 'build-plugin Jackson manifest');
  requireValue(manifest.schemaVersion === 'APPROVAL_BUILD_PLUGIN_JACKSON_GRAPH_TRANSITION_V1'
    && manifest.repository === REPOSITORY && manifest.base.graphDigest === SERVER_DEPENDENCY_GRAPH
    && manifest.observed.graphDigest === BUILD_PLUGIN_JACKSON_GRAPH
    && manifest.pluginCoordinateVersionChangeCount === 2 && manifest.pluginCoordinateVersionChanges.length === 2
    && manifest.runtimeComponentChangeCount === 0 && manifest.runtimeEdgeChangeCount === 0
    && manifest.importedBomChangeCount === 0 && manifest.scopeChangeCount === 0 && manifest.licenseChangeCount === 0
    && manifest.findingReviewRequired === true && manifest.releaseBlocked === true,
  'build-plugin Jackson transition identity mismatch');
}

/** This retained capture is explicitly a modified-POM worktree, never a clean current-head witness. */
export function readBuildPluginJacksonCandidate(manifest = readBuildPluginJacksonManifest()) {
  validateManifest(manifest);
  const capture = manifest.capture;
  requireValue(capture.kind === 'LOCAL_PRECOMMIT_POM_CANDIDATE' && capture.exactHeadEvidence === false
    && capture.sourceWorktreeClean === false, 'build-plugin Jackson capture cannot claim exact-head evidence');
  const e2 = pinned(capture.e2.path, capture.e2.rawSha256, 'build-plugin Jackson candidate E2');
  requireContent(e2, capture.e2.contentSha256, 'build-plugin Jackson candidate E2');
  const accepted = e2.githubActions.acceptedDependencyGraph;
  requireValue(e2.repository === REPOSITORY && e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1'
    && e2.commitSha === capture.sourceBaseHead
    && hash({ maven: e2.maven, pnpm: e2.pnpm, githubActions: accepted.githubActions, limitations: accepted.limitations })
      === BUILD_PLUGIN_JACKSON_GRAPH, 'build-plugin Jackson candidate graph mismatch');
  return e2;
}
export function readBuildPluginJacksonManifest() {
  const manifest = pinned('docs/operations/build-plugin-jackson-transition.json',
    BUILD_PLUGIN_JACKSON_MANIFEST_SHA256, 'build-plugin Jackson manifest');
  validateManifest(manifest);
  readBuildPluginJacksonCandidate(manifest);
  return manifest;
}

/** Reverse only two plugin coordinates and their actual report hash; all other graph bytes survive. */
export function verifyBuildPluginJacksonDelta(projection, manifest = readBuildPluginJacksonManifest()) {
  validateManifest(manifest);
  requireValue(hash(projection) === BUILD_PLUGIN_JACKSON_GRAPH, 'current build-plugin Jackson graph drift');
  const previous = structuredClone(projection), maven = previous.maven;
  for (const change of manifest.pluginCoordinateVersionChanges) {
    const prefix = `${change.group}:${change.name}:${change.type}:`;
    const before = prefix + change.fromVersion, after = prefix + change.toVersion;
    const index = maven.resolvedPluginCoordinates.indexOf(after);
    requireValue(index >= 0 && !maven.resolvedPluginCoordinates.includes(before),
      'build-plugin Jackson coordinate transition mismatch');
    maven.resolvedPluginCoordinates[index] = before;
  }
  maven.resolvedPluginCoordinates.sort();
  requireValue(maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.to,
    'build-plugin Jackson report hash mismatch');
  maven.pluginResolutionSha256 = manifest.pluginResolutionHashChange.from;
  requireValue(hash(previous) === SERVER_DEPENDENCY_GRAPH, 'undeclared build-plugin Jackson graph change');
  return previous;
}
