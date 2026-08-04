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

const finalAcceptance = source('docs/m6/M6_E_P7_FINAL_ACCEPTANCE.md');
const threatModel = source('docs/m6/M6_E_APPROVAL_ASSISTANCE_THREAT_MODEL.md');
const p4Jdbc = source(
  'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalAssistanceDurableEvidenceStoreIntegrationTest.java',
);
const providerFaults = source(
  'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesProductionFaultMatrixTest.java',
);
const serviceTests = source(
  'apps/server/src/test/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationServiceTest.java',
);
const publicFailures = source(
  'apps/server/src/test/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationFailureContractTest.java',
);
const webPanel = source(
  'apps/web/overlay/apps/web-ele/src/components/approval/ApprovalAssistancePanel.vue',
);
const mobilePanel = source(
  'apps/mobile/overlay/src/components/approval/ApprovalAssistancePanel.vue',
);

test('P7 final acceptance freezes every accepted slice and exact rebaseline', () => {
  const documents = [
    'docs/m6/M6_E_P0_REBASELINE_AND_THREAT_MODEL_ACCEPTANCE.md',
    'docs/m6/M6_E_P1_SERVER_OWNED_CONTEXT_ACCEPTANCE.md',
    'docs/m6/M6_E_P2_BOUNDED_ADVISORY_CONTRACT_ACCEPTANCE.md',
    'docs/m6/M6_E_P3_SYNCHRONOUS_ORCHESTRATION_ACCEPTANCE.md',
    'docs/m6/M6_E_P4_DURABLE_EVIDENCE_ACCEPTANCE.md',
    'docs/m6/M6_E_P5_READ_ONLY_API_AND_PRESENTATION_ACCEPTANCE.md',
    'docs/m6/M6_E_P6_A_OPENAI_PROVIDER_AUDIT_ACCEPTANCE.md',
    'docs/m6/M6_E_P6_B_OPENAI_SECRET_SOURCE_ACCEPTANCE.md',
    'docs/m6/M6_E_P6_C_OPENAI_CODEC_ACCEPTANCE.md',
    'docs/m6/M6_E_P6_D_OPENAI_SECURE_SENDER_ACCEPTANCE.md',
    'docs/m6/M6_E_P6_E_PRODUCTION_INVOCATION_ACCEPTANCE.md',
    'docs/m6/M6_E_P6_F_FAULT_SECURITY_INCIDENT_ACCEPTANCE.md',
    'docs/m6/M6_E_P6_F_OPENAI_INCIDENT_RUNBOOK.md',
  ];
  for (const document of documents) assert.equal(existsSync(path.join(root, document)), true);

  assert.match(finalAcceptance, /ff736dee3b02c6a9f087d92b2a176d9af2724886/);
  assert.match(finalAcceptance, /61265b123ef688a9e81c90bdee3319abafad850b/);
  assert.match(finalAcceptance, /30896143997/);
  assert.match(finalAcceptance, /P7_PENDING_EXACT_PERMANENT_VALIDATION/);
  assert.match(finalAcceptance, /P7_NOT_READY_OR_MERGED/);
});

test('P7 adversarial matrix closes every threat explicitly gated to P7', () => {
  for (const threat of [
    'T05', 'T06', 'T07', 'T08', 'T10', 'T11', 'T12', 'T13', 'T14', 'T15',
    'T16', 'T17', 'T18', 'T19', 'T20', 'T21', 'T22', 'T25', 'T26',
  ]) {
    assert.match(threatModel, new RegExp(`\\| ${threat} \\|[\\s\\S]*?P7`));
    assert.match(finalAcceptance, new RegExp(`\\b${threat}\\b`));
  }
  assert.match(finalAcceptance, /prompt and tool injection/i);
  assert.match(finalAcceptance, /unknown evidence reference/i);
  assert.match(finalAcceptance, /oversized and malformed input\/output/i);
  assert.match(finalAcceptance, /stale state and version drift/i);
  assert.match(finalAcceptance, /Secret, DNS, TLS, SSRF, cost, rate, circuit and kill switch/i);
  assert.match(finalAcceptance, /hash ambiguity and tamper resistance/i);
});

test('P7 retains real PostgreSQL concurrency and conflict evidence', () => {
  assert.match(p4Jdbc, /concurrentExactStoreProducesOneStoreAndOneReplay/);
  assert.match(p4Jdbc, /List\.of\(StoreDisposition\.REPLAYED, StoreDisposition\.STORED\)/);
  assert.match(p4Jdbc, /concurrentExactTombstoneProducesOneTransitionAndOneReplay/);
  assert.match(
    p4Jdbc,
    /List\.of\(TombstoneDisposition\.REPLAYED, TombstoneDisposition\.TOMBSTONED\)/,
  );
  assert.match(p4Jdbc, /sameRequestWithDifferentEvidenceIdentityConflictsWithoutPartialWrites/);
  assert.match(p4Jdbc, /sameEvidenceIdentityIsIsolatedByTenant/);
  assert.match(p4Jdbc, /evidenceEventsAndStateRejectPhysicalMutationOrDeletion/);
  assert.match(finalAcceptance, /one `STORED` and one `REPLAYED`/);
  assert.match(finalAcceptance, /one `TOMBSTONED` and one `REPLAYED`/);
});

