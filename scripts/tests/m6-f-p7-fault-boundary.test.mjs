import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import './m6-f-p7-concurrency-boundary.test.mjs';
import './m6-f-p7-incident-rehearsal-boundary.test.mjs';
import './m6-f-p8-rebaseline-boundary.test.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function read(relativePath) {
  const file = path.join(root, relativePath);
  assert.equal(existsSync(file), true, `${relativePath} must exist`);
  return readFileSync(file, 'utf8');
}

const sender = read(
  'server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai/'
    + 'OpenAiResponsesSecureHttpSender.java',
);
const postDispatch = read(
  'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/'
    + 'OpenAiResponsesPostDispatchUnknownAcceptanceTest.java',
);
const circuit = read(
  'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/'
    + 'OpenAiResponsesCircuitFaultAcceptanceTest.java',
);
const circuitReadiness = read(
  'apps/server/src/test/java/io/github/akaryc1b/approval/api/'
    + 'ControlledAutomationGovernanceCircuitFaultAcceptanceTest.java',
);
const usage = read(
  'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/'
    + 'OpenAiResponsesRateUsageFaultAcceptanceTest.java',
);
const history = read(
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/'
    + 'persistence/jdbc/JdbcApprovalAssistanceGovernanceHistoryQuery.java',
);
const historyFault = read(
  'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/'
    + 'persistence/jdbc/JdbcApprovalAssistanceGovernanceHistoryFaultIntegrationTest.java',
);
const lineageFault = read(
  'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/'
    + 'persistence/jdbc/JdbcControlledAutomationLineageFaultIntegrationTest.java',
);
const historyCore = read(
  'server-modules/approval-ai-core/src/test/java/io/github/akaryc1b/approval/ai/core/'
    + 'ApprovalAssistanceGovernanceHistoryFaultAcceptanceTest.java',
);

test('P7-B promotes only post-dispatch transport ambiguity to UNKNOWN', () => {
  assert.match(sender, /catch \(OpenAiResponsesTransportException exception\)/);
  assert.match(sender, /if \(permit\.dispatched\(\)\)/);
  assert.match(sender, /Failure\.UNKNOWN/);
  assert.match(postDispatch, /timeoutAndIoFailureAfterDispatchRemainSingleAttemptUnknown/);
  assert.match(postDispatch, /preDispatchDnsTlsAndSecretFailuresKeepExactClassificationAndZeroUsage/);
  assert.match(postDispatch, /connectionDriftBeforeDispatchDoesNotReadSecretOrRecordUsage/);
  assert.match(postDispatch, /assertEquals\(1, fixture\.network\(\)\.exchangeCount\.get\(\)\)/);
});

test('P7-B circuit transitions are deterministic and fail closed', () => {
  for (const scenario of [
    'consecutiveFailuresOpenCircuitAndOpenWindowRejectsAdmission',
    'openWindowAllowsOneHalfOpenProbeAndSuccessClosesCircuit',
    'halfOpenFailureReopensForAnotherFullWindow',
    'snapshotsNeverAcquirePermitOrChangeGeneration',
  ]) {
    assert.match(circuit, new RegExp(scenario));
  }
  assert.doesNotMatch(circuit, /Thread\.sleep|Math\.random/);
});

test('P7-B OPEN and HALF_OPEN readiness remains incident blocked', () => {
  assert.match(
    circuitReadiness,
    /openAndHalfOpenCircuitStatesRemainIncidentBlockedAndNonExecuting/,
  );
  assert.match(circuitReadiness, /AI_PROVIDER_CIRCUIT_OPEN/);
  assert.match(circuitReadiness, /AI_PROVIDER_CIRCUIT_HALF_OPEN/);
  assert.match(circuitReadiness, /AI_INCIDENT_STEP_DO_NOT_AUTOMATICALLY_RETRY/);
  assert.match(circuitReadiness, /assertFalse\(view\.rollbackExecutionAvailable\(\)\)/);
});

test('P7-B usage tests bind dispatch accounting to exact rate windows', () => {
  for (const scenario of [
    'preDispatchCloseAndCancellationRecordZeroUsage',
    'dispatchAndTerminalRecordingAreExactlyOnce',
    'delayedDispatchRemainsOwnedByOriginalRateWindow',
    'tenantAndGlobalSaturationRemainBoundedAndRedacted',
    'tenantCapacityAndEnvelopeOverflowFailClosed',
    'expiredAndFutureCostPoliciesRejectBeforeDispatch',
    'expiredAndFutureSecretVersionsFailBeforeMaterialRead',
  ]) {
    assert.match(usage, new RegExp(scenario));
  }
  assert.doesNotMatch(usage, /Thread\.sleep|Math\.random/);
});

