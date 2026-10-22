// Exact local capture fields; historical absolute paths are data, never opened here.
// Reviewed scalar values independently match runtime bytes and equivalent retained
// Maven artifact bytes; neither hash shape nor a path prefix grants a decision.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { commitObject, verifyPath, objectHash } from './gitleaks-git-source-proof.mjs';
import { RESOURCES_FLATTEN_GRAPH, readResourcesFlattenManifest,
  readResourcesFlattenCandidate } from './resources-flatten-graph-transition.mjs';

export { RESOURCES_FLATTEN_GRAPH };
const DOCUMENTS = [{"path":"docs/operations/resources-flatten-evidence/runtime-inputs.json","sha256":"50ebd07d1b0e146679df59a7c529ff8e9ca698c959f0ce8d206f542316190f55","count":156,"role":"RESOURCES_FLATTEN_CAPTURE_RUNTIME_FILE_DIGEST","lines":[6,7,89,90,107,108,122,124,152,153,157]},{"path":"docs/operations/resources-flatten-evidence/cache-inputs.json","sha256":"5fc4c1ab37d8896599b05c39a191fad18b824174da6788375d75afaf6f8ed1c6","count":3327,"role":"RESOURCES_FLATTEN_CAPTURE_CACHE_FILE_DIGEST","lines":[88,89,142,143,144,145,231,232,234,235,552,553,671,672,674,675,677,678,683,686,687,689,692,693,695,696,697,698,705,706,707,708,710,713,714,715,716,1110,1111,1191,1192,1193,1194,1196,1197,1198,1199,1201,1202,1203,1204,1251,1252,1253,1254,1347,1348,1478,1479,1480,1481,1501,1502,1503,1504,1506,1507,1508,1509,1521,1522,1523,1524,1526,1527,1528,1529,1531,1532,1533,1534,1536,1537,1581,1582,1583,1584,1760,1761,1762,1763,1770,1771,1772,1773,1780,1781,1782,1783,1793,1794,1795,1796,1891,1892,1974,1975,1976,1977,2036,2037,2038,2039,2041,2042,2044,2045,2046,2047,2358,2359,2373,2374,2379,2380,2385,2386,2391,2392,2400,2401,2406,2407,2427,2428,2433,2434,2442,2443,2448,2449,2460,2461,2502,2503,2508,2509,2734,2735,2893,2894,2896,2897,2899,2900,2902,2903,2904,2905,2907,2908,2910,2911,2955,2956,2957,2958,3288,3289]}];
const DIRECTORY = 'docs/operations/resources-flatten-evidence/';
const PROVENANCE = [
  { path: DIRECTORY + 'capture-execution.json', sha256: 'af0cf1434e4ad1e02ee0fe42a78889b04aaad500b41dc956e25ec186408ec935' },
  { path: DIRECTORY + 'model-capture-execution.json', sha256: '28229842906bf622c7cd76b9e8cbfbf592e1fc025625eabb60ed0a21b319348a' },
  { path: DIRECTORY + 'capture.py', sha256: '7fe48c76536050e6666db8f70fc9f222cabd3b31dbbd9641b8bd431bd1acb72e' },
];
export const ACCEPTED_RESOURCES_FLATTEN_GITLEAKS_374_SHA256 = '3acb89d507e8fc2053da12f692597d24d0a16cdd7743f9363872f7e197c8fe77';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const requireValue = (ok, message) => { if (!ok) throw new Error(`Gitleaks Resources/Flatten digest ${message}`); };
const shape = (value, keys, label) => requireValue(value && canonical(Object.keys(value).sort()) === canonical([...keys].sort()), `${label} shape drift`);
const seal = value => stable({ ...value, contentSha256: hash(canonical(value)) });
export const isResourcesFlattenExecutionDigestPath = path => DOCUMENTS.some(row => row.path === path);

