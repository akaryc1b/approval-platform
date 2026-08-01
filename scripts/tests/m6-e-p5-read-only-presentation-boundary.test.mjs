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
    /SUMMARY/,
    /MATERIAL_COMPLETENESS/,
    /RISK_REVIEW/,
    /PROVIDER_NOT_CONFIGURED/,
    /AI_ASSISTANCE_P6_PROVIDER_REQUIRED/,
    /Authority\.ADVISORY/,
    /AssertionStatus\.UNVERIFIED_ADVISORY/,
    /HUMAN_REVIEW_REQUIRED/,
    /No deterministic mock is used in production/,
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
