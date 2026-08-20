import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

function text(path) {
  const absolute = resolve(root, path)
  assert.equal(existsSync(absolute), true, `missing ${path}`)
  return readFileSync(absolute, 'utf8')
}

function runLauncher(...args) {
  return spawnSync(
    process.execPath,
    [resolve(root, 'scripts/product-readiness/demo-client.mjs'), ...args],
    {
      cwd: root,
      encoding: 'utf8',
      shell: false,
    },
  )
}

const scenario = JSON.parse(text('config/demo/purchase-payment-golden-path.json'))
const launcherManifest = JSON.parse(text('config/demo/cross-client-local-demo.json'))
const launcher = text('scripts/product-readiness/demo-client.mjs')
const webLocalDemo = text(
  'apps/web/overlay/apps/web-ele/src/platform/approval/local-demo.ts',
)
const webRuntime = text(
  'apps/web/overlay/apps/web-ele/src/platform/approval/runtime.ts',
)
const webTransport = text(
  'apps/web/overlay/apps/web-ele/src/api/approval/transport.ts',
)
const webDevelopmentEnv = text(
  'apps/web/overlay/apps/web-ele/.env.development',
)
const webBaseEnv = text('apps/web/overlay/apps/web-ele/.env')
const webVite = text('apps/web/overlay/apps/web-ele/vite.config.ts')
const mobileLocalDemo = text(
  'apps/mobile/overlay/src/platform/approval/local-demo.ts',
)
const mobileRuntime = text(
  'apps/mobile/overlay/src/platform/approval/runtime.ts',
)
const mobileTransport = text(
  'apps/mobile/overlay/src/api/approval/transport.ts',
)
const mobileDevelopmentEnv = text(
  'apps/mobile/overlay/env/.env.development',
)
const mobileBaseEnv = text('apps/mobile/overlay/env/.env')
const packageJson = JSON.parse(text('package.json'))
const guide = text('docs/product-readiness/CROSS_CLIENT_LOCAL_DEMO.md')
const aggregate = text('scripts/tests/m3-repository-hygiene.test.mjs')

const expectedOperators = [
  'demo-employee',
  'demo-manager',
  'demo-finance-reviewer',
  'demo-finance-approver-a',
  'demo-finance-approver-b',
  'demo-admin',
]

test('PC and mobile local demo allowlists match the governed scenario identities', () => {
  const scenarioOperators = scenario.directory.users
    .map(user => user.id)
    .sort()
  assert.deepEqual(scenarioOperators, [...expectedOperators].sort())
  for (const operatorId of expectedOperators) {
    assert.equal(webLocalDemo.includes(operatorId), true, `web missing ${operatorId}`)
    assert.equal(
      mobileLocalDemo.includes(operatorId),
      true,
      `mobile missing ${operatorId}`,
    )
  }
  for (const source of [webLocalDemo, mobileLocalDemo]) {
    assert.match(source, /Unknown local demo operator/u)
    assert.match(source, /demoOperator/u)
    assert.match(source, /demo-purchase-payment/u)
  }
})

test('local demo identity is development-only and uses the exact demo tenant', () => {
  for (const source of [webLocalDemo, mobileLocalDemo]) {
    assert.match(source, /import\.meta\.env\.DEV/u)
    assert.match(source, /VITE_APPROVAL_LOCAL_DEMO === 'true'/u)
    assert.match(source, /requireApprovalLocalDemoTenant/u)
  }
  for (const source of [webDevelopmentEnv, mobileDevelopmentEnv]) {
    assert.match(source, /VITE_APPROVAL_LOCAL_DEMO=true/u)
    assert.match(source, /VITE_APPROVAL_TENANT_ID=demo-purchase-payment/u)
    assert.match(source, /VITE_APPROVAL_OPERATOR_ID=demo-manager/u)
    assert.match(source, /VITE_APPROVAL_CONNECTOR_KEY=demo-directory/u)
  }
  for (const source of [webBaseEnv, mobileBaseEnv]) {
    assert.doesNotMatch(source, /VITE_APPROVAL_LOCAL_DEMO=true/u)
    assert.doesNotMatch(source, /demo-purchase-payment/u)
  }
  assert.match(webRuntime, /localDemo: boolean/u)
  assert.match(mobileRuntime, /localDemo: boolean/u)
})

