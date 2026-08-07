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

function scenario(id, title, evidence = {}) {
  test(`${id} ${title}`, () => {
    assert.match(
      acceptance,
      new RegExp('\\| `' + id + '` \\|[^\\n]*\\| `PASS` \\|'),
    );
    const content = (evidence.files ?? []).map(read).join('\n');
    for (const token of evidence.includes ?? []) {
      assert.equal(content.includes(token), true, `${id} missing evidence token: ${token}`);
    }
    for (const pattern of evidence.matches ?? []) {
      assert.match(content, pattern, `${id} evidence pattern is missing`);
    }
    for (const pattern of evidence.excludes ?? []) {
      assert.doesNotMatch(content, pattern, `${id} contains prohibited evidence`);
    }
    evidence.verify?.(content);
  });
}

scenario('G2-01', 'Connector and AI Runtime remain tenant-isolated', {
  files: [
    'apps/server/src/main/java/io/github/akaryc1b/approval/config/ControlledAutomationGovernanceConfiguration.java',
    'docs/m6/M6_A_FINAL_ACCEPTANCE.md',
  ],
  includes: ['trustedTenantId', 'factory.usageSnapshot(trustedTenantId)', 'tenant routing'],
});

scenario('G2-02', 'Connector Secret Material cannot enter AI durable evidence', {
  files: [
    'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/V49__create_ai_approval_assistance_durable_evidence.sql',
    'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/ApprovalAssistanceDurableEvidence.java',
  ],
  includes: ['Hash-only durable evidence', 'ap_ai_approval_assistance_evidence'],
  excludes: /\b(secret_value|api_key|credential_material|raw_request|raw_response|prompt_body)\b/i,
});

scenario('G2-03', 'AI metadata cannot enter Connector credential material requests', {
  files: [
    'server-modules/approval-connector-credential-core/src/main/java/io/github/akaryc1b/approval/connector/credential/CredentialMaterialRequest.java',
  ],
  includes: ['Internal server-owned exact material request', 'credentialReferenceHash'],
  excludes: /approval\.ai|modelVersion|promptVersion|outputSchemaVersion|knowledgeSourceVersion/,
});

scenario('G2-04', 'SDK and Event payloads contain no Secret or trusted authority', {
  files: ['scripts/tests/m6-sdk-event-boundary.test.mjs'],
  includes: [
    'public client requests cannot manufacture trusted server evidence',
    "forbid(javaCredential, ['secret', 'password', 'privateKey', 'headerValue', 'credentialMaterial'])",
  ],
});

scenario('G2-05', 'Template and Component versions align with AI Form and UI provenance', {
  files: [
    'apps/server/src/test/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationServiceTest.java',
  ],
  includes: [
    'projectionUsesExactTrustedReleaseFormAndUiProvenance',
    'FORM_PACKAGE_HASH',
    'UI_SCHEMA_HASH',
  ],
});

scenario('G2-06', 'AI Assistance consumes exact Release Form and UI Schema evidence', {
  files: [
    'apps/server/src/test/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationServiceTest.java',
  ],
  includes: [
    'RELEASE_VERSION',
    'FORM_CONTENT_HASH',
    'FORM_SCHEMA_FIELD_COUNT',
    'projection.form().uiSchemaVersion()',
  ],
});

scenario('G2-07', 'Post-Provider stale Task writes zero durable evidence', {
  files: [
    'apps/server/src/test/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationServiceTest.java',
  ],
  includes: [
    'changedTaskAfterProviderFailsBeforeEvidenceStore',
    'missingTaskAfterProviderFailsBeforeEvidenceStore',
    'assertEquals(0, store.writes.get())',
  ],
});

scenario('G2-08', 'Post-dispatch UNKNOWN remains one Provider attempt', {
  files: [
    'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesPostDispatchUnknownAcceptanceTest.java',
  ],
  includes: [
    'timeoutAndIoFailureAfterDispatchRemainSingleAttemptUnknown',
    'assertEquals(1, fixture.network().exchangeCount.get())',
    'OpenAiResponsesTransportException.Failure.UNKNOWN',
  ],
});

scenario('G2-09', 'AI evidence cannot become command authority', {
  files: [
    'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/ApprovalAssistanceDurableEvidence.java',
  ],
  includes: ['Hash-only durable evidence', 'Provider attempts must be zero or one'],
  excludes: /commandAdmitted|commandPayload|approvalDecision|ApprovalTaskCommandService/,
});

