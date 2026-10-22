import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { requireCompleteCurrentE4 } from './scanner-evidence-provenance.mjs';
import { bytesFromBase64, commitObject, treeEntries, verifyPath, objectHash } from './gitleaks-git-source-proof.mjs';

const PLAN_SHA256 = '888890064d4d2342053a94c01447dd46bea257b445abb07af4f5527d6913c914';
const PLAN_FILE_SHA256 = '233be0fe354400bc0420cd951335f97a68ebc2709a958fc065b1af8bdbbae572';
const PLAN_PATH = 'docs/m6/m6-pr-e-e3-gitleaks-capture-hash-review.json';
const SCANNER_BASELINE_PATH = 'docs/m6/m6-pr-e-e4-scanner-baseline.json';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const seal = value => { const { contentSha256, ...payload } = value; return stable({ ...payload, contentSha256: hash(canonical(payload)) }); };
const requireValue = (condition, message) => { if (!condition) throw new Error(`Gitleaks capture hash ${message}`); };
const shape = (value, keys, label) => requireValue(value && canonical(Object.keys(value).sort()) === canonical([...keys].sort()), `${label} shape drift`);
const SHA40 = /^[0-9a-f]{40}$/;
const key = finding => `${finding.sourceClass}:${finding.findingId}`;

const JAR_GRAPH = '73825d80bd2a55d0507742bac096e73309100911d69692cd34dc753674bdb9c5';
const JAR_BASE = 'f1c99eaa998239044b164d32437a9f3c0765b321';
const JAR_BASE_TREE = 'bc30fc2b5c277e3b9cc7452af7616b56dd17868f';
import { readPreservedJarSource } from './jar-plugin-source-continuation.mjs';
const JAR_VERIFIER_PATH = 'scripts/security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs';
const JAR_VERIFIER_BASE_SHA256 = '3ecb8b738ea3bc251e10f73d0a93600bfca28791d62b0003f3a18b0fef665bb0';
const JAR_VERIFIER_INTRODUCTION_SHA256 = 'bdd6e49a49db78da02980d24e30741b6802174f398b9800fbcc9a93f73db9e98';
const JAR_FIXTURE_PATH = 'scripts/tests/fixtures/jar-digest-review-fixture.mjs';
const JAR_FIXTURE_CURRENT_SHA256 = 'd86e33849a97cee6765fb2e96a38eb86c234f502b59bdbba97989f71e712b412';
const JAR_FIXTURE_ORIGINAL_SHA256 = '5ee1a65d8587d82ca85b4e47bf91f8c818430693ea1c5a996bac411be5d7a83d';
const JAR_ACCEPTED_FINDINGS = 'afa27a6b74ed970e0618df2a537975dcebc355e65067e1cea262519a7eb60c7c';
// Closed historical graph contexts. A future graph continuation must explicitly
// carry this 194-record review forward; omitted fixture/digest records never fall back.
const HISTORICAL_GRAPHS = new Set([
  "255008a8cf1b3d57182cdd47c680aad7e484b027f23ae5e3a19590791732d950",
  "27bdcae01a4affff009d6b90ca04989bd14a3cb159bbef6a6a379fab84109a37",
  "2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94",
  "305364325fbd8a75f189fe50b708866e1f64cfc1a89f14847f67a087d131a94c",
  "390773d2aa746a2203eec870e5dd0a8f97f81e91913bb016c9ef95b749c0e7b3",
  "454934f756eb08a49bc6d74435f12064df6c74286cad358558b0e73dda78c9ca",
  "5760ae70a04b967548d991397b14076d8aaf45bb4bd3bd00e917317fdd649867",
  "6557cef5d2ef11c36a8efb89cf7c1c607c721e32cdbabf0228441bb585b3c829",
  "7a6b917477f2a9b4e1fea9eac40ae24a09af59cf93fe59737a08e3c8ff6868b0",
  "7a719e7a07e9c7c51c5dddc228f4f00cb89ec7a62b4731067d2d977bc72ebc63",
  "92f6b10a9fd199bbea69ce3a1bc743b77b00990b1e7ec4b1ca695407da7c91fe",
  "a338fa7e74474131fbd72f610f1be26cd7b676a46597aef5b04f5ef87a1dad92",
  "b49dc95d7b88cc0c30fd8e3f81fc184204c9e94c5aa741ec12a4e68a9ee28a28",
  "e7a2f92ae31e016e6691eb8625311a32e930ef1103836ad28a45a9b4c27748b6",
  "f2e4ca66b29bd4e819f8751117e56900416d47c474de270d84e9d2ef552c92c3"
]);
const JAR_DOCUMENTS = [{"path":"docs/operations/jar-plugin-evidence/model-runtime-inputs.json","sha256":"d3d8fa07357c919f8999c2c1d043d05305f8daa3eb82a0ad1d5b5c6f62e17f5d","lines":[9,10,92,93,110,111,125,127,155,156,160]},{"path":"docs/operations/jar-plugin-evidence/compatibility-local-seal.json","sha256":"7036dff106172b82af4ebe16427cc16fe1e2251d492ba11441926f3434e69973","lines":[168,185,186,324,562,691,739,740,831,845,846,883,884,1050,1051,1151,1152,1169,1170,1184,1186,1214,1215,1219,1251,1271,1272,1306,1308,1426,1466,1467,1468,1470,1471,1472,1473,1474,1475,1478,1479,1480,1481,1482,1623,1656,1657,1658,1659,1660,1661,1677,1678,1711,1761,1762,1769,1770,1771,1772,1781,1782,1783,1784,1785,1786,1787,1801,1802,1868,1869,1872,1873,1876,1877,1881,1882,1918,1967,1968,1992,1993,1994,1995,1996,2096,2100,2102,2104,2108,2110,2112,2119,2120,2124,2125,2130,2144,2146,2225,2281,2282,2283,2284,2285,2286,2287,2302,2303,2418]}];

import { ASSEMBLY_ENFORCER_LANG_GRAPH, requireLangDigestGraph, isLangExecutionDigestPath,
  langExecutionDocuments, langDigestFindings, verifyLangExecutionDigestProof } from './gitleaks-lang-execution-digests.mjs';
export { gitleaksLangExecutionDigestFields } from './gitleaks-lang-execution-digests.mjs';
import { RESOURCES_FLATTEN_GRAPH, requireResourcesFlattenDigestGraph, isResourcesFlattenExecutionDigestPath,
  resourcesFlattenExecutionDocuments, resourcesFlattenDigestFindings, verifyResourcesFlattenExecutionDigestProof }
  from './gitleaks-resources-flatten-execution-digests.mjs';

/** Dispatch is checked before any collector, not inferred from a convenient count. */
export function requireGitleaksCaptureInventory(e4) {
  const count = e4?.scanners?.gitleaks?.findingCount;
  if (e4?.e2GraphDigest === RESOURCES_FLATTEN_GRAPH) { requireResourcesFlattenDigestGraph(e4); return 554; }
  if (e4?.e2GraphDigest === ASSEMBLY_ENFORCER_LANG_GRAPH) { requireLangDigestGraph(e4); return 374; }
  if (e4?.e2GraphDigest === JAR_GRAPH) {
    requireValue(count === 194, 'current JAR graph requires all 194 Gitleaks observations'); return 194;
  }
  requireValue(![191, 192, 193, 194, 374, 554].includes(count), 'current execution-digest inventory requires the reviewed JAR graph and all 194 observations');
  if ([32, 70].includes(count)) requireValue(HISTORICAL_GRAPHS.has(e4?.e2GraphDigest), 'unsupported historical capture graph');
  return [32, 70].includes(count) ? count : null;
}

