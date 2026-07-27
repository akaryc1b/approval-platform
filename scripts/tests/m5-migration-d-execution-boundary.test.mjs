import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();
const read = (path) => readFileSync(join(root, path), 'utf8');
const application = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/'
    + 'ApprovalMigrationExecutionAdmissionService.java',
);
const jdbc = read(
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/'
    + 'JdbcApprovalMigrationExecutionAdmissionStore.java',
);
const migration = read(
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/'
    + 'V39__admit_authorized_process_migration_plans.sql',
);
const upgrade = read(
  'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/'
    + 'persistence/jdbc/JdbcApprovalMigrationUpgradeIntegrationTest.java',
);
const lineage = read(
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/'
    + 'V46__preserve_ambiguous_terminal_request_lineage.sql',
);

function filesBelow(path) {
  const absolute = join(root, path);
  return readdirSync(absolute).flatMap((name) => {
    const child = join(absolute, name);
    if (statSync(child).isDirectory()) {
      return filesBelow(child.slice(root.length + 1));
    }
    return [child];
  });
}

test('D1 consumes an exact authorized plan without an engine invocation', () => {
  assert.match(application, /requireAuthorizedPlan/);
  assert.match(application, /PlanStatus\.CONSUMED/);
  assert.match(application, /IntentStatus\.PENDING/);
  assert.doesNotMatch(application, /org\.flowable|ProcessMigrationService|\.migrate\(/);
  assert.doesNotMatch(jdbc, /org\.flowable|ProcessMigrationService|\.migrate\(/);
});

test('V39 linkage remains frozen while later D slices advance the schema', () => {
  assert.match(migration, /ap_process_migration_plan_consumption/);
  assert.match(migration, /AUTHORIZED' and new\.status='CONSUMED/);
  assert.match(migration, /intent for governed migration plan requires exact consumption evidence/);
  assert.match(migration, /consumed migration plan requires exact admitted intent evidence/);
  assert.match(migration, /append-only/);
  assert.doesNotMatch(migration, /ACT_[A-Z_]+/);
  assert.match(upgrade, /LATEST_VERSION = "48"/);
  assert.match(upgrade, /new UpgradeCase\("approval_latest_v38", "38"\)/);
  assert.match(upgrade, /new UpgradeCase\("approval_latest_v39", "39"\)/);
  assert.match(upgrade, /new UpgradeCase\("approval_latest_v40", "40"\)/);
  assert.match(upgrade, /new UpgradeCase\("approval_latest_v41", "41"\)/);
  assert.match(upgrade, /new UpgradeCase\("approval_latest_v42", "42"\)/);
  assert.match(upgrade, /new UpgradeCase\("approval_latest_v43", "43"\)/);
  assert.match(upgrade, /new UpgradeCase\("approval_latest_v44", "44"\)/);
  assert.match(upgrade, /new UpgradeCase\("approval_latest_v45", "45"\)/);
  assert.match(upgrade, /new UpgradeCase\("approval_latest_v46", "46"\)/);
  assert.match(upgrade, /new UpgradeCase\("approval_latest_v47", "47"\)/);
  assert.match(upgrade, /assertNoExecutionSideEffects/);
  assert.match(lineage, /ck_process_migration_attempt_request_v46/);
  assert.match(lineage, /BLOCKED_STALE/);
  assert.match(lineage, /FAILED_TERMINAL/);
  assert.match(lineage, /engine_outcome='UNKNOWN'/);
});

test('D1 keeps execution internal and does not add public controls', () => {
  const serverJava = filesBelow('server-modules')
    .filter((path) => path.endsWith('.java'))
    .filter((path) => path.includes('MigrationExecution') || path.includes('MigrationPlanConsumption'))
    .map((path) => readFileSync(path, 'utf8'))
    .join('\n');
  assert.doesNotMatch(serverJava, /@RestController|@Controller|@RequestMapping|@PostMapping/);
  assert.doesNotMatch(serverJava, /forceSuccess|fakeRollback|ignoreValidation|skipAudit/);
});
