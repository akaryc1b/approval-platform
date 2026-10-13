import assert from 'node:assert/strict';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const runner = path.join(root, 'scripts/tests/fixtures/run_boot_http_model_compatibility.py');
const enabled = process.env.GITHUB_ACTIONS === 'true' || process.env.BOOT_UTILITIES_MODEL_COMPATIBILITY === 'true';

test('Boot utility actual model runner defaults to a bounded nonexecuting plan', () => {
  const result = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', runner, '--profile=utilities'], {
    encoding: 'utf8', timeout: 20000, maxBuffer: 1024 * 1024,
    env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' },
  });
  assert.equal(result.status, 0, result.stderr);
  const plan = JSON.parse(result.stdout);
  assert.equal(plan.dependencyProfile, 'utilities'); assert.equal(plan.execution, 'NOT_RUN'); assert.equal(plan.actualModelCommands, 2);
  assert.equal(plan.modelCountPerCommand, 26); assert.equal(plan.lifecycle, false);
  assert.equal(plan.goal, 'org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom');
  const refused = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', runner, '--profile=unreviewed'], {
    encoding: 'utf8', timeout: 20000, maxBuffer: 1024 * 1024,
    env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' },
  });
  assert.equal(refused.status, 2);
  assert.match(refused.stderr, /invalid choice/);
});

test('CI compares paired actual Boot utility and retained HTTP models across all26 reactor projects', {
  skip: enabled ? false : 'Local actual model execution needs explicit reviewed offline cache and opt-in.',
  timeout: 720000,
}, () => {
  const repository = process.env.M6_PR_E_E2_MAVEN_REPOSITORY;
  const evidence = process.env.BOOT_UTILITIES_MODEL_EVIDENCE_DIRECTORY;
  const result = spawnSync('/usr/bin/timeout', ['--signal=TERM', '--kill-after=15s', '675s',
    '/usr/bin/python3', '-I', '-S', '-B', runner, '--execute', '--profile=utilities', `--source=${root}`,
    ...(repository ? [`--repository=${repository}`] : []), ...(evidence ? [`--evidence-directory=${evidence}`] : [])], {
    cwd: root, encoding: 'utf8', timeout: 710000, maxBuffer: 8 * 1024 * 1024,
    env: { ...process.env, BOOT_UTILITIES_MODEL_COMPATIBILITY: 'true' },
  });
  if (result.stdout) console.log(result.stdout.trim());
  if (result.stderr) console.error(result.stderr.trim());
  assert.equal(result.status, 0, `Boot actual model gate failed: ${result.error?.message ?? result.signal ?? result.status}`);
  const lines = result.stdout.split('\n').filter(line => line.startsWith('BOOT_UTILITIES_MODELS_RESULT '));
  assert.equal(lines.length, 1);
  const receipt = JSON.parse(lines[0].slice('BOOT_UTILITIES_MODELS_RESULT '.length));
  assert.equal(receipt.schemaVersion, 'BOOT_UTILITIES_ACTUAL_MODELS_V1');
  assert.equal(receipt.dependencyProfile, 'utilities');
  assert.equal(receipt.historicalUtilitiesSourceProjection, null);
  assert.equal(receipt.status, 'PASSED'); assert.equal(receipt.actualDefaultProfileModelEvidence, true);
  assert.equal(receipt.modelCount, 26); assert.equal(receipt.managedBootModelCount, 26); assert.equal(receipt.activeBootModelCount, 2);
  assert.equal(receipt.sourcePomCount, 28); assert.equal(receipt.allOtherModelFieldsUnchanged, true);
  assert.equal(receipt.sourceInputsStable, true); assert.equal(receipt.executionInputsStable, true);
  assert.equal(receipt.runtimeInputsStable, true); assert.equal(receipt.cacheExistingJarPomBytesStable, true);
  assert.equal(receipt.cleanupGraceSeconds, 15); assert.equal(receipt.cacheSnapshots.length, 3);
  assert.ok(Object.keys(receipt.runtimeHashes).length > 100);
  assert.ok(receipt.cacheInputsAfter.jarPomCount > 100);
  assert.equal(receipt.bootSourceProjection.schemaVersion, 'BOOT_UTILITIES_RETAINED_SOURCE_VIEW_V1');
  assert.equal(receipt.bootSourceProjection.suppliedRootPomSha256, '2f22ec8cae77cdfb519b81a8e48417fc75632416bcf28989f589f33d39261579');
  assert.equal(receipt.bootSourceProjection.retainedRootPomSha256, 'dd3d2a854fd32d53efc2ccbe0efc1c217af46a7824de89c4c30f5bf29249e3c1');
  assert.equal(receipt.modelChanges.length, 26);
  assert.equal(new Set(receipt.modelChanges.map(row => row.artifactId)).size, 26);
  const active = new Set(['approval-server', 'approval-generic-spring-host-example']);
  for (const row of receipt.modelChanges) {
    assert.equal(row.managedDeclarations, 1);
    assert.equal(row.managedDependencyAdditions, 2);
    assert.equal(row.activeDeclarations, active.has(row.artifactId) ? 1 : 0);
    assert.equal(row.activeDependencyAdditions, active.has(row.artifactId) ? 2 : 0);
    assert.equal(row.managedScope, active.has(row.artifactId) ? 'compile' : 'omitted');
  }
  assert.deepEqual(receipt.commands.map(row => row.case), [
    'boot-model-maven-version', 'boot-baseline-effective-models', 'boot-candidate-effective-models']);
  for (const command of receipt.commands) {
    assert.equal(command.exitStatus, 0); assert.equal(command.captureComplete, true); assert.equal(command.processGroupCleaned, true);
  }
  for (const key of ['effectiveModelSha256', 'baselineEffectiveModelSha256']) assert.match(receipt[key], /^[0-9a-f]{64}$/);
  assert.equal(receipt.scannerEvidence, false); assert.equal(receipt.transportCompatibilityClaimed, false);
});
