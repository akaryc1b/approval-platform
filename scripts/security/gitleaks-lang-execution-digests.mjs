// Exact local capture fields; historical absolute paths are data, never opened here.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { commitObject, verifyPath, objectHash } from './gitleaks-git-source-proof.mjs';
import { ASSEMBLY_ENFORCER_LANG_GRAPH, readAssemblyEnforcerLangManifest,
  readAssemblyEnforcerLangCandidate } from './assembly-enforcer-lang-graph-transition.mjs';

import { RESOURCES_FLATTEN_GRAPH, requireResourcesFlattenDigestGraph, isResourcesFlattenExecutionDigestPath }
  from './gitleaks-resources-flatten-execution-digests.mjs';

export { ASSEMBLY_ENFORCER_LANG_GRAPH };
const DOCUMENTS = [{"path":"docs/operations/assembly-enforcer-lang-evidence/runtime-inputs.json","sha256":"0e4d6bb6589c3accb075ea869f9769bb328cef83db802b1f705fd30eff95f8dd","count":158,"role":"LANG_CAPTURE_RUNTIME_FILE_DIGEST","lines":[8,9,91,92,109,110,124,126,154,155,159]},{"path":"docs/operations/assembly-enforcer-lang-evidence/cache-inputs.json","sha256":"090af136bf05db6030dd4b7ba43351123389157e4b480e3d6f7e85ba84da7be3","count":3332,"role":"LANG_CAPTURE_CACHE_FILE_DIGEST","lines":[88,89,142,143,144,145,231,232,234,235,552,553,671,672,674,675,677,678,683,686,687,689,692,693,695,696,697,698,705,706,707,708,710,713,714,715,716,1110,1111,1191,1192,1193,1194,1196,1197,1198,1199,1201,1202,1203,1204,1251,1252,1253,1254,1347,1348,1478,1479,1480,1481,1501,1502,1503,1504,1506,1507,1508,1509,1521,1522,1523,1524,1526,1527,1528,1529,1531,1532,1533,1534,1536,1537,1581,1582,1583,1584,1760,1761,1762,1763,1770,1771,1772,1773,1780,1781,1782,1783,1793,1794,1795,1796,1891,1892,1974,1975,1976,1977,2036,2037,2038,2039,2041,2042,2044,2045,2046,2047,2363,2364,2378,2379,2384,2385,2390,2391,2396,2397,2405,2406,2411,2412,2432,2433,2438,2439,2447,2448,2453,2454,2465,2466,2507,2508,2513,2514,2739,2740,2898,2899,2901,2902,2904,2905,2907,2908,2909,2910,2912,2913,2915,2916,2960,2961,2962,2963,3293,3294]}];
const DIRECTORY = 'docs/operations/assembly-enforcer-lang-evidence/';
const PROVENANCE = [
  { path: DIRECTORY + 'capture-execution.json', sha256: 'd7bd5e98dc745770f82dc3e3ca634c4f3681e83e6911025332057875351b389b' },
  { path: DIRECTORY + 'model-capture-execution.json', sha256: 'd3fcbd638b33b40a202cc86dc288c47760aca1211f87726092c613852281ab73' },
  { path: DIRECTORY + 'capture.py', sha256: 'a01cedc485554ce7a79da4621fe42667091f93daa9f92410f22e9b1dc9dfd39f' },
];
export const ACCEPTED_LANG_GITLEAKS_194_SHA256 = 'fa6e1ea237b95383d7cae96810fa6ce274048ad7efd392b9c48d01aace885774';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const requireValue = (ok, message) => { if (!ok) throw new Error(`Gitleaks Lang digest ${message}`); };
const shape = (value, keys, label) => requireValue(value && canonical(Object.keys(value).sort()) === canonical([...keys].sort()), `${label} shape drift`);
const seal = value => stable({ ...value, contentSha256: hash(canonical(value)) });
export const isLangExecutionDigestPath = path => DOCUMENTS.some(row => row.path === path);

