import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';
import { isolationRulePaths, isolationAlertNames, outboxIsolationGauges,
  monitoringIsolationFixtures, verifyMonitoringIsolation } from '../ops/verify-monitoring-isolation.mjs';

const root = resolve(import.meta.dirname, '../..');
const contents = isolationRulePaths.map(path => readFileSync(resolve(root, path), 'utf8'));
const files = isolationRulePaths.map(path => resolve(root, path));
const fixture = monitoringIsolationFixtures(files);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
function contract(platform, outbox, workflow) {
  const groups = [...platform.matchAll(/\b(?:sum by|and on) \(([^)]+)\)/gu)];
  assert.equal(groups.length, 15);
  for (const [, keys] of groups) assert.ok(keys.split(', ').includes('environment'), 'ISOLATION_AGGREGATION');
  for (const document of [outbox, workflow]) {
    for (const rule of document.groups.flatMap(group => group.rules)) {
      for (const [, keys] of rule.expr.matchAll(/on \(([^)]+)\)/gu)) {
        assert.equal(keys, 'job, instance, environment', 'ISOLATION_TARGET');
      }
    }
  }
  const rules = outbox.groups[0].rules;
  for (const rule of rules.filter(r => r.alert !== 'ApprovalOutboxMonitoringUnavailable')) {
    assert.match(rule.expr, /\nand \(approval_outbox_sample_up\{/u, 'ISOLATION_SAMPLE_LABELS');
  }
  const complete = rules.find(r => r.alert === 'ApprovalOutboxMonitoringUnavailable').expr;
  for (const name of outboxIsolationGauges) assert.ok(complete.includes(`and (${name}{`), 'ISOLATION_COMPLETE_LABELS');
}
const outbox = JSON.parse(contents[1]), workflow = JSON.parse(contents[2]);
test('all changed joins and aggregations retain environment and application sample identity', () => {
  contract(contents[0], outbox, workflow);
});
for (const [label, modify] of [
  ['HTTP aggregation', s => s.replace('sum by (job, instance, environment)', 'sum by (job, instance)')],
  ['HTTP histogram', s => s.replace('sum by (le, job, instance, environment)', 'sum by (le, job, instance)')],
  ['SLA aggregation', s => s.replace('sum by (application, environment)', 'sum by (application)')],
  ['Connector aggregation', s => s.replace('sum by (application, provider, operation, environment)', 'sum by (application, provider, operation)')],
]) test('reject removal of environment from ' + label, () => {
  assert.throws(() => contract(modify(contents[0]), outbox, workflow), /ISOLATION_AGGREGATION/u);
});
for (const kind of ['outbox', 'workflow']) test('reject legacy target joins in ' + kind, () => {
  const o = structuredClone(outbox), w = structuredClone(workflow);
  const changed = kind === 'outbox' ? o : w;
  for (const rule of changed.groups[0].rules) rule.expr = rule.expr.replaceAll('on (job, instance, environment)', 'on (job, instance)');
  assert.throws(() => contract(contents[0], o, w), /ISOLATION_TARGET/u);
});
test('reject completing an Outbox snapshot with another application label', () => {
  const o = structuredClone(outbox);
  o.groups[0].rules.find(r => r.alert === 'ApprovalOutboxMonitoringUnavailable').expr =
    o.groups[0].rules.find(r => r.alert === 'ApprovalOutboxMonitoringUnavailable').expr
      .replace('and (approval_outbox_due{', 'and on (job, instance, environment) (approval_outbox_due{');
  assert.throws(() => contract(contents[0], o, workflow), /ISOLATION_COMPLETE_LABELS/u);
});
test('native inventory checks every alert and complete workflow health at every checkpoint', () => {
  assert.equal(fixture.tests.length, 51);
  assert.equal(new Set(fixture.tests.map(t => t.name)).size, 51);
  assert.equal(fixture.tests.reduce((n, t) => n + t.promql_expr_test.length, 0), 2079);
  assert.deepEqual(fixture.rule_files, files);
  for (const c of fixture.tests) {
    assert.equal(new Set(c.input_series.map(s => s.series)).size, c.input_series.length);
    assert.ok(c.input_series.some(s => s.series.includes('environment="staging"')));
    for (const s of c.input_series) assert.ok(s.values.includes('x30') || s.values.split(' ').length === 31);
    for (const time of new Set(c.promql_expr_test.map(t => t.eval_time))) {
      const checks = c.promql_expr_test.filter(t => t.eval_time === time);
      assert.equal(checks.length, isolationAlertNames.length + 1);
      assert.deepEqual(checks.slice(0, -1).map(t => /alertname="([^"]+)"/u.exec(t.expr)[1]), isolationAlertNames);
      assert.ok(checks.every(t => !t.expr.includes('sum(')), 'do not hide duplicate or misrouted output');
    }
  }
});
test('both same-address environments, legacy absence, traffic floors, holds and recovery remain independent', () => {
  const find = name => { const c = fixture.tests.find(t => t.name === name); assert.ok(c, name); return c; };
  const busy = find('outbox: independent busy environments retain both routing identities');
  const samples = busy.promql_expr_test.flatMap(t => t.exp_samples);
  assert.equal(samples.length, 14);
  assert.equal(samples.filter(s => s.labels.includes('environment="production"')).length, 7);
  assert.equal(samples.filter(s => s.labels.includes('environment="staging"')).length, 7);
  const low = find('HTTP: low-traffic production cannot borrow the staging traffic floor');
  assert.ok(low.promql_expr_test.flatMap(t => t.exp_samples).every(s => s.labels.includes('environment="staging"')));
  const below = find('SLA: two environments below retry threshold must not be summed');
  assert.ok(below.promql_expr_test.every(t => t.exp_samples.length === 0));
  for (const name of outboxIsolationGauges) {
    find(`outbox: missing ${name} is not supplied by staging`);
    find(`outbox: mismatched ${name} cannot complete an application sample`);
  }
});
function setup(t) {
  const directory = mkdtempSync(resolve(tmpdir(), 'monitoring-isolation-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const promtool = resolve(directory, 'promtool'); writeFileSync(promtool, 'non-native unit fixture');
  return { directory, promtool, repositoryRoot: root };
}
test('native driver checks all real rules then fixtures; local runner never logs native acceptance', t => {
  const options = setup(t), calls = [], logs = [];
  t.mock.method(console, 'log', (...args) => logs.push(args));
  const result = verifyMonitoringIsolation({ ...options, runCommand: (...args) => calls.push(args) });
  assert.deepEqual(calls.map(c => c[1]), [['check', 'rules', ...files], ['test', 'rules', resolve(options.directory, 'monitoring-isolation.test.yml')]]);
  assert.deepEqual(calls.map(c => c[3]), [30000, 60000]);
  assert.deepEqual(result.inputs.map(i => i.sha256), contents.map(hash));
  assert.equal(result.fixtureSha256, hash(readFileSync(calls[1][1][2])));
  assert.equal(result.scenarios, 51); assert.equal(result.assertions, 2079);
  assert.equal(result.liveDeliveryVerified, false); assert.equal(result.productionDeploymentVerified, false);
  assert.deepEqual(logs, []);
});
for (const failAt of [1, 2]) test('native stage failure is not retried: ' + failAt, t => {
  const options = setup(t); let calls = 0; const failure = new Error('fixture-native-failure');
  assert.throws(() => verifyMonitoringIsolation({ ...options, runCommand() { if (++calls === failAt) throw failure; } }), e => e === failure);
  assert.equal(calls, failAt);
});
test('unsafe tool paths and occupied fixture files fail before any command', t => {
  const options = setup(t); let calls = 0; options.runCommand = () => calls++;
  assert.throws(() => verifyMonitoringIsolation({ ...options, promtool: 'promtool' }), /ABSOLUTE_TOOL/u);
  const link = resolve(options.directory, 'link'); symlinkSync(options.promtool, link);
  assert.throws(() => verifyMonitoringIsolation({ ...options, promtool: link }), /TOOL_FILE/u);
  const file = resolve(options.directory, 'monitoring-isolation.test.yml'); writeFileSync(file, 'retained');
  assert.throws(() => verifyMonitoringIsolation(options), /EEXIST/u);
  assert.equal(readFileSync(file, 'utf8'), 'retained'); assert.equal(calls, 0);
});
test('the existing pinned provisioner and aggregate include the suite without another download or workflow', () => {
  const provision = readFileSync(resolve(root, 'scripts/ops/verify-prometheus-rules.mjs'), 'utf8');
  assert.ok(provision.includes("import { verifyMonitoringIsolation } from './verify-monitoring-isolation.mjs';"));
  assert.ok(provision.indexOf('const result = runPromtoolChecks(executable)') < provision.indexOf('result.monitoringIsolation = verifyMonitoringIsolation('));
  assert.ok(provision.includes('console.log(JSON.stringify(result.monitoringIsolation))'));
  assert.ok(readFileSync(resolve(root, 'scripts/tests/m4-sla-calendar-boundary.test.mjs'), 'utf8')
    .includes("import './ops-monitoring-isolation.test.mjs';"));
});
