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
  'docs/m6/M6_E_P7_SECOND_READY_REVIEW_CORRECTION_ACCEPTANCE.md',
);
const serverContext = source(
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/AiServerRequestContext.java',
);
const authorizedResource = source(
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/AiAuthorizedResource.java',
);
const projection = source(
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/ApprovalAssistanceContextProjection.java',
);
const providerRequest = source(
  'server-modules/approval-ai-spi/src/main/java/io/github/akaryc1b/approval/ai/spi/AiProviderRequest.java',
);
const runtimeFactory = source(
  'server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesProductionRuntimeFactory.java',
);
const tenantTest = source(
  'server-modules/approval-ai-core/src/test/java/io/github/akaryc1b/approval/ai/core/AiTenantBoundaryCompatibilityTest.java',
);
const runtimeTenantTest = source(
  'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesProductionRuntimeFactoryTenantBoundaryTest.java',
);
const availability = source(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalAssistanceRuntimeAvailability.java',
);
const generationService = source(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationService.java',
);
const readContracts = source(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalAssistanceReadContracts.java',
);
const readController = source(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalAssistanceReadController.java',
);
const readControllerTest = source(
  'apps/server/src/test/java/io/github/akaryc1b/approval/api/ApprovalAssistanceReadControllerTest.java',
);
const webApi = source('apps/web/overlay/apps/web-ele/src/api/approval/assistance.ts');
const mobileApi = source('apps/mobile/overlay/src/api/approval/assistance.ts');
const webPanel = source(
  'apps/web/overlay/apps/web-ele/src/components/approval/ApprovalAssistancePanel.vue',
);
const mobilePanel = source(
  'apps/mobile/overlay/src/components/approval/ApprovalAssistancePanel.vue',
);

test('second Ready Review correction freezes exact actionable evidence', () => {
  assert.match(acceptance, /PRR_kwDOTbeZ188AAAABIUyBcQ/);
  assert.match(acceptance, /PRRT_kwDOTbeZ186WSxPZ/);
  assert.match(acceptance, /PRRC_kwDOTbeZ187dPieQ/);
  assert.match(acceptance, /PRRT_kwDOTbeZ186WSxPd/);
  assert.match(acceptance, /PRRC_kwDOTbeZ187dPieX/);
  assert.match(acceptance, /e4e4f64c48f89e0067a9114da0575824fdafdde3/);
  assert.match(acceptance, /30903214702/);
  assert.match(acceptance, /P7_SECOND_READY_REVIEW_CORRECTION_PENDING_EXACT_VALIDATION/);
  assert.match(acceptance, /SECOND_READY_REVIEW_THREADS_REMAIN_UNRESOLVED/);
});

test('every trusted AI tenant carrier accepts exactly the platform 128 maximum', () => {
  assert.match(serverContext, /tenantId = requireText\(tenantId, "tenantId", 128\)/);
  assert.match(authorizedResource, /tenantId = requireText\(tenantId, "tenantId", 128\)/);
  assert.match(projection, /tenantId = requireText\(tenantId, "tenantId", 128\)/);
  assert.equal(
    (providerRequest.match(/tenantId = requireText\(tenantId, "tenantId", 128\)/g) || []).length,
    2,
  );
  assert.match(runtimeFactory, /requireText\(trustedTenantId, "trustedTenantId", 128\)/);

  for (const production of [
    serverContext,
    authorizedResource,
    projection,
    providerRequest,
    runtimeFactory,
  ]) {
    assert.doesNotMatch(production, /tenantId[^\n]*120|trustedTenantId[^\n]*120/);
  }

  assert.match(tenantTest, /"t"\.repeat\(128\)/);
  assert.match(tenantTest, /"t"\.repeat\(129\)/);
  assert.match(tenantTest, /everyTrustedTenantCarrierAcceptsThePlatformMaximum/);
  assert.match(tenantTest, /everyTrustedTenantCarrierRejectsAboveThePlatformMaximum/);
  assert.match(runtimeTenantTest, /runtimeBindingAcceptsAndCachesThePlatformMaximumTenant/);
  assert.match(runtimeTenantTest, /runtimeBindingRejectsTenantAboveThePlatformMaximum/);
});

