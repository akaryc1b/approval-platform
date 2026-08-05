import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const contractPath = path.join(
  root,
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/' +
    'ApprovalAssistanceReadContracts.java',
);
const controllerPath = path.join(
  root,
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/' +
    'ApprovalAssistanceReadController.java',
);
const availabilityPath = path.join(
  root,
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/' +
    'ApprovalAssistanceRuntimeAvailability.java',
);
const generationServicePath = path.join(
  root,
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/' +
    'ApprovalAssistanceGenerationService.java',
);
const controllerTestPath = path.join(
  root,
  'apps/server/src/test/java/io/github/akaryc1b/approval/api/' +
    'ApprovalAssistanceReadControllerTest.java',
);
const serverPomPath = path.join(root, 'apps/server/pom.xml');
const webApiPath = path.join(
  root,
  'apps/web/overlay/apps/web-ele/src/api/approval/assistance.ts',
);
const mobileApiPath = path.join(
  root,
  'apps/mobile/overlay/src/api/approval/assistance.ts',
);
const webPanelPath = path.join(
  root,
  'apps/web/overlay/apps/web-ele/src/components/approval/' +
    'ApprovalAssistancePanel.vue',
);
const mobilePanelPath = path.join(
  root,
  'apps/mobile/overlay/src/components/approval/' +
    'ApprovalAssistancePanel.vue',
);
const webDetailPath = path.join(
  root,
  'apps/web/overlay/apps/web-ele/src/views/approval/workbench/index.vue',
);
const mobileDetailPath = path.join(
  root,
  'apps/mobile/overlay/src/pages/task/detail.vue',
);
const migrationRoot = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
);

function text(file) {
  return readFileSync(file, 'utf8');
}

function filesUnder(directory) {
  if (!existsSync(directory)) return [];
  const output = [];
  for (const entry of readdirSync(directory)) {
    const absolute = path.join(directory, entry);
    if (statSync(absolute).isDirectory()) output.push(...filesUnder(absolute));
    else output.push(absolute);
  }
  return output;
}

function exportedFunction(source, name) {
  const match = source.match(new RegExp(
    `export function ${name}\\([\\s\\S]*?\\n\\}`,
  ));
  assert.ok(match, `missing exported function ${name}`);
  return match[0];
}

