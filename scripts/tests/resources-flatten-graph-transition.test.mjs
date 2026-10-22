import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs, { cpSync, mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { syncBuiltinESMExports } from 'node:module';
import childProcess from 'node:child_process';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import { acceptedE2GraphProjection } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { RESOURCES_FLATTEN_GRAPH, readResourcesFlattenManifest, readResourcesFlattenBaseline,
  readResourcesFlattenCandidate, readResourcesFlattenReport, readResourcesFlattenAcceptedLineage,
  readResourcesFlattenAcceptedServerRemediation, verifyResourcesFlattenOwnerSubstitution,
  verifyResourcesFlattenGraphSubstitution, verifyResourcesFlattenCurrentEvidence, resourcesFlattenReceipt }
  from '../security/resources-flatten-graph-transition.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const manifest = readResourcesFlattenManifest(), before = readResourcesFlattenBaseline(), candidate = readResourcesFlattenCandidate();
const beforeRaw = readFileSync(join(root, manifest.archives.acceptedReport.path), 'utf8'), afterRaw = readResourcesFlattenReport();
const projection = acceptedE2GraphProjection(candidate), baseline = acceptedE2GraphProjection(before);
const BASE_GRAPH = '2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94';
const OLD = 'org.codehaus.plexus:plexus-utils:jar:4.0.2', NEW = 'org.codehaus.plexus:plexus-utils:jar:4.0.3';
const OWNERS = ['org.apache.maven.plugins:maven-resources-plugin:jar:3.4.0', 'org.codehaus.mojo:flatten-maven-plugin:jar:1.7.3'];
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const sha = value => createHash('sha256').update(value).digest('hex');
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: sha(canonical(payload)) }; };
// Synthetic heads test identity mechanics only. No synthetic E2 is scanner/CI evidence.
const current = (head = 'a'.repeat(40)) => seal({ ...candidate, commitSha: head });
function mutateOwner(raw, owner, edit) {
  return raw.replace(/   [^\n]+\n(?:      [^\n]+\n)+/g, block => block.startsWith(`   ${owner}\n`) ? edit(block) : block);
}

test('actual captures retain all runtime fields and 17 owners; only the two private Utils closures change', () => {
  const result = verifyResourcesFlattenOwnerSubstitution(beforeRaw, afterRaw);
  assert.equal(result.ownerCount, 17); assert.equal(result.unchangedOtherPluginOwnerCount, 15);
  assert.deepEqual(result.changes.map(v => [v.owner, v.previousOccurrences, v.currentOccurrences]), [[OWNERS[0], 24, 26], [OWNERS[1], 1, 1]]);
  assert.deepEqual(verifyResourcesFlattenGraphSubstitution(projection, baseline, beforeRaw, afterRaw), baseline);
  assert.equal(manifest.osvInput.prior.packageCount, 466); assert.equal(manifest.osvInput.current.packageCount, 465);
  assert.equal(manifest.scannerDispositionClaimed, false); assert.equal(manifest.releaseBlocked, true);
  assert.equal(Object.hasOwn(manifest, 'candidateFindingCount'), false);
});
for (const [name, edit] of [
  ['runtime component version', p => { p.maven.components[0].version = '0'; }],
  ['runtime scope', p => { p.maven.components[0].scope = 'test'; }],
  ['runtime reference', p => { p.maven.components[0].bomRef += '-forged'; }],
  ['runtime source', p => { p.maven.components[0].source += '-forged'; }],
  ['runtime license', p => { p.maven.components[0].licenses = ['forged']; }],
  ['runtime edge', p => { p.maven.edges.pop(); }],
  ['BOM', p => { p.maven.importedBoms.pop(); }],
  ['reactor roots', p => { p.maven.reactorRoots.reverse(); }],
  ['Actions', p => { p.githubActions = {}; }],
  ['pnpm', p => { p.pnpm = {}; }],
  ['limitations', p => { p.limitations = []; }],
  ['old Utils', p => { p.maven.resolvedPluginCoordinates.push(OLD); }],
  ['duplicate coordinate', p => { p.maven.resolvedPluginCoordinates.push(NEW); }],
  ['raw owner hash', p => { p.maven.pluginResolutionSha256 = '0'.repeat(64); }],
]) test(`complete graph rejects ${name}`, () => {
  const p = structuredClone(projection); edit(p);
  assert.throws(() => verifyResourcesFlattenGraphSubstitution(p, baseline, beforeRaw, afterRaw), /global owner union\/report|undeclared graph change/);
});
for (const [name, edit] of [
  ['no replacement', () => beforeRaw],
  ['one owner only', s => mutateOwner(s, OWNERS[0], b => b.replace(NEW, OLD))],
  ['unrelated closure with same global union', s => mutateOwner(s, 'org.apache.maven.plugins:maven-jar-plugin:jar:3.5.1', b => b.replace(`      ${NEW}\n`, ''))],
  ['unrelated owner occurrence', s => s + s.match(/   org\.apache\.maven\.plugins:maven-clean-plugin:jar:3\.2\.0\n(?:      [^\n]+\n)+/)[0]],
  ['Resources count', s => s + s.match(/   org\.apache\.maven\.plugins:maven-resources-plugin:jar:3\.4\.0\n(?:      [^\n]+\n)+/)[0]],
  ['Flatten count', s => s + s.match(/   org\.codehaus\.mojo:flatten-maven-plugin:jar:1\.7\.3\n(?:      [^\n]+\n)+/)[0]],
  ['extra dependency', s => mutateOwner(s, OWNERS[1], b => b + '      foreign:dependency:jar:1\n')],
  ['owner version', s => s.replaceAll(OWNERS[1], OWNERS[1].replace('1.7.3', '1.7.4'))],
  ['unrecognized line', s => s + '\nforged report\n'],
]) test(`owner attribution rejects ${name}`, () => {
  const altered = edit(afterRaw); assert.notEqual(altered, afterRaw);
  assert.throws(() => verifyResourcesFlattenOwnerSubstitution(beforeRaw, altered), /owner|replacement|report|dependency/);
});

