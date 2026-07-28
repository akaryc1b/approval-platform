import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();
const read = path => readFileSync(join(root, path), 'utf8');

const controller = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
    + 'ApprovalMigrationDiagnosticsController.java',
);
const parameters = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
    + 'ApprovalMigrationDiagnosticsParameters.java',
);
const queryPort = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/port/'
    + 'ApprovalMigrationDiagnosticsQuery.java',
);
const jdbc = read(
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/'
    + 'JdbcApprovalMigrationDiagnosticsQuery.java',
);
const webApi = read(
  'apps/web/overlay/apps/web-ele/src/api/approval/process-instance-operations.ts',
);
const webView = read(
  'apps/web/overlay/apps/web-ele/src/views/approval/process-instance-operations/diagnostics.vue',
);
const webRoute = read(
  'apps/web/overlay/apps/web-ele/src/router/routes/modules/'
    + 'approval-process-instance-operations.ts',
);
const mobileApi = read(
  'apps/mobile/overlay/src/api/approval/process-instance-operations.ts',
);
const mobileView = read(
  'apps/mobile/overlay/src/pages/operations/migration-diagnostics.vue',
);
const mobileProfile = read('apps/mobile/overlay/src/pages/profile/index.vue');
const workflow = read('.github/workflows/approval-platform-validation.yml');

const production = [
  controller,
  parameters,
  queryPort,
  jdbc,
  webApi,
  webView,
  webRoute,
  mobileApi,
  mobileView,
  mobileProfile,
].join('\n');

test('E2 exposes exactly three tenant-scoped GET-only diagnostics handlers', () => {
  assert.equal((controller.match(/@GetMapping/g) ?? []).length, 3);
  assert.doesNotMatch(controller, /@(?:Post|Put|Patch|Delete)Mapping/);
  assert.doesNotMatch(controller, /@RequestBody/);
  assert.match(controller, /MIGRATION_OPERATIONS_READ/);
  assert.match(controller, /ResourceScope\.TENANT/);
  assert.match(controller, /@RequestHeader\(TENANT_ID\)/);
  assert.match(controller, /\/plans\/\{planId\}\/diagnostics/);
  assert.match(controller, /\/diagnostics\/instances/);
  assert.match(controller, /\/instances\/\{instanceId\}\/diagnostics/);
});

test('E2 query parameters are closed bounded and duplicate rejecting', () => {
  for (const name of [
    'page',
    'pageSize',
    'sort',
    'status',
    'instanceId',
    'from',
    'to',
    'failureClass',
    'reconciliationState',
  ]) {
    assert.match(parameters, new RegExp(`"${name}"`));
  }
  assert.match(parameters, /entry\.getValue\(\)\.size\(\) != 1/);
  assert.match(parameters, /MAX_PARAMETER_LENGTH = 128/);
  assert.match(parameters, /explicit ISO-8601 offset/);
  assert.match(queryPort, /MAX_PAGE_SIZE = 100/);
  assert.match(queryPort, /MAX_PAGE = 10_000/);
  assert.match(queryPort, /Duration\.ofDays\(31\)/);
  assert.match(jdbc, /switch \(criteria\.sort\(\)\)/);
  assert.doesNotMatch(jdbc, /order by ["']?\s*\+\s*criteria\.sort/i);
});

test('E2 reads only platform evidence and redacts ownership', () => {
  for (const table of [
    'ap_process_migration_plan_aggregate',
    'ap_process_migration_canary_selection',
    'ap_process_migration_orchestration_run',
    'ap_process_migration_kill_switch_observation',
    'ap_process_migration_engine_outcome',
    'ap_process_migration_exact_verification',
    'ap_process_migration_reconciliation',
    'ap_process_migration_binding_cas_conflict',
    'ap_process_migration_instance_completion',
  ]) {
    assert.match(jdbc, new RegExp(table));
  }
  assert.match(queryPort, /leaseOwnerReference/);
  assert.match(queryPort, /fencingOwnerReference/);
  assert.match(jdbc, /MessageDigest\.getInstance\("SHA-256"\)/);
  assert.match(jdbc, /"sha256:"/);
  assert.doesNotMatch(jdbc, /jdbc\.update\s*\(/);
  assert.doesNotMatch(jdbc, /\b(?:insert into|update ap_|delete from)\b/i);
  assert.doesNotMatch(jdbc, /ProcessMigrationService|ProcessInstanceMigrationBuilder/);
  assert.doesNotMatch(jdbc, /\bACT_[A-Z0-9_]+\b/);
  assert.doesNotMatch(jdbc, /payload_json::text|bounded_summary/i);
});

test('E2 web and mobile diagnostics remain read-only and bounded', () => {
  for (const api of [webApi, mobileApi]) {
    assert.match(api, /\/diagnostics/);
    assert.match(api, /pageSize/);
    assert.doesNotMatch(api, /approvalCommandHeaders|Idempotency-Key/);
    assert.doesNotMatch(api, /method:\s*['"](?:POST|PUT|PATCH|DELETE)['"]/i);
  }
  assert.match(webRoute, /ApprovalProcessInstanceDiagnostics/);
  assert.match(webRoute, /authority: \['approval:ops:view'\]/);
  assert.match(webView, /Failure Class/);
  assert.match(webView, /Kill Switch/);
  assert.match(webView, /实例生命周期/);
  assert.match(mobileView, /instance-card/);
  assert.doesNotMatch(mobileView, /<table|ElTable/i);
  assert.match(mobileProfile, /openMigrationDiagnostics/);
  for (const view of [webView, mobileView]) {
    assert.match(view, /只读/);
    assert.doesNotMatch(
      view,
      /@click="[^"]*(?:execute|retry|rollback|force|startReconciliation|cancel)[^"]*"/i,
    );
  }
});

test('E2 creates no migration version M6 coupling or second automatic workflow', () => {
  assert.doesNotMatch(production, /V49__|\bM6[-_A-Z0-9]*\b/);
  assert.equal((workflow.match(/pull_request:/g) ?? []).length, 1);
  assert.equal((workflow.match(/push:/g) ?? []).length, 1);
  assert.doesNotMatch(workflow, /m5-e2.*workflow/i);
});