export function requireLangDigestGraph(e4) {
  if (e4.e2GraphDigest === RESOURCES_FLATTEN_GRAPH) requireResourcesFlattenDigestGraph(e4);
  else requireValue(e4.e2GraphDigest === ASSEMBLY_ENFORCER_LANG_GRAPH && e4.scanners?.gitleaks?.findingCount === 374,
    'requires the exact Lang graph and all 374 observations');
  // These bounded readers validate the fixed manifest and actual candidate graph.
  // They do not invoke complete graph/source/scanner admission or retain a cache.
  const manifest = readAssemblyEnforcerLangManifest();
  readAssemblyEnforcerLangCandidate(manifest);
  return manifest;
}
function pinned(spec) {
  const bytes = readFileSync(new URL(`../../${spec.path}`, import.meta.url));
  requireValue(hash(bytes) === spec.sha256, 'fixed source bytes changed: ' + spec.path);
  return { bytes, source: { path: spec.path, sha256: spec.sha256, blobSha: objectHash('blob', bytes), byteLength: bytes.length } };
}
export function langExecutionDocuments() {
  const manifest = readAssemblyEnforcerLangManifest(), provenance = PROVENANCE.map(pinned);
  for (const row of provenance.slice(0, 2)) {
    const capture = JSON.parse(row.bytes);
    requireValue(capture.schemaVersion === 'APPROVAL_ASSEMBLY_ENFORCER_LANG_CAPTURE_EXECUTION_V1'
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
export function gitleaksLangExecutionDigestFields() {
  return langExecutionDocuments().documents.flatMap(row => row.fields);
}
export function langDigestFindings(e4, expectedFinding, policy, context = langExecutionDocuments()) {
  const findings = e4.scanners.gitleaks.findings.filter(row => isLangExecutionDigestPath(row.path));
  requireValue(findings.length === 180 && /^[0-9a-f]{40}$/.test(findings[0]?.commit || '')
    && findings.every(row => row.commit === findings[0].commit), 'all 180 exact fields require one introduction');
  const expected = context.documents.flatMap(row => row.fields).map(field => expectedFinding(
    { finding: policy, source: { path: field.path } }, findings[0].commit, field)).sort((a,b) => a.findingId.localeCompare(b.findingId));
  requireValue(canonical(findings) === canonical(expected), 'exact field finding identity drift');
  const retained = e4.scanners.gitleaks.findings.filter(row => !isLangExecutionDigestPath(row.path)
    && !(e4.e2GraphDigest === RESOURCES_FLATTEN_GRAPH && isResourcesFlattenExecutionDigestPath(row.path)));
  requireValue(retained.length === 194 && hash(canonical(retained)) === ACCEPTED_LANG_GITLEAKS_194_SHA256,
    'accepted 194 finding identities/order changed');
  return findings;
}
export function verifyLangExecutionDigestProof(e4, proof, expectedFinding, policy) {
  requireLangDigestGraph(e4);
  const context = langExecutionDocuments(), { documents, provenance, base: accepted } = context;
  const findings = langDigestFindings(e4, expectedFinding, policy, context);
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
  return seal({ schemaVersion: 'M6_PR_E_E3_GITLEAKS_LANG_EXECUTION_DIGEST_REVIEW_V1', findings,
    documents: documents.map(row => row.source), fields: documents.flatMap(row => row.fields), captureProvenance: provenance,
    sourceProofCanonicalSha256: hash(canonical(proof)), acceptedMainCommit: base.sha, acceptedMainTree: base.tree,
    acceptedGitleaksInventorySha256: ACCEPTED_LANG_GITLEAKS_194_SHA256,
    inputDigestProvenance: 'INDEPENDENTLY_REHASHED_EXECUTED_LOCAL_INPUTS_NOT_GIT_OBJECTS',
    scannerColumnsRetained: false, localExecutionOnly: true, canonicalCiEvidence: false, releaseBlocked: true,
    decision: { disposition: 'NOT_APPLICABLE', evidenceCode: 'EXACT_PINNED_LANG_EXECUTED_INPUT_DIGEST_FIELDS', exploitPreconditionsSatisfied: false } });
}