test('governed transports add local identity headers without trusted permissions', () => {
  for (const source of [webTransport, mobileTransport]) {
    assert.match(source, /runtime\.localDemo/u)
    assert.match(source, /X-Tenant-Id/u)
    assert.match(source, /X-Operator-Id/u)
    assert.doesNotMatch(source, /X-Approval-Trusted-Permissions/u)
    assert.doesNotMatch(source, /X-Approval-Worker-Id/u)
  }
  assert.match(webTransport, /headers\.set\('X-Tenant-Id', runtime\.tenantId\)/u)
  assert.match(webTransport, /headers\.set\('X-Operator-Id', runtime\.operatorId\)/u)
  assert.match(mobileTransport, /header\['X-Tenant-Id'\] = runtime\.tenantId/u)
  assert.match(mobileTransport, /header\['X-Operator-Id'\] = runtime\.operatorId/u)
})

test('PC keeps shell mock traffic separate from the real approval backend', () => {
  assert.match(webDevelopmentEnv, /VITE_APPROVAL_API_URL=\/approval-api\/api/u)
  assert.match(webVite, /'\/api'/u)
  assert.match(webVite, /http:\/\/localhost:5320\/api/u)
  assert.match(webVite, /'\/approval-api'/u)
  assert.match(webVite, /http:\/\/127\.0\.0\.1:8080/u)
  assert.match(webVite, /APPROVAL_DEMO_BACKEND_URL/u)
})

test('H5 and WeChat have separate local backend addressing', () => {
  assert.match(
    mobileDevelopmentEnv,
    /VITE_APPROVAL_H5_API_URL=\/approval-api\/api/u,
  )
  assert.match(
    mobileDevelopmentEnv,
    /VITE_APPROVAL_WEIXIN_API_URL=http:\/\/127\.0\.0\.1:8080\/api/u,
  )
  assert.match(mobileDevelopmentEnv, /VITE_APP_PROXY_ENABLE=true/u)
  assert.match(mobileRuntime, /platform === 'web'/u)
  assert.match(mobileRuntime, /platform === 'mp-weixin'/u)
})

test('client launch plan exactly follows the governed golden path', () => {
  assert.equal(
    launcherManifest.scenarioManifest,
    'config/demo/purchase-payment-golden-path.json',
  )
  assert.equal(launcherManifest.tenantId, scenario.tenant.id)
  assert.equal(launcherManifest.businessKey, scenario.request.businessKey)
  assert.equal(launcherManifest.connectorKey, scenario.directory.connectorKey)

  const expectedHandoff = scenario.expectedWorkflow.flatMap(step =>
    step.actorIds.map(actorId => ({
      actorId,
      taskDefinitionKey: step.taskDefinitionKey,
    })))
  assert.deepEqual(
    launcherManifest.expectedHandoff.map(({ actorId, taskDefinitionKey }) => ({
      actorId,
      taskDefinitionKey,
    })),
    expectedHandoff,
  )

  const execution = runLauncher('plan', '--json')
  assert.equal(execution.status, 0, execution.stderr || execution.stdout)
  const plan = JSON.parse(execution.stdout)
  assert.equal(plan.tenantId, scenario.tenant.id)
  assert.equal(plan.businessKey, scenario.request.businessKey)
  assert.deepEqual(plan.expectedHandoff, launcherManifest.expectedHandoff)
  assert.deepEqual(plan.evidenceKeys, launcherManifest.evidenceKeys)
  assert.deepEqual(plan.nonClaims, launcherManifest.nonClaims)
})

test('client launcher is fail-closed before starting dependencies', () => {
  const unknownActor = runLauncher(
    'pc',
    '--actor',
    'unknown-user',
    '--skip-install',
  )
  assert.equal(unknownActor.status, 2)
  assert.match(unknownActor.stderr, /is not allowed for pc/u)

  const publicTarget = runLauncher(
    'h5',
    '--backend-origin',
    'https://example.com',
    '--skip-install',
  )
  assert.equal(publicTarget.status, 2)
  assert.match(publicTarget.stderr, /local\/private HTTP origin/u)

  const unsafePort = runLauncher(
    'pc',
    '--port',
    '80',
    '--skip-install',
  )
  assert.equal(unsafePort.status, 2)
  assert.match(unsafePort.stderr, /between 1024 and 65535/u)

  const invalidPlan = runLauncher(
    'plan',
    '--backend-origin',
    'http://127.0.0.1:8080',
  )
  assert.equal(invalidPlan.status, 2)
  assert.match(invalidPlan.stderr, /plan accepts only --json and --help/u)
})

