import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
const evidencePath = path.join(
  root,
  'docs/M5_PROCESS_INSTANCE_MIGRATION_PARALLEL_EVIDENCE.md',
);
const mappingCapabilityPath = path.join(
  root,
  'server-modules/approval-engine-flowable/src/test/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationParallelMappingCapabilityTest.java',
);
const completedBranchCapabilityPath = path.join(
  root,
  'server-modules/approval-engine-flowable/src/test/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationCompletedBranchCapabilityTest.java',
);
const staleValidationCapabilityPath = path.join(
  root,
  'server-modules/approval-engine-flowable/src/test/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationStaleValidationCapabilityTest.java',
);

async function text(file) {
  return readFile(file, 'utf8');
}

test('parallel evidence remains M5-A capability validation only', async () => {
  const evidence = await text(evidencePath);

  assert.match(evidence, /M5-A PARALLEL SLICE: `CAPABILITY_VALIDATION_ONLY`/);
  assert.match(evidence, /Overall conclusion remains: `SUPPORTED_WITH_LIMITATIONS`/);
  assert.match(evidence, /Current M5-A decision remains `SUPPORTED_WITH_LIMITATIONS`/);
  assert.match(evidence, /adds no `V33`/);

  for (const scenario of [3, 4, 5, 23, 24]) {
    assert.match(
      evidence,
      new RegExp('\\| ' + scenario + ' \\|[^\\n]*\\| `SUPPORTED_WITH_LIMITATIONS` \\|'),
      `parallel evidence does not classify scenario ${scenario}`,
    );
  }
  assert.match(evidence, /\| 6 \|[^\n]*\| `UNSUPPORTED` \|/);
  assert.match(evidence, /\| 25 \|[^\n]*\| `UNKNOWN_REQUIRES_MORE_EVIDENCE` \|/);

  for (const boundary of [
    'does not authorize M5-B',
    'Multi-instance migration remains prohibited',
    'A stale validation result must never authorize continued execution',
    'Task IDs and execution IDs cannot be treated as stable',
  ]) {
    assert.ok(evidence.includes(boundary), `parallel evidence omits ${boundary}`);
  }
});

test('parallel capability uses public mappings and public runtime evidence only', async () => {
  const mappingCapability = await text(mappingCapabilityPath);
  const completedBranchCapability = await text(completedBranchCapabilityPath);
  const staleValidationCapability = await text(staleValidationCapabilityPath);

  for (const operation of [
    'ActivityMigrationMapping.createMappingFor(',
    'List.of("leftReview", "rightReview")',
    'getActiveActivityIds',
    'parallelGateway',
  ]) {
    assert.ok(mappingCapability.includes(operation), `parallel mapping capability omits ${operation}`);
  }
  for (const operation of [
    'getActiveActivityIds',
    'createHistoricTaskInstanceQuery',
    'parallelGateway',
  ]) {
    assert.ok(completedBranchCapability.includes(operation), `completed branch capability omits ${operation}`);
  }
  for (const operation of [
    'validateMigration',
    'assertThrows(FlowableException.class',
  ]) {
    assert.ok(staleValidationCapability.includes(operation), `stale validation capability omits ${operation}`);
  }

  assert.match(mappingCapability, /"singleReview",\s*List\.of\("leftReview", "rightReview"\)/s);
  assert.match(mappingCapability, /List\.of\("leftReview", "rightReview"\),\s*"mergedReview"/s);
  for (const capability of [mappingCapability, completedBranchCapability, staleValidationCapability]) {
    assert.doesNotMatch(capability, /ACT_[A-Z0-9_]+/);
    assert.doesNotMatch(
      capability,
      /org\.flowable\.(?:common\.)?engine\.impl|org\.flowable\.engine\.impl/,
    );
  }
});
