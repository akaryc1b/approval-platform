import assert from 'node:assert/strict';
import test from 'node:test';
import {
  clientRoots, exists, filesUnder, path, text, textExtensions,
} from './m4-sla-calendar-boundary-support.mjs';

const localDemoIdentityFiles = new Set([
  'apps/web/overlay/apps/web-ele/src/api/approval/transport.ts',
  'apps/mobile/overlay/src/api/approval/transport.ts',
]);

test('clients keep trusted identity server-owned except bounded local demo headers', async () => {
  const alwaysForbidden = [
    'X-Approval-Trusted-Permissions',
    'X-Approval-Worker-Id',
  ];
  const localIdentityHeaders = ['X-Tenant-Id', 'X-Operator-Id'];
  const violations = [];
  const localHeaderFiles = new Set();

  for (const clientRoot of clientRoots) {
    for (const file of await filesUnder(clientRoot, textExtensions)) {
      const normalized = file.split(path.sep).join('/');
      const content = await text(file);
      for (const header of alwaysForbidden) {
        if (content.includes(header)) violations.push(`${normalized}: ${header}`);
      }
      const presentLocalHeaders = localIdentityHeaders.filter(header =>
        content.includes(header));
      if (presentLocalHeaders.length === 0) continue;
      if (!localDemoIdentityFiles.has(normalized)) {
        for (const header of presentLocalHeaders) {
          violations.push(`${normalized}: ${header}`);
        }
        continue;
      }

      localHeaderFiles.add(normalized);
      for (const header of localIdentityHeaders) {
        assert.equal(
          content.split(header).length - 1,
          1,
          `${normalized} must declare ${header} exactly once`,
        );
      }
      assert.match(
        content,
        /if \(runtime\.localDemo\) \{[\s\S]{0,400}X-Tenant-Id[\s\S]{0,400}X-Operator-Id[\s\S]{0,400}\}/u,
        `${normalized} must guard local identity headers with runtime.localDemo`,
      );
    }
  }

  assert.deepEqual(
    [...localHeaderFiles].sort(),
    [...localDemoIdentityFiles].sort(),
    'only the two governed transports may attach local identity headers',
  );
  assert.deepEqual(
    violations,
    [],
    `unbounded client identity remains:\n${violations.join('\n')}`,
  );

  for (const localDemoModule of [
    'apps/web/overlay/apps/web-ele/src/platform/approval/local-demo.ts',
    'apps/mobile/overlay/src/platform/approval/local-demo.ts',
  ]) {
    const content = await text(localDemoModule);
    assert.match(content, /import\.meta\.env\.DEV/);
    assert.match(content, /VITE_APPROVAL_LOCAL_DEMO === 'true'/);
    assert.match(content, /demo-purchase-payment/);
    assert.match(content, /Unknown local demo operator/);
  }

  const baseConfiguration = await text(
    'apps/server/src/main/resources/application.yml',
  );
  const localConfiguration = await text(
    'apps/server/src/main/resources/application-local.yml',
  );
  assert.match(
    baseConfiguration,
    /mode:\s*\$\{APPROVAL_IDENTITY_MODE:principal\}/,
  );
  assert.match(localConfiguration, /mode:\s*local-headers/);
});

