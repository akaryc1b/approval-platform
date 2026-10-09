import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { SITE_DEPENDENCY_PLUGIN_GRAPH } from './site-dependency-plugin-graph-transition.mjs';

export const RELEASE_PLUGIN_GRAPH = '7a719e7a07e9c7c51c5dddc228f4f00cb89ec7a62b4731067d2d977bc72ebc63';
export const RELEASE_PLUGIN_MANIFEST_SHA256 = 'a696e3ea1e23ca22838c90b7387f12e0a87e5c4f8ec927ebcd44ca93ac754ed9';
const CONTENT_SHA256 = 'bb6859cb42bb4e10c716cd82ca317349c157bafd284846838dc6edb300e77432';
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
  requireContent(manifest, CONTENT_SHA256, 'Release plugin manifest');
  requireValue(manifest.schemaVersion === 'APPROVAL_RELEASE_PLUGIN_GRAPH_TRANSITION_V1'
    && manifest.repository === REPOSITORY && manifest.base.graphDigest === SITE_DEPENDENCY_PLUGIN_GRAPH
    && manifest.observed.graphDigest === RELEASE_PLUGIN_GRAPH
    && manifest.addedPluginCoordinateCount === manifest.addedPluginCoordinates.length
    && manifest.removedPluginCoordinateCount === manifest.removedPluginCoordinates.length
    && manifest.runtimeComponentChangeCount === 0 && manifest.runtimeEdgeChangeCount === 0
    && manifest.importedBomChangeCount === 0 && manifest.scopeChangeCount === 0 && manifest.licenseChangeCount === 0
    && manifest.unchangedOtherPluginOwnerCount > 0
    && manifest.findingReviewRequired === true && manifest.releaseBlocked === true
    && manifest.findingDisposition === 'UNRESOLVED_UNTIL_FRESH_COMPLETE_SCANNER_AND_EXPLICIT_FINDING_REVIEW'
    && canonical(manifest.suppressions) === '[]' && canonical(manifest.exceptions) === '[]',
  'Release plugin transition identity mismatch');
  uniqueSorted(manifest.addedPluginCoordinates, 'added plugin coordinates');
  uniqueSorted(manifest.removedPluginCoordinates, 'removed plugin coordinates');
  requireValue(!manifest.addedPluginCoordinates.some(value => manifest.removedPluginCoordinates.includes(value)),
    'Release plugin coordinate declarations overlap');
}

/** Retained modified-POM observations do not claim a clean current-head checkout. */
export function readReleasePluginCandidate(manifest = readReleasePluginManifest()) {
  validateManifest(manifest);
  const capture = manifest.capture;
  requireValue(capture.kind === 'LOCAL_PRECOMMIT_POM_CANDIDATE' && capture.exactHeadEvidence === false
    && capture.sourceWorktreeClean === false, 'Release plugin capture cannot claim exact-head evidence');
  const e2 = pinned(capture.e2.path, capture.e2.rawSha256, 'Release plugin candidate E2');
  requireContent(e2, capture.e2.contentSha256, 'Release plugin candidate E2');
  const accepted = e2.githubActions.acceptedDependencyGraph;
  requireValue(e2.repository === REPOSITORY && e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1'
    && e2.commitSha === capture.sourceBaseHead
    && hash({ maven: e2.maven, pnpm: e2.pnpm, githubActions: accepted.githubActions, limitations: accepted.limitations })
      === RELEASE_PLUGIN_GRAPH, 'Release plugin candidate graph mismatch');
  return e2;
}
export function readReleasePluginReport(manifest = readReleasePluginManifest()) {
  validateManifest(manifest);
  const capture = manifest.capture.pluginReport;
  const report = pinned(capture.path, capture.rawSha256, 'Release plugin report capture');
  requireValue(report.schemaVersion === 'APPROVAL_MAVEN_PLUGIN_REPORT_SEGMENTS_V1'
    && report.sourceReportSha256 === manifest.pluginResolutionHashChange.to
    && Array.isArray(report.segments) && Array.isArray(report.segmentOrder)
    && report.segmentOrder.every(index => Number.isInteger(index) && index >= 0 && index < report.segments.length)
    && report.segments.every(value => typeof value === 'string') && typeof report.delimiter === 'string',
  'Release plugin report identity mismatch');
  const bytes = report.segmentOrder.map(index => report.segments[index]).join(report.delimiter);
  requireValue(sha256(bytes) === report.sourceReportSha256, 'Release plugin report hash mismatch');
  return bytes;
}
export function readReleasePluginManifest() {
  const manifest = pinned('docs/operations/release-plugin-transition.json',
    RELEASE_PLUGIN_MANIFEST_SHA256, 'Release plugin manifest');
  validateManifest(manifest);
  readReleasePluginCandidate(manifest);
  readReleasePluginReport(manifest);
  return manifest;
}

/** Reverse only declared plugin set changes and the report hash, preserving all other graph bytes. */
export function verifyReleasePluginDelta(projection, manifest = readReleasePluginManifest()) {
  validateManifest(manifest);
  requireValue(hash(projection) === RELEASE_PLUGIN_GRAPH, 'current Release plugin graph drift');
  const previous = structuredClone(projection), maven = previous.maven;
  uniqueSorted(maven.resolvedPluginCoordinates, 'resolved plugin coordinates');
  const coordinates = new Set(maven.resolvedPluginCoordinates);
  for (const coordinate of manifest.addedPluginCoordinates) {
    requireValue(coordinates.delete(coordinate), 'Release plugin addition missing');
  }
  for (const coordinate of manifest.removedPluginCoordinates) {
    requireValue(!coordinates.has(coordinate), 'Release old plugin coordinate survived');
    coordinates.add(coordinate);
  }
  maven.resolvedPluginCoordinates = [...coordinates].sort();
  requireValue(maven.pluginResolutionSha256 === manifest.pluginResolutionHashChange.to,
    'Release plugin report hash mismatch');
  maven.pluginResolutionSha256 = manifest.pluginResolutionHashChange.from;
  requireValue(hash(previous) === SITE_DEPENDENCY_PLUGIN_GRAPH, 'undeclared Release plugin graph change');
  return previous;
}
