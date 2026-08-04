import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function source(relativePath) {
  const file = path.join(root, relativePath);
  assert.equal(existsSync(file), true, `${relativePath} must exist`);
  return readFileSync(file, 'utf8');
}

const runbook = source('docs/m6/M6_E_P6_F_OPENAI_INCIDENT_RUNBOOK.md');
const providerFaultTest = source(
  'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesProductionFaultMatrixTest.java',
);
const failureContractTest = source(
  'apps/server/src/test/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationFailureContractTest.java',
);
const incidentConfigTest = source(
  'apps/server/src/test/java/io/github/akaryc1b/approval/config/ApprovalAssistanceProductionIncidentConfigurationTest.java',
);

test('P6-F runbook freezes emergency disable rollback and safe evidence', () => {
  assert.match(runbook, /APPROVAL_AI_OPENAI_ENABLED=false/);
  assert.match(runbook, /roll or restart every approval server instance/i);
  assert.match(runbook, /AI_ASSISTANCE_DISABLED/);
  assert.match(runbook, /accepted P5 GET remains zero-egress/i);
  assert.match(runbook, /cannot be recalled/i);
  assert.match(runbook, /Never send a second Provider\s+request automatically/i);
  assert.match(runbook, /Do not run a V49 down migration/i);
  assert.match(runbook, /V49 is additive durable evidence/i);
  assert.match(runbook, /two independent human approvals/i);
  assert.match(runbook, /OPENAI_API_KEY_VERSION/);
  assert.match(runbook, /Never retain:[\s\S]*OPENAI_API_KEY/);
  assert.match(runbook, /CI must not read `OPENAI_API_KEY`/);
  assert.match(runbook, /must not call `api\.openai\.com`/);
});

