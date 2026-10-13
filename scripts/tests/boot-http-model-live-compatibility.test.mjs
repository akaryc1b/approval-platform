import assert from 'node:assert/strict';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const runner = path.join(root, 'scripts/tests/fixtures/run_boot_http_model_compatibility.py');
const enabled = process.env.GITHUB_ACTIONS === 'true' || process.env.BOOT_HTTP_MODEL_COMPATIBILITY === 'true';

test('Boot actual model runner defaults to a bounded nonexecuting plan', () => {
  const result = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', runner], {
    encoding: 'utf8', timeout: 20000, maxBuffer: 1024 * 1024,
    env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' },
  });
  assert.equal(result.status, 0, result.stderr);
  const plan = JSON.parse(result.stdout);
  assert.equal(plan.execution, 'NOT_RUN'); assert.equal(plan.actualModelCommands, 2);
  assert.equal(plan.modelCountPerCommand, 26); assert.equal(plan.lifecycle, false);
  assert.equal(plan.goal, 'org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom');
});

test('CI compares paired actual current-Boot and retained-Guava models across all26 reactor projects', {
  skip: enabled ? false : 'Local actual model execution needs explicit reviewed offline cache and opt-in.',
  timeout: 720000,
}, () => {
  const repository = process.env.M6_PR_E_E2_MAVEN_REPOSITORY;
  const evidence = process.env.BOOT_HTTP_MODEL_EVIDENCE_DIRECTORY;
  const result = spawnSync('/usr/bin/timeout', ['--signal=TERM', '--kill-after=15s', '675s',
    '/usr/bin/python3', '-I', '-S', '-B', runner, '--execute', `--source=${root}`,
    ...(repository ? [`--repository=${repository}`] : []), ...(evidence ? [`--evidence-directory=${evidence}`] : [])], {
    cwd: root, encoding: 'utf8', timeout: 710000, maxBuffer: 8 * 1024 * 1024,
    env: { ...process.env, BOOT_HTTP_MODEL_COMPATIBILITY: 'true' },
  });
  if (result.stdout) console.log(result.stdout.trim());
  if (result.stderr) console.error(result.stderr.trim());
  assert.equal(result.status, 0, `Boot actual model gate failed: ${result.error?.message ?? result.signal ?? result.status}`);
  const lines = result.stdout.split('\n').filter(line => line.startsWith('BOOT_HTTP_MODELS_RESULT '));
  assert.equal(lines.length, 1);
  const receipt = JSON.parse(lines[0].slice('BOOT_HTTP_MODELS_RESULT '.length));
  assert.equal(receipt.status, 'PASSED'); assert.equal(receipt.actualDefaultProfileModelEvidence, true);
  assert.equal(receipt.modelCount, 26); assert.equal(receipt.managedBootModelCount, 26); assert.equal(receipt.activeBootModelCount, 2);
  assert.equal(receipt.sourcePomCount, 28); assert.equal(receipt.allOtherModelFieldsUnchanged, true);
  assert.equal(receipt.sourceInputsStable, true); assert.equal(receipt.executionInputsStable, true);
  assert.equal(receipt.runtimeInputsStable, true); assert.equal(receipt.cacheExistingJarPomBytesStable, true);
  assert.equal(receipt.cleanupGraceSeconds, 15); assert.equal(receipt.cacheSnapshots.length, 3);
  assert.ok(Object.keys(receipt.runtimeHashes).length > 100);
  assert.ok(receipt.cacheInputsAfter.jarPomCount > 100);
  assert.equal(receipt.bootSourceProjection.suppliedRootPomSha256, 'dd3d2a854fd32d53efc2ccbe0efc1c217af46a7824de89c4c30f5bf29249e3c1');
  assert.equal(receipt.bootSourceProjection.retainedRootPomSha256, 'd95d582e6ccfc91c01780717c83054cd9b52aeffa43c934fbf568f550288cdd5');
  assert.deepEqual(receipt.commands.map(row => row.case), [
    'boot-model-maven-version', 'boot-baseline-effective-models', 'boot-candidate-effective-models']);
  for (const command of receipt.commands) {
    assert.equal(command.exitStatus, 0); assert.equal(command.captureComplete, true); assert.equal(command.processGroupCleaned, true);
  }
  for (const key of ['effectiveModelSha256', 'baselineEffectiveModelSha256']) assert.match(receipt[key], /^[0-9a-f]{64}$/);
  assert.equal(receipt.scannerEvidence, false); assert.equal(receipt.transportCompatibilityClaimed, false);
});
