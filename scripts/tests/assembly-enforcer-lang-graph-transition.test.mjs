import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { syncBuiltinESMExports } from 'node:module';
import childProcess from 'node:child_process';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import test from 'node:test';
import { acceptedE2GraphProjection, resolveGitHubActionsEvidence } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { preservedHygieneJava21Source } from '../security/jar-plugin-source-continuation.mjs';
import { ASSEMBLY_ENFORCER_LANG_GRAPH, readAssemblyEnforcerLangManifest, readAssemblyEnforcerLangBaseline,
  readAssemblyEnforcerLangCandidate, readAssemblyEnforcerLangSourceWitness, readAssemblyEnforcerLangReport,
  readAssemblyEnforcerLangAcceptedLineage, readAssemblyEnforcerLangAcceptedServerRemediation,
  verifyAssemblyEnforcerLangOwnerSubstitution, verifyAssemblyEnforcerLangGraphSubstitution,
  verifyAssemblyEnforcerLangDelta, verifyAssemblyEnforcerLangCurrentEvidence, assemblyEnforcerLangReceipt }
  from '../security/assembly-enforcer-lang-graph-transition.mjs';

// Only pure production helpers are imported. No existing test suite, fixture driver,
// aggregate, scanner execution, Maven/JVM or network entrypoint is imported or run.
const root = fileURLToPath(new URL('../../', import.meta.url));
const raw = path => readFileSync(join(root, path));
const sha = value => createHash('sha256').update(value).digest('hex');
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const hash = value => sha(JSON.stringify(stable(value)));
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: hash(payload) }; };
const OLD = 'org.apache.commons:commons-lang3:jar:3.14.0';
const NEW = 'org.apache.commons:commons-lang3:jar:3.18.0';
const OWNERS = ['org.apache.maven.plugins:maven-assembly-plugin:jar:3.7.1',
  'org.apache.maven.plugins:maven-enforcer-plugin:jar:3.5.0'];
const manifest = readAssemblyEnforcerLangManifest(), candidate = readAssemblyEnforcerLangCandidate(manifest);
const currentCandidate = (commitSha = candidate.commitSha) => seal({ ...candidate, commitSha,
  githubActions: resolveGitHubActionsEvidence(root) });
const retained = { before: acceptedE2GraphProjection(readAssemblyEnforcerLangBaseline(manifest)),
  after: acceptedE2GraphProjection(candidate), beforeRaw: raw(manifest.base.pluginReport.path).toString(),
  afterRaw: readAssemblyEnforcerLangReport(manifest) };

test('M3 helper continuation reconstructs only the exact historical path/hash/bytes and size', () => {
  const path = 'scripts/security/hygiene-java21-workflow-transition.mjs', current = raw(path);
  const expected = '820cffc7c6202de35c767c445a60361c00e632e8c7163a5284b0e7490e605f0e';
  assert.equal(sha(current), '76ada2498855ebf319612b92dbaf4fa4fd223d420bd8a10e030ab11051699b07');
  const prior = preservedHygieneJava21Source(path, expected, current);
  assert.equal(sha(prior), expected); assert.equal(prior.length, 3254);
  assert.deepEqual(preservedHygieneJava21Source(path, expected, prior), prior);
  assert.deepEqual(preservedHygieneJava21Source('unrelated.mjs', expected, current), current);
  assert.deepEqual(preservedHygieneJava21Source(path, 'f'.repeat(64), current), current);
  const region = '  content = priorM3LogRetentionWorkflow(workflowPath, content);\n';
  for (const altered of [Buffer.concat([current, Buffer.from('\n')]),
    Buffer.from(current.toString().replace(region, '')),
    Buffer.from(current.toString().replace(region, region.repeat(2))),
    Buffer.from(current.toString().replace(region, region.replace('  content', '   content'))),
    Buffer.from(current.toString().replace('originalTestCommandAndExitStatusRetained: true',
      'originalTestCommandAndExitStatusRetained: false'))])
    assert.throws(() => preservedHygieneJava21Source(path, expected, altered), /current byte digest mismatch/);
});

