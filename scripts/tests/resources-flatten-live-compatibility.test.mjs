import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { resultMarker, portableMarker, liveInvocation, retainReceipts, validateReceipt, validatePortable } from './fixtures/resources-flatten-live-support.mjs';

const ci = process.env.GITHUB_ACTIONS === 'true';
const optedIn = process.env.RESOURCES_FLATTEN_COMPATIBILITY === 'true';
test('paired Resources / Flatten private Utils goals, complete linkage, and exact-source SDK flatten plus clean', {
  skip: !ci && !optedIn ? 'explicit Resources/Flatten compatibility opt-in required' : false,
}, () => {
  const sealPath = process.env.RESOURCES_FLATTEN_COMPATIBILITY_SEAL;
  if (!ci) assert.ok(sealPath, 'local offline execution requires an explicitly prepared fresh seal');
  const seal = !ci ? JSON.parse(readFileSync(sealPath, 'utf8')) : null;
  const invocation = liveInvocation(process.env, fileURLToPath(new URL('./fixtures', import.meta.url)));
  const started = Date.now();
  const run = spawnSync(invocation.executable, invocation.arguments, invocation.options);
  // Full failure evidence reaches the existing CI log before exit/status/semantic assertions.
  const records = retainReceipts(run.stdout || '', ci, console.log, { minimumMtimeMs: started - 1000 });
  assert.equal(run.status, 0, `${run.error?.message || ''}\n${run.stdout || ''}\n${run.stderr || ''}`);
  const pins = JSON.parse(readFileSync(new URL('./fixtures/resources-flatten-artifact-pins.json', import.meta.url), 'utf8'));
  assert.ok(records[resultMarker], 'complete current invocation goal result required');
  const goal = records[resultMarker];
  assert.equal(records.pointers[resultMarker].path, `${goal.config.output}/result.json`);
  if (seal) {
    assert.equal(seal.schemaVersion, 'RESOURCES_FLATTEN_COMPAT_V1');
    assert.deepEqual(goal.config, seal.config); assert.deepEqual(goal.source, seal.source);
    assert.deepEqual(goal.harnessHashes, seal.harnessHashes); assert.deepEqual(goal.runtimeHashes, seal.runtimeHashes);
    assert.deepEqual(goal.cacheHashes, seal.cacheHashes); assert.deepEqual(goal.semanticInventory, seal.inventory);
  }
  validateReceipt(goal, pins);
  if (ci) {
    assert.ok(records[portableMarker], 'complete current invocation preparation result required');
    validatePortable(records[portableMarker], pins, goal, records.pointers[resultMarker]);
  }
});
