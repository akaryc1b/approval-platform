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

const acceptance = source(
  'docs/m6/M6_E_P7_FINAL_READY_REVIEW_CORRECTION_ACCEPTANCE.md',
);
const decoder = source(
  'server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesResponseDecoder.java',
);
const decoderTest = source(
  'server-modules/approval-ai-openai/src/test/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesResponseDecoderTest.java',
);
const service = source(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationService.java',
);
const serviceTest = source(
  'apps/server/src/test/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationServiceTest.java',
);

test('final Ready Review correction freezes both exact actionable findings', () => {
  assert.match(acceptance, /PRR_kwDOTbeZ188AAAABIVludg/);
  assert.match(acceptance, /01cd530dc08a11d220fd0c04d0423bb81d05e2cc/);
  assert.match(acceptance, /PRRT_kwDOTbeZ186WUnxy/);
  assert.match(acceptance, /PRRC_kwDOTbeZ187dSSHe/);
  assert.match(acceptance, /PRRT_kwDOTbeZ186WUnx4/);
  assert.match(acceptance, /PRRC_kwDOTbeZ187dSSHm/);
  assert.match(acceptance, /P7_FINAL_READY_REVIEW_CORRECTION_PENDING_EXACT_VALIDATION/);
  assert.match(acceptance, /FINAL_READY_REVIEW_THREADS_REMAIN_UNRESOLVED/);
});

test('strict decoder admits only absent or exact medium response verbosity', () => {
  assert.match(decoder, /TEXT_FIELDS = Set\.of\("format", "verbosity"\)/);
  assert.match(decoder, /if \(text\.has\("verbosity"\)\)/);
  assert.match(
    decoder,
    /requireExactText\(text, "verbosity", "medium", SCHEMA_MISMATCH\)/,
  );
  assert.doesNotMatch(decoder, /"low"|"high"/);

  assert.match(decoderTest, /defaultTextVerbosityIsAcceptedButDriftFailsClosed/);
  assert.match(decoderTest, /text\.put\("verbosity", "medium"\)/);
  assert.match(decoderTest, /remove\("verbosity"\)/);
  assert.match(decoderTest, /put\("verbosity", "low"\)/);
  assert.match(decoderTest, /put\("verbosity", "high"\)/);
  assert.match(decoderTest, /put\("verbosity", 1\)/);
  assert.equal(
    (decoderTest.match(/Failure\.SCHEMA_MISMATCH/g) || []).length >= 3,
    true,
  );
});

test('task is revalidated after the only Provider attempt and before P4 evidence', () => {
  assert.equal((service.match(/taskQuery\.findPendingTask\s*\(/g) || []).length, 3);
  assert.equal((service.match(/orchestrator\.execute\s*\(/g) || []).length, 1);
  assert.equal((service.match(/evidenceStore\.store\s*\(/g) || []).length, 1);

  const executeIndex = service.indexOf('outcome = orchestrator.execute(request)');
  const postIndex = service.indexOf(
    'Optional<PendingTaskDetails> postInvocation = taskQuery.findPendingTask(identity)',
  );
  const evidenceIndex = service.indexOf(
    'evidence = ApprovalAssistanceProductionDurableEvidenceFactory.create',
  );
  const storeIndex = service.indexOf('stored = evidenceStore.store(evidence)');

  assert.ok(executeIndex >= 0);
  assert.ok(postIndex > executeIndex);
  assert.ok(evidenceIndex > postIndex);
  assert.ok(storeIndex > evidenceIndex);
  assert.match(service, /postInvocation\.isEmpty\(\)/);
  assert.match(service, /!task\.equals\(postInvocation\.orElseThrow\(\)\)/);
  assert.match(service, /GenerationOutcome\.failure\(GenerationStatus\.STALE_TASK\)/);
});

test('post-Provider drift tests prove one attempt and zero evidence writes', () => {
  assert.match(serviceTest, /changedTaskAfterProviderFailsBeforeEvidenceStore/);
  assert.match(serviceTest, /missingTaskAfterProviderFailsBeforeEvidenceStore/);
  assert.match(serviceTest, /List\.of\(task\(\), task\(\), changed\)/);
  assert.match(serviceTest, /CountingTaskQuery\(List\.of\(task\(\), task\(\)\)\)/);
  assert.equal(
    (serviceTest.match(/assertEquals\(3, query\.singleReads\.get\(\)\)/g) || []).length,
    4,
  );
  assert.equal(
    (serviceTest.match(/assertEquals\(1, providerCalls\.get\(\)\)/g) || []).length,
    5,
  );
  assert.equal(
    (serviceTest.match(/assertEquals\(0, store\.writes\.get\(\)\)/g) || []).length >= 3,
    true,
  );
  assert.match(serviceTest, /durableConflictDoesNotCauseASecondProviderAttempt/);
  assert.match(serviceTest, /unavailableStoreDoesNotCauseASecondProviderAttempt/);
});

test('final Ready correction adds no migration workflow or autonomous authority', () => {
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

  assert.match(acceptance, /No request setting, model selection, endpoint, streaming mode or Provider capability is added/);
  assert.match(acceptance, /does not retry the Provider/);
  assert.match(acceptance, /PR_REMAINS_DRAFT/);
  assert.match(acceptance, /M6_F_REMAINS_GATED/);
  assert.match(acceptance, /AI_IS_NOT_AN_OPERATOR/);
});
