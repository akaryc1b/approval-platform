import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
const clientRoots = [
  'apps/web/overlay/apps/web-ele/src',
  'apps/mobile/overlay/src',
];
const clientExtensions = new Set(['.js', '.mjs', '.ts', '.tsx', '.vue']);

async function text(relativePath) {
  return readFile(path.join(root, relativePath), 'utf8');
}

async function filesUnder(relativePath, acceptedExtensions = null) {
  const result = [];
  async function visit(current) {
    for (const entry of await readdir(path.join(root, current), { withFileTypes: true })) {
      const next = path.join(current, entry.name);
      if (entry.isDirectory()) {
        await visit(next);
      } else if (!acceptedExtensions || acceptedExtensions.has(path.extname(entry.name))) {
        result.push(next);
      }
    }
  }
  await visit(relativePath);
  return result;
}

test('production identity is principal-backed and local headers are profile-gated', async () => {
  const base = await text('apps/server/src/main/resources/application.yml');
  const local = await text('apps/server/src/main/resources/application-local.yml');
  const configuration = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
      + 'ApprovalIdentitySecurityConfiguration.java',
  );

  assert.match(base, /mode:\s*\$\{APPROVAL_IDENTITY_MODE:principal\}/);
  assert.doesNotMatch(base, /mode:\s*local-headers/);
  assert.match(local, /mode:\s*local-headers/);
  assert.doesNotMatch(local, /enforced:\s*false/);
  assert.match(configuration, /requires the explicit local or test profile/);
});

test('trusted request wrapper owns tenant operator permissions and correlation', async () => {
  const filter = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/security/'
      + 'ApprovalIdentityContextFilter.java',
  );

  assert.match(filter, /instanceof ApprovalPrincipal approvalPrincipal/);
  assert.match(filter, /return principal\.tenantId\(\)/);
  assert.match(filter, /return principal\.operatorId\(\)/);
  assert.match(filter, /localPermissionHeader\.equalsIgnoreCase\(name\)/);
  assert.match(filter, /APPROVAL_REQUEST_ID_INVALID/);
  assert.match(filter, /APPROVAL_TENANT_CONTEXT_MISMATCH/);
  assert.match(filter, /APPROVAL_PRINCIPAL_DISABLED/);
  assert.match(filter, /APPROVAL_SESSION_EXPIRED/);
});

test('browser and mobile production sources cannot manufacture trusted permissions', async () => {
  const trustedHeader = 'X-Approval-Trusted-Permissions';
  for (const clientRoot of clientRoots) {
    for (const file of await filesUnder(clientRoot, clientExtensions)) {
      const content = await text(file);
      assert.equal(
        content.includes(trustedHeader),
        false,
        `${file} must not manufacture the trusted permission header`,
      );
    }
  }
});

test('enterprise responsibility roles and sources remain closed', async () => {
  const roles = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/security/'
      + 'ApprovalEnterpriseRole.java',
  );
  const sources = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/security/'
      + 'ApprovalResponsibilitySourceType.java',
  );
  const roleNames = [...roles.matchAll(/^\s*([A-Z][A-Z_]+)\("/gm)]
    .map((match) => match[1]);
  const sourceNames = [...sources.matchAll(/^\s*([A-Z][A-Z_]+)(?:,|$)/gm)]
    .map((match) => match[1]);

  assert.deepEqual(roleNames, [
    'PLATFORM_ADMIN',
    'TENANT_ADMIN',
    'PROCESS_DESIGNER',
    'PROCESS_PUBLISHER',
    'AUDITOR',
    'OPERATIONS',
    'DEPARTMENT_APPROVAL_ADMIN',
    'DATA_ARCHIVE_ADMIN',
    'CONNECTOR_ADMIN',
    'PARTICIPANT',
  ]);
  assert.deepEqual(sourceNames, [
    'PERSON',
    'DEPARTMENT',
    'POSITION',
    'ROLE',
    'USER_GROUP',
  ]);
});

test('management endpoint scan validates capability and resource scope declarations', async () => {
  const contract = await text(
    'apps/server/src/test/java/io/github/akaryc1b/approval/api/'
      + 'ApprovalManagementEndpointContractTest.java',
  );
  const permission = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
      + 'ApprovalManagementPermission.java',
  );

  assert.match(contract, /management endpoints without capabilities/);
  assert.match(contract, /management endpoints with invalid resource declarations/);
  assert.match(contract, /case TENANT -> !departmentVariableDeclared/);
  assert.match(contract, /case DEPARTMENT -> departmentVariableDeclared/);
  assert.match(permission, /ResourceScope resourceScope\(\) default ResourceScope\.TENANT/);
  assert.match(permission, /String departmentPathVariable\(\) default ""/);
});

