import {
  mkdirSync,
  writeFileSync,
} from 'node:fs';
import { resolve } from 'node:path';
import { performance } from 'node:perf_hooks';

import {
  startManagedNode,
  waitForMarker,
} from '../pc-h5-runtime/processes.mjs';
import {
  readSandboxStatus,
  waitForState,
} from '../purchase-payment-e2e/evidence.mjs';
import {
  outputRoot,
  runIdentifier,
  sourceIdentity,
  writeJson,
} from './contract.mjs';
import {
  approvalConcurrency,
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
  requestTimeoutMs,
  snapshot,
  startConcurrency,
  stateTimeoutMs,
  volumePrefixes,
} from './backlog-drain-contract.mjs';
import {
  appendEvidenceEnvelope,
  queryOutboxRows,
  seededAttachmentIds,
  validateDelivered,
  validateUnavailable,
} from './backlog-drain-evidence.mjs';
import { executeBacklogWorkflow } from './backlog-drain-http.mjs';
import {
  createBacklogDrainObservations,
} from './backlog-drain-observations.mjs';
import {
  cleanup,
  remainingMilliseconds,
  resetDisposableData,
} from './backlog-drain-lifecycle.mjs';

export { backlogDrainPlan };

export async function executeBacklogDrain(contract) {
  const identity = sourceIdentity();
  const expectedRows = exactConfiguredRowCount(contract);
  const runId = `${runIdentifier()}-backlog-drain`;
  const runDirectory = resolve(outputRoot, runId);
  mkdirSync(runDirectory, { recursive: true, mode: 0o700 });
  const startedAt = new Date();
  const deadline = startedAt.getTime() + maximumRuntimeMs;
  const prefixes = volumePrefixes(contract);
  const environment = backendEnvironment(runDirectory, contract, prefixes);

  writeJson(resolve(runDirectory, 'source-identity.json'), snapshot(
    'CAPACITY_BACKLOG_DRAIN_SOURCE_IDENTITY_V1',
    { runId, ...identity },
  ));
  writeJson(resolve(runDirectory, 'backlog-drain-contract.json'), snapshot(
    'CAPACITY_BACKLOG_DRAIN_CONTRACT_V1',
    {
      expectedRows,
      startConcurrency,
      approvalConcurrency,
      requestTimeoutMs,
      stateTimeoutMs,
      dispatchBatchSize,
      dispatchRetryDelay,
      dispatchMaximumAttempts: Number(dispatchMaximumAttempts),
      prefixes,
      claim,
      nonClaims,
    },
  ));

  let backend;
  let mutated = false;
  let executionError;
  let cleanupError;
  let cleanupEvidence;
  let summary;
  let observations;
  try {
    resetDisposableData(
      environment,
      remainingMilliseconds(deadline, 'backlog-drain initial reset'),
    );
    mutated = true;
    backend = startManagedNode(
      'Start existing demo backend with Generic REST Connector and unavailable payment sandbox',
      ['scripts/product-readiness/demo-backend.mjs', 'start'],
      resolve(runDirectory, 'backlog-drain-backend.log'),
      environment,
    );
    await waitForMarker(
      backend,
      'BACKEND_LOCAL_START_VERIFIED',
      Math.min(
        backendTimeoutMs,
        remainingMilliseconds(deadline, 'backlog-drain backend readiness'),
      ),
    );
    await waitForMarker(
      backend,
      'PURCHASE_PAYMENT_DEMO_SEED_APPLIED',
      Math.min(
        backendTimeoutMs,
        remainingMilliseconds(deadline, 'backlog-drain Seed readiness'),
      ),
    );
    await waitForMarker(
      backend,
      'PURCHASE_PAYMENT_LOCAL_SANDBOX_STARTED',
      Math.min(
        backendTimeoutMs,
        remainingMilliseconds(deadline, 'backlog-drain sandbox readiness'),
      ),
    );

    const token = runId.replace(/[^0-9A-Za-z]/gu, '').slice(-12);
    const result = await executeBacklogWorkflow(
      contract,
      prefixes,
      seededAttachmentIds(contract),
      expectedRows,
      token,
    );
    const instances = result.instances;
    writeJson(resolve(runDirectory, 'backlog-drain-instances.json'), snapshot(
      'CAPACITY_BACKLOG_DRAIN_INSTANCES_V1',
      {
        expectedRows,
        completedInstances: instances.length,
        instances: instances.map(instance => ({
          businessKey: instance.businessKey,
          purchaseOrderReference: instance.purchaseOrderReference,
          instanceId: instance.instanceId,
          finalStatus: instance.finalStatus,
        })),
      },
    ));
    writeJson(
      resolve(runDirectory, 'backlog-drain-command-attempts.json'),
      snapshot('CAPACITY_BACKLOG_DRAIN_COMMAND_ATTEMPTS_V1', {
        attempts: result.attempts,
        transportAttempts: result.attempts.length,
        retryScheduled: result.attempts.filter(attempt =>
          attempt.outcome === 'RETRY_SCHEDULED').length,
        terminalFailures: result.attempts.filter(attempt =>
          attempt.outcome.startsWith('TERMINAL')).length,
      }),
    );

    const instanceIds = instances.map(instance => instance.instanceId);
    const sandboxStatusPath =
      environment.APPROVAL_DEMO_PAYMENT_SANDBOX_STATUS_FILE;
    const unavailable = await waitForState(
      'Configured-volume Outbox backlog under HTTP 503',
      () => ({
        rows: queryOutboxRows(instanceIds),
        sandbox: readSandboxStatus(sandboxStatusPath),
      }),
      value => validateUnavailable(value, expectedRows),
      Math.min(
        stateTimeoutMs,
        remainingMilliseconds(deadline, 'backlog-drain unavailable evidence'),
      ),
    );
    writeJson(resolve(runDirectory, 'outbox-backlog-unavailable.json'), snapshot(
      'CAPACITY_OUTBOX_BACKLOG_UNAVAILABLE_V1',
      unavailable,
    ));

    observations = createBacklogDrainObservations(unavailable.rows, expectedRows);
    const recoveryStartedAt = performance.now();
    const readRecoveryState = () => {
      const value = {
        rows: queryOutboxRows(instanceIds),
        sandbox: readSandboxStatus(sandboxStatusPath),
      };
      observations.observe(value.rows, performance.now() - recoveryStartedAt);
      return value;
    };
    writeFileSync(
      environment.APPROVAL_DEMO_PAYMENT_SANDBOX_CONTROL_FILE,
      'recover\n',
      { encoding: 'utf8', mode: 0o600 },
    );
    const delivered = await waitForState(
      'Configured-volume Outbox backlog delivered after sandbox recovery',
      readRecoveryState,
      value => validateDelivered(value, expectedRows),
      Math.min(
        stateTimeoutMs,
        remainingMilliseconds(deadline, 'backlog-drain delivered evidence'),
      ),
    );
    writeJson(resolve(runDirectory, 'outbox-backlog-delivered.json'), snapshot(
      'CAPACITY_OUTBOX_BACKLOG_DELIVERED_V1',
      delivered,
    ));

    for (let observation = 1; observation <= 5; observation += 1) {
      await new Promise(resolvePromise => setTimeout(resolvePromise, 1_000));
      const stable = readRecoveryState();
      if (!validateDelivered(stable, expectedRows)) {
        throw new Error(
          `backlog drain lost exactly-once stability at observation ${observation}`,
        );
      }
    }

    const observedDrain = observations.metrics();
    summary = snapshot('CAPACITY_OUTBOX_BACKLOG_DRAIN_SUMMARY_V1', {
      status: 'PASSED_AT_CONFIGURED_VOLUME_ONLY',
      claim,
      expectedRows,
      pendingRowsBeforeRecovery: unavailable.rows.length,
      deliveredRowsAfterRecovery: delivered.rows.length,
      minimumAttemptsPerRow: Math.min(
        ...delivered.rows.map(row => row.attempts),
      ),
      maximumAttemptsPerRow: Math.max(
        ...delivered.rows.map(row => row.attempts),
      ),
      sandboxDeliveryAttempts: delivered.sandbox.deliveryAttempts,
      acceptedPaymentResults: delivered.sandbox.acceptedPaymentResults,
      duplicateAcceptedPaymentResults: 0,
      recoveryElapsedMs: observedDrain.allDeliveredAfterRecoveryMs,
      deliveredPerSecond: observedDrain.deliveredPerSecond,
      observedDrain,
      stableObservations: 5,
      interpretation: 'LOCAL_SINGLE_NODE_CONFIGURED_VOLUME_NOT_PRODUCTION_RTO',
      startedAt: startedAt.toISOString(),
      completedAt: new Date().toISOString(),
    });
  } catch (error) {
    executionError = error;
  } finally {
    // Preserve partial observations on failure without bypassing cleanup.
    try {
      if (observations) {
        writeJson(
          resolve(runDirectory, 'outbox-backlog-observations.json'),
          observations.evidence(),
        );
      }
    } catch (error) {
      executionError ??= error;
    }
    try {
      cleanupEvidence = await cleanup(
        backend,
        environment,
        runDirectory,
        mutated,
      );
    } catch (error) {
      cleanupError = error;
    }
  }

  if (executionError || cleanupError) {
    const failure = snapshot('CAPACITY_BACKLOG_DRAIN_FAILURE_V1', {
      status: 'FAILED',
      execution: executionError instanceof Error
        ? executionError.message
        : executionError ? String(executionError) : null,
      cleanup: cleanupError instanceof Error
        ? cleanupError.message
        : cleanupError ? String(cleanupError) : null,
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