test('P7-B durable history is read-only repeatable-read and normalizes failures', () => {
  assert.match(history, /setReadOnly\(true\)/);
  assert.match(history, /ISOLATION_REPEATABLE_READ/);
  assert.match(history, /DataAccessException \| TransactionException/);
  assert.match(history, /HistoryQueryException/);
  assert.doesNotMatch(
    history,
    /\b(insert|update|delete)\s+ap_ai_approval_assistance_evidence/i,
  );
  assert.match(
    historyFault,
    /unavailableV49TableReturnsNoPartialSummaryAndPerformsNoRepairWrite/,
  );
});

test('P7-B durable history contracts reject inconsistency and overflow', () => {
  for (const scenario of [
    'activeAndTombstonedCountsMustExactlyEqualTotal',
    'attemptAndInvocationCountsCannotDiverge',
    'retentionCannotExceedActiveEvidence',
    'outcomeAndUseCaseAggregatesMustMatchExactDurableTotals',
    'aggregateAdditionOverflowFailsClosed',
  ]) {
    assert.match(historyCore, new RegExp(scenario));
  }
});

test('P7-B lineage failures roll back event and state atomically', () => {
  for (const scenario of [
    'registrationEventFailureRollsBackLineageAndEventTogether',
    'terminalStateUpdateFailureRollsBackInsertedEvent',
    'partialOutcomePersistsAsPartialAndCannotBecomeSuccess',
  ]) {
    assert.match(lineageFault, new RegExp(scenario));
  }
  assert.match(lineageFault, /P7 injected lineage event failure/);
  assert.match(lineageFault, /P7 injected lineage state failure/);
});

test('P7-B retains malformed JSON schema and output fail-closed evidence', () => {
  const decoder = read(
    'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/'
      + 'OpenAiResponsesResponseDecoderTest.java',
  );
  const codec = read(
    'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/'
      + 'OpenAiResponsesCodecHardeningTest.java',
  );
  assert.match(decoder, /incompleteProviderErrorRefusalAndMultipleOutputsFailClosed/);
  assert.match(decoder, /statelessReasoningOutputShapeFailsClosed/);
  assert.match(decoder, /SCHEMA_MISMATCH/);
  assert.match(codec, /currentKnownResponseFieldsAreAcceptedOnlyInTheStatelessProfile/);
  assert.match(codec, /assertSchemaMismatch/);
});

test('P7-B retains provider single-attempt redaction and no automatic retry', () => {
  const providerFault = read(
    'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/'
      + 'OpenAiResponsesProductionFaultMatrixTest.java',
  );
  assert.match(providerFault, /everyTransportFailureIsSingleAttemptStableAndNonRetryable/);
  assert.match(providerFault, /providerHttpFailureMatrixUsesOneExchangeAndNeverLeaksBody/);
  assert.match(providerFault, /unexpectedRuntimeFailureRemainsUnknownBodyFreeAndSingleAttempt/);
  assert.match(providerFault, /assertFalse\(outcome\.failure\(\)\.retryable\(\)/);
});

test('P7-B adds no V51 or second automatic workflow', () => {
  const resourceRoot = path.join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/resources',
  );
  const versions = [
    path.join(resourceRoot, 'db/migration'),
    path.join(resourceRoot, 'm6f/db/migration'),
  ].flatMap((directory) => readdirSync(directory))
    .map((name) => /^V(\d+)__.+\.sql$/.exec(name))
    .filter(Boolean)
    .map((match) => Number(match[1]));
  assert.equal(Math.max(...versions), 50);
  assert.equal(versions.some((version) => version >= 51), false);

  const workflowRoot = path.join(root, '.github/workflows');
  const automatic = readdirSync(workflowRoot)
    .filter((name) => /\.ya?ml$/.test(name))
    .filter((name) => {
      const content = readFileSync(path.join(workflowRoot, name), 'utf8');
      return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
    });
  assert.deepEqual(automatic, ['approval-platform-validation.yml']);
});