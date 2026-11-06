import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, realpathSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { acceptedE2GraphProjection, resolveGitHubActionsEvidence } from './m6-pr-e-e2-generate-sbom.mjs';
import { parseCheckstylePluginOwners } from './checkstyle-plugin-graph-transition.mjs';
import { osvInputFromE2 } from './osv-scan-coverage.mjs';
import { requireCombinedOwnerSource, reverseCombinedOwnerPom } from './combined-owner-source.mjs';

export const COMBINED_OWNER_GRAPH = '9b7e635903e6011c24aa92bf7ab6638dd698d62fd8a4c75505e2455b42f0c774';
export const COMBINED_OWNER_MANIFEST_SHA256 = 'c1d3fb3d0b5602c4738411a476b3e08d8a5ae65a6dd0264457ca5427cc2e1c56';
const PRIOR_GRAPH = 'c1b04674b8dd67b29e0b720e793b62fe354be6a2cedef544e44abaa8614cf062';
const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const MANIFEST_PATH = 'docs/operations/combined-owner-evidence/capture-manifest.json';
const PRIOR_REPORT = 'docs/operations/resources-flatten-evidence/plugin-owner-report.txt';
const REPOSITORY = 'akaryc1b/approval-platform';
const SHA40 = /^[0-9a-f]{40}$/, SHA256 = /^[0-9a-f]{64}$/;
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const sha = value => createHash('sha256').update(value).digest('hex');
const hash = value => sha(canonical(value));
const same = (a, b) => canonical(a) === canonical(b);
const requireValue = (ok, message) => { if (!ok) throw new Error(`Combined owner graph: ${message}`); };
function raw(path, expected, size) {
  requireValue(typeof path === 'string' && /^[A-Za-z0-9_./-]+$/.test(path) && !path.startsWith('/')
    && path.split('/').every(part => part && part !== '.' && part !== '..'), 'unsafe source path');
  const root = resolve(ROOT), file = resolve(root, path);
  requireValue(realpathSync(root) === root && realpathSync(file) === file && lstatSync(file).isFile()
    && !lstatSync(file).isSymbolicLink() && lstatSync(file).size <= 16 * 1024 * 1024, 'bounded regular source file required');
  const bytes = readFileSync(file);
  requireValue(bytes.length <= 16 * 1024 * 1024 && (size === undefined || bytes.length === size)
    && (!expected || (SHA256.test(expected) && sha(bytes) === expected)), `source digest mismatch: ${path}`);
  return bytes;
}
function sealed(value, label) {
  requireValue(value && SHA256.test(value.contentSha256 || ''), `${label} seal missing`);
  const { contentSha256, ...payload } = value;
  requireValue(hash(payload) === contentSha256, `${label} seal mismatch`);
}
export function readCombinedOwnerManifest() {
  return JSON.parse(raw(MANIFEST_PATH, COMBINED_OWNER_MANIFEST_SHA256));
}
function validateManifest(manifest) {
  requireValue(same(manifest, readCombinedOwnerManifest()), 'capture manifest drift');
  requireValue(manifest.schemaVersion === 'APPROVAL_COMBINED_OWNER_CAPTURE_V1'
    && manifest.repository === REPOSITORY && manifest.graphDigest === COMBINED_OWNER_GRAPH
    && manifest.scanEvidenceIncluded === false && manifest.releaseBlocked === true, 'manifest identity');
}
function capture(name, manifest) {
  const pin = manifest.files[name];
  requireValue(pin && pin.path === `docs/operations/combined-owner-evidence/${name}`, 'capture membership');
  return raw(pin.path, pin.sha256, pin.bytes);
}
function jsonCapture(name, manifest) { return JSON.parse(capture(name, manifest)); }
/** Exact read closure of a successful, read-only accepted RF verifier invocation.
 * No historical verifier, Maven command, scanner or vulnerability probe runs here.
 */
