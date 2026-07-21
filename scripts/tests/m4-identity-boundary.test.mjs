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
