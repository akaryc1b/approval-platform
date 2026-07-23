import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
const evidencePath = path.join(
  root,
  'docs/M5_PROCESS_INSTANCE_MIGRATION_HISTORY_RECONCILIATION_EVIDENCE.md',
);
const capabilityPath = path.join(
  root,
  'server-modules/approval-engine-flowable/src/test/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationHistoryOnlyReconciliationCapabilityTest.java',
);

async function text(file) {
  return readFile(file, 'utf8');
}

test('history-only evidence remains isolated M5-A capability validation', async () => {
  const evidence = await text(evidencePath);

  assert.match(evidence, /M5-A HISTORY-ONLY SLICE: `CAPABILITY_VALIDATION_ONLY`/);
  assert.match(evidence, /Overall conclusion remains: `SUPPORTED_WITH_LIMITATIONS`/);
  assert.match(evidence, /Current M5-A decision remains `SUPPORTED_WITH_LIMITATIONS`/);
  assert.match(evidence, /adds no `V33`/);

  for (const boundary of [
    'No runtime and no history is not `NOT_APPLIED`',
    'Never retry migration automatically',
    'must not be converted into an active migration success',
    'does not claim a true migration-versus-complete',
    'does not authorize M5-B',
  ]) {
    assert.ok(evidence.includes(boundary), `history-only evidence omits ${boundary}`);
  }
});

test('history-only capability uses public runtime and history evidence only', async () => {
  const capability = await text(capabilityPath);

  for (const operation of [
    'HistoryService',
    'createHistoricProcessInstanceQuery',
    'getProcessDefinitionId',
    'getEndTime',
    'getDeleteReason',
    'MISSING_NO_EVIDENCE',
    'HISTORY_ONLY_SOURCE_COMPLETED',
    'HISTORY_ONLY_SOURCE_TERMINATED',
    'HISTORY_ONLY_TARGET_COMPLETED',
    'HISTORY_ONLY_TARGET_TERMINATED',
    'runtime.deleteProcessInstance',
  ]) {
    assert.ok(capability.includes(operation), `history-only capability omits ${operation}`);
  }

  assert.doesNotMatch(capability, /ACT_[A-Z0-9_]+/);
  assert.doesNotMatch(
    capability,
    /org\.flowable\.(?:common\.)?engine\.impl|org\.flowable\.engine\.impl/,
  );
  assert.doesNotMatch(
    capability,
    /deleteHistoricProcessInstance|createNativeHistoric|executeJob|deleteJob|setJobRetries/,
  );
});
