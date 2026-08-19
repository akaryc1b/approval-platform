import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const repositoryRoot = fileURLToPath(new URL('../..', import.meta.url));
const launcherPath = join(
  repositoryRoot,
  'scripts/product-readiness/demo-client.mjs',
);
const manifestPath = join(
  repositoryRoot,
  'config/demo/cross-client-local-demo.json',
);
const scenarioPath = join(
  repositoryRoot,
  'config/demo/purchase-payment-golden-path.json',
);
const webRoot = join(repositoryRoot, 'apps/web/overlay/apps/web-ele');
const mobileRoot = join(repositoryRoot, 'apps/mobile/overlay');

async function text(...segments) {
  return readFile(join(repositoryRoot, ...segments), 'utf8');
}

async function json(...segments) {
  return JSON.parse(await text(...segments));
}

function runLauncher(...arguments_) {
  return spawnSync(process.execPath, [launcherPath, ...arguments_], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    shell: false,
  });
}

test('cross-client plan binds the canonical purchase-payment scenario', async () => {
  const manifest = await json('config/demo/cross-client-local-demo.json');
  const scenario = await json('config/demo/purchase-payment-golden-path.json');
  const output = execFileSync(
    process.execPath,
    [launcherPath, 'plan', '--json'],
    { cwd: repositoryRoot, encoding: 'utf8' },
  );
  const plan = JSON.parse(output);

  assert.equal(manifest.scenarioManifest, 'config/demo/purchase-payment-golden-path.json');
  assert.equal(manifest.tenantId, scenario.tenant.id);
  assert.equal(manifest.businessKey, scenario.request.businessKey);
  assert.equal(manifest.connectorKey, scenario.directory.connectorKey);
  assert.equal(plan.tenantId, scenario.tenant.id);
  assert.equal(plan.businessKey, scenario.request.businessKey);
  assert.deepEqual(plan.expectedHandoff, manifest.expectedHandoff);
  assert.deepEqual(plan.evidenceKeys, manifest.evidenceKeys);
  assert.deepEqual(plan.nonClaims, manifest.nonClaims);

  const expectedHandoff = scenario.expectedWorkflow.flatMap(step =>
    step.actorIds.map(actorId => ({
      actorId,
      taskDefinitionKey: step.taskDefinitionKey,
    })));
  assert.deepEqual(
    manifest.expectedHandoff.map(({ actorId, taskDefinitionKey }) => ({
      actorId,
      taskDefinitionKey,
    })),
    expectedHandoff,
  );
});

