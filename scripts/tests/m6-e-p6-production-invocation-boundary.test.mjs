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

const openAiRoot =
  'server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai';
const coreRoot =
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core';
const serverApiRoot = 'apps/server/src/main/java/io/github/akaryc1b/approval/api';
const serverConfigRoot = 'apps/server/src/main/java/io/github/akaryc1b/approval/config';

test('production adapter is exact, framework-free and performs one exchange', () => {
  const adapter = source(`${openAiRoot}/OpenAiResponsesAdvisoryProvider.java`);

  assert.match(adapter, /implements AiAdvisoryProvider/);
  assert.match(adapter, /AiProviderType\.REMOTE/);
  assert.match(adapter, /OpenAiResponsesRequestEncoder/);
  assert.match(adapter, /OpenAiResponsesResponseDecoder/);
  assert.match(adapter, /OpenAiResponsesTransportPort/);
  assert.equal((adapter.match(/transport\.exchange\s*\(/g) || []).length, 1);
  assert.doesNotMatch(adapter, /org\.springframework|Flowable|Jdbc|DataSource|Controller/);
  assert.doesNotMatch(
    adapter,
    /for\s*\([^)]*(?:attempt|retry)|while\s*\([^)]*(?:attempt|retry)|Thread\.sleep|fallbackProvider|secondProvider/i,
  );
  assert.doesNotMatch(adapter, /previous_response_id|stream\s*=\s*true/i);
  assert.doesNotMatch(adapter, /HttpClient|WebClient|RestClient/);
});

test('production orchestration is additive and preserves deterministic P3', () => {
  const production = source(`${coreRoot}/ApprovalAssistanceProductionOrchestrator.java`);
  const deterministic = source(`${coreRoot}/ApprovalAssistanceSynchronousOrchestrator.java`);

  assert.match(deterministic, /DETERMINISTIC_TEST_ONLY/);
  assert.match(production, /AiProviderType\.REMOTE/);
  assert.match(production, /maximumProviderAttempts\(\) != 1/);
  assert.match(production, /killSwitchEnabled/);
  assert.match(production, /circuitPermit/);
  assert.match(production, /tenantRatePermit/);
  assert.match(production, /globalRatePermit/);
  assert.match(production, /costPermit/);
  assert.match(production, /new Result\(request, providerOutcome\.result\(\)\)/);
  assert.doesNotMatch(production, /while\s*\(|for\s*\([^)]*attempt/i);
});

test('P4 production integration remains hash-only and uses the accepted record', () => {
  const factory = source(
    `${coreRoot}/ApprovalAssistanceProductionDurableEvidenceFactory.java`,
  );
  const store = source(
    'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalAssistanceDurableEvidenceStore.java',
  );

  assert.match(factory, /new ApprovalAssistanceDurableEvidence\s*\(/);
  assert.match(factory, /M6-E-P4-DURABLE-EVIDENCE-V1/);
  assert.match(factory, /M6-E-P4-PROVIDER-VALUE-EVIDENCE-V1/);
  assert.match(store, /implements ApprovalAssistanceDurableEvidenceStore/);
  assert.doesNotMatch(factory, /requestBody|responseBody|apiKey|secretValue|rawRequestId/);
});

test('generation API is distinct, closed and advisory-only', () => {
  const controller = source(
    `${serverApiRoot}/ApprovalAssistanceGenerationController.java`,
  );
  const contracts = source(`${serverApiRoot}/ApprovalAssistanceGenerationContracts.java`);
  const readController = source(`${serverApiRoot}/ApprovalAssistanceReadController.java`);

  assert.match(controller, /@PostMapping\("\/\{taskId\}\/assistance\/generations"\)/);
  assert.match(controller, /CacheControl\.noStore\(\)/);
  assert.match(controller, /@RequestBody JsonNode body/);
  assert.match(contracts, /object\.size\(\) != REQUEST_FIELDS\.size\(\)/);
  assert.match(contracts, /REQUEST_FIELDS\.contains\(object\.fieldNames\(\)\.next\(\)\)/);
  assert.match(contracts, /REQUEST_INVALID/);
  assert.match(contracts, /commandAvailable/);
  assert.match(contracts, /providerSelectable/);
  assert.match(contracts, /retryAttempted/);
  assert.match(contracts, /fallbackAttempted/);
  assert.doesNotMatch(contracts, /ProviderVersion|ModelVersion|PromptTemplateVersion/);
  assert.match(readController, /@GetMapping\("\/\{taskId\}\/assistance"\)/);
  assert.doesNotMatch(readController, /OpenAi|AiAdvisoryService|\.generate\s*\(/);
});

test('server-owned service revalidates task before one production execution and P4 store', () => {
  const service = source(`${serverApiRoot}/ApprovalAssistanceGenerationService.java`);

  assert.equal((service.match(/taskQuery\.findPendingTask\s*\(/g) || []).length, 2);
  assert.equal((service.match(/orchestrator\.execute\s*\(/g) || []).length, 1);
  assert.equal((service.match(/evidenceStore\.store\s*\(/g) || []).length, 1);
  assert.match(service, /StoreDisposition\.CONFLICT/);
  assert.match(service, /Optional<OpenAiResponsesProductionRuntimeFactory>/);
  assert.doesNotMatch(service, /approve\s*\(|reject\s*\(|returnTask\s*\(|transfer\s*\(/);
  assert.doesNotMatch(service, /ACT_[A-Z_]+/);
});

test('Spring wiring is default-disabled and never reads the API key', () => {
  const config = source(
    `${serverConfigRoot}/ApprovalAssistanceProductionConfiguration.java`,
  );
  const serverPom = source('apps/server/pom.xml');

  assert.match(config, /APPROVAL_AI_OPENAI_ENABLED/);
  assert.match(config, /getProperty\(ENABLED, "false"\)/);
  assert.match(config, /OPENAI_API_KEY_VERSION/);
  assert.doesNotMatch(config, /getProperty\("OPENAI_API_KEY"\)|System\.getenv/);
  assert.doesNotMatch(config, /api\.openai\.com|gpt-5-mini|endpoint override|model alias/i);
  assert.match(serverPom, /<artifactId>approval-ai-openai<\/artifactId>/);
});

test('PC and Mobile invoke generation only from explicit handlers', () => {
  const webApi = source(
    'apps/web/overlay/apps/web-ele/src/api/approval/assistance.ts',
  );
  const webPanel = source(
    'apps/web/overlay/apps/web-ele/src/components/approval/ApprovalAssistancePanel.vue',
  );
  const mobileApi = source('apps/mobile/overlay/src/api/approval/assistance.ts');
  const mobilePanel = source(
    'apps/mobile/overlay/src/components/approval/ApprovalAssistancePanel.vue',
  );

  for (const api of [webApi, mobileApi]) {
    assert.match(api, /assistance\/generations/);
    assert.match(api, /method: 'POST'/);
    assert.match(api, /JSON\.stringify\(\{ useCase \}\)/);
  }
  for (const panel of [webPanel, mobilePanel]) {
    assert.match(panel, /async function generateAssistance\(\)/);
    assert.match(panel, /@click="generateAssistance"/);
    assert.match(
      panel,
      /if\s*\(\s*!props\.taskId\s*\|\|\s*generating\.value\s*\|\|\s*assistance\.value\?\.availability\s*!==\s*'AVAILABLE'\s*\)\s*return/,
    );
    const watchBody = panel.slice(panel.indexOf('watch('));
    assert.doesNotMatch(watchBody, /generateApprovalAssistance\s*\(/);
    assert.doesNotMatch(panel, /setInterval|poll|autoGenerate/i);
  }
});

test('P6-E introduces no new migration or second automatic workflow', () => {
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

test('P6-E production sources contain no automation or approval command path', () => {
  const paths = [
    `${openAiRoot}/OpenAiResponsesAdvisoryProvider.java`,
    `${openAiRoot}/OpenAiResponsesProductionRuntimeFactory.java`,
    `${coreRoot}/ApprovalAssistanceProductionOrchestrator.java`,
    `${coreRoot}/ApprovalAssistanceProductionDurableEvidenceFactory.java`,
    `${serverApiRoot}/ApprovalAssistanceGenerationService.java`,
    `${serverApiRoot}/ApprovalAssistanceGenerationController.java`,
  ];
  const production = paths.map(source).join('\n');

  assert.doesNotMatch(production, /Queue|Scheduler|Scheduled|Worker|Listener|polling/i);
  assert.doesNotMatch(production, /AUTOMATION_PROPOSAL|EXECUTABLE_ACTION/);
  assert.doesNotMatch(production, /ACT_[A-Z_]+/);
  assert.doesNotMatch(production, /approve\s*\(|reject\s*\(|withdraw\s*\(|terminate\s*\(/);
  assert.doesNotMatch(
    production,
    /previousResponse|previous_response_id|["']conversation["']|function calling|vector store|embedding/i,
  );
});
