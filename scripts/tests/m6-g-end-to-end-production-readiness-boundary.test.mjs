import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const acceptancePath = 'docs/m6/M6_G_END_TO_END_PRODUCTION_READINESS_ACCEPTANCE.md';

function read(relativePath) {
  const file = path.join(root, relativePath);
  assert.equal(existsSync(file), true, `${relativePath} must exist`);
  return readFileSync(file, 'utf8');
}

const acceptance = read(acceptancePath);

const scenarios = [
  ['G2-01', 'Connector and AI remain tenant-isolated',
    'apps/server/src/main/java/io/github/akaryc1b/approval/config/ControlledAutomationGovernanceConfiguration.java',
    'factory.usageSnapshot(trustedTenantId)'],
  ['G2-02', 'Connector Secret cannot enter AI evidence',
    'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/V49__create_ai_approval_assistance_durable_evidence.sql',
    'ap_ai_approval_assistance_evidence'],
  ['G2-03', 'AI metadata cannot enter Connector credentials',
    'server-modules/approval-connector-credential-core/src/main/java/io/github/akaryc1b/approval/connector/credential/CredentialMaterialRequest.java',
    'credentialReferenceHash'],
  ['G2-04', 'SDK and Event payloads carry no trusted authority',
    'scripts/tests/m6-sdk-event-boundary.test.mjs',
    'public client requests cannot manufacture trusted server evidence'],
  ['G2-05', 'Template versions align with AI provenance',
    'apps/server/src/test/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationServiceTest.java',
    'projectionUsesExactTrustedReleaseFormAndUiProvenance'],
  ['G2-06', 'AI consumes exact Release Form and UI evidence',
    'apps/server/src/test/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationServiceTest.java',
    'projection.form().uiSchemaVersion()'],
  ['G2-07', 'Stale post-Provider task writes zero evidence',
    'apps/server/src/test/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationServiceTest.java',
    'changedTaskAfterProviderFailsBeforeEvidenceStore'],
  ['G2-08', 'Post-dispatch UNKNOWN remains one attempt',
    'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesPostDispatchUnknownAcceptanceTest.java',
    'timeoutAndIoFailureAfterDispatchRemainSingleAttemptUnknown'],
  ['G2-09', 'AI evidence cannot become command authority',
    'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/ApprovalAssistanceDurableEvidence.java',
    'Hash-only durable evidence'],
  ['G2-10', 'Empty whitelist cannot be bypassed',
    'docs/m6/M6_F_ACTION_WHITELIST_DECISION.md',
    'EMPTY_PENDING_EXISTING_COMMAND_AUDIT'],
  ['G2-11', 'Unavailable reauthentication blocks confirmation',
    'server-modules/approval-ai-core/src/test/java/io/github/akaryc1b/approval/ai/core/ControlledAutomationConfirmationServiceTest.java',
    'currentUnavailableReauthenticationBlocksConfirmationWithoutAllocatingId'],
  ['G2-12', 'Governance reads create no Runtime Binding',
    'apps/server/src/main/java/io/github/akaryc1b/approval/config/ControlledAutomationGovernanceConfiguration.java',
    'factory.controlSnapshot()'],
  ['G2-13', 'Incident readiness does not invoke Provider',
    'apps/server/src/main/java/io/github/akaryc1b/approval/config/ControlledAutomationGovernanceConfiguration.java',
    'ReviewPlan.preview'],
  ['G2-14', 'Circuit OPEN is not healthy',
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ControlledAutomationGovernanceControlHealthContracts.java',
    'AI_PROVIDER_CIRCUIT_OPEN'],
  ['G2-15', 'Circuit HALF_OPEN is not healthy',
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ControlledAutomationGovernanceControlHealthContracts.java',
    'AI_PROVIDER_CIRCUIT_HALF_OPEN'],
  ['G2-16', 'Tenant saturation leaks no other tenant',
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ControlledAutomationGovernanceUsageContracts.java',
    'otherTenantUsageExposed'],
  ['G2-17', 'Global saturation leaks no exact count',
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ControlledAutomationGovernanceUsageContracts.java',
    'globalExactUsageExposed'],
  ['G2-18', 'History failure performs no repair write',
    'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalAssistanceGovernanceHistoryFaultIntegrationTest.java',
    'unavailableV49TableReturnsNoPartialSummaryAndPerformsNoRepairWrite'],
  ['G2-19', 'Web generation is explicit',
    'apps/web/overlay/apps/web-ele/src/components/approval/ApprovalAssistancePanel.vue',
    '@click'],
  ['G2-20', 'Mobile generation is explicit',
    'apps/mobile/overlay/src/components/approval/ApprovalAssistancePanel.vue',
    '@click'],
  ['G2-21', 'Event replay has collision-free identity',
    'packages/approval-sdk/src/index.ts',
    'const key = JSON.stringify([keyReference, nonce])'],
  ['G2-22', 'Clean install reaches V50',
    'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationUpgradeIntegrationTest.java',
    'approval_latest_fresh'],
  ['G2-23', 'Historical upgrades reach V50',
    'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationUpgradeIntegrationTest.java',
    'CURRENT_LATEST_VERSION = "50"'],
  ['G2-24', 'PostgreSQL locking and replay remain native',
    'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcControlledAutomationLineageStore.java',
    'for update'],
  ['G2-25', 'Nearest-microsecond rounding remains exact',
    'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcControlledAutomationLineageInstantPrecisionIntegrationTest.java',
    'postgresqlRoundsToNearestMicrosecondAndCarriesIntoNextSecond'],
  ['G2-26', 'The single permanent Workflow remains complete',
    '.github/workflows/approval-platform-validation.yml',
    'name: Approval Platform Validation'],
  ['G2-27', 'Four Artifact classes remain reconstructable',
    '.github/workflows/approval-platform-validation.yml',
    'approval-maven-${{ github.run_id }}'],
  ['G2-28', 'Production AI has no Flowable internal access',
    'scripts/tests/m6-f-p8-g1-audit-boundary.test.mjs',
    'ACT_[A-Z0-9_]+'],
  ['G2-29', 'No Provider-to-command path exists',
    'scripts/tests/m6-f-p8-g1-audit-boundary.test.mjs',
    'ApprovalTaskCommandService'],
  ['G2-30', 'Clients cannot forge trusted permissions',
    'scripts/tests/approval-client-boundary.test.mjs',
    'X-Approval-Trusted-Permissions'],
];

