import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { chmodSync, mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { MAVEN, requirePinnedMaven } from '../ci/maven-toolchain.mjs';
import { publishMavenPath, verifyDistribution } from '../ci/setup-maven.mjs';
import { gitBlob, historicalMavenWorkflow, readMavenWorkflowTransition, resolveMavenWorkflowTargets,
  verifyMavenSourceContinuation, verifyMavenWorkflowTransition } from '../security/maven-workflow-transition.mjs';
import { generateEvidence } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { verifyWorkflowSupplyChainRemediation } from '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation-accepted.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = relative => readFileSync(path.join(root, relative), 'utf8');
const plan = JSON.parse(read('docs/m6/m6-pr-e-e3-r2b-workflow-supply-chain-remediation.json'));
const snapshot = () => Object.fromEntries(readdirSync(path.join(root, '.github/workflows')).sort().map(name => {
  const relative = `.github/workflows/${name}`, content = read(relative);
  return [relative, { content, blobSha: gitBlob(content) }];
}));
const fakeVersion = version => () => ({ status: 0, stdout: `Apache Maven ${version} (reviewed)\nJava version: 21\n` });
const changedPath = '.github/workflows/approval-platform-validation.yml';
const manifest = readMavenWorkflowTransition();

function currentE4() {
  const scanners = Object.fromEntries(Object.entries({ osv: 115, gitleaks: 27, semgrep: 3, zizmor: 0 }).map(([name, count]) => [name, {
    scanCompleted: true, rawReportRetained: false, candidateSecretMaterialRetained: false,
    sourceSnippetRetained: false, findingCount: count, findings: Array.from({ length: count }, (_, i) => ({ findingId: `${name}-${i}` })),
  }]));
  return { repository: plan.repository, commitSha: '7'.repeat(40), contentSha256: 'a'.repeat(64),
    allScannersCompleted: true, rawScannerReportsRetained: false, candidateSecretMaterialRetained: false,
    totalFindingCount: 145, scanners };
}
function currentSnapshot() {
  return { repository: plan.repository, commitSha: '7'.repeat(40), currentE4CanonicalSha256: 'a'.repeat(64),
    dependabotBlobSha: '38ba75af261c084b5c8984c52fb1bf23439fd1a9', scannerExecutionCount: 1,
    suppressionPathsPresent: [], workflows: snapshot() };
}

test('exact runtime rejects the image-preinstalled Maven update before resolving any graph', () => {
  assert.equal(requirePinnedMaven('mvn', fakeVersion('3.9.16')).runtimeVerified, true);
  for (const value of ['3.10.0', '3.9.16-rc1', '3.9.160', '4.0.0']) {
    assert.throws(() => requirePinnedMaven('mvn', fakeVersion(value)), /Maven runtime drift/);
  }
  assert.throws(() => requirePinnedMaven('mvn', () => ({ status: 127, stdout: '' })), /unavailable/);
  assert.match(read('scripts/security/m6-pr-e-e2-generate-sbom.mjs'), /function maven\(r\)\{requirePinnedMaven\(\);/);
});

test('corrupt official distribution bytes cannot pass verification', () => {
  assert.throws(() => verifyDistribution(Buffer.from('not the verified Apache archive')), /SHA-512 mismatch/);
});

test('checksum failure executes neither tar nor Maven and does not publish PATH', () => {
  const temporary = mkdtempSync(path.join(tmpdir(), 'maven-integrity-test-'));
  try {
    const bin = path.join(temporary, 'bin'); mkdirSync(bin);
    const curl = path.join(bin, 'curl'), tar = path.join(bin, 'tar'), marker = path.join(temporary, 'extracted');
    writeFileSync(curl, '#!/bin/sh\nfor output; do :; done\nprintf corrupt > "$output"\n');
    writeFileSync(tar, `#!/bin/sh\nprintf extracted > '${marker}'\n`);
    chmodSync(curl, 0o755); chmodSync(tar, 0o755);
    const githubPath = path.join(temporary, 'github-path'); writeFileSync(githubPath, '');
    const result = spawnSync(process.execPath, [path.join(root, 'scripts/ci/setup-maven.mjs'), temporary], {
      encoding: 'utf8', env: { ...process.env, PATH: bin, GITHUB_ACTIONS: 'true', GITHUB_PATH: githubPath },
    });
    assert.notEqual(result.status, 0); assert.match(result.stderr, /SHA-512 mismatch/);
    assert.equal(existsSync(marker), false); assert.equal(readFileSync(githubPath, 'utf8'), '');
    assert.equal(readdirSync(temporary).some(name => name.startsWith('approval-maven-')), false);
  } finally { rmSync(temporary, { recursive: true, force: true }); }
});

test('PATH publication rejects newline injection and relative paths', () => {
  for (const value of ['/tmp/maven\n/untrusted', '/tmp/maven\r/untrusted', 'relative/bin']) {
    assert.throws(() => publishMavenPath(value), /Invalid Maven PATH/);
  }
  const directory = mkdtempSync(path.join(tmpdir(), 'maven-path-test-'));
  try {
    const target = path.join(directory, 'path'); publishMavenPath('/tmp/verified/bin', target);
    assert.equal(readFileSync(target, 'utf8'), '/tmp/verified/bin\n');
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('only the five affected workflow files and eight logical Maven consumers change', () => {
  assert.deepEqual(Object.keys(manifest.workflows).sort(), [changedPath, '.github/workflows/backend-ci.yml',
    '.github/workflows/generic-spring-host-ci.yml', '.github/workflows/ruoyi5-host-ci.yml', '.github/workflows/ruoyi6-host-ci.yml']);
  assert.equal(Object.values(manifest.workflows).reduce((sum, value) => sum + value.stepCount, 0), 8);
  const actual = snapshot(), before = structuredClone(actual);
  const proof = resolveMavenWorkflowTargets(root, plan.workflowInventory.targetBlobs);
  assert.equal(proof.toolchainTransition.changedWorkflowCount, 5);
  assert.equal(proof.toolchainTransition.version, MAVEN.version);
  for (const [relative, value] of Object.entries(actual)) {
    assert.equal(proof.targetBlobs[relative], gitBlob(value.content));
    assert.equal(gitBlob(historicalMavenWorkflow(relative, value.content)), plan.workflowInventory.targetBlobs[relative]);
  }
  assert.deepEqual(actual, before);
  const logicalJobs = [...actual[changedPath].content.matchAll(/^  ([\w-]+):\n([\s\S]*?)(?=^  [\w-]+:\n|(?![\s\S]))/gm)];
  assert.deepEqual(logicalJobs.filter(match => match[2].includes(manifest.insertedStep)).map(match => match[1]),
    ['hygiene', 'backend-core', 'persistence-jdbc', 'web']);
});

for (const [name, mutate] of [
  ['extra command', content => content + '\n# unreviewed workflow byte\n'],
  ['extra setup step', content => content + manifest.insertedStep],
  ['missing setup step', content => content.replace(manifest.insertedStep, '')],
  ['changed setup command', content => content.replace('node scripts/ci/setup-maven.mjs', 'node scripts/ci/unreviewed.mjs')],
  ['unrelated permission', content => content.replace('contents: read', 'contents: write')],
]) test(`current workflow rejects ${name}, even with recomputed blob`, () => {
  const current = snapshot(); current[changedPath].content = mutate(current[changedPath].content);
  current[changedPath].blobSha = gitBlob(current[changedPath].content);
  assert.throws(() => verifyMavenWorkflowTransition(current, plan.workflowInventory.targetBlobs), /workflow|step/);
});

test('partial rollout, unknown workflow and installer source changes fail closed', () => {
  const partial = snapshot(); partial[changedPath].content = historicalMavenWorkflow(changedPath, partial[changedPath].content);
  partial[changedPath].blobSha = gitBlob(partial[changedPath].content);
  assert.throws(() => verifyMavenWorkflowTransition(partial, plan.workflowInventory.targetBlobs), /mixed/);
  const extra = snapshot(); extra['.github/workflows/unreviewed.yml'] = extra[changedPath];
  assert.throws(() => verifyMavenWorkflowTransition(extra, plan.workflowInventory.targetBlobs), /inventory/);
  const temporary = mkdtempSync(path.join(tmpdir(), 'maven-source-test-'));
  try {
    mkdirSync(path.join(temporary, 'scripts/ci'), { recursive: true });
    for (const relative of Object.keys(manifest.toolchainSourceBlobs)) writeFileSync(path.join(temporary, relative), read(relative));
    writeFileSync(path.join(temporary, 'scripts/ci/setup-maven.mjs'), 'unreviewed');
    assert.throws(() => verifyMavenWorkflowTransition(snapshot(), plan.workflowInventory.targetBlobs, temporary), /source blob drift/);
  } finally { rmSync(temporary, { recursive: true, force: true }); }
});

test('actual-current E2 and R2B evidence retain current workflow bytes and explicit lineage', () => {
  const e2 = generateEvidence(root);
  assert.equal(e2.toolchain.maven.version, MAVEN.version);
  assert.equal(e2.toolchain.maven.runtimeVerified, false);
  assert.equal(e2.githubActions.workflowToolchainTransition.changedWorkflowCount, 5);
  for (const workflow of e2.githubActions.workflows) assert.equal(workflow.blobSha, gitBlob(read(workflow.path)));
  const e4 = currentE4(), current = currentSnapshot(), before = structuredClone(current);
  const proof = verifyWorkflowSupplyChainRemediation(e4, plan, current);
  assert.deepEqual(proof.workflowBlobs, Object.fromEntries(Object.entries(current.workflows).map(([p, v]) => [p, v.blobSha])));
  assert.equal(proof.workflowToolchainTransition.changedWorkflowCount, 5);
  assert.equal(proof.actionUseCount, 43); assert.equal(proof.checkoutCredentialBoundaryCount, 14);
  assert.equal(proof.scannerExecutionCount, 1); assert.equal(proof.releaseBlocked, true);
  assert.deepEqual(current, before);
});

test('source continuation accepts only the exact reviewed new E2 blob, retaining expression and finding identity', () => {
  const relative = 'scripts/security/m6-pr-e-e2-generate-sbom.mjs', change = manifest.sourceContinuations[relative];
  const content = read(relative), source = { content, blobSha: gitBlob(content) };
  const proof = verifyMavenSourceContinuation(relative, source, change.fromBlob);
  assert.equal(proof.toBlob, source.blobSha);
  assert.equal(createHash('sha256').update(content.split('\n')[72]).digest('hex'), change.unchangedSourceLineSha256);
  const altered = content + '\n// otherwise same expression\n';
  assert.throws(() => verifyMavenSourceContinuation(relative, { content: altered, blobSha: gitBlob(altered) }, change.fromBlob), /source blob drift/);
  assert.throws(() => verifyMavenSourceContinuation('unreviewed.mjs', source, change.fromBlob), /source blob drift/);
  assert.throws(() => verifyMavenSourceContinuation(relative, source, '0'.repeat(40)), /source blob drift/);
});

import './server-dependency-baseline.test.mjs';

import './site-dependency-plugin-baseline.test.mjs';

import './release-plugin-baseline.test.mjs';

import './hygiene-java21-workflow-transition.test.mjs';
