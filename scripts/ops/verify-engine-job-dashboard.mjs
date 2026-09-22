import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';

export const engineDashboardPath = 'deploy/observability/grafana/approval-engine-jobs.json';
export const overviewPath = 'deploy/observability/grafana/approval-operations.json';
export const engineRulePath = 'deploy/observability/prometheus/approval-engine-jobs.rules.yml';
export const engineQueues = Object.freeze(['executable', 'timer', 'suspended', 'dead_letter']);
export const engineHealthRecord = 'approval:engine_job_sample_healthy';
const alerts = ['ApprovalEngineJobDeadLetters', 'ApprovalEngineJobMonitoringUnavailable'];
const selector = 'job="approval-platform",environment=~"${environment:regex}",instance=~"${instance:regex}",engine_job_monitor="enabled"';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const healthExpression = `(${engineHealthRecord}{${selector}} == 1) and on (job, instance, environment) (up{${selector}} == 1)`;
const queueExpression = queue => {
  const vector = `approval_engine_jobs{${selector},queue="${queue}"}`;
  return `(${vector} >= 0) and (${vector} < +Inf) and ignoring (queue) (${healthExpression})`;
};
const resolveExpression = expr => expr.replaceAll('${environment:regex}', 'test').replaceAll('${instance:regex}', 'node-a');

/** Validate the shipped JSON, not a separately generated mock dashboard. */
export function engineDashboardQueries(document, overview) {
  assert.equal(document.uid, 'approval-engine-jobs');
  assert.equal(overview.uid, 'approval-operations');
  assert.deepEqual(document.templating, overview.templating);
  assert.deepEqual(document.templating.list.map(v => v.name), ['datasource', 'environment', 'instance']);
  assert.equal(document.templating.list[0].query, 'prometheus');
  for (const variable of document.templating.list.slice(1)) {
    assert.equal(variable.multi, false); assert.equal(variable.includeAll, false);
  }
  for (const [dashboard, destination] of [[document, 'approval-operations'], [overview, 'approval-engine-jobs']]) {
    const links = dashboard.links.filter(link => link.url === '/d/' + destination);
    assert.equal(links.length, 1, 'ENGINE_DASHBOARD_NAVIGATION');
    assert.equal(links[0].includeVars, true); assert.equal(links[0].keepTime, true);
  }
  const expected = [
    [2, 'stat', [`up{${selector}}`]], [3, 'stat', [healthExpression]],
    ...engineQueues.map((queue, index) => [index + 5, 'stat', [queueExpression(queue)]]),
    [10, 'timeseries', engineQueues.map(queueExpression)],
    [12, 'table', [`ALERTS{${selector},alertstate="firing",alertname=~"${alerts.join('|')}"}`]],
  ];
  const panels = document.panels.filter(panel => panel.type !== 'row');
  assert.deepEqual(panels.map(p => p.id), expected.map(([id]) => id), 'ENGINE_DASHBOARD_PANEL_INVENTORY');
  assert.equal(new Set(document.panels.map(p => p.id)).size, document.panels.length);
  for (let i = 0; i < document.panels.length; i++) {
    const a = document.panels[i].gridPos;
    assert.ok([a.x, a.y, a.w, a.h].every(Number.isInteger));
    assert.ok(a.x >= 0 && a.y >= 0 && a.w > 0 && a.h > 0 && a.x + a.w <= 24);
    for (const other of document.panels.slice(i + 1)) {
      const b = other.gridPos;
      assert.ok(a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y,
        'ENGINE_DASHBOARD_PANEL_OVERLAP');
    }
  }
  const queries = [];
  for (const [index, panel] of panels.entries()) {
    const [id, type, expressions] = expected[index];
    assert.equal(panel.type, type); assert.deepEqual(panel.datasource, { type: 'prometheus', uid: '$datasource' });
    assert.equal(panel.fieldConfig.defaults.noValue, '未知', 'ENGINE_DASHBOARD_UNKNOWN_NOT_ZERO');
    assert.equal(panel.fieldConfig.defaults.unit, 'short');
    assert.deepEqual(panel.targets.map(t => t.expr), expressions, 'ENGINE_DASHBOARD_QUERY_CONTRACT');
    assert.equal(new Set(panel.targets.map(t => t.refId)).size, panel.targets.length);
    if (type === 'stat') assert.deepEqual(panel.options.reduceOptions.calcs, ['last']);
    if (type === 'timeseries') {
      assert.equal(panel.fieldConfig.defaults.custom.spanNulls, false);
      assert.equal(panel.fieldConfig.defaults.custom.stacking.mode, 'none');
    }
    for (const target of panel.targets) {
      assert.equal(target.instant, type !== 'timeseries'); assert.equal(target.range, type === 'timeseries');
      assert.equal(target.format, type === 'table' ? 'table' : 'time_series');
      const expression = resolveExpression(target.expr);
      assert.ok(!expression.includes('$'), 'ENGINE_DASHBOARD_UNRESOLVED_VARIABLE');
      queries.push({ id, refId: target.refId, expression });
    }
  }
  assert.equal(queries.length, 11);
  return queries;
}

