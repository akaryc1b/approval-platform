import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, rmSync, writeFileSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';
import { buildEngineJobRuleFixtures, engineJobQueues, engineJobAlerts, engineJobHealthRecord } from '../ops/engine-job-alert-fixtures.mjs';
import { verifyEngineJobAlerts } from '../ops/verify-engine-job-alerts.mjs';

const root = resolve(import.meta.dirname, '../..');
const text = path => readFileSync(resolve(root, path), 'utf8');
const ruleFile = resolve(root, 'deploy/observability/prometheus/approval-engine-jobs.rules.yml');
const document = JSON.parse(readFileSync(ruleFile));
const rules = document.groups[0].rules;
const fixture = buildEngineJobRuleFixtures(ruleFile, document);

test('engine rules preserve fixed queues, two-minute holds and independent opt-in', () => {
  assert.deepEqual(rules.map(rule => rule.record || rule.alert), [engineJobHealthRecord, ...engineJobAlerts]);
  for (const rule of rules.slice(1)) {
    assert.equal(rule.for, '2m');
    assert.deepEqual(rule.labels, {severity: 'critical', owner: 'approval-platform', component: 'engine-jobs'});
    assert.ok(rule.annotations.runbook_url.endsWith('#' + rule.alert.toLowerCase()));
  }
  for (const queue of engineJobQueues) {
    assert.ok(rules[0].expr.includes(`queue="${queue}"} >= 0`));
    assert.ok(rules[0].expr.includes(`queue="${queue}"} < +Inf`));
  }
  for (const rule of rules) {
    assert.doesNotMatch(rule.expr, /sum\s*\(|or\s+vector\(0\)|tenant|process_id|task_id|bool/u);
  }
  assert.ok(rules[0].expr.includes('approval_engine_jobs_sample_up'));
  assert.ok(rules[0].expr.includes('up{job="approval-platform",engine_job_monitor="enabled"} == 1'));
});

test('native fixture freezes all checkpoints including cross-replica and invalid input cases', () => {
  assert.equal(fixture.tests.length, 36);
  assert.equal(new Set(fixture.tests.map(group => group.name)).size, 36);
  assert.equal(fixture.tests.reduce((n,g) => n + g.alert_rule_test.length, 0), 102);
  assert.equal(fixture.tests.reduce((n,g) => n + g.promql_expr_test.length, 0), 51);
  for (const group of fixture.tests) {
    assert.equal(group.alert_rule_test.length, 2 * group.promql_expr_test.length);
    assert.equal(new Set(group.input_series.map(series => series.series)).size, group.input_series.length);
    for (const series of group.input_series) assert.equal(series.values.split(' ').length, 8);
    for (const check of group.promql_expr_test) {
      assert.deepEqual(group.alert_rule_test.filter(alert => alert.eval_time === check.eval_time).map(alert => alert.alertname), engineJobAlerts);
    }
  }
  for (const queue of engineJobQueues) for (const invalid of ['NaN', '+Inf', '-Inf', '-1']) {
    const group = fixture.tests.find(value => value.name === `invalid ${queue}=${invalid}`);
    assert.deepEqual(group.promql_expr_test[0].exp_samples, []);
    assert.equal(group.alert_rule_test[1].exp_alerts.length, 1);
  }
});

test('native rule inventory cannot silently omit a required alert', () => {
  const changed = structuredClone(document); changed.groups[0].rules.pop();
  assert.throws(() => buildEngineJobRuleFixtures(ruleFile, changed), /ENGINE_JOB_RULE_INVENTORY/u);
});

function context(t, failAt = 0) {
  const directory = mkdtempSync(resolve(tmpdir(), 'engine-alert-test-'));
  t.after(() => rmSync(directory, {recursive: true, force: true}));
  const promtool = resolve(directory, 'promtool'); writeFileSync(promtool, 'controlled command fixture');
  const calls = []; const failure = new Error('expected command failure');
  return { directory, promtool, calls, failure, options: {directory, repositoryRoot: root, promtool,
    runCommand(file, args, cwd, timeout) {
      calls.push({file, args, cwd, timeout}); if (calls.length === failAt) throw failure;
    }} };
}
test('verifier evaluates generated fixtures with the existing native-tool contract', t => {
  const f = context(t); const result = verifyEngineJobAlerts(f.options);
  assert.deepEqual(f.calls.map(call => call.args), [['check', 'rules', ruleFile],
    ['test', 'rules', resolve(f.directory, 'engine-job-rules.test.yml')]]);
  assert.deepEqual(f.calls.map(call => call.timeout), [30000, 60000]);
  assert.deepEqual(JSON.parse(readFileSync(resolve(f.directory, 'engine-job-rules.test.yml'))), fixture);
  assert.equal(result.ruleTestGroups, 36); assert.equal(result.alertAssertions, 102);
  assert.equal(result.expressionAssertions, 51);
  assert.equal(result.liveScrapeVerified, false); assert.equal(result.notificationDeliveryVerified, false);
  assert.equal(result.processFailureEventVerified, false);
});
for (const failAt of [1,2]) test(`native failure ${failAt} propagates without retry`, t => {
  const f = context(t, failAt);
  assert.throws(() => verifyEngineJobAlerts(f.options), error => error === f.failure);
  assert.equal(f.calls.length, failAt);
});
test('invalid paths and occupied evidence fail before tool execution', t => {
  const f = context(t);
  assert.throws(() => verifyEngineJobAlerts({...f.options,promtool:'promtool'}), /ABSOLUTE/u);
  const link = resolve(f.directory,'link'); symlinkSync(f.promtool,link);
  assert.throws(() => verifyEngineJobAlerts({...f.options,promtool:link}), /FILE_REQUIRED/u);
  writeFileSync(resolve(f.directory,'engine-job-rules.test.yml'),'do not overwrite');
  assert.throws(() => verifyEngineJobAlerts(f.options), /EEXIST/u);
  assert.equal(f.calls.length,0);
});
test('controlled runner never prints a native success receipt', t => {
  const f = context(t); const messages=[]; t.mock.method(console,'log', (...args)=>messages.push(args));
  verifyEngineJobAlerts(f.options); assert.deepEqual(messages,[]);
});
test('existing provisioning executes new native rules without another download or workflow', () => {
  const provision = text('scripts/ops/verify-prometheus-rules.mjs');
  assert.ok(provision.includes("import { verifyEngineJobAlerts } from './verify-engine-job-alerts.mjs'"));
  assert.ok(provision.indexOf('const result = runPromtoolChecks(executable)') < provision.indexOf('result.engineJobs = verifyEngineJobAlerts('));
  assert.ok(provision.includes('console.log(JSON.stringify(result.engineJobs))'));
  const config = text('deploy/observability/prometheus/prometheus.yml');
  assert.ok(config.includes('/etc/prometheus/rules/approval-engine-jobs.rules.yml'));
  assert.doesNotMatch(config.replace(/^\s*#.*$/gmu, ''), /engine_job_monitor:/u);
  assert.ok(text('scripts/tests/m4-sla-calendar-boundary.test.mjs').includes("import './ops-engine-job-alerting.test.mjs';"));
});
test('reader has only four public count queries and no engine mutation or native table access', () => {
  const source = text('server-modules/approval-engine-flowable/src/main/java/io/github/akaryc1b/approval/engine/flowable/FlowableJobPopulationReader.java');
  assert.equal((source.match(/\.count\(\)/gu)||[]).length,4);
  assert.doesNotMatch(source, /ACT_|executeJob|moveJob|deleteJob|getJobException|\.list\(/u);
  const config = text('apps/server/src/main/java/io/github/akaryc1b/approval/config/ApprovalEngineJobObservabilityConfiguration.java');
  assert.ok(config.includes('isActualTransactionActive()'));
  assert.ok(config.includes('isSynchronizationActive()'));
  assert.ok(config.includes('havingValue = "true"'));
  assert.doesNotMatch(config, /matchIfMissing|setAsyncExecutorActivate|createStandalone|new Hikari/u);
  const guide = text('docs/operations/engine-job-monitoring.md');
  for (const name of engineJobAlerts) assert.ok(guide.includes('## ' + name));
  assert.ok(guide.includes('not an atomic snapshot'));
  assert.ok(guide.includes('shared engine pool'));
});
