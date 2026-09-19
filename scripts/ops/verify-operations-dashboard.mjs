import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

export const operationsDashboardPath = 'deploy/observability/grafana/approval-operations.json';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
export function resolveDashboardExpression(expression) {
  const result = expression.replaceAll('${environment:regex}', 'test')
    .replaceAll('${instance:regex}', 'node-a').replaceAll('$__rate_interval', '5m');
  assert.ok(!result.includes('$'), 'DASHBOARD_UNRESOLVED_VARIABLE');
  return result;
}
export function operationsDashboardQueries(document) {
  assert.equal(document.uid, 'approval-operations');
  const variables = document.templating.list;
  assert.deepEqual(variables.map(value => value.name), ['datasource', 'environment', 'instance']);
  assert.equal(variables[0].type, 'datasource'); assert.equal(variables[0].query, 'prometheus');
  for (const variable of variables.slice(1)) {
    assert.equal(variable.multi, false, 'DASHBOARD_SINGLE_INSTANCE_REQUIRED');
    assert.equal(variable.includeAll, false, 'DASHBOARD_ALL_REPLICAS_FORBIDDEN');
    assert.ok(variable.query.includes('job="approval-platform"'));
  }
  assert.equal(new Set(document.panels.map(panel => panel.id)).size, document.panels.length);
  const queries = [];
  for (const panel of document.panels.filter(panel => panel.type !== 'row')) {
    assert.equal(panel.datasource.type, 'prometheus'); assert.equal(panel.datasource.uid, '$datasource');
    assert.equal(panel.fieldConfig.defaults.noValue, '未知', 'DASHBOARD_UNKNOWN_NOT_ZERO');
    assert.equal(panel.targets.length, 1);
    const expression = panel.targets[0].expr;
    assert.ok(expression.includes('job="approval-platform"'));
    assert.ok(expression.includes('environment=~"${environment:regex}"'));
    assert.ok(expression.includes('instance=~"${instance:regex}"'));
    assert.doesNotMatch(expression, /tenant|user_id|task_id|instance_id|trace_id|business_key|payload/u);
    assert.doesNotMatch(expression, /\bor\s+vector\s*\(\s*0\s*\)/u);
    if (panel.id >= 7 && panel.id <= 12) {
      assert.ok(expression.includes('and approval:workflow_population_sample_healthy'));
      assert.doesNotMatch(expression, /sum\s*\(/u);
    }
    if (panel.id >= 18 && panel.id <= 25) {
      for (const part of ['< +Inf', '>= 0', 'approval_outbox_sample_up', 'up{']) {
        assert.ok(expression.includes(part), 'DASHBOARD_OUTBOX_GUARD:' + part);
      }
      assert.doesNotMatch(expression, /sum\s*\(/u);
    }
    if ([13, 14, 15].includes(panel.id)) {
      assert.equal(panel.fieldConfig.defaults.unit, 's');
      assert.ok(expression.includes('histogram_quantile(0.95'));
      assert.ok(expression.includes('rate(') && expression.includes('[$__rate_interval]'));
    }
    queries.push({ id: panel.id, expression: resolveDashboardExpression(expression) });
  }
  assert.equal(queries.length, 25);
  return queries;
}

export function dashboardQueryFixtures(ruleFile, dashboardQueryFile, queries) {
  const names = ['approval_workflow_sample_up', 'approval_process_active', 'approval_process_sla_covered',
    'approval_process_overdue', 'approval_task_active', 'approval_task_sla_covered', 'approval_task_overdue',
    'approval_outbox_sample_up', 'approval_outbox_pending', 'approval_outbox_in_flight', 'approval_outbox_dead',
    'approval_outbox_oldest_unfinished_age_seconds', 'approval_notification_outbox_pending',
    'approval_notification_outbox_in_flight', 'approval_notification_outbox_dead',
    'approval_notification_outbox_oldest_unfinished_age_seconds'];
  const defaults = Object.fromEntries(names.map((name, i) => [name, [1, 4, 3, 1, 8, 6, 2, 1, 12, 3, 1, 500, 5, 1, 1, 300][i]]));
  // Six explicit samples preserve x5 timing, including non-finite values that cannot use expansion.
  function series(overrides = {}, instance = 'node-a') {
    return Object.entries({ up: 1, ...defaults, ...overrides }).filter(([, value]) => value !== null)
      .map(([name, value]) => ({ series: name + `{job="approval-platform",instance="${instance}",environment="test",workflow_monitor="enabled",outbox_monitor="enabled"`
        + (name === 'up' ? '}' : ',application="approval-platform"}'), values: Array(6).fill(String(value)).join(' ') }));
  }
  const cases = [];
  const quantityIds = [...Array.from({ length: 6 }, (_, i) => i + 7), ...Array.from({ length: 8 }, (_, i) => i + 18)];
  function scenario(name, overrides, expected) {
    cases.push({ name, interval: '1m', input_series: [...series(overrides), ...series({ approval_process_active: 99 }, 'node-b')],
      promql_expr_test: Object.entries(expected).map(([id, value]) => ({
        expr: `sum(${queries.find(query => query.id === Number(id)).expression})`, eval_time: '1m',
        exp_samples: value === null ? [] : [{ labels: '{}', value }],
      })) });
  }
  const missingQuantities = Object.fromEntries(quantityIds.map(id => [id, null]));
  scenario('single selected replica preserves exact snapshots', {},
    { 7: 4, 8: 3, 9: 1, 10: 8, 11: 6, 12: 2, 18: 12, 19: 3, 20: 1, 21: 500, 22: 5, 23: 1, 24: 1, 25: 300 });
  scenario('failed monitors cannot reuse old numeric snapshots', { approval_workflow_sample_up: 0, approval_outbox_sample_up: 0 }, missingQuantities);
  scenario('unreachable selected target cannot borrow healthy replica data', { up: 0 }, missingQuantities);
  scenario('one missing workflow gauge invalidates all population panels', { approval_task_sla_covered: null },
    { 7: null, 8: null, 9: null, 10: null, 11: null, 12: null, 18: 12 });
  scenario('invalid outbox dead observations are not zeros', { approval_outbox_dead: 'NaN', approval_notification_outbox_dead: '+Inf' }, { 20: null, 24: null });
  scenario('genuine fresh empty population is zero not absent', Object.fromEntries(names.filter(name => !name.endsWith('sample_up')).map(name => [name, 0])),
    Object.fromEntries(quantityIds.map(id => [id, 0])));
  return { rule_files: [ruleFile, dashboardQueryFile], evaluation_interval: '1m', tests: cases };
}

/** JSON invariants locally; query syntax and snapshot safety executed by the real pinned promtool. */
export function verifyOperationsDashboard({ directory, repositoryRoot, promtool, runCommand }) {
  const bytes = readFileSync(resolve(repositoryRoot, operationsDashboardPath));
  const queries = operationsDashboardQueries(JSON.parse(bytes));
  const queryFile = resolve(directory, 'operations-dashboard-queries.yml');
  const rules = { groups: [{ name: 'operations-dashboard-query-syntax', rules: queries.map(query => ({
    record: `approval:dashboard_validation_panel_${query.id}`, expr: query.expression,
  })) }] };
  writeFileSync(queryFile, JSON.stringify(rules), { flag: 'wx', mode: 0o600 });
  const ruleFile = resolve(repositoryRoot, 'deploy/observability/prometheus/approval-workflow-population.rules.yml');
  const fixture = dashboardQueryFixtures(ruleFile, queryFile, queries);
  const fixtureFile = resolve(directory, 'operations-dashboard.test.yml');
  writeFileSync(fixtureFile, JSON.stringify(fixture), { flag: 'wx', mode: 0o600 });
  runCommand(promtool, ['check', 'rules', queryFile], directory, 30000);
  runCommand(promtool, ['test', 'rules', fixtureFile], directory, 60000);
  return { status: 'OPS_OPERATIONS_DASHBOARD_QUERIES_VERIFIED', dashboardSha256: hash(bytes),
    queryCount: queries.length, scenarios: fixture.tests.length,
    assertions: fixture.tests.reduce((n, test) => n + test.promql_expr_test.length, 0),
    grafanaBrowserVerified: false, productionDeploymentVerified: false };
}
