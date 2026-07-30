import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const spiRoot = path.join(
  root,
  'server-modules/approval-ai-spi/src/main/java/io/github/akaryc1b/approval/ai/spi',
);
const coreRoot = path.join(
  root,
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core',
);
const coreTestRoot = path.join(
  root,
  'server-modules/approval-ai-core/src/test/java/io/github/akaryc1b/approval/ai/core',
);

function source(directory, name) {
  const file = path.join(directory, `${name}.java`);
  assert.equal(existsSync(file), true, `${name}.java must exist`);
  return readFileSync(file, 'utf8');
}

test('transport mapping SPI remains metadata-only and non-dispatching', () => {
  const mapper = source(spiRoot, 'AiProviderTransportMapper');
  const request = source(spiRoot, 'AiProviderTransportMappingRequest');
  const result = source(spiRoot, 'AiProviderTransportMappingResult');
  const observation = source(spiRoot, 'AiProviderTransportFixtureObservation');
  const production = `${mapper}\n${request}\n${result}\n${observation}`;

  assert.doesNotMatch(production, /import\s+java\.net\.|HttpClient|WebClient|RestClient/);
  assert.doesNotMatch(production, /System\.getenv|System\.getProperty|@Value\s*\(/);
  assert.doesNotMatch(
    production,
    /\b(String|byte\[\]|Object)\s+(body|payload|secret|token|password|privateKey)\b/,
  );
  assert.match(request, /cannot authorize dispatch or Provider invocation/);
  assert.match(result, /must remain zero-call/);
  assert.match(result, /cannot authorize retry or post-invocation fallback/);
  assert.match(observation, /cannot store raw response bodies/);
});

test('canonical payload and signing input contain hashes but no raw values', () => {
  const policy = source(coreRoot, 'AiProviderPayloadCanonicalizationPolicy');
  const payload = source(coreRoot, 'AiProviderCanonicalPayloadEvidence');
  const signing = source(coreRoot, 'AiProviderSigningInputEvidence');

  assert.match(policy, /cannot authorize raw values or Secret material/);
  assert.match(payload, /without raw field values/);
  assert.match(payload, /confidential and restricted field evidence must be redacted/);
  assert.doesNotMatch(payload, /\bObject\s+value\b|\bString\s+rawValue\b/);
  assert.match(signing, /signatureComputed/);
  assert.match(signing, /secretMaterialAccessed/);
  assert.match(signing, /SENSITIVE_HEADERS/);
  assert.match(signing, /cannot compute signatures or access runtime data/);
});

test('transport lifecycle fails closed and cannot retry or dispatch', () => {
  const evaluator = source(coreRoot, 'AiProviderTransportLifecycleEvaluator');
  const report = source(coreRoot, 'AiProviderTransportLifecycleReport');
  const production = `${evaluator}\n${report}`;

  assert.doesNotMatch(production, /\.advise\s*\(/);
  assert.doesNotMatch(production, /import\s+java\.net\.|System\.getenv|System\.getProperty/);
  assert.match(report, /CANCELLED_BEFORE_DISPATCH/);
  assert.match(report, /TIMED_OUT_BEFORE_DISPATCH/);
  assert.match(report, /MALFORMED_JSON/);
  assert.match(report, /SCHEMA_DRIFT/);
  assert.match(report, /UNKNOWN_FIELDS/);
  assert.match(report, /cannot authorize retry or fallback/);
});

test('transport audit evidence is redaction-safe and hash-only', () => {
  const audit = source(coreRoot, 'AiProviderTransportAuditEvidence');

  assert.match(audit, /rawRequestStored/);
  assert.match(audit, /rawResponseStored/);
  assert.match(audit, /headerValuesStored/);
  assert.match(audit, /secretMaterialStored/);
  assert.match(audit, /networkPayloadStored/);
  assert.match(audit, /must remain redaction-safe and hash-only/);
  assert.doesNotMatch(audit, /\bString\s+(requestBody|responseBody|headerValue|secretValue)\b/);
});

test('transport acceptance checklist is permanently non-executable', () => {
  const checklist = source(coreRoot, 'AiProviderTransportAcceptanceChecklist');

  assert.match(checklist, /NON_EXECUTABLE_TRANSPORT_ACCEPTANCE/);
  assert.match(checklist, /SECRET_MATERIAL_ABSENT/);
  assert.match(checklist, /NETWORK_DISPATCH_PROHIBITED/);
  assert.match(checklist, /MALFORMED_RESPONSE_FAILS_CLOSED/);
  assert.match(checklist, /SCHEMA_DRIFT_FAILS_CLOSED/);
  assert.match(checklist, /TWO_PERSON_REVIEW_BOUND/);
  assert.match(checklist, /cannot authorize execution or production/);
});

test('deterministic transport implementation remains test-only', () => {
  const deterministic = path.join(coreTestRoot, 'DeterministicProviderTransportMapper.java');
  assert.equal(existsSync(deterministic), true);

  const productionFiles = readdirSync(coreRoot)
    .filter((name) => name.endsWith('.java'))
    .map((name) => readFileSync(path.join(coreRoot, name), 'utf8'))
    .join('\n');
  assert.doesNotMatch(productionFiles, /implements\s+AiProviderTransportMapper/);
  assert.doesNotMatch(productionFiles, /class\s+DeterministicProviderTransportMapper/);
});

test('transport boundary uses only the existing automatic workflow', () => {
  const workflowRoot = path.join(root, '.github/workflows');
  const workflows = readdirSync(workflowRoot)
    .filter((name) => /\.ya?ml$/.test(name));
  const automatic = workflows.filter((name) => {
    const content = readFileSync(path.join(workflowRoot, name), 'utf8');
    return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
  });
  assert.deepEqual(automatic, ['approval-platform-validation.yml']);

  const workflow = readFileSync(
    path.join(workflowRoot, 'approval-platform-validation.yml'),
    'utf8',
  );
  assert.match(workflow, /Verify M6 AI transport review boundaries/);
  assert.match(workflow, /m6-ai-transport-review-boundary\.test\.mjs/);
  assert.match(workflow, /m6-ai-transport-review-boundary\.log/);
});
