import assert from 'node:assert/strict'
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

const manifest = JSON.parse(text('config/demo/purchase-payment-golden-path.json'))
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
  const manifestOperators = manifest.directory.users
    .map(user => user.id)
    .sort()
  assert.deepEqual(manifestOperators, [...expectedOperators].sort())
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

test('package and guide expose the client commands without claiming runtime E2E', () => {
  assert.equal(
    packageJson.scripts?.['demo:clients:check'],
    'node --test scripts/tests/product-readiness-cross-client-local-demo-boundary.test.mjs',
  )
  assert.equal(
    packageJson.scripts?.['mobile:dev:weixin'],
    'pnpm mobile:bootstrap && pnpm -C .upstream/unibest init-baseFiles && pnpm -C .upstream/unibest dev:mp-weixin',
  )
  for (const command of [
    'pnpm demo:backend:start',
    'pnpm web:dev',
    'pnpm mobile:dev:h5',
    'pnpm mobile:dev:weixin',
  ]) {
    assert.equal(guide.includes(command), true, `guide missing ${command}`)
  }
  for (const marker of [
    'CROSS_CLIENT_LOCAL_DEMO_BINDING_IMPLEMENTED',
    'CROSS_CLIENT_RUNTIME_NOT_EXECUTED',
    'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
    'PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED',
  ]) {
    assert.equal(guide.includes(marker), true, `guide missing ${marker}`)
  }
})

test('the permanent Hygiene aggregate loads the cross-client local demo boundary', () => {
  assert.match(
    aggregate,
    /import '\.\/product-readiness-cross-client-local-demo-boundary\.test\.mjs';/u,
  )
})
