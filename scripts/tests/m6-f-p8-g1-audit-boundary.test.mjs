import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const auditPath = 'docs/m6/M6_F_P8_G1_COMPLETENESS_PRODUCTION_READINESS_AUDIT.md';

function read(relativePath) {
  const file = path.join(root, relativePath);
  assert.equal(existsSync(file), true, `${relativePath} must exist`);
  return readFileSync(file, 'utf8');
}

function filesUnder(directory) {
  if (!existsSync(directory)) return [];
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const child = path.join(directory, entry.name);
    return entry.isDirectory() ? filesUnder(child) : [child];
  });
}

function countMatches(content, pattern) {
  return [...content.matchAll(pattern)].length;
}

const audit = read(auditPath);
const proposal = read(
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/'
    + 'ControlledAutomationProposal.java',
);
const whitelist = read(
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/'
    + 'ControlledAutomationActionWhitelist.java',
);
const evaluator = read(
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/'
    + 'ControlledAutomationGovernanceEvaluator.java',
);
const confirmation = read(
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/'
    + 'ControlledAutomationConfirmationService.java',
);
const reauthentication = read(
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/'
    + 'ControlledAutomationReauthenticationVerifier.java',
);
const whitelistDecision = read('docs/m6/M6_F_ACTION_WHITELIST_DECISION.md');
const runtimeFactory = read(
  'server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai/'
    + 'OpenAiResponsesProductionRuntimeFactory.java',
);
const usageLedger = read(
  'server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai/'
    + 'OpenAiResponsesRuntimeUsageLedger.java',
);
const governanceConfiguration = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
    + 'ControlledAutomationGovernanceConfiguration.java',
);
const requestBoundary = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/security/'
    + 'ControlledAutomationGovernanceRequestBoundaryFilter.java',
);
const securityConfiguration = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
    + 'ControlledAutomationGovernanceSecurityConfiguration.java',
);
const v49 = read(
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/'
    + 'V49__create_ai_approval_assistance_durable_evidence.sql',
);
const v50 = read(
  'server-modules/approval-persistence-jdbc/src/main/resources/m6f/db/migration/'
    + 'V50__create_ai_controlled_automation_lineage.sql',
);
const jdbcLineage = read(
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/'
    + 'persistence/jdbc/JdbcControlledAutomationLineageStore.java',
);
const jdbcHistory = read(
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/'
    + 'persistence/jdbc/JdbcApprovalAssistanceGovernanceHistoryQuery.java',
);
const upgrade = read(
  'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/'
    + 'persistence/jdbc/JdbcApprovalMigrationUpgradeIntegrationTest.java',
);
const webBoundary = read(
  'apps/web/overlay/apps/web-ele/src/components/approval/'
    + 'ControlledAutomationConfirmationBoundary.vue',
);
const mobileBoundary = read(
  'apps/mobile/overlay/src/components/approval/'
    + 'ControlledAutomationConfirmationBoundary.vue',
);
const workflow = read('.github/workflows/approval-platform-validation.yml');

