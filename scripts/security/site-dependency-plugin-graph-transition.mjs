import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { BUILD_PLUGIN_JACKSON_GRAPH } from './build-plugin-jackson-graph-transition.mjs';

export const SITE_DEPENDENCY_PLUGIN_GRAPH = 'e7a2f92ae31e016e6691eb8625311a32e930ef1103836ad28a45a9b4c27748b6';
export const SITE_DEPENDENCY_PLUGIN_MANIFEST_SHA256 = '6aa929fb3e460451ca62f8271854c50db59f8f59e928e101c8064c9a905e0d9f';
const CONTENT_SHA256 = '33d7843d77ae98a88b4b9e51122cd4a77a04c5d8ac5a7dc3945ca6a051ae790e';
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
function uniqueSorted(values, label) {
  requireValue(Array.isArray(values) && new Set(values).size === values.length
    && canonical(values) === canonical([...values].sort()), `${label} must be unique and sorted`);
}
function validateManifest(manifest) {
  requireContent(manifest, CONTENT_SHA256, 'Site/Dependency plugin manifest');
  requireValue(manifest.schemaVersion === 'APPROVAL_SITE_DEPENDENCY_PLUGIN_GRAPH_TRANSITION_V1'
    && manifest.repository === REPOSITORY && manifest.base.graphDigest === BUILD_PLUGIN_JACKSON_GRAPH
    && manifest.observed.graphDigest === SITE_DEPENDENCY_PLUGIN_GRAPH
    && manifest.addedPluginCoordinateCount === manifest.addedPluginCoordinates.length
    && manifest.removedPluginCoordinateCount === manifest.removedPluginCoordinates.length
    && manifest.runtimeComponentChangeCount === 0 && manifest.runtimeEdgeChangeCount === 0
    && manifest.importedBomChangeCount === 0 && manifest.scopeChangeCount === 0 && manifest.licenseChangeCount === 0
    && manifest.unchangedOtherPluginOwnerCount > 0
    && manifest.findingReviewRequired === true && manifest.releaseBlocked === true
    && manifest.findingDisposition === 'UNRESOLVED_UNTIL_FRESH_COMPLETE_SCANNER_AND_EXPLICIT_FINDING_REVIEW'
    && canonical(manifest.suppressions) === '[]' && canonical(manifest.exceptions) === '[]',
  'Site/Dependency plugin transition identity mismatch');
  uniqueSorted(manifest.addedPluginCoordinates, 'added plugin coordinates');
  uniqueSorted(manifest.removedPluginCoordinates, 'removed plugin coordinates');
  requireValue(!manifest.addedPluginCoordinates.some(value => manifest.removedPluginCoordinates.includes(value)),
    'Site/Dependency plugin coordinate declarations overlap');
}

/** Retained modified-POM observations do not claim a clean current-head checkout. */
export function readSiteDependencyPluginCandidate(manifest = readSiteDependencyPluginManifest()) {
  validateManifest(manifest);
  const capture = manifest.capture;
  requireValue(capture.kind === 'LOCAL_PRECOMMIT_POM_CANDIDATE' && capture.exactHeadEvidence === false
    && capture.sourceWorktreeClean === false, 'Site/Dependency plugin capture cannot claim exact-head evidence');
  const e2 = pinned(capture.e2.path, capture.e2.rawSha256, 'Site/Dependency plugin candidate E2');
  requireContent(e2, capture.e2.contentSha256, 'Site/Dependency plugin candidate E2');
  const accepted = e2.githubActions.acceptedDependencyGraph;
  requireValue(e2.repository === REPOSITORY && e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1'
    && e2.commitSha === capture.sourceBaseHead
    && hash({ maven: e2.maven, pnpm: e2.pnpm, githubActions: accepted.githubActions, limitations: accepted.limitations })
      === SITE_DEPENDENCY_PLUGIN_GRAPH, 'Site/Dependency plugin candidate graph mismatch');
  return e2;
}
export function readSiteDependencyPluginReport(manifest = readSiteDependencyPluginManifest()) {
  validateManifest(manifest);
  const capture = manifest.capture.pluginReport;
  const report = pinned(capture.path, capture.rawSha256, 'Site/Dependency plugin report capture');
  requireValue(report.schemaVersion === 'APPROVAL_MAVEN_PLUGIN_REPORT_SEGMENTS_V1'
    && report.sourceReportSha256 === manifest.pluginResolutionHashChange.to
    && Array.isArray(report.segments) && Array.isArray(report.segmentOrder)
    && report.segmentOrder.every(index => Number.isInteger(index) && index >= 0 && index < report.segments.length)
    && report.segments.every(value => typeof value === 'string') && typeof report.delimiter === 'string',
  'Site/Dependency plugin report identity mismatch');
  const bytes = report.segmentOrder.map(index => report.segments[index]).join(report.delimiter);
  requireValue(sha256(bytes) === report.sourceReportSha256, 'Site/Dependency plugin report hash mismatch');
  return bytes;
}
export function readSiteDependencyPluginManifest() {
  const manifest = pinned('docs/operations/site-dependency-plugin-transition.json',
    SITE_DEPENDENCY_PLUGIN_MANIFEST_SHA256, 'Site/Dependency plugin manifest');
  validateManifest(manifest);
  readSiteDependencyPluginCandidate(manifest);
  readSiteDependencyPluginReport(manifest);
  return manifest;
}

/** Reverse only declared plugin set changes and the report hash, preserving all other graph bytes. */
export function verifySiteDependencyPluginDelta(projection, manifest = readSiteDependencyPluginManifest()) {
  validateManifest(manifest);
  requireValue(hash(projection) === SITE_DEPENDENCY_PLUGIN_GRAPH, 'current Site/Dependency plugin graph drift');
  const previous = structuredClone(projection), maven = previous.maven;
  uniqueSorted(maven.resolvedPluginCoordinates, 'resolved plugin coordinates');
  const coordinates = new Set(maven.resolvedPluginCoordinates);
  for (const coordinate of manifest.addedPluginCoordinates) {
    requireValue(coordinates.delete(coordinate), 'Site/Dependency plugin addition missing');
  }
  for (const coordinate of manifest.removedPluginCoordinates) {
    requireValue(!coordinates.has(coordinate), 'Site/Dependency old plugin coordinate survived');
    coordinates.add(coordinate);
  }
  maven.resolvedPluginCoordinates = [...coordinates].sort();
  requireValue(maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.to,
    'Site/Dependency plugin report hash mismatch');
  maven.pluginResolutionSha256 = manifest.pluginResolutionHashChange.from;
  requireValue(hash(previous) === BUILD_PLUGIN_JACKSON_GRAPH, 'undeclared Site/Dependency plugin graph change');
  return previous;
}
