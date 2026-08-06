import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function read(relativePath) {
  const file = path.join(root, relativePath);
  assert.equal(existsSync(file), true, `${relativePath} must exist`);
  return readFileSync(file, 'utf8');
}

function walk(directory) {
  return readdirSync(directory).flatMap((name) => {
    const child = path.join(directory, name);
    return statSync(child).isDirectory() ? walk(child) : [child];
  });
}

const core = read(
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/'
    + 'ControlledAutomationLineageStore.java',
);
const jdbc = read(
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/'
    + 'persistence/jdbc/JdbcControlledAutomationLineageStore.java',
);
const migration = read(
  'server-modules/approval-persistence-jdbc/src/main/resources/m6f/db/migration/'
    + 'V50__create_ai_controlled_automation_lineage.sql',
);
const integration = read(
  'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/'
    + 'persistence/jdbc/JdbcControlledAutomationLineageStoreIntegrationTest.java',
);
const instantPrecisionIntegration = read(
  'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/'
    + 'persistence/jdbc/JdbcControlledAutomationLineageInstantPrecisionIntegrationTest.java',
);
const architecture = read(
  'server-modules/approval-architecture-tests/src/test/java/io/github/akaryc1b/approval/'
    + 'architecture/M6FControlledAutomationLineageArchitectureTest.java',
);
const configuration = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
    + 'ControlledAutomationLineageConfiguration.java',
);
const persistencePom = read(
  'server-modules/approval-persistence-jdbc/pom.xml',
);
const acceptance = read(
  'docs/m6/M6_F_P4_DURABLE_LINEAGE_CAS_REPLAY_ACCEPTANCE.md',
);

test('P4 lineage is hash-only, confirmation-bound and permanently non-executable', () => {
  assert.match(core, /ControlledAutomationConfirmationEvidence/);
  assert.match(core, /confirmationEvidenceHash/);
  assert.match(core, /sourceEvidenceHash/);
  assert.match(core, /typedParameterHash/);
  assert.match(core, /proposalLineageHash/);
  assert.match(core, /resourceEvidenceHash/);
  assert.match(core, /registrationIdempotencyKeyHash/);
  assert.match(core, /registrationIdempotencyPayloadHash/);
  assert.match(core, /CONFIRMED/);
  assert.match(core, /CANCELLED/);
  assert.match(core, /SUCCEEDED/);
  assert.match(core, /FAILED/);
  assert.match(core, /PARTIAL/);
  assert.match(core, /UNKNOWN/);
  assert.match(core, /P4 permits zero or one command attempt and never automatic retry/);
  assert.doesNotMatch(
    core,
    /\b(String|Object|byte\[\])\s+(rawValue|rawPayload|commandPayload|secret|password|token)\b/,
  );
  assert.doesNotMatch(core, /ApprovalMessageService|ConnectorInvocation|ProcessMigrationService/);
});

