import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function filesUnder(directory) {
  if (!existsSync(directory)) return [];
  const files = [];
  for (const entry of readdirSync(directory)) {
    const absolute = path.join(directory, entry);
    if (statSync(absolute).isDirectory()) files.push(...filesUnder(absolute));
    else files.push(absolute);
  }
  return files;
}

function relative(file) {
  return path.relative(root, file).split(path.sep).join('/');
}

function text(file) {
  return readFileSync(file, 'utf8');
}

const bootstrapPath = path.join(root, 'docs/m6/M6_E_APPROVAL_ASSISTANCE_BOOTSTRAP.md');
const threatModelPath = path.join(root, 'docs/m6/M6_E_APPROVAL_ASSISTANCE_THREAT_MODEL.md');
const resultContractPath = path.join(
  root,
  'server-modules/approval-ai-spi/src/main/java/' +
    'io/github/akaryc1b/approval/ai/spi/AiAdvisoryResult.java',
);
const projectionPath = path.join(
  root,
  'server-modules/approval-ai-core/src/main/java/' +
    'io/github/akaryc1b/approval/ai/core/ApprovalAssistanceContextProjection.java',
);
const assemblerPath = path.join(
  root,
  'server-modules/approval-ai-core/src/main/java/' +
    'io/github/akaryc1b/approval/ai/core/ApprovalAssistanceContextAssembler.java',
);
const advisoryContractPath = path.join(
  root,
  'server-modules/approval-ai-core/src/main/java/' +
    'io/github/akaryc1b/approval/ai/core/ApprovalAssistanceAdvisoryContract.java',
);

const productionRoots = [
  path.join(root, 'server-modules/approval-ai-spi/src/main/java'),
  path.join(root, 'server-modules/approval-ai-core/src/main/java'),
  path.join(root, 'server-modules/approval-application/src/main/java'),
  path.join(root, 'apps/server/src/main/java'),
];

const allProductionJava = productionRoots
  .flatMap(filesUnder)
  .filter((file) => file.endsWith('.java'));

const m6eProductionJava = allProductionJava.filter((file) => {
  const name = path.basename(file);
  const source = text(file);
  const location = relative(file);
  return location.includes('/approval-ai-') ||
    /(?:Ai|AI|ApprovalAssistance|Advisory)/.test(name) ||
    /package\s+io\.github\.akaryc1b\.approval\.(?:ai|assistance)/.test(source);
});

