import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { isJava21Version, selectJava21 } from '../ci/select-java21.mjs';
import { readHygieneJava21WorkflowTransition, readHygieneM3LogRetentionTransition, priorM3LogRetentionWorkflow, priorJava21Workflow } from '../security/hygiene-java21-workflow-transition.mjs';
import { gitBlob, historicalMavenWorkflow, readMavenWorkflowTransition, verifyMavenWorkflowTransition } from '../security/maven-workflow-transition.mjs';
import { resolveGitHubActionsEvidence } from '../security/m6-pr-e-e2-generate-sbom.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = relative => readFileSync(path.join(root, relative), 'utf8');
const manifest = readHygieneJava21WorkflowTransition();
const retentionManifest = readHygieneM3LogRetentionTransition();
const mavenManifest = readMavenWorkflowTransition();
const plan = JSON.parse(read('docs/m6/m6-pr-e-e3-r2b-workflow-supply-chain-remediation.json'));
const workflowPath = '.github/workflows/approval-platform-validation.yml';
const step = manifest.insertedStep;
const snapshot = () => Object.fromEntries(readdirSync(path.join(root, '.github/workflows')).sort().map(name => {
  const relative = `.github/workflows/${name}`, content = read(relative);
  return [relative, { content, blobSha: gitBlob(content) }];
}));
function temporary(t, prefix = 'java21-') {
  const directory = mkdtempSync(path.join(tmpdir(), prefix));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}
function selection(t) {
  const directory = temporary(t), githubEnv = path.join(directory, 'env'), githubPath = path.join(directory, 'path');
  writeFileSync(githubEnv, 'EXISTING=value\n'); writeFileSync(githubPath, '/existing/bin\n');
  return { JAVA_HOME_21_X64: '/runner/jdk-21', JAVA_HOME: '/runner/jdk-17', PATH: '/runner/jdk-17/bin:/usr/bin',
    GITHUB_ENV: githubEnv, GITHUB_PATH: githubPath };
}
// Unit-level process results only: no executable shims are created or admitted as runtime evidence.
const versionResult = (java = '21.0.12.1', javac = java) => command => command.endsWith('/javac')
  ? { status: 0, stdout: `javac ${javac}\n`, stderr: '' }
  : { status: 0, stdout: '', stderr: `openjdk version "${java}" 2026-08-18\n` };
function unchanged(env) {
  assert.equal(readFileSync(env.GITHUB_ENV, 'utf8'), 'EXISTING=value\n');
  assert.equal(readFileSync(env.GITHUB_PATH, 'utf8'), '/existing/bin\n');
}

for (const version of ['21', '21.0', '21.0.12.1', '21.00.001', '21+1', '21+000', '21.0.12.1+1']) {
  test(`linear Java 21 grammar accepts ${version} and preserves selection`, t => {
    assert.equal(isJava21Version(version), true);
    const env = selection(t);
    assert.equal(selectJava21(env, versionResult(version)).version, version);
  });
}
for (const [name, version] of [
  ['empty token', ''], ['wrong major', '17.0.20.1'], ['lookalike major', '210'],
  ['leading zero major', '021'], ['empty minor', '21.'], ['empty internal minor', '21..1'],
  ['empty build', '21+'], ['two builds', '21+1+2'], ['build with a dot', '21+1.2'],
  ['leading dot', '.21'], ['negative minor', '21.-1'], ['negative build', '21+-1'],
  ['minor sign', '21.+1'], ['early access', '21-ea'], ['vendor suffix', '21.0.1-LTS'],
  ['leading major whitespace', ' 21'], ['minor whitespace', '21. 1'], ['build whitespace', '21+ 1'],
  ['non-ASCII minor', '21.１'], ['non-ASCII build', '21+١'], ['NUL suffix', '21\0'],
]) {
  test(`linear Java 21 grammar rejects ${name} with unchanged error and no publication`, t => {
    assert.equal(isJava21Version(version), false);
    const env = selection(t);
    assert.throws(() => selectJava21(env, versionResult(version)), { message: 'Preinstalled java must be Java 21' });
    unchanged(env);
    assert.throws(() => selectJava21(env, versionResult('21', version)), { message: 'Preinstalled javac must be Java 21' });
    unchanged(env);
  });
}

test('Java version token validation preserves existing javac output whitespace handling', t => {
  assert.equal(isJava21Version('21 '), false);
  assert.equal(isJava21Version('21\n'), false);
  const env = selection(t);
  assert.throws(() => selectJava21(env, versionResult('21 ')), { message: 'Preinstalled java must be Java 21' });
  unchanged(env);
  assert.equal(selectJava21(env, versionResult('21', '21 ')).version, '21');
});

