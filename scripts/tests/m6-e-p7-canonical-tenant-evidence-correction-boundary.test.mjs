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

const acceptance = source(
  'docs/m6/M6_E_P7_CANONICAL_TENANT_EVIDENCE_CORRECTION_ACCEPTANCE.md',
);
const providerRequest = source(
  'server-modules/approval-ai-spi/src/main/java/io/github/akaryc1b/approval/ai/spi/AiProviderRequest.java',
);
const runtimeFactory = source(
  'server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesProductionRuntimeFactory.java',
);
const runtimeTest = source(
  'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesProductionRuntimeFactoryTenantBoundaryTest.java',
);
const readController = source(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalAssistanceReadController.java',
);
const readContracts = source(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalAssistanceReadContracts.java',
);

test('canonical tenant correction freezes the exact successful implementation evidence', () => {
  assert.match(acceptance, /3d14e299fe1a40da1b59d1e32a5512150dd41673/);
  assert.match(acceptance, /30908055123/);
  assert.match(acceptance, /Maven core: `1263 \/ 0 \/ 0 \/ 0`/);
  assert.match(acceptance, /Persistence JDBC: `295 \/ 0 \/ 0 \/ 0`/);
  assert.match(acceptance, /Maven aggregate: `1558 \/ 0 \/ 0 \/ 0`/);
  assert.match(acceptance, /OpenAI: `59 \/ 59`/);
  assert.match(acceptance, /P7_CANONICAL_TENANT_EVIDENCE_CORRECTION_IMPLEMENTATION_ACCEPTED/);
  assert.match(acceptance, /DOCUMENTED_HEAD_PERMANENT_VALIDATION_REQUIRED/);
});

test('all four implementation artifacts remain exact and independently verifiable', () => {
  for (const evidence of [
    '8891941448',
    '328048',
    '71e319d9f0b523650586d7993193f09ffecf9ca35bbd41a0f9fbd97926feeeff',
    '8891918010',
    '18845',
    '0634639dbe7b15d666bf61245983fd8a139b87caf9d2e53b315ae760e3801ca1',
    '8891899093',
    '9792',
    '67dc857003e17d236019506c9794860d81aa4bd3e2d448f0bc4791da93a59604',
    '8891871139',
    '11188',
    '6abab67ef480b63585ab5e82a4df541cc1e1a25cc83728fb90e5c95def4ab1b3',
  ]) {
    assert.match(acceptance, new RegExp(evidence));
  }
});

test('provider request compatibility is restored while tenant bounds advance only to 128', () => {
  assert.match(providerRequest, /Set<String> allowedFields,/);
  assert.match(providerRequest, /List<InputField> inputFields,/);
  assert.match(providerRequest, /allowedFields = allowedFields == null/);
  assert.match(providerRequest, /inputFields = inputFields == null/);
  assert.match(providerRequest, /inputFields\.size\(\) > 200/);
  assert.match(providerRequest, /input field keys must be unique/);
  assert.match(providerRequest, /allowedFields\.contains\(field\.key\(\)\)/);
  assert.equal(
    (providerRequest.match(/tenantId = requireText\(tenantId, "tenantId", 128\)/g) || []).length,
    2,
  );
  assert.doesNotMatch(providerRequest, /tenantId[^\n]*120/);
  assert.doesNotMatch(providerRequest, /allowedFieldKeys|List<InputField> fields,/);
});

test('runtime admission and credential evidence share one canonical tenant hash', () => {
  assert.match(
    runtimeFactory,
    /CanonicalPayloadHash\.sha256Utf8\("tenant\\n" \+ tenantId\)/,
  );
  assert.doesNotMatch(
    runtimeFactory,
    /CanonicalPayloadHash\.sha256Utf8\(tenantId\)/,
  );
  assert.match(runtimeFactory, /credentialRequest,[\s\S]*?admission,[\s\S]*?clock/);
  assert.match(
    runtimeTest,
    /CanonicalPayloadHash\.sha256Utf8\("tenant\\n" \+ tenantId\)/,
  );
  assert.match(runtimeTest, /assertSame\(first, replay\)/);
  assert.match(runtimeTest, /"t"\.repeat\(128\)/);
  assert.match(runtimeTest, /"t"\.repeat\(129\)/);
});

test('runtime-aware GET remains accurate zero-egress and non-generating', () => {
  assert.match(readContracts, /AVAILABLE/);
  assert.match(readContracts, /PROVIDER_NOT_CONFIGURED/);
  assert.match(readContracts, /AI_ASSISTANCE_AVAILABLE/);
  assert.match(readContracts, /AI_ASSISTANCE_PROVIDER_REQUIRED/);
  assert.doesNotMatch(readContracts, /NO_ADVISORY_RESULT_AVAILABLE/);
  assert.match(readController, /runtimeAvailability\.providerConfigured\(\)/);
  for (const forbidden of [
    /\.generate\s*\(/,
    /\.bind\s*\(/,
    /OpenAi/,
    /SecretMaterial/,
    /HttpClient/,
    /java\.net\./,
  ]) {
    assert.doesNotMatch(readController, forbidden);
  }
});

test('canonical correction retains migration workflow and authority closure', () => {
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

  assert.match(acceptance, /no new migration or workflow is introduced/i);
  assert.match(acceptance, /SECOND_READY_REVIEW_THREADS_PENDING_EVIDENCE_REPLY/);
  assert.match(acceptance, /PR_REMAINS_DRAFT/);
  assert.match(acceptance, /M6_F_REMAINS_GATED/);
  assert.match(acceptance, /AI_IS_NOT_AN_OPERATOR/);
});
