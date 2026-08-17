import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const fixturePath = resolve(root, 'config/demo/purchase-payment-demo-seed.json');
const scenarioPath = resolve(root, 'config/demo/purchase-payment-golden-path.json');
const localConfigPath = resolve(root, 'apps/server/src/main/resources/application-local.yml');
const serverPomPath = resolve(root, 'apps/server/pom.xml');
const demoSourceRoot = resolve(
  root,
  'apps/server/src/main/java/io/github/akaryc1b/approval/demo',
);
const integrationTestPath = resolve(
  root,
  'apps/server/src/test/java/io/github/akaryc1b/approval/integration/'
    + 'PurchasePaymentDemoSeedIntegrationTest.java',
);
const statusPath = resolve(root, 'docs/product-readiness/README.md');
const guidePath = resolve(root, 'docs/product-readiness/PURCHASE_PAYMENT_GOLDEN_PATH.md');
const packagePath = resolve(root, 'package.json');
const hygieneAggregatePath = resolve(root, 'scripts/tests/m3-repository-hygiene.test.mjs');

const demoFiles = [
  'PurchasePaymentDemoConfiguration.java',
  'PurchasePaymentDemoOrganizationConnector.java',
  'PurchasePaymentDemoScenario.java',
  'PurchasePaymentDemoSeeder.java',
  'PurchasePaymentDemoSeedState.java',
].map((name) => resolve(demoSourceRoot, name));

function text(path) {
  assert.equal(existsSync(path), true, `missing ${path}`);
  return readFileSync(path, 'utf8');
}

test('seed fixture maps every governed logical attachment to a unique fixed UUID', () => {
  const scenario = JSON.parse(text(scenarioPath));
  const fixture = JSON.parse(text(fixturePath));
  assert.equal(fixture.schemaVersion, 1);
  assert.equal(
    fixture.scenarioManifest,
    'config/demo/purchase-payment-golden-path.json',
  );
  assert.deepEqual(
    fixture.attachments.map((attachment) => attachment.logicalId),
    scenario.request.attachmentIds,
  );
  const ids = fixture.attachments.map((attachment) => attachment.attachmentId);
  assert.equal(new Set(ids).size, ids.length);
  for (const attachment of fixture.attachments) {
    assert.match(
      attachment.attachmentId,
      /^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u,
    );
    assert.equal(attachment.contentType, 'text/plain');
    assert.equal(attachment.contentUtf8.includes('DEMO-PP-0001'), true);
  }
});

test('demo seed is local-profile-only, explicit and default-off', () => {
  const configuration = text(demoFiles[0]);
  const localConfig = text(localConfigPath);
  assert.match(configuration, /@Profile\("local"\)/u);
  assert.match(configuration, /prefix = "approval\.demo\.purchase-payment"/u);
  assert.match(configuration, /havingValue = "true"/u);
  assert.match(
    localConfig,
    /enabled: \$\{APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED:false\}/u,
  );
  assert.doesNotMatch(localConfig, /enabled:\s*true/u);
});

test('demo source reuses application authorities and adds no SQL, REST or auth bypass', () => {
  const source = demoFiles.map(text).join('\n');
  assert.match(source, /PurchasePaymentApplicationService/u);
  assert.match(source, /ApprovalAttachmentService/u);
  assert.match(source, /ConnectorPurchasePaymentAssigneeResolver/u);
  assert.match(source, /ApplicationRunner/u);
  assert.doesNotMatch(
    source,
    /JdbcTemplate|DataSource|createStatement|executeUpdate|ACT_[A-Z_]+/u,
  );
  assert.doesNotMatch(
    source,
    /@RestController|@RequestMapping|@GetMapping|@PostMapping/u,
  );
  assert.doesNotMatch(
    source,
    /password|accessToken|secretKey|authorizationBypass/iu,
  );
});

test('server packages governed demo resources and permanent CI starts the real backend', () => {
  const pom = text(serverPomPath);
  const integrationTest = text(integrationTestPath);
  assert.match(pom, /<targetPath>demo<\/targetPath>/u);
  assert.match(pom, /<include>purchase-payment-golden-path\.json<\/include>/u);
  assert.match(pom, /<include>purchase-payment-demo-seed\.json<\/include>/u);
  assert.match(integrationTest, /@SpringBootTest/u);
  assert.match(integrationTest, /WebEnvironment\.RANDOM_PORT/u);
  assert.match(integrationTest, /PostgreSQLContainer/u);
  assert.match(integrationTest, /\/actuator\/health/u);
  assert.match(integrationTest, /PurchasePaymentDemoSeedState/u);
  assert.match(integrationTest, /PurchasePaymentDemoSeedState\.SeedEvidence replay/u);
});

test('product-readiness docs distinguish CI seed/start proof from unfinished approval E2E', () => {
  const status = text(statusPath);
  const guide = text(guidePath);
  for (const source of [status, guide]) {
    assert.match(source, /DETERMINISTIC_DEMO_SEED_IMPLEMENTED/u);
    assert.match(source, /BACKEND_LOCAL_START_VERIFIED/u);
    assert.match(source, /SHARED_DEMO_ENVIRONMENT_SEED_NOT_APPLIED/u);
    assert.match(source, /PURCHASE_APPROVAL_E2E_NOT_EXECUTED/u);
    assert.match(source, /PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED/u);
    assert.match(source, /PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED/u);
  }
  assert.doesNotMatch(guide, /^PURCHASE_APPROVAL_E2E_STATUS=PASSED$/mu);
  assert.doesNotMatch(guide, /^PRODUCTION_PAYMENT_INTEGRATION_STATUS=VERIFIED$/mu);
});

test('package entrypoint and permanent Hygiene aggregate load the seed boundary', () => {
  const packageJson = JSON.parse(text(packagePath));
  assert.equal(
    packageJson.scripts?.['demo:seed:check'],
    'node --test scripts/tests/product-readiness-demo-seed-boundary.test.mjs',
  );
  assert.match(
    text(hygieneAggregatePath),
    /import '\.\/product-readiness-demo-seed-boundary\.test\.mjs';/u,
  );
});