test('linear Java 21 grammar rejects missing and non-string tokens', () => {
  for (const value of [undefined, null, 21, [], {}]) assert.equal(isJava21Version(value), false);
});

test('linear Java 21 grammar handles long numeric tokens and long malformed suffixes', t => {
  const version = `21${'.1234567890'.repeat(20000)}+${'9'.repeat(20000)}`;
  assert.equal(isJava21Version(version), true);
  assert.equal(isJava21Version(version + 'x'), false);
  assert.equal(isJava21Version(version + '+1'), false);
  assert.equal(isJava21Version('21' + '.'.repeat(220000)), false);
  const env = selection(t);
  assert.throws(() => selectJava21(env, versionResult(version + 'x')), { message: 'Preinstalled java must be Java 21' });
  unchanged(env);
});

test('Java selection verifies both exact JDK tools before publishing JAVA_HOME and PATH', t => {
  const env = selection(t), before = structuredClone(env), calls = [];
  const result = selectJava21(env, (command, args, options) => {
    unchanged(env); calls.push(command);
    assert.deepEqual(args, ['-version']); assert.equal(options.env.JAVA_HOME, env.JAVA_HOME_21_X64);
    assert.equal(options.env.PATH, `${env.JAVA_HOME_21_X64}/bin:${env.PATH}`);
    assert.equal(options.timeout, 30000); assert.equal(options.shell, undefined);
    return versionResult()(command);
  });
  assert.deepEqual(calls, ['/runner/jdk-21/bin/java', '/runner/jdk-21/bin/javac']);
  assert.deepEqual(result, { javaHome: '/runner/jdk-21', version: '21.0.12.1' });
  assert.equal(readFileSync(env.GITHUB_ENV, 'utf8'), 'EXISTING=value\nJAVA_HOME=/runner/jdk-21\n');
  assert.equal(readFileSync(env.GITHUB_PATH, 'utf8'), '/existing/bin\n/runner/jdk-21/bin\n');
  assert.deepEqual(env, before);
});
for (const [name, run] of [
  ['default Java 17', versionResult('17.0.20.1')],
  ['JRE without javac', command => command.endsWith('/javac') ? { status: null, error: new Error('ENOENT') } : versionResult()(command)],
  ['Java 21 with Java 17 compiler', versionResult('21.0.12.1', '17.0.20.1')],
  ['different Java 21 patch compiler', versionResult('21.0.12.1', '21.0.12')],
  ['lookalike major', versionResult('210.0.1')],
  ['early access release', versionResult('21-ea')],
  ['nonzero version command', () => ({ status: 1, stderr: 'openjdk version "21"' })],
  ['timeout', () => ({ status: null, error: new Error('ETIMEDOUT') })],
  ['missing version line', () => ({ status: 0, stdout: 'unknown' })],
]) test(`Java selection rejects ${name} without publishing either environment file`, t => {
  const env = selection(t); assert.throws(() => selectJava21(env, run), /Java 21|disagree|unavailable/); unchanged(env);
});
for (const key of ['JAVA_HOME_21_X64', 'GITHUB_ENV', 'GITHUB_PATH']) {
  for (const value of [undefined, '', 'relative/path', '/tmp/injected\nJAVA_HOME=/other', '/tmp/injected\r', '/tmp/injected\0']) {
    test(`Java selection rejects invalid ${key} ${JSON.stringify(value)} before execution`, t => {
      const env = selection(t), invalid = { ...env, [key]: value };
      assert.throws(() => selectJava21(invalid, () => assert.fail('must not execute')), /absolute single-line/); unchanged(env);
    });
  }
}

test('Java selection rejects PATH delimiter injection in the JDK home before execution', t => {
  const env = selection(t);
  assert.throws(() => selectJava21({ ...env, JAVA_HOME_21_X64: '/tmp/trusted:/tmp/other' },
    () => assert.fail('must not execute')), /exactly one PATH entry/);
  unchanged(env);
});

for (const home of ['/runner/alias/../jdk-21', '/runner/./jdk-21']) {
  test(`Java selection rejects dot segments in ${home} before execution`, t => {
    const env = selection(t);
    assert.throws(() => selectJava21({ ...env, JAVA_HOME_21_X64: home },
      () => assert.fail('must not execute')), /dot path segments/);
    unchanged(env);
  });
}

test('Java selection rejects shared environment output files', t => {
  const env = selection(t);
  assert.throws(() => selectJava21({ ...env, GITHUB_PATH: env.GITHUB_ENV }, () => assert.fail('must not execute')), /distinct/);
  unchanged(env);
});