test('M3 workflow continuation reconstructs only the exact historical path/hash/bytes and size', () => {
  const path = '.github/workflows/approval-platform-validation.yml', current = raw(path);
  const expected = '5e0d736ccc9b5b43156397534eea2e1fc612a9fd9ee653d13e31aed3090c9ea5';
  assert.equal(sha(current), 'd108d8a4544796bc6a068e1345efb85b16bd7b559a3d202db024f40d7247782d');
  const prior = preservedHygieneJava21Source(path, expected, current);
  assert.equal(sha(prior), expected); assert.equal(prior.length, 19151);
  assert.deepEqual(preservedHygieneJava21Source(path, expected, prior), prior);
  assert.deepEqual(preservedHygieneJava21Source('unrelated.yml', expected, current), current);
  assert.deepEqual(preservedHygieneJava21Source(path, 'f'.repeat(64), current), current);
  const retention = JSON.parse(raw('docs/m6/hygiene-m3-log-retention-transition.json'));
  for (const { after } of retention.workflows[path].replacements) {
    for (const replacement of ['', after.repeat(2), after.replace('m3-repository', 'm4-repository')]) {
      const altered = Buffer.from(replaceOnce(current.toString(), after, replacement));
      assert.throws(() => preservedHygieneJava21Source(path, expected, altered), /current byte digest mismatch/);
    }
  }
});

test('all nine retained source witnesses preserve their complete historical input hashes', async () => {
  for (const [name, reader] of [
    ['compiler-plugin', 'readCompilerPluginSourceWitness'],
    ['checkstyle-plugin', 'readCheckstylePluginSourceWitness'],
    ['checkstyle-guava', 'readCheckstyleGuavaSourceWitness'],
    ['boot-http', 'readBootHttpSourceWitness'],
    ['boot-utilities', 'readBootUtilitiesSourceWitness'],
    ['jacoco-antrun', 'readJacocoAntrunSourceWitness'],
    ['assembly-plexus', 'readAssemblyPlexusSourceWitness'],
    ['jar-plugin', 'readJarPluginSourceWitness'],
    ['assembly-enforcer-lang', 'readAssemblyEnforcerLangSourceWitness'],
  ]) {
    const api = await import(`../security/${name}-graph-transition.mjs`);
    assert.equal(api[reader]().releaseBlocked, true, name);
  }
});

test('retained actual Lang capture has exactly two owner replacements and a complete reversed accepted graph', () => {
  const owners = verifyAssemblyEnforcerLangOwnerSubstitution(retained.beforeRaw, retained.afterRaw);
  assert.equal(owners.ownerCount, 17); assert.equal(owners.unchangedOtherPluginOwnerCount, 15);
  assert.deepEqual(owners.changes, manifest.pluginOwnerChanges);
  for (const owner of OWNERS) {
    assert.equal(owners.before.occurrences[owner], 26); assert.equal(owners.after.occurrences[owner], 26);
    assert.ok(owners.before.owners[owner].includes(OLD)); assert.ok(owners.after.owners[owner].includes(NEW));
  }
  assert.deepEqual(verifyAssemblyEnforcerLangGraphSubstitution(retained.after, retained.before,
    retained.beforeRaw, retained.afterRaw), retained.before);
  assert.deepEqual(verifyAssemblyEnforcerLangDelta(retained.after), retained.before);
  assert.deepEqual(manifest.addedPluginCoordinates, []); assert.deepEqual(manifest.removedPluginCoordinates, [OLD]);
  assert.equal(retained.before.maven.resolvedPluginCoordinates.length, 264);
  assert.equal(retained.after.maven.resolvedPluginCoordinates.length, 263);
  const witness = readAssemblyEnforcerLangSourceWitness(manifest);
  assert.equal(witness.sourcePomFiles.length, 28); assert.equal(witness.exactHeadEvidence, false);
  assert.equal(witness.compatibilityAcceptanceClaimed, false); assert.equal(witness.scannerDispositionClaimed, false);
});