function jarExecutionDocuments() {
  const pinnedJson = (path, digest) => { const bytes = readFileSync(new URL(`../../${path}`, import.meta.url));
    requireValue(hash(bytes) === digest, 'fixed execution document bytes changed'); return JSON.parse(bytes); };
  const manifest = pinnedJson('docs/operations/jar-plugin-transition.json', 'bc674c02318cc1e217e9e041dc1ca894f186d40961f14a7a94672788ab6b8a9c');
  requireValue(manifest.observed.graphDigest === JAR_GRAPH && manifest.base.sourceHead === JAR_BASE
    && manifest.capture.sourcePom.rawSha256 === hash(readPreservedJarSource('pom.xml', manifest.capture.sourcePom.rawSha256)), 'current JAR source continuation changed');
  const model = pinnedJson('docs/operations/jar-plugin-evidence/model-capture-execution.json', '240f416004b1fe43ea485f08b835d383331b4b5b4c5642c0ad900294964745c6');
  requireValue(model.runtimeInputManifestSha256 === JAR_DOCUMENTS[0].sha256 && model.runtimeInputsStable === true
    && model.status === 'PASSED_CAPTURE_ONLY', 'executed model runtime provenance changed');
  const continuation = pinnedJson('docs/operations/jar-plugin-evidence/compatibility-consumer-source-continuation.json', 'c07723ecace96eb042fdb1f35ff217d97492da1903f5b67dcb926e6314f7a9b9');
  requireValue(continuation.actualMavenExecutionSealSha256 === JAR_DOCUMENTS[1].sha256
    && continuation.actualReceiptSha256 === 'a41a99952e9277838ad54b38b7699239c6e51495e79ad0b8b95a429617b18389', 'executed compatibility provenance changed');
  const result = pinnedJson('docs/operations/jar-plugin-evidence/compatibility-local-result.json', continuation.actualReceiptSha256);
  requireValue(result.status === 'PASSED' && result.inputSealsUnchanged === true && result.scannerEvidence === false, 'executed compatibility result changed');
  const map = (value, count) => requireValue(value && !Array.isArray(value) && Object.keys(value).length === count
    && Object.entries(value).every(([path, digest]) => path.startsWith('/') && /^[a-f0-9]{64}$/.test(digest)), 'fixed execution digest-map schema changed');
  return JAR_DOCUMENTS.map((spec, index) => {
    const bytes = readFileSync(new URL(`../../${spec.path}`, import.meta.url));
    requireValue(hash(bytes) === spec.sha256, 'fixed execution document bytes changed');
    const value = JSON.parse(bytes), text = bytes.toString('utf8');
    requireValue(text === `${JSON.stringify(value, null, 2)}\n`, 'fixed execution document formatting changed');
    if (index === 0) map(value, 159);
    else {
      shape(value, ['schemaVersion','plan','config','sourceHashes','runtimeHashes','cacheHashes','pins','linkage'], 'execution seal');
      shape(value.config, ['source','pins','maven','jdk','repository','output'], 'execution seal configuration');
      requireValue(value.schemaVersion === 1 && value.plan.offline === true && value.plan.sockets === false
        && value.plan.scannerEvidence === false && value.plan.productionActivationClaim === false, 'execution seal schema/limits changed');
      map(value.sourceHashes, 21); map(value.runtimeHashes, 1117); map(value.cacheHashes, 1211);
    }
    const positions = new Map();
    const walk = (value, pointer, line, keys) => {
      positions.set(line, { value, pointer, keys });
      if (!value || typeof value !== 'object') return;
      let next = line + 1;
      for (const [name, child] of Object.entries(value)) {
        walk(child, `${pointer}/${name.replaceAll('~','~0').replaceAll('/','~1')}`, next, [...keys, name]);
        next += JSON.stringify(child, null, 2).split('\n').length;
      }
    };
    walk(value, '', 1, []); const lines = text.split('\n');
    const fields = spec.lines.map(line => {
      const field = positions.get(line), keys = field?.keys;
      requireValue(field && /^[a-f0-9]{64}$/.test(field.value) && (index === 0 ? keys.length === 1
        : keys.length === 2 && ['runtimeHashes','cacheHashes'].includes(keys[0])), 'execution field role changed');
      const rawLine = lines[line - 1], expected = ' '.repeat(keys.length * 2) + JSON.stringify(keys.at(-1)) + ': ' + JSON.stringify(field.value);
      requireValue(rawLine === expected || rawLine === expected + ',', 'exact execution scalar line changed');
      const startOneBased = rawLine.indexOf(JSON.stringify(field.value)) + 2;
      return { path: spec.path, jsonPointer: field.pointer, line, lineSha256: hash(rawLine),
        role: index === 0 ? 'MODEL_RUNTIME_FILE_DIGEST' : keys[0] === 'runtimeHashes' ? 'COMPATIBILITY_RUNTIME_FILE_DIGEST' : 'COMPATIBILITY_CACHE_FILE_DIGEST',
        scannerColumnsRetained: false, derivedJsonValueColumns: { startOneBased, endOneBased: startOneBased + 63 } };
    });
    return { source: { path: spec.path, sha256: spec.sha256, blobSha: objectHash('blob', bytes), byteLength: bytes.length }, fields };
  });
}
export function gitleaksJarExecutionDigestFields() { return jarExecutionDocuments().flatMap(document => document.fields); }
function jarDigestFindings(e4, plan, documents = jarExecutionDocuments()) {
  const fields = documents.flatMap(document => document.fields), paths = new Set(JAR_DOCUMENTS.map(document => document.path));
  const findings = e4.scanners.gitleaks.findings.filter(finding => paths.has(finding.path));
  requireValue(findings.length === 121 && SHA40.test(findings[0]?.commit || '')
    && findings.every(finding => finding.commit === findings[0].commit), 'all 121 exact execution fields require one introduction');
  const expected = fields.map(field => expectedFinding({ finding: plan.finding, source: { path: field.path } }, findings[0].commit, field))
    .sort((a,b) => a.findingId.localeCompare(b.findingId));
  requireValue(canonical(findings) === canonical(expected), 'execution field finding identity drift');
  const retained = e4.scanners.gitleaks.findings.filter(finding => !paths.has(finding.path) && finding.path !== JAR_FIXTURE_PATH && finding.path !== JAR_VERIFIER_PATH
    && !([374, 554].includes(e4.scanners.gitleaks.findingCount) && isLangExecutionDigestPath(finding.path))
    && !(e4.e2GraphDigest === RESOURCES_FLATTEN_GRAPH && isResourcesFlattenExecutionDigestPath(finding.path)));
  requireValue(retained.length === 70 && hash(canonical(retained)) === JAR_ACCEPTED_FINDINGS, 'accepted main1862 findings changed');
  return findings;
}
function jarFixtureSource(bytes, introduction = false) {
  const sha256 = hash(bytes);
  requireValue(introduction ? [JAR_FIXTURE_ORIGINAL_SHA256, JAR_FIXTURE_CURRENT_SHA256].includes(sha256)
    : sha256 === JAR_FIXTURE_CURRENT_SHA256, 'fixed fixture document bytes changed');
  const line = 18, field = 'ACCEPTED_JAR_DIGEST_BASE', rawLine = bytes.toString('utf8').split('\n')[line - 1];
  requireValue(rawLine === `export const ${field} = '${JAR_BASE}';`, 'exact fixture accepted-main declaration changed');
  const startOneBased = rawLine.indexOf(JAR_BASE) + 1;
  return { path: JAR_FIXTURE_PATH, sha256, blobSha: objectHash('blob', bytes), byteLength: bytes.length,
    line, field, lineSha256: hash(rawLine), role: 'ACCEPTED_MAIN_GIT_COMMIT_IDENTITY', scannerColumnsRetained: false,
    derivedLiteralColumns: { startOneBased, endOneBased: startOneBased + 39 } };
}
function jarFixtureCurrentSource() {
  return jarFixtureSource(readFileSync(new URL(`../../${JAR_FIXTURE_PATH}`, import.meta.url)));
}
function jarFixtureFinding(e4, plan, source = jarFixtureCurrentSource()) {
  const findings = e4.scanners.gitleaks.findings.filter(finding => finding.path === JAR_FIXTURE_PATH);
  const policy = plan.retainedFindings.find(finding => finding.ruleId === 'sourcegraph-access-token');
  requireValue(findings.length === 1 && SHA40.test(findings[0]?.commit || '') && policy
    && canonical(findings[0]) === canonical(expectedFinding({ finding: policy, source }, findings[0].commit, source)),
  'exact fixture public-commit finding identity drift');
  return findings[0];
}
function verifyFixturePublicCommitProof(e4, proof, executionProof, plan) {
  shape(proof, ['commits','introductionParents','currentPath','introductionPath','basePath','introductionContentBase64'], 'fixture public-commit proof');
  const source = jarFixtureCurrentSource(), finding = jarFixtureFinding(e4, plan, source);
  requireValue(Array.isArray(proof.commits) && proof.commits.length >= 2 && proof.commits.length <= 4096, 'fixture ancestry required');
  const commits = proof.commits.map(commitObject), current = commits[0], base = commits.at(-1);
  // Reuse the actual accepted-main commit object already required by the two
  // executed-input document proofs. The fixture literal names this object.
  const accepted = commitObject(executionProof.commits.at(-1));
  requireValue(new Set(commits.map(row => row.sha)).size === commits.length && current.sha === e4.commitSha
    && current.tree === e4.checkout.expectedHeadTreeSha && base.sha === accepted.sha && base.tree === accepted.tree
    && accepted.sha === JAR_BASE && accepted.tree === JAR_BASE_TREE, 'fixture current/accepted-main identity changed');
  for (let index = 0; index < commits.length - 1; index++) requireValue(commits[index].parents.includes(commits[index + 1].sha), 'fixture ancestry mismatch');
  const introduced = commits.find(row => row.sha === finding.commit);
  requireValue(introduced && introduced.sha !== base.sha && introduced.parents.length > 0, 'fixture introduction absent');
  const introductionSource = jarFixtureSource(bytesFromBase64(proof.introductionContentBase64), true);
  verifyPath(proof.currentPath, current.tree, source.path, source.blobSha);
  verifyPath(proof.introductionPath, introduced.tree, source.path, introductionSource.blobSha);
  verifyPath(proof.basePath, base.tree, source.path, null);
  requireValue(Array.isArray(proof.introductionParents) && proof.introductionParents.length === introduced.parents.length, 'every fixture introduction parent required');
  for (const [index, row] of proof.introductionParents.entries()) {
    shape(row, ['commit','sourcePath'], 'fixture introduction parent'); const parent = commitObject(row.commit);
    requireValue(parent.sha === introduced.parents[index], 'fixture introduction parent mismatch');
    verifyPath(row.sourcePath, parent.tree, source.path, null);
  }
  return seal({ schemaVersion: 'M6_PR_E_E3_GITLEAKS_FIXTURE_PUBLIC_COMMIT_REVIEW_V1', finding, source,
    introductionSource: { sha256: introductionSource.sha256, blobSha: introductionSource.blobSha, byteLength: introductionSource.byteLength },
    sourceProofCanonicalSha256: hash(canonical(proof)), acceptedMainCommit: accepted.sha, acceptedMainTree: accepted.tree,
    inputProvenance: 'ACTUAL_ACCEPTED_GIT_COMMIT_OBJECT_AND_EXACT_FIXED_SOURCE_FIELD', scannerColumnsRetained: false,
    releaseBlocked: true, rawCandidateMaterialRetained: false,
    decision: { disposition: 'NOT_APPLICABLE', evidenceCode: 'EXACT_FIXTURE_ACCEPTED_MAIN_GIT_IDENTITY', exploitPreconditionsSatisfied: false } });
}
function jarVerifierSource(bytes) {
  requireValue(bytes.length > 0 && bytes.length <= 256 * 1024, 'verifier source length invalid');
  const lines = bytes.toString('utf8').split('\n');
  const fields = [
    { line: 22, field: 'JAR_BASE', value: JAR_BASE, role: 'ACCEPTED_MAIN_GIT_COMMIT_IDENTITY' },
    { line: 23, field: 'JAR_BASE_TREE', value: JAR_BASE_TREE, role: 'ACCEPTED_MAIN_GIT_TREE_IDENTITY' },
  ].map(({ value, ...field }) => {
    const rawLine = lines[field.line - 1];
    requireValue(rawLine === `const ${field.field} = '${value}';`, 'exact verifier accepted-main declaration changed');
    const startOneBased = rawLine.indexOf(value) + 1;
    return { ...field, lineSha256: hash(rawLine), scannerColumnsRetained: false,
      derivedLiteralColumns: { startOneBased, endOneBased: startOneBased + 39 } };
  });
  return { path: JAR_VERIFIER_PATH, sha256: hash(bytes), blobSha: objectHash('blob', bytes), byteLength: bytes.length, fields };
}
function jarVerifierCurrentSource() { return jarVerifierSource(readFileSync(new URL(import.meta.url))); }
function jarVerifierFindings(e4, plan, source = jarVerifierCurrentSource()) {
  const findings = e4.scanners.gitleaks.findings.filter(finding => finding.path === JAR_VERIFIER_PATH);
  const policy = plan.retainedFindings.find(finding => finding.ruleId === 'sourcegraph-access-token');
  requireValue(findings.length === 2 && SHA40.test(findings[0]?.commit || '') && policy
    && findings.every(finding => finding.commit === findings[0].commit), 'exact verifier public-Git introduction required');
  const expected = source.fields.map(field => expectedFinding({ finding: policy, source }, findings[0].commit, field))
    .sort((a,b) => a.findingId.localeCompare(b.findingId));
  requireValue(canonical(findings) === canonical(expected), 'exact verifier public-Git finding identity drift');
  return findings;
}
function requireVerifierFieldsAbsent(bytes) {
  requireValue(bytes.length > 0 && bytes.length <= 256 * 1024
    && !/\b(?:JAR_BASE|JAR_BASE_TREE)\b/.test(bytes.toString('utf8')), 'verifier fields already present before introduction');
}
function verifyVerifierPublicGitProof(e4, proof, executionProof, plan) {
  shape(proof, ['commits','introductionParents','currentPath','introductionPath','introductionContentBase64','basePath','baseContentBase64'], 'verifier public-Git proof');
  const source = jarVerifierCurrentSource(), findings = jarVerifierFindings(e4, plan, source);
  requireValue(Array.isArray(proof.commits) && proof.commits.length >= 2 && proof.commits.length <= 4096, 'verifier ancestry required');
  const commits = proof.commits.map(commitObject), current = commits[0], base = commits.at(-1);
  const accepted = commitObject(executionProof.commits.at(-1));
  requireValue(new Set(commits.map(row => row.sha)).size === commits.length && current.sha === e4.commitSha
    && current.tree === e4.checkout.expectedHeadTreeSha && base.sha === accepted.sha && base.tree === accepted.tree
    && accepted.sha === JAR_BASE && accepted.tree === JAR_BASE_TREE, 'verifier current/accepted-main identity changed');
  for (let index = 0; index < commits.length - 1; index++) requireValue(commits[index].parents.includes(commits[index + 1].sha), 'verifier ancestry mismatch');
  const introduced = commits.find(row => row.sha === findings[0].commit);
  requireValue(introduced && introduced.sha !== base.sha && introduced.parents.length > 0, 'verifier introduction absent');
  const introductionBytes = bytesFromBase64(proof.introductionContentBase64);
  requireValue(hash(introductionBytes) === JAR_VERIFIER_INTRODUCTION_SHA256, 'fixed public verifier introduction bytes changed');
  const introductionSource = jarVerifierSource(introductionBytes), baseBytes = bytesFromBase64(proof.baseContentBase64);
  requireValue(hash(baseBytes) === JAR_VERIFIER_BASE_SHA256, 'fixed accepted verifier bytes changed');
  requireVerifierFieldsAbsent(baseBytes);
  verifyPath(proof.currentPath, current.tree, source.path, source.blobSha);
  verifyPath(proof.introductionPath, introduced.tree, source.path, introductionSource.blobSha);
  verifyPath(proof.basePath, base.tree, source.path, objectHash('blob', baseBytes));
  requireValue(Array.isArray(proof.introductionParents) && proof.introductionParents.length === introduced.parents.length, 'every verifier introduction parent required');
  for (const [index, row] of proof.introductionParents.entries()) {
    shape(row, ['commit','sourcePath','contentBase64'], 'verifier introduction parent');
    const parent = commitObject(row.commit), bytes = bytesFromBase64(row.contentBase64);
    requireValue(parent.sha === introduced.parents[index], 'verifier introduction parent mismatch');
    requireVerifierFieldsAbsent(bytes);
    verifyPath(row.sourcePath, parent.tree, source.path, objectHash('blob', bytes));
  }
  return seal({ schemaVersion: 'M6_PR_E_E3_GITLEAKS_VERIFIER_PUBLIC_GIT_REVIEW_V1', findings, source, introductionSource,
    sourceProofCanonicalSha256: hash(canonical(proof)), acceptedMainCommit: accepted.sha, acceptedMainTree: accepted.tree,
    acceptedSourceSha256: hash(baseBytes), inputProvenance: 'ACTUAL_ACCEPTED_GIT_COMMIT_AND_TREE_OBJECTS_AND_EXACT_SOURCE_FIELDS',
    scannerColumnsRetained: false, releaseBlocked: true, rawCandidateMaterialRetained: false,
    decision: { disposition: 'NOT_APPLICABLE', evidenceCode: 'EXACT_VERIFIER_ACCEPTED_MAIN_GIT_IDENTITIES', exploitPreconditionsSatisfied: false } });
}
function verifyExecutionDigestProof(e4, proof, plan) {
  shape(proof, ['commits','introductionParents','documents'], 'execution digest proof');
  const documents = jarExecutionDocuments(), findings = jarDigestFindings(e4, plan, documents);
  requireValue(Array.isArray(proof.commits) && proof.commits.length >= 2 && proof.commits.length <= 4096, 'execution digest ancestry required');
  const commits = proof.commits.map(commitObject), current = commits[0], base = commits.at(-1);
  requireValue(new Set(commits.map(row => row.sha)).size === commits.length && current.sha === e4.commitSha
    && current.tree === e4.checkout.expectedHeadTreeSha && base.sha === JAR_BASE && base.tree === JAR_BASE_TREE, 'execution digest current/base identity changed');
  for (let index = 0; index < commits.length - 1; index++) requireValue(commits[index].parents.includes(commits[index + 1].sha), 'execution digest ancestry mismatch');
  const introduced = commits.find(row => row.sha === findings[0].commit);
  requireValue(introduced && introduced.sha !== base.sha && introduced.parents.length > 0, 'execution document introduction absent');
  requireValue(Array.isArray(proof.introductionParents) && proof.introductionParents.length === introduced.parents.length, 'every execution introduction parent required');
  for (const [index, row] of proof.introductionParents.entries()) {
    shape(row, ['commit','sourcePaths'], 'execution introduction parent'); const parent = commitObject(row.commit);
    requireValue(parent.sha === introduced.parents[index] && Array.isArray(row.sourcePaths) && row.sourcePaths.length === 2, 'execution parent identity/path count mismatch');
    for (const [offset, source] of row.sourcePaths.entries()) {
      shape(source, ['path','pathProof'], 'execution parent source'); requireValue(source.path === documents[offset].source.path, 'execution parent path mismatch');
      verifyPath(source.pathProof, parent.tree, source.path, null);
    }
  }
  requireValue(Array.isArray(proof.documents) && proof.documents.length === 2, 'two complete execution document proofs required');
  for (const [index, row] of proof.documents.entries()) {
    shape(row, ['path','currentPath','introductionPath','basePath'], 'execution document'); const source = documents[index].source;
    requireValue(row.path === source.path, 'execution document path mismatch');
    verifyPath(row.currentPath, current.tree, source.path, source.blobSha);
    verifyPath(row.introductionPath, introduced.tree, source.path, source.blobSha);
    verifyPath(row.basePath, base.tree, source.path, null);
  }
  return seal({ schemaVersion: 'M6_PR_E_E3_GITLEAKS_EXECUTION_DIGEST_REVIEW_V1', findings,
    documents: documents.map(row => row.source), fields: documents.flatMap(row => row.fields),
    sourceProofCanonicalSha256: hash(canonical(proof)), acceptedMainCommit: JAR_BASE, acceptedMainTree: JAR_BASE_TREE,
    acceptedGitleaksInventorySha256: JAR_ACCEPTED_FINDINGS,
    inputDigestProvenance: 'INDEPENDENTLY_REHASHED_EXECUTED_LOCAL_INPUTS_NOT_GIT_OBJECTS',
    scannerColumnsRetained: false, localExecutionOnly: true, canonicalCiEvidence: false, releaseBlocked: true,
    decision: { disposition: 'NOT_APPLICABLE', evidenceCode: 'EXACT_PINNED_EXECUTED_INPUT_DIGEST_FIELDS', exploitPreconditionsSatisfied: false } });
}


