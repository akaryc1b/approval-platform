import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const enabled = process.env.GITHUB_ACTIONS === 'true' || process.env.COMPILER_PLUGIN_COMPATIBILITY === 'true';
test('CI executes inherited Compiler goals, incremental state, processors and observed-realm IO APIs', {
  skip: enabled ? false : 'Set COMPILER_PLUGIN_COMPATIBILITY=true with the explicit reviewed offline repository for local execution.',
  timeout: 690000,
}, () => {
  const repository = process.env.M6_PR_E_E2_MAVEN_REPOSITORY;
  const evidence = process.env.COMPILER_PLUGIN_EVIDENCE_DIRECTORY;
  const result = spawnSync('python3', ['-B', path.join(root, 'scripts/tests/fixtures/run_compiler_live_compatibility.py'), '--execute', `--source=${root}`,
    ...(repository ? [`--repository=${repository}`] : []), ...(evidence ? [`--evidence-directory=${evidence}`] : [])],
  { cwd: root, encoding: 'utf8', timeout: 680000, maxBuffer: 4 * 1024 * 1024 });
  if (result.stdout) console.log(result.stdout.trim());
  if (result.stderr) console.error(result.stderr.trim());
  assert.equal(result.status, 0, `Compiler execution failed: ${result.error?.message ?? result.signal ?? result.status}`);
  const lines = result.stdout.split('\n').filter(line => line.startsWith('COMPILER_LIVE_RESULT '));
  assert.equal(lines.length, 1); const receipt = JSON.parse(lines[0].slice('COMPILER_LIVE_RESULT '.length));
  assert.equal(receipt.status, 'PASSED'); assert.equal(receipt.modelSourceCount, 28);
  assert.equal(receipt.modelCount, 26); assert.match(receipt.effectiveModelSha256, /^[0-9a-f]{64}$/);
  assert.equal(receipt.scannerEvidence, false); assert.equal(receipt.vulnerableApiReachabilityClaim, false);
  assert.deepEqual(receipt.coverageGaps, []);
  for (const label of ['initial-compile', 'initial-test-compile', 'unchanged-compile', 'unchanged-test-compile',
    'changed-main-compile', 'changed-main-test-compile', 'changed-test-compile', 'added-source-compile', 'removed-source-compile',
    'invalid-source', 'invalid-test-source', 'annotation-compile', 'annotation-test-compile']) {
    assert.equal(receipt.cases[label].status, 'PASSED', label);
    assert.equal(Object.keys(receipt.cases[label].realmArtifacts).length, 12, label);
  }
  assert.equal(receipt.apiProbe.status, 'PASSED'); assert.equal(receipt.apiProbe.goalReachabilityClaim, false);
  assert.equal(receipt.apiProbe.assertions, 63);
  assert.equal(receipt.apiProbe.observedRealmLogSha256, receipt.cases['initial-compile'].logSha256);
});