for (const [name, mutate] of [
  ['runtime component', p => { p.maven.components[0].version = 'forged'; }],
  ['runtime edge', p => { p.maven.edges.pop(); }],
  ['scope', p => { p.maven.components[0].scope = 'forged'; }],
  ['license', p => { p.maven.components[0].licenses = ['forged']; }],
  ['imported BOM', p => { p.maven.importedBoms.reverse(); }],
  ['Actions', p => { p.githubActions = { forged: true }; }],
  ['pnpm', p => { p.pnpm = { forged: true }; }],
  ['limitations', p => { p.limitations = ['forged']; }],
  ['old coordinate injected', p => { p.maven.resolvedPluginCoordinates.push(OLD); }],
  ['duplicate coordinate', p => { p.maven.resolvedPluginCoordinates.push(NEW); }],
  ['report hash', p => { p.maven.pluginResolutionSha256 = '0'.repeat(64); }],
]) test(`Lang complete graph rejects ${name}`, () => {
  const after = structuredClone(retained.after); mutate(after);
  assert.throws(() => verifyAssemblyEnforcerLangGraphSubstitution(after, retained.before,
    retained.beforeRaw, retained.afterRaw), /global owner union\/report mismatch|undeclared graph change/);
});

function editOwner(report, owner, edit) {
  return report.replace(/   [^\n]+\n(?:      [^\n]+\n)+/g, block => block.startsWith(`   ${owner}\n`) ? edit(block) : block);
}
for (const [name, mutate] of [
  ['no replacement', () => retained.beforeRaw],
  ['only one owner replaced', s => editOwner(s, OWNERS[0], block => block.replace(NEW, OLD))],
  ['unrelated owner with unchanged union', s => editOwner(s, 'org.apache.maven.plugins:maven-jar-plugin:jar:3.5.1',
    block => block.replace(`      ${NEW}\n`, ''))],
  ['unrelated owner occurrence', s => s + editOwner(s, OWNERS[0], block => block).match(/   org\.apache\.maven\.plugins:maven-clean-plugin:jar:3\.2\.0\n(?:      [^\n]+\n)+/)[0]],
  ['changed owner occurrence', s => s + s.match(/   org\.apache\.maven\.plugins:maven-assembly-plugin:jar:3\.7\.1\n(?:      [^\n]+\n)+/)[0]],
  ['extra private dependency', s => editOwner(s, OWNERS[1], block => block + '      foreign:dependency:jar:1\n')],
  ['retained Assembly Plexus regression', s => editOwner(s, OWNERS[0], block => block.replace('plexus-utils:jar:4.0.3', 'plexus-utils:jar:4.0.1'))],
  ['wrong owner version', s => s.replaceAll(OWNERS[1], OWNERS[1].replace('3.5.0', '3.6.0'))],
  ['foreign line', s => s + '\nforged report\n'],
]) test(`Lang owner evidence rejects ${name}`, () => {
  const changed = mutate(retained.afterRaw); assert.notEqual(changed, retained.afterRaw);
  assert.throws(() => verifyAssemblyEnforcerLangOwnerSubstitution(retained.beforeRaw, changed),
    /owner|replacement|Checkstyle report/);
});

test('Lang current receipt binds the complete E2/head/schema and keeps the immutable JAR lineage and release gates', () => {
  const current = currentCandidate('a'.repeat(40));
  verifyAssemblyEnforcerLangCurrentEvidence(current);
  const receipt = assemblyEnforcerLangReceipt(current);
  assert.equal(receipt.schemaVersion, 'APPROVAL_ASSEMBLY_ENFORCER_LANG_GRAPH_LINEAGE_V1');
  assert.equal(receipt.currentE2GraphDigest, ASSEMBLY_ENFORCER_LANG_GRAPH);
  assert.equal(receipt.commitSha, current.commitSha); assert.equal(receipt.sourceE2ContentSha256, current.contentSha256);
  assert.deepEqual(receipt.preservedJarLineage, readAssemblyEnforcerLangAcceptedLineage());
  assert.equal(receipt.preservedJarLineage.schemaVersion, 'APPROVAL_JAR_PLUGIN_GRAPH_LINEAGE_V1');
  assert.equal(receipt.preservedJarLineage.unchangedOtherPluginOwnerCount, 16);
  assert.equal(receipt.releaseBlocked, true); assert.equal(receipt.findingReviewRequired, true);
  assert.deepEqual(assemblyEnforcerLangReceipt({ commitSha: current.commitSha, contentSha256: current.contentSha256 }), receipt);
  assert.deepEqual(readAssemblyEnforcerLangAcceptedServerRemediation().subsequentGraphTransition, receipt.preservedJarLineage);
  assert.throws(() => assemblyEnforcerLangReceipt({ commitSha: candidate.commitSha, contentSha256: current.contentSha256 }), /identity mismatch/);
  assert.throws(() => verifyAssemblyEnforcerLangCurrentEvidence({ ...current, commitSha: candidate.commitSha }), /current E2/);
  for (const mutate of [x => { x.toolchain = { forged: true }; }, x => { x.schemaVersion = 'forged'; },
    x => { x.githubActions = { forged: true }; }, x => { x.repository = 'forged'; }, x => { x.commitSha = 'stale'; }]) {
    const altered = structuredClone(current); mutate(altered);
    assert.throws(() => verifyAssemblyEnforcerLangCurrentEvidence(seal(altered)), /current E2/);
    assert.throws(() => assemblyEnforcerLangReceipt(seal(altered)), /identity mismatch|current E2/);
  }
  const changedFullE2 = { ...current, toolchain: { forged: true } };
  assert.throws(() => assemblyEnforcerLangReceipt(changedFullE2), /current E2/);
});

