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

const rawBoundary = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/security/'
    + 'ControlledAutomationGovernanceRequestBoundaryFilter.java',
);
const rawBoundaryTest = read(
  'apps/server/src/test/java/io/github/akaryc1b/approval/security/'
    + 'ControlledAutomationGovernanceRequestBoundaryFilterTest.java',
);
const boundaryConfiguration = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
    + 'ControlledAutomationGovernanceSecurityConfiguration.java',
);
const authorizationTest = read(
  'apps/server/src/test/java/io/github/akaryc1b/approval/api/'
    + 'ControlledAutomationGovernanceAuthorizationAdversarialTest.java',
);
const identityTest = read(
  'apps/server/src/test/java/io/github/akaryc1b/approval/security/'
    + 'ApprovalIdentityContextFilterTest.java',
);
const proposal = read(
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/'
    + 'ControlledAutomationProposal.java',
);
const confirmationTest = read(
  'server-modules/approval-ai-core/src/test/java/io/github/akaryc1b/approval/ai/core/'
    + 'ControlledAutomationConfirmationServiceTest.java',
);
const adversarialCoreTest = read(
  'server-modules/approval-ai-core/src/test/java/io/github/akaryc1b/approval/ai/core/'
    + 'ControlledAutomationAdversarialInputAcceptanceTest.java',
);
const compositeTest = read(
  'apps/server/src/test/java/io/github/akaryc1b/approval/api/'
    + 'ControlledAutomationGovernanceEvidenceAdversarialTest.java',
);
const matrix = read('docs/m6/M6_F_P7_ADVERSARIAL_FAULT_CONCURRENCY_MATRIX.md');

test('P7-A raw governance boundary rejects ambiguity before trusted identity', () => {
  assert.match(rawBoundary, /extends OncePerRequestFilter/);
  assert.match(rawBoundary, /AI_GOVERNANCE_TENANT_INVALID/);
  assert.match(rawBoundary, /AI_GOVERNANCE_QUERY_INVALID/);
  assert.match(rawBoundary, /AI_GOVERNANCE_BODY_NOT_ALLOWED/);
  assert.match(rawBoundary, /AI_GOVERNANCE_METHOD_OVERRIDE_REJECTED/);
  assert.match(rawBoundary, /AI_GOVERNANCE_METHOD_NOT_ALLOWED/);
  assert.match(rawBoundary, /getHeaders\(name\)/);
  assert.match(rawBoundary, /getParameterValues\(name\)/);
  assert.match(rawBoundary, /values\.length != 1/);
  assert.match(rawBoundary, /parameterNames/);
  assert.match(rawBoundary, /MAXIMUM_WINDOW = Duration\.ofDays\(31\)/);
  assert.match(rawBoundary, /MAXIMUM_LOOKBACK = Duration\.ofDays\(3_650\)/);
  assert.doesNotMatch(
    rawBoundary,
    /AiAdvisoryProvider|SecretMaterial|JdbcTemplate|DataSource|ApprovalMessageService|RuntimeService|TaskService|WebClient|HttpClient|RestClient/,
  );
});

test('P7-A exercises tenant query body method and correlation attacks', () => {
  for (const scenario of [
    'missingEmptyWhitespaceOverlongControlAndDuplicateTenantHeadersFailClosed',
    'historyRejectsNonCanonicalDuplicatePollutedAndOutOfRangeWindows',
    'changePlanRejectsUnknownRepeatedOverlongAndInjectedParameters',
    'requestBodiesMethodOverridesAndMutationMethodsNeverReachGovernanceSources',
    'allSixExactReadOnlyRequestsPassWithoutConsumingOrChangingInput',
    'failuresUseSafeCorrelationAndNeverEchoInjectedInput',
  ]) {
    assert.match(rawBoundaryTest, new RegExp(scenario));
  }
  assert.match(rawBoundaryTest, /duplicateTenant/);
  assert.match(rawBoundaryTest, /duplicateParameter/);
  assert.match(rawBoundaryTest, /pollutedChangePlan/);
  assert.match(rawBoundaryTest, /downstream\.get\(\)/);
});

