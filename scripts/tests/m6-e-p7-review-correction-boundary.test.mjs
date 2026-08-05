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

const acceptance = source('docs/m6/M6_E_P7_REVIEW_CORRECTION_ACCEPTANCE.md');
const decoder = source(
  'server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesResponseDecoder.java',
);
const decoderTest = source(
  'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesResponseDecoderTest.java',
);
const context = source(
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/AiServerRequestContext.java',
);
const contextTest = source(
  'server-modules/approval-ai-core/src/test/java/io/github/akaryc1b/approval/ai/core/AiServerRequestContextTest.java',
);

test('P7 review correction freezes both actionable Codex findings', () => {
  assert.match(acceptance, /PRR_kwDOTbeZ188AAAABIUMkpg/);
  assert.match(acceptance, /PRRT_kwDOTbeZ186WReSu/);
  assert.match(acceptance, /PRRC_kwDOTbeZ187dNq-g/);
  assert.match(acceptance, /PRRT_kwDOTbeZ186WReS2/);
  assert.match(acceptance, /PRRC_kwDOTbeZ187dNq-p/);
  assert.match(acceptance, /Provider-generated `x-request-id`/);
  assert.match(acceptance, /client-generated `X-Client-Request-Id`/);
  assert.match(acceptance, /platform tenant identity contract permits 128 characters/);
  assert.match(acceptance, /P7_REVIEW_CORRECTION_PENDING_EXACT_VALIDATION/);
});

test('Provider and client correlation identifiers remain separate and fail closed', () => {
  assert.match(decoder, /String providerRequestId = exactText/);
  assert.match(decoder, /String providerRequestIdHash = OpenAiResponsesProtocol\.sha256Utf8/);
  assert.match(decoder, /response\.transportEvidence\(\)[\s\S]*?\.clientRequestIdHash\(\)/);
  assert.match(decoder, /admittedClientRequestIdHash\.equals\([\s\S]*?expectations\.admittedRequestIdHash\(\)/);
  assert.match(decoder, /providerRequestIdHash,/);
  assert.doesNotMatch(
    decoder,
    /providerRequestIdHash\.equals\(expectations\.admittedRequestIdHash\(\)\)/,
  );

  assert.match(decoderTest, /PROVIDER_REQUEST_ID = "req_provider_123"/);
  assert.match(decoderTest, /CLIENT_REQUEST_ID_HASH/);
  assert.match(decoderTest, /"req_provider_other"/);
  assert.match(decoderTest, /differentProviderRequestId\.requestIdHash\(\)/);
  assert.match(decoderTest, /"client_request_other"/);
  assert.match(decoderTest, /TransportEvidence\.verified\(/);
  assert.match(decoderTest, /Failure\.REQUEST_ID_MISMATCH/);
});

test('AI tenant context accepts exactly the platform 128-character maximum', () => {
  assert.match(context, /tenantId = requireText\(tenantId, "tenantId", 128\)/);
  assert.doesNotMatch(context, /tenantId = requireText\(tenantId, "tenantId", 120\)/);
  assert.match(contextTest, /"t"\.repeat\(128\)/);
  assert.match(contextTest, /assertEquals\(tenantId, context\.tenantId\(\)\)/);
  assert.match(contextTest, /"t"\.repeat\(129\)/);
  assert.match(contextTest, /assertThrows\([\s\S]*?IllegalArgumentException\.class/);
});

test('P7 review correction preserves migration workflow and authority boundaries', () => {
  assert.match(acceptance, /adds no Provider, model, Prompt, endpoint, Secret source, retry, fallback/);
  assert.match(acceptance, /adds no migration/);
  assert.match(acceptance, /adds no workflow/);
  assert.match(acceptance, /AI_IS_NOT_AN_OPERATOR/);
  assert.match(acceptance, /PR_83_REMAINS_DRAFT/);
  assert.match(acceptance, /REVIEW_THREADS_REMAIN_UNRESOLVED_UNTIL_EXACT_EVIDENCE/);

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
});

test('permanent transport review loads the P7 review correction boundary', () => {
  const aggregator = source('scripts/tests/m6-ai-transport-review-boundary.test.mjs');
  assert.match(
    aggregator,
    /import '\.\/m6-e-p7-review-correction-boundary\.test\.mjs';/,
  );
});
