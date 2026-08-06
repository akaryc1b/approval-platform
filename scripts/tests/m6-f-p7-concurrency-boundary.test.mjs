import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import './m6-f-p7-composite-stability-boundary.test.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function read(relativePath) {
  const file = path.join(root, relativePath);
  assert.equal(existsSync(file), true, `${relativePath} must exist`);
  return readFileSync(file, 'utf8');
}

const lineage = read(
  'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/'
    + 'persistence/jdbc/JdbcControlledAutomationLineageConcurrencyAcceptanceTest.java',
);
const confirmation = read(
  'server-modules/approval-ai-core/src/test/java/io/github/akaryc1b/approval/ai/core/'
    + 'ControlledAutomationConfirmationConcurrencyAcceptanceTest.java',
);
const usage = read(
  'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/'
    + 'OpenAiResponsesUsageConcurrencyAcceptanceTest.java',
);
const usageLedger = read(
  'server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai/'
    + 'OpenAiResponsesRuntimeUsageLedger.java',
);
const circuit = read(
  'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/'
    + 'OpenAiResponsesCircuitConcurrencyAcceptanceTest.java',
);
const runtimeControl = read(
  'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/'
    + 'OpenAiResponsesProductionRuntimeControlConcurrencyTest.java',
);
const runtimeFactory = read(
  'server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai/'
    + 'OpenAiResponsesProductionRuntimeFactory.java',
);
const composite = read(
  'apps/server/src/test/java/io/github/akaryc1b/approval/api/'
    + 'ControlledAutomationGovernanceCompositeConcurrencyAcceptanceTest.java',
);
const composition = read(
  'apps/server/src/test/java/io/github/akaryc1b/approval/config/'
    + 'ControlledAutomationGovernanceCompositionRaceAcceptanceTest.java',
);
const matrix = read('docs/m6/M6_F_P7_ADVERSARIAL_FAULT_CONCURRENCY_MATRIX.md');

const concurrencyTests = [
  lineage,
  confirmation,
  usage,
  circuit,
  runtimeControl,
  composite,
  composition,
].join('\n');

test('P7-C registration replay CAS and terminal races use real PostgreSQL', () => {
  for (const scenario of [
    'concurrentExactRegistrationProducesOneStateAndOneEvent',
    'concurrentSameKeyDifferentPayloadHasOneOwnerAndOneConflict',
    'mixedReplayAndConflictRaceCannotDuplicateStateOrEvent',
    'identicalProposalIdsRemainIsolatedAcrossConcurrentTenants',
    'everyTerminalPairHasOneWinnerAndOneConsistentState',
    'unknownWinsAgainstAConcurrentRetryAndRemainsTerminal',
    'exactReplayAfterRowLockReleaseDoesNotAppendAnotherEvent',
    'concurrentRegistrationsUseUniqueAppendOnlyEventIdentities',
  ]) {
    assert.match(lineage, new RegExp(scenario));
  }
  assert.match(lineage, /PostgreSQLContainer/);
  assert.match(lineage, /for update/);
  assert.match(lineage, /TransitionDisposition\.APPLIED/);
  assert.match(lineage, /TransitionDisposition\.REPLAYED/);
  assert.match(lineage, /TransitionDisposition\.STATE_CONFLICT/);
  assert.match(lineage, /LineageStatus\.UNKNOWN/);
  assert.doesNotMatch(lineage, /Thread\.sleep|Math\.random/);
});

