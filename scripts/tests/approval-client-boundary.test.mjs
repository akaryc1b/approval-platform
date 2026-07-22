import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import { extname, join, relative } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const repositoryRoot = fileURLToPath(new URL('../..', import.meta.url));
const webRoot = join(repositoryRoot, 'apps/web/overlay/apps/web-ele/src');
const mobileRoot = join(repositoryRoot, 'apps/mobile/overlay/src');
const sourceExtensions = new Set(['.js', '.mjs', '.ts', '.tsx', '.vue']);

async function sourceFiles(root) {
  const entries = await readdir(root, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) files.push(...await sourceFiles(path));
    else if (sourceExtensions.has(extname(entry.name))) files.push(path);
  }
  return files;
}

async function sources(root) {
  const files = await sourceFiles(root);
  return Promise.all(files.map(async path => ({
    content: await readFile(path, 'utf8'),
    path,
  })));
}

function displayPath(path) {
  return relative(repositoryRoot, path);
}

test('browser and mobile overlays cannot forge trusted management authorities', async () => {
  const files = [...await sources(webRoot), ...await sources(mobileRoot)];
  const offenders = files
    .filter(file => file.content.includes('X-Approval-Trusted-Permissions'))
    .map(file => displayPath(file.path));
  assert.deepEqual(offenders, []);
});

test('mobile participant overlay cannot reference tenant management endpoints', async () => {
  const offenders = (await sources(mobileRoot))
    .filter(file => file.content.includes('/approval/management/'))
    .map(file => displayPath(file.path));
  assert.deepEqual(offenders, []);
});

test('web management API modules use the governed approval transport', async () => {
  const webSources = await sources(webRoot);
  const managementModules = webSources.filter(file =>
    file.content.includes('/approval/management/'));
  assert.ok(managementModules.length >= 3, 'expected management API modules');
  for (const module of managementModules) {
    assert.match(
      module.content,
      /#\/api\/approval\/transport/,
      `${displayPath(module.path)} bypasses governed transport`,
    );
    assert.doesNotMatch(
      module.content,
      /\bfetch\s*\(/,
      `${displayPath(module.path)} calls fetch directly`,
    );
  }

  for (const modulePath of [
    'api/approval/audit.ts',
    'api/approval/consistency.ts',
    'api/approval/handovers.ts',
    'api/approval/operational-failures.ts',
  ]) {
    const content = await readFile(join(webRoot, modulePath), 'utf8');
    assert.match(content, /#\/api\/approval\/transport/);
  }
});

test('mobile participant API modules use the governed approval transport', async () => {
  const apiRoot = join(mobileRoot, 'api/approval');
  const apiSources = await sources(apiRoot);
  const directRequestOffenders = apiSources
    .filter(file => !file.path.endsWith('transport.ts'))
    .filter(file => file.content.includes('uni.request('))
    .map(file => displayPath(file.path));
  assert.deepEqual(directRequestOffenders, []);

  for (const modulePath of [
    'comments.ts',
    'delegations.ts',
    'forms.ts',
    'identities.ts',
    'index.ts',
    'notifications.ts',
    'task-collaboration.ts',
  ]) {
    const content = await readFile(join(apiRoot, modulePath), 'utf8');
    assert.match(
      content,
      /@\/api\/approval\/transport/,
      `${modulePath} bypasses governed mobile transport`,
    );
  }
});

test('governed transports preserve structured error and request tracing fields', async () => {
  const webTransport = await readFile(join(webRoot, 'api/approval/transport.ts'), 'utf8');
  for (const field of ['code', 'requestId', 'retryable', 'status']) {
    assert.match(webTransport, new RegExp(`readonly ${field}`));
  }
  assert.match(webTransport, /response\.headers\.get\('X-Request-Id'\)/);
  assert.match(webTransport, /credentials: 'same-origin'/);
  assert.match(webTransport, /approvalRequestWithTrace/);
  assert.match(webTransport, /APPROVAL_NETWORK_ERROR/);
  assert.doesNotMatch(webTransport, /Trusted-Permissions/);

  const mobileTransport = await readFile(join(mobileRoot, 'api/approval/transport.ts'), 'utf8');
  for (const field of ['code', 'requestId', 'retryable', 'status']) {
    assert.match(mobileTransport, new RegExp(`readonly ${field}`));
  }
  assert.match(mobileTransport, /APPROVAL_NETWORK_ERROR/);
  assert.match(mobileTransport, /allowNotFound/);
  assert.match(mobileTransport, /X-Request-Id/);
  assert.doesNotMatch(mobileTransport, /Trusted-Permissions/);
});

test('management mutations surface success request tracing', async () => {
  const audit = await readFile(join(webRoot, 'api/approval/audit.ts'), 'utf8');
  const failures = await readFile(
    join(webRoot, 'api/approval/operational-failures.ts'),
    'utf8',
  );
  assert.match(audit, /approvalRequestWithTrace/);
  assert.match(audit, /operationRequestId/);
  assert.match(failures, /approvalRequestWithTrace/);
  assert.match(failures, /requestId/);
});

test('management routes declare host-side capability hints', async () => {
  const auditRoutes = await readFile(
    join(webRoot, 'router/routes/modules/approval-audit.ts'),
    'utf8',
  );
  const approvalRoutes = await readFile(
    join(webRoot, 'router/routes/modules/approval.ts'),
    'utf8',
  );
  assert.match(auditRoutes, /authority: \['approval:audit:view'\]/);
  assert.match(approvalRoutes, /name: 'ApprovalHandovers'[\s\S]*authority: \['approval:ops:view'\]/);
  assert.match(approvalRoutes, /name: 'ApprovalOperations'[\s\S]*authority: \['approval:ops:view'\]/);
  assert.match(approvalRoutes, /name: 'ApprovalOperationalFailures'[\s\S]*authority: \['approval:ops:view'\]/);
});

test('process release mutations require explicit bounded reason headers', async () => {
  const design = await readFile(join(webRoot, 'api/approval/process-design.ts'), 'utf8');
  const effective = await readFile(join(webRoot, 'api/approval/effective-release.ts'), 'utf8');

  assert.match(design, /globalThis\.prompt/);
  assert.match(design, /X-Approval-Operation-Reason/);
  assert.match(design, /length < 8 \|\| length > 512/);
  assert.match(effective, /X-Approval-Operation-Reason/);
  assert.match(effective, /length < 8 \|\| length > 512/);
  assert.match(effective, /JSON\.stringify\(\{ expectedRevision \}\)/);
  assert.doesNotMatch(effective, /JSON\.stringify\(\{ expectedRevision, reason \}\)/);

  for (const content of [design, effective]) {
    assert.doesNotMatch(content, /X-(?:Tenant|Operator)-Id/);
    assert.doesNotMatch(content, /audit(?:Chain)?Reference\s*:/i);
  }
});