test('one hygiene run step precedes all checks without changing Action uses or historical graph', () => {
  const current = read(workflowPath), before = priorJava21Workflow(workflowPath, current);
  assert.equal(gitBlob(before), manifest.workflows[workflowPath].fromBlob);
  assert.equal(gitBlob(priorM3LogRetentionWorkflow(workflowPath, current)), manifest.workflows[workflowPath].toBlob);
  assert.equal(gitBlob(current), retentionManifest.workflows[workflowPath].toBlob);
  assert.equal(current.split(step).length, 2);
  assert.ok(current.indexOf(step) > current.indexOf('  hygiene:'));
  assert.ok(current.indexOf(step) < current.indexOf(mavenManifest.insertedStep));
  assert.ok(current.indexOf(step) < current.indexOf('      - name: Verify final tracked-tree hygiene'));
  assert.equal(current.slice(current.indexOf('  backend-core:')), before.slice(before.indexOf('  backend-core:')));
  assert.deepEqual([...current.matchAll(/^\s*uses:.+$/gm)].map(m => m[0]), [...before.matchAll(/^\s*uses:.+$/gm)].map(m => m[0]));
  assert.equal(gitBlob(historicalMavenWorkflow(workflowPath, current)), plan.workflowInventory.targetBlobs[workflowPath]);
  assert.equal(createHash('sha256').update(read('docs/m6/maven-toolchain-transition.json')).digest('hex'), manifest.priorMavenTransitionManifestSha256);
  const proof = verifyMavenWorkflowTransition(snapshot(), plan.workflowInventory.targetBlobs);
  assert.equal(proof.toolchainTransition.changedWorkflowCount, 5);
  assert.equal(proof.toolchainTransition.java21WorkflowContinuation.changedWorkflowCount, 1);
  assert.equal(proof.toolchainTransition.java21WorkflowContinuation.javaMajorVersion, 21);
  assert.deepEqual(proof.toolchainTransition.java21WorkflowContinuation.m3LogRetentionContinuation.workflowBlobs,
    { [workflowPath]: { fromBlob: manifest.workflows[workflowPath].toBlob, toBlob: gitBlob(current) } });
  const actions = resolveGitHubActionsEvidence(root);
  assert.equal(actions.workflows.flatMap(workflow => workflow.actions).length, 43);
  assert.deepEqual(actions.workflowToolchainTransition, proof.toolchainTransition);
  assert.equal(actions.acceptedDependencyGraph.contentSha256, JSON.parse(read('docs/operations/release-plugin-evidence/precommit-candidate-E2.json')).githubActions.acceptedDependencyGraph.contentSha256);
});
for (const [name, mutate] of [
  ['missing Java selection', content => content.replace(step, '')],
  ['duplicated Java selection', content => content.replace(step, step.repeat(2))],
  ['altered Java command', content => content.replace('node scripts/ci/select-java21.mjs', 'node scripts/ci/select-java17.mjs')],
  ['moved below checks', content => content.replace(step, '').replace('  backend-core:', step + '\n  backend-core:')],
  ['moved to backend job', content => content.replace(step, '').replace('      - name: Verify Maven core reactor', step + '      - name: Verify Maven core reactor')],
  ['new Action usage', content => content.replace(step, step + '      - uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3 # v4\n')],
  ['unrelated timeout', content => content.replace('timeout-minutes: 45', 'timeout-minutes: 46')],
  ['unrelated permission', content => content.replace('contents: read', 'contents: write')],
]) test(`Java continuation rejects ${name} with recomputed workflow blob`, () => {
  const current = snapshot(); current[workflowPath].content = mutate(current[workflowPath].content);
  current[workflowPath].blobSha = gitBlob(current[workflowPath].content);
  assert.throws(() => verifyMavenWorkflowTransition(current, plan.workflowInventory.targetBlobs), /workflow|step/);
});