scenario('G2-10', 'Controlled Automation cannot bypass the empty whitelist', {
  files: [
    'docs/m6/M6_F_ACTION_WHITELIST_DECISION.md',
    'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/ControlledAutomationActionWhitelist.java',
  ],
  includes: [
    'EMPTY_PENDING_EXISTING_COMMAND_AUDIT',
    'P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND',
    'return Optional.empty()',
  ],
});

scenario('G2-11', 'Confirmation cannot bypass unavailable Production Reauthentication', {
  files: [
    'server-modules/approval-ai-core/src/test/java/io/github/akaryc1b/approval/ai/core/ControlledAutomationConfirmationServiceTest.java',
  ],
  includes: [
    'currentUnavailableReauthenticationBlocksConfirmationWithoutAllocatingId',
    'ConfirmationDisposition.REAUTHENTICATION_UNAVAILABLE',
    'assertEquals(0, identifiers.get())',
  ],
});

scenario('G2-12', 'Governance reads create no Runtime Binding', {
  files: [
    'apps/server/src/main/java/io/github/akaryc1b/approval/config/ControlledAutomationGovernanceConfiguration.java',
  ],
  includes: ['factory.controlSnapshot()', 'factory.usageSnapshot(trustedTenantId)'],
  excludes: /\.bind\s*\(/,
});

scenario('G2-13', 'Incident Readiness does not invoke the Provider', {
  files: [
    'apps/server/src/main/java/io/github/akaryc1b/approval/config/ControlledAutomationGovernanceConfiguration.java',
  ],
  includes: ['controlledAutomationGovernanceIncidentReadinessSource', 'ReviewPlan.preview'],
  excludes: /\.exchange\s*\(|\.advise\s*\(|orchestrator\.execute\s*\(/,
});

scenario('G2-14', 'Circuit OPEN is a blocker rather than healthy', {
  files: [
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ControlledAutomationGovernanceControlHealthContracts.java',
  ],
  includes: ['CircuitHealth.OPEN', 'AI_PROVIDER_CIRCUIT_OPEN'],
});

scenario('G2-15', 'Circuit HALF_OPEN is a blocker rather than healthy', {
  files: [
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ControlledAutomationGovernanceControlHealthContracts.java',
  ],
  includes: ['CircuitHealth.HALF_OPEN', 'AI_PROVIDER_CIRCUIT_HALF_OPEN'],
});

scenario('G2-16', 'Tenant saturation leaks no other-tenant usage', {
  files: [
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ControlledAutomationGovernanceUsageContracts.java',
  ],
  includes: [
    'TENANT_RATE_WINDOW_SATURATED',
    'otherTenantUsageExposed',
    'if (globalExactUsageExposed',
  ],
});

scenario('G2-17', 'Global saturation leaks no exact global usage count', {
  files: [
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ControlledAutomationGovernanceUsageContracts.java',
  ],
  includes: [
    'GLOBAL_RATE_WINDOW_SATURATED',
    'globalExactUsageExposed',
    'if (globalExactUsageExposed',
  ],
});

scenario('G2-18', 'Durable history failure performs no repair write', {
  files: [
    'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalAssistanceGovernanceHistoryFaultIntegrationTest.java',
  ],
  includes: [
    'unavailableV49TableReturnsNoPartialSummaryAndPerformsNoRepairWrite',
    'assertEquals(stateBefore',
    'assertEquals(eventBefore',
  ],
});

for (const [id, client, file] of [
  ['G2-19', 'Web', 'apps/web/overlay/apps/web-ele/src/components/approval/ApprovalAssistancePanel.vue'],
  ['G2-20', 'Mobile', 'apps/mobile/overlay/src/components/approval/ApprovalAssistancePanel.vue'],
]) {
  scenario(id, `${client} generation remains explicit and non-automatic`, {
    files: [file],
    includes: ['ADVISORY', 'UNVERIFIED_ADVISORY', '@click'],
    verify(content) {
      assert.equal(
        [...content.matchAll(/generateApprovalAssistance\s*\(/g)].length,
        1,
        `${id} must retain exactly one explicit Provider generation call`,
      );
      assert.doesNotMatch(content, /onMounted\s*\(\s*generateAssistance|watchEffect\s*\(\s*generateAssistance/);
    },
  });
}

scenario('G2-21', 'Event replay creates no duplicate AI Connector or Template side effect', {
  files: [
    'packages/approval-sdk/src/index.ts',
    'scripts/tests/m6-sdk-event-boundary.test.mjs',
  ],
  includes: [
    'const key = JSON.stringify([keyReference, nonce])',
    "if (store.contains(event.eventId)) return 'duplicate'",
    'SDK source contains no subscription persistence or delivery worker',
    'SDK source exposes no Flowable or M5 migration execution API',
  ],
});

scenario('G2-22', 'Clean PostgreSQL install reaches V50 without execution side effects', {
  files: [
    'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationUpgradeIntegrationTest.java',
  ],
  includes: [
    'new UpgradeCase("approval_latest_fresh", null)',
    'freshAndHistoricalUpgradePathsReachV50WithoutExecutionSideEffects',
    'assertNoExecutionSideEffects',
  ],
});

scenario('G2-23', 'Historical PostgreSQL upgrades reach V50 without evidence mutation', {
  files: [
    'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationUpgradeIntegrationTest.java',
  ],
  includes: [
    'CURRENT_LATEST_VERSION = "50"',
    'upgradesV27WithFiveThousandInstancesAndTasksWithoutChangingEvidence',
    'JdbcApprovalMigrationUpgradeSupport.assertProjectionEvidence(jdbc, 5_000)',
  ],
});

scenario('G2-24', 'PostgreSQL locking CAS and replay remain native', {
  files: [
    'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcControlledAutomationLineageStore.java',
    'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcControlledAutomationLineageConcurrencyAcceptanceTest.java',
  ],
  includes: ['for update', 'TransactionTemplate', 'CountDownLatch'],
});

scenario('G2-25', 'PostgreSQL nearest-microsecond rounding remains exact', {
  files: [
    'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcControlledAutomationLineageInstantPrecisionIntegrationTest.java',
    'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcControlledAutomationLineageStore.java',
  ],
  includes: [
    'postgresqlRoundsToNearestMicrosecondAndCarriesIntoNextSecond',
    '123456500Z',
    '999999500Z',
    'HALF_MICROSECOND_NANOS',
  ],
  excludes: /truncatedTo\(ChronoUnit\.MICROS\)/,
});

scenario('G2-26', 'The single permanent Workflow remains complete', {
  files: ['.github/workflows/approval-platform-validation.yml'],
  includes: [
    'name: Approval Platform Validation',
    'Java 21 / Maven core',
    'Persistence JDBC / shard',
    'Vben TypeScript / production build',
    'UniApp TypeScript / H5 / WeChat',
    'Repository hygiene',
  ],
  verify() {
    const workflowRoot = path.join(root, '.github/workflows');
    const automatic = readdirSync(workflowRoot)
      .filter(name => /\.ya?ml$/.test(name))
      .filter((name) => {
        const content = readFileSync(path.join(workflowRoot, name), 'utf8');
        return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
      });
    assert.deepEqual(automatic, ['approval-platform-validation.yml']);
  },
});

scenario('G2-27', 'Four final Artifact classes remain reconstructable', {
  files: ['.github/workflows/approval-platform-validation.yml'],
  includes: [
    'verify-persistence-jdbc-shards.py',
    'actions/upload-artifact/merge@v4',
    'approval-maven-${{ github.run_id }}',
    'approval-vben-${{ github.run_id }}',
    'approval-mobile-${{ github.run_id }}',
    'approval-hygiene-${{ github.run_id }}',
  ],
});

scenario('G2-28', 'Production AI cannot access Flowable internal tables', {
  files: ['scripts/tests/m6-f-p8-g1-audit-boundary.test.mjs'],
  includes: [
    'finds no Provider-to-command or autonomous execution path in AI production code',
    'ACT_[A-Z0-9_]+',
    'org\\.flowable',
  ],
});

scenario('G2-29', 'No Provider-to-command path exists', {
  files: ['scripts/tests/m6-f-p8-g1-audit-boundary.test.mjs'],
  includes: [
    'Provider-to-command',
    'ApprovalTaskCommandService',
    'import\\s+io\\.github\\.akaryc1b\\.approval\\.application\\.',
  ],
});

scenario('G2-30', 'Browser and Mobile cannot manufacture trusted permissions', {
  files: ['scripts/tests/approval-client-boundary.test.mjs'],
  includes: [
    'browser and mobile overlays cannot forge trusted management authorities',
    'X-Approval-Trusted-Permissions',
    'mobile overlay cannot reference tenant management endpoints',
  ],
});

test('G2 final decision remains acceptance-passed and production-readiness-blocked', () => {
  assert.match(acceptance, /Scenario result: `30 \/ 30 PASS`/);
  assert.match(acceptance, /M6_G_END_TO_END_ACCEPTANCE_PASSED/);
  assert.match(acceptance, /M6_PRODUCTION_READINESS_BLOCKED/);
  assert.match(acceptance, /EMPTY_PENDING_EXISTING_COMMAND_AUDIT/);
  assert.match(acceptance, /P5_A_SKIPPED_NO_QUA