function sourceClosure(manifest) {
  const closure = jsonCapture('accepted-rf-read-closure.json', manifest);
  requireValue(Array.isArray(closure) && closure.length === 223
    && new Set(closure.map(entry => entry.path)).size === 223, 'accepted read closure membership');
  for (const entry of closure) {
    if (entry.path === 'pom.xml') {
      const prior = reverseCombinedOwnerPom(raw(entry.path));
      requireValue(prior.length === entry.bytes && sha(prior) === entry.sha256, 'root POM predecessor mismatch');
    } else raw(entry.path, entry.sha256, entry.bytes);
  }
  requireCombinedOwnerSource(ROOT);
  const models = jsonCapture('model-summary.json', manifest);
  requireValue(models.captureStatus === 'ACTUAL_MAVEN_EFFECTIVE_MODELS'
    && models.demoProfile === 'product-readiness-demo' && Object.keys(models.sourcePomHashes).length === 28,
  'actual model capture identity');
  for (const [path, expected] of Object.entries(models.sourcePomHashes)) raw(path, expected);
  for (const mode of ['normal', 'demo']) requireValue(models[mode].projectCount === 26
    && models[mode].projects.length === 26 && new Set(models[mode].projects.map(row => row.artifactId)).size === 26,
  'actual effective model inventory');
  return closure;
}
function directEvidence(manifest) {
  validateManifest(manifest); sourceClosure(manifest);
  const accepted = jsonCapture('accepted-pr174-E2.json', manifest), candidate = jsonCapture('candidate-E2.json', manifest);
  sealed(accepted, 'accepted E2'); sealed(candidate, 'captured E2');
  requireValue(accepted.commitSha === manifest.acceptedHead && accepted.repository === REPOSITORY
    && hash(acceptedE2GraphProjection(accepted)) === PRIOR_GRAPH, 'accepted E2 anchor');
  const receipt = jsonCapture('accepted-rf-receipt.json', manifest); sealed(receipt, 'accepted RF receipt');
  requireValue(receipt.commitSha === accepted.commitSha && receipt.sourceE2ContentSha256 === accepted.contentSha256
    && receipt.currentE2GraphDigest === PRIOR_GRAPH && receipt.releaseBlocked === true, 'accepted RF receipt binding');
  const beforeRaw = raw(PRIOR_REPORT).toString(), afterRaw = capture('plugin-owner-report.txt', manifest).toString();
  return { accepted, candidate, receipt, beforeRaw, afterRaw };
}
/** Pure, exact graph comparison for negative tests; not source admission by itself. */
export function verifyCombinedOwnerGraphSubstitution(projection, baseline, beforeRaw, afterRaw) {
  requireValue(hash(projection) === COMBINED_OWNER_GRAPH && hash(baseline) === PRIOR_GRAPH, 'exact graph digest mismatch');
  const before = parseCheckstylePluginOwners(beforeRaw), after = parseCheckstylePluginOwners(afterRaw);
  const ownerKey = coordinate => coordinate.split(':').slice(0, 2).join(':');
  const ownerMap = value => new Map(Object.entries(value.owners).map(([name, coords]) => [ownerKey(name), { name, coords }]));
  const a = ownerMap(before), b = ownerMap(after), names = [...a.keys()].sort();
  requireValue(a.size === 17 && b.size === 17 && same(names, [...b.keys()].sort()), 'owner inventory mismatch');
  const changed = names.filter(name => !same(a.get(name).coords, b.get(name).coords));
  requireValue(same(changed, ['maven-assembly-plugin', 'maven-checkstyle-plugin', 'maven-dependency-plugin', 'maven-site-plugin']
    .map(name => `org.apache.maven.plugins:${name}`).sort()), 'four-owner scope mismatch');
  for (const name of names) requireValue(before.occurrences[a.get(name).name] === after.occurrences[b.get(name).name], 'owner occurrence drift');
  const union = value => [...new Set(Object.values(value.owners).flat())].sort();
  requireValue(same(baseline.maven.resolvedPluginCoordinates, union(before))
    && same(projection.maven.resolvedPluginCoordinates, union(after)), 'owner union differs from E2');
  for (const field of ['components', 'edges', 'reactorRoots', 'importedBoms'])
    requireValue(same(projection.maven[field], baseline.maven[field]), `runtime ${field} changed`);
  requireValue(same(projection.pnpm, baseline.pnpm) && same(projection.githubActions, baseline.githubActions), 'pnpm or workflow graph changed');
  requireValue(same(union(before).filter(value => value.includes(':plexus-utils:')),
    union(after).filter(value => value.includes(':plexus-utils:'))), 'Utils coordinates changed');
  requireValue(osvInputFromE2(projection).packageCount === 457, 'exact current target inventory');
  return baseline;
}
function delta(projection, evidence) {
  return verifyCombinedOwnerGraphSubstitution(projection, acceptedE2GraphProjection(evidence.accepted), evidence.beforeRaw, evidence.afterRaw);
}
export function readCombinedOwnerCandidate(manifest = readCombinedOwnerManifest()) { return directEvidence(manifest).candidate; }
export function readCombinedOwnerAcceptedE2(manifest = readCombinedOwnerManifest()) { return directEvidence(manifest).accepted; }
export function readCombinedOwnerAcceptedServerRemediation(manifest = readCombinedOwnerManifest()) {
  directEvidence(manifest);
  const server = jsonCapture('accepted-server-remediation.json', manifest); sealed(server, 'accepted server remediation');
  requireValue(server.releaseBlocked === true, 'historical release barrier'); return server;
}
export function verifyCombinedOwnerDelta(projection, manifest = readCombinedOwnerManifest()) {
  const evidence = directEvidence(manifest), previous = delta(projection, evidence); sourceClosure(manifest); return previous;
}
function currentIdentity(e2, expectedCommitSha, evidence) {
  requireValue(SHA40.test(expectedCommitSha || '') && e2?.commitSha === expectedCommitSha, 'current verified head required');
  sealed(e2, 'current E2');
  const { commitSha, contentSha256, ...payload } = e2;
  const { commitSha: capturedHead, contentSha256: capturedSeal, ...captured } = evidence.candidate;
  requireValue(e2.repository === REPOSITORY && e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1'
    && same(payload, captured) && same(resolveGitHubActionsEvidence(ROOT), captured.githubActions), 'current E2 source/Actions/toolchain drift');
}
export function verifyCombinedOwnerCurrentEvidence(e2, expectedCommitSha, manifest = readCombinedOwnerManifest()) {
  const evidence = directEvidence(manifest); currentIdentity(e2, expectedCommitSha, evidence);
  delta(acceptedE2GraphProjection(e2), evidence); sourceClosure(manifest);
}
export function combinedOwnerReceipt(identity, expectedCommitSha) {
  const manifest = readCombinedOwnerManifest(), evidence = directEvidence(manifest);
  requireValue(SHA40.test(expectedCommitSha || '') && identity?.commitSha === expectedCommitSha, 'current verified head required');
  const { contentSha256: ignored, ...captured } = evidence.candidate;
  const payload = { ...captured, commitSha: expectedCommitSha };
  requireValue(hash(payload) === identity.contentSha256, 'receipt current E2 identity mismatch');
  const e2 = same(Object.keys(identity).sort(), ['commitSha', 'contentSha256']) ? { ...payload, contentSha256: identity.contentSha256 } : identity;
  currentIdentity(e2, expectedCommitSha, evidence); delta(acceptedE2GraphProjection(e2), evidence); sourceClosure(manifest);
  const receipt = { schemaVersion: 'APPROVAL_COMBINED_OWNER_GRAPH_LINEAGE_V1', repository: REPOSITORY,
    commitSha: expectedCommitSha, sourceE2ContentSha256: identity.contentSha256,
    priorE2GraphDigest: PRIOR_GRAPH, currentE2GraphDigest: COMBINED_OWNER_GRAPH,
    manifestSha256: COMBINED_OWNER_MANIFEST_SHA256, preservedResourcesFlattenLineage: evidence.receipt,
    acceptedReadClosureSha256: manifest.files['accepted-rf-read-closure.json'].sha256,
    actualModelSummarySha256: manifest.files['model-summary.json'].sha256,
    changedOwnerCount: 4, unchangedOtherPluginOwnerCount: 13, runtimeComponentChangeCount: 0,
    runtimeEdgeChangeCount: 0, importedBomChangeCount: 0, scopeChangeCount: 0, licenseChangeCount: 0,
    inputPackageCount: 457, scannerExecutionClaimed: false, vulnerabilityReproductionExecuted: false,
    findingReviewRequired: true, releaseBlocked: true };
  return { ...receipt, contentSha256: hash(receipt) };
}