test('client launcher preserves the merged identity bridge and proxy paths', () => {
  assert.match(launcher, /VITE_APPROVAL_LOCAL_DEMO: 'true'/u)
  assert.match(launcher, /APPROVAL_DEMO_BACKEND_URL: resolved\.backendOrigin/u)
  assert.match(launcher, /VITE_APPROVAL_API_URL: '\/approval-api\/api'/u)
  assert.match(launcher, /VITE_APPROVAL_H5_API_URL: '\/approval-api\/api'/u)
  assert.match(
    launcher,
    /VITE_APPROVAL_WEIXIN_API_URL: `\$\{resolved\.backendOrigin\}\/api`/u,
  )
  assert.match(launcher, /VITE_SERVER_BASEURL: resolved\.backendOrigin/u)
  assert.doesNotMatch(launcher, /VITE_APPROVAL_LOCAL_IDENTITY_HEADERS/u)
  assert.doesNotMatch(launcher, /\/approval-api\/api\/api/u)

  assert.match(launcher, /spawnSync\(process\.execPath, args/u)
  assert.match(launcher, /spawnSync\(pnpmExecutable\(\), args/u)
  assert.match(launcher, /spawn\(pnpmExecutable\(\), clientArguments/u)
  assert.match(launcher, /shell: false/gu)
  assert.doesNotMatch(launcher, /spawn(?:Sync)?\(command/u)
  assert.doesNotMatch(launcher, /shell:\s*true/u)
  assert.doesNotMatch(launcher, /\bexec\s*\(/u)
})

test('package and guide expose role launchers without claiming runtime E2E', () => {
  const expectedScripts = {
    'demo:client:plan': 'node scripts/product-readiness/demo-client.mjs plan --json',
    'demo:client:pc': 'node scripts/product-readiness/demo-client.mjs pc',
    'demo:client:h5': 'node scripts/product-readiness/demo-client.mjs h5',
    'demo:client:wechat': 'node scripts/product-readiness/demo-client.mjs wechat',
    'demo:clients:check':
      'node --test scripts/tests/product-readiness-cross-client-local-demo-boundary.test.mjs',
  }
  for (const [name, command] of Object.entries(expectedScripts)) {
    assert.equal(packageJson.scripts?.[name], command)
  }
  assert.equal(
    packageJson.scripts?.['mobile:dev:weixin'],
    'pnpm mobile:bootstrap && pnpm -C .upstream/unibest init-baseFiles '
      + '&& pnpm -C .upstream/unibest dev:mp-weixin',
  )

  for (const command of [
    'pnpm demo:backend:start',
    'pnpm demo:client:plan',
    'pnpm demo:client:pc',
    'pnpm demo:client:h5',
    'pnpm demo:client:wechat',
  ]) {
    assert.equal(guide.includes(command), true, `guide missing ${command}`)
  }
  for (const marker of [
    'LOCAL_CROSS_CLIENT_LAUNCHERS_IMPLEMENTED',
    'CROSS_CLIENT_RUNTIME_NOT_EXECUTED',
    'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
    'PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED',
  ]) {
    assert.equal(guide.includes(marker), true, `guide missing ${marker}`)
  }
  const forbiddenConclusionHeading = guide.indexOf('## 禁止推导的结论')
  assert.ok(forbiddenConclusionHeading >= 0, 'guide missing forbidden conclusion section')
  for (const marker of [
    'PURCHASE_APPROVAL_E2E_PASSED',
    'PC_H5_WECHAT_RUNTIME_PASSED',
  ]) {
    const occurrences = [...guide.matchAll(new RegExp(`^${marker}$`, 'gmu'))]
    assert.equal(occurrences.length, 1, `${marker} must appear once as a non-claim`)
    assert.ok(
      occurrences[0].index > forbiddenConclusionHeading,
      `${marker} may appear only after the forbidden conclusion heading`,
    )
  }
})

test('the permanent Hygiene aggregate loads the cross-client local demo boundary', () => {
  assert.match(
    aggregate,
    /import '\.\/product-readiness-cross-client-local-demo-boundary\.test\.mjs';/u,
  )
})
