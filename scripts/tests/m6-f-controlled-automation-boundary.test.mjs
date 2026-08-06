import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const r0Document = path.join(
  root,
  'docs/m6/M6_F_R0_REBASELINE_AND_AUTHORITY_THREAT_MODEL.md',
);
const p0AuditDocument = path.join(
  root,
  'docs/m6/M6_F_P0_EXISTING_COMMAND_AUDIT.md',
);
const whitelistDecisionDocument = path.join(
  root,
  'docs/m6/M6_F_ACTION_WHITELIST_DECISION.md',
);

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

function productionFiles() {
  const roots = [
    'apps/server/src/main',
    'apps/web/overlay/apps/web-ele/src',
    'apps/mobile/overlay/src',
    'server-modules',
  ];
  return roots.flatMap((relativePath) => filesUnder(path.join(root, relativePath)))
    .filter((file) => !file.includes(`${path.sep}src${path.sep}test${path.sep}`))
    .filter((file) => /\.(java|ts|vue|sql|ya?ml)$/.test(file));
}

test('R0 freezes the permanent authority chain and empty whitelist', () => {
  const document = readFileSync(r0Document, 'utf8');

  assert.match(document, /AI_IS_NOT_AN_OPERATOR/);
  assert.match(document, /EMPTY_PENDING_EXISTING_COMMAND_AUDIT/);
  assert.match(document, /Provider -> direct command/);
  assert.match(document, /typed non-executable proposal/);
  assert.match(document, /fresh server policy and precondition evaluation/);
  assert.match(document, /fresh authorization preview/);
  assert.match(document, /explicit human confirmation/);
  assert.match(document, /existing application command service/);
  assert.match(document, /immutable audited result/);
  assert.match(document, /Proposal is not a command, credential, permission token/);
});

test('R0 retains advisory-only M6-E client and server semantics', () => {
  const mobile = read('apps/mobile/overlay/src/api/approval/assistance.ts');
  const web = read('apps/web/overlay/apps/web-ele/src/api/approval/assistance.ts');
  const server = read(
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalAssistanceReadContracts.java',
  );

  assert.match(mobile, /commandAvailable:\s*false/);
  assert.match(web, /commandAvailable:\s*false/);
  assert.match(server, /commandAvailable/);
  assert.doesNotMatch(`${mobile}\n${web}`, /approveAssistance|executeAssistance|confirmAssistance/);
});

test('Provider modules cannot import application command authority', () => {
  const aiProduction = filesUnder(path.join(root, 'server-modules'))
    .filter((file) => file.includes(`${path.sep}approval-ai-`))
    .filter((file) => file.includes(`${path.sep}src${path.sep}main${path.sep}`))
    .filter((file) => file.endsWith('.java'))
    .map((file) => readFileSync(file, 'utf8'))
    .join('\n');

  assert.doesNotMatch(
    aiProduction,
    /import\s+io\.github\.akaryc1b\.approval\.application\./,
  );
  assert.doesNotMatch(
    aiProduction,
    /\b(ApprovalMessageService|ApprovalTaskCommandService|ApprovalProcessCommandService)\b/,
  );
});