test('current E2 and preserved receipt require caller-verified head, full identity, and immutable accepted main905 lineage', () => {
  const e2 = current(); verifyResourcesFlattenCurrentEvidence(e2, e2.commitSha);
  const receipt = resourcesFlattenReceipt(e2, e2.commitSha);
  assert.equal(receipt.schemaVersion, 'APPROVAL_RESOURCES_FLATTEN_GRAPH_LINEAGE_V1');
  assert.equal(receipt.currentE2GraphDigest, RESOURCES_FLATTEN_GRAPH);
  assert.equal(receipt.commitSha, e2.commitSha); assert.equal(receipt.sourceE2ContentSha256, e2.contentSha256);
  assert.deepEqual(receipt.preservedLangLineage, readResourcesFlattenAcceptedLineage());
  assert.deepEqual(readResourcesFlattenAcceptedServerRemediation().subsequentGraphTransition, receipt.preservedLangLineage.preservedJarLineage);
  assert.equal(receipt.preservedLangLineage.commitSha, '905cd30fde430972da58203a61136d50a28f81e8');
  assert.equal(receipt.releaseBlocked, true); assert.equal(receipt.findingReviewRequired, true);
  assert.equal(Object.hasOwn(receipt, 'allScannersCompleted'), false);
  assert.deepEqual(resourcesFlattenReceipt({ commitSha: e2.commitSha, contentSha256: e2.contentSha256 }, e2.commitSha), receipt);
  for (const head of [undefined, '', 'b'.repeat(40), candidate.commitSha]) {
    assert.throws(() => verifyResourcesFlattenCurrentEvidence(e2, head), /current verified head/);
    assert.throws(() => resourcesFlattenReceipt(e2, head), /current verified head/);
  }
  for (const edit of [e => { e.toolchain = {}; }, e => { e.schemaVersion = 'forged'; }, e => { e.repository = 'foreign'; },
    e => { e.githubActions = {}; }, e => { e.maven.edges.pop(); }, e => { e.unknown = true; }]) {
    const value = structuredClone(e2); edit(value); const changed = seal(value);
    assert.throws(() => verifyResourcesFlattenCurrentEvidence(changed, e2.commitSha), /current E2/);
    assert.throws(() => resourcesFlattenReceipt(changed, e2.commitSha), /identity mismatch/);
  }
});

