import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { performance } from 'node:perf_hooks';

import { startManagedNode, waitForMarker } from '../pc-h5-runtime/processes.mjs';
import { readSandboxStatus, waitForState } from '../purchase-payment-e2e/evidence.mjs';
import { eventType } from '../purchase-payment-e2e/contract.mjs';
import { outputRoot, runIdentifier, sourceIdentity, writeJson } from './contract.mjs';
import {
  backendEnvironment,
  backendTimeoutMs,
  backlogDrainPlan,
  claim,
  dispatchBatchSize,
  dispatchMaximumAttempts,
  dispatchRetryDelay,
  exactConfiguredRowCount,
  maximumRuntimeMs,
  nonClaims,
  snapshot,
  stateTimeoutMs,
} from './backlog-drain-contract.mjs';
import {
  appendEvidenceEnvelope,
  queryOutboxRows,
  validateDelivered,
  validateUnavailable,
} from './backlog-drain-evidence.mjs';
import { createBacklogDrainObservations } from './backlog-drain-observations.mjs';
import { cleanup, remainingMilliseconds } from './backlog-drain-lifecycle.mjs';
import { validateBacklogHandoff, requireSameBacklogIdentity } from './backlog-handoff.mjs';
import {
  publishExactEventAllowlist,
  verifyExactAcceptedPayments,
} from './sandbox-event-allowlist.mjs';

export { backlogDrainPlan };