test('client sources display server SLA evidence but never manufacture authoritative dueAt', async () => {
  const sources = [
    await text('apps/web/overlay/apps/web-ele/src/api/approval/sla.ts'),
    await text('apps/mobile/overlay/src/api/approval/sla.ts'),
    await text('apps/mobile/overlay/src/pages/task/detail.vue'),
  ];
  assert.match(sources[0], /dueAt: string/);
  assert.match(sources[1], /dueAt: string/);
  assert.match(sources[2], /taskSla\.dueAt/);
  for (const content of sources) {
    assert.doesNotMatch(content, /dueAt\s*[:=]\s*(?:new Date|Date\.now|add|plus)/i);
    assert.doesNotMatch(content, /JSON\.stringify\([\s\S]{0,600}\bdueAt\b/);
  }
});

test('client replay requests never nominate tenant worker or arbitrary target identity', async () => {
  const forbiddenReplayPayload = /(?:tenantId|workerId|leaseOwner)\s*:/i;
  for (const clientRoot of clientRoots) {
    for (const file of await filesUnder(clientRoot, textExtensions)) {
      const content = await text(file);
      const payloads = [
        ...content.matchAll(/\/replay\b[\s\S]{0,1200}?JSON\.stringify\(([^)]*)\)/gi),
        ...content.matchAll(/JSON\.stringify\(([^)]*)\)[\s\S]{0,1200}?\/replay\b/gi),
        ...content.matchAll(/\/replay\b[\s\S]{0,1200}?new URLSearchParams\(([^)]*)\)/gi),
        ...content.matchAll(/new URLSearchParams\(([^)]*)\)[\s\S]{0,1200}?\/replay\b/gi),
      ];
      for (const match of payloads) assert.doesNotMatch(
        match[1] ?? match[0], forbiddenReplayPayload,
        `${file} replay request must use principal tenant and server worker identity`,
      );
    }
  }
});

test('SLA management controllers remain capability governed and principal-scoped', async () => {
  const identityFilter = await text('apps/server/src/main/java/io/github/akaryc1b/approval/security/ApprovalIdentityContextFilter.java');
  assert.match(identityFilter, /return principal\.tenantId\(\)/);
  assert.match(identityFilter, /return principal\.operatorId\(\)/);
  assert.match(identityFilter, /new TrustedApprovalRequest/);
  const controllers = [
    'ApprovalCalendarManagementController.java',
    'ApprovalSlaPolicyManagementController.java',
    'ApprovalSlaInstanceManagementController.java',
  ];
  if (await exists('apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalSlaExecutionManagementController.java')) {
    controllers.push('ApprovalSlaExecutionManagementController.java');
  }
  for (const name of controllers) {
    const content = await text(`apps/server/src/main/java/io/github/akaryc1b/approval/api/${name}`);
    const mappings = [...content.matchAll(/@(Get|Post|Put|Delete|Patch)Mapping\b/g)].length;
    const permissions = [...content.matchAll(/@ApprovalManagementPermission\b/g)].length;
    assert.ok(mappings > 0, `${name} must expose management mappings`);
    assert.ok(permissions >= mappings, `${name} mappings must declare capabilities`);
    assert.doesNotMatch(content, /@RequestParam[^\n]*(?:tenantId|operatorId)/i);
    assert.doesNotMatch(content, /@RequestBody[^\n]*(?:tenantId|operatorId)/i);
  }
  const capability = await text('apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalManagementPermission.java');
  for (const required of ['SLA_READ', 'SLA_DESIGN', 'SLA_PUBLISH', 'SLA_ACTIVATE']) {
    assert.match(capability, new RegExp(`\\b${required}\\b`));
  }
});

test('participant SLA endpoint cannot nominate another tenant or user', async () => {
  const controller = await text('apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalParticipantSlaController.java');
  const mobileClient = await text('apps/mobile/overlay/src/api/approval/sla.ts');
  assert.doesNotMatch(controller, /@RequestParam/);
  assert.doesNotMatch(controller, /X-User-Id|X-Act-As|X-Trusted-User/i);
  assert.doesNotMatch(mobileClient, /[?&](?:userId|tenantId)=/);
  assert.doesNotMatch(mobileClient, /\/approval\/management\//);
  assert.match(mobileClient, /allowNotFound: true/);
});

test('production sources remain independent from Flowable internal tables', async () => {
  const internalTable = /\b(?:ACT_[A-Z0-9_]+|act_[a-z0-9_]+)\b/;
  for (const sourceRoot of ['apps', 'server-modules', 'integrations', 'examples']) {
    for (const file of await filesUnder(sourceRoot)) {
      const normalized = file.split(path.sep).join('/');
      if (!normalized.includes('/src/main/') || !/\.(?:java|sql|xml|ya?ml)$/.test(normalized)) continue;
      assert.doesNotMatch(await text(file), internalTable, `${normalized} references a Flowable internal table`);
    }
  }
});