function copyFixture(directory, includeEvidence = true) {
  cpSync(join(root, 'scripts/security'), join(directory, 'scripts/security'), { recursive: true });
  // Explicit current capture closure plus static imports; no recursive old graph validation.
  const execution = JSON.parse(readFileSync(join(root, manifest.archives.modelExecution.path)));
  const paths = [...Object.keys(execution.captureInputs), 'scripts/security/resources-flatten-graph-transition.mjs',
    'scripts/security/osv-scan-coverage.mjs', 'scripts/security/scanner-report-structure.mjs'];
  if (includeEvidence) paths.push('docs/operations/resources-flatten-transition.json', ...Object.values(manifest.archives).map(a => a.path));
  if (includeEvidence) {
    const publicManifestPath = 'docs/operations/assembly-enforcer-lang-transition.json';
    paths.push(publicManifestPath, ...JSON.parse(readFileSync(join(root, 'docs/operations/assembly-enforcer-lang-evidence/accepted-artifact-inventory.json'))).files.map(row => row.path));
    function refs(value) {
      if (!value || typeof value !== 'object') return;
      if (typeof value.path === 'string' && typeof value.rawSha256 === 'string') paths.push(value.path);
      for (const child of Object.values(value)) refs(child);
    }
    refs(JSON.parse(readFileSync(join(root, publicManifestPath))));
  }
  for (const p of [...new Set(paths)]) { const target = join(directory, p); mkdirSync(dirname(target), { recursive: true }); cpSync(join(root, p), target); }
}
function forbidExecution(t) {
  const patched = [], forbidden = () => { throw new Error('Unexpected subprocess or network execution'); };
  for (const [object, keys] of [[childProcess, ['spawn', 'spawnSync', 'exec', 'execSync', 'execFile', 'execFileSync', 'fork']],
    [http, ['request', 'get']], [https, ['request', 'get']], [net, ['connect', 'createConnection']], [globalThis, ['fetch']]])
    for (const key of keys) { patched.push([object, key, object[key]]); object[key] = forbidden; }
  syncBuiltinESMExports();
  t.after(() => { for (const [object, key, value] of patched) object[key] = value; syncBuiltinESMExports(); });
}
test('new module imports without any new evidence; only selected admission reads it', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'resources-flatten-inert-'));
  t.after(() => rmSync(directory, { recursive: true, force: true })); copyFixture(directory, false); forbidExecution(t);
  const read = fs.readFileSync;
  fs.readFileSync = function (path, ...args) {
    if (String(path).includes('resources-flatten-evidence') || String(path).includes('resources-flatten-transition.json'))
      throw new Error('Unexpected new evidence import read');
    return read.call(this, path, ...args);
  }; syncBuiltinESMExports();
  t.after(() => { fs.readFileSync = read; syncBuiltinESMExports(); });
  const api = await import(pathToFileURL(join(directory, 'scripts/security/resources-flatten-graph-transition.mjs')));
  assert.equal(api.RESOURCES_FLATTEN_GRAPH, RESOURCES_FLATTEN_GRAPH);
  assert.throws(() => api.readResourcesFlattenManifest(), /ENOENT|Unexpected new evidence import read/);
});

