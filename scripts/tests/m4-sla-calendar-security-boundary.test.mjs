import assert from 'node:assert/strict';
import test from 'node:test';
import {
  clientRoots, exists, filesUnder, path, text, textExtensions,
} from './m4-sla-calendar-boundary-support.mjs';

test('browser and mobile never manufacture trusted tenant operator permission or worker identity', async () => {
  const forbidden = ['X-Tenant-Id', 'X-Operator-Id', 'X-Approval-Trusted-Permissions', 'X-Approval-Worker-Id'];
  const violations = [];
  for (const clientRoot of clientRoots) {
    for (const file of await filesUnder(clientRoot, textExtensions)) {
      const content = await text(file);
      for (const header of forbidden) if (content.includes(header)) violations.push(`${file}: ${header}`);
    }
  }
  assert.deepEqual(violations, [], `trusted client identity remains:\n${violations.join('\n')}`);
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