test('P6-F exhausts transport failures with one stable non-retryable attempt', () => {
  assert.match(
    providerFaultTest,
    /OpenAiResponsesTransportException\.Failure\.values\(\)/,
  );
  assert.match(providerFaultTest, /assertEquals\(1, calls\.get\(\)/);
  assert.match(providerFaultTest, /assertFalse\(outcome\.failure\(\)\.retryable\(\)/);
  assert.match(providerFaultTest, /List\.of\(401, 403, 429, 500, 503\)/);
  assert.match(providerFaultTest, /AI_OPENAI_DISABLED/);
  assert.match(providerFaultTest, /AI_OPENAI_POLICY_BLOCKED/);
  assert.match(providerFaultTest, /AI_OPENAI_TIMEOUT/);
  assert.match(providerFaultTest, /AI_OPENAI_REQUEST_REJECTED/);
  assert.match(providerFaultTest, /AI_OPENAI_PROVIDER_UNAVAILABLE/);
  assert.match(providerFaultTest, /AI_OPENAI_UNKNOWN/);
  assert.match(providerFaultTest, /provider-sensitive-body/);
  assert.match(providerFaultTest, /assertFalse\(outcome\.toString\(\)\.contains\(body\)\)/);
});

test('P6-F public failure contract is no-store advisory-only and complete', () => {
  for (const status of [
    'DISABLED',
    'NOT_FOUND',
    'STALE_TASK',
    'POLICY_BLOCKED',
    'PROVIDER_UNAVAILABLE',
    'TIMEOUT',
    'INVALID_OUTPUT',
    'UNKNOWN',
    'EVIDENCE_CONFLICT',
    'EVIDENCE_UNAVAILABLE',
  ]) {
    assert.match(failureContractTest, new RegExp(`GenerationStatus\\.${status}`));
  }
  for (const code of [
    'AI_ASSISTANCE_DISABLED',
    'AI_ASSISTANCE_NOT_FOUND',
    'AI_ASSISTANCE_STALE_TASK',
    'AI_ASSISTANCE_POLICY_BLOCKED',
    'AI_ASSISTANCE_PROVIDER_UNAVAILABLE',
    'AI_ASSISTANCE_TIMEOUT',
    'AI_ASSISTANCE_INVALID_OUTPUT',
    'AI_ASSISTANCE_UNKNOWN',
    'AI_ASSISTANCE_EVIDENCE_CONFLICT',
    'AI_ASSISTANCE_EVIDENCE_UNAVAILABLE',
  ]) {
    assert.match(failureContractTest, new RegExp(code));
  }
  assert.match(failureContractTest, /assertEquals\("no-store"/);
  assert.match(failureContractTest, /Authority\.ADVISORY/);
  assert.match(failureContractTest, /AssertionStatus\.UNVERIFIED_ADVISORY/);
  assert.match(failureContractTest, /assertTrue\(response\.getBody\(\)\.needsHumanReview\(\)\)/);
  assert.match(failureContractTest, /assertFalse\(response\.getBody\(\)\.retryAttempted\(\)\)/);
  assert.match(failureContractTest, /assertFalse\(response\.getBody\(\)\.fallbackAttempted\(\)\)/);
  assert.match(failureContractTest, /assertNull\(response\.getBody\(\)\.evidenceId\(\)\)/);
  assert.match(failureContractTest, /assertNull\(response\.getBody\(\)\.advisoryResult\(\)\)/);
});

test('P6-F configuration drills prove emergency disable and exact rotation', () => {
  assert.match(incidentConfigTest, /APPROVAL_AI_OPENAI_ENABLED", "false"/);
  assert.match(incidentConfigTest, /assertTrue\(runtime\.isEmpty\(\)\)/);
  assert.match(incidentConfigTest, /expiredSecretVersionBlocksRuntimeActivation/);
  assert.match(incidentConfigTest, /futureCostPolicyBlocksRuntimeActivation/);
  assert.match(
    incidentConfigTest,
    /AI production version policy is not currently valid/,
  );
  assert.match(incidentConfigTest, /secretRotationRequiresAChangedExactVersionReference/);
  assert.match(incidentConfigTest, /"key-v1"/);
  assert.match(incidentConfigTest, /"key-v2"/);
  assert.doesNotMatch(incidentConfigTest, /OPENAI_API_KEY"/);
});

test('P6-F retains the accepted production path without operational bypasses', () => {
  const paths = [
    'server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesAdvisoryProvider.java',
    'server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesProductionRuntimeFactory.java',
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationService.java',
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationController.java',
    'apps/server/src/main/java/io/github/akaryc1b/approval/config/ApprovalAssistanceProductionConfiguration.java',
  ];
  const production = paths.map(source).join('\n');

  assert.doesNotMatch(production, /@Scheduled|RetryTemplate|setInterval|CompletableFuture\.delayedExecutor/);
  assert.doesNotMatch(production, /fallbackProvider|secondProvider|alternateEndpoint/i);
  assert.doesNotMatch(production, /approve\s*\(|reject\s*\(|withdraw\s*\(|terminate\s*\(/);
  assert.doesNotMatch(production, /ACT_[A-Z_]+/);
  assert.doesNotMatch(production, /previous_response_id\s*[,=]\s*["'][^"']+/i);
  assert.doesNotMatch(production, /["']conversation["']\s*[,=]\s*["'][^"']+/i);
});

test('P6-F creates no V50 migration or second automatic workflow', () => {
  const migrationRoot = path.join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
  );
  const migrations = readdirSync(migrationRoot);
  assert.equal(migrations.some(name => /^V(?:5[0-9]|[6-9][0-9])__/.test(name)), false);
  assert.equal(migrations.filter(name => /^V49__/.test(name)).length, 1);

  const workflowRoot = path.join(root, '.github/workflows');
  const automatic = readdirSync(workflowRoot)
    .filter(name => /\.ya?ml$/.test(name))
    .filter(name => {
      const content = readFileSync(path.join(workflowRoot, name), 'utf8');
      return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
    });
  assert.deepEqual(automatic, ['approval-platform-validation.yml']);
});

test('permanent transport review loads the P6-F incident boundary', () => {
  const aggregator = source('scripts/tests/m6-ai-transport-review-boundary.test.mjs');
  assert.match(
    aggregator,
    /import '\.\/m6-e-p6-openai-fault-incident-boundary\.test\.mjs';/,
  );
  assert.match(runbook, /P6_F_FAULT_SECURITY_INCIDENT_ONLY/);
  assert.match(runbook, /ZERO_EGRESS_DETERMINISTIC_DRILLS/);
  assert.match(runbook, /V49_EVIDENCE_PRESERVED_ON_ROLLBACK/);
  assert.match(runbook, /AI_IS_NOT_AN_OPERATOR/);
});
