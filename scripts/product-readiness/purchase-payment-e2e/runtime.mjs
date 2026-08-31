import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

import {
  ledgerPath,
  loadContract,
  outputRoot,
  runIdentifier,
  sourceIdentity,
  writeJson,
} from './contract.mjs';
import {
  appendCiEvidenceEnvelope,
  nextSuccessfulLedger,
  resetLedger,
} from './evidence.mjs';
import {
  cleanupRuntime,
  stagePcH5Evidence,
} from './lifecycle.mjs';
import {
  backendEnvironment,
  runPaymentStage,
} from './payment.mjs';

export async function execute(reusePcH5) {
  const contract = loadContract();
  const identity = sourceIdentity();
  const runId = runIdentifier();
  mkdirSync(outputRoot, { recursive: true, mode: 0o700 });
  const runDirectory = resolve(outputRoot, runId);
  mkdirSync(runDirectory, { recursive: true, mode: 0o700 });
  writeJson(resolve(runDirectory, 'source-identity.json'), {
    schemaVersion: 1,
    evidenceKind: 'PURCHASE_PAYMENT_E2E_SOURCE_IDENTITY_V1',
    runId,
    capturedAt: new Date().toISOString(),
    ...identity,
  });
  writeJson(resolve(runDirectory, 'acceptance-contract.json'), contract.acceptance);

  const managed = [];
  let executionResult;
  let executionError;
  let cleanupEvidence;
  let cleanupError;
  const environment = backendEnvironment(runDirectory, contract);
  try {
    const pcH5Evidence = stagePcH5Evidence(
      runDirectory,
      contract,
      identity,
      reusePcH5,
    );
    executionResult = {
      pcH5Evidence,
      payment: await runPaymentStage(
        runDirectory,
        contract,
        identity,
        pcH5Evidence,
        managed,
      ),
    };
  } catch (error) {
    executionError = error;
  } finally {
    try {
      cleanupEvidence = await cleanupRuntime(
        managed,
        environment,
        runDirectory,
      );
    } catch (error) {
      cleanupError = error;
    }
  }

  if (executionError || cleanupError) {
    resetLedger(identity, runId);
    const details = {
      execution: executionError instanceof Error
        ? executionError.message
        : executionError ? String(executionError) : null,
      cleanup: cleanupError instanceof Error
        ? cleanupError.message
        : cleanupError ? String(cleanupError) : null,
    };
    writeJson(resolve(runDirectory, 'runtime-failure.json'), {
      schemaVersion: 1,
      evidenceKind: 'PURCHASE_PAYMENT_LOCAL_ALPHA_E2E_FAILURE_V1',
      runId,
      failedAt: new Date().toISOString(),
      ...details,
    });
    appendCiEvidenceEnvelope('FAILED', runDirectory, identity);
    throw new Error(`purchase-payment E2E failed: ${JSON.stringify(details)}`, {
      cause: executionError || cleanupError,
    });
  }

  const ledger = nextSuccessfulLedger(identity, runId);
  const claimsDeclared = ledger.successfulRunIds.length >= 2;
  const summary = {
    schemaVersion: 1,
    evidenceKind: 'PURCHASE_PAYMENT_LOCAL_ALPHA_E2E_V1',
    runId,
    commitSha: identity.commitSha,
    treeSha: identity.treeSha,
    tenantId: contract.scenario.tenant.id,
    businessKey: contract.scenario.request.businessKey,
    instanceId: executionResult.pcH5Evidence.instanceId,
    targetClient: contract.policy.targetClient,
    acceptanceClient: contract.policy.acceptanceClient,
    acceptanceMode: contract.policy.acceptanceMode,
    taskIds: [
      ...executionResult.pcH5Evidence.steps.map(step => step.taskId),
      executionResult.pcH5Evidence.paymentHandoff.taskId,
    ],
    finalStatus: executionResult.payment.h5Evidence.finalState.status,
    outboxStatus: executionResult.payment.delivered.outbox[0].status,
    acceptedPaymentSideEffects:
      executionResult.payment.delivered.sandbox.acceptedPaymentResults,
    wechatMiniProgramBuild: 'PASSED_BUILD_ONLY',
    cleanup: cleanupEvidence,
    successfulRunIds: ledger.successfulRunIds,
    claimsDeclared,
    claims: claimsDeclared
      ? contract.acceptance.claimsAfterTwoConsecutiveCleanRuns
      : [],
    nonClaims: contract.acceptance.nonClaims,
    completedAt: new Date().toISOString(),
  };
  try {
    writeJson(resolve(runDirectory, 'runtime-summary.json'), summary);
    writeJson(ledgerPath, ledger);
    appendCiEvidenceEnvelope('PASSED', runDirectory, identity);
  } catch (error) {
    resetLedger(identity, runId);
    throw error;
  }

  console.log(`PURCHASE_PAYMENT_E2E_RUN_ID=${runId}`);
  console.log(`PURCHASE_PAYMENT_E2E_EVIDENCE=${runDirectory}`);
  if (!claimsDeclared) {
    console.log('PURCHASE_PAYMENT_E2E_FIRST_CLEAN_RUN_RECORDED');
    console.log('TWO_CONSECUTIVE_CLEAN_RUNS_REQUIRED');
    for (const nonClaim of contract.acceptance.nonClaims) {
      console.log(nonClaim);
    }
    return;
  }
  for (const claim of contract.acceptance.claimsAfterTwoConsecutiveCleanRuns) {
    console.log(claim);
  }
  for (const nonClaim of contract.acceptance.nonClaims) {
    console.log(nonClaim);
  }
}
