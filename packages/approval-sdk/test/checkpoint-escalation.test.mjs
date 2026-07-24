import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import {
  ScriptedAggregateCheckpointStore,
  aggregateExportCheckpointResultMap,
  recordAggregateExportCheckpoint,
} from '../dist/aggregate-export-checkpoint.js';
import {
  ScriptedReconciliationEscalationStore,
  evaluateReconciliationEscalation,
  reconciliationEscalationResultMap,
} from '../dist/reconciliation-escalation.js';

const fixture = JSON.parse(await readFile(
  new URL('../../../contracts/sdk/v1/fixtures/checkpoint-escalation-v1.json', import.meta.url),
  'utf8',
));

function checkpointInput(overrides = {}) {
  return {
    ...fixture.aggregateCheckpoint,
    snapshots: fixture.aggregationSnapshots,
    ...overrides,
  };
}

function escalationInput(name, evaluationOrdinal) {
  return {
    contractVersion: '1',
    reconciliationProof: fixture.reconciliationProofs[name],
    evaluationOrdinal,
  };
}

test('shared fixture produces exact checkpoint and escalation evidence', async () => {
  const checkpointStore = new ScriptedAggregateCheckpointStore(4);
  const checkpoint = await recordAggregateExportCheckpoint(
    fixture.aggregateCheckpointPolicy,
    checkpointInput(),
    checkpointStore,
  );
  assert.deepEqual(
    aggregateExportCheckpointResultMap(checkpoint),
    fixture.expectations.checkpointResult,
  );
  const duplicate = await recordAggregateExportCheckpoint(
    fixture.aggregateCheckpointPolicy,
    checkpointInput(),
    checkpointStore,
  );
  assert.deepEqual(
    aggregateExportCheckpointResultMap(duplicate),
    fixture.expectations.duplicateCheckpointResult,
  );

  const stores = {
    unresolved: new ScriptedReconciliationEscalationStore(8),
    conflict: new ScriptedReconciliationEscalationStore(8),
    finalize: new ScriptedReconciliationEscalationStore(8),
  };
  const results = [];
  for (const value of fixture.evaluations) {
    results.push(await evaluateReconciliationEscalation(
      fixture.escalationPolicy,
      escalationInput(value.proof, value.evaluationOrdinal),
      stores[value.store],
    ));
  }
  assert.deepEqual(
    results.map(reconciliationEscalationResultMap),
    fixture.expectations.escalationResults,
  );
});

test('partial export and duplicate snapshot never create checkpoint', async () => {
  const partialStore = new ScriptedAggregateCheckpointStore(4);
  const partial = await recordAggregateExportCheckpoint(
    fixture.aggregateCheckpointPolicy,
    checkpointInput({ exportedSnapshotDigests: [fixture.aggregationSnapshots[0].snapshotDigest] }),
    partialStore,
  );
  assert.deepEqual(
    aggregateExportCheckpointResultMap(partial),
    fixture.expectations.partialCheckpointResult,
  );
  assert.equal(partialStore.size, 0);

  const duplicateStore = new ScriptedAggregateCheckpointStore(4);
  const duplicate = await recordAggregateExportCheckpoint(
    fixture.aggregateCheckpointPolicy,
    checkpointInput({
      snapshots: [fixture.aggregationSnapshots[0], fixture.aggregationSnapshots[0]],
      exportedSnapshotDigests: [
        fixture.aggregationSnapshots[0].snapshotDigest,
        fixture.aggregationSnapshots[0].snapshotDigest,
      ],
    }),
    duplicateStore,
  );
  assert.equal(duplicate.reasonCode, 'aggregate_checkpoint_duplicate_snapshot');
  assert.equal(duplicateStore.size, 0);
});

test('checkpoint chain rejects continuity, ordinal and snapshot reuse violations', async () => {
  const store = new ScriptedAggregateCheckpointStore(4);
  const first = await recordAggregateExportCheckpoint(
    fixture.aggregateCheckpointPolicy,
    checkpointInput(),
    store,
  );
  assert.equal(first.status, 'recorded');

  const wrongPrevious = await recordAggregateExportCheckpoint(
    fixture.aggregateCheckpointPolicy,
    checkpointInput({
      checkpointOrdinal: 50,
      previousCheckpointDigest: 'wrong',
    }),
    store,
  );
  assert.equal(wrongPrevious.reasonCode, 'aggregate_checkpoint_continuity_mismatch');

  const ordinalRegression = await recordAggregateExportCheckpoint(
    fixture.aggregateCheckpointPolicy,
    checkpointInput({
      checkpointOrdinal: 40,
      previousCheckpointDigest: first.checkpoint.checkpointDigest,
    }),
    store,
  );
  assert.equal(ordinalRegression.reasonCode, 'aggregate_checkpoint_ordinal_regression');

  const reuse = await recordAggregateExportCheckpoint(
    fixture.aggregateCheckpointPolicy,
    checkpointInput({
      checkpointOrdinal: 50,
      previousCheckpointDigest: first.checkpoint.checkpointDigest,
    }),
    store,
  );
  assert.equal(reuse.reasonCode, 'aggregate_checkpoint_snapshot_reuse');
  assert.equal(store.size, 1);
});