test('Lang current Actions retain only the exact M3 continuation and reject historical or forged current identities', () => {
  const current = currentCandidate('b'.repeat(40)), actions = structuredClone(current.githubActions);
  const workflowPath = '.github/workflows/approval-platform-validation.yml';
  const java21 = actions.workflowToolchainTransition.java21WorkflowContinuation;
  assert.deepEqual(java21.m3LogRetentionContinuation, {
    schemaVersion: 'APPROVAL_HYGIENE_M3_LOG_RETENTION_CONTINUATION_V1',
    manifestSha256: '452c856216d8213b7705a5a83b0eaf801c146a782c1a1759fe5a7d99e75f8efc',
    workflowBlobs: { [workflowPath]: { fromBlob: 'e2d9d43a8594503f4efd9e3683ba69fe3f291581',
      toBlob: '4cdfa529d6f5209d8c0dd913b7ae845d2829ce30' } },
    originalTestCommandAndExitStatusRetained: true,
  });
  delete java21.m3LogRetentionContinuation;
  const workflow = actions.workflows.find(row => row.path === workflowPath);
  assert.equal(workflow.blobSha, '4cdfa529d6f5209d8c0dd913b7ae845d2829ce30');
  workflow.blobSha = 'e2d9d43a8594503f4efd9e3683ba69fe3f291581';
  assert.deepEqual(actions, candidate.githubActions);
  assert.deepEqual(acceptedE2GraphProjection(current), retained.after);
  assert.deepEqual(readAssemblyEnforcerLangCandidate(), candidate);
  const stale = seal({ ...candidate, commitSha: current.commitSha });
  for (const evidence of [candidate, stale]) {
    assert.throws(() => verifyAssemblyEnforcerLangCurrentEvidence(evidence), /current E2/);
    assert.throws(() => assemblyEnforcerLangReceipt(evidence), /identity mismatch/);
    assert.throws(() => assemblyEnforcerLangReceipt({ commitSha: evidence.commitSha,
      contentSha256: evidence.contentSha256 }), /identity mismatch/);
  }
  for (const mutate of [
    value => { value.workflows.find(row => row.path === workflowPath).blobSha = 'f'.repeat(40); },
    value => { value.workflows.find(row => row.path !== workflowPath).automatic = true; },
    value => { value.workflowToolchainTransition.java21WorkflowContinuation.m3LogRetentionContinuation.manifestSha256 = 'f'.repeat(64); },
    value => { value.workflowToolchainTransition.java21WorkflowContinuation.m3LogRetentionContinuation.originalTestCommandAndExitStatusRetained = false; },
    value => { delete value.workflowToolchainTransition.java21WorkflowContinuation.m3LogRetentionContinuation; },
    value => { value.interpretation.forged = true; },
  ]) {
    const altered = structuredClone(current); mutate(altered.githubActions);
    const evidence = seal(altered);
    assert.throws(() => verifyAssemblyEnforcerLangCurrentEvidence(evidence), /current E2/);
    assert.throws(() => assemblyEnforcerLangReceipt(evidence), /identity mismatch/);
    assert.throws(() => assemblyEnforcerLangReceipt({ commitSha: evidence.commitSha,
      contentSha256: evidence.contentSha256 }), /identity mismatch/);
  }
});

function replaceOnce(source, before, after) {
  assert.equal(source.split(before).length, 2, before); return source.replace(before, after);
}