test('new workflow requires exact source and rejects missing or altered helper; old workflow rejects new source', t => {
  const directory = temporary(t), files = { ...mavenManifest.toolchainSourceBlobs, ...manifest.toolchainSourceBlobs };
  for (const relative of Object.keys(files)) {
    mkdirSync(path.dirname(path.join(directory, relative)), { recursive: true });
    writeFileSync(path.join(directory, relative), read(relative));
  }
  const current = snapshot(), old = structuredClone(current);
  old[workflowPath].content = priorJava21Workflow(workflowPath, old[workflowPath].content);
  old[workflowPath].blobSha = gitBlob(old[workflowPath].content);
  assert.throws(() => verifyMavenWorkflowTransition(old, plan.workflowInventory.targetBlobs, directory), /mixed Java 21/);
  const helper = path.join(directory, 'scripts/ci/select-java21.mjs');
  writeFileSync(helper, read('scripts/ci/select-java21.mjs') + '\n// altered\n');
  assert.throws(() => verifyMavenWorkflowTransition(current, plan.workflowInventory.targetBlobs, directory), /source blob drift/);
  rmSync(helper);
  assert.throws(() => verifyMavenWorkflowTransition(current, plan.workflowInventory.targetBlobs, directory), /source blob drift/);
  const historical = verifyMavenWorkflowTransition(old, plan.workflowInventory.targetBlobs, directory);
  assert.equal(historical.toolchainTransition.java21WorkflowContinuation, undefined);
  assert.equal(historical.targetBlobs[workflowPath], manifest.workflows[workflowPath].fromBlob);
});

test('all pre-Maven workflows reject the new Java helper while a complete historical source tree remains valid', t => {
  const current = snapshot();
  for (const [relative, value] of Object.entries(current)) {
    value.content = historicalMavenWorkflow(relative, value.content); value.blobSha = gitBlob(value.content);
  }
  assert.throws(() => verifyMavenWorkflowTransition(current, plan.workflowInventory.targetBlobs), /mixed Java 21/);
  const directory = temporary(t);
  assert.deepEqual(verifyMavenWorkflowTransition(current, plan.workflowInventory.targetBlobs, directory),
    { targetBlobs: plan.workflowInventory.targetBlobs, toolchainTransition: null });
});

test('Java continuation manifest bytes fail closed through the production reader', t => {
  const directory = temporary(t);
  for (const relative of ['scripts/security/hygiene-java21-workflow-transition.mjs', 'docs/m6/hygiene-java21-workflow-transition.json']) {
    mkdirSync(path.dirname(path.join(directory, relative)), { recursive: true }); writeFileSync(path.join(directory, relative), read(relative));
  }
  const entry = path.join(directory, 'run.mjs');
  writeFileSync(entry, "import { readHygieneJava21WorkflowTransition } from './scripts/security/hygiene-java21-workflow-transition.mjs'; readHygieneJava21WorkflowTransition();");
  const execute = () => spawnSync(process.execPath, [entry], { encoding: 'utf8', timeout: 5000 });
  assert.equal(execute().status, 0);
  const file = path.join(directory, 'docs/m6/hygiene-java21-workflow-transition.json');
  for (const key of Object.keys(manifest)) {
    writeFileSync(file, JSON.stringify({ ...manifest, [key]: 'unreviewed' }));
    const result = execute(); assert.notEqual(result.status, 0); assert.match(result.stderr, /manifest drift/);
  }
});

test('M3 retention changes only the exact command wrapper and seventh artifact path', () => {
  const current = read(workflowPath), change = retentionManifest.workflows[workflowPath];
  let projected = current;
  assert.equal(change.replacements.length, 2);
  for (const replacement of change.replacements) {
    assert.equal(projected.split(replacement.after).length, 2);
    projected = projected.replace(replacement.after, replacement.before);
  }
  assert.equal(gitBlob(projected), change.fromBlob);
  assert.equal(priorM3LogRetentionWorkflow(workflowPath, current), projected);
  assert.equal(priorM3LogRetentionWorkflow(workflowPath, projected), projected);
  assert.equal(read('docs/m6/hygiene-java21-workflow-transition.json').length > 0, true);
  assert.equal(createHash('sha256').update(read('docs/m6/hygiene-java21-workflow-transition.json')).digest('hex'),
    retentionManifest.priorJava21ManifestSha256);
  assert.equal(current.match(/^\s*if: always\(\)$/gm).length, projected.match(/^\s*if: always\(\)$/gm).length);
  assert.deepEqual([...current.matchAll(/^\s*(?:uses:|timeout-minutes:|contents:).+$/gm)].map(x => x[0]),
    [...projected.matchAll(/^\s*(?:uses:|timeout-minutes:|contents:).+$/gm)].map(x => x[0]));
});
for (const [name, mutate] of [
  ['changed M3 command', s => s.replace('node --test scripts/tests/m3-repository-hygiene.test.mjs', 'node --test scripts/tests/m4-identity-boundary.test.mjs')],
  ['missing M3 capture', s => s.replace('>m3-repository-hygiene.log 2>&1', '>other.log 2>&1')],
  ['missing M3 upload path', s => s.replace('            m3-repository-hygiene.log\n', '')],
  ['duplicate M3 upload path', s => s.replace('            m3-repository-hygiene.log\n', '            m3-repository-hygiene.log\n'.repeat(2))],
  ['lost failure status', s => s.replace('exit "$m3_status"', 'exit 0')],
  ['added retry', s => s.replace('|| m3_status=$?', '|| node --test scripts/tests/m3-repository-hygiene.test.mjs')],
  ['changed upload condition', s => s.replace('if: always()', 'if: success()')],
]) test(`M3 retention rejects ${name} even with a recomputed current blob`, () => {
  const current = snapshot();
  current[workflowPath].content = mutate(current[workflowPath].content);
  current[workflowPath].blobSha = gitBlob(current[workflowPath].content);
  assert.throws(() => verifyMavenWorkflowTransition(current, plan.workflowInventory.targetBlobs), /workflow|step/);
});