test('local identity headers are explicit, development-only and transport-owned', async () => {
  const webRuntime = await readFile(
    join(webRoot, 'src/platform/approval/runtime.ts'),
    'utf8',
  );
  const webTransport = await readFile(
    join(webRoot, 'src/api/approval/transport.ts'),
    'utf8',
  );
  const mobileRuntime = await readFile(
    join(mobileRoot, 'src/platform/approval/runtime.ts'),
    'utf8',
  );
  const mobileTransport = await readFile(
    join(mobileRoot, 'src/api/approval/transport.ts'),
    'utf8',
  );

  for (const runtime of [webRuntime, mobileRuntime]) {
    assert.match(runtime, /VITE_APPROVAL_LOCAL_IDENTITY_HEADERS/);
    assert.match(runtime, /import\.meta\.env\.DEV/);
    assert.match(runtime, /isLocalDemoApiBaseUrl/);
    assert.match(runtime, /localIdentityHeaders/);
    assert.match(runtime, /private|私有网络/i);
    assert.doesNotMatch(runtime, /MODE\s*===\s*['"]production['"]\s*\?\s*true/);
  }

  assert.match(webTransport, /headers\.delete\('X-Tenant-Id'\)/);
  assert.match(webTransport, /headers\.delete\('X-Operator-Id'\)/);
  assert.match(webTransport, /if \(runtime\.localIdentityHeaders\)/);
  assert.match(webTransport, /headers\.set\('X-Tenant-Id', runtime\.tenantId\)/);
  assert.match(webTransport, /headers\.set\('X-Operator-Id', runtime\.operatorId\)/);

  assert.match(mobileTransport, /normalized !== 'x-tenant-id'/);
  assert.match(mobileTransport, /normalized !== 'x-operator-id'/);
  assert.match(mobileTransport, /if \(runtime\.localIdentityHeaders\)/);
  assert.match(mobileTransport, /headers\['X-Tenant-Id'\] = runtime\.tenantId/);
  assert.match(mobileTransport, /headers\['X-Operator-Id'\] = runtime\.operatorId/);

  for (const source of [webRuntime, webTransport, mobileRuntime, mobileTransport]) {
    assert.doesNotMatch(source, /X-Approval-Trusted-Permissions/);
  }
});

test('PC and H5 use bounded approval proxies while WeChat requires a private origin', async () => {
  const webVite = await readFile(join(webRoot, 'vite.config.ts'), 'utf8');
  const launcher = await readFile(launcherPath, 'utf8');

  assert.match(webVite, /VITE_APPROVAL_DEV_PROXY_TARGET/);
  assert.match(webVite, /'\/approval-api'/);
  assert.match(webVite, /replace\(\/\^\\\/approval-api\/, '\/api'\)/);
  assert.match(webVite, /target\.protocol !== 'http:'/);
  assert.match(webVite, /isPrivateIpv4/);
  assert.doesNotMatch(webVite, /VITE_APPROVAL_DEV_PROXY_TARGET.*https:\/\//s);

  assert.match(launcher, /VITE_APPROVAL_API_URL: '\/approval-api'/);
  assert.match(launcher, /VITE_APP_PROXY_ENABLE: 'true'/);
  assert.match(launcher, /VITE_APP_PROXY_PREFIX: '\/approval-api'/);
  assert.match(launcher, /VITE_SERVER_BASEURL: `\$\{resolved\.backendOrigin\}\/api`/);
  assert.match(launcher, /VITE_APPROVAL_API_URL: `\$\{resolved\.backendOrigin\}\/api`/);
  assert.match(launcher, /normalizeBackendOrigin/);
  assert.match(launcher, /local\/private HTTP origin/);
});

test('launcher rejects unknown actors, public targets and unsafe ports before execution', () => {
  const unknownActor = runLauncher(
    'pc', '--actor', 'unknown-user', '--skip-install',
  );
  assert.equal(unknownActor.status, 2);
  assert.match(unknownActor.stderr, /is not allowed for pc/);

  const publicTarget = runLauncher(
    'h5', '--backend-origin', 'https://example.com', '--skip-install',
  );
  assert.equal(publicTarget.status, 2);
  assert.match(publicTarget.stderr, /local\/private HTTP origin/);

  const unsafePort = runLauncher(
    'pc', '--port', '80', '--skip-install',
  );
  assert.equal(unsafePort.status, 2);
  assert.match(unsafePort.stderr, /between 1024 and 65535/);

  const invalidPlanOption = runLauncher('plan', '--actor', 'demo-manager');
  assert.equal(invalidPlanOption.status, 2);
  assert.match(invalidPlanOption.stderr, /plan does not accept actor/);
});

test('launcher uses fixed executables and shell-free argument arrays', async () => {
  const launcher = await readFile(launcherPath, 'utf8');

  assert.match(launcher, /spawnSync\(process\.execPath, args/);
  assert.match(launcher, /spawnSync\(pnpmExecutable\(\), args/);
  assert.match(launcher, /spawn\(pnpmExecutable\(\), clientArguments/);
  assert.match(launcher, /shell: false/g);
  assert.match(launcher, /const commands = new Set\(\['plan', \.\.\.clientCommands\]\)/);
  assert.match(launcher, /const clientCommands = new Set\(\['pc', 'h5', 'wechat'\]\)/);
  assert.doesNotMatch(launcher, /function\s+run\s*\(/);
  assert.doesNotMatch(launcher, /function\s+runChecked\s*\([^)]*command/);
  assert.doesNotMatch(launcher, /spawn(?:Sync)?\(command/);
  assert.doesNotMatch(launcher, /shell:\s*true/);
  assert.doesNotMatch(launcher, /\bexec\s*\(/);
});

test('package commands and existing user pages expose the real approval workflow', async () => {
  const packageJson = await json('package.json');
  const webWorkbench = await readFile(
    join(webRoot, 'src/views/approval/workbench/index.vue'),
    'utf8',
  );
  const webRoutes = await readFile(
    join(webRoot, 'src/router/routes/modules/approval.ts'),
    'utf8',
  );
  const mobileList = await readFile(
    join(mobileRoot, 'src/pages/task/list.vue'),
    'utf8',
  );
  const mobileDetail = await readFile(
    join(mobileRoot, 'src/pages/task/detail.vue'),
    'utf8',
  );

  assert.equal(
    packageJson.scripts['demo:client:plan'],
    'node scripts/product-readiness/demo-client.mjs plan --json',
  );
  assert.equal(
    packageJson.scripts['demo:client:pc'],
    'node scripts/product-readiness/demo-client.mjs pc',
  );
  assert.equal(
    packageJson.scripts['demo:client:h5'],
    'node scripts/product-readiness/demo-client.mjs h5',
  );
  assert.equal(
    packageJson.scripts['demo:client:wechat'],
    'node scripts/product-readiness/demo-client.mjs wechat',
  );
  assert.match(
    packageJson.scripts['web:test:client-boundary'],
    /product-readiness-cross-client-local-demo-boundary\.test\.mjs/,
  );
  assert.match(packageJson.scripts['mobile:dev:weixin'], /dev:mp-weixin/);

  assert.match(webRoutes, /path: '\/approval\/workbench'/);
  assert.match(webWorkbench, /findPendingTasks/);
  assert.match(webWorkbench, /findApprovalTimeline/);
  assert.match(webWorkbench, /approveTask/);
  assert.match(webWorkbench, /rejectTask/);

  assert.match(mobileList, /findPendingTasks/);
  assert.match(mobileList, /pages\/task\/detail/);
  assert.match(mobileDetail, /findPendingTask/);
  assert.match(mobileDetail, /approveTask/);
  assert.match(mobileDetail, /rejectTask/);
});

test('product documentation preserves runtime and acceptance non-claims', async () => {
  const guide = await text('docs/product-readiness/CROSS_CLIENT_LOCAL_DEMO.md');
  const index = await text('docs/product-readiness/README.md');
  const manifest = await json('config/demo/cross-client-local-demo.json');

  for (const marker of manifest.nonClaims) {
    assert.match(guide, new RegExp(marker));
  }
  assert.match(guide, /demo:client:pc/);
  assert.match(guide, /demo:client:h5/);
  assert.match(guide, /demo:client:wechat/);
  assert.match(guide, /DEMO-PP-0001/);
  assert.match(guide, /demo-purchase-payment/);
  assert.match(index, /CROSS_CLIENT_LOCAL_DEMO\.md/);
  assert.match(index, /LOCAL_CLIENT_LAUNCHERS_IMPLEMENTED_RUNTIME_NOT_EXECUTED/);
  assert.match(index, /CROSS_CLIENT_RUNTIME_NOT_EXECUTED/);
  assert.doesNotMatch(index, /PC_H5_WECHAT_RUNTIME_PASSED/);
});
