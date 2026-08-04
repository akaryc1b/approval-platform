import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const moduleRoot = path.join(root, 'server-modules/approval-ai-openai');
const sourceRoot = path.join(moduleRoot, 'src/main/java');
const testRoot = path.join(moduleRoot, 'src/test/java');
const modulePomPath = path.join(moduleRoot, 'pom.xml');
const serverPomPath = path.join(root, 'apps/server/pom.xml');
const migrationRoot = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
);

const packageRoot = path.join(
  sourceRoot,
  'io/github/akaryc1b/approval/ai/openai',
);
const sourceFiles = [
  'OpenAiResponsesProtocol.java',
  'OpenAiResponsesRequestEncoder.java',
  'OpenAiResponsesResponseDecoder.java',
  'OpenAiResponsesTransportPort.java',
].map(name => path.join(packageRoot, name));
const testFiles = [
  'OpenAiResponsesRequestEncoderTest.java',
  'OpenAiResponsesResponseDecoderTest.java',
  'OpenAiResponsesTransportPortTest.java',
].map(name => path.join(
  testRoot,
  'io/github/akaryc1b/approval/ai/openai',
  name,
));

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

test('P6-C request encoder and strict decoder freeze one stateless Responses profile', () => {
  for (const requiredPath of [
    ...sourceFiles,
    ...testFiles,
    modulePomPath,
    serverPomPath,
  ]) {
    assert.equal(existsSync(requiredPath), true, `missing P6-C file ${requiredPath}`);
  }

  const protocol = text(sourceFiles[0]);
  const encoder = text(sourceFiles[1]);
  const decoder = text(sourceFiles[2]);
  const port = text(sourceFiles[3]);

  for (const required of [
    /PROVIDER_ID = "openai-responses"/,
    /MODEL_ID = "gpt-5-mini"/,
    /MODEL_VERSION = "2025-08-07"/,
    /MODEL_SNAPSHOT = "gpt-5-mini-2025-08-07"/,
    /OUTPUT_SCHEMA_ID = "approval-assistance"/,
    /OUTPUT_SCHEMA_VERSION = 1/,
    /RESPONSE_FORMAT_NAME = "approval_assistance_v1"/,
    /MAXIMUM_REQUEST_BYTES = 262_144/,
    /MAXIMUM_RESPONSE_BYTES = 262_144/,
    /REQUEST_ID_MISMATCH/,
    /DUPLICATE_PROPERTY/,
    /UNKNOWN_PROPERTY/,
    /VERSION_MISMATCH/,
    /RESULT_INVALID/,
  ]) assert.match(protocol, required);

  for (const required of [
    /root\.put\("model", OpenAiResponsesProtocol\.MODEL_SNAPSHOT\)/,
    /root\.put\("store", false\)/,
    /root\.put\("background", false\)/,
    /root\.put\("stream", false\)/,
    /root\.set\("tools", JSON\.arrayNode\(\)\)/,
    /root\.put\("tool_choice", "none"\)/,
    /root\.put\("truncation", "disabled"\)/,
    /format\.put\("type", "json_schema"\)/,
    /format\.put\("name", OpenAiResponsesProtocol\.RESPONSE_FORMAT_NAME\)/,
    /format\.put\("strict", true\)/,
    /schema\.put\("additionalProperties", false\)/,
    /sorted\(Comparator\.comparing\(AiProviderRequest\.InputField::key\)\)/,
    /OpenAiResponsesProtocol\.sha256\(body\)/,
    /MAXIMUM_REQUEST_BYTES/,
  ]) assert.match(encoder, required);

  for (const forbidden of [
    /previous_response_id/,
    /prompt_cache_retention/,
    /root\.set\("metadata"/,
    /request\.context\(\)/,
    /request\.resource\(\)/,
  ]) assert.doesNotMatch(encoder, forbidden);

  for (const required of [
    /STRICT_DUPLICATE_DETECTION/,
    /CodingErrorAction\.REPORT/,
    /response\.statusCode\(\) != 200/,
    /REQUEST_ID_MISMATCH/,
    /requireAllowed\(root, ROOT_FIELDS\)/,
    /"completed"/,
    /"output_text"/,
    /"json_schema"/,
    /MODEL_SNAPSHOT/,
    /MAXIMUM_RESPONSE_BYTES/,
    /validateEvidence/,
    /validateItemIds/,
    /new AiAdvisoryResult/,
    /sha256Utf8\(responseId\)/,
  ]) assert.match(decoder, required);

  assert.match(port, /public interface OpenAiResponsesTransportPort/);
  assert.match(port, /Response exchange\(Request request\)/);
  assert.match(port, /MAXIMUM_CONNECT_TIMEOUT = Duration\.ofSeconds\(2\)/);
  assert.match(port, /MAXIMUM_TOTAL_TIMEOUT = Duration\.ofSeconds\(15\)/);
  assert.match(port, /Arrays\.copyOf/);
  assert.match(port, /requestIdHash=/);
  assert.match(port, /record TransportEvidence/);
});

test('P6-C codec stays isolated while P6-D supplies one sender and P6-E one caller', () => {
  const productionFiles = filesUnder(sourceRoot)
    .filter(file => file.endsWith('.java'));
  const codecProduction = sourceFiles.map(text).join('\n');
  const tests = testFiles.map(text).join('\n');

  assert.deepEqual(
    productionFiles
      .filter(file => /implements\s+OpenAiResponsesTransportPort/.test(text(file)))
      .map(file => path.basename(file)),
    ['OpenAiResponsesSecureHttpSender.java'],
  );
  assert.deepEqual(
    productionFiles
      .filter(file => path.basename(file) !== 'OpenAiResponsesTransportPort.java')
      .filter(file => /\.exchange\s*\(/.test(text(file)))
      .map(file => path.basename(file))
      .sort(),
    [
      'OpenAiResponsesAdvisoryProvider.java',
      'OpenAiResponsesSecureHttpSender.java',
    ],
  );

  for (const forbidden of [
    /api\.openai\.com/,
    /import\s+java\.net\./,
    /import\s+javax\.net\.ssl\./,
    /HttpClient/,
    /WebClient/,
    /RestClient/,
    /Authorization\s*[:=]/,
    /Bearer\s+/,
    /@Component\b/,
    /@Bean\b/,
    /@RestController\b/,
    /@PostMapping\b/,
    /@Scheduled\b/,
    /ApprovalAssistanceSynchronousOrchestrator/,
    /ApprovalAssistanceDurableEvidenceStore/,
    /JdbcTemplate/,
    /DataSource/,
    /\.(approve|reject|returnTask|transfer|withdraw|terminate|migrate|publish|activate)\s*\(/,
  ]) assert.doesNotMatch(codecProduction, forbidden);

  assert.doesNotMatch(tests, /System\.getenv/);
  assert.doesNotMatch(tests, /api\.openai\.com/);
  assert.match(tests, /deterministicFakePortIsTestOnlyAndExplicitlySingleCall/);
  assert.match(tests, /exactProfileEncodesCanonicalStatelessIdentityFreeRequest/);
  assert.match(tests, /duplicateUnknownMalformedInvalidUtf8AndOversizeFailClosed/);

  const modulePom = text(modulePomPath);
  assert.match(modulePom, /<artifactId>approval-ai-spi<\/artifactId>/);
  assert.match(modulePom, /<artifactId>jackson-databind<\/artifactId>/);
  for (const forbidden of [
    /spring-boot/,
    /approval-ai-core/,
    /approval-persistence-jdbc/,
    /flowable/,
    /httpclient/i,
  ]) assert.doesNotMatch(modulePom, forbidden);
  const serverPom = text(serverPomPath);
  assert.equal((serverPom.match(/<artifactId>approval-ai-openai<\/artifactId>/g) || []).length, 1);

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

  const production = productionFiles.map(text).join('\n');
  for (const required of [
    /OpenAiEnvironmentCredentialMaterialSource/,
    /OpenAiResponsesAdvisoryProvider/,
    /OpenAiResponsesProductionRuntimeFactory/,
    /OpenAiResponsesRequestEncoder/,
    /OpenAiResponsesResponseDecoder/,
    /OpenAiResponsesTransportPort/,
    /OpenAiResponsesSecureHttpSender/,
    /OpenAiResponsesTransportAdmission/,
  ]) assert.match(production, required);
});