const shellQuote = value => `'${value.replaceAll("'", "'\\''")}'`;
function runRetentionShell(t, fixture, prepare = () => {}) {
  const directory = temporary(t, 'm3-capture-');
  const block = retentionManifest.workflows[workflowPath].replacements[0].after;
  assert.match(block, /        shell: bash\n        run: \|\n/);
  let command = block.split('        run: |\n')[1].split('\n').map(line => line.replace(/^          /, '')).join('\n');
  const original = 'node --test scripts/tests/m3-repository-hygiene.test.mjs';
  assert.equal(command.split(original).length, 2);
  command = command.replace(original, `${shellQuote(process.execPath)} -e ${shellQuote(fixture)}`);
  prepare(directory);
  const result = spawnSync('/bin/bash', ['--noprofile', '--norc', '-eo', 'pipefail', '-c', command], {
    cwd: directory, env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8' }, encoding: 'utf8', timeout: 5000, maxBuffer: 1024 * 1024,
  });
  assert.equal(result.error, undefined);
  return { directory, result };
}
for (const status of [0, 7]) test(`M3 capture shell retains both synthetic streams and exact exit ${status}`, t => {
  const { directory, result } = runRetentionShell(t,
    `process.stdout.write('fixture stdout\\n'); process.stderr.write('fixture stderr\\n'); process.exitCode=${status};`);
  assert.equal(result.status, status);
  assert.equal(result.stdout, 'fixture stdout\nfixture stderr\n');
  assert.equal(result.stderr, '');
  assert.equal(readFileSync(path.join(directory, 'm3-repository-hygiene.log'), 'utf8'), result.stdout);
});
test('M3 capture shell fails closed when output redirection is unavailable', t => {
  const { result } = runRetentionShell(t, "process.stdout.write('MUST_NOT_EXECUTE')", directory => {
    mkdirSync(path.join(directory, 'm3-repository-hygiene.log'));
  });
  assert.notEqual(result.status, 0);
  assert.equal(result.stdout.includes('MUST_NOT_EXECUTE'), false);
});
test('M3 capture shell fails closed when its completed log cannot be read', t => {
  const { result } = runRetentionShell(t, "require('node:fs').unlinkSync('m3-repository-hygiene.log')");
  assert.notEqual(result.status, 0);
});

test('M3 retention manifest rejects missing or changed bytes without changing historical readers', t => {
  const directory = temporary(t, 'm3-manifest-');
  for (const relative of ['scripts/security/hygiene-java21-workflow-transition.mjs', 'docs/m6/hygiene-java21-workflow-transition.json',
    'docs/m6/hygiene-m3-log-retention-transition.json']) {
    mkdirSync(path.dirname(path.join(directory, relative)), { recursive: true });
    writeFileSync(path.join(directory, relative), read(relative));
  }
  const entry = path.join(directory, 'run.mjs');
  writeFileSync(entry, "import { readHygieneM3LogRetentionTransition } from './scripts/security/hygiene-java21-workflow-transition.mjs'; readHygieneM3LogRetentionTransition();");
  const execute = () => spawnSync(process.execPath, [entry], { encoding: 'utf8', timeout: 5000 });
  assert.equal(execute().status, 0);
  const file = path.join(directory, 'docs/m6/hygiene-m3-log-retention-transition.json');
  writeFileSync(file, JSON.stringify({ ...retentionManifest, reviewBasisHead: 'unreviewed' }));
  const changed = execute(); assert.notEqual(changed.status, 0); assert.match(changed.stderr, /manifest drift/);
  rmSync(file);
  assert.notEqual(execute().status, 0);
  writeFileSync(entry, "import { readHygieneJava21WorkflowTransition } from './scripts/security/hygiene-java21-workflow-transition.mjs'; readHygieneJava21WorkflowTransition();");
  assert.equal(execute().status, 0);
});