const common = { job: 'approval-platform', environment: 'test', instance: 'node-a', engine_job_monitor: 'enabled' };
const metricLabels = { ...common, application: 'approval-platform' };
const labels = (name, values) => name + '{' + Object.entries(values)
  .sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => `${k}=${JSON.stringify(v)}`).join(',') + '}';
const samples = (name, tags, value) => value === null ? [] : [{ labels: labels(name, tags), value }];
const repeated = value => Array(7).fill(String(value)).join(' ');
function input(overrides = {}, instance = 'node-a', environment = 'test', enabled = true) {
  const tags = { ...common, instance, environment };
  if (!enabled) delete tags.engine_job_monitor;
  const app = { ...tags, application: 'approval-platform' };
  const values = { up: 1, sample: 1, executable: 3, timer: 5, suspended: 2, dead_letter: 1, ...overrides };
  return Object.entries(values).filter(([, value]) => value !== null).map(([key, value]) => ({
    series: labels(key === 'up' ? 'up' : key === 'sample' ? 'approval_engine_jobs_sample_up' : 'approval_engine_jobs',
      key === 'up' ? tags : key === 'sample' ? app : { ...app, queue: key }),
    values: Array.isArray(value) ? value.join(' ') : repeated(value),
  }));
}

/** Exact labels/values from the actual 11 query strings; no sum that can hide duplicates. */
export function engineDashboardFixtures(ruleFile, queryFile, ruleDocument, queries) {
  const group = ruleDocument.groups[0];
  assert.equal(group.name, 'approval-platform-engine-jobs');
  assert.deepEqual(group.rules.map(r => r.record || r.alert), [engineHealthRecord, ...alerts]);
  assert.deepEqual(group.rules.slice(1).map(r => r.for), ['2m', '2m']);
  const tests = [];
  const defaults = [3, 5, 2, 1];
  function scenario(name, series, checkpoints) {
    const checks = [];
    for (const { time = '1m', up = 1, healthy = true, counts = defaults, firing = [] } of checkpoints) {
      for (const query of queries) {
        let expected;
        if (query.id === 2) expected = samples('up', common, up);
        else if (query.id === 3) expected = healthy ? samples(engineHealthRecord, metricLabels, 1) : [];
        else if (query.id === 12) expected = firing.flatMap(index => samples('ALERTS', {
          ...(index === 0 ? { ...metricLabels, queue: 'dead_letter' } : common),
          ...group.rules[index + 1].labels, alertname: alerts[index], alertstate: 'firing',
        }, 1));
        else {
          const index = query.id === 10 ? query.refId.charCodeAt(0) - 65 : query.id - 5;
          expected = healthy ? samples('approval_engine_jobs', { ...metricLabels, queue: engineQueues[index] }, counts[index]) : [];
        }
        checks.push({ expr: query.expression, eval_time: time, exp_samples: expected });
      }
    }
    // Other complete targets must never supply values/labels for the selected target.
    tests.push({ name, interval: '1m', input_series: [...series, ...input({ executable: 99 }, 'node-b'),
      ...input({ executable: 77 }, 'node-a', 'another-environment')], promql_expr_test: checks });
  }
  scenario('selected replica retains exact queue labels before and after the dead-letter hold', input(),
    [{}, { time: '2m', firing: [0] }]);
  scenario('fresh empty queues remain genuine zero', input(Object.fromEntries(engineQueues.map(q => [q, 0]))),
    [{ counts: [0, 0, 0, 0] }, { time: '3m', counts: [0, 0, 0, 0] }]);
  scenario('dead-letter resolution clears only after actual healthy zero', input({ dead_letter: [1, 1, 1, 0, 0, 0, 0] }),
    [{ time: '2m', firing: [0] }, { time: '3m', counts: [3, 5, 2, 0] }]);
  scenario('failed sampling is unavailable rather than queue recovery', input({ sample: 0 }),
    [{ healthy: false }, { time: '2m', healthy: false, firing: [1] }]);
  scenario('unreachable target does not borrow another instance health', input({ up: 0 }),
    [{ up: 0, healthy: false }, { time: '3m', up: 0, healthy: false }]);
  scenario('target removed from discovery is absent rather than zero', input({ up: null }),
    [{ up: null, healthy: false }, { time: '3m', up: null, healthy: false }]);
  scenario('disabled target has no expected monitor or quantities', input({}, 'node-a', 'test', false),
    [{ up: null, healthy: false }, { time: '3m', up: null, healthy: false }]);
  for (const key of ['sample', ...engineQueues]) {
    scenario('missing ' + key + ' invalidates the full sample', input({ [key]: null }),
      [{ healthy: false }, { time: '2m', healthy: false, firing: [1] }]);
    scenario('stale ' + key + ' breaks trends and starts the unavailable hold', input({ [key]: [1, 'stale', '_', '_', '_', '_', '_'] }),
      [{ time: '1m', healthy: false }, { time: '3m', healthy: false, firing: [1] }]);
    for (const bad of ['NaN', '+Inf', '-Inf', -1]) {
      scenario('invalid ' + key + '=' + bad + ' never looks like an empty queue', input({ [key]: bad }),
        [{ healthy: false }, { time: '2m', healthy: false, firing: [1] }]);
    }
  }
  const mismatch = input().map(series => series.series.includes('queue="timer"')
    ? { ...series, series: series.series.replace('application="approval-platform"', 'application="another-application"') } : series);
  scenario('mismatched application labels cannot combine into complete health', mismatch,
    [{ healthy: false }, { time: '2m', healthy: false, firing: [1] }]);
  const split = input().map(series => series.series.includes('queue="timer"') || series.series.includes('queue="suspended"')
    ? { ...series, series: series.series.replace('instance="node-a"', 'instance="node-c"') } : series);
  scenario('partial replicas cannot complete each other', split,
    [{ healthy: false }, { time: '2m', healthy: false, firing: [1] }]);
  scenario('sampling recovery restarts the original dead-letter hold', input({ sample: [0, 0, 0, 1, 1, 1, 1] }),
    [{ time: '2m', healthy: false, firing: [1] }, { time: '3m' }, { time: '4m' }, { time: '5m', firing: [0] }]);
  // Isolate missing/stale/zero recording-rule behavior from its producer using separate fixtures.
  const ordinaryRuleTests = tests.length;
  for (const [name, value] of [['missing', null], ['stale', [1, 'stale', '_', '_', '_', '_', '_']], ['zero', 0]]) {
    const series = [...input(), ...(value === null ? [] : [{ series: labels(engineHealthRecord, metricLabels),
      values: Array.isArray(value) ? value.join(' ') : repeated(value) }])];
    scenario(name + ' recording rule cannot reuse otherwise valid quantities', series, [{ healthy: false }]);
  }
  // Select the correct rule-file set per test document; promtool runs both without any mutation.
  return [
    { rule_files: [ruleFile, queryFile], group_eval_order: [group.name, 'engine-dashboard-query-syntax'],
      evaluation_interval: '1m', tests: tests.slice(0, ordinaryRuleTests) },
    { rule_files: [queryFile], evaluation_interval: '1m', tests: tests.slice(ordinaryRuleTests) },
  ];
}

