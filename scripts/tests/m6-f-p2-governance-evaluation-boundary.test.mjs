import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const coreRoot = path.join(
  root,
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core',
);
const testRoot = path.join(
  root,
  'server-modules/approval-ai-core/src/test/java/io/github/akaryc1b/approval/ai/core',
);

function source(directory, name) {
  const file = path.join(directory, `${name}.java`);
  assert.equal(existsSync(file), true, `${name}.java must exist`);
  return readFileSync(file, 'utf8');
}

test('P2 reloads fresh governance and the server Action Whitelist on every evaluation', () => {
  const evaluator = source(coreRoot, 'ControlledAutomationGovernanceEvaluator');

  assert.match(evaluator, /snapshotSource\.load\s*\(/);
  assert.match(evaluator, /whitelistSupplier\.get\s*\(/);
  assert.match(evaluator, /currentContext/);
  assert.match(evaluator, /currentResource/);
  assert.match(evaluator, /currentPolicy/);
  assert.match(evaluator, /rolesEvidenceHash/);
  assert.match(evaluator, /authorizationEvidenceHash/);
  assert.match(evaluator, /separationOfDutiesAllowed/);
  assert.match(evaluator, /killSwitchActive/);
  assert.match(evaluator, /commandPreconditionsSatisfied/);
});

test('P2 exposes only the closed read-only decision set', () => {
  const evaluator = source(coreRoot, 'ControlledAutomationGovernanceEvaluator');

  for (const decision of [
    'ELIGIBLE',
    'INELIGIBLE',
    'EXPIRED',
    'STALE',
    'POLICY_BLOCKED',
    'AUTHORIZATION_DENIED',
    'SOURCE_EVIDENCE_INVALID',
    'ACTION_NOT_WHITELISTED',
    'REAUTHENTICATION_REQUIRED',
  ]) {
    assert.match(evaluator, new RegExp(`\\b${decision}\\b`));
  }
  assert.match(evaluator, /READ_ONLY_NON_EXECUTING_PREVIEW/);
  assert.match(evaluator, /businessSideEffectProduced/);
  assert.match(evaluator, /providerInvoked/);
  assert.match(evaluator, /connectorInvoked/);
  assert.match(evaluator, /commandAttempted/);
  assert.doesNotMatch(evaluator, /\b(EXECUTE|EXECUTING|EXECUTED|SUCCEEDED)\b/);
});

test('P2 fails closed for authority, evidence, whitelist, policy and state drift', () => {
  const evaluator = source(coreRoot, 'ControlledAutomationGovernanceEvaluator');

  for (const reason of [
    'TENANT_EVIDENCE_MISMATCH',
    'OPERATOR_EVIDENCE_MISMATCH',
    'PROPOSAL_NOT_ACTIVE',
    'PROPOSAL_EXPIRED',
    'SOURCE_EVIDENCE_MISSING',
    'SOURCE_EVIDENCE_MISMATCH',
    'SOURCE_EVIDENCE_INTEGRITY_INVALID',
    'WHITELIST_VERSION_DRIFT',
    'ACTION_MISSING_FROM_WHITELIST',
    'ACTION_DEFINITION_DRIFT',
    'POLICY_VERSION_DRIFT',
    'POLICY_DENIED',
    'FEATURE_DISABLED',
    'KILL_SWITCH_ACTIVE',
    'PERMISSION_REVOKED',
    'RESOURCE_AUTHORIZATION_DENIED',
    'RESOURCE_EVIDENCE_DRIFT',
    'RESOURCE_STATE_DRIFT',
    'RESOURCE_VERSION_DRIFT',
    'SEPARATION_OF_DUTIES_DENIED',
    'COMMAND_PRECONDITION_FAILED',
    'REAUTHENTICATION_REQUIRED',
  ]) {
    assert.match(evaluator, new RegExp(reason));
  }
});

test('P2 source has no Provider, connector, Flowable, mutation or scheduling authority', () => {
  const production = [
    source(coreRoot, 'ControlledAutomationGovernanceSnapshotSource'),
    source(coreRoot, 'ControlledAutomationGovernanceEvaluator'),
  ].join('\n');

  assert.doesNotMatch(production, /AiAdvisoryProvider|\.advise\s*\(/);
  assert.doesNotMatch(production, /ConnectorInvocation|ConnectorProvider/);
  assert.doesNotMatch(production, /ApprovalMessageService|PurchasePaymentTaskActionService/);
  assert.doesNotMatch(production, /RuntimeService|TaskService|ProcessMigrationService|ACT_/);
  assert.doesNotMatch(production, /JdbcTemplate|DataSource|@Transactional/);
  assert.doesNotMatch(production, /HttpClient|WebClient|RestClient|java\.net/);
  assert.doesNotMatch(production, /@Scheduled|TaskScheduler|SchedulingConfigurer/);
});

test('P2 tests prove fresh reload and fail-closed decision matrix', () => {
  const tests = source(testRoot, 'ControlledAutomationGovernanceEvaluatorTest');

  assert.match(tests, /everyEvaluationReloadsFreshGovernanceAndWhitelistAndRemainsReadOnly/);
  assert.match(tests, /currentEmptyWhitelistFailsActionNotWhitelisted/);
  assert.match(tests, /forgedTenantAndOperatorFailClosed/);
  assert.match(tests, /inactiveExpiredAndLineageTamperedProposalFailClosed/);
  assert.match(tests, /deletedMismatchedAndTamperedSourceEvidenceFailClosed/);
  assert.match(tests, /whitelistVersionAndDefinitionDriftFailClosed/);
  assert.match(tests, /policyAuthorizationStateAndHumanGatesFailClosed/);
  assert.match(tests, /evaluationEvidenceBindsFreshSnapshotAndStateComparison/);
});