test('direct boundary rejects fresh raw source, model, replay, immutable provenance and late mutations without execution', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'resources-flatten-direct-'));
  t.after(() => rmSync(directory, { recursive: true, force: true })); copyFixture(directory); forbidExecution(t);
  const api = await import(pathToFileURL(join(directory, 'scripts/security/resources-flatten-graph-transition.mjs')));
  const e2 = current(), invoke = () => api.resourcesFlattenReceipt(e2, e2.commitSha), expected = invoke();
  const files = ['pom.xml', 'integrations/host-sdk/pom.xml', 'apps/server/pom.xml',
    'integrations/ruoyi5-host-starter/overlay/ruoyi-extend/ruoyi-approval-host-starter/pom.xml',
    '.github/workflows/approval-platform-validation.yml', 'scripts/security/m6-pr-e-e2-generate-sbom.mjs',
    'docs/operations/resources-flatten-transition.json', 'scripts/security/assembly-enforcer-lang-graph-transition.mjs',
    'scripts/security/jar-plugin-source-continuation.mjs',
    'scripts/security/plugin-owner-parser-cache.mjs', 'scripts/security/checkstyle-plugin-graph-transition.mjs',
    'docs/operations/assembly-enforcer-lang-transition.json',
    'docs/operations/assembly-enforcer-lang-evidence/preserved-server-remediation.json',
    'docs/operations/assembly-enforcer-lang-evidence/preserved-jar-lineage.json', ...['oldRoot', 'oldSdk', 'acceptedE2',
      'acceptedReport', 'candidateE2', 'candidateReport', 'sourceWitness', 'sourceManifest', 'candidateExecution', 'modelExecution',
      'baselineNormalModel', 'candidateNormalModel', 'baselineDemoModel', 'candidateDemoModel', 'baselineLog', 'candidateLog',
      'occurrences', 'modelData', 'runtimeInputs', 'cacheInputs'].map(k => manifest.archives[k].path)];
  for (const path of files) {
    const file = join(directory, path), original = readFileSync(file);
    writeFileSync(file, Buffer.concat([original, Buffer.from('\n')]));
    try { assert.throws(invoke, /byte digest mismatch/, path); } finally { writeFileSync(file, original); }
  }
  assert.deepEqual(invoke(), expected);
  for (const p of ['pom.xml', 'integrations/host-sdk/pom.xml']) {
    api.verifyResourcesFlattenCurrentEvidence(e2, e2.commitSha);
    const file = join(directory, p), original = readFileSync(file), archive = p === 'pom.xml' ? 'oldRoot' : 'oldSdk';
    writeFileSync(file, readFileSync(join(directory, manifest.archives[archive].path)));
    try { assert.throws(invoke, /byte digest mismatch/, `late mixed pair ${p}`); } finally { writeFileSync(file, original); }
  }
  for (const archive of ['oldRoot', 'oldSdk']) {
    api.verifyResourcesFlattenCurrentEvidence(e2, e2.commitSha);
    const file = join(directory, manifest.archives[archive].path), original = readFileSync(file);
    writeFileSync(file, Buffer.concat([original, Buffer.from('\n')]));
    try { assert.throws(invoke, /byte digest mismatch/, `late old archive ${archive}`); } finally { writeFileSync(file, original); }
  }
  const sdk = join(directory, 'integrations/host-sdk/pom.xml'), originalSdk = readFileSync(sdk);
  unlinkSync(sdk); try { assert.throws(invoke, /ENOENT/); } finally { writeFileSync(sdk, originalSdk); }
  unlinkSync(sdk); symlinkSync(join(root, 'integrations/host-sdk/pom.xml'), sdk);
  try { assert.throws(invoke, /bounded real file/); } finally { unlinkSync(sdk); writeFileSync(sdk, originalSdk); }
  for (const path of ['.mvn', 'integrations/host-sdk/.mvn', 'pnpm-lock.yaml']) {
    const file = join(directory, path); symlinkSync(join(directory, 'missing-configuration-target'), file);
    try { assert.throws(invoke, /unreviewed Maven configuration or pnpm lock/, `dangling ${path}`); }
    finally { unlinkSync(file); }
  }
  assert.deepEqual(invoke(), expected);
});

test('admission final reads reject in-call XML and archival-data replacement after their first successful reads', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'resources-flatten-during-'));
  t.after(() => rmSync(directory, { recursive: true, force: true })); copyFixture(directory); forbidExecution(t);
  const api = await import(pathToFileURL(join(directory, 'scripts/security/resources-flatten-graph-transition.mjs')));
  const e2 = current(), originalRead = fs.readFileSync;
  for (const key of ['candidateNormalModel', 'modelData', 'public-server', 'public-lineage']) {
    const publicPath = key === 'public-server' ? 'docs/operations/assembly-enforcer-lang-evidence/preserved-server-remediation.json'
      : 'docs/operations/assembly-enforcer-lang-evidence/preserved-jar-lineage.json';
    const file = join(directory, manifest.archives[key]?.path ?? publicPath), original = originalRead(file); let rootReads = 0, changed = false;
    fs.readFileSync = function(path, ...args) {
      const raw = originalRead.call(this, path, ...args);
      if (String(path) === join(directory, 'pom.xml') && ++rootReads === 3) {
        writeFileSync(file, Buffer.concat([original, Buffer.from('\n')])); changed = true;
      }
      return raw;
    }; syncBuiltinESMExports();
    try { assert.throws(() => api.resourcesFlattenReceipt(e2, e2.commitSha), /byte digest mismatch/, key); assert.equal(changed, true); }
    finally { fs.readFileSync = originalRead; syncBuiltinESMExports(); writeFileSync(file, original); }
  }
});