test('runtime-aware GET exposes only accurate closed availability states', () => {
  assert.match(availability, /boolean providerConfigured\(\)/);
  assert.match(generationService, /implements ApprovalAssistanceRuntimeAvailability/);
  assert.match(
    generationService,
    /public boolean providerConfigured\(\)[\s\S]*?return runtimeFactory\.isPresent\(\)/,
  );

  assert.match(readContracts, /enum Availability[\s\S]*?AVAILABLE,[\s\S]*?PROVIDER_NOT_CONFIGURED/);
  assert.match(readContracts, /AI_ASSISTANCE_AVAILABLE/);
  assert.match(readContracts, /AI_ASSISTANCE_PROVIDER_REQUIRED/);
  assert.match(readContracts, /EXPLICIT_GENERATION_REQUIRED/);
  assert.match(readContracts, /EXPLICIT_GENERATION_UNAVAILABLE/);
  assert.match(readContracts, /PRODUCTION_PROVIDER_NOT_CONFIGURED/);
  assert.match(readContracts, /HUMAN_REVIEW_REQUIRED/);
  assert.doesNotMatch(readContracts, /NO_ADVISORY_RESULT_AVAILABLE/);
  assert.doesNotMatch(readContracts, /AI_ASSISTANCE_P6_PROVIDER_REQUIRED/);

  assert.match(readController, /ApprovalAssistanceRuntimeAvailability/);
  assert.match(readController, /runtimeAvailability\.providerConfigured\(\)/);
  assert.match(readController, /AssistanceView\.current/);
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

  assert.match(readControllerTest, /Availability\.AVAILABLE/);
  assert.match(readControllerTest, /Availability\.PROVIDER_NOT_CONFIGURED/);
  assert.match(readControllerTest, /authorizedPendingTaskReportsExplicitGenerationAvailableWhenRuntimeExists/);
  assert.match(readControllerTest, /authorizedPendingTaskReturnsNoStoreProviderRequiredViewWhenRuntimeIsAbsent/);
  assert.match(readControllerTest, /assertFalse\(body\.providerInvocationStarted\(\)\)/);
  assert.match(readControllerTest, /assertFalse\(body\.resultAvailable\(\)\)/);
  assert.match(readControllerTest, /assertNull\(body\.advisoryResult\(\)\)/);
});

test('Web and Mobile share the closed states and suppress unavailable generation', () => {
  const clients = `${webApi}\n${mobileApi}`;
  assert.match(clients, /availability: 'AVAILABLE' \| 'PROVIDER_NOT_CONFIGURED'/);
  assert.match(clients, /code: 'AI_ASSISTANCE_AVAILABLE' \| 'AI_ASSISTANCE_PROVIDER_REQUIRED'/);
  assert.doesNotMatch(clients, /AI_ASSISTANCE_P6_PROVIDER_REQUIRED/);

  for (const panel of [webPanel, mobilePanel]) {
    assert.match(panel, /assistance\.value\?\.availability !== 'AVAILABLE'/);
    assert.match(panel, /assistance\.availability !== 'AVAILABLE'/);
    assert.match(panel, /@click="generateAssistance"/);
    assert.doesNotMatch(panel, /setInterval|autoGenerate|polling/i);
  }
});

test('second Ready correction preserves migration workflow and authority closure', () => {
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

  assert.match(acceptance, /adds no Provider, model, Prompt, endpoint, Secret source, retry, fallback/);
  assert.match(acceptance, /AI_IS_NOT_AN_OPERATOR/);
  assert.match(acceptance, /M6_F_REMAINS_GATED/);
});

test('permanent transport review loads the second Ready correction boundary', () => {
  const aggregator = source('scripts/tests/m6-ai-transport-review-boundary.test.mjs');
  assert.match(
    aggregator,
    /import '\.\/m6-e-p7-second-ready-review-correction-boundary\.test\.mjs';/,
  );
});