test('P4 JDBC rounds PostgreSQL instants before replay, hashing and row-locked CAS', () => {
  assert.match(jdbc, /implements ControlledAutomationLineageStore/);
  assert.match(
    jdbc,
    /RegistrationCommand exact = canonicalRegistration\([\s\S]*transactions\.execute\(status -> registerOnce\(exact\)\)/,
  );
  assert.match(
    jdbc,
    /TransitionCommand exact = canonicalTransition\([\s\S]*transactions\.execute\(status -> transitionOnce\(exact\)\)/,
  );
  assert.match(
    jdbc,
    /private static RegistrationCommand canonicalRegistration\([\s\S]*RegistrationCommand\.fromEvidence\(/,
  );
  assert.match(
    jdbc,
    /private static TransitionCommand canonicalTransition\([\s\S]*TransitionCommand\.create\(/,
  );
  assert.match(jdbc, /NANOS_PER_MICROSECOND = 1_000L/);
  assert.match(
    jdbc,
    /HALF_MICROSECOND_NANOS = NANOS_PER_MICROSECOND \/ 2/,
  );
  assert.match(
    jdbc,
    /private static Instant postgresInstant\([\s\S]*long remainder = exact\.getNano\(\) % NANOS_PER_MICROSECOND;[\s\S]*if \(remainder < HALF_MICROSECOND_NANOS\) \{[\s\S]*return exact\.minusNanos\(remainder\);[\s\S]*return exact\.plusNanos\(NANOS_PER_MICROSECOND - remainder\);/,
  );
  assert.doesNotMatch(jdbc, /truncatedTo\(ChronoUnit\.MICROS\)/);
  assert.doesNotMatch(jdbc, /registerOnce\(command\)|transitionOnce\(command\)/);
  assert.match(jdbc, /on conflict do nothing/);
  assert.match(jdbc, /for update/);
  assert.match(jdbc, /RegistrationDisposition\.REPLAYED/);
  assert.match(jdbc, /RegistrationDisposition\.CONFLICT/);
  assert.match(jdbc, /TransitionDisposition\.IDEMPOTENCY_CONFLICT/);
  assert.match(jdbc, /TransitionDisposition\.REVISION_CONFLICT/);
  assert.match(jdbc, /TransitionDisposition\.STATE_CONFLICT/);
  assert.match(jdbc, /controlled-automation lineage CAS lost after row lock/);
  assert.doesNotMatch(
    jdbc,
    /ApprovalMessageService|ApprovalTaskCollaborationService|AiAdvisoryProvider|ConnectorInvocation|RuntimeService|TaskService|HttpClient|WebClient|RestClient|@Scheduled/,
  );
});

test('V50 creates immutable state and append-only event lineage with no retry', () => {
  assert.match(migration, /create table ap_ai_controlled_automation_lineage \(/);
  assert.match(migration, /create table ap_ai_controlled_automation_lineage_event \(/);
  assert.match(migration, /command_attempts between 0 and 1/);
  assert.match(migration, /not automatic_retry_allowed/);
  assert.match(migration, /status in \('CONFIRMED','CANCELLED','SUCCEEDED','FAILED','PARTIAL','UNKNOWN'\)/);
  assert.match(migration, /status='UNKNOWN' and outcome='UNKNOWN' and command_attempts=1/);
  assert.match(migration, /one ordered terminal CAS transition only/);
  assert.match(migration, /controlled-automation events are append-only/);
  assert.match(migration, /deferrable initially deferred/);
  assert.match(migration, /lineage state lacks an exact append-only event/);
  assert.doesNotMatch(migration, /\bACT_[A-Z0-9_]+\b|raw_payload|secret_value|credential_value/);
});

test('P4 real PostgreSQL tests cover replay concurrency cancellation and UNKNOWN', () => {
  for (const scenario of [
    'registersAndReplaysExactHashOnlyLineage',
    'sameIdempotencyKeyWithDifferentPayloadConflicts',
    'sameProposalIdentityIsTenantAndOperatorScoped',
    'concurrentTerminalTransitionProducesOneWinner',
    'cancellationRecordsZeroAttempts',
    'unknownIsTerminalAndCannotBeRetried',
    'eventsAndLineageRejectPhysicalMutationOrDeletion',
  ]) {
    assert.match(integration, new RegExp(scenario));
  }
  assert.match(integration, /TransitionDisposition\.APPLIED/);
  assert.match(integration, /TransitionDisposition\.REPLAYED/);
  assert.match(integration, /TransitionDisposition\.STATE_CONFLICT/);
  assert.match(integration, /assertEquals\(2, eventCount\(registration\)\)/);
});

test('P4 PostgreSQL precision regression proves native rounding and replay boundaries', () => {
  for (const scenario of [
    'postgresqlRoundsToNearestMicrosecondAndCarriesIntoNextSecond',
    'registrationReplayRoundsBelowHalfMicrosecondDown',
    'registrationReplayRoundsHalfMicrosecondUp',
    'registrationDistinctPostgresMicrosecondsConflictAcrossHalfBoundary',
    'registrationRoundingCarriesIntoNextSecond',
    'transitionReplayRoundsHalfMicrosecondUp',
    'transitionDistinctPostgresMicrosecondsConflictAcrossHalfBoundary',
  ]) {
    assert.match(instantPrecisionIntegration, new RegExp(scenario));
  }
  assert.match(instantPrecisionIntegration, /PostgreSQLContainer/);
  assert.match(instantPrecisionIntegration, /JdbcTemplate/);
  assert.match(instantPrecisionIntegration, /Timestamp\.from\(value\)/);
  assert.match(instantPrecisionIntegration, /OffsetDateTime\.class/);
  assert.match(instantPrecisionIntegration, /123456499Z/);
  assert.match(instantPrecisionIntegration, /123456500Z/);
  assert.match(instantPrecisionIntegration, /999999500Z/);
  assert.match(instantPrecisionIntegration, /RegistrationDisposition\.REPLAYED/);
  assert.match(instantPrecisionIntegration, /RegistrationDisposition\.CONFLICT/);
  assert.match(instantPrecisionIntegration, /TransitionDisposition\.REPLAYED/);
  assert.match(
    instantPrecisionIntegration,
    /TransitionDisposition\.IDEMPOTENCY_CONFLICT/,
  );
  assert.doesNotMatch(instantPrecisionIntegration, /ChronoUnit\.MICROS/);
  assert.doesNotMatch(
    instantPrecisionIntegration,
    /Thread\.sleep|Math\.random|new Random\s*\(/,
  );
});

test('P4 is server-wired but exposes no API or execution composition', () => {
  assert.match(configuration, /ControlledAutomationLineageStore/);
  assert.match(configuration, /JdbcControlledAutomationLineageStore/);
  assert.match(configuration, /UUID::randomUUID/);
  assert.doesNotMatch(
    configuration,
    /FlywayConfigurationCustomizer|@RestController|@PostMapping|ApprovalMessageService/,
  );
  assert.match(persistencePom, /<exclude>m6f\/db\/migration\/\*\*<\/exclude>/);
  assert.match(persistencePom, /<targetPath>db\/migration\/m6f<\/targetPath>/);
  assert.match(architecture, /coreLineagePortCannotDependOnCommandsPersistenceNetworkConnectorOrFlowable/);
  assert.match(architecture, /p4PersistenceContainsOnlyHashLineageAndNoExecutionAuthority/);
  assert.match(architecture, /automaticRetryAllowed/);
});

test('P4 owns exact recursive V50 while historical M5 and M6-E migrations remain frozen', () => {
  const resourceRoot = path.join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/resources',
  );
  const migrationRoots = [
    path.join(resourceRoot, 'db/migration'),
    path.join(resourceRoot, 'm6f/db/migration'),
  ];
  const versioned = migrationRoots.flatMap(walk)
    .map((file) => path.relative(resourceRoot, file).replaceAll(path.sep, '/'))
    .map((name) => ({ name, match: /(?:^|\/)V(\d+)__.+\.sql$/.exec(name) }))
    .filter(({ match }) => match)
    .map(({ name, match }) => ({ name, version: Number(match[1]) }));
  assert.equal(Math.max(...versioned.map(({ version }) => version)), 50);
  assert.deepEqual(
    versioned.filter(({ version }) => version === 49).map(({ name }) => name),
    ['db/migration/V49__create_ai_approval_assistance_durable_evidence.sql'],
  );
  assert.deepEqual(
    versioned.filter(({ version }) => version === 50).map(({ name }) => name),
    ['m6f/db/migration/V50__create_ai_controlled_automation_lineage.sql'],
  );
  assert.deepEqual(versioned.filter(({ version }) => version >= 51), []);

  const upgrade = read(
    'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/'
      + 'persistence/jdbc/JdbcApprovalMigrationUpgradeIntegrationTest.java',
  );
  assert.match(upgrade, /CURRENT_LATEST_VERSION = "50"/);
  assert.match(upgrade, /new UpgradeCase\("approval_latest_v49", "49"\)/);
  assert.match(upgrade, /freshAndHistoricalUpgradePathsReachV50WithoutExecutionSideEffects/);
  assert.match(upgrade, /assertM6FLineageEmpty\(jdbc\)/);
});

test('P4 acceptance retains empty whitelist, unavailable reauthentication and P5 skip', () => {
  assert.match(acceptance, /P4_IMPLEMENTED_NON_EXECUTING/);
  assert.match(acceptance, /EMPTY_PENDING_EXISTING_COMMAND_AUDIT/);
  assert.match(acceptance, /REAUTHENTICATION_UNAVAILABLE/);
  assert.match(acceptance, /P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND/);
  assert.match(acceptance, /AI_IS_NOT_AN_OPERATOR/);
  assert.match(acceptance, /No command service is referenced or invoked/);
  assert.match(acceptance, /`UNKNOWN` is terminal and cannot be retried automatically/);
});

test('permanent transport review loads the P4 durable lineage boundary', () => {
  const aggregator = read('scripts/tests/m6-ai-transport-review-boundary.test.mjs');
  assert.match(
    aggregator,
    /import '\.\/m6-f-p4-durable-lineage-boundary\.test\.mjs';/,
  );
  const workflowRoot = path.join(root, '.github/workflows');
  const automatic = readdirSync(workflowRoot)
    .filter((name) => /\.ya?ml$/.test(name))
    .filter((name) => {
      const content = readFileSync(path.join(workflowRoot, name), 'utf8');
      return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
    });
  assert.deepEqual(automatic, ['approval-platform-validation.yml']);
});