test('matching rehashed manifests/evidence cannot replace independent accepted-main or actual-capture anchors', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'resources-flatten-anchor-'));
  t.after(() => rmSync(directory, { recursive: true, force: true })); copyFixture(directory); forbidExecution(t);
  const manifestFile = join(directory, 'docs/operations/resources-flatten-transition.json');
  const originalManifest = readFileSync(manifestFile), moduleSource = readFileSync(join(directory, 'scripts/security/resources-flatten-graph-transition.mjs'), 'utf8');
  let sequence = 0;
  async function reseal(value) {
    const changed = seal(value), raw = Buffer.from(JSON.stringify(changed, null, 2) + '\n'); writeFileSync(manifestFile, raw);
    const file = join(directory, `scripts/security/resources-flatten-anchor-probe-${sequence++}.mjs`);
    writeFileSync(file, moduleSource.replace(/const RESOURCES_FLATTEN_MANIFEST_SHA256 = '[^']+';/, `const RESOURCES_FLATTEN_MANIFEST_SHA256 = '${sha(raw)}';`)
      .replace(/const MANIFEST_CONTENT = '[^']+';/, `const MANIFEST_CONTENT = '${changed.contentSha256}';`));
    return import(pathToFileURL(file));
  }
  for (const key of ['acceptedE2', 'acceptedReport', 'oldRoot', 'oldSdk', 'sourceManifest',
    'candidateReport', 'modelExecution', 'candidateNormalModel', 'occurrences', 'modelData']) {
    const file = join(directory, manifest.archives[key].path), original = readFileSync(file), edited = Buffer.concat([original, Buffer.from('\n')]);
    writeFileSync(file, edited); const value = JSON.parse(originalManifest);
    value.archives[key].rawSha256 = sha(edited); value.archives[key].bytes = edited.length;
    try { const api = await reseal(value); assert.throws(() => api.readResourcesFlattenManifest(), /independent main905\/capture trust anchor/); }
    finally { writeFileSync(file, original); writeFileSync(manifestFile, originalManifest); }
  }
  for (const edit of [v => { v.base.sourceHead = 'f'.repeat(40); }, v => { v.base.sourceTree = 'f'.repeat(40); },
    v => { v.releaseBlocked = false; }, v => { v.findingReviewRequired = false; }, v => { v.scannerDispositionClaimed = true; },
    v => { v.suppressions = ['forged']; }, v => { v.exceptions = ['forged']; }]) {
    const value = JSON.parse(originalManifest); edit(value);
    try { const api = await reseal(value); assert.throws(() => api.readResourcesFlattenManifest(), /transition identity mismatch/); }
    finally { writeFileSync(manifestFile, originalManifest); }
  }
});

