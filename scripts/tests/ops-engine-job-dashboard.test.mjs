import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { engineDashboardPath, overviewPath, engineRulePath, engineHealthRecord, engineQueues,
  engineDashboardQueries, engineDashboardFixtures, verifyEngineJobDashboard } from '../ops/verify-engine-job-dashboard.mjs';

const root = fileURLToPath(new URL('../..', import.meta.url));
const bytes = readFileSync(resolve(root, engineDashboardPath));
const document = JSON.parse(bytes); const overview = JSON.parse(readFileSync(resolve(root, overviewPath)));
const ruleFile = resolve(root, engineRulePath); const ruleBytes = readFileSync(ruleFile); const rules = JSON.parse(ruleBytes);
const queries = engineDashboardQueries(document, overview);
const fixtures = engineDashboardFixtures(ruleFile, 'engine-dashboard-queries.yml', rules, queries);
const allCases = fixtures.flatMap(f => f.tests);
const hash = value => createHash('sha256').update(value).digest('hex');
const text = path => readFileSync(resolve(root, path), 'utf8');
const scenario = name => { const value = allCases.find(t => t.name === name); assert.ok(value, name); return value; };

test('shipped companion has eight panels and eleven actual scoped queries', () => {
  assert.equal(queries.length, 11); assert.equal(document.panels.filter(p => p.type !== 'row').length, 8);
  assert.equal(overview.panels.flatMap(p => p.targets || []).length, 25);
  assert.deepEqual(document.templating, overview.templating);
  assert.deepEqual(document.links.map(link => link.url), ['/d/approval-operations']);
  assert.deepEqual(overview.links.map(link => link.url), ['/d/approval-engine-jobs']);
});
test('queue snapshots and trend queries are identical and never stacked or forward-filled', () => {
  for (const [index, queue] of engineQueues.entries()) {
    const stat = queries.find(q => q.id === index + 5);
    const trend = queries.find(q => q.id === 10 && q.refId === String.fromCharCode(65 + index));
    assert.equal(stat.expression, trend.expression); assert.ok(stat.expression.includes(`queue="${queue}"`));
    assert.match(stat.expression, /and ignoring \(queue\)/u);
    assert.ok(stat.expression.includes(engineHealthRecord));
    assert.doesNotMatch(stat.expression, /sum\s*\(|or\s+vector\(0\)/u);
  }
});
for (const [name, change] of [
  ['missing queue panel', d => { d.panels.splice(d.panels.findIndex(p => p.id === 6), 1); }],
  ['duplicate panel id', d => { d.panels[0].id = 2; }],
  ['panel overlap', d => { d.panels.find(p => p.id === 3).gridPos.x = 0; }],
  ['multi-instance selection', d => { d.templating.list[2].multi = true; }],
  ['all environments', d => { d.templating.list[1].includeAll = true; }],
  ['lost navigation variables', d => { d.links[0].includeVars = false; }],
  ['unknown as zero', d => { d.panels.find(p => p.id === 5).fieldConfig.defaults.noValue = '0'; }],
  ['last non-null hides current absence', d => { d.panels.find(p => p.id === 5).options.reduceOptions.calcs = ['lastNotNull']; }],
  ['stacked queues', d => { d.panels.find(p => p.id === 10).fieldConfig.defaults.custom.stacking.mode = 'normal'; }],
  ['bridged missing data', d => { d.panels.find(p => p.id === 10).fieldConfig.defaults.custom.spanNulls = true; }],
  ['removed health guard', d => { const p = d.panels.find(p => p.id === 5); p.targets[0].expr = p.targets[0].expr.split(' and ignoring')[0]; }],
  ['widened replica match', d => { const p = d.panels.find(p => p.id === 5); p.targets[0].expr = p.targets[0].expr.replace('ignoring (queue)', 'on (job)'); }],
  ['range query used as current count', d => { d.panels.find(p => p.id === 5).targets[0].instant = false; }],
  ['removed infinity guard', d => { const p = d.panels.find(p => p.id === 5); p.targets[0].expr = p.targets[0].expr.replace('< +Inf', '!= 0'); }],
]) {
  test('reject dashboard regression: ' + name, () => {
    const changed = structuredClone(document); change(changed);
    assert.throws(() => engineDashboardQueries(changed, overview));
  });
}
test('native fixtures use the actual submitted queries and exact label expectations without sum', () => {
  assert.equal(allCases.length, 43); assert.equal(new Set(allCases.map(c => c.name)).size, 43);
  assert.equal(allCases.reduce((n, c) => n + c.promql_expr_test.length, 0), 935);
  assert.deepEqual(fixtures[0].rule_files, [ruleFile, 'engine-dashboard-queries.yml']);
  assert.deepEqual(fixtures[1].rule_files, ['engine-dashboard-queries.yml']);
  for (const c of allCases) {
    assert.equal(new Set(c.input_series.map(s => s.series)).size, c.input_series.length);
    assert.ok(c.input_series.some(s => s.series.includes('instance="node-b"')));
    assert.ok(c.input_series.some(s => s.series.includes('environment="another-environment"')));
    for (const time of new Set(c.promql_expr_test.map(check => check.eval_time))) {
      assert.deepEqual(c.promql_expr_test.filter(check => check.eval_time === time).map(check => check.expr),
        queries.map(q => q.expression));
    }
    for (const check of c.promql_expr_test) {
      assert.ok(!check.expr.startsWith('sum('));
      for (const sample of check.exp_samples) {
        assert.match(sample.labels, /instance="node-a"/u); assert.match(sample.labels, /environment="test"/u);
        assert.match(sample.labels, /engine_job_monitor="enabled"/u);
      }
    }
  }
});
test('native expectations retain all four independent counts, including genuine zero', () => {
  for (const [name, expected] of [
    ['selected replica retains exact queue labels before and after the dead-letter hold', [3, 5, 2, 1]],
    ['fresh empty queues remain genuine zero', [0, 0, 0, 0]],
  ]) {
    const c = scenario(name);
    assert.deepEqual(c.promql_expr_test.slice(2, 6).map(check => check.exp_samples[0].value), expected);
    assert.ok(c.promql_expr_test.slice(2, 6).every((check, index) =>
      check.exp_samples[0].labels.includes(`queue="${engineQueues[index]}"`)));
  }
});
test('missing stale nonfinite and negative values invalidate all snapshot and trend quantities', () => {
  for (const key of ['sample', ...engineQueues]) {
    for (const name of ['missing ' + key + ' invalidates the full sample',
      'stale ' + key + ' breaks trends and starts the unavailable hold',
      ...['NaN', '+Inf', '-Inf', -1].map(bad => 'invalid ' + key + '=' + bad + ' never looks like an empty queue')]) {
      const c = scenario(name);
      for (const [index, check] of c.promql_expr_test.entries()) {
        if (index % 11 > 0 && index % 11 < 10) assert.deepEqual(check.exp_samples, []);
      }
      for (const s of c.input_series) assert.doesNotMatch(s.values, /(?:NaN|[+-]?Inf)x/u);
    }
  }
});
test('monitor recovery restarts the unchanged two-minute dead-letter hold', () => {
  const c = scenario('sampling recovery restarts the original dead-letter hold');
  const alertQuery = queries.find(q => q.id === 12).expression;
  const checks = c.promql_expr_test.filter(check => check.expr === alertQuery);
  assert.deepEqual(checks.map(check => check.eval_time), ['2m', '3m', '4m', '5m']);
  assert.deepEqual(checks.map(check => check.exp_samples.length), [1, 0, 0, 1]);
  assert.match(checks[0].exp_samples[0].labels, /ApprovalEngineJobMonitoringUnavailable/u);
  assert.match(checks[3].exp_samples[0].labels, /ApprovalEngineJobDeadLetters/u);
});
test('missing stale and zero recording rules are tested without creating them from the engine rule group', () => {
  assert.equal(fixtures[1].tests.length, 3);
  for (const c of fixtures[1].tests) assert.ok(c.promql_expr_test.slice(1).every(check => check.exp_samples.length === 0));
});
test('rule inventory or alert hold drift is not silently accepted', () => {
  for (const change of [d => d.groups[0].rules.pop(), d => { d.groups[0].rules[1].for = '1s'; }]) {
    const d = structuredClone(rules); change(d);
    assert.throws(() => engineDashboardFixtures(ruleFile, 'queries.yml', d, queries));
  }
});
function runner(t) {
  const directory = mkdtempSync(resolve(tmpdir(), 'engine-dashboard-unit-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const promtool = resolve(directory, 'promtool'); writeFileSync(promtool, 'non-native fixture tool');
  return { directory, repositoryRoot: root, promtool };
}
test('native driver parses before evaluating, reuses the pinned tool and emits no mocked success receipt', t => {
  const options = runner(t); const calls = [], messages = [];
  t.mock.method(console, 'log', (...args) => messages.push(args));
  const result = verifyEngineJobDashboard({ ...options, runCommand: (...args) => calls.push(args) });
  assert.equal(calls.length, 2); assert.deepEqual(calls[0][1].slice(0, 2), ['check', 'rules']);
  assert.deepEqual(calls[1][1].slice(0, 2), ['test', 'rules']); assert.equal(calls[1][1].length, 4);
  assert.equal(calls[0][3], 30000); assert.equal(calls[1][3], 60000);
  assert.equal(result.queryCount, 11); assert.equal(result.scenarios, 43); assert.equal(result.assertions, 935);
  assert.equal(result.dashboardSha256, hash(bytes)); assert.equal(result.ruleSha256, hash(ruleBytes));
  assert.equal(result.grafanaBrowserVerified, false); assert.equal(result.notificationDeliveryVerified, false);
  assert.equal(result.productionDeploymentVerified, false); assert.deepEqual(messages, []);
  for (const [index, path] of calls[1][1].slice(2).entries()) {
    assert.equal(result.fixtures[index].sha256, hash(readFileSync(path)));
    assert.equal(JSON.parse(readFileSync(path)).tests.length, fixtures[index].tests.length);
  }
});
for (const failAt of [1, 2]) {
  test('native failure propagates without retry at command ' + failAt, t => {
    const options = runner(t); let calls = 0; const failure = new Error('EXPECTED_NATIVE_FAILURE');
    assert.throws(() => verifyEngineJobDashboard({ ...options, runCommand() {
      if (++calls === failAt) throw failure;
    } }), error => error === failure);
    assert.equal(calls, failAt);
  });
}
test('invalid tool path and occupied output fail before a native command', t => {
  const options = runner(t); let calls = 0; options.runCommand = () => calls++;
  assert.throws(() => verifyEngineJobDashboard({ ...options, promtool: 'promtool' }), /ABSOLUTE/u);
  const link = resolve(options.directory, 'link'); symlinkSync(options.promtool, link);
  assert.throws(() => verifyEngineJobDashboard({ ...options, promtool: link }), /TOOL_FILE/u);
  const queryFile = resolve(options.directory, 'engine-dashboard-queries.yml'); writeFileSync(queryFile, 'retained');
  assert.throws(() => verifyEngineJobDashboard(options), /EEXIST/u);
  assert.equal(readFileSync(queryFile, 'utf8'), 'retained'); assert.equal(calls, 0);
});
test('existing provisioner and aggregate execute the new check without another workflow', () => {
  const provisioner = text('scripts/ops/verify-prometheus-rules.mjs');
  assert.ok(provisioner.includes("import { verifyEngineJobDashboard } from './verify-engine-job-dashboard.mjs';"));
  assert.ok(provisioner.indexOf('const result = runPromtoolChecks(executable)')
    < provisioner.indexOf('result.engineJobDashboard = verifyEngineJobDashboard('));
  assert.ok(provisioner.includes('console.log(JSON.stringify(result.engineJobDashboard))'));
  assert.ok(text('scripts/tests/m4-sla-calendar-boundary.test.mjs').includes("import './ops-engine-job-dashboard.test.mjs';"));
  assert.ok(text('docs/operations/engine-job-dashboard.md').includes('not business recovery'));
});

// The unchanged native fixtures include a healthy target with the same address
// in another environment. It must neither authorize this target nor silence it.
test('engine health and unavailable rules match the full environment target identity', () => {
  const [health, dead, unavailable] = rules.groups[0].rules;
  assert.ok(health.expr.includes('and on (job, instance, environment) (up{'));
  assert.ok(unavailable.expr.includes('unless on (job, instance, environment) ' + engineHealthRecord));
  assert.deepEqual([dead.for, unavailable.for], ['2m', '2m']);
  for (const c of fixtures[0].tests) {
    const otherUp = c.input_series.find(s => s.series.startsWith('up{')
      && s.series.includes('instance="node-a"') && s.series.includes('environment="another-environment"'));
    assert.ok(otherUp); assert.equal(otherUp.values, '1 1 1 1 1 1 1');
  }
});
