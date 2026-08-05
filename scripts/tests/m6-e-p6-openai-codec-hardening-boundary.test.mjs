import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const javaRoot = path.join(
  root,
  'server-modules/approval-ai-openai/src/main/java/' +
    'io/github/akaryc1b/approval/ai/openai',
);
const testRoot = path.join(
  root,
  'server-modules/approval-ai-openai/src/test/java/' +
    'io/github/akaryc1b/approval/ai/openai',
);

function text(directory, file) {
  return readFileSync(path.join(directory, file), 'utf8');
}

test('P6-C hardening preserves exact P2 confidence and typed strict-schema constants', () => {
  const protocol = text(javaRoot, 'OpenAiResponsesProtocol.java');
  const encoder = text(javaRoot, 'OpenAiResponsesRequestEncoder.java');
  const hardening = text(testRoot, 'OpenAiResponsesCodecHardeningTest.java');

  assert.match(protocol, /PROVIDER_VERSION = "responses-v1"/);
  assert.match(protocol, /MEDIUM_CONFIDENCE_MINIMUM = 0\.50d/);
  assert.match(protocol, /HIGH_CONFIDENCE_MINIMUM = 0\.80d/);
  assert.doesNotMatch(protocol, /1\.0d \/ 3\.0d|2\.0d \/ 3\.0d/);

  assert.match(encoder, /needsHumanReview", booleanConstant\(true\)/);
  assert.match(
    encoder,
    /booleanConstant\(versions\.knowledgeSource\(\)\.containsCustomerData\(\)\)/,
  );
  assert.match(encoder, /schema\.put\("type", "boolean"\)/);
  assert.match(encoder, /schema\.set\("enum", JSON\.arrayNode\(\)\.add\(value\)\)/);
  assert.match(encoder, /PROVIDER_VERSION\.equals\(provider\.version\(\)\)/);

  assert.match(hardening, /strictSchemaUsesTypedBooleanConstantsAndExactProviderVersion/);
  assert.match(hardening, /decodedConfidenceUsesTheAcceptedP2HalfAndFourFifthsThresholds/);
  assert.match(hardening, /0\.50d, AiAdvisoryResult\.ConfidenceBand\.MEDIUM/);
  assert.match(hardening, /0\.80d, AiAdvisoryResult\.ConfidenceBand\.HIGH/);
});

test('P6-C decoder admits current known fields only in the stateless profile', () => {
  const decoder = text(javaRoot, 'OpenAiResponsesResponseDecoder.java');
  const hardening = text(testRoot, 'OpenAiResponsesCodecHardeningTest.java');

  for (const field of ['max_tool_calls', 'prompt', 'top_logprobs']) {
    assert.match(decoder, new RegExp(`"${field}"`));
  }
  for (const field of [
    'prompt',
    'prompt_cache_key',
    'prompt_cache_retention',
    'safety_identifier',
    'user',
    'max_tool_calls',
  ]) {
    assert.match(
      decoder,
      new RegExp(`requireAbsentOrNull\\(root, "${field}"\\)`),
    );
  }
  assert.match(decoder, /requireAbsentOrZero\(root, "top_logprobs"\)/);
  assert.match(decoder, /requireAbsentOrEmptyObject\(root, "metadata"\)/);
  assert.match(hardening, /currentKnownResponseFieldsAreAcceptedOnlyInTheStatelessProfile/);
  assert.match(hardening, /prompt_cache_retention", "24h"/);
  assert.match(hardening, /metadata.*tenant.*forbidden/s);
});