export function requireResourcesFlattenDigestGraph(e4) {
  requireValue(e4.e2GraphDigest === RESOURCES_FLATTEN_GRAPH && e4.scanners?.gitleaks?.findingCount === 554,
    'requires the exact Resources/Flatten graph and all 554 observations');
  // These bounded readers validate the fixed manifest and actual candidate graph.
  // They do not invoke complete graph/source/scanner admission or retain a cache.
  const manifest = readResourcesFlattenManifest();
  readResourcesFlattenCandidate(manifest);
  return manifest;
}
function pinned(spec) {
  const bytes = readFileSync(new URL(`../../${spec.path}`, import.meta.url));
  requireValue(hash(bytes) === spec.sha256, 'fixed source bytes changed: ' + spec.path);
  return { bytes, source: { path: spec.path, sha256: spec.sha256, blobSha: objectHash('blob', bytes), byteLength: bytes.length } };
}
export function resourcesFlattenExecutionDocuments() {
  const manifest = readResourcesFlattenManifest(), provenance = PROVENANCE.map(pinned);
  for (const row of provenance.slice(0, 2)) {
    const capture = JSON.parse(row.bytes);
    requireValue(capture.schemaVersion === 'APPROVAL_RESOURCES_FLATTEN_CAPTURE_EXECUTION_V1'
      && capture.status === 'PASSED_CAPTURE_ONLY' && capture.sourceHead === manifest.base.sourceHead
      && capture.sourceTree === manifest.base.sourceTree && capture.sourceDirty === true
      && capture.exactHeadEvidence === false && capture.canonicalCiEvidence === false && capture.offline === true
      && capture.runtimeInputsStable === true && capture.cacheInputsStable === true && capture.captureInputsStable === true
      && capture.runtimeInputManifestSha256 === DOCUMENTS[0].sha256 && capture.cacheInputManifestSha256 === DOCUMENTS[1].sha256
      && capture.collectorSha256 === PROVENANCE[2].sha256 && capture.scannerDispositionClaimed === false,
    'fixed capture provenance changed');
  }
  const documents = DOCUMENTS.map(spec => {
    const { bytes, source } = pinned(spec), text = bytes.toString('utf8'), value = JSON.parse(text);
    requireValue(value && !Array.isArray(value) && Object.keys(value).length === spec.count
      && Object.entries(value).every(([path, digest]) => path.startsWith('/') && /^[a-f0-9]{64}$/.test(digest))
      && text === `${JSON.stringify(value, null, 2)}\n`, 'fixed flat digest-map schema/format drift');
    const entries = Object.entries(value), lines = text.split('\n');
    const fields = spec.lines.map(line => {
      const entry = entries[line - 2]; requireValue(entry, 'fixed digest field absent');
      const [path, digest] = entry, rawLine = lines[line - 1];
      const expected = '  ' + JSON.stringify(path) + ': ' + JSON.stringify(digest);
      requireValue(rawLine === expected || rawLine === expected + ',', 'exact scalar line drift');
      const startOneBased = rawLine.indexOf(JSON.stringify(digest)) + 2;
      return { path: spec.path, jsonPointer: '/' + path.replaceAll('~', '~0').replaceAll('/', '~1'), line,
        lineSha256: hash(rawLine), role: spec.role, scannerColumnsRetained: false,
        derivedJsonValueColumns: { startOneBased, endOneBased: startOneBased + 63 } };
    });
    return { source, fields };
  });
  requireValue(documents[0].fields.length === 11 && documents[1].fields.length === 169, 'fixed field cardinality drift');
  return { documents, provenance: provenance.map(row => row.source), base: manifest.base };
}
export function gitleaksResourcesFlattenExecutionDigestFields() {
  return resourcesFlattenExecutionDocuments().documents.flatMap(row => row.fields);
}
export function resourcesFlattenDigestFindings(e4, expectedFinding, policy, context = resourcesFlattenExecutionDocuments()) {
  const findings = e4.scanners.gitleaks.findings.filter(row => isResourcesFlattenExecutionDigestPath(row.path));
  requireValue(findings.length === 180 && /^[0-9a-f]{40}$/.test(findings[0]?.commit || '')
    && findings.every(row => row.commit === findings[0].commit), 'all 180 exact fields require one introduction');
  const expected = context.documents.flatMap(row => row.fields).map(field => expectedFinding(
    { finding: policy, source: { path: field.path } }, findings[0].commit, field)).sort((a,b) => a.findingId.localeCompare(b.findingId));
  requireValue(canonical(findings) === canonical(expected), 'exact field finding identity drift');
  const retained = e4.scanners.gitleaks.findings.filter(row => !isResourcesFlattenExecutionDigestPath(row.path));
  requireValue(retained.length === 374 && hash(canonical(retained)) === ACCEPTED_RESOURCES_FLATTEN_GITLEAKS_374_SHA256,
    'accepted 374 finding identities/order changed');
  return findings;
}
export function verifyResourcesFlattenExecutionDigestProof(e4, proof, expectedFinding, policy) {
  requireResourcesFlattenDigestGraph(e4);
  const context = resourcesFlattenExecutionDocuments(), { documents, provenance, base: accepted } = context;
  const findings = resourcesFlattenDigestFindings(e4, expectedFinding, policy, context);
  shape(proof, ['commits', 'introductionParents', 'documents', 'provenance'], 'proof');
  requireValue(Array.isArray(proof.commits) && proof.commits.length >= 2 && proof.commits.length <= 4096, 'bounded ancestry required');
  const commits = proof.commits.map(commitObject), current = commits[0], base = commits.at(-1);
  requireValue(new Set(commits.map(row => row.sha)).size === commits.length && current.sha === e4.commitSha
    && current.tree === e4.checkout.expectedHeadTreeSha && base.sha === accepted.sourceHead && base.tree === accepted.sourceTree,
  'current/accepted Git identity changed');
  for (let i = 0; i < commits.length - 1; i++) requireValue(commits[i].parents.includes(commits[i + 1].sha), 'Git ancestry mismatch');
  const introduced = commits.find(row => row.sha === findings[0].commit);
  requireValue(introduced && introduced.sha !== base.sha && introduced.parents.length > 0, 'introduction absent');
  const sources = [...documents.map(row => row.source), ...provenance];
  requireValue(Array.isArray(proof.introductionParents) && proof.introductionParents.length === introduced.parents.length,
    'every introduction parent required');
  for (const [i, row] of proof.introductionParents.entries()) {
    shape(row, ['commit', 'sourcePaths'], 'introduction parent'); const parent = commitObject(row.commit);
    requireValue(parent.sha === introduced.parents[i] && Array.isArray(row.sourcePaths) && row.sourcePaths.length === sources.length,
      'parent identity/path count mismatch');
    for (const [j, item] of row.sourcePaths.entries()) {
      shape(item, ['path', 'pathProof'], 'parent source'); requireValue(item.path === sources[j].path, 'parent source order mismatch');
      verifyPath(item.pathProof, parent.tree, item.path, null);
    }
  }
  for (const [name, expected] of [['documents', documents.map(row => row.source)], ['provenance', provenance]]) {
    requireValue(Array.isArray(proof[name]) && proof[name].length === expected.length, name + ' count mismatch');
    for (const [i, row] of proof[name].entries()) {
      shape(row, ['path', 'currentPath', 'introductionPath', 'basePath'], name + ' source'); const source = expected[i];
      requireValue(row.path === source.path, name + ' source path/order mismatch');
      verifyPath(row.currentPath, current.tree, source.path, source.blobSha);
      verifyPath(row.introductionPath, introduced.tree, source.path, source.blobSha);
      verifyPath(row.basePath, base.tree, source.path, null);
    }
  }
  return seal({ schemaVersion: 'M6_PR_E_E3_GITLEAKS_RESOURCES_FLATTEN_EXECUTION_DIGEST_REVIEW_V1', findings,
    documents: documents.map(row => row.source), fields: documents.flatMap(row => row.fields), captureProvenance: provenance,
    sourceProofCanonicalSha256: hash(canonical(proof)), acceptedMainCommit: base.sha, acceptedMainTree: base.tree,
    acceptedGitleaksInventorySha256: ACCEPTED_RESOURCES_FLATTEN_GITLEAKS_374_SHA256,
    inputDigestProvenance: 'PINNED_LOCAL_CAPTURE_DIGESTS_REHASHED_AGAINST_RUNTIME_AND_RETAINED_MAVEN_ARTIFACT_BYTES',
    scannerColumnsRetained: false, localExecutionOnly: true, canonicalCiEvidence: false, releaseBlocked: true,
    decision: { disposition: 'NOT_APPLICABLE', evidenceCode: 'EXACT_PINNED_RESOURCES_FLATTEN_EXECUTED_INPUT_DIGEST_FIELDS', exploitPreconditionsSatisfied: false } });
}
