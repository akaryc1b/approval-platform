import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
async function text(relative) { return readFile(path.join(root, relative), 'utf8'); }

test('M5-A history-only evidence remains fail closed', async () => {
  const evidence = await text('docs/M5_PROCESS_INSTANCE_MIGRATION_HISTORY_RECONCILIATION_EVIDENCE.md');
  for (const boundary of [
    'M5-A HISTORY-ONLY SLICE: `CAPABILITY_VALIDATION_ONLY`',
    'No runtime and no history is not `NOT_APPLIED`',
    'Never retry migration automatically',
    'must not be converted into an active migration success',
  ]) assert.ok(evidence.includes(boundary), `history evidence omits ${boundary}`);
  const capability = await text('server-modules/approval-engine-flowable/src/test/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationHistoryOnlyReconciliationCapabilityTest.java');
  for (const operation of [
    'MISSING_NO_EVIDENCE', 'HISTORY_ONLY_SOURCE_COMPLETED',
    'HISTORY_ONLY_SOURCE_TERMINATED', 'HISTORY_ONLY_TARGET_COMPLETED',
    'HISTORY_ONLY_TARGET_TERMINATED', 'createHistoricProcessInstanceQuery',
  ]) assert.ok(capability.includes(operation), `history capability omits ${operation}`);
  assert.doesNotMatch(capability, /ACT_[A-Z0-9_]+|createNative|deleteHistoricProcessInstance/);
});

test('M5-A concurrent-command evidence remains bounded public-API evidence', async () => {
  const evidence = await text('docs/M5_PROCESS_INSTANCE_MIGRATION_CONCURRENCY_EVIDENCE.md');
  for (const observation of [
    'Permanent workflow Run `30058147323` / run #471',
    '`COMPLETION_WON_SOURCE_COMPLETED=6`',
    '`MIGRATION_WON_TARGET_ACTIVE_AFTER_COMPLETE_CONFLICT=14`',
    '`ONE_MIGRATION_WON=20`',
    'Concurrent duplicate calls must not reach Flowable',
  ]) assert.ok(evidence.includes(observation), `concurrency evidence omits ${observation}`);
  const capability = await text('server-modules/approval-engine-flowable/src/test/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationConcurrentCommandCapabilityTest.java');
  for (const operation of [
    'CountDownLatch', 'Executors.newFixedThreadPool(2)',
    'tasks.complete(task.getId())', 'migrate(instance.getId())',
  ]) assert.ok(capability.includes(operation), `concurrency capability omits ${operation}`);
  assert.doesNotMatch(capability, /ACT_[A-Z0-9_]+|createNative|executeJob|deleteJob/);
});