test('P7 proves per-request one Provider attempt and no retry after persistence failure', () => {
  assert.match(providerFaults, /OpenAiResponsesTransportException\.Failure\.values\(\)/);
  assert.match(providerFaults, /assertEquals\(1, calls\.get\(\)/);
  assert.match(providerFaults, /assertFalse\(outcome\.failure\(\)\.retryable\(\)/);
  assert.match(serviceTests, /evidenceConflictDoesNotInvokeProviderTwice/);
  assert.match(serviceTests, /evidenceUnavailableDoesNotInvokeProviderTwice/);
  assert.match(serviceTests, /assertEquals\(1, providerCalls\.get\(\)\)/);
  assert.match(publicFailures, /retryAttempted\(\)/);
  assert.match(publicFailures, /fallbackAttempted\(\)/);
  assert.match(finalAcceptance, /exactly-once external Provider execution across two distinct explicit HTTP requests is not claimed/i);
  assert.match(finalAcceptance, /rate, cost and circuit controls bound that residual case/i);
});

test('P7 clients prevent in-flight duplicate generation and never auto-generate', () => {
  for (const panel of [webPanel, mobilePanel]) {
    assert.match(panel, /if \(!props\.taskId \|\| generating\.value\) return/);
    assert.match(panel, /generating\.value = true/);
    assert.match(panel, /generating\.value = false/);
    assert.match(panel, /@click="generateAssistance"/);
    const watchBody = panel.slice(panel.indexOf('watch('));
    assert.doesNotMatch(watchBody, /generateApprovalAssistance\s*\(/);
    assert.doesNotMatch(panel, /setInterval|autoGenerate|polling/i);
  }
});

test('P7 preserves current-main JDBC parallelism and M6-E dependency', () => {
  const pom = source('server-modules/approval-persistence-jdbc/pom.xml');
  assert.match(pom, /<artifactId>approval-ai-core<\/artifactId>/);
  assert.match(pom, /<approval\.persistence\.test\.fork-count>4<\/approval\.persistence\.test\.fork-count>/);
  assert.match(pom, /<forkCount>\$\{approval\.persistence\.test\.fork-count\}<\/forkCount>/);
  assert.match(pom, /<reuseForks>true<\/reuseForks>/);
  assert.match(pom, /<append>true<\/append>/);
});

test('P7 adds no migration, workflow or product capability', () => {
  const migrationRoot = path.join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
  );
  const migrations = readdirSync(migrationRoot);
  assert.equal(migrations.filter(name => /^V49__/.test(name)).length, 1);
  assert.equal(migrations.some(name => /^V(?:5[0-9]|[6-9][0-9])__/.test(name)), false);

  const workflowRoot = path.join(root, '.github/workflows');
  const automatic = readdirSync(workflowRoot)
    .filter(name => /\.ya?ml$/.test(name))
    .filter(name => {
      const content = readFileSync(path.join(workflowRoot, name), 'utf8');
      return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
    });
  assert.deepEqual(automatic, ['approval-platform-validation.yml']);

  assert.match(finalAcceptance, /no production Java, TypeScript, migration or workflow change/i);
  assert.match(finalAcceptance, /no Provider, Prompt, endpoint, retry, fallback, Queue, Worker or Scheduler/i);
  assert.match(finalAcceptance, /no automation proposal or executable action/i);
});

test('P7 final merge and post-main gate remains conditional and auditable', () => {
  assert.match(finalAcceptance, /ordinary Merge Commit only/i);
  assert.match(finalAcceptance, /never squash, rebase or force push/i);
  assert.match(finalAcceptance, /natural `push -> main`/);
  assert.match(finalAcceptance, /four post-main artifacts/i);
  assert.match(finalAcceptance, /Issue #80 closes only after/i);
  assert.match(finalAcceptance, /M6-F remains gated until M6-E post-main closure/i);
  assert.match(finalAcceptance, /AI_IS_NOT_AN_OPERATOR/);
});

test('permanent transport review loads the P7 final acceptance boundary', () => {
  const aggregator = source('scripts/tests/m6-ai-transport-review-boundary.test.mjs');
  assert.match(
    aggregator,
    /import '\.\/m6-e-p7-final-acceptance-boundary\.test\.mjs';/,
  );
});
