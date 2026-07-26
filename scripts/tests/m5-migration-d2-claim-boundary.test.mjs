import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();
const read = (path) => readFileSync(join(root, path), 'utf8');
const applicationRoot =
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/';
const domainRoot =
  'server-modules/approval-domain/src/main/java/io/github/akaryc1b/approval/';
const jdbcRoot =
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/';

const operations = read(
  `${domainRoot}domain/migration/ApprovalCommandOperation.java`,
);
const fenceDomain = read(
  `${domainRoot}domain/migration/ApprovalMigrationCommandFence.java`,
);
const claimService = read(
  `${applicationRoot}application/ApprovalMigrationAttemptClaimService.java`,
);
const runner = read(
  `${applicationRoot}application/ApprovalMigrationOneShotClaimRunner.java`,
);
const projectionFence = read(
  `${applicationRoot}application/CommandFencedApprovalProjectionStore.java`,
);
const provisioningPort = read(
  `${applicationRoot}application/port/ApprovalMigrationAttemptProvisioningStore.java`,
);
const claimPort = read(
  `${applicationRoot}application/port/ApprovalMigrationAttemptClaimStore.java`,
);
const claimJdbc = read(
  `${jdbcRoot}persistence/jdbc/JdbcApprovalMigrationAttemptClaimStore.java`,
);
const provisioningJdbc = read(
  `${jdbcRoot}persistence/jdbc/JdbcApprovalMigrationAttemptProvisioningStore.java`,
);
const instanceFenceJdbc = read(
  `${jdbcRoot}persistence/jdbc/JdbcApprovalInstanceCommandFence.java`,
);
const migration = read(
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/'
    + 'V40__create_migration_command_fence_and_claim.sql',
);
const productionWiring = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
    + 'ApprovalRuntimeBindingEvidenceConfiguration.java',
);
const applicationConfig = read('apps/server/src/main/resources/application.yml');

function recordBody(source, name) {
  const match = source.match(new RegExp(`public record ${name}\\(([\\s\\S]*?)\\) \\{`));
  assert.ok(match, `${name} record was not found`);
  return match[1];
}

test('D2 shares one tenant and instance command serialization boundary', () => {
  for (const operation of [
    'COMPLETE',
    'APPROVE',
    'REJECT',
    'RETURN',
    'WITHDRAW',
    'RETRIEVE',
    'TRANSFER',
    'TERMINATE',
    'MIGRATION',
  ]) {
    assert.match(operations, new RegExp(`\\b${operation}\\b`));
  }
  assert.match(productionWiring, /new CommandFencedApprovalProjectionStore/);
  assert.match(projectionFence, /fence\.guardBusinessCommand/);
  assert.match(instanceFenceJdbc, /approval-instance-command:v1:/);
  assert.match(instanceFenceJdbc, /pg_advisory_xact_lock/);
  assert.match(claimJdbc, /instanceFences\.acquireMigrationLock/);
  assert.match(claimJdbc, /ap_approval_instance_command_fence/);
});

test('D2 provisions sealed selections before a deterministic bounded claim', () => {
  const provisionPosition = claimService.indexOf('provisioning.ensureInitialAttempts');
  const claimPosition = claimService.indexOf('claims.claim');
  assert.ok(provisionPosition >= 0 && claimPosition > provisionPosition);
  assert.match(provisioningJdbc, /status='CONSUMED'/);
  assert.match(provisioningJdbc, /ap_process_migration_plan_instance/);
  assert.match(provisioningJdbc, /ap_process_runtime_binding/);
  assert.match(provisioningJdbc, /instance_status/);
  assert.match(claimJdbc, /order by created_at,attempt_id/);
  assert.match(claimJdbc, /limit :limit/);
  assert.match(claimJdbc, /for update skip locked/);
  assert.match(migration, /idx_process_migration_attempt_claim_v40/);
  assert.match(migration, /tenant_id,intent_id,status,lease_until,created_at,attempt_id/);
  assert.doesNotMatch(provisioningJdbc, /org\.flowable|ProcessMigrationService|\.migrate\(/);
  assert.doesNotMatch(claimJdbc, /org\.flowable|ProcessMigrationService|\.migrate\(/);
});

test('D2 keeps worker identity and leases server owned and stale owner fenced', () => {
  assert.match(claimService, /workerIdentity\.get\(\)/);
  assert.doesNotMatch(recordBody(claimService, 'ClaimCommand'), /worker/i);
  assert.doesNotMatch(recordBody(claimService, 'RenewalCommand'), /worker/i);
  assert.match(claimService, /Duration\.ofMinutes\(15\)/);
  assert.match(fenceDomain, /same-owner renewal requires current ownership and lease extension/);
  assert.match(fenceDomain, /lease takeover requires expiry/);
  assert.match(claimJdbc, /current\.renewed\(request\.workerId\(\)/);
  assert.match(claimJdbc, /migration attempt lease ownership is stale/);
  assert.match(migration, /same-owner fence renewal requires current ownership and extension/);
  assert.match(migration, /command fence takeover requires expiry/);
  assert.match(migration, /where status='ACTIVE'/);
  assert.match(claimPort, /replayedExistingClaim/);
  assert.match(provisioningPort, /replayedExistingProvisioning/);
});

test('D2 remains one-shot internal default-disabled infrastructure', () => {
  assert.match(applicationConfig, /APPROVAL_MIGRATION_EXECUTION_ENABLED:false/);
  assert.match(applicationConfig, /APPROVAL_MIGRATION_WORKER_ENABLED:false/);
  assert.match(applicationConfig, /APPROVAL_MIGRATION_RECONCILIATION_AUTOMATIC_ENABLED:false/);
  assert.match(runner, /migration worker is disabled/);
  assert.doesNotMatch(runner, /@Scheduled|TaskScheduler|ScheduledExecutorService/);

  const internalSurface = [
    claimService,
    runner,
    projectionFence,
    provisioningPort,
    claimPort,
    provisioningJdbc,
    claimJdbc,
    instanceFenceJdbc,
  ].join('\n');
  assert.doesNotMatch(
    internalSurface,
    /@RestController|@Controller|@RequestMapping|@PostMapping/,
  );
  assert.doesNotMatch(internalSurface, /forceSuccess|fakeRollback|ignoreValidation|skipAudit/);
  assert.doesNotMatch(internalSurface, /ACT_[A-Z_]+/);
});