export function readGitleaksCaptureHashReviewPlan() {
  const plan = JSON.parse(readFileSync(new URL('../../docs/m6/m6-pr-e-e3-gitleaks-capture-hash-review.json', import.meta.url), 'utf8'));
  requireValue(plan.contentSha256 === PLAN_SHA256 && seal(plan).contentSha256 === PLAN_SHA256, 'plan mismatch');
  return plan;
}
/** Only the fixed schema's reviewed roles, never a search for hash-shaped strings. */
export function gitleaksPlanMetadataFields() {
  const plan = readGitleaksCaptureHashReviewPlan();
  const bytes = readFileSync(new URL(`../../${PLAN_PATH}`, import.meta.url));
  requireValue(hash(bytes) === PLAN_FILE_SHA256 && bytes.toString('utf8') === `${JSON.stringify(plan, null, 2)}\n`, 'frozen plan bytes mismatch');
  const positions = new Map();
  const walk = (value, pointer, line) => {
    positions.set(pointer, { value, line });
    if (!value || typeof value !== 'object') return;
    let next = line + 1;
    for (const [name, child] of Object.entries(value)) {
      walk(child, `${pointer}/${name.replaceAll('~', '~0').replaceAll('/', '~1')}`, next);
      next += JSON.stringify(child, null, 2).split('\n').length;
    }
  };
  walk(plan, '', 1);
  const selectors = [
    ['/acceptedPublicBaseCommit', 'ACCEPTED_BASE_COMMIT'], ['/acceptedPublicBaseTreeSha', 'ACCEPTED_BASE_TREE'],
    ['/acceptedPublicBaseUrl', 'ACCEPTED_BASE_COMMIT_URL'], ['/scannerIdentity/sourceCommit', 'ACCEPTED_EXTERNAL_SCANNER_SOURCE_METADATA'],
    ...plan.retainedFindings.map((_, index) => [`/retainedFindings/${index}/commit`, 'ACCEPTED_INVENTORY_COMMIT_METADATA']),
    ['/source/blobSha', 'CAPTURE_SOURCE_BLOB'], ['/collector/blobSha', 'CAPTURE_COLLECTOR_BLOB'],
    ...plan.observations.map((_, index) => [`/observations/${index}/input/blobSha`, 'ACCEPTED_INPUT_BLOB']),
  ];
  requireValue(plan.retainedFindings.length === 29 && plan.observations.length === 3 && selectors.length === 38, 'fixed metadata role cardinality drift');
  const lines = bytes.toString('utf8').split('\n');
  return selectors.map(([jsonPointer, role]) => {
    const position = positions.get(jsonPointer);
    requireValue(position && typeof position.value === 'string', 'fixed metadata pointer absent');
    requireValue(role === 'ACCEPTED_BASE_COMMIT_URL'
      ? position.value === `https://github.com/${plan.repository}/commit/${plan.acceptedPublicBaseCommit}`
      : SHA40.test(position.value), 'fixed metadata field type/value drift');
    return { jsonPointer, role, line: position.line, lineSha256: hash(lines[position.line - 1]) };
  });
}
function planMetadataSource() {
  const bytes = readFileSync(new URL(`../../${PLAN_PATH}`, import.meta.url));
  requireValue(hash(bytes) === PLAN_FILE_SHA256, 'frozen plan bytes mismatch');
  return { path: PLAN_PATH, blobSha: objectHash('blob', bytes), sha256: PLAN_FILE_SHA256, byteLength: bytes.length };
}
function expectedPlanMetadataFinding(plan, commit, field) {
  const policy = plan.retainedFindings.find(finding => finding.ruleId === 'sourcegraph-access-token');
  requireValue(policy && policy.sourceClass === 'E4_GITLEAKS', 'accepted metadata scanner policy absent');
  return expectedFinding({ finding: policy, source: { path: PLAN_PATH } }, commit, field);
}
function expectedFinding(plan, commit, observation) {
  const { ruleId, description, sourceClass } = plan.finding, path = plan.source.path, line = observation.line;
  const fingerprint = `${commit}:${path}:${ruleId}:${line}`;
  return { commit, description, endLine: line, findingId: hash(['GITLEAKS', fingerprint, ruleId, path, String(line)].join('\0')),
    fingerprint, path, ruleId, sourceClass, startLine: line };
}
function requireCurrentFindings(e4, plan) {
  requireGitleaksCaptureInventory(e4);
  requireCompleteCurrentE4(e4, plan.repository);
  const scanner = e4.scanners.gitleaks;
  for (const [field, value] of Object.entries(plan.scannerIdentity)) requireValue(scanner[field] === value, `scanner identity drift ${field}`);
  requireValue([32, 70, 194, 374, 554].includes(scanner.findingCount), 'requires the exact supported capture inventory');
  const ids = new Set(plan.retainedFindings.map(finding => finding.findingId));
  requireValue(canonical(scanner.findings.filter(finding => ids.has(finding.findingId))) === canonical(plan.retainedFindings),
    'retained finding metadata/order drift');
  requireValue(canonical(scanner.findings) === canonical([...scanner.findings].sort((a, b) => a.findingId.localeCompare(b.findingId))), 'current finding order drift');
  const unretained = scanner.findings.filter(finding => !ids.has(finding.findingId));
  const added = unretained.filter(finding => finding.path === plan.source.path);
  requireValue(added.length === 3 && SHA40.test(added[0].commit || '') && added.every(finding => finding.commit === added[0].commit),
    'exact common introduction required');
  const expected = plan.observations.map(observation => expectedFinding(plan, added[0].commit, observation))
    .sort((a, b) => a.findingId.localeCompare(b.findingId));
  requireValue(canonical(added) === canonical(expected), 'unreviewed addition or exact finding identity drift');
  const metadata = unretained.filter(finding => finding.path === PLAN_PATH);
  if ([70, 194, 374, 554].includes(scanner.findingCount)) {
    requireValue(metadata.length === 38 && SHA40.test(metadata[0].commit || '')
      && metadata.every(finding => finding.commit === metadata[0].commit), 'exact plan metadata introduction required');
    const expectedMetadata = gitleaksPlanMetadataFields().map(field => expectedPlanMetadataFinding(plan, metadata[0].commit, field))
      .sort((a, b) => a.findingId.localeCompare(b.findingId));
    requireValue(canonical(metadata) === canonical(expectedMetadata), 'unreviewed plan metadata identity drift');
  } else requireValue(metadata.length === 0, 'unexpected plan metadata records');
  const executionFields = [194, 374, 554].includes(scanner.findingCount) ? jarDigestFindings(e4, plan) : [];
  const fixtureFields = [194, 374, 554].includes(scanner.findingCount) ? [jarFixtureFinding(e4, plan)] : [];
  const verifierFields = [194, 374, 554].includes(scanner.findingCount) ? jarVerifierFindings(e4, plan) : [];
  const langFields = [374, 554].includes(scanner.findingCount) ? langDigestFindings(e4, expectedFinding, plan.finding) : [];
  const resourcesFlattenFields = scanner.findingCount === 554 ? resourcesFlattenDigestFindings(e4, expectedFinding, plan.finding) : [];
  requireValue(unretained.length === added.length + metadata.length + executionFields.length + fixtureFields.length + verifierFields.length + langFields.length + resourcesFlattenFields.length, 'unreviewed finding outside exact source paths');
  return added;
}
function verifyBlob(contentBase64, expected) {
  const bytes = bytesFromBase64(contentBase64);
  requireValue(bytes.length === expected.byteLength && objectHash('blob', bytes) === expected.blobSha
    && hash(bytes) === expected.sha256, 'complete source blob/hash mismatch');
  return bytes;
}
function verifyProof(e4, findings, proof, plan) {
  shape(proof, ['commits', 'introductionParents', 'baseSourcePath', 'source', 'collector', 'inputs',
    ...([70, 194, 374, 554].includes(e4.scanners.gitleaks.findingCount) ? ['planMetadata'] : []),
    ...([194, 374, 554].includes(e4.scanners.gitleaks.findingCount) ? ['executionDigests', 'fixturePublicCommit', 'verifierPublicGit'] : []),
    ...([374, 554].includes(e4.scanners.gitleaks.findingCount) ? ['langExecutionDigests'] : []),
    ...(e4.scanners.gitleaks.findingCount === 554 ? ['resourcesFlattenExecutionDigests'] : [])], 'proof');
  requireValue(Array.isArray(proof.commits) && proof.commits.length >= 2 && proof.commits.length <= 4096, 'Git ancestry proof required');
  const commits = proof.commits.map(commitObject);
  requireValue(new Set(commits.map(commit => commit.sha)).size === commits.length, 'duplicate ancestry commit');
  requireValue(commits[0].sha === e4.commitSha && commits[0].tree === e4.checkout.expectedHeadTreeSha, 'exact current Git Head/tree mismatch');
  for (let index = 0; index < commits.length - 1; index++) requireValue(commits[index].parents.includes(commits[index + 1].sha), 'Git parent ancestry mismatch');
  const base = commits.at(-1), introduced = commits.find(commit => commit.sha === findings[0].commit);
  requireValue(base.sha === plan.acceptedPublicBaseCommit && base.tree === plan.acceptedPublicBaseTreeSha
    && introduced && introduced.sha !== base.sha && introduced.parents.length > 0, 'introduction/accepted public base provenance mismatch');
  requireValue(Array.isArray(proof.introductionParents) && proof.introductionParents.length === introduced.parents.length,
    'every introduction parent absence proof required');
  for (let index = 0; index < introduced.parents.length; index++) {
    const row = proof.introductionParents[index]; shape(row, ['commit', 'sourcePath'], 'introduction parent');
    const parent = commitObject(row.commit);
    requireValue(parent.sha === introduced.parents[index], 'introduction parent identity/order mismatch');
    verifyPath(row.sourcePath, parent.tree, plan.source.path, null);
  }
  verifyPath(proof.baseSourcePath, base.tree, plan.source.path, null);
  for (const name of ['source', 'collector']) {
    const row = proof[name], expected = plan[name];
    shape(row, ['contentBase64', 'currentPath', 'introductionPath'], name);
    verifyBlob(row.contentBase64, expected);
    verifyPath(row.currentPath, commits[0].tree, expected.path, expected.blobSha);
    verifyPath(row.introductionPath, introduced.tree, expected.path, expected.blobSha);
  }
  const text = bytesFromBase64(proof.source.contentBase64).toString('utf8'), capture = JSON.parse(text);
  requireValue(capture && !Array.isArray(capture) && capture.status === 'PASSED_CAPTURE_ONLY'
    && capture.sourceHead === base.sha && capture.sourceTree === base.tree && capture.sourceDirty === true
    && capture.canonicalCiEvidence === false && capture.captureInputsStable === true
    && capture.collectorSha256 === plan.collector.sha256 && capture.collectorSnapshot === 'capture.py'
    && capture.captureInputs && typeof capture.captureInputs === 'object' && !Array.isArray(capture.captureInputs),
  'exact capture object/producer binding mismatch');
  requireValue(Array.isArray(proof.inputs) && proof.inputs.length === plan.observations.length, 'all three source input proofs required');
  for (let index = 0; index < plan.observations.length; index++) {
    const observation = plan.observations[index], input = observation.input, row = proof.inputs[index];
    shape(row, ['path', 'contentBase64', 'basePath', 'introductionPath', 'currentPath'], 'source input');
    requireValue(row.path === input.path && observation.jsonPointer === `/captureInputs/${input.path.replaceAll('~', '~0').replaceAll('/', '~1')}`,
      'exact input path/JSON pointer mismatch');
    const bytes = verifyBlob(row.contentBase64, input), digest = hash(bytes);
    requireValue(Object.hasOwn(capture.captureInputs, input.path) && capture.captureInputs[input.path] === digest,
      'capture scalar is not SHA256 of accepted complete source bytes');
    const line = text.split('\n')[observation.line - 1];
    requireValue(line === `    "${input.path}": "${digest}",` && hash(line) === observation.lineSha256,
      'exact capture input field/line mismatch');
    verifyPath(row.basePath, base.tree, input.path, input.blobSha);
    verifyPath(row.introductionPath, introduced.tree, input.path, input.blobSha);
    verifyPath(row.currentPath, commits[0].tree, input.path, input.blobSha);
  }
}
function verifyPlanMetadataProof(e4, proof, plan) {
  shape(proof, ['commits', 'introductionParents', 'baseSourcePath', 'source', 'scannerBaseline'], 'plan metadata proof');
  const findings = e4.scanners.gitleaks.findings.filter(finding => finding.path === PLAN_PATH);
  requireValue(findings.length === 38 && Array.isArray(proof.commits) && proof.commits.length >= 2
    && proof.commits.length <= 4096, 'plan metadata ancestry required');
  const commits = proof.commits.map(commitObject), current = commits[0], base = commits.at(-1);
  requireValue(new Set(commits.map(row => row.sha)).size === commits.length, 'duplicate plan metadata ancestry');
  requireValue(current.sha === e4.commitSha && current.tree === e4.checkout.expectedHeadTreeSha,
    'plan metadata exact current Git Head/tree mismatch');
  for (let index = 0; index < commits.length - 1; index++) requireValue(commits[index].parents.includes(commits[index + 1].sha), 'plan metadata parent ancestry mismatch');
  const introduced = commits.find(row => row.sha === findings[0].commit);
  requireValue(base.sha === plan.acceptedPublicBaseCommit && base.tree === plan.acceptedPublicBaseTreeSha
    && introduced && introduced.sha !== base.sha && introduced.parents.length > 0, 'plan metadata introduction/base mismatch');
  requireValue(Array.isArray(proof.introductionParents) && proof.introductionParents.length === introduced.parents.length,
    'every plan metadata introduction parent required');
  for (let index = 0; index < introduced.parents.length; index++) {
    const row = proof.introductionParents[index]; shape(row, ['commit', 'sourcePath'], 'plan metadata parent');
    const parent = commitObject(row.commit); requireValue(parent.sha === introduced.parents[index], 'plan metadata parent identity/order mismatch');
    verifyPath(row.sourcePath, parent.tree, PLAN_PATH, null);
  }
  verifyPath(proof.baseSourcePath, base.tree, PLAN_PATH, null);
  const source = planMetadataSource(); shape(proof.source, ['contentBase64', 'currentPath', 'introductionPath'], 'plan metadata source');
  const bytes = verifyBlob(proof.source.contentBase64, source);
  requireValue(canonical(JSON.parse(bytes.toString('utf8'))) === canonical(plan), 'plan metadata source/schema mismatch');
  verifyPath(proof.source.currentPath, current.tree, PLAN_PATH, source.blobSha);
  verifyPath(proof.source.introductionPath, introduced.tree, PLAN_PATH, source.blobSha);
  // The external source commit is documentary metadata in the accepted Git blob.
  // No upstream object, tag-to-commit or source-to-binary reproducibility claim.
  shape(proof.scannerBaseline, ['contentBase64', 'basePath'], 'accepted scanner baseline');
  const baselineBytes = bytesFromBase64(proof.scannerBaseline.contentBase64);
  verifyPath(proof.scannerBaseline.basePath, base.tree, SCANNER_BASELINE_PATH, objectHash('blob', baselineBytes));
  const baseline = JSON.parse(baselineBytes.toString('utf8')), scanner = baseline.scanners?.gitleaks;
  requireValue(baseline.schemaVersion === 'M6_PR_E_E4_SCANNER_BASELINE_V1' && baseline.repository === plan.repository
    && scanner?.sourceRepository === 'gitleaks/gitleaks' && scanner.version === plan.scannerIdentity.version
    && scanner.sourceCommit === plan.scannerIdentity.sourceCommit
    && scanner.installation?.sha256 === plan.scannerIdentity.assetSha256, 'accepted external scanner metadata tuple mismatch');
  const fields = gitleaksPlanMetadataFields();
  // The enclosing capture proof already verifies all five actual blob preimages,
  // including accepted-base paths for the three input documents.
  return seal({ schemaVersion: 'M6_PR_E_E3_GITLEAKS_PLAN_METADATA_REVIEW_V1', source, findings, fields,
    sourceProofCanonicalSha256: hash(canonical(proof)), acceptedPublicBaseCommit: base.sha,
    acceptedInventoryProvenance: { acceptedE4CanonicalSha256: plan.acceptedE4CanonicalSha256,
      retainedFindingCount: plan.retainedFindingCount, retainedFindingsSha256: hash(canonical(plan.retainedFindings)),
      basis: 'EXACT_ACCEPTED_INVENTORY_METADATA_NO_INDEPENDENT_COMMIT_OBJECT_REVALIDATION' },
    scannerBaselinePath: SCANNER_BASELINE_PATH, scannerBaselineBlobSha: objectHash('blob', baselineBytes),
    externalScannerProvenance: 'ACCEPTED_BASELINE_METADATA_ONLY_NO_UPSTREAM_OBJECT_TAG_OR_REPRODUCIBLE_BUILD_CLAIM',
    decision: { disposition: 'NOT_APPLICABLE', evidenceCode: 'EXACT_PINNED_PLAN_GIT_IDENTITY_METADATA', exploitPreconditionsSatisfied: false } });
}
function receipt(e4, findings, proof, plan) {
  const planMetadataReview = [70, 194, 374, 554].includes(e4.scanners.gitleaks.findingCount) ? verifyPlanMetadataProof(e4, proof.planMetadata, plan) : null;
  const executionDigestReview = [194, 374, 554].includes(e4.scanners.gitleaks.findingCount) ? verifyExecutionDigestProof(e4, proof.executionDigests, plan) : null;
  const fixturePublicCommitReview = executionDigestReview ? verifyFixturePublicCommitProof(e4, proof.fixturePublicCommit, proof.executionDigests, plan) : null;
  const verifierPublicGitReview = executionDigestReview ? verifyVerifierPublicGitProof(e4, proof.verifierPublicGit, proof.executionDigests, plan) : null;
  const langExecutionDigestReview = [374, 554].includes(e4.scanners.gitleaks.findingCount)
    ? verifyLangExecutionDigestProof(e4, proof.langExecutionDigests, expectedFinding, plan.finding) : null;
  const resourcesFlattenExecutionDigestReview = e4.scanners.gitleaks.findingCount === 554
    ? verifyResourcesFlattenExecutionDigestProof(e4, proof.resourcesFlattenExecutionDigests, expectedFinding, plan.finding) : null;
  return seal({ schemaVersion: resourcesFlattenExecutionDigestReview ? 'M6_PR_E_E3_GITLEAKS_CAPTURE_HASH_REVIEW_EVIDENCE_V7' : langExecutionDigestReview ? 'M6_PR_E_E3_GITLEAKS_CAPTURE_HASH_REVIEW_EVIDENCE_V6' : executionDigestReview ? 'M6_PR_E_E3_GITLEAKS_CAPTURE_HASH_REVIEW_EVIDENCE_V5' : planMetadataReview ? 'M6_PR_E_E3_GITLEAKS_CAPTURE_HASH_REVIEW_EVIDENCE_V2' : 'M6_PR_E_E3_GITLEAKS_CAPTURE_HASH_REVIEW_EVIDENCE_V1', repository: e4.repository,
    commitSha: e4.commitSha, sourceE4CanonicalSha256: e4.contentSha256, planCanonicalSha256: plan.contentSha256,
    acceptedPublicBaseCommit: plan.acceptedPublicBaseCommit, acceptedE4CanonicalSha256: plan.acceptedE4CanonicalSha256,
    retainedFindingCount: plan.retainedFindingCount, currentFindingCount: e4.scanners.gitleaks.findingCount,
    retainedFindingsSha256: hash(canonical(plan.retainedFindings)), findings, source: plan.source,
    collector: plan.collector, observations: plan.observations, proof, decision: plan.decision, ...plan.limits,
    ...(planMetadataReview ? { planMetadataReview } : {}), ...(executionDigestReview ? { executionDigestReview, fixturePublicCommitReview, verifierPublicGitReview } : {}),
    ...(langExecutionDigestReview ? { langExecutionDigestReview } : {}),
    ...(resourcesFlattenExecutionDigestReview ? { resourcesFlattenExecutionDigestReview } : {}) });
}
export function verifyGitleaksCaptureHashReview(e4, snapshot) {
  const plan = readGitleaksCaptureHashReviewPlan(), findings = requireCurrentFindings(e4, plan);
  requireValue(snapshot?.repository === e4.repository && snapshot.commitSha === e4.commitSha
    && snapshot.currentE4CanonicalSha256 === e4.contentSha256, 'exact current E4 binding required');
  verifyProof(e4, findings, snapshot.proof, plan);
  return receipt(e4, findings, snapshot.proof, plan);
}
export function requireGitleaksCaptureHashReviewReceipt(e4, evidence) {
  const verified = verifyGitleaksCaptureHashReview(e4, { repository: evidence?.repository, commitSha: evidence?.commitSha,
    currentE4CanonicalSha256: evidence?.sourceE4CanonicalSha256, proof: evidence?.proof });
  requireValue(canonical(evidence) === canonical(verified), 'receipt drift');
  return verified;
}
function decisionReview(evidence, finding) {
  if (isResourcesFlattenExecutionDigestPath(finding.path)) {
    const review = evidence.resourcesFlattenExecutionDigestReview, document = review.documents.find(row => row.path === finding.path);
    const field = review.fields.find(row => row.path === finding.path && row.line === finding.startLine);
    return { reviewLayer: 'E3-I2-APPEND_ONLY-GITLEAKS-RESOURCES-FLATTEN-EXECUTION-DIGEST', reviewCanonicalSha256: evidence.contentSha256,
      sourceCommitSha: finding.commit, sourceBlobSha: document.blobSha, sourcePath: document.path,
      sourceLineSha256: field.lineSha256, jsonPointer: field.jsonPointer, metadataRole: field.role,
      scannerColumnsRetained: false, derivedJsonValueColumns: field.derivedJsonValueColumns,
      inputDigestProvenance: review.inputDigestProvenance, acceptedMainCommit: review.acceptedMainCommit,
      evidenceCode: review.decision.evidenceCode, exploitPreconditionsSatisfied: false };
  }
  if (isLangExecutionDigestPath(finding.path)) {
    const review = evidence.langExecutionDigestReview, document = review.documents.find(row => row.path === finding.path);
    const field = review.fields.find(row => row.path === finding.path && row.line === finding.startLine);
    return { reviewLayer: 'E3-I2-APPEND_ONLY-GITLEAKS-LANG-EXECUTION-DIGEST', reviewCanonicalSha256: evidence.contentSha256,
      sourceCommitSha: finding.commit, sourceBlobSha: document.blobSha, sourcePath: document.path,
      sourceLineSha256: field.lineSha256, jsonPointer: field.jsonPointer, metadataRole: field.role,
      scannerColumnsRetained: false, derivedJsonValueColumns: field.derivedJsonValueColumns,
      inputDigestProvenance: review.inputDigestProvenance, acceptedMainCommit: review.acceptedMainCommit,
      evidenceCode: review.decision.evidenceCode, exploitPreconditionsSatisfied: false };
  }
  if (finding.path === JAR_VERIFIER_PATH) {
    const review = evidence.verifierPublicGitReview, field = review.source.fields.find(row => row.line === finding.startLine);
    return { reviewLayer: 'E3-I2-APPEND_ONLY-GITLEAKS-VERIFIER-PUBLIC-GIT', reviewCanonicalSha256: evidence.contentSha256,
      sourceCommitSha: finding.commit, sourceBlobSha: review.introductionSource.blobSha, currentSourceBlobSha: review.source.blobSha,
      sourcePath: review.source.path, sourceLineSha256: field.lineSha256, metadataRole: field.role,
      sourceField: field.field, scannerColumnsRetained: false, derivedLiteralColumns: field.derivedLiteralColumns,
      acceptedMainCommit: review.acceptedMainCommit, acceptedMainTree: review.acceptedMainTree,
      inputProvenance: review.inputProvenance, evidenceCode: review.decision.evidenceCode, exploitPreconditionsSatisfied: false };
  }
  if (finding.path === JAR_FIXTURE_PATH) {
    const review = evidence.fixturePublicCommitReview;
    return { reviewLayer: 'E3-I2-APPEND_ONLY-GITLEAKS-FIXTURE-PUBLIC-COMMIT', reviewCanonicalSha256: evidence.contentSha256,
      sourceCommitSha: finding.commit, sourceBlobSha: review.introductionSource.blobSha, currentSourceBlobSha: review.source.blobSha,
      sourcePath: review.source.path, sourceLineSha256: review.source.lineSha256, metadataRole: review.source.role,
      sourceField: review.source.field, scannerColumnsRetained: false, derivedLiteralColumns: review.source.derivedLiteralColumns,
      acceptedMainCommit: review.acceptedMainCommit, acceptedMainTree: review.acceptedMainTree,
      inputProvenance: review.inputProvenance, evidenceCode: review.decision.evidenceCode, exploitPreconditionsSatisfied: false };
  }
  if (JAR_DOCUMENTS.some(document => document.path === finding.path)) {
    const review = evidence.executionDigestReview, document = review.documents.find(row => row.path === finding.path);
    const field = review.fields.find(row => row.path === finding.path && row.line === finding.startLine);
    return { reviewLayer: 'E3-I2-APPEND_ONLY-GITLEAKS-EXECUTION-DIGEST', reviewCanonicalSha256: evidence.contentSha256,
      sourceCommitSha: finding.commit, sourceBlobSha: document.blobSha, sourcePath: document.path,
      sourceLineSha256: field.lineSha256, jsonPointer: field.jsonPointer, metadataRole: field.role,
      scannerColumnsRetained: false, derivedJsonValueColumns: field.derivedJsonValueColumns,
      inputDigestProvenance: review.inputDigestProvenance, acceptedMainCommit: review.acceptedMainCommit,
      evidenceCode: review.decision.evidenceCode, exploitPreconditionsSatisfied: false };
  }
  if (finding.path === PLAN_PATH) {
    const metadata = evidence.planMetadataReview, field = metadata.fields.find(item => item.line === finding.startLine);
    return { reviewLayer: 'E3-I2-APPEND_ONLY-GITLEAKS-PLAN-METADATA', reviewCanonicalSha256: evidence.contentSha256,
      sourceCommitSha: finding.commit, sourceBlobSha: metadata.source.blobSha, sourcePath: metadata.source.path,
      sourceLineSha256: field.lineSha256, jsonPointer: field.jsonPointer, metadataRole: field.role,
      acceptedPublicBaseCommit: evidence.acceptedPublicBaseCommit, evidenceCode: metadata.decision.evidenceCode,
      ...(field.role === 'ACCEPTED_EXTERNAL_SCANNER_SOURCE_METADATA' ? { externalScannerProvenance: metadata.externalScannerProvenance } : {}),
      ...(field.role === 'ACCEPTED_INVENTORY_COMMIT_METADATA' ? { acceptedInventoryProvenance: metadata.acceptedInventoryProvenance } : {}),
      exploitPreconditionsSatisfied: false };
  }
  const observation = evidence.observations.find(item => item.line === finding.startLine);
  return { reviewLayer: 'E3-I2-APPEND_ONLY-GITLEAKS-CAPTURE-HASH', reviewCanonicalSha256: evidence.contentSha256,
    sourceCommitSha: finding.commit, sourceBlobSha: evidence.source.blobSha, sourcePath: evidence.source.path,
    sourceLineSha256: observation.lineSha256, jsonPointer: observation.jsonPointer, input: observation.input,
    acceptedPublicBaseCommit: evidence.acceptedPublicBaseCommit, evidenceCode: evidence.decision.evidenceCode,
    exploitPreconditionsSatisfied: false };
}
export function gitleaksCaptureReviewFindings(evidence) {
  return [...evidence.findings, ...(evidence.planMetadataReview?.findings || []), ...(evidence.executionDigestReview?.findings || []), ...(evidence.fixturePublicCommitReview ? [evidence.fixturePublicCommitReview.finding] : []), ...(evidence.verifierPublicGitReview?.findings || []), ...(evidence.langExecutionDigestReview?.findings || []), ...(evidence.resourcesFlattenExecutionDigestReview?.findings || [])];
}
function requireDecisionIdentity(decision, finding) {
  requireValue(decision && ['ruleId', 'commit', 'fingerprint', 'path', 'startLine', 'endLine']
    .every(field => decision.sourceIdentity?.[field] === finding[field]), 'decision source identity drift');
}
export function applyGitleaksCaptureHashDecisions(decisions, evidence) {
  const findings = gitleaksCaptureReviewFindings(evidence), expectedCount = evidence.resourcesFlattenExecutionDigestReview ? 525 : evidence.langExecutionDigestReview ? 345 : evidence.verifierPublicGitReview ? 165 : evidence.planMetadataReview ? 41 : 3;
  const byKey = new Map(findings.map(finding => [key(finding), finding]));
  requireValue(byKey.size === expectedCount && findings.length === expectedCount, 'distinct exact reviewed identities required');
  const applied = new Set();
  const result = decisions.map(decision => {
    const finding = byKey.get(key(decision)); if (!finding) return decision;
    requireDecisionIdentity(decision, finding);
    requireValue(decision.disposition === 'UNRESOLVED' && decision.severityBand === 'UNKNOWN' && !decision.reviewEvidence,
      'may only review exact unresolved findings');
    requireValue(!applied.has(key(finding)), 'duplicate current decision');
    applied.add(key(finding));
    return stable({ ...decision, disposition: 'NOT_APPLICABLE', exploitPreconditions: ['ACTUAL_CREDENTIAL_MATERIAL_PRESENT'],
      reviewEvidence: decisionReview(evidence, finding) });
  });
  requireValue(applied.size === expectedCount, 'exactly three capture and any proven 38 metadata/121 execution/one fixture/two verifier/180 Lang/180 Resources-Flatten current decisions required');
  return result;
}
export function requireGitleaksCaptureHashDecisions(triage, evidence) {
  for (const finding of gitleaksCaptureReviewFindings(evidence)) {
    const decision = triage.decisions.find(item => key(item) === key(finding));
    requireDecisionIdentity(decision, finding);
    requireValue(decision.disposition === 'NOT_APPLICABLE' && decision.severityBand === 'UNKNOWN'
      && canonical(decision.exploitPreconditions) === canonical(['ACTUAL_CREDENTIAL_MATERIAL_PRESENT'])
      && canonical(decision.reviewEvidence) === canonical(decisionReview(evidence, finding)), 'current disposition drift');
  }
  requireValue(triage.decisions.filter(item => item.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-CAPTURE-HASH').length === 3,
    'current review count drift');
  requireValue(triage.decisions.filter(item => item.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-PLAN-METADATA').length === (evidence.planMetadataReview ? 38 : 0),
    'current metadata review count drift');
  requireValue(triage.decisions.filter(item => item.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-EXECUTION-DIGEST').length === (evidence.executionDigestReview ? 121 : 0),
    'current execution digest review count drift');
  requireValue(triage.decisions.filter(item => item.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-FIXTURE-PUBLIC-COMMIT').length === (evidence.fixturePublicCommitReview ? 1 : 0),
    'current fixture public-commit review count drift');
  requireValue(triage.decisions.filter(item => item.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-VERIFIER-PUBLIC-GIT').length === (evidence.verifierPublicGitReview ? 2 : 0),
    'current verifier public-Git review count drift');
  requireValue(triage.decisions.filter(item => item.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-LANG-EXECUTION-DIGEST').length === (evidence.langExecutionDigestReview ? 180 : 0),
    'current Lang execution digest review count drift');
  requireValue(triage.decisions.filter(item => item.reviewEvidence?.reviewLayer === 'E3-I2-APPEND_ONLY-GITLEAKS-RESOURCES-FLATTEN-EXECUTION-DIGEST').length === (evidence.resourcesFlattenExecutionDigestReview ? 180 : 0),
    'current Resources/Flatten execution digest review count drift');
}

/** Read only Git objects; no source, index, ref or scanner mutation. */
export function buildGitleaksCaptureHashReviewSnapshot(e4, { root, git = spawnSync } = {}) {
  const plan = readGitleaksCaptureHashReviewPlan(), findings = requireCurrentFindings(e4, plan), cache = new Map();
  const readObject = (type, sha) => {
    requireValue(SHA40.test(sha || ''), 'Git object identity required');
    const id = `${type}:${sha}`;
    if (!cache.has(id)) {
      const result = git('git', ['cat-file', type, sha], { cwd: root, maxBuffer: 32 * 1024 * 1024 });
      requireValue(result.status === 0, `Git ${type} read failed`);
      cache.set(id, { sha, contentBase64: Buffer.from(result.stdout).toString('base64') });
    }
    return cache.get(id);
  };
  const ancestry = (head, target) => {
    const previous = new Map([[head, null]]), queue = [head]; let found = false;
    while (queue.length && previous.size <= 4096) {
      const next = queue.shift(), parsed = commitObject(readObject('commit', next));
      if (next === target) { found = true; break; }
      for (const parent of parsed.parents) if (!previous.has(parent)) { previous.set(parent, next); queue.push(parent); }
    }
    requireValue(found, 'required introduction/base absent from bounded Git ancestry');
    const rows = [];
    for (let next = target; next !== null; next = previous.get(next)) rows.unshift(readObject('commit', next));
    return rows;
  };
  const commits = [...ancestry(e4.commitSha, findings[0].commit), ...ancestry(findings[0].commit, plan.acceptedPublicBaseCommit).slice(1)];
  const parsed = commits.map(commitObject), current = parsed[0], base = parsed.at(-1), intro = parsed.find(row => row.sha === findings[0].commit);
  const pathProof = (tree, path) => {
    const rows = []; let nextTree = tree;
    for (const part of path.split('/')) {
      const row = readObject('tree', nextTree); rows.push(row);
      const entry = treeEntries(bytesFromBase64(row.contentBase64)).find(item => item.name === part);
      if (!entry || entry.mode !== '40000') break;
      nextTree = entry.sha;
    }
    return rows;
  };
  const sourceProof = expected => ({ contentBase64: readObject('blob', expected.blobSha).contentBase64,
    currentPath: pathProof(current.tree, expected.path), introductionPath: pathProof(intro.tree, expected.path) });
  const snapshot = { repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256,
    proof: { commits, introductionParents: intro.parents.map(sha => {
      const commit = readObject('commit', sha); return { commit, sourcePath: pathProof(commitObject(commit).tree, plan.source.path) };
    }), baseSourcePath: pathProof(base.tree, plan.source.path), source: sourceProof(plan.source), collector: sourceProof(plan.collector),
    inputs: plan.observations.map(({ input }) => ({ path: input.path, ...sourceProof(input), basePath: pathProof(base.tree, input.path) })) } };
  if ([70, 194, 374, 554].includes(e4.scanners.gitleaks.findingCount)) {
    const finding = e4.scanners.gitleaks.findings.find(item => item.path === PLAN_PATH), source = planMetadataSource();
    const metadataCommits = [...ancestry(e4.commitSha, finding.commit), ...ancestry(finding.commit, plan.acceptedPublicBaseCommit).slice(1)];
    const metadataIntro = commitObject(readObject('commit', finding.commit));
    const baselinePath = pathProof(base.tree, SCANNER_BASELINE_PATH);
    const baselineEntry = treeEntries(bytesFromBase64(baselinePath.at(-1).contentBase64))
      .find(entry => entry.name === SCANNER_BASELINE_PATH.split('/').at(-1));
    requireValue(baselineEntry?.mode === '100644', 'accepted scanner baseline file absent');
    snapshot.proof.planMetadata = { commits: metadataCommits,
      introductionParents: metadataIntro.parents.map(sha => {
        const commit = readObject('commit', sha); return { commit, sourcePath: pathProof(commitObject(commit).tree, PLAN_PATH) };
      }), baseSourcePath: pathProof(base.tree, PLAN_PATH), source: {
        contentBase64: readObject('blob', source.blobSha).contentBase64,
        currentPath: pathProof(current.tree, PLAN_PATH), introductionPath: pathProof(metadataIntro.tree, PLAN_PATH) },
      scannerBaseline: { contentBase64: readObject('blob', baselineEntry.sha).contentBase64, basePath: baselinePath } };
  }
  if ([194, 374, 554].includes(e4.scanners.gitleaks.findingCount)) {
    const documents = jarExecutionDocuments(), findings = jarDigestFindings(e4, plan, documents);
    const digestCommits = [...ancestry(e4.commitSha, findings[0].commit), ...ancestry(findings[0].commit, JAR_BASE).slice(1)];
    const digestIntro = commitObject(readObject('commit', findings[0].commit));
    snapshot.proof.executionDigests = { commits: digestCommits,
      introductionParents: digestIntro.parents.map(sha => {
        const commit = readObject('commit', sha), parent = commitObject(commit);
        return { commit, sourcePaths: documents.map(({source}) => ({ path: source.path, pathProof: pathProof(parent.tree, source.path) })) };
      }), documents: documents.map(({source}) => ({ path: source.path,
        currentPath: pathProof(current.tree, source.path), introductionPath: pathProof(digestIntro.tree, source.path),
        basePath: pathProof(JAR_BASE_TREE, source.path) })) };
    const source = jarFixtureCurrentSource(), finding = jarFixtureFinding(e4, plan, source);
    const fixtureCommits = [...ancestry(e4.commitSha, finding.commit), ...ancestry(finding.commit, JAR_BASE).slice(1)];
    const fixtureIntro = commitObject(readObject('commit', finding.commit));
    const introductionPath = pathProof(fixtureIntro.tree, source.path);
    const introductionEntry = treeEntries(bytesFromBase64(introductionPath.at(-1).contentBase64))
      .find(entry => entry.name === source.path.split('/').at(-1));
    requireValue(introductionEntry?.mode === '100644', 'fixture introduction file absent');
    snapshot.proof.fixturePublicCommit = { commits: fixtureCommits,
      introductionParents: fixtureIntro.parents.map(sha => {
        const commit = readObject('commit', sha); return { commit, sourcePath: pathProof(commitObject(commit).tree, source.path) };
      }), currentPath: pathProof(current.tree, source.path), introductionPath,
      basePath: pathProof(JAR_BASE_TREE, source.path), introductionContentBase64: readObject('blob', introductionEntry.sha).contentBase64 };
  }
  if ([194, 374, 554].includes(e4.scanners.gitleaks.findingCount)) {
    const source = jarVerifierCurrentSource(), findings = jarVerifierFindings(e4, plan, source);
    const verifierCommits = [...ancestry(e4.commitSha, findings[0].commit), ...ancestry(findings[0].commit, JAR_BASE).slice(1)];
    const verifierIntro = commitObject(readObject('commit', findings[0].commit));
    const completePath = tree => {
      const sourcePath = pathProof(tree, source.path);
      const entry = treeEntries(bytesFromBase64(sourcePath.at(-1).contentBase64))
        .find(entry => entry.name === source.path.split('/').at(-1));
      requireValue(entry?.mode === '100644', 'verifier source file absent');
      return { sourcePath, contentBase64: readObject('blob', entry.sha).contentBase64 };
    };
    const introduction = completePath(verifierIntro.tree), accepted = completePath(JAR_BASE_TREE);
    snapshot.proof.verifierPublicGit = { commits: verifierCommits, currentPath: pathProof(current.tree, source.path),
      introductionPath: introduction.sourcePath, introductionContentBase64: introduction.contentBase64,
      basePath: accepted.sourcePath, baseContentBase64: accepted.contentBase64,
      introductionParents: verifierIntro.parents.map(sha => {
        const commit = readObject('commit', sha); return { commit, ...completePath(commitObject(commit).tree) };
      }) };
  }
  if ([374, 554].includes(e4.scanners.gitleaks.findingCount)) {
    const context = langExecutionDocuments(), findings = langDigestFindings(e4, expectedFinding, plan.finding, context);
    const { documents, provenance, base: accepted } = context;
    const digestCommits = [...ancestry(e4.commitSha, findings[0].commit), ...ancestry(findings[0].commit, accepted.sourceHead).slice(1)];
    const introduced = commitObject(readObject('commit', findings[0].commit));
    const sources = [...documents.map(row => row.source), ...provenance];
    const prove = source => ({ path: source.path, currentPath: pathProof(current.tree, source.path),
      introductionPath: pathProof(introduced.tree, source.path), basePath: pathProof(accepted.sourceTree, source.path) });
    snapshot.proof.langExecutionDigests = { commits: digestCommits,
      introductionParents: introduced.parents.map(sha => {
        const commit = readObject('commit', sha), parent = commitObject(commit);
        return { commit, sourcePaths: sources.map(source => ({ path: source.path, pathProof: pathProof(parent.tree, source.path) })) };
      }), documents: documents.map(row => prove(row.source)), provenance: provenance.map(prove) };
  }
  if (e4.scanners.gitleaks.findingCount === 554) {
    const context = resourcesFlattenExecutionDocuments(), findings = resourcesFlattenDigestFindings(e4, expectedFinding, plan.finding, context);
    const { documents, provenance, base: accepted } = context;
    const digestCommits = [...ancestry(e4.commitSha, findings[0].commit), ...ancestry(findings[0].commit, accepted.sourceHead).slice(1)];
    const introduced = commitObject(readObject('commit', findings[0].commit));
    const sources = [...documents.map(row => row.source), ...provenance];
    const prove = source => ({ path: source.path, currentPath: pathProof(current.tree, source.path),
      introductionPath: pathProof(introduced.tree, source.path), basePath: pathProof(accepted.sourceTree, source.path) });
    snapshot.proof.resourcesFlattenExecutionDigests = { commits: digestCommits,
      introductionParents: introduced.parents.map(sha => {
        const commit = readObject('commit', sha), parent = commitObject(commit);
        return { commit, sourcePaths: sources.map(source => ({ path: source.path, pathProof: pathProof(parent.tree, source.path) })) };
      }), documents: documents.map(row => prove(row.source)), provenance: provenance.map(prove) };
  }
  verifyGitleaksCaptureHashReview(e4, snapshot);
  return snapshot;
}