test('both router seams select the direct graph, reject stale identities/receipts and unknown graphs without candidate-context ancestor admission', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'resources-flatten-router-'));
  t.after(() => rmSync(directory, { recursive: true, force: true })); copyFixture(directory);
  // Public Lang metadata/closure validation is permitted. Old current-source
  // admission and deeper ancestor replay must still never run on the candidate.
  cpSync(join(root, 'scripts/security'), join(directory, 'scripts/security'), { recursive: true });
  for (const [file, signatures] of [
    ['jar-plugin-graph-transition.mjs', ['export function readJarPluginManifest() {', 'export function jarPluginReceipt(identity) {']],
    ['assembly-plexus-graph-transition.mjs', ['export function readAssemblyPlexusManifest() {']],
  ]) {
    const target = join(directory, 'scripts/security', file); let source = readFileSync(target, 'utf8');
    for (const signature of signatures) { assert.equal(source.split(signature).length, 2); source = source.replace(signature, `${signature} throw Error('Unexpected ancestor admission');`); }
    writeFileSync(target, source);
  }
  forbidExecution(t);
  const router = await import(pathToFileURL(join(directory, 'scripts/security/observability-dependency-graph.mjs')));
  const e2 = current(), receipt = router.verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, e2.commitSha);
  assert.deepEqual(receipt, resourcesFlattenReceipt(e2, e2.commitSha));
  const e4 = { repository: e2.repository, commitSha: e2.commitSha, e2GraphDigest: RESOURCES_FLATTEN_GRAPH,
    e2CurrentContentSha256: e2.contentSha256, e2GraphTransition: receipt };
  assert.deepEqual(router.requirePreservedGraph(e4, BASE_GRAPH, e2.commitSha), receipt);
  assert.throws(() => router.verifyObservabilityGraph(e2, projection, BASE_GRAPH), /current verified head/);
  assert.throws(() => router.requirePreservedGraph(e4, BASE_GRAPH), /current verified head/);
  assert.throws(() => router.requirePreservedGraph({ ...e4, e2CurrentContentSha256: 'f'.repeat(64) }, BASE_GRAPH, e2.commitSha), /identity mismatch/);
  assert.throws(() => router.requirePreservedGraph({ ...e4, e2GraphTransition: { ...receipt, releaseBlocked: false } }, BASE_GRAPH, e2.commitSha), /lineage mismatch/);
  const unknown = structuredClone(e2); unknown.maven.components[0].version = '0'; const changed = seal(unknown);
  assert.throws(() => router.verifyObservabilityGraph(changed, acceptedE2GraphProjection(changed), BASE_GRAPH, e2.commitSha), /E2 graph drift/);
  const sdk = join(directory, 'integrations/host-sdk/pom.xml'); writeFileSync(sdk, readFileSync(sdk, 'utf8') + '\n');
  assert.throws(() => router.requirePreservedGraph(e4, BASE_GRAPH, e2.commitSha), /byte digest mismatch/);
});

 test('recomputed public proof never reads historical full scan or main905 server report', t => {
  forbidExecution(t);
  const read = fs.readFileSync;
  fs.readFileSync = function(path, ...args) {
    assert.doesNotMatch(String(path), /accepted-main905-(?:E4|server-remediation)\.json/);
    return read.call(this, path, ...args);
  }; syncBuiltinESMExports();
  t.after(() => { fs.readFileSync = read; syncBuiltinESMExports(); });
  const e2 = current(), receipt = resourcesFlattenReceipt(e2, e2.commitSha);
  assert.equal(receipt.preservedLangProofSource, 'RECOMPUTED_FROM_PUBLIC_SOURCE_AND_ARCHIVES');
  assert.equal(Object.hasOwn(receipt, 'acceptedMain905E4Sha256'), false);
  assert.equal(Object.hasOwn(manifest.archives, 'acceptedE4'), false);
  assert.equal(Object.hasOwn(manifest.archives, 'acceptedServer'), false);
 });

test('a jointly resealed public Lang manifest and validator cannot replace the independently pinned base', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'resources-flatten-public-anchor-'));
  t.after(() => rmSync(directory, { recursive: true, force: true })); copyFixture(directory); forbidExecution(t);
  const manifestFile = join(directory, 'docs/operations/assembly-enforcer-lang-transition.json');
  const moduleFile = join(directory, 'scripts/security/assembly-enforcer-lang-graph-transition.mjs');
  const original = readFileSync(manifestFile), value = JSON.parse(original);
  const oldContent = value.contentSha256; value.inventory.componentCount++;
  const changed = seal(value), raw = Buffer.from(JSON.stringify(changed, null, 2) + '\n');
  writeFileSync(manifestFile, raw);
  writeFileSync(moduleFile, readFileSync(moduleFile, 'utf8').replace(sha(original), sha(raw)).replace(oldContent, changed.contentSha256));
  const api = await import(pathToFileURL(join(directory, 'scripts/security/resources-flatten-graph-transition.mjs')));
  const e2 = current();
  assert.throws(() => api.resourcesFlattenReceipt(e2, e2.commitSha), /byte digest mismatch.*assembly-enforcer-lang-graph-transition/);
});


test('a jointly resealed parser edit cannot replace the original independently pinned public source', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'resources-flatten-parser-anchor-'));
  t.after(() => rmSync(directory, { recursive: true, force: true })); copyFixture(directory); forbidExecution(t);
  const parserFile = join(directory, 'scripts/security/checkstyle-plugin-graph-transition.mjs');
  const moduleFile = join(directory, 'scripts/security/resources-flatten-graph-transition.mjs');
  const original = readFileSync(parserFile), changed = Buffer.concat([original, Buffer.from('\n')]);
  const source = readFileSync(moduleFile, 'utf8');
  assert.equal(source.split(sha(original)).length, 2, 'only the current parser raw pin is resealed');
  writeFileSync(parserFile, changed);
  writeFileSync(moduleFile, source.replace(sha(original), sha(changed)));
  const api = await import(pathToFileURL(moduleFile));
  const e2 = current();
  assert.throws(() => api.resourcesFlattenReceipt(e2, e2.commitSha), /public Checkstyle parser source continuation mismatch/);
});