test('high-risk management access requires reason idempotency and audit evidence', async () => {
  const permission = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
      + 'ApprovalManagementPermission.java',
  );
  const interceptor = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
      + 'ApprovalManagementPermissionInterceptor.java',
  );
  const recorder = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/security/'
      + 'DefaultApprovalManagementGovernanceRecorder.java',
  );
  const auditContract = await text(
    'server-modules/approval-domain/src/main/java/io/github/akaryc1b/approval/domain/audit/'
      + 'AuditEventContract.java',
  );

  for (const capability of [
    'PUBLISH',
    'DEPLOY',
    'ACTIVATE',
    'TRANSFER',
    'AUDIT_EXPORT',
    'AUDIT_VERIFY',
    'CONSISTENCY_RUN',
    'OPERATIONAL_FAILURE_REPLAY',
  ]) {
    assert.match(
      permission,
      new RegExp(`${capability}\\([\\s\\S]*?true\\s*\\)`),
      `${capability} must remain high risk`,
    );
  }
  assert.match(interceptor, /X-Approval-Operation-Reason/);
  assert.match(interceptor, /Idempotency-Key/);
  assert.match(interceptor, /governance\.recordAuthorized/);
  assert.match(interceptor, /APPROVAL_MANAGEMENT_AUDIT_UNAVAILABLE/);
  assert.doesNotMatch(interceptor, /LOGGER\.[\s\S]{0,300}OPERATION_REASON_HEADER/);
  assert.match(recorder, /MANAGEMENT_HIGH_RISK_AUTHORIZED/);
  assert.match(recorder, /values\.put\("reason", reason\)/);
  assert.match(recorder, /idempotency\.execute/);
  assert.match(auditContract, /approval\.management-security/);
  assert.match(auditContract, /"reason"/);
});

test('management authorization metrics remain low cardinality', async () => {
  const interceptor = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
      + 'ApprovalManagementPermissionInterceptor.java',
  );
  const counterCalls = [...interceptor.matchAll(
    /meters\.counter\(([\s\S]*?)\)\.increment\(\);/g,
  )];
  assert.ok(counterCalls.length > 0, 'authorization counter call is missing');
  for (const [, tags] of counterCalls) {
    assert.match(tags, /"requirement"/);
    assert.match(tags, /"outcome"/);
    assert.match(tags, /"decision"/);
    assert.match(tags, /"role"/);
    assert.match(tags, /"resource_scope"/);
    assert.doesNotMatch(
      tags,
      /tenantId|operatorId|requestId|traceId|instanceId|taskId|departmentId/,
    );
  }
});

test('management roles never become task participation authority', async () => {
  const principal = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/security/'
      + 'ApprovalPrincipal.java',
  );
  const resolver = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/security/'
      + 'DefaultApprovalResponsibilityResolver.java',
  );

  assert.match(principal, /boolean hasAuthority\(String authority\)/);
  assert.match(principal, /boolean hasEnterpriseRole\(ApprovalEnterpriseRole role\)/);
  assert.doesNotMatch(resolver, /approval\.task\./);
  assert.match(
    resolver,
    /matrix\.put\(ApprovalEnterpriseRole\.PARTICIPANT, Set\.of\(\)\)/,
  );
});

test('production source and migrations never depend on Flowable internal tables', async () => {
  const roots = ['apps', 'server-modules', 'integrations', 'examples'];
  const internalTable = /\b(?:ACT_[A-Z0-9_]+|act_[a-z0-9_]+)\b/;
  for (const sourceRoot of roots) {
    for (const file of await filesUnder(sourceRoot)) {
      const normalized = file.split(path.sep).join('/');
      if (!normalized.includes('/src/main/')) continue;
      if (!/\.(?:java|sql|xml|ya?ml)$/.test(normalized)) continue;
      const content = await text(file);
      assert.doesNotMatch(
        content,
        internalTable,
        `${normalized} must not reference Flowable internal tables`,
      );
    }
  }
});
