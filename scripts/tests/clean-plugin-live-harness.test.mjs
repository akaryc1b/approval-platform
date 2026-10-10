import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const fixture = name => path.join(root, 'scripts/tests/fixtures', name);
// Only Python syntax/contract checks and default plan rendering. No Maven/Java,
// network, scanner or Clean/deletion tests are invoked by this named module.
test('Clean live harness synthetic contracts forbid unsafe commands and preserve exact XML and origin binding', () => {
  const result = spawnSync('python3', ['-B', fixture('clean_live_support_test.py')], {
    cwd: root, encoding: 'utf8', timeout: 30000, maxBuffer: 2 * 1024 * 1024,
  });
  assert.equal(result.status, 0, `${result.error?.message ?? ''}\n${result.stdout}\n${result.stderr}`);
  console.log(result.stderr.trim());
});

test('Clean live harness defaults to a plan without creating a sandbox or spawning tools', () => {
  const result = spawnSync('python3', ['-B', fixture('run_clean_live_compatibility.py')], {
    cwd: root, encoding: 'utf8', timeout: 30000, maxBuffer: 1024 * 1024,
  });
  assert.equal(result.status, 0, result.stderr);
  const plan = JSON.parse(result.stdout);
  assert.equal(plan.execution, 'NOT_RUN');
  assert.equal(plan.cases.length, 14);
  assert.equal(plan.models, 26);
  assert.equal(plan.apiAssertions, 23);
  assert.equal(plan.localRequiresOfflinePinnedRepository, true);
});
