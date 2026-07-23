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
const subprocessEvidencePath = path.join(
  root,
  'docs/M5_PROCESS_INSTANCE_MIGRATION_SUBPROCESS_EVIDENCE.md',
);
const embeddedCapabilityPath = path.join(
  root,
  'server-modules/approval-engine-flowable/src/test/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationEmbeddedSubprocessCapabilityTest.java',
);
const calledChildCapabilityPath = path.join(
  root,
  'server-modules/approval-engine-flowable/src/test/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationCalledChildCapabilityTest.java',
);
const callExitCapabilityPath = path.join(
  root,
  'server-modules/approval-engine-flowable/src/test/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationCallActivityExitCapabilityTest.java',
);
const timerJobEvidencePath = path.join(
  root,
  'docs/M5_PROCESS_INSTANCE_MIGRATION_TIMER_JOB_EVIDENCE.md',
);
const timerCatchCapabilityPath = path.join(
  root,
  'server-modules/approval-engine-flowable/src/test/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationTimerCatchCapabilityTest.java',
);
const boundaryTimerCapabilityPath = path.join(
  root,
  'server-modules/approval-engine-flowable/src/test/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationBoundaryTimerCapabilityTest.java',
);
const pendingAsyncCapabilityPath = path.join(
  root,
  'server-modules/approval-engine-flowable/src/test/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationPendingAsyncJobCapabilityTest.java',
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

test('subprocess evidence remains isolated M5-A capability validation', async () => {
  const evidence = await text(subprocessEvidencePath);

  assert.match(evidence, /M5-A SUBPROCESS SLICE: `CAPABILITY_VALIDATION_ONLY`/);
  assert.match(evidence, /Overall conclusion remains: `SUPPORTED_WITH_LIMITATIONS`/);
  assert.match(evidence, /Current M5-A decision remains `SUPPORTED_WITH_LIMITATIONS`/);
  assert.match(evidence, /adds no `V33`/);

  for (const scenario of [7, 8]) {
    assert.match(
      evidence,
      new RegExp('\\| ' + scenario + ' \\|[^\\n]*\\| `SUPPORTED_WITH_LIMITATIONS` \\|'),
      `subprocess evidence does not classify scenario ${scenario}`,
    );
  }

  for (const boundary of [
    'parent-to-child Call Activity migration: `UNSUPPORTED`',
    'automatic recursive migration of a parent and all children: `UNSUPPORTED`',
    'does not authorize M5-B',
    'No code may query or modify Flowable `ACT_*` tables',
  ]) {
    assert.ok(evidence.includes(boundary), `subprocess evidence omits ${boundary}`);
  }
});

test('subprocess capability uses public scope and call-tree APIs only', async () => {
  const embedded = await text(embeddedCapabilityPath);
  const child = await text(calledChildCapabilityPath);
  const exit = await text(callExitCapabilityPath);

  for (const operation of [
    'ActivityMigrationMapping.createMappingFor("rootReview", "nestedReview")',
    'ActivityMigrationMapping.createMappingFor("nestedReview", "rootReview")',
    '<subProcess id="reviewScope">',
    'getActiveActivityIds',
  ]) {
    assert.ok(embedded.includes(operation), `embedded capability omits ${operation}`);
  }

  for (const operation of [
    'superProcessInstanceId(parentInstance.getId())',
    'migrate(childInstance.getId())',
    '<callActivity id="callChild"',
  ]) {
    assert.ok(child.includes(operation), `called-child capability omits ${operation}`);
  }

  for (const operation of [
    '.inParentProcessOfCallActivityId("callChild")',
    'superProcessInstanceId(parent.getId())',
    'processInstanceId(child.getId())',
  ]) {
    assert.ok(exit.includes(operation), `call-exit capability omits ${operation}`);
  }

  for (const capability of [embedded, child, exit]) {
    assert.doesNotMatch(capability, /ACT_[A-Z0-9_]+/);
    assert.doesNotMatch(
      capability,
      /org\.flowable\.(?:common\.)?engine\.impl|org\.flowable\.engine\.impl/,
    );
  }
});

test('timer and job evidence remains isolated M5-A capability validation', async () => {
  const evidence = await text(timerJobEvidencePath);

  assert.match(evidence, /M5-A TIMER\/JOB SLICE: `CAPABILITY_VALIDATION_ONLY`/);
  assert.match(evidence, /Overall conclusion remains: `SUPPORTED_WITH_LIMITATIONS`/);
  assert.match(evidence, /Current M5-A decision remains `SUPPORTED_WITH_LIMITATIONS`/);
  assert.match(evidence, /adds no `V33`/);

  for (const scenario of [9, 10, 28]) {
    assert.match(
      evidence,
      new RegExp('\\| ' + scenario + ' \\|[^\\n]*\\| `SUPPORTED_WITH_LIMITATIONS` \\|'),
      `timer/job evidence does not classify scenario ${scenario}`,
    );
  }
  for (const scenario of [11, 12]) {
    assert.match(
      evidence,
      new RegExp('\\| ' + scenario + ' \\|[^\\n]*\\| `UNSUPPORTED` \\|'),
      `timer/job evidence does not prohibit scenario ${scenario}`,
    );
  }
  assert.match(evidence, /\| 27 \|[^\n]*\| `UNKNOWN_REQUIRES_MORE_EVIDENCE` \|/);

  for (const boundary of [
    'does not authorize M5-B',
    'Timeout remains `UNKNOWN`',
    'must reject every executable async or pending-job instance',
    'must not be the only equality condition',
  ]) {
    assert.ok(evidence.includes(boundary), `timer/job evidence omits ${boundary}`);
  }
});

test('timer and pending-job capability uses public service evidence only', async () => {
  const timerCatch = await text(timerCatchCapabilityPath);
  const boundaryTimer = await text(boundaryTimerCapabilityPath);
  const pendingAsync = await text(pendingAsyncCapabilityPath);

  for (const operation of [
    'ManagementService',
    'createTimerJobQuery',
    'getProcessDefinitionId',
    'getElementId',
    'getDuedate',
  ]) {
    assert.ok(timerCatch.includes(operation), `timer catch capability omits ${operation}`);
  }
  for (const operation of [
    'reviewBoundary',
    'createTimerJobQuery',
    'addsBoundaryTimerToAnExistingUserTaskWaitState',
    'removesBoundaryTimerWhileKeepingTheUserTaskWaitState',
  ]) {
    assert.ok(boundaryTimer.includes(operation), `boundary timer capability omits ${operation}`);
  }
  for (const operation of [
    'createJobQuery',
    'flowable:async="true"',
    'detectsPendingAsyncJobDefinitionMismatchAfterMigration',
    'getActiveActivityIds',
  ]) {
    assert.ok(pendingAsync.includes(operation), `pending async capability omits ${operation}`);
  }

  for (const capability of [timerCatch, boundaryTimer, pendingAsync]) {
    assert.doesNotMatch(capability, /ACT_[A-Z0-9_]+/);
    assert.doesNotMatch(
      capability,
      /org\.flowable\.(?:common\.)?engine\.impl|org\.flowable\.engine\.impl/,
    );
    assert.doesNotMatch(capability, /executeJob|deleteJob|setJobRetries|moveJobToDeadLetter/);
  }
});
