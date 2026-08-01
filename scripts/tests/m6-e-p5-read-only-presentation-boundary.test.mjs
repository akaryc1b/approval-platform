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

test('P5 server surface is tenant-scoped GET-only and unavailable before P6', () => {
  for (const requiredPath of [
    contractPath,
    controllerPath,
    controllerTestPath,
    serverPomPath,
  ]) {
    assert.equal(existsSync(requiredPath), true, `missing P5 server source ${requiredPath}`);
  }

  const contract = text(contractPath);
  for (const required of [
    /List\.of\(UseCase\.values\(\)\)/,
    /PROVIDER_NOT_CONFIGURED/,
    /AI_ASSISTANCE_P6_PROVIDER_REQUIRED/,
    /Authority\.ADVISORY/,
    /AssertionStatus\.UNVERIFIED_ADVISORY/,
    /HUMAN_REVIEW_REQUIRED/,
    /no deterministic mock is used in production/i,
    /providerInvocationStarted/,
    /providerSelectable/,
    /commandAvailable/,
    /resultAvailable/,
    /advisoryResult != null/,
    /TaskSnapshot\.from\(task\)/,
    /P5 pre-P6 assistance view must remain unavailable and non-authoritative/,
  ]) {
    assert.match(contract, required);
  }
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
    /AssistanceView\.providerRequired/,
  ]) {
    assert.match(controller, required);
  }
  for (const forbidden of [
    /@(?:Post|Put|Patch|Delete)Mapping/,
    /@ApprovalManagementPermission/,
    /ApprovalCommand/,
    /ApprovalAssistanceSynchronousOrchestrator/,
    /ApprovalAssistanceDurableEvidenceStore/,
    /AiAdvisoryProvider/,
    /SecretMaterial/,
    /HttpClient/,
    /java\.net\./,
    /org\.flowable/,
  ]) {
    assert.doesNotMatch(controller, forbidden);
  }

  const controllerTest = text(controllerTestPath);
  for (const required of [
    /authorizedPendingTaskReturnsNoStoreProviderRequiredView/,
    /everyClosedP2UseCaseCanBeRequestedWithoutProviderInvocation/,
    /tenantOperatorOrTaskMismatchReturnsNoStoreNotFound/,
    /List\.of\(UseCase\.values\(\)\)/,
    /assertFalse\(body\.providerInvocationStarted\(\)\)/,
    /assertFalse\(body\.providerSelectable\(\)\)/,
    /assertFalse\(body\.commandAvailable\(\)\)/,
    /assertFalse\(body\.resultAvailable\(\)\)/,
    /assertNull\(body\.advisoryResult\(\)\)/,
  ]) {
    assert.match(controllerTest, required);
  }

  const serverPom = text(serverPomPath);
  assert.match(serverPom, /<artifactId>approval-ai-spi<\/artifactId>/);
  assert.match(serverPom, /<artifactId>approval-ai-core<\/artifactId>/);

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

test('P5 Web and Mobile clients expose only read-only advisory presentation', () => {
  for (const requiredPath of [
    webApiPath,
    mobileApiPath,
    webPanelPath,
    mobilePanelPath,
    webDetailPath,
    mobileDetailPath,
  ]) {
    assert.equal(existsSync(requiredPath), true, `missing P5 client source ${requiredPath}`);
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
    /availability: 'PROVIDER_NOT_CONFIGURED'/,
    /commandAvailable: false/,
    /needsHumanReview: true/,
    /providerInvocationStarted: false/,
    /providerSelectable: false/,
    /resultAvailable: false/,
    /\/approval\/tasks\/\$\{encodeURIComponent\(taskId\)\}\/assistance/,
    /method: 'GET'/,
  ]) {
    assert.match(clients, required);
  }
  for (const forbidden of [
    /method:\s*['"](?:POST|PUT|PATCH|DELETE)['"]/i,
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
    /生产 AI Provider 尚未配置/,
    /当前不会生成或伪造任何 AI 内容/,
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