test('P7-A tenant and reauthentication boundaries remain fail closed', () => {
  assert.match(identityTest, /principalContextOverridesForgedOperatorAndRemovesPermissionHeader/);
  assert.match(identityTest, /crossTenantClaimFailsWithoutDisclosingResourceExistence/);
  assert.match(identityTest, /request must not reach the controller/);
  assert.match(
    confirmationTest,
    /currentUnavailableReauthenticationBlocksConfirmationWithoutAllocatingId/,
  );
  assert.match(
    confirmationTest,
    /exactProposalEvaluationIdentityAndChallengeBindingsAreMandatory/,
  );
});

test('P7-A management authorization denial occurs before every source read', () => {
  for (const scenario of [
    'missingReadPermissionNeverReachesGovernanceSource',
    'departmentScopedResponsibilityCannotReadTenantGovernance',
    'exactTenantReadAuthorityAllowsOneReadOnlySourceCall',
  ]) {
    assert.match(authorizationTest, new RegExp(scenario));
  }
  assert.match(authorizationTest, /assertEquals\(0, sourceCalls\.get\(\)\)/);
  assert.match(authorizationTest, /Requirement\.READ\.authority\(\)/);
});

test('P7-A strict evidence hashes are canonical lowercase without normalization', () => {
  const hashMethod = /static String requireSha256\([\s\S]*?\n    }/.exec(proposal);
  assert.notEqual(hashMethod, null);
  assert.match(hashMethod[0], /SHA256\.matcher\(value\)\.matches\(\)/);
  assert.doesNotMatch(hashMethod[0], /toLowerCase|trim\(\)/);
  assert.match(adversarialCoreTest, /evidenceHashesRejectUppercaseWrongLengthNonHexAndWhitespace/);
  assert.match(adversarialCoreTest, /resourceAndConfirmationEvidenceCannotReuseOldHashAfterTampering/);
  assert.match(adversarialCoreTest, /promptCommandSqlShellFlowableAndConnectorInjectionCannotCreateAuthority/);
  assert.match(adversarialCoreTest, /forgedProposalTenantOperatorAndConfirmationBindingsNeverProduceEvidence/);
});

test('P7-A composite evidence cannot splice hashes signals steps or blockers', () => {
  for (const scenario of [
    'componentEvidenceHashesRejectUppercaseWrongLengthAndNonHex',
    'replacedComponentHashCannotReusePriorCompositeHash',
    'changedIncidentSignalCannotReusePriorCompositeHash',
    'changedOperatorStepCannotReusePriorCompositeHash',
    'changedBlockerCannotReusePriorCompositeHash',
  ]) {
    assert.match(compositeTest, new RegExp(scenario));
  }
  assert.match(compositeTest, /source\.evidenceHash\(\)/);
});

test('P7-A request boundary is earlier than identity and remains narrowly registered', () => {
  assert.match(boundaryConfiguration, /Ordered\.HIGHEST_PRECEDENCE \+ 10/);
  assert.match(boundaryConfiguration, /BASE_PATH \+ "\/\*"/);
  const identity = read(
    'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
      + 'ApprovalIdentitySecurityConfiguration.java',
  );
  assert.match(identity, /Ordered\.HIGHEST_PRECEDENCE \+ 20/);
});

test('P7-A keeps V50 empty whitelist and the sole automatic workflow', () => {
  assert.match(matrix, /EMPTY_PENDING_EXISTING_COMMAND_AUDIT/);
  assert.match(matrix, /P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND/);
  assert.match(matrix, /P7_A_NOT_STARTED/);

  const resourceRoot = path.join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/resources',
  );
  const migrationFiles = [
    path.join(resourceRoot, 'db/migration'),
    path.join(resourceRoot, 'm6f/db/migration'),
  ].flatMap((directory) => readdirSync(directory)
    .filter((name) => /^V\d+__.+\.sql$/.test(name))
    .map((name) => name));
  const versions = migrationFiles
    .map((name) => Number(/^V(\d+)__/.exec(name)?.[1]))
    .filter(Number.isFinite);
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
