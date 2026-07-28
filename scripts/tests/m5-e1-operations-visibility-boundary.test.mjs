import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();
const read = path => readFileSync(join(root, path), 'utf8');

const controller = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
    + 'ApprovalMigrationOperationsController.java',
);
const permission = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
    + 'ApprovalManagementPermission.java',
);
const resolver = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/security/'
    + 'DefaultApprovalResponsibilityResolver.java',
);
const queryPort = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/port/'
    + 'ApprovalMigrationOperationsQuery.java',
);
const jdbc = read(
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/'
    + 'JdbcApprovalMigrationOperationsQuery.java',
);
const webApi = read(
  'apps/web/overlay/apps/web-ele/src/api/approval/process-instance-operations.ts',
);
const webView = read(
  'apps/web/overlay/apps/web-ele/src/views/approval/process-instance-operations/index.vue',
);
const webRoute = read(
  'apps/web/overlay/apps/web-ele/src/router/routes/modules/'
    + 'approval-process-instance-operations.ts',
);
const mobileApi = read(
  'apps/mobile/overlay/src/api/approval/process-instance-operations.ts',
);
const mobileView = read(
  'apps/mobile/overlay/src/pages/operations/migrations.vue',
);
const workflow = read('.github/workflows/approval-platform-validation.yml');

const production = [
  controller,
  permission,
  resolver,
  queryPort,
  jdbc,
  webApi,
  webView,
  webRoute,
  mobileApi,
  mobileView,
].join('\n');

test('E1 exposes exactly four tenant-scoped GET operations handlers', () => {
  assert.match(
    controller,
    /"\/api\/approval\/management\/process-instance-operations"/,
  );
  assert.match(
    controller,
    /"\/api\/approval\/mobile\/process-instance-operations"/,
  );
  assert.equal((controller.match(/@GetMapping/g) ?? []).length, 4);
  assert.doesNotMatch(controller, /@(?:Post|Put|Patch|Delete)Mapping/);
  assert.match(controller, /MIGRATION_OPERATIONS_READ/);
  assert.match(controller, /ResourceScope\.TENANT/);
  assert.match(controller, /@RequestHeader\(TENANT_ID\)/);
  assert.doesNotMatch(controller, /@RequestBody/);
});

test('E1 capability is dedicated read-only and excludes unrelated roles', () => {
  const capability = permission.match(
    /MIGRATION_OPERATIONS_READ\(([\s\S]*?)\),\s*TRANSFER/,
  );
  assert.ok(capability, 'migration operations capability is missing');
  assert.match(
    capability[1],
    /"approval\.management\.migration\.operations\.read",\s*"migration-operations-read"/s,
  );
  assert.doesNotMatch(capability[1], /\btrue\b/);
  for (const role of ['PROCESS_PUBLISHER', 'AUDITOR', 'OPERATIONS']) {
    const block = resolver.match(new RegExp(
      `ApprovalEnterpriseRole\\.${role},[\\s\\S]*?\\n\\s*\\)`,
    ));
    assert.ok(block, `missing ${role} capability block`);
    assert.match(block[0], /MIGRATION_OPERATIONS_READ/);
  }
  assert.match(resolver, /ApprovalEnterpriseRole\.PARTICIPANT, Set\.of\(\)/);
});

test('E1 reads bounded durable evidence without mutation or Flowable access', () => {
  assert.match(queryPort, /MAX_PAGE_SIZE = 200/);
  assert.match(queryPort, /OperationsSummary summarize/);
  assert.match(queryPort, /PlanPage findPlans/);
  assert.match(queryPort, /Optional<PlanDetail> findPlan/);
  assert.match(queryPort, /InstancePage findInstances/);
  assert.match(queryPort, /unresolvedCount = selectedInstanceCount/);
  assert.match(queryPort, /plan without aggregate revision cannot expose aggregate evidence/);
  assert.match(jdbc, /order by plan\.created_at desc,plan\.plan_id desc/);
  assert.match(jdbc, /order by selection\.sequence_no/);
  assert.match(jdbc, /ap_process_migration_plan_aggregate/);
  assert.match(jdbc, /ap_process_migration_instance_completion/);
  assert.match(jdbc, /ap_process_migration_reconciliation_observation/);
  assert.doesNotMatch(jdbc, /jdbc\.update\s*\(/);
  assert.doesNotMatch(jdbc, /\b(?:insert into|update ap_|delete from)\b/i);
  assert.doesNotMatch(jdbc, /ProcessMigrationService|ProcessInstanceMigrationBuilder/);
  assert.doesNotMatch(jdbc, /\bACT_[A-Z0-9_]+\b/);
  assert.doesNotMatch(jdbc, /payload_json::text|operation_reason|reason\s+from/i);
});

test('Web and Mobile visibility clients remain GET-only and command-free', () => {
  assert.match(webApi, /\/approval\/management\/process-instance-operations\/summary/);
  assert.match(mobileApi, /\/approval\/mobile\/process-instance-operations\/summary/);
  assert.doesNotMatch(mobileApi, /\/approval\/management\//);
  for (const client of [webApi, mobileApi]) {
    assert.match(client, /process-instance-operations\/plans/);
    assert.doesNotMatch(client, /approvalCommandHeaders|Idempotency-Key/);
    assert.doesNotMatch(client, /method:\s*['"](?:POST|PUT|PATCH|DELETE)['"]/i);
    assert.doesNotMatch(client, /execute|retry|rollback|forceSuccess|reconcile/i);
  }
  assert.match(webRoute, /authority: \['approval:ops:view'\]/);
  for (const view of [webView, mobileView]) {
    assert.match(view, /只读/);
    assert.doesNotMatch(
      view,
      /@click="[^"]*(?:execute|retry|rollback|force|reconcile|cancel)[^"]*"/i,
    );
  }
});

test('Web and Mobile never silently truncate bounded operations evidence', () => {
  assert.match(webView, /instancePageChanged/);
  assert.match(webView, /:total="instancePage\.total"/);
  assert.match(webView, /instancePage\.offset \+ 1/);
  assert.match(mobileApi, /function boundedPaging\(limit: number, offset: number\)/);
  assert.match(mobileView, /loadMorePlans/);
  assert.match(mobileView, /plans\.length }} \/ {{ page\.total/);
  assert.match(mobileView, /loadMoreInstances/);
  assert.match(mobileView, /instances\.length }} \/ {{ instancePage\.total/);
});

test('E1 adds no Flyway version M6 ownership or second workflow', () => {
  assert.doesNotMatch(production, /V49__|\bM6[-_A-Z0-9]*\b/);
  assert.equal((workflow.match(/pull_request:/g) ?? []).length, 1);
  assert.equal((workflow.match(/push:/g) ?? []).length, 1);
  assert.doesNotMatch(workflow, /m5-e1.*workflow/i);
});