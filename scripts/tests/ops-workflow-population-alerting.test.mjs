import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { buildWorkflowPopulationRuleFixtures, workflowPopulationAlerts, workflowPopulationGauges,
  workflowPopulationRecord } from '../ops/workflow-population-alert-fixtures.mjs';
import { verifyWorkflowPopulationAlerts } from '../ops/verify-workflow-population-alerts.mjs';

const root = fileURLToPath(new URL('../..', import.meta.url));
const ruleFile = resolve(root, 'deploy/observability/prometheus/approval-workflow-population.rules.yml');
const bytes = readFileSync(ruleFile); const document = JSON.parse(bytes);
const rules = document.groups.flatMap(group => group.rules);
const fixture = buildWorkflowPopulationRuleFixtures(ruleFile, document);
const text = path => readFileSync(resolve(root, path), 'utf8');
const digest = content => createHash('sha256').update(content).digest('hex');
const scenario = name => {
  const value = fixture.tests.find(group => group.name === name); assert.ok(value, name); return value;
};

test('one health recording rule precedes three independently scoped alerts', () => {
  assert.equal(document.groups.length, 1);
  assert.deepEqual(rules.map(rule => rule.record || rule.alert), [workflowPopulationRecord, ...workflowPopulationAlerts]);
  assert.deepEqual(rules.slice(1).map(rule => rule.for), ['2m', '2m', '2m']);
  assert.deepEqual(rules.slice(1).map(rule => rule.labels.severity), ['warning', 'warning', 'critical']);
  for (const rule of rules.slice(1)) {
    assert.deepEqual(Object.keys(rule.labels).sort(), ['component', 'owner', 'severity']);
    assert.equal(rule.labels.component, 'workflow-population');
    assert.equal(rule.labels.owner, 'approval-platform');
    assert.ok(rule.annotations.runbook_url.endsWith('#' + rule.alert.toLowerCase()));
  }
  for (const rule of rules) {
    assert.match(rule.expr, /job="approval-platform",workflow_monitor="enabled"/u);
    assert.doesNotMatch(rule.expr, /sum\s*\(|bool|or\s+vector\(0\)|tenant_id|task_id|policy_id|payload|outbox_monitor/u);
  }
  assert.match(rules[1].expr, /^\(approval_process_overdue\{[^}]+\} > 0\)\nand approval:workflow_population_sample_healthy/u);
  assert.match(rules[2].expr, /^\(approval_task_overdue\{[^}]+\} > 0\)\nand approval:workflow_population_sample_healthy/u);
  assert.match(rules[3].expr, /unless on \(job, instance\) approval:workflow_population_sample_healthy/u);
});
test('health requires all six finite nonnegative gauges and both subset invariants', () => {
  const expression = rules[0].expr;
  for (const name of workflowPopulationGauges) {
    assert.ok(expression.includes(`and (${name}{job="approval-platform",workflow_monitor="enabled"} >= 0)`));
    assert.ok(expression.includes(`and (${name}{job="approval-platform",workflow_monitor="enabled"} < +Inf)`));
  }
  for (const scope of ['process', 'task']) {
    assert.ok(expression.includes(`approval_${scope}_sla_covered{job="approval-platform",workflow_monitor="enabled"} <= approval_${scope}_active{`));
    assert.ok(expression.includes(`approval_${scope}_overdue{job="approval-platform",workflow_monitor="enabled"} <= approval_${scope}_sla_covered{`));
  }
  assert.match(expression, /^\(approval_workflow_sample_up\{[^}]+\} == 1\)/u);
  assert.match(expression, /and on \(job, instance\) \(up\{[^}]+\} == 1\)$/u);
  assert.equal((expression.match(/on \(job, instance\)/gu) || []).length, 1,
    'all snapshot gauges must retain full common-label matching; only up lacks the application tag');
});
test('every native checkpoint includes all alerts and an exact health-record expectation', () => {
  assert.equal(fixture.tests.length, 54);
  assert.equal(new Set(fixture.tests.map(group => group.name)).size, fixture.tests.length);
  assert.deepEqual(fixture.rule_files, [ruleFile]);
  assert.deepEqual(fixture.group_eval_order, ['approval-platform-workflow-population']);
  assert.equal(fixture.evaluation_interval, '1m');
  for (const group of fixture.tests) {
    assert.equal(group.alert_rule_test.length, group.promql_expr_test.length * 3);
    for (const check of group.promql_expr_test) {
      assert.deepEqual(group.alert_rule_test.filter(value => value.eval_time === check.eval_time)
        .map(value => value.alertname), workflowPopulationAlerts);
      assert.equal(check.expr, workflowPopulationRecord);
    }
    assert.equal(new Set(group.input_series.map(value => value.series)).size, group.input_series.length);
  }
  assert.equal(fixture.tests.reduce((n, g) => n + g.alert_rule_test.length, 0), 243);
  assert.equal(fixture.tests.reduce((n, g) => n + g.promql_expr_test.length, 0), 81);
});
for (const name of workflowPopulationAlerts) {
  test(`${name} has firing, held, cleared and label-checked expectations`, () => {
    const cases = fixture.tests.flatMap(group => group.alert_rule_test).filter(value => value.alertname === name);
    assert.ok(cases.some(value => value.exp_alerts.length > 0));
    assert.ok(cases.some(value => value.exp_alerts.length === 0));
    for (const value of cases.flatMap(check => check.exp_alerts)) {
      assert.equal(value.exp_labels.workflow_monitor, 'enabled');
      assert.ok(['node-a', 'node-b'].includes(value.exp_labels.instance));
      assert.ok(value.exp_annotations.description.includes(value.exp_labels.instance));
      assert.deepEqual(Object.keys(value.exp_annotations).sort(), ['description', 'runbook_url', 'summary']);
    }
  });
}
test('missing invalid and stale samples have independent regression cases', () => {
  for (const name of ['approval_workflow_sample_up', ...workflowPopulationGauges]) {
    const missing = scenario(`missing ${name} cannot be masked by other healthy gauges`);
    assert.ok(missing.input_series.every(value => !value.series.startsWith(name + '{')));
    assert.deepEqual(missing.promql_expr_test[0].exp_samples, []);
    assert.equal(missing.alert_rule_test[2].exp_alerts.length, 1);
    const stale = scenario(`stale ${name} invalidates the whole snapshot`);
    assert.deepEqual(stale.promql_expr_test.map(value => value.exp_samples.length), [1, 0, 0]);
    assert.deepEqual(stale.alert_rule_test.filter(value => value.alertname === workflowPopulationAlerts[2])
      .map(value => value.exp_alerts.length), [0, 0, 1]);
  }
  for (const name of workflowPopulationGauges) {
    for (const invalid of ['NaN', '+Inf', '-1']) {
      const group = scenario(`invalid ${name}=${invalid} is unavailable not zero`);
      assert.equal(group.alert_rule_test[2].exp_alerts.length, 1);
      assert.deepEqual(group.promql_expr_test[0].exp_samples, []);
    }
  }
});
test('same target labels are retained and incomplete replicas never mask one another', () => {
  const labels = scenario('deployment labels survive recording and alert joins without replica sums');
  assert.equal(labels.alert_rule_test[0].exp_alerts[0].exp_labels.cluster, 'cluster-a');
  assert.equal(labels.promql_expr_test[0].exp_samples.length, 2);
  assert.ok(labels.promql_expr_test[0].exp_samples[0].labels.includes('cluster="cluster-a"'));
  const partial = scenario('partial populations on different targets cannot combine into health');
  assert.deepEqual(partial.promql_expr_test[0].exp_samples, []);
  assert.deepEqual(partial.alert_rule_test[2].exp_alerts.map(value => value.exp_labels.instance), ['node-a', 'node-b']);
  const mismatch = scenario('inconsistent common labels cannot manufacture a complete sample');
  assert.ok(mismatch.input_series.some(value => value.series.includes('application="other-application"')));
  assert.equal(mismatch.alert_rule_test[2].exp_alerts.length, 1);
});
test('sampling recovery is not automatically process recovery or an immediate overdue page', () => {
  const group = scenario('failed sampling suppresses stale positive populations and recovery restarts hold');
  assert.deepEqual(group.promql_expr_test.map(value => value.exp_samples.length), [0, 0, 1, 1, 1]);
  assert.deepEqual(group.alert_rule_test.filter(value => value.alertname === workflowPopulationAlerts[0])
    .map(value => value.exp_alerts.length), [0, 0, 0, 0, 1]);
  assert.deepEqual(group.alert_rule_test.filter(value => value.alertname === workflowPopulationAlerts[2])
    .map(value => value.exp_alerts.length), [0, 1, 0, 0, 0]);
});
test('rule inventory drift is rejected instead of silently dropping an alert', () => {
  for (const mutate of [value => value.groups[0].rules.pop(), value => value.groups[0].rules.reverse(),
    value => { value.groups[0].rules[0].record = 'different'; }]) {
    const changed = structuredClone(document); mutate(changed);
    assert.throws(() => buildWorkflowPopulationRuleFixtures(ruleFile, changed), /WORKFLOW_RULE_INVENTORY/u);
  }
});
function runner(t, failAt = 0) {
  const directory = mkdtempSync(resolve(tmpdir(), 'workflow-alert-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const promtool = resolve(directory, 'promtool'); writeFileSync(promtool, 'not an executable native engine');
  const calls = []; const failure = new Error('EXPECTED_NATIVE_FAILURE');
  return { directory, promtool, calls, failure, options: { directory, repositoryRoot: root, promtool,
    runCommand(file, args, cwd, timeout) {
      calls.push({ file, args, cwd, timeout }); if (calls.length === failAt) throw failure;
      return 'SUCCESS';
    } } };
}
test('verifier parses then evaluates exact bytes using the supplied pinned tool without downloads', t => {
  const f = runner(t); const result = verifyWorkflowPopulationAlerts(f.options);
  assert.equal(result.ruleSha256, digest(bytes));
  const fixtureFile = resolve(f.directory, 'workflow-population-rules.test.yml');
  assert.equal(result.fixtureSha256, digest(readFileSync(fixtureFile)));
  assert.deepEqual(JSON.parse(readFileSync(fixtureFile)), fixture);
  assert.deepEqual(f.calls, [
    { file: f.promtool, args: ['check', 'rules', ruleFile], cwd: f.directory, timeout: 30000 },
    { file: f.promtool, args: ['test', 'rules', fixtureFile], cwd: f.directory, timeout: 60000 },
  ]);
  assert.equal(result.ruleTestGroups, fixture.tests.length);
  assert.equal(result.alertAssertions, 243); assert.equal(result.expressionAssertions, 81);
  assert.equal(result.liveScrapeVerified, false); assert.equal(result.notificationDeliveryVerified, false);
  assert.equal(result.databaseBusinessChainVerified, false);
});
for (const failAt of [1, 2]) {
  test(`native command ${failAt} failure propagates without retry or success output`, t => {
    const f = runner(t, failAt);
    assert.throws(() => verifyWorkflowPopulationAlerts(f.options), error => error === f.failure);
    assert.equal(f.calls.length, failAt);
  });
}
test('a mocked runner cannot print native verification or claim live delivery', t => {
  const f = runner(t); const messages = [];
  t.mock.method(console, 'log', (...args) => messages.push(args));
  assert.equal(verifyWorkflowPopulationAlerts(f.options).status, 'OPS_WORKFLOW_POPULATION_RULES_VERIFIED');
  assert.deepEqual(messages, []);
});
test('unsafe tool paths and occupied fixture files fail before native execution', t => {
  const f = runner(t);
  assert.throws(() => verifyWorkflowPopulationAlerts({ ...f.options, promtool: 'promtool' }), /ABSOLUTE/u);
  const link = resolve(f.directory, 'link'); symlinkSync(f.promtool, link);
  assert.throws(() => verifyWorkflowPopulationAlerts({ ...f.options, promtool: link }), /FILE_REQUIRED/u);
  const path = resolve(f.directory, 'workflow-population-rules.test.yml'); writeFileSync(path, 'owned by another invocation');
  assert.throws(() => verifyWorkflowPopulationAlerts(f.options), /EEXIST/u);
  assert.equal(readFileSync(path, 'utf8'), 'owned by another invocation');
  assert.equal(f.calls.length, 0);
});
test('new production file and default-off expectation are wired to existing provisioning and tests', () => {
  const config = text('deploy/observability/prometheus/prometheus.yml');
  assert.ok(config.includes('/etc/prometheus/rules/approval-workflow-population.rules.yml'));
  assert.doesNotMatch(config, /\/rules\/\*\.yml/u);
  assert.doesNotMatch(config.replace(/^\s*#.*$/gmu, ''), /workflow_monitor:/u);
  const provisioning = text('scripts/ops/verify-prometheus-rules.mjs');
  assert.match(provisioning, /import \{ verifyWorkflowPopulationAlerts \}/u);
  assert.ok(provisioning.indexOf('const result = runPromtoolChecks(executable)')
    < provisioning.indexOf('const workflowPopulation = verifyWorkflowPopulationAlerts('));
  assert.match(provisioning, /runCommand: \(file, args, cwd, timeout\) => command\(spawnSync, file, args, cwd, timeout\)/u);
  assert.match(provisioning, /return \{ \.\.\.result, outbox, workflowPopulation, operationsDashboard, workflowDelivery \}/u);
  const suite = text('scripts/tests/ops-prometheus-rule-runtime.test.mjs');
  assert.ok(suite.includes("import './ops-workflow-population-alerting.test.mjs';"));
  assert.ok(suite.includes("assert.equal(result.workflowPopulation.status, 'OPS_WORKFLOW_POPULATION_RULES_VERIFIED')"));
  const guide = text('docs/operations/workflow-population-alerting.md');
  assert.ok(guide.includes('workflow_monitor: enabled'));
  assert.ok(guide.includes('not business recovery'));
  for (const name of workflowPopulationAlerts) assert.ok(guide.includes('## ' + name));
});