test('M6-E P0 records the exact baseline, data flow and required future gates', () => {
  assert.equal(existsSync(bootstrapPath), true);
  assert.equal(existsSync(threatModelPath), true);

  const bootstrap = text(bootstrapPath);
  assert.match(bootstrap, /fcf031da9e6e04b15a1255044021a7fdd6637421/);
  assert.match(bootstrap, /30612812090/);
  assert.match(bootstrap, /Issue #78, closed \/ completed/);
  assert.match(bootstrap, /Issue #80/);
  assert.match(bootstrap, /server identity context/);
  assert.match(bootstrap, /field-permission projection/);
  assert.match(bootstrap, /bounded at-most-one Provider invocation/);
  assert.match(bootstrap, /durable minimal assistance evidence is required/);
  assert.match(bootstrap, /one real production Provider gate is required/);
  assert.match(bootstrap, /M6-E is synchronous and bounded/);
  assert.match(bootstrap, /AI_IS_NOT_AN_OPERATOR/);
  assert.match(bootstrap, /PROVIDER_TO_DIRECT_COMMAND_IS_PROHIBITED/);
});

test('M6-E threat model covers tenant, injection, Provider and authority-confusion threats', () => {
  const threatModel = text(threatModelPath);
  for (const required of [
    /forged tenant or operator/,
    /unauthorized field leakage/,
    /prompt injection/,
    /tool or command injection/,
    /cross-tenant Provider route/,
    /SSRF\/DNS rebinding\/redirect/,
    /stale approval state/,
    /feedback poisoning/,
    /background autonomous execution/,
    /confused deputy/,
    /UI authority confusion/,
    /Provider -> application command/,
  ]) {
    assert.match(threatModel, required);
  }
  assert.match(threatModel, /ADVISORY_NOT_AUTHORITY/);
  assert.match(threatModel, /HUMAN_REVIEW_REQUIRED/);
});

test('advisory result remains explicitly unverified and human-reviewed', () => {
  const resultContract = text(resultContractPath);
  assert.match(resultContract, /ADVISORY/);
  assert.match(resultContract, /UNVERIFIED_ADVISORY/);
  assert.match(resultContract, /needsHumanReview/);
  assert.doesNotMatch(
    resultContract,
    /\b(?:ApprovalCommand|ApprovalDecision|ExecutableAction|CommandCredential)\b/,
  );
});

test('M6-E and AI production code cannot directly acquire approval or Flowable authority', () => {
  assert.ok(m6eProductionJava.length > 0, 'AI production sources must be present');
  const production = m6eProductionJava.map((file) => `\n// ${relative(file)}\n${text(file)}`).join('\n');

  for (const forbidden of [
    /import\s+org\.flowable\./,
    /import\s+io\.github\.akaryc1b\.approval\.engine\./,
    /\bRuntimeService\b/,
    /\bTaskService\b/,
    /\bProcessMigrationService\b/,
    /\bProcessInstanceMigrationPort\b/,
    /\bApprovalCommandService\b/,
    /\bApprovalTaskCommandService\b/,
    /\bACT_[A-Z0-9_]+\b/,
  ]) {
    assert.doesNotMatch(production, forbidden);
  }
});

test('P1 projection is server-owned, minimized and side-effect free', () => {
  assert.equal(existsSync(projectionPath), true);
  assert.equal(existsSync(assemblerPath), true);
  const projection = text(projectionPath);
  const assembler = text(assemblerPath);
  const p1 = `${projection}\n${assembler}`;

  assert.match(projection, /stateVersion/);
  assert.match(projection, /formContentHash/);
  assert.match(projection, /schemaFieldCount/);
  assert.match(projection, /uiSchemaHash/);
  assert.match(projection, /submissionRevision/);
  assert.match(projection, /maximumTextCharactersPerValue/);
  assert.match(projection, /maximumTotalTextCharacters/);
  assert.match(projection, /ATTACHMENT_METADATA_KEYS/);
  assert.match(projection, /attachmentMetadataOnly/);
  assert.match(projection, /attachmentExtractionAttempted/);
  assert.match(projection, /schema field count does not match projection evidence/);
  assert.match(assembler, /AiDataMinimizer/);
  assert.match(assembler, /allowedFieldKeys/);
  assert.match(assembler, /FieldAccess\.HIDDEN/);
  assert.match(assembler, /AttachmentMetadata/);
  assert.match(assembler, /countProviderAttachmentMetadata/);
  assert.match(assembler, /AI_ASSISTANCE_CROSS_TENANT_CONTEXT/);
  assert.match(assembler, /AI_ASSISTANCE_TASK_STATE_MISMATCH/);
  assert.match(assembler, /AI_ASSISTANCE_FORM_UI_SCHEMA_MISMATCH/);

  for (const forbidden of [
    /import\s+java\.sql\./,
    /import\s+javax\.sql\./,
    /import\s+org\.springframework\./,
    /import\s+io\.github\.akaryc1b\.approval\.application\./,
    /import\s+io\.github\.akaryc1b\.approval\.engine\./,
    /\.advise\s*\(/,
    /@RestController\b/,
    /@Scheduled\b/,
  ]) {
    assert.doesNotMatch(p1, forbidden);
  }
});

test('P2 advisory contract is bounded, evidence-backed and non-executable', () => {
  assert.equal(existsSync(advisoryContractPath), true);
  const contract = text(advisoryContractPath);

  for (const required of [
    /SUMMARY\(AiCapability\.APPROVAL_SUMMARY\)/,
    /MATERIAL_COMPLETENESS\(AiCapability\.MATERIAL_COMPLETENESS\)/,
    /RISK_REVIEW\(AiCapability\.RISK_SIGNALS\)/,
    /KnowledgeSourceVersion\.none\(\)/,
    /ProjectionProvenance/,
    /expectedVersions/,
    /requestedAt\.isBefore\(provenance\.resourceObservedAt\(\)\)/,
    /P2_MAXIMUM_ITEM_LIMIT = 25/,
    /P2_MAXIMUM_EVIDENCE_LIMIT = 64/,
    /P2_MAXIMUM_LIMITATION_LIMIT = 12/,
    /needsHumanReview/,
    /Authority\.ADVISORY/,
    /AssertionStatus\.UNVERIFIED_ADVISORY/,
    /observations, risk signals and recommendations require evidence/,
    /every declared evidence reference must support an advisory item/,
    /evidence reference field is not present in the Provider-safe projection/,
  ]) {
    assert.match(contract, required);
  }

  for (const forbidden of [
    /SIMILAR_CASES/,
    /APPROVAL_OPINION_SUGGESTION/,
    /import\s+java\.sql\./,
    /import\s+javax\.sql\./,
    /import\s+org\.springframework\./,
    /import\s+io\.github\.akaryc1b\.approval\.application\./,
    /import\s+io\.github\.akaryc1b\.approval\.engine\./,
    /\bApprovalCommand(?:Service)?\b/,
    /\.advise\s*\(/,
    /@RestController\b/,
    /@Scheduled\b/,
  ]) {
    assert.doesNotMatch(contract, forbidden);
  }
});

test('M6-E remains synchronous and contains no autonomous execution role', () => {
  const m6eSources = m6eProductionJava.map(text).join('\n');
  for (const forbidden of [
    /@Scheduled\b/,
    /SchedulingConfigurer/,
    /class\s+\w*(?:Ai|ApprovalAssistance)\w*(?:Worker|Scheduler|Listener)\b/,
    /interface\s+\w*(?:Ai|ApprovalAssistance)\w*(?:Queue|Worker|Scheduler)\b/,
    /package\s+[^;]*\.(?:queue|worker|scheduler)\s*;/,
  ]) {
    assert.doesNotMatch(m6eSources, forbidden);
  }

  const bootstrap = text(bootstrapPath);
  assert.match(bootstrap, /no AI Queue, Worker, Scheduler, listener, polling/);
  assert.match(
    bootstrap,
    /No partial invocation\s+may trigger Provider fallback or an approval command/,
  );
});

test('the permanent workflow loads M6-E checks through the existing hygiene gate', () => {
  const workflowRoot = path.join(root, '.github/workflows');
  const workflows = filesUnder(workflowRoot).filter((file) => /\.ya?ml$/.test(file));
  const automatic = workflows.filter((file) => {
    const content = text(file);
    return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
  });
  assert.deepEqual(
    automatic.map(relative).sort(),
    ['.github/workflows/approval-platform-validation.yml'],
  );

  const permanent = text(path.join(workflowRoot, 'approval-platform-validation.yml'));
  assert.match(permanent, /m3-repository-hygiene\.test\.mjs/);

  const hygieneGate = text(path.join(root, 'scripts/tests/m3-repository-hygiene.test.mjs'));
  assert.match(hygieneGate, /m6-e-approval-assistance-boundary\.test\.mjs/);
});