/** Uses the existing digest/version-checked tool; no download, Docker, or workflow of its own. */
export function verifyEngineJobDashboard({ directory, repositoryRoot, promtool, runCommand }) {
  assert.ok(isAbsolute(promtool), 'ENGINE_DASHBOARD_TOOL_ABSOLUTE');
  const stat = lstatSync(promtool);
  assert.ok(stat.isFile() && !stat.isSymbolicLink() && realpathSync(promtool) === promtool, 'ENGINE_DASHBOARD_TOOL_FILE');
  const bytes = readFileSync(resolve(repositoryRoot, engineDashboardPath));
  const overviewBytes = readFileSync(resolve(repositoryRoot, overviewPath));
  const ruleFile = resolve(repositoryRoot, engineRulePath); const ruleBytes = readFileSync(ruleFile);
  const queries = engineDashboardQueries(JSON.parse(bytes), JSON.parse(overviewBytes));
  const queryFile = resolve(directory, 'engine-dashboard-queries.yml');
  writeFileSync(queryFile, JSON.stringify({ groups: [{ name: 'engine-dashboard-query-syntax', rules: queries.map(q => ({
    record: `approval:engine_dashboard_panel_${q.id}_${q.refId.toLowerCase()}`, expr: q.expression,
  })) }] }), { flag: 'wx', mode: 0o600 });
  const fixtures = engineDashboardFixtures(ruleFile, queryFile, JSON.parse(ruleBytes), queries);
  const fixtureFiles = fixtures.map((fixture, index) => {
    const file = resolve(directory, `engine-dashboard-${index}.test.yml`);
    writeFileSync(file, JSON.stringify(fixture), { flag: 'wx', mode: 0o600 }); return file;
  });
  runCommand(promtool, ['check', 'rules', queryFile], directory, 30000);
  runCommand(promtool, ['test', 'rules', ...fixtureFiles], directory, 60000);
  return { status: 'OPS_ENGINE_JOB_DASHBOARD_QUERIES_VERIFIED', dashboardSha256: hash(bytes),
    overviewSha256: hash(overviewBytes), ruleSha256: hash(ruleBytes), queryCount: queries.length,
    scenarios: fixtures.reduce((n, f) => n + f.tests.length, 0),
    assertions: fixtures.flatMap(f => f.tests).reduce((n, t) => n + t.promql_expr_test.length, 0),
    fixtures: fixtureFiles.map(file => ({ sha256: hash(readFileSync(file)) })),
    grafanaBrowserVerified: false, notificationDeliveryVerified: false, productionDeploymentVerified: false };
}
