import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const moduleRoot = path.join(root, 'server-modules/approval-ai-openai');
const sourcePath = path.join(
  moduleRoot,
  'src/main/java/io/github/akaryc1b/approval/ai/openai/' +
    'OpenAiEnvironmentCredentialMaterialSource.java',
);
const testPath = path.join(
  moduleRoot,
  'src/test/java/io/github/akaryc1b/approval/ai/openai/' +
    'OpenAiEnvironmentCredentialMaterialSourceTest.java',
);
const modulePomPath = path.join(moduleRoot, 'pom.xml');
const modulesPomPath = path.join(root, 'server-modules/pom.xml');
const architecturePomPath = path.join(
  root,
  'server-modules/approval-architecture-tests/pom.xml',
);
const serverPomPath = path.join(root, 'apps/server/pom.xml');
const connectorProviderPath = path.join(
  root,
  'server-modules/approval-connector-spi/src/main/java/' +
    'io/github/akaryc1b/approval/connector/ConnectorProvider.java',
);
const connectorOperationPath = path.join(
  root,
  'server-modules/approval-connector-spi/src/main/java/' +
    'io/github/akaryc1b/approval/connector/contract/ConnectorOperation.java',
);
const materialTypePath = path.join(
  root,
  'server-modules/approval-connector-credential-core/src/main/java/' +
    'io/github/akaryc1b/approval/connector/credential/CredentialMaterialType.java',
);
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

test('P6-B OpenAI environment source is exact callback-scoped and redaction-safe', () => {
  for (const requiredPath of [
    sourcePath,
    testPath,
    modulePomPath,
    modulesPomPath,
    architecturePomPath,
    connectorProviderPath,
    connectorOperationPath,
    materialTypePath,
  ]) {
    assert.equal(existsSync(requiredPath), true, `missing P6-B source ${requiredPath}`);
  }

  const source = text(sourcePath);
  for (const required of [
    /implements CredentialMaterialSource/,
    /PROVIDER_KEY = "openai-responses"/,
    /CREDENTIAL_REFERENCE_ID = "openai-api-key"/,
    /PROTOCOL_PROFILE = "OPENAI_RESPONSES_V1"/,
    /CAPABILITY = "APPROVAL_ASSISTANCE"/,
    /SECRET_VARIABLE = "OPENAI_API_KEY"/,
    /VERSION_VARIABLE = "OPENAI_API_KEY_VERSION"/,
    /EnvironmentVariableReader/,
    /System\.getenv/,
    /CredentialMaterialType\.API_KEY/,
    /ConnectorOperation\.AI_ADVISORY_GENERATE/,
    /CredentialMaterialEnvironment\.PRODUCTION/,
    /AtomicBoolean leaseActive/,
    /compareAndSet\(false, true\)/,
    /CredentialMaterialLease\.takeOwnership/,
    /Arrays\.fill\(secretCharacters, '\\0'\)/,
    /Arrays\.fill\(material, \(byte\) 0\)/,
    /leaseActive\.set\(false\)/,
    /CredentialMaterialFailure\.CONCURRENT_ACCESS_REJECTED/,
    /CredentialMaterialFailure\.VERSION_DRIFT/,
    /CredentialMaterialFailure\.CREDENTIAL_NOT_YET_VALID/,
    /CredentialMaterialFailure\.CREDENTIAL_EXPIRED/,
    /bindingEvidenceHash/,
  ]) {
    assert.match(source, required);
  }

  const retainedSecretField = /(?m)^\s*(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?(?:byte|char)\[\]\s+\w+/;
  assert.doesNotMatch(source, retainedSecretField);
  for (const forbidden of [
    /api\.openai\.com/,
    /java\.net\./,
    /HttpClient/,
    /WebClient/,
    /RestClient/,
    /Authorization\s*[:=]/,
    /Bearer\s+/,
    /@Component\b/,
    /@Bean\b/,
    /@RestController\b/,
    /@Scheduled\b/,
    /ApprovalAssistanceSynchronousOrchestrator/,
    /ApprovalAssistanceDurableEvidenceStore/,
    /JdbcTemplate/,
    /DataSource/,
  ]) {
    assert.doesNotMatch(source, forbidden);
  }

  const sourceWithoutConstants = source
    .replaceAll('OPENAI_API_KEY_VERSION', '')
    .replaceAll('OPENAI_API_KEY', '');
  assert.doesNotMatch(sourceWithoutConstants, /sk-[A-Za-z0-9_-]{8,}/);
  assert.doesNotMatch(source, /String\s+(?:apiKey|secret|token)\s*[;=]/i);

  assert.match(text(connectorProviderPath), /AI_ADVISORY/);
  assert.match(
    text(connectorOperationPath),
    /AI_ADVISORY_GENERATE\(ConnectorProvider\.Capability\.AI_ADVISORY\)/,
  );
  assert.match(text(materialTypePath), /API_KEY/);
});

test('P6-B tests prove lifecycle failures without real environment or Provider access', () => {
  const testSource = text(testPath);
  for (const required of [
    /exactRequestUsesCallbackScopedCopyAndZeroizesEveryPlatformOwnedArray/,
    /sourceAllowsOnlyOneActiveLeaseAndAllowsAFreshLeaseAfterClose/,
    /callbackFailureStillClosesAndReleasesTheLease/,
    /missingBlankMalformedAndVersionDriftFailClosedWithoutSecretDisclosure/,
    /exactRequestDriftIsRejectedBeforeEnvironmentAccess/,
    /notYetValidAndExpiredVersionFailBeforeEnvironmentAccess/,
    /evidenceAndExceptionsRemainRedactedAndLegacyScopeIsUnavailable/,
    /assertTrue\(allZero\(environment\.lastReturnedSecret\(\)\)\)/,
    /assertTrue\(allZero\(callbackCopy\.get\(\)\)\)/,
    /CONCURRENT_ACCESS_REJECTED/,
    /SOURCE_UNAVAILABLE/,
    /MATERIAL_MALFORMED/,
    /VERSION_DRIFT/,
    /ROUTE_DRIFT/,
    /BINDING_DRIFT/,
    /ENVIRONMENT_DRIFT/,
    /POLICY_DRIFT/,
    /CREDENTIAL_NOT_YET_VALID/,
    /CREDENTIAL_EXPIRED/,
    /CapturingEnvironment implements EnvironmentVariableReader/,
  ]) {
    assert.match(testSource, required);
  }
  for (const forbidden of [
    /systemEnvironment\(/,
    /System\.getenv/,
    /api\.openai\.com/,
    /HttpClient/,
    /Authorization\s*[:=]/,
    /Bearer\s+/,
  ]) {
    assert.doesNotMatch(testSource, forbidden);
  }

  const modulePom = text(modulePomPath);
  assert.match(modulePom, /<artifactId>approval-connector-credential-core<\/artifactId>/);
  assert.match(modulePom, /<artifactId>approval-connector-spi<\/artifactId>/);
  for (const forbidden of [
    /spring-boot/,
    /approval-ai-core/,
    /approval-persistence-jdbc/,
    /flowable/,
    /httpclient/i,
  ]) {
    assert.doesNotMatch(modulePom, forbidden);
  }

  assert.match(text(modulesPomPath), /<module>approval-ai-openai<\/module>/);
  assert.match(
    text(architecturePomPath),
    /<artifactId>approval-ai-openai<\/artifactId>/,
  );
  assert.doesNotMatch(text(serverPomPath), /approval-ai-openai/);

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