export async function executeBacklogDrain(contract, handoff) {
  const identity = sourceIdentity();
  const expectedRows = exactConfiguredRowCount(contract);
  const runId = `${runIdentifier()}-backlog-drain`;
  const runDirectory = resolve(outputRoot, runId);
  mkdirSync(runDirectory, { recursive: true, mode: 0o700 });
  const startedAt = new Date();
  const deadline = startedAt.getTime() + maximumRuntimeMs;
  const environment = backendEnvironment(runDirectory, contract);
  writeJson(resolve(runDirectory, 'source-identity.json'), snapshot(
    'CAPACITY_BACKLOG_DRAIN_SOURCE_IDENTITY_V1', { runId, ...identity },
  ));
  writeJson(resolve(runDirectory, 'backlog-drain-contract.json'), snapshot(
    'CAPACITY_BACKLOG_DRAIN_CONTRACT_V1', {
      expectedRows, dispatchBatchSize, dispatchRetryDelay,
      dispatchMaximumAttempts: Number(dispatchMaximumAttempts), claim, nonClaims,
      sourceRunId: handoff?.sourceRunId,
      backlogSource: 'ORIGINAL_PROFILE_MATRIX_ROWS',
      authorization: 'EXACT_GENERATED_EVENT_ALLOWLIST',
    },
  ));
  let backend;
  let executionError;
  let cleanupError;
  let cleanupEvidence;
  let summary;
  let observations;
  try {
    if (!Array.isArray(handoff?.instances)) {
      throw new Error('drain requires the original profile-matrix handoff');
    }
    const instances = handoff.instances;
    const instanceIds = instances.map(instance => instance.instanceId);
    // Read the untouched rows BEFORE starting any dispatcher. Do not reset or generate work.
    const original = validateBacklogHandoff(handoff, queryOutboxRows(instanceIds), expectedRows);
    writeJson(resolve(runDirectory, 'matrix-backlog-handoff.json'), snapshot(
      'CAPACITY_ORIGINAL_MATRIX_BACKLOG_HANDOFF_V1', {
        sourceRunId: handoff.sourceRunId, ...identity, instances, rows: original,
      },
    ));
    writeJson(resolve(runDirectory, 'backlog-drain-instances.json'), snapshot(
      'CAPACITY_BACKLOG_DRAIN_INSTANCES_V1', {
        expectedRows, completedInstances: instances.length, instances,
        sourceRunId: handoff.sourceRunId, reusedProfileMatrixBacklog: true,
      },
    ));
    writeJson(resolve(runDirectory, 'backlog-drain-command-attempts.json'), snapshot(
      'CAPACITY_BACKLOG_DRAIN_COMMAND_ATTEMPTS_V1', {
        attempts: [], transportAttempts: 0, retryScheduled: 0, terminalFailures: 0,
        interpretation: 'NO_NEW_APPROVAL_COMMANDS_REUSES_PROFILE_MATRIX_WORK',
        sourceRunId: handoff.sourceRunId,
      },
    ));
    backend = startManagedNode(
      'Start existing demo backend against retained profile data with unavailable payment sandbox',
      ['scripts/product-readiness/demo-backend.mjs', 'start'],
      resolve(runDirectory, 'backlog-drain-backend.log'), environment,
    );
    for (const marker of ['BACKEND_LOCAL_START_VERIFIED', 'PURCHASE_PAYMENT_DEMO_SEED_APPLIED',
      'PURCHASE_PAYMENT_LOCAL_SANDBOX_STARTED']) {
      await waitForMarker(backend, marker, Math.min(backendTimeoutMs,
        remainingMilliseconds(deadline, 'backlog-drain backend readiness')));
    }
    const readState = () => {
      const value = {
        rows: queryOutboxRows(instanceIds),
        sandbox: readSandboxStatus(environment.APPROVAL_DEMO_PAYMENT_SANDBOX_STATUS_FILE),
      };
      requireSameBacklogIdentity(original, value.rows);
      return value;
    };
    const unavailable = await waitForState(
      'Original profile-matrix Outbox backlog under HTTP 503', readState,
      value => validateUnavailable(value, expectedRows),
      Math.min(stateTimeoutMs, remainingMilliseconds(deadline, 'backlog-drain unavailable evidence')),
    );
    writeJson(resolve(runDirectory, 'outbox-backlog-unavailable.json'), snapshot(
      'CAPACITY_OUTBOX_BACKLOG_UNAVAILABLE_V1', unavailable,
    ));
    const allowlist = publishExactEventAllowlist(
      environment.APPROVAL_DEMO_PAYMENT_SANDBOX_EVENT_ALLOWLIST_FILE,
      unavailable.rows, instances, contract.scenario.tenant.id, eventType(),
    );
    writeJson(resolve(runDirectory, 'payment-sandbox-allowlist.json'), allowlist);
    observations = createBacklogDrainObservations(unavailable.rows, expectedRows);
    const recoveryStartedAt = performance.now();
    const readRecoveryState = () => {
      const value = readState();
      observations.observe(value.rows, performance.now() - recoveryStartedAt);
      return value;
    };
    writeFileSync(environment.APPROVAL_DEMO_PAYMENT_SANDBOX_CONTROL_FILE, 'recover\n',
      { encoding: 'utf8', mode: 0o600 });
    const complete = value => validateDelivered(value, expectedRows)
      && verifyExactAcceptedPayments(value.sandbox, allowlist);
    const delivered = await waitForState(
      'Configured-volume Outbox backlog delivered after sandbox recovery',
      readRecoveryState, complete,
      Math.min(stateTimeoutMs, remainingMilliseconds(deadline, 'backlog-drain delivered evidence')),
    );
    writeJson(resolve(runDirectory, 'outbox-backlog-delivered.json'), snapshot(
      'CAPACITY_OUTBOX_BACKLOG_DELIVERED_V1', delivered,
    ));
    for (let observation = 1; observation <= 5; observation += 1) {
      await new Promise(resolvePromise => setTimeout(resolvePromise, 1_000));
      const stable = readRecoveryState();
      if (!complete(stable)) {
        throw new Error(`backlog drain lost exactly-once stability at observation ${observation}`);
      }
    }
    const observedDrain = observations.metrics();
    summary = snapshot('CAPACITY_OUTBOX_BACKLOG_DRAIN_SUMMARY_V1', {
      status: 'PASSED_AT_CONFIGURED_VOLUME_ONLY', claim, expectedRows,
      sourceRunId: handoff.sourceRunId, reusedProfileMatrixBacklog: true,
      exactEventAllowlistVerified: true, allowlistSha256: allowlist.sha256,
      pendingRowsBeforeRecovery: unavailable.rows.length,
      deliveredRowsAfterRecovery: delivered.rows.length,
      minimumAttemptsPerRow: Math.min(...delivered.rows.map(row => row.attempts)),
      maximumAttemptsPerRow: Math.max(...delivered.rows.map(row => row.attempts)),
      sandboxDeliveryAttempts: delivered.sandbox.deliveryAttempts,
      acceptedPaymentResults: delivered.sandbox.acceptedPaymentResults,
      duplicateAcceptedPaymentResults: 0,
      recoveryElapsedMs: observedDrain.allDeliveredAfterRecoveryMs,
      deliveredPerSecond: observedDrain.deliveredPerSecond,
      observedDrain, stableObservations: 5,
      interpretation: 'LOCAL_SINGLE_NODE_CONFIGURED_VOLUME_NOT_PRODUCTION_RTO',
      startedAt: startedAt.toISOString(), completedAt: new Date().toISOString(),
    });
  } catch (error) {
    executionError = error;
  } finally {
    try {
      if (observations) {
        writeJson(resolve(runDirectory, 'outbox-backlog-observations.json'), observations.evidence());
      }
    } catch (error) {
      executionError ??= error;
    }
    try {
      cleanupEvidence = await cleanup(backend, environment, runDirectory, Boolean(backend), 'PROFILE_MATRIX');
    } catch (error) {
      cleanupError = error;
    }
  }
  if (executionError || cleanupError) {
    const failure = snapshot('CAPACITY_BACKLOG_DRAIN_FAILURE_V1', {
      status: 'FAILED',
      execution: executionError instanceof Error ? executionError.message : executionError ? String(executionError) : null,
      cleanup: cleanupError instanceof Error ? cleanupError.message : cleanupError ? String(cleanupError) : null,
    });
    writeJson(resolve(runDirectory, 'backlog-drain-failure.json'), failure);
    appendEvidenceEnvelope('FAILED', runDirectory, identity);
    throw new Error(`capacity backlog drain failed: ${JSON.stringify(failure)}`, {
      cause: executionError || cleanupError,
    });
  }
  summary.cleanup = cleanupEvidence;
  writeJson(resolve(runDirectory, 'outbox-backlog-drain-summary.json'), summary);
  appendEvidenceEnvelope('PASSED', runDirectory, identity);
  console.log(`CAPACITY_BACKLOG_DRAIN_RUN_ID=${runId}`);
  console.log(`CAPACITY_BACKLOG_DRAIN_EVIDENCE=${runDirectory}`);
  console.log(claim);
  for (const marker of nonClaims) console.log(marker);
  return summary;
}
