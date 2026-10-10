import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import './clean-plugin-live-harness.test.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const runner = path.join(root, 'scripts/tests/fixtures/run_clean_live_compatibility.py');
const enabled = process.env.GITHUB_ACTIONS === 'true' || process.env.CLEAN_PLUGIN_COMPATIBILITY === 'true';
const skip = enabled ? false : 'Live Clean compatibility is disabled locally; set CLEAN_PLUGIN_COMPATIBILITY=true with the reviewed offline repository to enable it.';

function live(stage, timeout) {
  const repository = process.env.M6_PR_E_E2_MAVEN_REPOSITORY;
  // Local opt-in is always offline; the helper also enforces this independently.
  // Normal GITHUB_ACTIONS=true enables the tests with official Maven resolution.
  const result = spawnSync('python3', ['-B', runner, '--execute', `--stage=${stage}`, `--source=${root}`,
    ...(repository ? [`--repository=${repository}`] : []),
    ...(process.env.GITHUB_ACTIONS === 'true' ? [] : ['--offline'])], {
    cwd: root, encoding: 'utf8', timeout, maxBuffer: 4 * 1024 * 1024,
  });
  // The helper emits bounded, constructed metadata only. Raw Maven output stays
  // in its fresh sandbox on failure, with real statuses and hashes in CI logs.
  if (result.stdout) console.log(result.stdout.trim());
  if (result.stderr) console.error(result.stderr.trim());
  assert.equal(result.status, 0, `Clean ${stage} failed: status=${result.status}; signal=${result.signal}; ${result.error?.message ?? ''}`);
  const entries = result.stdout.split('\n').filter(line => line.startsWith('CLEAN_LIVE_RESULT '));
  assert.equal(entries.length, 1, 'exactly one current live result is required');
  const receipt = JSON.parse(entries[0].slice('CLEAN_LIVE_RESULT '.length));
  assert.equal(receipt.status, 'PASSED', 'required coverage cannot be skipped/incomplete in an enabled test');
  assert.equal(receipt.stage, stage);
  assert.equal(receipt.scannerEvidence, false);
  assert.equal(receipt.vulnerableApiReachabilityClaim, false);
  return receipt;
}

test('CI verifies actual inherited Clean and retained pins across all 26 current effective models', {
  skip, timeout: 390000,
}, () => {
  const receipt = live('models', 380000);
  assert.equal(receipt.modelCount, 26);
  assert.equal(Object.keys(receipt.sourcePomHashes).length, 28);
  assert.match(receipt.effectiveModelSha256, /^[0-9a-f]{64}$/);
});

test('CI runs 14 sandboxed Clean goals, natural exact realm origins and the bound 23-assertion Shared Utils IO probe', {
  skip, timeout: 690000,
}, () => {
  const receipt = live('fixtures', 680000);
  assert.equal(Object.keys(receipt.cases).length, 14);
  assert.deepEqual(receipt.coverageGaps, []);
  for (const [name, item] of Object.entries(receipt.cases)) {
    assert.equal(item.status, 'PASSED', name);
    assert.equal(item.filesystemAssertions, 'PASSED', name);
    assert.equal(item.realmCoordinates.length, 3, name);
  }
  assert.equal(receipt.apiProbe.status, 'PASSED');
  assert.equal(receipt.apiProbe.assertions, 23);
  assert.equal(receipt.apiProbe.goalReachabilityClaim, false);
  assert.equal(receipt.apiProbe.defaultGoalLogSha256, receipt.cases.defaults.logSha256);
});
