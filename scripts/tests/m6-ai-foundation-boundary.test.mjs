import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const aiRoots = [
  path.join(root, 'server-modules/approval-ai-spi'),
  path.join(root, 'server-modules/approval-ai-core'),
];

function filesUnder(directory) {
  if (!existsSync(directory)) return [];
  const result = [];
  for (const entry of readdirSync(directory)) {
    const absolute = path.join(directory, entry);
    if (statSync(absolute).isDirectory()) result.push(...filesUnder(absolute));
    else result.push(absolute);
  }
  return result;
}

function relative(file) {
  return path.relative(root, file).split(path.sep).join('/');
}

function text(file) {
  return readFileSync(file, 'utf8');
}

const aiFiles = aiRoots.flatMap(filesUnder);
const aiMainJava = aiFiles.filter((file) =>
  relative(file).includes('/src/main/java/') && file.endsWith('.java'));
const aiTestJava = aiFiles.filter((file) =>
  relative(file).includes('/src/test/java/') && file.endsWith('.java'));

test('AI production code has no network client, credentials, provider adapter or prompt asset', () => {
  assert.ok(aiMainJava.length > 0, 'AI main sources must exist');
  const production = aiMainJava.map(text).join('\n');
  const forbiddenPatterns = [
    /import\s+java\.net\./,
    /import\s+org\.springframework\.web\./,
    /import\s+okhttp3\./,
    /HttpClient\s*\./,
    /WebClient\s*\./,
    /RestClient\s*\./,
    /System\.getenv\s*\(/,
    /System\.getProperty\s*\(/,
    /@Value\s*\(/,
    /implements\s+AiAdvisoryProvider/,
    /Authorization\s*:/,
    /api[_-]?key\s*=/i,
  ];
  for (const pattern of forbiddenPatterns) {
    assert.doesNotMatch(production, pattern);
  }
  for (const moduleRoot of aiRoots) {
    assert.equal(
      existsSync(path.join(moduleRoot, 'src/main/resources')),
      false,
      `${relative(moduleRoot)} must not contain production prompt or knowledge assets`,
    );
  }
});

test('AI foundation has no persistence, V33, customer knowledge data or production mock', () => {
  const production = aiMainJava.map(text).join('\n');
  for (const pattern of [
    /import\s+java\.sql\./,
    /import\s+javax\.sql\./,
    /import\s+org\.springframework\.jdbc\./,
    /import\s+io\.github\.akaryc1b\.approval\.persistence\./,
    /\bJdbc[A-Z]/,
    /\bFlyway\b/,
  ]) {
    assert.doesNotMatch(production, pattern);
  }
  assert.equal(
    aiMainJava.some((file) => path.basename(file).includes('MockAiProvider')),
    false,
  );
  assert.ok(
    aiTestJava.some((file) => path.basename(file) === 'DeterministicMockAiProvider.java'),
    'deterministic mock must remain test-only',
  );

  const migrations = filesUnder(
    path.join(root, 'server-modules/approval-persistence-jdbc/src/main/resources/db/migration'),
  );
  assert.equal(
    migrations.some((file) => /^V33__/.test(path.basename(file))),
    false,
    'M6-D must not create V33',
  );
});

test('AI contracts cannot execute approval, transfer, termination or migration commands', () => {
  const production = aiMainJava.map(text).join('\n');
  for (const pattern of [
    /import\s+io\.github\.akaryc1b\.approval\.application\./,
    /import\s+io\.github\.akaryc1b\.approval\.engine\./,
    /\.(approve|reject|returnTask|transfer|withdraw|terminate|migrate|resubmit)\s*\(/,
    /RecommendationType\s*\{[^}]*(APPROVE|REJECT|TRANSFER|MIGRATE)/s,
  ]) {
    assert.doesNotMatch(production, pattern);
  }

  const resultContract = text(path.join(
    root,
    'server-modules/approval-ai-spi/src/main/java/' +
      'io/github/akaryc1b/approval/ai/spi/AiAdvisoryResult.java',
  ));
  assert.match(resultContract, /UNVERIFIED_ADVISORY/);
  assert.match(resultContract, /needsHumanReview/);
  assert.doesNotMatch(resultContract, /\bApprovalDecision\b|\bApprovalCommand\b/);
});

test('AI routing invokes at most one provider and forbids post-invocation fallback', () => {
  const coreSource = path.join(
    root,
    'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core',
  );
  const coordinator = text(path.join(coreSource, 'AiAdvisoryCoordinator.java'));
  const routingPolicy = text(path.join(coreSource, 'AiProviderRoutingPolicy.java'));
  const executionOutcome = text(path.join(coreSource, 'AiCoordinatedAdvisoryOutcome.java'));
  const routingMetrics = text(path.join(coreSource, 'AiProviderRoutingMetrics.java'));
  const usageEvidence = text(path.join(
    root,
    'server-modules/approval-ai-spi/src/main/java/' +
      'io/github/akaryc1b/approval/ai/spi/AiUsageEvidence.java',
  ));

  assert.equal((coordinator.match(/advisoryService\.advise\s*\(/g) || []).length, 1);
  assert.match(routingPolicy, /allowPreInvocationCandidateFallback/);
  assert.match(routingPolicy, /allowPostInvocationFallback/);
  assert.match(routingPolicy, /post-invocation provider fallback is prohibited/);
  assert.match(executionOutcome, /postInvocationFallbackAttempted/);
  assert.match(executionOutcome, /post-invocation fallback is prohibited/);
  assert.match(usageEvidence, /inputTokens/);
  assert.match(usageEvidence, /outputTokens/);
  assert.match(usageEvidence, /estimatedCost/);
  assert.match(usageEvidence, /observedLatencyMillis/);

  for (const highCardinality of [
    /tenantId/,
    /operatorId/,
    /userId/,
    /instanceId/,
    /taskId/,
    /requestId/,
    /traceId/,
    /promptContent/,
    /modelResponse/,
    /errorMessage/,
  ]) {
    assert.doesNotMatch(routingMetrics, highCardinality);
  }
});

test('AI artifact metadata and offline evaluation cannot contain prompts or authorize production', () => {
  const coreSource = path.join(
    root,
    'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core',
  );
  const prompt = text(path.join(coreSource, 'AiPromptTemplateDescriptor.java'));
  const knowledge = text(path.join(coreSource, 'AiKnowledgeSourceDescriptor.java'));
  const policy = text(path.join(coreSource, 'AiPolicyDescriptor.java'));
  const output = text(path.join(coreSource, 'AiOutputSchemaDescriptor.java'));
  const artifactRegistry = text(path.join(coreSource, 'AiAdvisoryArtifactRegistry.java'));
  const providerRegistry = text(path.join(coreSource, 'AiProviderRegistry.java'));
  const evaluationRunner = text(path.join(coreSource, 'AiEvaluationRunner.java'));
  const evaluationReport = text(path.join(coreSource, 'AiEvaluationReport.java'));

  assert.doesNotMatch(
    prompt,
    /\bString\s+(prompt|promptText|promptBody|content|body|instructions|messages)\b/,
  );
  assert.match(prompt, /Metadata-only prompt template authorization/);
  assert.match(knowledge, /containsCustomerData/);
  assert.match(knowledge, /knowledge retrieval is prohibited/);
  assert.match(policy, /humanReviewRequired/);
  assert.match(policy, /authoritativeDecisionAllowed/);
  assert.match(policy, /postInvocationRetryAllowed/);
  assert.match(output, /advisoryOnly/);
  assert.match(output, /humanReviewRequired/);
  assert.match(artifactRegistry, /exact AI prompt template metadata is not registered/);
  assert.match(providerRegistry, /artifactRegistry\.authorize/);
  assert.match(providerRegistry, /DETERMINISTIC_MOCK/);
  assert.doesNotMatch(evaluationRunner, /\.advise\s*\(/);
  assert.match(evaluationReport, /productionEnablementAuthorized/);
  assert.match(evaluationReport, /approvalAutomationAuthorized/);
  assert.match(evaluationReport, /cannot authorize production enablement/);
  assert.match(evaluationReport, /cannot authorize approval automation/);
});

test('only the permanent validation workflow is automatic', () => {
  const workflowRoot = path.join(root, '.github/workflows');
  const workflows = filesUnder(workflowRoot)
    .filter((file) => /\.ya?ml$/.test(file));
  const automatic = workflows.filter((file) => {
    const content = text(file);
    return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
  });
  assert.deepEqual(
    automatic.map(relative).sort(),
    ['.github/workflows/approval-platform-validation.yml'],
  );
});

test('M6-D branch diff preserves M5, runtime binding and frozen governance boundaries', () => {
  if (process.env.GITHUB_HEAD_REF !== 'agent/m6-d-ai-foundation') return;

  const changed = execFileSync(
    'git',
    ['diff', '--name-only', 'origin/main...HEAD'],
    { cwd: root, encoding: 'utf8' },
  ).trim().split('\n').filter(Boolean);
  const forbidden = changed.filter((file) =>
    file.startsWith('server-modules/approval-persistence-jdbc/') ||
    /migration.*(plan|intent|attempt|verification|reconciliation)/i.test(file) ||
    /runtime.*binding/i.test(file) ||
    /^docs\/M3_FINAL_ACCEPTANCE\.md$/.test(file) ||
    /^docs\/M4_.*GOVERNANCE\.md$/.test(file));
  assert.deepEqual(forbidden, []);
  assert.equal(changed.some((file) => /V33__/.test(file)), false);
});