test('P7-C confirmation races remain explicit non-executable evidence only', () => {
  for (const scenario of [
    'twoOperatorsRacingCanOnlyConfirmForTheProposalBoundOperator',
    'duplicateSameOperatorConfirmationCreatesOnlySingleUseNonExecutableEvidence',
    'confirmationAndExpiryRaceUsesExactControlledClockBoundaries',
    'freshConfirmationRacingPolicyAndVersionDriftFailsClosed',
  ]) {
    assert.match(confirmation, new RegExp(scenario));
  }
  assert.match(confirmation, /CONFIRMED_NON_EXECUTABLE/);
  assert.match(confirmation, /IDENTITY_MISMATCH/);
  assert.match(confirmation, /PROPOSAL_NOT_ACTIVE/);
  assert.match(confirmation, /EVALUATION_NOT_ELIGIBLE/);
  assert.match(confirmation, /assertFalse\([^\n]*commandAdmitted\(\)\)/);
  assert.doesNotMatch(
    confirmation,
    /ApprovalTaskCommandService|ApprovalProcessCommandService|\.advise\s*\(/,
  );
});

test('P7-C usage concurrency is bounded exactly once and window-stable', () => {
  for (const scenario of [
    'sameTenantConcurrentDispatchStopsExactlyAtTheTenantAndGlobalBoundary',
    'multipleTenantsRaceForTheGlobalBoundaryWithoutLeakingExactGlobalUsage',
    'concurrentSnapshotsRemainCoherentWhileRecordsAreCommitted',
    'concurrentTenantCreationCannotExceedTheConfiguredCapacity',
    'onlyTheNewestFourRateWindowsRemainTracked',
    'duplicateConcurrentMarkDispatchedCommitsUsageExactlyOnce',
    'delayedConcurrentDispatchRetainsTheOriginalAdmissionWindow',
  ]) {
    assert.match(usage, new RegExp(scenario));
  }
  assert.match(usageLedger, /public synchronized void recordDispatched/);
  assert.match(usageLedger, /public synchronized UsageSnapshot snapshot/);
  assert.match(usageLedger, /MAXIMUM_TRACKED_WINDOWS = 4/);
  assert.match(usageLedger, /global-exact-usage-redacted/);
  assert.doesNotMatch(usage, /Thread\.sleep|Math\.random/);
});

test('P7-C circuit admits one HALF_OPEN probe and snapshots atomically', () => {
  for (const scenario of [
    'concurrentFailuresReachTheThresholdWithoutLostGeneration',
    'concurrentOpenAdmissionsAreAllRejectedWithoutChangingGeneration',
    'halfOpenWindowAllowsExactlyOneConcurrentProbe',
    'halfOpenSuccessAndFailureRaceAcceptsOnlyOneTerminalProbeOutcome',
    'concurrentStateObservationsNeverDecreaseGeneration',
  ]) {
    assert.match(circuit, new RegExp(scenario));
  }
  for (const scenario of [
    'controlSnapshotNeverTearsCircuitStateFromItsGeneration',
    'concurrentControlSnapshotsRemainSideEffectFreeAndGenerationMonotonic',
  ]) {
    assert.match(runtimeControl, new RegExp(scenario));
  }
  assert.match(
    runtimeFactory,
    /synchronized \(circuitBreaker\) \{[\s\S]*circuitState = circuitBreaker\.state\(\);[\s\S]*circuitGeneration = circuitBreaker\.generation\(\);[\s\S]*\}/,
  );
  assert.doesNotMatch(`${circuit}\n${runtimeControl}`, /Thread\.sleep|Math\.random/);
});

test('P7-C composite evidence cannot mix observations runtimes or retries', () => {
  for (const scenario of [
    'everyComponentFromAnotherObservationCycleIsRejected',
    'retryCannotCherryPickAHealthierRuntimeOrSplicePriorComponents',
    'replacingAnyComponentReferenceCannotReuseAnOlderCompositeHash',
    'coherentRetriesProduceIndependentWholeSnapshotsOnly',
  ]) {
    assert.match(composite, new RegExp(scenario));
  }
  for (const scenario of [
    'changingSnapshotSourceIsReadExactlyOncePerCompositeAttempt',
    'concurrentAttemptsNeverReuseOrSpliceComponentsFromAnotherAttempt',
  ]) {
    assert.match(composition, new RegExp(scenario));
  }
  assert.match(composite, /IncidentReadinessView\.from/);
  assert.match(composition, /snapshotReads\.getAndIncrement/);
  assert.doesNotMatch(`${composite}\n${composition}`, /Thread\.sleep|Math\.random/);
});

test('P7-C deterministic matrix and synchronization contract remain bound', () => {
  for (const section of [
    'Proposal registration concurrency',
    'Confirmation concurrency',
    'CAS and terminal races',
    'Usage Ledger concurrency',
    'Circuit concurrency',
    'Composite Snapshot races',
  ]) {
    assert.match(matrix, new RegExp(section));
  }
  assert.match(concurrencyTests, /Executors\.newVirtualThreadPerTaskExecutor\(\)/);
  assert.match(concurrencyTests, /CountDownLatch/);
  assert.doesNotMatch(concurrencyTests, /Thread\.sleep|Math\.random|new Random\s*\(/);
});

test('P7-C adds no command authority V51 or second automatic workflow', () => {
  assert.doesNotMatch(
    concurrencyTests,
    /ApprovalTaskCommandService|ApprovalProcessCommandService|Runtime\.getRuntime\(\)\.exec/,
  );
  assert.doesNotMatch(
    concurrencyTests,
    /@Scheduled\b|TaskScheduler|\b(class|interface|record)\s+\w*(Worker|Queue|Scheduler)/,
  );

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
