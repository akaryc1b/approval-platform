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
  'docs/m6/M6_E_P7_FINAL_READY_REVIEW_CORRECTION_VALIDATION_ACCEPTANCE.md',
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

test('final Ready validation freezes exact implementation workflow and artifacts', () => {
  for (const evidence of [
    '1ad630949fb92e9126f9303f3a7457036c9364f8',
    '30911672094',
    '1266 / 0 / 0 / 0',
    '295 / 0 / 0 / 0',
    '1561 / 0 / 0 / 0',
    '60 / 60',
    '175 / 175',
    '67 / 67',
    '8893426431',
    '327644',
    '3bf8294de6a26da0b7dfe56156bcacdd4c25f64a5d7074860b316654945b9a70',
    '8893397669',
    '18869',
    '523c637c0e81d28ab6195f2ee86568edde10867112facfdaf17ec75fb7d91959',
    '8893374179',
    '9788',
    '63dda182be0ed4570a7697dc006a9e170e14c34a71ef042eada68e375a6d8280',
    '8893343071',
    '11594',
    '75f95deb38785acb8a2eb3b3e534fba4e4f501c847167d6aa01399bbe4bbb2a4',
  ]) {
    assert.match(acceptance, new RegExp(evidence.replaceAll('/', '\\/')));
  }
  assert.match(acceptance, /P7_FINAL_READY_REVIEW_CORRECTION_IMPLEMENTATION_ACCEPTED/);
  assert.match(acceptance, /DOCUMENTED_HEAD_PERMANENT_VALIDATION_REQUIRED/);
});

test('validated decoder remains closed around exact default verbosity', () => {
  assert.match(decoder, /TEXT_FIELDS = Set\.of\("format", "verbosity"\)/);
  assert.match(decoder, /if \(text\.has\("verbosity"\)\)/);
  assert.match(
    decoder,
    /requireExactText\(text, "verbosity", "medium", SCHEMA_MISMATCH\)/,
  );
  assert.match(decoderTest, /defaultTextVerbosityIsAcceptedButDriftFailsClosed/);
  assert.match(decoderTest, /remove\("verbosity"\)/);
  assert.match(decoderTest, /put\("verbosity", "low"\)/);
  assert.match(decoderTest, /put\("verbosity", "high"\)/);
  assert.match(decoderTest, /put\("verbosity", 1\)/);
  assert.match(acceptance, /unknown siblings continue to fail as `UNKNOWN_PROPERTY`/);
});

test('validated service keeps post-Provider task equality before evidence', () => {
  assert.equal((service.match(/taskQuery\.findPendingTask\s*\(/g) || []).length, 3);
  assert.equal((service.match(/orchestrator\.execute\s*\(/g) || []).length, 1);
  assert.equal((service.match(/evidenceStore\.store\s*\(/g) || []).length, 1);

  const execution = service.indexOf('outcome = orchestrator.execute(request)');
  const post = service.indexOf(
    'Optional<PendingTaskDetails> postInvocation = taskQuery.findPendingTask(identity)',
  );
  const evidence = service.indexOf(
    'evidence = ApprovalAssistanceProductionDurableEvidenceFactory.create',
  );
  const store = service.indexOf('stored = evidenceStore.store(evidence)');
  assert.ok(execution >= 0 && post > execution && evidence > post && store > evidence);
  assert.match(service, /postInvocation\.isEmpty\(\)/);
  assert.match(service, /!task\.equals\(postInvocation\.orElseThrow\(\)\)/);
  assert.match(service, /GenerationStatus\.STALE_TASK/);
});

test('validated stale-state tests retain one Provider attempt and zero writes', () => {
  assert.match(serviceTest, /changedTaskAfterProviderFailsBeforeEvidenceStore/);
  assert.match(serviceTest, /missingTaskAfterProviderFailsBeforeEvidenceStore/);
  assert.equal(
    (serviceTest.match(/assertEquals\(3, query\.singleReads\.get\(\)\)/g) || []).length,
    4,
  );
  assert.equal(
    (serviceTest.match(/assertEquals\(1, providerCalls\.get\(\)\)/g) || []).length,
    4,
  );
  assert.equal(
    (serviceTest.match(/assertEquals\(0, store\.writes\.get\(\)\)/g) || []).length >= 3,
    true,
  );
  assert.match(acceptance, /never retries the Provider/);
});

test('final Ready validation retains repository and authority closure', () => {
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

  assert.match(acceptance, /FINAL_READY_REVIEW_THREADS_PENDING_EVIDENCE_REPLY/);
  assert.match(acceptance, /PR_REMAINS_DRAFT/);
  assert.match(acceptance, /M6_F_REMAINS_GATED/);
  assert.match(acceptance, /AI_IS_NOT_AN_OPERATOR/);
});