test('direct Lang boundary rejects fresh source/archive/inventory mutations without invoking ancestor admission or execution', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'assembly-enforcer-lang-direct-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  cpSync(root, directory, { recursive: true, filter: path => path !== join(root, '.git') });
  for (const [file, signatures] of [
    ['scripts/security/jar-plugin-graph-transition.mjs', ['export function readJarPluginManifest() {', 'export function jarPluginReceipt(identity) {']],
    ['scripts/security/assembly-plexus-graph-transition.mjs', ['export function readAssemblyPlexusManifest() {']],
    ['scripts/security/observability-dependency-graph.mjs', ['export function verifyObservabilityGraph(e2, projection, expectedBaseDigest, expectedCommitSha) {']],
  ]) {
    const path = join(directory, file); let source = readFileSync(path, 'utf8');
    for (const signature of signatures) source = replaceOnce(source, signature, `${signature} throw new Error('Unexpected ancestor admission');`);
    writeFileSync(path, source);
  }
  const forbidden = () => { throw new Error('Unexpected subprocess or network execution'); };
  const patched = [];
  for (const [object, keys] of [[childProcess, ['spawn', 'spawnSync', 'exec', 'execSync', 'execFile', 'execFileSync', 'fork']],
    [http, ['request', 'get']], [https, ['request', 'get']], [net, ['connect', 'createConnection']], [globalThis, ['fetch']]])
    for (const key of keys) { patched.push([object, key, object[key]]); object[key] = forbidden; }
  syncBuiltinESMExports();
  t.after(() => { for (const [object, key, value] of patched) object[key] = value; syncBuiltinESMExports(); });
  const api = await import(pathToFileURL(join(directory, 'scripts/security/assembly-enforcer-lang-graph-transition.mjs')));
  // Explicitly establish that the ancestor entrypoints now throw.
  const jar = await import(pathToFileURL(join(directory, 'scripts/security/jar-plugin-graph-transition.mjs')));
  assert.throws(() => jar.readJarPluginManifest(), /Unexpected ancestor admission/);
  assert.throws(() => jar.jarPluginReceipt(candidate), /Unexpected ancestor admission/);
  const graph = await import(pathToFileURL(join(directory, 'scripts/security/observability-dependency-graph.mjs')));
  assert.throws(() => graph.verifyObservabilityGraph(), /Unexpected ancestor admission/);
  const current = currentCandidate();
  const invoke = () => api.assemblyEnforcerLangReceipt(current), originalReceipt = invoke();
  assert.deepEqual(originalReceipt, assemblyEnforcerLangReceipt(current));
  for (const [label, file] of [
    ['ancestor owner report', 'docs/operations/checkstyle-plugin-evidence/plugin-owner-report.txt'],
    ['ancestor source archive', 'docs/operations/compiler-plugin-evidence/candidate-root-pom.xml'],
    ['accepted JAR archive', 'docs/operations/jar-plugin-evidence/candidate-root-pom.xml'],
    ['accepted full JAR model', 'docs/operations/jar-plugin-evidence/candidate-effective-pom.xml'],
    ['accepted lineage', manifest.base.lineage.path], ['accepted server bridge', manifest.base.serverRemediation.path],
    ['fixed inventory', manifest.base.artifactInventory.path],
    ['current manifest', 'docs/operations/assembly-enforcer-lang-transition.json'],
    ['current E2', manifest.capture.e2.path], ['current root', 'pom.xml'], ['current module', 'apps/server/pom.xml'],
    ['current overlay', 'integrations/ruoyi5-host-starter/overlay/ruoyi-extend/ruoyi-approval-host-starter/pom.xml'],
    ['model capture', 'docs/operations/assembly-enforcer-lang-evidence/model-capture-execution.json'],
    ['model source projection', 'docs/operations/assembly-enforcer-lang-evidence/model-source-projection.json'],
    ['full current model', 'docs/operations/assembly-enforcer-lang-evidence/candidate-effective-pom.xml'],
    ['current workflow', '.github/workflows/approval-platform-validation.yml'],
    ['current Java selector', 'scripts/ci/select-java21.mjs'],
    ['current workflow helper', 'scripts/security/hygiene-java21-workflow-transition.mjs'],
    ['M3 retention manifest', 'docs/m6/hygiene-m3-log-retention-transition.json'],
  ]) {
    const target = join(directory, file), original = readFileSync(target);
    writeFileSync(target, Buffer.concat([original, Buffer.from('\n')]));
    try { assert.throws(invoke, /byte digest mismatch|retained Lang predecessor|workflow blob|source blob drift|manifest drift/, label); }
    finally { writeFileSync(target, original); }
    assert.deepEqual(invoke(), originalReceipt, `fresh restored ${label}`);
  }
  for (const file of ['pom.xml', manifest.capture.e2.path, manifest.base.lineage.path,
    'docs/operations/jar-plugin-evidence/candidate-root-pom.xml',
    'docs/operations/jar-plugin-evidence/baseline-effective-pom.xml',
    '.github/workflows/approval-platform-validation.yml',
    'scripts/security/hygiene-java21-workflow-transition.mjs',
    'docs/m6/hygiene-m3-log-retention-transition.json']) {
    api.verifyAssemblyEnforcerLangCurrentEvidence(current);
    api.verifyAssemblyEnforcerLangDelta(retained.after);
    api.readAssemblyEnforcerLangAcceptedLineage();
    const target = join(directory, file), original = readFileSync(target);
    writeFileSync(target, Buffer.concat([original, Buffer.from('\n')]));
    try { assert.throws(invoke, /byte digest mismatch|retained Lang predecessor|workflow blob|manifest drift/, `late ${file}`); }
    finally { writeFileSync(target, original); }
    assert.deepEqual(invoke(), originalReceipt, `fresh after late mutation ${file}`);
  }
  for (const file of ['docs/m6/m6-pr-e-e2-action-resolution-baseline.json',
    'docs/m6/m6-pr-e-e3-r2b-workflow-supply-chain-remediation.json']) {
    const target = join(directory, file), original = readFileSync(target), altered = JSON.parse(original);
    if (file.includes('action-resolution')) altered.interpretation.forged = true;
    else altered.forged = true;
    writeFileSync(target, JSON.stringify(altered));
    try { assert.throws(invoke, /current Actions source continuation drift/, `independent Actions pin ${file}`); }
    finally { writeFileSync(target, original); }
    assert.deepEqual(invoke(), originalReceipt, `fresh restored Actions source ${file}`);
  }
  const workflowPath = '.github/workflows/approval-platform-validation.yml';
  const workflowFile = join(directory, workflowPath), originalWorkflow = readFileSync(workflowFile);
  const retention = JSON.parse(readFileSync(join(directory, 'docs/m6/hygiene-m3-log-retention-transition.json')));
  let oldWorkflow = originalWorkflow.toString();
  for (const { before, after } of retention.workflows[workflowPath].replacements)
    oldWorkflow = replaceOnce(oldWorkflow, after, before);
  writeFileSync(workflowFile, oldWorkflow);
  try {
    for (const evidence of [current, candidate]) {
      assert.throws(() => api.verifyAssemblyEnforcerLangCurrentEvidence(evidence), /current Actions source continuation drift/);
      assert.throws(() => api.assemblyEnforcerLangReceipt(evidence), /current Actions source continuation drift/);
      assert.throws(() => api.assemblyEnforcerLangReceipt({ commitSha: evidence.commitSha,
        contentSha256: evidence.contentSha256 }), /current Actions source continuation drift/);
    }
  } finally { writeFileSync(workflowFile, originalWorkflow); }
  assert.deepEqual(invoke(), originalReceipt, 'fresh restored M3 workflow after exact rollback');
  const helperPath = 'scripts/security/hygiene-java21-workflow-transition.mjs';
  const helperFile = join(directory, helperPath), originalHelper = readFileSync(helperFile);
  writeFileSync(helperFile, preservedHygieneJava21Source(helperPath,
    '820cffc7c6202de35c767c445a60361c00e632e8c7163a5284b0e7490e605f0e', originalHelper));
  try {
    assert.throws(() => api.verifyAssemblyEnforcerLangCurrentEvidence(current), /byte digest mismatch/);
    assert.throws(invoke, /byte digest mismatch/);
    assert.throws(() => api.assemblyEnforcerLangReceipt({ commitSha: current.commitSha,
      contentSha256: current.contentSha256 }), /byte digest mismatch/);
  } finally { writeFileSync(helperFile, originalHelper); }
  assert.deepEqual(invoke(), originalReceipt, 'fresh restored M3 helper after exact rollback');
  const manifestFile = join(directory, 'docs/operations/assembly-enforcer-lang-transition.json');
  const originalManifest = readFileSync(manifestFile); let probeCount = 0;
  async function candidateProbe(editedManifest) {
    const sealedManifest = seal(editedManifest), manifestBytes = Buffer.from(JSON.stringify(sealedManifest, null, 2) + '\n');
    writeFileSync(manifestFile, manifestBytes);
    const probeFile = join(directory, `scripts/security/lang-candidate-seal-probe-${probeCount++}.mjs`);
    const source = readFileSync(join(directory, 'scripts/security/assembly-enforcer-lang-graph-transition.mjs'), 'utf8')
      .replace(/const ASSEMBLY_ENFORCER_LANG_MANIFEST_SHA256 = '[^']+';/, `const ASSEMBLY_ENFORCER_LANG_MANIFEST_SHA256 = '${sha(manifestBytes)}';`)
      .replace(/const CONTENT = '[^']+';/, `const CONTENT = '${sealedManifest.contentSha256}';`);
    writeFileSync(probeFile, source); return import(pathToFileURL(probeFile));
  }
  for (const [label, file, mutate] of [
    ['matching hashes cannot shorten fixed inventory', manifest.base.artifactInventory.path, value => {
      value.files.pop(); value.fileCount = value.files.length; value.totalBytes = value.files.reduce((n, row) => n + row.bytes, 0);
    }],
    ['matching hashes cannot substitute fixed inventory member', manifest.base.artifactInventory.path, value => {
      const path = 'scripts/security/osv-scan-coverage.mjs', bytes = readFileSync(join(directory, path));
      value.files[0] = { path, rawSha256: sha(bytes), bytes: bytes.length };
      value.totalBytes = value.files.reduce((n, row) => n + row.bytes, 0);
    }],
    ['stale lineage head', manifest.base.lineage.path, value => { value.commitSha = 'f'.repeat(40); }],
    ['forged nested receipt', manifest.base.lineage.path, value => {
      value.preservedAssemblyLineage.releaseBlocked = false; value.preservedAssemblyLineage = seal(value.preservedAssemblyLineage);
    }],
    ['forged accepted server bridge', manifest.base.serverRemediation.path, value => { value.allScannersCompleted = false; }],
    ['fabricated accepted E2', manifest.base.e2.path, value => { value.commitSha = 'f'.repeat(40); }],
  ]) {
    const target = join(directory, file), original = readFileSync(target), value = JSON.parse(original); mutate(value);
    const edited = seal(value), editedBytes = Buffer.from(JSON.stringify(edited, null, 2) + '\n');
    const changedManifest = JSON.parse(originalManifest);
    const key = Object.keys(changedManifest.base).find(key => changedManifest.base[key]?.path === file);
    changedManifest.base[key] = { path: file, rawSha256: sha(editedBytes), contentSha256: edited.contentSha256 };
    writeFileSync(target, editedBytes);
    try {
      const probe = await candidateProbe(changedManifest);
      assert.throws(() => probe.readAssemblyEnforcerLangManifest(), /accepted main1868 trust anchor mismatch/, label);
    } finally { writeFileSync(target, original); writeFileSync(manifestFile, originalManifest); }
    assert.deepEqual(invoke(), originalReceipt, `restored ${label}`);
  }
  for (const [label, mutate] of [
    ['accepted tree', value => { value.base.sourceTree = 'f'.repeat(40); }],
    ['accepted run', value => { value.base.sourceRunId = 1; }],
    ['release gate', value => { value.releaseBlocked = false; }],
    ['review gate', value => { value.findingReviewRequired = false; }],
    ['suppression', value => { value.suppressions = ['forged']; }],
    ['exception', value => { value.exceptions = ['forged']; }],
  ]) {
    const value = JSON.parse(originalManifest); mutate(value);
    try {
      const probe = await candidateProbe(value);
      assert.throws(() => probe.readAssemblyEnforcerLangManifest(), /transition identity mismatch/, label);
    } finally { writeFileSync(manifestFile, originalManifest); }
  }
  assert.deepEqual(invoke(), originalReceipt);
});