test('assistance GET remains tenant-scoped runtime-aware and zero-egress', () => {
  for (const requiredPath of [
    contractPath,
    controllerPath,
    availabilityPath,
    generationServicePath,
    controllerTestPath,
    serverPomPath,
  ]) {
    assert.equal(existsSync(requiredPath), true, `missing assistance server source ${requiredPath}`);
  }

  const contract = text(contractPath);
  for (const required of [
    /List\.of\(UseCase\.values\(\)\)/,
    /AVAILABLE/,
    /PROVIDER_NOT_CONFIGURED/,
    /AI_ASSISTANCE_AVAILABLE/,
    /AI_ASSISTANCE_PROVIDER_REQUIRED/,
    /EXPLICIT_GENERATION_REQUIRED/,
    /EXPLICIT_GENERATION_UNAVAILABLE/,
    /HUMAN_REVIEW_REQUIRED/,
    /providerInvocationStarted/,
    /providerSelectable/,
    /commandAvailable/,
    /resultAvailable/,
    /advisoryResult != null/,
    /AssistanceView current\(/,
    /boolean providerConfigured/,
    /TaskSnapshot\.from\(task\)/,
    /read-only assistance view must remain non-executing and non-authoritative/,
  ]) {
    assert.match(contract, required);
  }
  assert.doesNotMatch(contract, /NO_ADVISORY_RESULT_AVAILABLE/);
  assert.doesNotMatch(contract, /AI_ASSISTANCE_P6_PROVIDER_REQUIRED/);
  for (const forbidden of [
    /@(?:Post|Put|Patch|Delete)Mapping/,
    /ApprovalAssistanceSynchronousOrchestrator/,
    /ApprovalAssistanceDurableEvidenceStore/,
    /AiAdvisoryProvider/,
    /SecretMaterial/,
    /HttpClient/,
    /java\.net\./,
    /org\.flowable/,
  ]) {
    assert.doesNotMatch(contract, forbidden);
  }

  const availability = text(availabilityPath);
  assert.match(availability, /boolean providerConfigured\(\)/);
  assert.doesNotMatch(availability, /Secret|HttpClient|java\.net\.|bind\s*\(/);

  const generationService = text(generationServicePath);
  assert.match(
    generationService,
    /implements ApprovalAssistanceRuntimeAvailability/,
  );
  assert.match(
    generationService,
    /public boolean providerConfigured\(\)[\s\S]*?return runtimeFactory\.isPresent\(\)/,
  );

  const controller = text(controllerPath);
  for (const required of [
    /@RequestMapping\("\/api\/approval\/tasks"\)/,
    /@GetMapping\("\/\{taskId\}\/assistance"\)/,
    /@RequestHeader\(TENANT_ID_HEADER\)/,
    /@RequestHeader\(OPERATOR_ID_HEADER\)/,
    /new PendingTaskIdentity\(/,
    /trustedTenantId/,
    /trustedOperatorId/,
    /CacheControl\.noStore\(\)/,
    /ResponseEntity\.notFound\(\)/,
    /ApprovalAssistanceRuntimeAvailability/,
    /runtimeAvailability\.providerConfigured\(\)/,
    /AssistanceView\.current/,
  ]) {
    assert.match(controller, required);
  }
  for (const forbidden of [
    /@(?:Post|Put|Patch|Delete)Mapping/,
    /@ApprovalManagementPermission/,
    /ApprovalCommand/,
    /\.generate\s*\(/,
    /ApprovalAssistanceSynchronousOrchestrator/,
    /ApprovalAssistanceDurableEvidenceStore/,
    /AiAdvisoryProvider/,
    /OpenAi/,
    /SecretMaterial/,
    /HttpClient/,
    /java\.net\./,
    /org\.flowable/,
  ]) {
    assert.doesNotMatch(controller, forbidden);
  }

  const controllerTest = text(controllerTestPath);
  for (const required of [
    /authorizedPendingTaskReturnsNoStoreProviderRequiredViewWhenRuntimeIsAbsent/,
    /authorizedPendingTaskReportsExplicitGenerationAvailableWhenRuntimeExists/,
    /everyClosedP2UseCaseCanBeReadWithoutProviderInvocation/,
    /tenantOperatorOrTaskMismatchReturnsNoStoreNotFound/,
    /Availability\.AVAILABLE/,
    /Availability\.PROVIDER_NOT_CONFIGURED/,
    /EXPLICIT_GENERATION_REQUIRED/,
    /EXPLICIT_GENERATION_UNAVAILABLE/,
    /assertFalse\(body\.providerInvocationStarted\(\)\)/,
    /assertFalse\(body\.providerSelectable\(\)\)/,
    /assertFalse\(body\.commandAvailable\(\)\)/,
    /assertFalse\(body\.resultAvailable\(\)\)/,
    /assertNull\(body\.advisoryResult\(\)\)/,
  ]) {
    assert.match(controllerTest, required);
  }
  assert.doesNotMatch(controllerTest, /NO_ADVISORY_RESULT_AVAILABLE/);

  const serverPom = text(serverPomPath);
  assert.match(serverPom, /<artifactId>approval-ai-spi<\/artifactId>/);
  assert.match(serverPom, /<artifactId>approval-ai-core<\/artifactId>/);
  assert.match(serverPom, /<artifactId>approval-ai-openai<\/artifactId>/);

  const versioned = filesUnder(migrationRoot).map((file) => {
    const name = path.basename(file);
    const match = /^V(\d+)__/.exec(name);
    return match ? { name, version: Number(match[1]) } : null;
  }).filter(Boolean);
  assert.deepEqual(
    versioned.filter(({ version }) => version === 49).map(({ name }) => name),
    ['V49__create_ai_approval_assistance_durable_evidence.sql'],
  );
  assert.deepEqual(versioned.filter(({ version }) => version >= 50), []);
});

test('GET stays zero-egress while explicit POST remains separate', () => {
  for (const requiredPath of [
    webApiPath,
    mobileApiPath,
    webPanelPath,
    mobilePanelPath,
    webDetailPath,
    mobileDetailPath,
  ]) {
    assert.equal(existsSync(requiredPath), true, `missing assistance client ${requiredPath}`);
  }

  const webApi = text(webApiPath);
  const mobileApi = text(mobileApiPath);
  const clients = `${webApi}\n${mobileApi}`;
  for (const required of [
    /MATERIAL_COMPLETENESS/,
    /RISK_REVIEW/,
    /SUMMARY/,
    /advisoryResult: null/,
    /assertionStatus: 'UNVERIFIED_ADVISORY'/,
    /authority: 'ADVISORY'/,
    /availability: 'AVAILABLE' \| 'PROVIDER_NOT_CONFIGURED'/,
    /code: 'AI_ASSISTANCE_AVAILABLE' \| 'AI_ASSISTANCE_PROVIDER_REQUIRED'/,
    /commandAvailable: false/,
    /needsHumanReview: true/,
    /providerInvocationStarted: false/,
    /providerSelectable: false/,
    /resultAvailable: false/,
  ]) {
    assert.match(clients, required);
  }
  assert.doesNotMatch(clients, /AI_ASSISTANCE_P6_PROVIDER_REQUIRED/);

  for (const api of [webApi, mobileApi]) {
    const find = exportedFunction(api, 'findApprovalAssistance');
    assert.match(find, /\/approval\/tasks\/\$\{encodeURIComponent\(taskId\)\}\/assistance/);
    assert.match(find, /method: 'GET'/);
    assert.doesNotMatch(find, /method: 'POST'|assistance\/generations/);

    const generate = exportedFunction(api, 'generateApprovalAssistance');
    assert.match(generate, /assistance\/generations/);
    assert.match(generate, /method: 'POST'/);
    assert.match(generate, /JSON\.stringify\(\{ useCase \}\)/);
  }

  for (const forbidden of [
    /Idempotency-Key/i,
    /X-Approval-Reason/i,
    /approveTask|rejectTask|transferTask|resubmitTask|withdrawInstance|retrieveTask/,
    /providerId|modelId|routeId|promptTemplateId|secret/i,
  ]) {
    assert.doesNotMatch(clients, forbidden);
  }

  const webPanel = text(webPanelPath);
  const mobilePanel = text(mobilePanelPath);
  const panels = `${webPanel}\n${mobilePanel}`;
  for (const required of [
    /AI 辅助（未验证）/,
    /ADVISORY/,
    /UNVERIFIED_ADVISORY/,
    /必须人工复核/,
    /AI 不拥有审批权限/,
    /显式生成 AI 建议/,
    /仅本次点击触发一次/,
    /generateApprovalAssistance/,
    /@click="generateAssistance"/,
    /本区域不会填写审批意见/,
    /不提供同意、驳回、转办或其他命令/,
    /findApprovalAssistance/,
    /DEFAULT_USE_CASES/,
  ]) {
    assert.match(panels, required);
  }
  for (const forbidden of [
    /v-model=['"](?:opinion|approvalComment|formValues)/,
    /approveTask|rejectTask|transferTask|resubmitTask|withdrawInstance|retrieveTask/,
    /@click=['"]submit(?:Approval|Rejection|Transfer|Resubmission)/,
    /providerId|modelId|routeId|promptTemplateId|secret/i,
    /setInterval|poll|autoGenerate/i,
  ]) {
    assert.doesNotMatch(panels, forbidden);
  }

  const webDetail = text(webDetailPath);
  assert.match(
    webDetail,
    /import ApprovalAssistancePanel from '#\/components\/approval\/ApprovalAssistancePanel\.vue'/,
  );
  assert.match(
    webDetail,
    /<ApprovalAssistancePanel v-if="!revisionTask" :task-id="selectedTask\.taskId"\/>/,
  );

  const mobileDetail = text(mobileDetailPath);
  assert.match(
    mobileDetail,
    /import ApprovalAssistancePanel from '@\/components\/approval\/ApprovalAssistancePanel\.vue'/,
  );
  assert.match(
    mobileDetail,
    /<ApprovalAssistancePanel v-if="!revisionTask" :task-id="details\.taskId" \/>/,
  );
});
