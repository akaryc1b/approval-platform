// Exact public HMAC test fixtures, not absent credential material or an allowlist.
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { bytesFromBase64, commitObject, treeEntries, verifyPath, objectHash } from './gitleaks-git-source-proof.mjs';
import { requireCompleteCurrentE4 } from './scanner-evidence-provenance.mjs';

export const HMAC_TEST_REVIEW_BASE = '5a2f166b57851ff0063f3f158cb5e2754e7b602f';
const BASE_TREE = 'c550553a330d137392ce207718f0407af079247d';
const INVENTORY = '1bca2f9e5eefa0c0883ec6a44b3f5a466550c676335e0087cb188e00b077b8de';
const INPUTS = [
  {"path":"server-modules/approval-connector-generic/src/test/java/io/github/akaryc1b/approval/connector/generic/GenericRestAuthenticationConnectorTest.java","blobSha":"ee11b04e3492cc80dcaaf05f528b798e989ac569","sha256":"854fa459be703beee3f3e8ccd50fb27be4456465ba49baa837d992f4a8fbfe8f"},
  {"path":"pom.xml","blobSha":"57b4aa75a6792e365e79f3753f9563a0f4164a59","sha256":"c409ffb415a3fe2d62ca510495141502fb884fd930ffda6d6f65381bfd192c86"},
  {"path":"server-modules/approval-connector-generic/pom.xml","blobSha":"b4b7b1f3d97b79dc36afb9d06ee93ef8a8d33bd6","sha256":"7f226080386ca849a0076ff51c748096ada6f2d2646fe4049f7e761b9ee878c9"},
  {"path":"server-modules/approval-connector-generic/src/test/java/io/github/akaryc1b/approval/connector/generic/StubHttpClient.java","blobSha":"f835304a019be7c977406401badd8f9b5573f4e5","sha256":"18a9f952b6c7d7433035665d902876562fabba2b90b67c950899bd5c5328d27d"},
  {"path":"server-modules/approval-connector-generic/src/test/java/io/github/akaryc1b/approval/connector/generic/GenericRestBusinessCallbackConnectorTest.java","blobSha":"4945e68a3e14f203635ee294973a8a06fb18a233","sha256":"d3f43b940c0aa8b34960f1ad63f96c930fec25f3d3761213f903c5e7b324ba7a"},
  {"path":"server-modules/approval-connector-generic/pom.xml","blobSha":"27c137b41427b0f404cd4ff355411bec4122a6d7","sha256":"6e000059803d20e38183af13e63f1268c1430c304e1d8d1839a167dec07601f8"},
  {"path":"integrations/host-sdk/src/test/java/io/github/akaryc1b/approval/host/security/HostRequestSecurityTest.java","blobSha":"c52e3df13267b777719da548e59ade9cece9d5e9","sha256":"25d69e80be0af5f16664d657e4edad0ca89bce020a9f7cfc915ffa59c4b79dc4"},
  {"path":"pom.xml","blobSha":"92ce18ee35543118c609394a7536d1469d1fee0c","sha256":"943da6ceb8372f5d1dfc2765ccfd3ad9e6057bf184e0a91f602a3346945ee875"},
  {"path":"integrations/host-sdk/pom.xml","blobSha":"ae4f526286130a3ec746486c7b62337463a556f7","sha256":"9857e28fa838570855082b56cbbe3a0449c8e26f9a2fb9b95d47f016d5792dac"},
  {"path":"server-modules/approval-integration-core/src/test/java/io/github/akaryc1b/approval/integration/webhook/WebhookSecurityTest.java","blobSha":"0f011d9e1768cac63fc023daaab68256e30fdbc6","sha256":"81cfff49c6e9ac19f1ff9bd901dd18d44a28782d62fef035d7ab7d0116a023ac"},
  {"path":"server-modules/approval-integration-core/pom.xml","blobSha":"0195f5b74f5760f495b52e2ba46623056ead4596","sha256":"f973a4d8370d25cef5be1d0e975a1cd2a51582f31bc2624bbf601aea4b443a3b"},
  {"path":"server-modules/approval-connector-generic/src/test/java/io/github/akaryc1b/approval/connector/generic/GenericRestBusinessCallbackConnectorTest.java","blobSha":"f07869240cf9a436a6c730b61337f52b79c43eb7","sha256":"c7b9db27dab690aca75295ab5bb071d907867622dafdcc18b47f84a69c55ef21"},
  {"path":"integrations/host-sdk/pom.xml","blobSha":"935c6c7595354ebeaa1647e0fcbdf9fe770f3593","sha256":"5db201bf6906066f060219cac9336ced9834db14eccf501622ad37f521835724"},
  {"path":"server-modules/approval-integration-core/src/test/java/io/github/akaryc1b/approval/integration/webhook/WebhookSecurityTest.java","blobSha":"b6363f45f693cbea64453c7f04402075bb69c77f","sha256":"ded7c71f121b6f2473c26eba28ad11b1b32e1164b67af3cb9a47533af8bf9895"},
  {"path":"examples/connector-smoke/test/client.test.mjs","blobSha":"1facdd3bd26a2e54120d1b7a4f1bdc12c1ceabe5","sha256":"5ef32385bbcdb308d609b194c20abc04771920277f98058e2fccb8ac51ac4368"},
  {"path":"examples/connector-smoke/package.json","blobSha":"b6bb5e3d77b5eca49550ea445895f61d62d2d03d","sha256":"a46ecb74e895da90a491d3453fc751463c9a27b3b81c070c18a4b64b8557ea5e"},
  {"path":"server-modules/approval-connector-generic/src/test/java/io/github/akaryc1b/approval/connector/generic/GenericRestOrganizationConnectorTest.java","blobSha":"0eb78d745bcc7afdfe8f73c227f6e2411477233e","sha256":"d7d6516a0a8cf5bb54c45dae6ca780f11b6d7f7b84ac3fdf9d67188c0ec8ffdb"},
  {"path":"integrations/host-sdk/pom.xml","blobSha":"0c5ba656594eb13ea69830a4415f6715bb8dcea7","sha256":"2d933d9c89c7a0ee99b4870091a880ac086b295501914297c9bb6a21dd751078"},
  {"path":"pom.xml","blobSha":"207f23ab0d3f93e5f97c5c2ecf222f753c1ccd3c","sha256":"7aaef759cbdebaafde2383186cbb90861c23f51bd4fe3219614ed0fa8c22a9ab"},
];
const CONTEXTS = [
  {"input":0,"line":22,"lineSha256":"5b1c94a79bac3c883b423f1fc52888c31eb3487f1028f9f02d21920e02747afd","role":"SHARED_HTTP_STUB"},
  {"input":4,"line":30,"lineSha256":"5b1c94a79bac3c883b423f1fc52888c31eb3487f1028f9f02d21920e02747afd","role":"LOOPBACK_HTTP_SERVER"},
  {"input":6,"line":21,"lineSha256":"5b1c94a79bac3c883b423f1fc52888c31eb3487f1028f9f02d21920e02747afd","role":"IN_PROCESS_HMAC"},
  {"input":9,"line":18,"lineSha256":"5b1c94a79bac3c883b423f1fc52888c31eb3487f1028f9f02d21920e02747afd","role":"IN_PROCESS_HMAC"},
  {"input":11,"line":40,"lineSha256":"5b1c94a79bac3c883b423f1fc52888c31eb3487f1028f9f02d21920e02747afd","role":"CAPTURING_HTTP_STUB"},
  {"input":13,"line":18,"lineSha256":"5b1c94a79bac3c883b423f1fc52888c31eb3487f1028f9f02d21920e02747afd","role":"IN_PROCESS_HMAC"},
  {"input":14,"line":11,"lineSha256":"041b293b360fd00fdf0050ebdf54446f392d6e4284d419a1c5d05e071a34ef96","role":"NODE_FAKE_FETCH"},
  {"input":16,"line":26,"lineSha256":"5b1c94a79bac3c883b423f1fc52888c31eb3487f1028f9f02d21920e02747afd","role":"SHARED_HTTP_STUB"},
];
const OBSERVATIONS = [
  {"findingId":"0985995f4e3a85d8ec212e8c796fdec4ba193f78df8da9790b84a7c2ea9657bd","commit":"6f35a3e57f29f02091079f1fca60c01831ed3fe8","source":0,"supports":[1,2,3],"ancestorOfBase":false,"currentSource":0,"retainedAnchor":"d0f15e4f42bc847c8d69ed86c0b45ce0c7737fb1"},
  {"findingId":"36f2e4fdf9f6c7410d767c66eb140235bb95a78fd14678ac3e57e8007a97b979","commit":"42820e7d35ac81f1cd7d7eed878f709abc173ea5","source":0,"supports":[1,2,3],"ancestorOfBase":true,"currentSource":0},
  {"findingId":"5fb13ba6d950d034667e4de83992e03880d08657381792c78227415b01f3dc40","commit":"47dee26f8443dd008f4553bc77ee4991ee7b4047","source":4,"supports":[1,5],"ancestorOfBase":false,"currentSource":11,"retainedAnchor":"1d4f0956a43e8c582cafbed05687e1540c77fa9b"},
  {"findingId":"637786f50111c46bbe0b6b23dc966b6bf24d658a1fe2bf35087896fddfb2a28b","commit":"234dc1f8a3b2990ceb648e959c80bfde36e17961","source":6,"supports":[7,8],"ancestorOfBase":false,"currentSource":6,"retainedAnchor":"d5ee4a0e6337310958905237152ceab8f1229c02"},
  {"findingId":"8bb6dc41061f8b36fb8a6583b84aa1088061758f5428bc138bbd26637703fcef","commit":"9ce273becd217c80217792a9d66edc79d4100e96","source":9,"supports":[1,10],"ancestorOfBase":true,"currentSource":9},
  {"findingId":"a2a6e026423dfb0dc4bf5fe01a59b8fbfdb3daecdc5adc296bf43796588c4795","commit":"9ce273becd217c80217792a9d66edc79d4100e96","source":11,"supports":[1,5],"ancestorOfBase":true,"currentSource":11},
  {"findingId":"a753581c267b30c7d0af6939fa893031c9d0dfabb90cef5c57685c4445d0684a","commit":"b99747739ba39096928d9bcca00fff646bb934a5","source":6,"supports":[7,12],"ancestorOfBase":true,"currentSource":6},
  {"findingId":"ab87670ff8c48d0cc1cea8261abf39492589fbd8f2fc1af21fbfa40418e0ed96","commit":"f37e12b65f16bb07f387c91bb13dc998a563ed1c","source":13,"supports":[1,10],"ancestorOfBase":false,"currentSource":9,"retainedAnchor":"1d4f0956a43e8c582cafbed05687e1540c77fa9b"},
  {"findingId":"bc06c52dd704f3c706d16d4ba6002d973726f5f8bacf9a49550e3d9fdebaf775","commit":"c322cc42d4a79f15ba62db6c8c85c50c589ac717","source":14,"supports":[15],"ancestorOfBase":false,"currentSource":14,"retainedAnchor":"ef3455997c1c632d247d4fd1cfc558386f70c33b"},
  {"findingId":"bdf58828e510359641ac81816e5a9d33826e4d8179d2c3a1d897bc57b82e30ba","commit":"42820e7d35ac81f1cd7d7eed878f709abc173ea5","source":16,"supports":[1,2,3],"ancestorOfBase":true,"currentSource":16},
  {"findingId":"c02016c68b36664c6cb9520df274c8ceb2f66fa088859bb8dfba66dec5f93c83","commit":"bbbd0edbc0d9055791e42e7068134dc13276549d","source":16,"supports":[1,2,3],"ancestorOfBase":false,"currentSource":16,"retainedAnchor":"d0f15e4f42bc847c8d69ed86c0b45ce0c7737fb1"},
  {"findingId":"eda6c6277ddf6f5e2d49df048e0c481b951919760765963bff6305e02b262906","commit":"49f85c98930a42edf71ce74d8129af9cb8157c84","source":14,"supports":[15],"ancestorOfBase":true,"currentSource":14},
];
const CURRENT = [15,14,17,6,18,2,0,11,16,3,10,9];
const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const LAYER = 'E3-I2-APPEND-ONLY-GITLEAKS-PUBLIC-HMAC-TEST';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const seal = value => { const { contentSha256, ...payload } = value; return stable({ ...payload, contentSha256: hash(canonical(payload)) }); };
const requireValue = (condition, message) => { if (!condition) throw new Error(`Gitleaks HMAC test ${message}`); };
const shape = (value, keys) => requireValue(value && canonical(Object.keys(value).sort()) === canonical([...keys].sort()), 'proof shape changed');
const contextFor = index => CONTEXTS.find(row => row.input === index);
export const historicalHmacTestSources = () => OBSERVATIONS.map(row => ({ ...structuredClone(row), ...INPUTS[row.source], ...contextFor(row.source) }));
export const hmacTestCurrentScope = () => CURRENT.map(index => structuredClone(INPUTS[index]));
const gitRead = args => {
  const result = spawnSync('git', args, { cwd: ROOT, timeout: 10000, maxBuffer: 32 * 1024 * 1024 });
  requireValue(result.status === 0, 'required local Git input unavailable'); return Buffer.from(result.stdout);
};
function refHeads(containing = null) {
  const args = ['for-each-ref', '--format=%(objectname)', ...(containing ? [`--contains=${containing}`] : []), 'refs/heads', 'refs/remotes'];
  const rows = [...new Set(gitRead(args).toString('utf8').trim().split('\n').filter(Boolean))].sort();
  requireValue(rows.length > 0 && rows.length <= 4096 && rows.every(row => /^[a-f0-9]{40}$/.test(row)), 'retained history refs required'); return rows;
}
function checkedBytes(pin, bytes) {
  requireValue(objectHash('blob', bytes) === pin.blobSha && hash(bytes) === pin.sha256, 'pinned input bytes changed'); return bytes.toString('utf8');
}
// No source/secret bytes enter a receipt, exception, cache, log or tool argument.
// Re-read original blobs AND current files on every receipt validation.
function inspectSources() {
  const historical = new Map();
  for (const pin of INPUTS) if (!historical.has(pin.blobSha)) historical.set(pin.blobSha, gitRead(['cat-file', 'blob', pin.blobSha]));
  const texts = INPUTS.map(pin => checkedBytes(pin, historical.get(pin.blobSha)));
  for (const index of CURRENT) {
    const pin = INPUTS[index], url = new URL(`../../${pin.path}`, import.meta.url), stat = lstatSync(url);
    requireValue(stat.isFile() && !stat.isSymbolicLink(), 'current input must be a regular file'); checkedBytes(pin, readFileSync(url));
  }
  const values = [];
  for (const row of CONTEXTS) {
    const source = texts[row.input], line = source.split('\n')[row.line - 1];
    requireValue(hash(line || '') === row.lineSha256, 'original fixture line changed');
    const match = row.role === 'NODE_FAKE_FETCH'
      ? line.match(/^const SECRET_TEXT = '([a-f0-9]{32})';$/)
      : line.match(/^\s*private static final byte\[\] SECRET = "([a-f0-9]{32})"$/);
    requireValue(match && match[1].slice(0, 16).repeat(2) === match[1], 'reviewed fixture declaration changed'); values.push(match[1]);
    if (row.role === 'NODE_FAKE_FETCH') requireValue(source.includes("from 'node:test'") && source.includes('fetchImpl') && source.includes('new Response'), 'Node test transport changed');
    else {
      requireValue(source.includes('@Test') && source.includes('.getBytes(StandardCharsets.UTF_8)'), 'JUnit fixture ownership changed');
      if (row.role === 'SHARED_HTTP_STUB') requireValue(source.includes('new StubHttpClient'), 'shared test transport changed');
      if (row.role === 'CAPTURING_HTTP_STUB') requireValue(source.includes('class CapturingHttpClient extends HttpClient'), 'capturing test transport changed');
      if (row.role === 'LOOPBACK_HTTP_SERVER') requireValue(source.includes('new InetSocketAddress("127.0.0.1", 0)') && source.includes('@BeforeEach') && source.includes('@AfterEach'), 'test-owned loopback lifecycle changed');
      if (row.role === 'IN_PROCESS_HMAC') requireValue(source.includes('.sign(SECRET,') && source.includes('.verify(SECRET,'), 'in-process HMAC tests changed');
    }
  }
  requireValue(new Set(values).size === 1, 'reviewed fixture relationship changed');
  for (let index = 0; index < INPUTS.length; index++) {
    const pin = INPUTS[index], source = texts[index];
    if (pin.path.endsWith('StubHttpClient.java')) requireValue(source.includes('extends HttpClient') && source.includes('responder.apply(request)') && source.includes('CompletableFuture.completedFuture(send('), 'shared stub implementation changed');
    if (pin.path.endsWith('package.json')) {
      const pkg = JSON.parse(source); requireValue(pkg.private === true && pkg.scripts.test === 'node --test ./test/*.test.mjs', 'Node test ownership changed');
    }
    if (pin.path.endsWith('/pom.xml')) requireValue(source.includes('<relativePath>../../pom.xml</relativePath>'), 'direct build parent changed');
  }
}
function findings(e4) {
  requireCompleteCurrentE4(e4, 'akaryc1b/approval-platform');
  const scanner = e4.scanners.gitleaks;
  requireValue(scanner.version === '8.30.1' && scanner.sourceCommit === '83d9cd684c87d95d656c1458ef04895a7f1cbd8e'
    && scanner.assetSha256 === '551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb'
    && scanner.mode === 'FULL_GIT_HISTORY' && scanner.redactionPercent === 100, 'pinned fresh scanner identity required');
  requireValue(scanner.findingCount === 554 && hash(canonical(scanner.findings)) === INVENTORY, 'complete unchanged 554 observation inventory required');
  return historicalHmacTestSources().map(source => {
    const finding = scanner.findings.find(row => row.findingId === source.findingId), fingerprint = `${source.commit}:${source.path}:generic-api-key:${source.line}`;
    requireValue(finding && finding.sourceClass === 'E4_GITLEAKS' && finding.commit === source.commit && finding.path === source.path
      && finding.ruleId === 'generic-api-key' && finding.startLine === source.line && finding.endLine === source.line && finding.fingerprint === fingerprint
      && finding.findingId === hash(['GITLEAKS', fingerprint, finding.ruleId, source.path, String(source.line)].join('\0')), 'exact finding identity changed'); return finding;
  });
}
function proofReader(proof) {
  shape(proof, ['objects', 'currentCommits', 'currentScope', 'sources']);
  requireValue(Array.isArray(proof.objects) && proof.objects.length > 0 && proof.objects.length <= 8192, 'bounded object table required');
  const rows = new Map(), used = new Set();
  for (const row of proof.objects) {
    shape(row, ['type', 'sha', 'contentBase64']);
    requireValue(['commit', 'tree'].includes(row.type) && /^[a-f0-9]{40}$/.test(row.sha) && !rows.has(row.sha), 'unique commit/tree objects required');
    requireValue(objectHash(row.type, bytesFromBase64(row.contentBase64)) === row.sha, 'Git object hash changed'); rows.set(row.sha, row);
  }
  const read = (type, sha) => { const row = rows.get(sha); requireValue(row?.type === type, 'missing/wrong-type Git object'); used.add(sha); return { sha, contentBase64: row.contentBase64 }; };
  const chain = (ids, first, last) => {
    requireValue(Array.isArray(ids) && ids.length > 0 && ids.length <= 4096 && new Set(ids).size === ids.length, 'bounded unique ancestry required');
    const parsed = ids.map(sha => commitObject(read('commit', sha)));
    requireValue(parsed[0].sha === first && parsed.at(-1).sha === last, 'ancestry endpoints changed');
    for (let i = 0; i < parsed.length - 1; i++) requireValue(parsed[i].parents.includes(parsed[i + 1].sha), 'ancestry parent changed'); return parsed;
  };
  const path = (ids, tree, pin) => { requireValue(Array.isArray(ids), 'path proof required'); verifyPath(ids.map(sha => read('tree', sha)), tree, pin.path, pin.blobSha); };
  return { chain, path, finish: () => requireValue(used.size === rows.size, 'unused Git object rejected') };
}
export function verifyGitleaksHmacTestReview(e4, snapshot) {
  const reviewed = findings(e4); inspectSources();
  requireValue(snapshot?.repository === e4.repository && snapshot.commitSha === e4.commitSha
    && snapshot.currentE4CanonicalSha256 === e4.contentSha256, 'exact E4 binding required');
  const proof = snapshot.proof, reader = proofReader(proof), current = reader.chain(proof.currentCommits, e4.commitSha, HMAC_TEST_REVIEW_BASE);
  requireValue(current[0].tree === e4.checkout.expectedHeadTreeSha && current.at(-1).tree === BASE_TREE, 'current/base tree changed');
  requireValue(Array.isArray(proof.currentScope) && proof.currentScope.length === CURRENT.length, 'complete current build/test scope required');
  for (let index = 0; index < CURRENT.length; index++) {
    const row = proof.currentScope[index], pin = INPUTS[CURRENT[index]]; shape(row, ['input', 'acceptedPath', 'currentPath']);
    requireValue(row.input === CURRENT[index], 'current scope identity/order changed');
    reader.path(row.acceptedPath, BASE_TREE, pin); reader.path(row.currentPath, current[0].tree, pin);
  }
  requireValue(Array.isArray(proof.sources) && proof.sources.length === 12, 'exactly twelve historical sources required');
  const retained = new Set(refHeads());
  for (let index = 0; index < OBSERVATIONS.length; index++) {
    const expected = OBSERVATIONS[index], row = proof.sources[index]; shape(row, ['findingId', 'commits', 'paths']);
    requireValue(row.findingId === expected.findingId, 'historical source identity/order changed');
    const first = expected.ancestorOfBase ? HMAC_TEST_REVIEW_BASE : row.commits?.[0];
    if (!expected.ancestorOfBase) requireValue(retained.has(first) && row.commits.includes(expected.retainedAnchor), 'pinned off-main retained-ref ancestry required');
    const commits = reader.chain(row.commits, first, expected.commit), inputs = [expected.source, ...expected.supports];
    requireValue(Array.isArray(row.paths) && row.paths.length === inputs.length, 'historical source/build/helper closure required');
    for (let i = 0; i < inputs.length; i++) {
      const member = row.paths[i]; shape(member, ['input', 'trees']); requireValue(member.input === inputs[i], 'historical input identity/order changed');
      reader.path(member.trees, commits.at(-1).tree, INPUTS[inputs[i]]);
    }
  }
  reader.finish();
  return seal({ schemaVersion: 'M6_PR_E_E3_GITLEAKS_PUBLIC_HMAC_TEST_REVIEW_V1', repository: e4.repository,
    commitSha: e4.commitSha, sourceE4CanonicalSha256: e4.contentSha256, reviewBasisCommit: HMAC_TEST_REVIEW_BASE, reviewBasisTree: BASE_TREE,
    retainedFindingCount: 554, retainedFindingsSha256: INVENTORY, findings: reviewed, sources: historicalHmacTestSources(),
    inputPins: INPUTS, currentScope: CURRENT, proof,
    decision: { disposition: 'NOT_APPLICABLE', evidenceCode: 'EXACT_PINNED_INTENTIONALLY_PUBLIC_HMAC_TEST_FIXTURE',
      credentialMaterialPresent: true, intentionallyPublicTestFixture: true, productionCredentialExposureInReviewedTestSources: false,
      externalDeploymentUseAssessed: false, exploitPreconditionsSatisfied: false },
    releaseBlocked: true, rawCandidateMaterialRetained: false, rawSourceBlobsRetained: false });
}
export function requireGitleaksHmacTestReviewReceipt(e4, evidence) {
  const verified = verifyGitleaksHmacTestReview(e4, { repository: evidence?.repository, commitSha: evidence?.commitSha,
    currentE4CanonicalSha256: evidence?.sourceE4CanonicalSha256, proof: evidence?.proof });
  requireValue(canonical(evidence) === canonical(verified), 'receipt changed'); return verified;
}
export function hmacTestDecisions(decisions, evidence, apply = false) {
  const sources = historicalHmacTestSources(); requireValue(canonical(evidence.sources) === canonical(sources) && evidence.findings.length === 12, 'exact reviewed source scope required');
  const byId = new Map(sources.map(row => [row.findingId, row])), seen = new Set();
  const result = decisions.map(decision => {
    const source = byId.get(decision.findingId); if (!source) return decision;
    const finding = evidence.findings.find(row => row.findingId === source.findingId);
    requireValue(!seen.has(source.findingId) && decision.sourceClass === finding.sourceClass && decision.severityBand === 'UNKNOWN'
      && ['ruleId', 'commit', 'fingerprint', 'path', 'startLine', 'endLine'].every(field => decision.sourceIdentity?.[field] === finding[field]), 'decision identity changed');
    seen.add(source.findingId);
    const reviewed = stable({ ...decision, disposition: 'NOT_APPLICABLE', exploitPreconditions: ['NON_SYNTHETIC_CREDENTIAL_EXPOSURE_IN_REVIEWED_SOURCE'],
      reviewEvidence: { reviewLayer: LAYER, reviewCanonicalSha256: evidence.contentSha256, sourceCommitSha: source.commit, sourcePath: source.path,
        sourceBlobSha: source.blobSha, sourceLineSha256: source.lineSha256, materialRole: 'FUNCTIONAL_PUBLIC_HMAC_TEST_FIXTURE',
        evidenceCode: evidence.decision.evidenceCode, credentialMaterialPresent: true, exploitPreconditionsSatisfied: false } });
    if (apply) requireValue(decision.disposition === 'UNRESOLVED' && !decision.reviewEvidence, 'only exact unresolved findings may be reviewed');
    else requireValue(canonical(decision) === canonical(reviewed), 'decision review changed'); return reviewed;
  });
  requireValue(seen.size === 12 && result.filter(row => row.reviewEvidence?.reviewLayer === LAYER).length === 12, 'exactly twelve decisions required'); return result;
}
/** Read-only collector; proof deduplicates authenticated commits/trees, never blobs. */
export function buildGitleaksHmacTestReviewSnapshot(e4, { root = ROOT, git = spawnSync } = {}) {
  findings(e4); inspectSources(); const objects = new Map();
  const read = (type, sha) => {
    requireValue(['commit', 'tree'].includes(type) && /^[a-f0-9]{40}$/.test(sha || ''), 'Git proof object identity required');
    if (!objects.has(sha)) {
      const result = git('git', ['cat-file', type, sha], { cwd: root, timeout: 10000, maxBuffer: 32 * 1024 * 1024 });
      requireValue(result.status === 0 && objectHash(type, Buffer.from(result.stdout)) === sha, 'Git proof object unavailable/changed');
      objects.set(sha, { type, sha, contentBase64: Buffer.from(result.stdout).toString('base64') });
    }
    const row = objects.get(sha); requireValue(row.type === type, 'Git proof object type changed'); return { sha, contentBase64: row.contentBase64 };
  };
  const ancestry = (head, target) => {
    const previous = new Map([[head, null]]), queue = [head]; let found = false;
    while (queue.length && previous.size <= 4096) {
      const next = queue.shift(), parsed = commitObject(read('commit', next)); if (next === target) { found = true; break; }
      for (const parent of parsed.parents) if (!previous.has(parent)) { previous.set(parent, next); queue.push(parent); }
    }
    requireValue(found, 'required reviewed ancestry absent'); const rows = [];
    for (let next = target; next !== null; next = previous.get(next)) rows.unshift(next); return rows;
  };
  const path = (tree, name) => {
    const rows = []; let next = tree;
    for (const part of name.split('/')) {
      const row = read('tree', next); rows.push(next); const entry = treeEntries(bytesFromBase64(row.contentBase64)).find(item => item.name === part);
      if (!entry || entry.mode !== '40000') break; next = entry.sha;
    }
    return rows;
  };
  const currentCommits = ancestry(e4.commitSha, HMAC_TEST_REVIEW_BASE), currentTree = commitObject(read('commit', currentCommits[0])).tree;
  const currentScope = CURRENT.map(input => ({ input, acceptedPath: path(BASE_TREE, INPUTS[input].path), currentPath: path(currentTree, INPUTS[input].path) }));
  const sources = OBSERVATIONS.map(row => {
    const commits = row.ancestorOfBase ? ancestry(HMAC_TEST_REVIEW_BASE, row.commit)
      : [...ancestry(refHeads(row.retainedAnchor)[0], row.retainedAnchor), ...ancestry(row.retainedAnchor, row.commit).slice(1)];
    const tree = commitObject(read('commit', row.commit)).tree;
    return { findingId: row.findingId, commits, paths: [row.source, ...row.supports].map(input => ({ input, trees: path(tree, INPUTS[input].path) })) };
  });
  const used = new Set([...currentCommits, ...currentScope.flatMap(row => [...row.acceptedPath, ...row.currentPath]), ...sources.flatMap(row => [...row.commits, ...row.paths.flatMap(path => path.trees)])]);
  return { repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256,
    proof: { objects: [...objects.values()].filter(row => used.has(row.sha)).sort((a, b) => a.sha.localeCompare(b.sha)), currentCommits, currentScope, sources } };
}
