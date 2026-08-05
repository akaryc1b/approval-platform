import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function source(relativePath) {
  const file = path.join(root, relativePath);
  assert.equal(existsSync(file), true, `${relativePath} must exist`);
  return readFileSync(file, 'utf8');
}

const normalizer = source(
  'server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesOutputNormalizer.java',
);
const provider = source(
  'server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesAdvisoryProvider.java',
);
const normalizerTest = source(
  'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesOutputNormalizerTest.java',
);

test('stateless reasoning metadata is closed and removed before strict decoding', () => {
  assert.match(normalizer, /STRICT_DUPLICATE_DETECTION/);
  assert.match(normalizer, /REASONING_FIELDS = Set\.of/);
  assert.match(normalizer, /"encrypted_content"/);
  assert.match(normalizer, /"summary"/);
  assert.match(normalizer, /"content"/);
  assert.match(normalizer, /MAXIMUM_OUTPUT_ITEMS = 16/);
  assert.match(normalizer, /MAXIMUM_REASONING_PARTS = 16/);
  assert.match(normalizer, /"message"\.equals\(type\)/);
  assert.match(normalizer, /"reasoning"\.equals\(type\)/);
  assert.match(normalizer, /requireExact\(reasoning, "status", "completed"\)/);
  assert.match(normalizer, /validateReasoningParts\(reasoning\.get\("summary"\)\)/);
  assert.match(normalizer, /validateReasoningParts\(reasoning\.get\("content"\)\)/);
  assert.match(normalizer, /normalized\.set\("output", MAPPER\.createArrayNode\(\)\.add\(message\.deepCopy\(\)\)\)/);
  assert.match(normalizer, /if \(!reasoningObserved\) \{\s*return response;/);
  assert.doesNotMatch(normalizer, /advisory|evidenceStore|logger|System\.out|System\.err/);
});

test('provider normalizes only after verified transport and before the existing decoder', () => {
  const verified = provider.indexOf('if (!response.transportEvidence().verified())');
  const normalized = provider.indexOf('OpenAiResponsesOutputNormalizer.normalize(response)');
  const decoded = provider.indexOf('decoder.decode(');

  assert.ok(verified >= 0 && normalized > verified && decoded > normalized);
  assert.equal(
    (provider.match(/OpenAiResponsesOutputNormalizer\.normalize\s*\(/g) || []).length,
    1,
  );
  assert.equal((provider.match(/decoder\.decode\s*\(/g) || []).length, 1);
  assert.equal((provider.match(/transport\.exchange\s*\(/g) || []).length, 1);
  assert.doesNotMatch(provider, /retry|fallbackAttempt|secondProvider/);
});

test('directed tests accept reasoning around one message and reject every ambiguous shape', () => {
  for (const required of [
    /statelessReasoningBeforeOrAfterMessageIsAcceptedButNotExposed/,
    /responseWithoutReasoningPreservesOriginalTransportResponse/,
    /reasoningOnlyFailsClosed/,
    /duplicateAssistantMessagesFailClosed/,
    /unknownOutputTypeFailsClosed/,
    /unknownReasoningFieldFailsClosed/,
    /incompleteReasoningFailsClosed/,
    /malformedReasoningPartFailsClosed/,
    /duplicateReasoningPropertyFailsClosed/,
    /assertFalse\(body\.contains\("opaque-summary"\)\)/,
    /assertFalse\(body\.contains\("opaque-reasoning"\)\)/,
    /assertFalse\(body\.contains\("opaque-encrypted"\)\)/,
  ]) {
    assert.match(normalizerTest, required);
  }
});

test('reasoning correction preserves single workflow migration and authority boundaries', () => {
  const migrationRoot = path.join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
  );
  const migrations = readdirSync(migrationRoot);
  assert.equal(migrations.filter(name => /^V49__/.test(name)).length, 1);
  assert.equal(migrations.some(name => /^V(?:5[0-9]|[6-9][0-9])__/.test(name)), false);

  const workflowRoot = path.join(root, '.github/workflows');
  const automatic = readdirSync(workflowRoot)
    .filter(name => /\.ya?ml$/.test(name))
    .filter(name => {
      const content = readFileSync(path.join(workflowRoot, name), 'utf8');
      return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
    });
  assert.deepEqual(automatic, ['approval-platform-validation.yml']);

  const production = `${normalizer}\n${provider}`;
  assert.doesNotMatch(production, /tools\s*=|tool_choice|function_call|stream\s*=|background\s*=/);
  assert.doesNotMatch(production, /Queue|Worker|Scheduler|listener|polling/);
  assert.doesNotMatch(production, /approve\(|reject\(|transfer\(|withdraw\(|terminate\(/);
});
