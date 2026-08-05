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

function text(relativePath) {
  const file = path.join(root, relativePath);
  assert.equal(existsSync(file), true, `${relativePath} must exist`);
  return readFileSync(file, 'utf8');
}

test('P3 explicit confirmation is non-executable and requires reauthentication', () => {
  const service = source(coreRoot, 'ControlledAutomationConfirmationService');
  const verifier = source(coreRoot, 'ControlledAutomationReauthenticationVerifier');

  assert.match(service, /ConfirmationIntent\.EXPLICIT_CLICK/);
  assert.match(service, /EXPLICIT_CLICK_REQUIRED/);
  assert.match(service, /REAUTHENTICATION_UNAVAILABLE/);
  assert.match(service, /REAUTHENTICATION_EXPIRED/);
  assert.match(service, /REAUTHENTICATION_FAILED/);
  assert.match(service, /NON_EXECUTABLE_CONFIRMATION/);
  assert.match(service, /singleUseRequired/);
  assert.match(service, /commandAdmitted/);
  assert.match(verifier, /static ControlledAutomationReauthenticationVerifier unavailable/);
  assert.match(verifier, /VerificationStatus\.UNAVAILABLE/);
});

test('P3 confirmation binding includes exact authority, state and governance evidence', () => {
  const service = source(coreRoot, 'ControlledAutomationConfirmationService');

  for (const binding of [
    'proposalId',
    'tenantEvidenceHash',
    'operatorEvidenceHash',
    'sourceEvidenceHash',
    'canonicalActionType',
    'typedParameterHash',
    'resourceEvidenceHash',
    'whitelistVersion',
    'policyVersion',
    'evaluationEvidenceHash',
    'reauthenticationEvidenceHash',
    'reauthenticationChallengeId',
    'confirmedAt',
    'expiresAt',
  ]) {
    assert.match(service, new RegExp(binding));
  }
  assert.match(service, /evaluation\.decision\(\) != EvaluationDecision\.ELIGIBLE/);
  assert.match(service, /proposal\.expiresAt\(\)\.isAfter\(now\)/);
  assert.match(service, /evaluation\.currentWhitelistVersion\(\)/);
});

test('P3 has no Provider, connector, Flowable, persistence or command execution path', () => {
  const production = [
    source(coreRoot, 'ControlledAutomationReauthenticationVerifier'),
    source(coreRoot, 'ControlledAutomationConfirmationService'),
  ].join('\n');

  assert.doesNotMatch(production, /AiAdvisoryProvider|\.advise\s*\(/);
  assert.doesNotMatch(production, /ConnectorInvocation|ConnectorProvider/);
  assert.doesNotMatch(production, /ApprovalMessageService|PurchasePaymentTaskActionService/);
  assert.doesNotMatch(production, /RuntimeService|TaskService|ProcessMigrationService|ACT_/);
  assert.doesNotMatch(production, /JdbcTemplate|DataSource|@Transactional/);
  assert.doesNotMatch(production, /HttpClient|WebClient|RestClient|java\.net/);
  assert.doesNotMatch(production, /@Scheduled|TaskScheduler|SchedulingConfigurer/);
});

test('P3 tests prove unavailable production reauthentication and explicit-click boundary', () => {
  const tests = source(testRoot, 'ControlledAutomationConfirmationServiceTest');

  assert.match(tests, /currentUnavailableReauthenticationBlocksConfirmation/);
  assert.match(tests, /pageLoadEnterTimerRetryAndTabChangeCannotConfirm/);
  assert.match(tests, /exactProposalEvaluationIdentityAndChallengeBindingsAreMandatory/);
  assert.match(tests, /expiredChallengeAndFailedVerificationCannotConfirm/);
  assert.match(tests, /acceptedTestVerifierCreatesOnlyShortLivedNonExecutableEvidence/);
  assert.match(tests, /confirmationContractContainsNoCredentialOrCommandPayload/);
});

test('PC and Mobile display the same fail-closed confirmation semantics', () => {
  const webPanel = text(
    'apps/web/overlay/apps/web-ele/src/components/approval/ControlledAutomationConfirmationBoundary.vue',
  );
  const mobilePanel = text(
    'apps/mobile/overlay/src/components/approval/ControlledAutomationConfirmationBoundary.vue',
  );
  const webAssistance = text(
    'apps/web/overlay/apps/web-ele/src/components/approval/ApprovalAssistancePanel.vue',
  );
  const mobileAssistance = text(
    'apps/mobile/overlay/src/components/approval/ApprovalAssistancePanel.vue',
  );

  for (const required of [
    'AI_IS_NOT_AN_OPERATOR',
    'NOT_AUTHORIZED',
    'ACTION_NOT_WHITELISTED',
    'EMPTY_PENDING_EXISTING_COMMAND_AUDIT',
    'UNAVAILABLE',
    '确认不可用',
    '确认成功不等于命令成功',
  ]) {
    assert.match(webPanel, new RegExp(required));
    assert.match(mobilePanel, new RegExp(required));
  }
  assert.match(webPanel, /<ElButton disabled/);
  assert.match(mobilePanel, /<wd-button block disabled/);
  assert.doesNotMatch(webPanel, /@click|@keyup|@submit/);
  assert.doesNotMatch(mobilePanel, /@click|@confirm|@submit/);
  assert.match(webAssistance, /<ControlledAutomationConfirmationBoundary \/>/);
  assert.match(mobileAssistance, /<ControlledAutomationConfirmationBoundary \/>/);
});