for (const [id, title, file, token] of scenarios) {
  test(`${id} ${title}`, () => {
    assert.match(acceptance, new RegExp('\\| `' + id + '` \\|[^\\n]*\\| `PASS` \\|'));
    assert.equal(read(file).includes(token), true, `${id} missing evidence token: ${token}`);
  });
}

test('G2 preserves explicit client invocation and no automatic generation', () => {
  for (const file of [
    'apps/web/overlay/apps/web-ele/src/components/approval/ApprovalAssistancePanel.vue',
    'apps/mobile/overlay/src/components/approval/ApprovalAssistancePanel.vue',
  ]) {
    const panel = read(file);
    assert.equal([...panel.matchAll(/generateApprovalAssistance\s*\(/g)].length, 1);
    assert.match(panel, /ADVISORY/);
    assert.match(panel, /UNVERIFIED_ADVISORY/);
    assert.doesNotMatch(panel, /onMounted\s*\(\s*generateAssistance|watchEffect\s*\(\s*generateAssistance/);
  }
});

test('G2 preserves one automatic workflow and unique V49 V50', () => {
  const workflowRoot = path.join(root, '.github/workflows');
  const automatic = readdirSync(workflowRoot)
    .filter(name => /\.ya?ml$/.test(name))
    .filter((name) => /^\s{0,4}(pull_request|push):\s*$/m.test(
      readFileSync(path.join(workflowRoot, name), 'utf8'),
    ));
  assert.deepEqual(automatic, ['approval-platform-validation.yml']);

  const resourceRoot = path.join(root, 'server-modules/approval-persistence-jdbc/src/main/resources');
  const versions = [path.join(resourceRoot, 'db/migration'), path.join(resourceRoot, 'm6f/db/migration')]
    .flatMap(directory => readdirSync(directory))
    .map(name => /^V(\d+)__.+\.sql$/.exec(name))
    .filter(Boolean)
    .map(match => Number(match[1]));
  assert.equal(versions.filter(version => version === 49).length, 1);
  assert.equal(versions.filter(version => version === 50).length, 1);
  assert.equal(versions.some(version => version >= 51), false);
});

test('G2 final decision separates acceptance from Production Readiness', () => {
  assert.equal(scenarios.length, 30);
  assert.match(acceptance, /Scenario result: `30 \/ 30 PASS`/);
  assert.match(acceptance, /M6_G_END_TO_END_ACCEPTANCE_PASSED/);
  assert.match(acceptance, /M6_PRODUCTION_READINESS_BLOCKED/);
  assert.match(acceptance, /EMPTY_PENDING_EXISTING_COMMAND_AUDIT/);
  assert.match(acceptance, /P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND/);
  assert.match(acceptance, /ISSUE_82_REMAINS_OPEN/);
  assert.match(acceptance, /ISSUE_62_REMAINS_OPEN/);
  assert.match(acceptance, /AI_IS_NOT_AN_OPERATOR/);
});