test('M6-F production surface contains no executable payload or credential carrier', () => {
  const candidates = productionFiles()
    .filter((file) => /m6.?f|automation|proposal|confirmation/i.test(file))
    .map((file) => readFileSync(file, 'utf8'))
    .join('\n');

  assert.doesNotMatch(
    candidates,
    /\b(String|byte\[\]|Object)\s+(apiKey|bearerToken|sessionCredential|permissionToken|secret|password|privateKey)\b/,
  );
  assert.doesNotMatch(
    candidates,
    /\b(String|Object)\s+(sql|script|executableExpression|javaClassName|dynamicModule|httpBody|connectorCredential)\b/,
  );
  assert.doesNotMatch(candidates, /Runtime\.getRuntime\(\)\.exec|ProcessBuilder\s*\(/);
});

test('M6-F introduces no automatic executor, worker, queue, scheduler or polling path', () => {
  const candidates = productionFiles()
    .filter((file) => /m6.?f|automation|proposal|confirmation/i.test(file))
    .map((file) => readFileSync(file, 'utf8'))
    .join('\n');

  assert.doesNotMatch(candidates, /@Scheduled\b|SchedulingConfigurer|TaskScheduler/);
  assert.doesNotMatch(candidates, /\b(class|interface|record)\s+\w*(Worker|Queue|Scheduler|Poller)\b/);
  assert.doesNotMatch(candidates, /setInterval\s*\(|setTimeout\s*\([^,]+,\s*\d+/);
});

test('R0 and P0 preserve migration upper bound V49', () => {
  const migrationRoot = path.join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
  );
  const versions = readdirSync(migrationRoot)
    .map((name) => /^V(\d+)__.+\.sql$/.exec(name))
    .filter(Boolean)
    .map((match) => Number(match[1]));

  assert.equal(Math.max(...versions), 49);
  assert.equal(versions.some((version) => version >= 50), false);
  assert.equal(
    existsSync(path.join(migrationRoot, 'V49__create_ai_approval_assistance_durable_evidence.sql')),
    true,
  );
});

test('controlled automation boundary uses only the existing automatic workflow', () => {
  const workflowRoot = path.join(root, '.github/workflows');
  const workflows = readdirSync(workflowRoot).filter((name) => /\.ya?ml$/.test(name));
  const automatic = workflows.filter((name) => {
    const content = readFileSync(path.join(workflowRoot, name), 'utf8');
    return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
  });

  assert.deepEqual(automatic, ['approval-platform-validation.yml']);
  const workflow = readFileSync(
    path.join(workflowRoot, 'approval-platform-validation.yml'),
    'utf8',
  );
  assert.match(workflow, /m6-ai-transport-review-boundary\.test\.mjs/);
});

test('R0 explicitly excludes every high-risk and arbitrary execution action', () => {
  const document = readFileSync(r0Document, 'utf8');
  for (const prohibited of [
    'approve',
    'reject or return',
    'transfer',
    'withdraw',
    'terminate',
    'migrate',
    'arbitrary HTTP',
    'arbitrary SQL',
    'arbitrary script',
    'direct connector command',
    'direct Flowable command',
  ]) {
    assert.match(document, new RegExp(prohibited.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }
});

test('P0 records result A and authorizes no executable Action', () => {
  const audit = readFileSync(p0AuditDocument, 'utf8');
  const decision = readFileSync(whitelistDecisionDocument, 'utf8');

  assert.match(audit, /P0_RESULT_A_NO_QUALIFYING_EXISTING_COMMAND/);
  assert.match(audit, /Each candidate was evaluated against the mandatory twenty-point gate/);
  assert.match(audit, /C20/);
  assert.match(audit, /P5-A is skipped and remains prohibited/);
  assert.match(decision, /m6-f-action-whitelist-v1-empty/);
  assert.match(decision, /Action count: `0`/);
  assert.match(decision, /EMPTY_PENDING_EXISTING_COMMAND_AUDIT/);
  assert.match(decision, /P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND/);
  assert.doesNotMatch(decision, /SINGLE_EXISTING_LOW_RISK_ACTION_/);
});

test('P0 does not confuse session expiry, reason or audit with reauthentication', () => {
  const principal = read(
    'apps/server/src/main/java/io/github/akaryc1b/approval/security/ApprovalPrincipal.java',
  );
  const identityFilter = read(
    'apps/server/src/main/java/io/github/akaryc1b/approval/security/ApprovalIdentityContextFilter.java',
  );
  const management = read(
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalManagementPermissionInterceptor.java',
  );
  const authentication = `${principal}\n${identityFilter}\n${management}`;

  assert.match(principal, /sessionExpiresAt/);
  assert.match(management, /operationReason/);
  assert.match(management, /governance\.recordAuthorized/);
  assert.doesNotMatch(
    authentication,
    /\b(reauthentication|reauthenticated|stepUpAuthentication|freshAuthentication|mfaEvidence)\b/i,
  );
});

test('P0 low-risk candidates retain their real controls and documented blockers', () => {
  const messageService = read(
    'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/ApprovalMessageService.java',
  );
  const messageController = read(
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalMessageController.java',
  );
  const notificationService = read(
    'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/ApprovalNotificationService.java',
  );
  const commentService = read(
    'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/ApprovalCommentService.java',
  );
  const commentController = read(
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalCommentController.java',
  );

  assert.match(messageService, /public MessageActionResult urge/);
  assert.match(messageService, /public MessageActionResult copy/);
  assert.match(messageService, /public Optional<ReadResult> markRead/);
  assert.match(messageService, /IdempotencyGuard/);
  assert.doesNotMatch(messageController, /@ApprovalManagementPermission/);

  assert.match(notificationService, /public PreferenceBundle updatePreferences/);
  assert.match(notificationService, /expectedVersion/);
  assert.doesNotMatch(notificationService, /IdempotencyGuard/);

  assert.match(commentService, /IdempotencyGuard/);
  assert.match(commentService, /expectedVersion/);
  assert.match(commentService, /appendMentionMessages/);
  assert.doesNotMatch(commentController, /@ApprovalManagementPermission/);
});

test('P0 rejects high-risk, replay and connector-adjacent candidates', () => {
  const templateController = read(
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ProcessTemplateManagementController.java',
  );
  const collaboration = read(
    'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/ApprovalTaskCollaborationService.java',
  );
  const operationalReplay = read(
    'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/ApprovalOperationalFailureService.java',
  );
  const audit = readFileSync(p0AuditDocument, 'utf8');

  assert.match(templateController, /Requirement\.TRANSFER/);
  assert.match(collaboration, /TaskOutcome\.APPROVED/);
  assert.match(collaboration, /DECIDE_OPERATION/);
  assert.match(operationalReplay, /replayDeadLetter/);
  assert.match(operationalReplay, /replayOutboxDead/);
  assert.match(operationalReplay, /consistency\.run/);
  assert.match(audit, /Approve, reject\/return, resubmit, transfer, withdraw, retrieve/);
  assert.match(audit, /direct connector commands/);
});