test('checkpoint capacity and scripted failure append no partial state', async () => {
  const capacity = new ScriptedAggregateCheckpointStore(1);
  const first = await recordAggregateExportCheckpoint(
    { ...fixture.aggregateCheckpointPolicy, maxStoredCheckpoints: 1 },
    checkpointInput(),
    capacity,
  );
  assert.equal(first.status, 'recorded');
  const rejected = await recordAggregateExportCheckpoint(
    { ...fixture.aggregateCheckpointPolicy, maxStoredCheckpoints: 1 },
    checkpointInput({
      streamReference: 'telemetry-export-fixture-2',
      checkpointOrdinal: 50,
      previousCheckpointDigest: null,
    }),
    capacity,
  );
  assert.equal(rejected.status, 'capacity_rejected');
  assert.equal(capacity.size, 1);

  const failing = new ScriptedAggregateCheckpointStore(4, [1]);
  const failed = await recordAggregateExportCheckpoint(
    fixture.aggregateCheckpointPolicy,
    checkpointInput(),
    failing,
  );
  assert.equal(failed.reasonCode, 'aggregate_checkpoint_store_failed');
  assert.equal(failing.size, 0);
});

test('checkpoint version and policy bounds fail closed', async () => {
  const store = new ScriptedAggregateCheckpointStore(4);
  await assert.rejects(() => recordAggregateExportCheckpoint(
    { ...fixture.aggregateCheckpointPolicy, contractVersion: '2' },
    checkpointInput(),
    store,
  ));
  await assert.rejects(() => recordAggregateExportCheckpoint(
    { ...fixture.aggregateCheckpointPolicy, maxSnapshotsPerCheckpoint: 0 },
    checkpointInput(),
    store,
  ));
  const before = await recordAggregateExportCheckpoint(
    fixture.aggregateCheckpointPolicy,
    checkpointInput({ checkpointOrdinal: 10 }),
    store,
  );
  assert.equal(before.reasonCode, 'aggregate_checkpoint_before_snapshot');
  assert.equal(store.size, 0);
});

test('caller ordinal deterministically escalates unresolved evidence', async () => {
  const store = new ScriptedReconciliationEscalationStore(8);
  const observe = await evaluateReconciliationEscalation(
    fixture.escalationPolicy,
    escalationInput('acknowledgementMissing', 33),
    store,
  );
  const investigate = await evaluateReconciliationEscalation(
    fixture.escalationPolicy,
    escalationInput('acknowledgementMissing', 40),
    store,
  );
  const blockStore = new ScriptedReconciliationEscalationStore(8);
  const block = await evaluateReconciliationEscalation(
    fixture.escalationPolicy,
    escalationInput('conflicting', 55),
    blockStore,
  );
  assert.deepEqual(
    [observe.proof.escalationLevel, investigate.proof.escalationLevel, block.proof.escalationLevel],
    ['observe', 'investigate', 'block'],
  );
  assert.equal(observe.proof.requiresManualAction, false);
  assert.equal(investigate.proof.requiresManualAction, true);
  assert.equal(block.proof.requiresManualAction, true);
  assert.equal(observe.finalizationCheckpoint, null);
  assert.equal(investigate.finalizationCheckpoint, null);
  assert.equal(block.finalizationCheckpoint, null);
});

test('only acknowledged confirmation creates finalization checkpoint', async () => {
  const store = new ScriptedReconciliationEscalationStore(8);
  const resolved = await evaluateReconciliationEscalation(
    fixture.escalationPolicy,
    escalationInput('acknowledged', 41),
    store,
  );
  assert.equal(resolved.proof.escalationLevel, 'resolved');
  assert.equal(resolved.proof.safeToFinalize, true);
  assert.equal(resolved.finalizationCheckpoint.checkpointDigest,
    'e108d3017d3cf456882ac3518e3cacd5d5805b9d2c8ebd47dd4eb585cb938156');
  assert.equal(store.finalizationCount, 1);

  const duplicate = await evaluateReconciliationEscalation(
    fixture.escalationPolicy,
    escalationInput('acknowledged', 41),
    store,
  );
  assert.equal(duplicate.status, 'duplicate');
  assert.equal(store.finalizationCount, 1);
});

test('escalation regression, capacity and scripted failure are atomic', async () => {
  const store = new ScriptedReconciliationEscalationStore(8);
  await evaluateReconciliationEscalation(
    fixture.escalationPolicy,
    escalationInput('acknowledgementMissing', 40),
    store,
  );
  const ordinalRegression = await evaluateReconciliationEscalation(
    fixture.escalationPolicy,
    escalationInput('acknowledgementMissing', 35),
    store,
  );
  assert.equal(ordinalRegression.reasonCode, 'reconciliation_escalation_ordinal_regression');
  assert.equal(store.size, 1);

  const capacity = new ScriptedReconciliationEscalationStore(1);
  await evaluateReconciliationEscalation(
    { ...fixture.escalationPolicy, maxStoredProofs: 1 },
    escalationInput('acknowledgementMissing', 33),
    capacity,
  );
  const rejected = await evaluateReconciliationEscalation(
    { ...fixture.escalationPolicy, maxStoredProofs: 1 },
    escalationInput('conflicting', 55),
    capacity,
  );
  assert.equal(rejected.status, 'capacity_rejected');
  assert.equal(capacity.size, 1);

  const failing = new ScriptedReconciliationEscalationStore(8, [1]);
  const failed = await evaluateReconciliationEscalation(
    fixture.escalationPolicy,
    escalationInput('acknowledged', 41),
    failing,
  );
  assert.equal(failed.reasonCode, 'reconciliation_escalation_store_failed');
  assert.equal(failing.size, 0);
  assert.equal(failing.finalizationCount, 0);
});