test('P8-G1 binds the complete non-executing controlled-automation authority', () => {
  assert.match(proposal, /Authority\.NON_EXECUTABLE_PROPOSAL/);
  assert.match(proposal, /ProposalStatus\.PROPOSED/);
  assert.match(proposal, /requiresHumanConfirmation/);
  assert.match(proposal, /ReauthenticationRequirement\.REQUIRED/);
  assert.match(whitelist, /static ControlledAutomationActionWhitelist empty/);
  assert.match(whitelistDecision, /EMPTY_PENDING_EXISTING_COMMAND_AUDIT/);
  assert.match(whitelistDecision, /P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND/);

  for (const freshGate of [
    'TENANT_EVIDENCE_MISMATCH',
    'OPERATOR_EVIDENCE_MISMATCH',
    'PROPOSAL_EXPIRED',
    'SOURCE_EVIDENCE_MISSING',
    'SOURCE_EVIDENCE_MISMATCH',
    'WHITELIST_VERSION_DRIFT',
    'ACTION_DEFINITION_DRIFT',
    'POLICY_VERSION_DRIFT',
    'PERMISSION_REVOKED',
    'RESOURCE_AUTHORIZATION_DENIED',
    'RESOURCE_STATE_DRIFT',
    'RESOURCE_VERSION_DRIFT',
    'SEPARATION_OF_DUTIES_DENIED',
    'COMMAND_PRECONDITION_FAILED',
    'REAUTHENTICATION_REQUIRED',
  ]) {
    assert.match(evaluator, new RegExp(freshGate));
  }

  assert.match(confirmation, /ConfirmationIntent\.EXPLICIT_CLICK/);
  assert.match(confirmation, /ConfirmationAuthority\.NON_EXECUTABLE_CONFIRMATION/);
  assert.match(confirmation, /singleUseRequired/);
  assert.match(confirmation, /commandAdmitted/);
  assert.match(reauthentication, /enum VerificationStatus \{[\s\S]*UNAVAILABLE/);
  assert.match(reauthentication, /return Verification\.unavailable\(\)/);
  assert.doesNotMatch(
    `${proposal}\n${evaluator}\n${confirmation}\n${reauthentication}`,
    /ApprovalTaskCommandService|ApprovalProcessCommandService|Runtime\.getRuntime\(\)\.exec/,
  );
});

test('P8-G1 finds no Provider-to-command or autonomous execution path in AI production code', () => {
  const roots = [
    'server-modules/approval-ai-spi/src/main',
    'server-modules/approval-ai-core/src/main',
    'server-modules/approval-ai-openai/src/main',
  ];
  const production = roots.flatMap((relativePath) => filesUnder(path.join(root, relativePath)))
    .filter((file) => file.endsWith('.java'))
    .map((file) => readFileSync(file, 'utf8'))
    .join('\n');

  assert.doesNotMatch(production, /import\s+io\.github\.akaryc1b\.approval\.application\./);
  assert.doesNotMatch(
    production,
    /\b(ApprovalTaskCommandService|ApprovalProcessCommandService|ApprovalMessageService)\b/,
  );
  assert.doesNotMatch(production, /\bACT_[A-Z0-9_]+\b|org\.flowable/);
  assert.doesNotMatch(production, /Runtime\.getRuntime\(\)\.exec|ProcessBuilder\s*\(/);
  assert.doesNotMatch(production, /@Scheduled\b|TaskScheduler/);
  assert.doesNotMatch(
    production,
    /\b(class|interface|record)\s+\w*(Worker|Queue|Scheduler|Poller)\b/,
  );
});

test('P8-G1 verifies one shared Runtime control plane and read-only observations', () => {
  assert.equal(
    countMatches(
      runtimeFactory,
      /private final OpenAiResponsesTransportControls\.RateLimiter rateLimiter;/g,
    ),
    1,
  );
  assert.equal(
    countMatches(
      runtimeFactory,
      /private final OpenAiResponsesTransportControls\.CircuitBreaker circuitBreaker;/g,
    ),
    1,
  );
  assert.equal(
    countMatches(runtimeFactory, /private final OpenAiResponsesRuntimeUsageLedger usageLedger;/g),
    1,
  );
  assert.equal(
    countMatches(
      runtimeFactory,
      /new OpenAiResponsesTransportControls\.RateLimiter\(/g,
    ),
    1,
  );
  assert.equal(
    countMatches(
      runtimeFactory,
      /new OpenAiResponsesTransportControls\.CircuitBreaker\(/g,
    ),
    1,
  );
  assert.equal(
    countMatches(runtimeFactory, /new OpenAiResponsesRuntimeUsageLedger\(/g),
    1,
  );
  assert.match(runtimeFactory, /public RuntimeControlSnapshot controlSnapshot\(\)/);
  assert.match(runtimeFactory, /public OpenAiResponsesRuntimeUsageLedger\.UsageSnapshot usageSnapshot/);
  assert.match(runtimeFactory, /synchronized \(circuitBreaker\)/);
  assert.match(usageLedger, /global-exact-usage-redacted/);
  assert.match(governanceConfiguration, /factory\.controlSnapshot\(\)/);
  assert.match(governanceConfiguration, /factory\.usageSnapshot\(trustedTenantId\)/);
  assert.match(governanceConfiguration, /requireStableRuntimeObservation/);
  assert.doesNotMatch(governanceConfiguration, /\.bind\s*\(|System\.getenv|openLease\s*\(/);
});

test('P8-G1 verifies exactly six tenant READ GET-only no-store operations endpoints', () => {
  const controllers = [
    'ControlledAutomationGovernanceReadController.java',
    'ControlledAutomationGovernancePlanController.java',
    'ControlledAutomationGovernanceControlHealthController.java',
    'ControlledAutomationGovernanceUsageController.java',
    'ControlledAutomationGovernanceHistoryController.java',
    'ControlledAutomationGovernanceIncidentReadinessController.java',
  ].map((name) => read(`apps/server/src/main/java/io/github/akaryc1b/approval/api/${name}`));

  assert.equal(controllers.length, 6);
  for (const controller of controllers) {
    assert.match(controller, /@ApprovalManagementPermission\(/);
    assert.match(controller, /Requirement\.READ/);
    assert.match(controller, /ResourceScope\.TENANT/);
    assert.match(controller, /@GetMapping/);
    assert.match(controller, /CacheControl\.noStore\(\)/);
    assert.doesNotMatch(
      controller,
      /@(PostMapping|PutMapping|PatchMapping|DeleteMapping)\b/,
    );
  }

  for (const endpoint of [
    '/snapshot',
    '/change-plan',
    '/control-health',
    '/usage',
    '/history',
    '/incident-readiness',
  ]) {
    assert.match(requestBoundary, new RegExp(endpoint.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }
  assert.match(requestBoundary, /if \(!"GET"\.equals\(request\.getMethod\(\)\)\)/);
  assert.match(requestBoundary, /AI_GOVERNANCE_METHOD_OVERRIDE_REJECTED/);
  assert.match(requestBoundary, /AI_GOVERNANCE_BODY_NOT_ALLOWED/);
  assert.match(requestBoundary, /values\.size\(\) != 1/);
  assert.match(requestBoundary, /values == null \|\| values\.length != 1/);
  assert.match(requestBoundary, /parsed\.toString\(\)\.equals\(value\)/);
  assert.match(securityConfiguration, /Ordered\.HIGHEST_PRECEDENCE \+ 10/);
});

test('P8-G1 verifies V49 and V50 ownership constraints CAS and upgrades', () => {
  assert.match(v49, /hash-only durable approval-assistance evidence/);
  assert.match(v49, /provider_attempts between 0 and 1/);
  assert.match(v49, /not retry_attempted and not post_invocation_fallback_attempted/);
  assert.match(v49, /evidence is immutable/);
  assert.match(v49, /evidence events are append-only/);
  assert.match(v49, /deferrable initially deferred/);

  assert.match(v50, /command_attempts between 0 and 1/);
  assert.match(v50, /not automatic_retry_allowed/);
  assert.match(
    v50,
    /status='CANCELLED'\s+and\s+outcome='NONE'\s+and\s+command_attempts=0/,
  );
  for (const [status, outcome] of [
    ['SUCCEEDED', 'SUCCESS'],
    ['FAILED', 'FAILURE'],
    ['PARTIAL', 'PARTIAL'],
    ['UNKNOWN', 'UNKNOWN'],
  ]) {
    assert.match(
      v50,
      new RegExp(
        `status='${status}'\\s+and\\s+outcome='${outcome}'\\s+and\\s+command_attempts=1`,
      ),
    );
  }
  assert.match(v50, /lineage events are append-only/);
  assert.match(v50, /deferrable initially deferred/);
  assert.match(jdbcLineage, /for update/);
  assert.match(jdbcLineage, /TransactionTemplate/);
  assert.match(jdbcHistory, /setReadOnly\(true\)/);
  assert.match(jdbcHistory, /ISOLATION_REPEATABLE_READ/);
  assert.match(upgrade, /freshAndHistoricalUpgradePathsReachV50WithoutExecutionSideEffects/);
  assert.match(upgrade, /upgradesV27WithFiveThousandInstancesAndTasksWithoutChangingEvidence/);

  const resourceRoot = path.join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/resources',
  );
  const versions = [
    path.join(resourceRoot, 'db/migration'),
    path.join(resourceRoot, 'm6f/db/migration'),
  ].flatMap((directory) => readdirSync(directory))
    .map((name) => /^V(\d+)__.+\.sql$/.exec(name))
    .filter(Boolean)
    .map((match) => Number(match[1]));
  assert.equal(Math.max(...versions), 50);
  assert.equal(versions.filter((version) => version === 49).length, 1);
  assert.equal(versions.filter((version) => version === 50).length, 1);
  assert.equal(versions.some((version) => version >= 51), false);
});

test('P8-G1 verifies P7 security fault and concurrency evidence is executable', () => {
  const acceptanceRoots = [
    'apps/server/src/test/java',
    'server-modules/approval-ai-core/src/test/java',
    'server-modules/approval-ai-openai/src/test/java',
    'server-modules/approval-persistence-jdbc/src/test/java',
  ];
  const selected = acceptanceRoots
    .flatMap((relativePath) => filesUnder(path.join(root, relativePath)))
    .filter((file) => /(?:Adversarial|Fault|Concurrency|IncidentRollback|UnknownIncident).*Test\.java$/.test(file));
  assert.ok(selected.length >= 15, 'P7 acceptance test surface must remain present');
  const content = selected.map((file) => readFileSync(file, 'utf8')).join('\n');
  assert.match(content, /CountDownLatch/);
  assert.match(content, /Executors\.newVirtualThreadPerTaskExecutor\(\)/);
  assert.match(content, /PostgreSQLContainer/);
  assert.doesNotMatch(content, /Thread\.sleep|Math\.random|new Random\s*\(/);
  assert.doesNotMatch(content, /System\.getenv\s*\(|sk-proj-|BEGIN PRIVATE KEY/);
});

test('P8-G1 verifies Web and Mobile parity and no executable or rendered-comment UI', () => {
  for (const client of [webBoundary, mobileBoundary]) {
    for (const value of [
      'AI_IS_NOT_AN_OPERATOR',
      'NON_EXECUTABLE',
      'NOT_AUTHORIZED',
      'ACTION_NOT_WHITELISTED',
      'EMPTY_PENDING_EXISTING_COMMAND_AUDIT',
      'UNAVAILABLE',
      '确认不可用',
      '确认成功不等于命令成功',
    ]) {
      assert.match(client, new RegExp(value));
    }
    assert.match(client, /disabled/);
    assert.doesNotMatch(
      client,
      /executeAutomation|confirmAndExecute|approveAssistance|rejectAssistance|runCommand/,
    );
    const template = client.match(/<template>([\s\S]*?)<\/template>/)?.[1] ?? '';
    assert.doesNotMatch(template, /<!--|\bTODO\b|\bFIXME\b/);
  }
});

test('P8-G1 verifies the sole permanent workflow has no broad test bypass', () => {
  const workflowRoot = path.join(root, '.github/workflows');
  const automatic = readdirSync(workflowRoot)
    .filter((name) => /\.ya?ml$/.test(name))
    .filter((name) => {
      const content = readFileSync(path.join(workflowRoot, name), 'utf8');
      return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
    });
  assert.deepEqual(automatic, ['approval-platform-validation.yml']);
  assert.match(workflow, /permissions:\s*\n\s*contents: read/);
  assert.match(workflow, /Java 21 \/ Maven core/);
  assert.match(workflow, /Persistence JDBC \/ shard/);
  assert.match(workflow, /Java 21 \/ Maven \/ PostgreSQL/);
  assert.match(workflow, /Vben TypeScript \/ production build/);
  assert.match(workflow, /UniApp TypeScript \/ H5 \/ WeChat/);
  assert.match(workflow, /Repository hygiene/);
  assert.match(workflow, /m6-ai-transport-review-boundary\.test\.mjs/);
  assert.doesNotMatch(workflow, /continue-on-error\s*:\s*true/);
  assert.doesNotMatch(workflow, /-DskipTests\b|-Dmaven\.test\.skip=true/);

  const productionRoots = [
    'apps/server/src/main',
    'apps/web/overlay/apps/web-ele/src',
    'apps/mobile/overlay/src',
    'server-modules',
  ];
  const production = productionRoots
    .flatMap((relativePath) => filesUnder(path.join(root, relativePath)))
    .filter((file) => !file.includes(`${path.sep}src${path.sep}test${path.sep}`))
    .filter((file) => /\.(java|ts|vue|sql|ya?ml)$/.test(file))
    .map((file) => readFileSync(file, 'utf8'))
    .join('\n');
  assert.doesNotMatch(production, /\/Users\/[^/\s]+\/|C:\\Users\\/);
  const credentialTokenPattern = new RegExp(
    'g' + 'hp_[A-Za-z0-9]{20,}|sk-' + 'proj-[A-Za-z0-9_-]{20,}',
  );
  assert.doesNotMatch(production, credentialTokenPattern);
  assert.doesNotMatch(production, /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/);
});

test('P8-G1 audit records every required section and honest limitation', () => {
  for (const section of [
    'A — Controlled Automation completeness',
    'B — AI Governance completeness',
    'C — Operations API completeness',
    'D — Persistence and upgrade compatibility',
    'E — Security audit',
    'F — Fault and concurrency audit',
    'G — Web and Mobile audit',
    'H — Workflow and repository audit',
    'I — Honest limitations',
  ]) {
    assert.match(audit, new RegExp(section.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }
  for (const limitation of [
    'EMPTY_PENDING_EXISTING_COMMAND_AUDIT',
    'P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND',
    'Production Reauthentication',
    'automatic Retry',
    'automatic Retry, fallback, Rollback, Notification, Incident execution or Retention Tombstone',
    'actual Provider billing',
    'durable P6-D cost-upper-bound History',
    'durable Circuit or Control Health time-series',
    'Canary, rollout, deployment or traffic mutation',
    'Provider, model, Prompt, Policy or Secret mutation',
  ]) {
    assert.match(
      audit,
      new RegExp(limitation.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'),
    );
  }
  assert.match(audit, /No blocking completeness, security, compatibility/);
  assert.match(audit, /P8_G1_PENDING_EXACT_HEAD_VALIDATION/);
  assert.match(audit, /P8_G2_PROHIBITED/);
  assert.doesNotMatch(audit, /\b(TODO|TBD|FIXME)\b/);
});
