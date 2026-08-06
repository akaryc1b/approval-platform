import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function read(relativePath) {
  const file = path.join(root, relativePath);
  assert.equal(existsSync(file), true, `${relativePath} must exist`);
  return readFileSync(file, 'utf8');
}

const rehearsal = read(
  'apps/server/src/test/java/io/github/akaryc1b/approval/api/'
    + 'ControlledAutomationGovernanceIncidentRollbackRehearsalTest.java',
);
const unknown = read(
  'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/'
    + 'OpenAiResponsesPostDispatchUnknownIncidentRehearsalTest.java',
);
const acceptance = read(
  'docs/m6/M6_F_P7_ADVERSARIAL_FAULT_CONCURRENCY_ACCEPTANCE.md',
);

test('P7-D rehearses each of the eight read-only readiness and rollback scenarios', () => {
  for (const scenario of [
    'scenario1RuntimeNotConfiguredIsAlreadyDisabledAndRequiresNoReleaseAction',
    'scenario2HealthyRuntimeRemainsAdvisoryOnlyWithEmptyWhitelistAndNoReauth',
    'scenario3CircuitOpenIsIncidentBlockedWithManualRollbackReviewOnly',
    'scenario4CircuitHalfOpenIsNotHealthyAndDoesNotInitiateAProbe',
    'scenario5TenantRateSaturationDoesNotResetLimitsOrInvokeProvider',
    'scenario6GlobalRateSaturationExposesOnlyTheBooleanGlobalPosture',
    'scenario7VersionDriftRequiresReviewWithoutRestoringOrMutatingRuntime',
    'scenario8RetentionDueRequiresManualTombstoneReviewWithoutScheduler',
  ]) {
    assert.match(rehearsal, new RegExp(scenario));
  }
  assert.match(rehearsal, /RUNTIME_NOT_CONFIGURED/);
  assert.match(rehearsal, /OBSERVATION_READY_ADVISORY_ONLY/);
  assert.match(rehearsal, /ACTION_REQUIRED/);
  assert.match(rehearsal, /INCIDENT_BLOCKED/);
  assert.match(rehearsal, /ALREADY_DISABLED/);
  assert.match(rehearsal, /REVIEW_NON_EXECUTABLE_ROLLBACK_PLAN/);
});

test('P7-D rehearses post-dispatch UNKNOWN as one auditable exchange', () => {
  assert.match(
    unknown,
    /scenario9PostDispatchUnknownRemainsSingleAttemptAuditableAndNonRetryable/,
  );
  assert.match(unknown, /Failure\.UNKNOWN/);
  assert.match(unknown, /assertEquals\(1, fixture\.network\(\)\.exchangeCount\.get\(\)\)/);
  assert.match(unknown, /assertEquals\(1, usage\.committedRequests\(\)\)/);
  assert.doesNotMatch(unknown, /retry\s*\(|fallback\s*\(|Thread\.sleep|Math\.random/);
});

test('P7-D rehearsals remain manual evidence only', () => {
  for (const boundary of [
    'incidentMutationAvailable',
    'providerInvocationAvailable',
    'rollbackExecutionAvailable',
    'commandExecutionAuthorized',
    'automaticRetryAuthorized',
    'notificationAutomationAvailable',
    'rawSecretExposed',
  ]) {
    assert.match(rehearsal, new RegExp(`assertFalse\\(view\\.${boundary}\\(\\)\\)`));
  }
  assert.match(rehearsal, /EMPTY_ACTION_WHITELIST/);
  assert.match(rehearsal, /P5_SKIPPED/);
  assert.doesNotMatch(
    `${rehearsal}\n${unknown}`,
    /ApprovalTaskCommandService|ApprovalProcessCommandService|Runtime\.getRuntime\(\)\.exec/,
  );
  assert.doesNotMatch(
    `${rehearsal}\n${unknown}`,
    /@Scheduled\b|TaskScheduler|\b(class|interface|record)\s+\w*(Worker|Queue|Scheduler)/,
  );
});

test('P7 formal acceptance records all scenarios gates and honest limitations', () => {
  for (const required of [
    'Exact baseline',
    'Threat / Fault / Race acceptance',
    'Scenario 1',
    'Scenario 2',
    'Scenario 3',
    'Scenario 4',
    'Scenario 5',
    'Scenario 6',
    'Scenario 7',
    'Scenario 8',
    'Scenario 9',
    'Action Whitelist',
    'P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND',
    'AI_IS_NOT_AN_OPERATOR',
    'Honest limitations',
    'P8 entry gate',
  ]) {
    assert.match(acceptance, new RegExp(required.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }
  assert.match(acceptance, /EMPTY_PENDING_EXISTING_COMMAND_AUDIT/);
  assert.match(acceptance, /Provider -> direct command/);
  assert.match(acceptance, /P7_D_IMPLEMENTED_PENDING_EXACT_HEAD_PERMANENT_VALIDATION/);
});

test('P7-D adds no V51 second workflow or automatic incident path', () => {
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
  assert.doesNotMatch(
    acceptance,
    /automatic (incident|rollback|retry|notification|retention) (execution|mutation)/i,
  );
});
