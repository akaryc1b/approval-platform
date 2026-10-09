import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { buildSlaCompletionRules, validateSlaCompletionConfig, writeSlaCompletionRules,
  slaCompletionAlertNames } from '../ops/sla-completion-alerts.mjs';
import { buildSlaCompletionAlertFixtures, slaAlertFixtureConfig,
  verifySlaCompletionAlerts } from '../ops/verify-sla-completion-alerts.mjs';

const root = fileURLToPath(new URL('../..', import.meta.url));
const config = () => structuredClone(slaAlertFixtureConfig);
const document = buildSlaCompletionRules(config());
const rules = document.groups[0].rules;
function directory(t) {
  const path = mkdtempSync(resolve(tmpdir(), 'sla-completion-alerts-'));
  t.after(() => rmSync(path, { recursive: true, force: true })); return path;
}

test('explicit objectives are separate and do not mutate caller configuration', () => {
  const before = config(); assert.deepEqual(validateSlaCompletionConfig(before), before);
  assert.deepEqual(before, slaAlertFixtureConfig);
  assert.deepEqual(rules.map(rule => rule.alert), [slaCompletionAlertNames[0], ...slaCompletionAlertNames]);
  assert.deepEqual(rules.map(rule => rule.for), ['5m', '5m', '2m', '2m']);
  for (const rule of rules) {
    assert.equal(rule.labels.owner, 'approval-platform'); assert.equal(rule.labels.component, 'sla-completion');
    assert.ok(rule.annotations.runbook_url.endsWith('#' + rule.alert.toLowerCase()));
    assert.ok(rule.expr.includes('environment="production"'));
    assert.ok(rule.expr.includes('instance="node-a:8081"'));
    assert.ok(rule.expr.includes('sla_lag_monitor="enabled"'));
    assert.match(rule.expr, /on \(job, instance, environment\)/u);
    assert.doesNotMatch(rule.expr, /sum\s*\(|or\s+vector\(0\)|tenant_id|task_id|policy_id/u);
  }
  for (const rule of rules.slice(0, 2)) {
    assert.match(rule.expr, /source="original"/u);
    assert.match(rule.expr, /histogram_quantile\(0\.95, rate\(/u);
    assert.match(rule.expr, /increase\([^\n]+\[30m\]\) >= 20/u);
    assert.match(rule.expr, /unless ignoring \(target, source, time_basis\)/u);
    assert.match(rule.expr, /le="\+Inf"\} == ignoring \(le\)/u);
  }
});

const invalidConfigs = [
  ['missing config', () => null], ['missing scope', () => ({ objectives: [] })],
  ['empty environment', c => ({ ...c, environment: '' })],
  ['selector injection', c => ({ ...c, instance: 'node"} or vector(1)' })],
  ['unknown option', c => ({ ...c, enable: true })],
  ['empty objectives', c => ({ ...c, objectives: [] })],
  ['duplicate objectives', c => ({ ...c, objectives: [c.objectives[0], c.objectives[0]] })],
];
for (const [name, transform] of invalidConfigs) {
  test(`reject ${name} instead of generating permissive defaults`, () => {
    assert.throws(() => buildSlaCompletionRules(transform(config())), /SLA_ALERT_/u);
  });
}
for (const [field, values] of Object.entries({ target: ['tenant-id', 'Task', null], timeBasis: ['wall', 'WORKING_TIME'],
  thresholdSeconds: [0, -1, Infinity, NaN, '60', 604800], minimumSamples: [0, 1.5, '20', 1000001] })) {
  test(`validate every ${field} choice`, () => {
    for (const value of values) {
      const changed = config(); changed.objectives[0][field] = value;
      assert.throws(() => buildSlaCompletionRules(changed), /SLA_ALERT_/u);
    }
  });
}
test('all six bounded target/basis objectives are supported once', () => {
  const c = config(); c.objectives = ['process', 'task', 'collaboration'].flatMap(target =>
    ['natural_time', 'working_time'].map(timeBasis => ({ target, timeBasis, thresholdSeconds: 0.5, minimumSamples: 1 })));
  assert.equal(buildSlaCompletionRules(c).groups[0].rules.length, 8);
  assert.notEqual(buildSlaCompletionRules({ ...c, environment: 'test' }).groups[0].name, document.groups[0].name);
});
test('exclusive writing and invalid input preserve existing files and symlink targets', t => {
  const dir = directory(t); const target = resolve(dir, 'rules.yml');
  assert.deepEqual(writeSlaCompletionRules(config(), target), document);
  const bytes = readFileSync(target, 'utf8');
  assert.throws(() => writeSlaCompletionRules(config(), target), { code: 'EEXIST' });
  assert.throws(() => writeSlaCompletionRules({}, target), /SLA_ALERT_CONFIG_KEYS/u);
  const link = resolve(dir, 'link.yml'); symlinkSync(target, link);
  assert.throws(() => writeSlaCompletionRules(config(), link), { code: 'EEXIST' });
  assert.equal(readFileSync(target, 'utf8'), bytes);
});
test('CLI emits exactly the same rules without claiming deployment or echoing input', t => {
  const dir = directory(t); const input = resolve(dir, 'config.json'); const output = resolve(dir, 'rules.yml');
  writeFileSync(input, JSON.stringify(config()));
  const script = resolve(root, 'scripts/ops/sla-completion-alerts.mjs');
  const run = (...args) => spawnSync(process.execPath, [script, ...args], { encoding: 'utf8', timeout: 10000 });
  const result = run('--config', input, '--output', output);
  assert.equal(result.status, 0); assert.deepEqual(JSON.parse(readFileSync(output)), document);
  assert.match(result.stdout, /explicitly provision/u);
  assert.equal(run('--config', input, '--output', output).status, 1);
  writeFileSync(input, '{"private-fixture-secret":');
  const invalid = run('--config', input, '--output', output);
  assert.equal(invalid.status, 1); assert.doesNotMatch(invalid.stderr, /private-fixture-secret/u);
});
test('native cases retain every alert checkpoint, isolation, holds and recovery', () => {
  const fixture = buildSlaCompletionAlertFixtures('/tmp/rules.yml', document);
  assert.equal(fixture.tests.length, 30);
  assert.equal(new Set(fixture.tests.map(group => group.name)).size, fixture.tests.length);
  for (const group of fixture.tests) {
    assert.equal(group.alert_rule_test.length % 3, 0);
    for (const alert of group.alert_rule_test.flatMap(check => check.exp_alerts)) {
      assert.doesNotMatch(JSON.stringify(alert.exp_annotations), /\{\{/u);
      assert.ok(alert.exp_annotations.description.includes('node-a:8081'));
      assert.ok(alert.exp_annotations.description.includes('production'));
    }
    for (let i = 0; i < group.alert_rule_test.length; i += 3) {
      assert.deepEqual(group.alert_rule_test.slice(i, i + 3).map(check => check.alertname), slaCompletionAlertNames);
    }
  }
  for (const name of slaCompletionAlertNames) {
    const checks = fixture.tests.flatMap(group => group.alert_rule_test).filter(check => check.alertname === name);
    assert.ok(checks.some(check => check.exp_alerts.length)); assert.ok(checks.some(check => !check.exp_alerts.length));
  }
  for (const word of ['environment', 'sample floor', 'hold', 'replay', 'resets', 'stale', 'NaN', 'missing', 'counter', 'low-lag']) {
    assert.ok(fixture.tests.some(group => group.name.includes(word)), word);
  }
});
for (const failAt of [0, 1, 2]) {
  test(`native runner stage ${failAt} preserves failures and never logs fixture acceptance`, t => {
    const dir = directory(t); const tool = resolve(dir, 'promtool'); writeFileSync(tool, 'not a native executable');
    const calls = []; const messages = []; t.mock.method(console, 'log', (...args) => messages.push(args));
    const invoke = () => verifySlaCompletionAlerts({ directory: dir, promtool: tool,
      runCommand(file, args, cwd, timeout) {
        calls.push({ file, args, cwd, timeout }); if (calls.length === failAt) throw new Error('NATIVE_FAILURE');
      } });
    if (failAt) assert.throws(invoke, /NATIVE_FAILURE/u);
    else {
      const result = invoke(); assert.equal(result.status, 'OPS_SLA_COMPLETION_ALERTS_VERIFIED');
      assert.equal(result.scenarios, 30); assert.ok(result.alertAssertions > 100);
      assert.equal(result.deployed, false); assert.equal(result.humanNotificationVerified, false);
      assert.equal(result.liveMetricScrapeVerified, false);
      assert.match(result.generatorSha256, /^[a-f0-9]{64}$/u);
    }
    assert.equal(calls.length, failAt || 2); assert.equal(calls[0].args[0], 'check');
    assert.deepEqual(messages, []);
  });
}
test('unsafe native executable and occupied fixture files fail before execution', t => {
  const dir = directory(t); const tool = resolve(dir, 'promtool'); writeFileSync(tool, 'fixture');
  let calls = 0; const options = { directory: dir, promtool: tool, runCommand: () => calls++ };
  assert.throws(() => verifySlaCompletionAlerts({ ...options, promtool: 'promtool' }), /ABSOLUTE/u);
  const link = resolve(dir, 'tool-link'); symlinkSync(tool, link);
  assert.throws(() => verifySlaCompletionAlerts({ ...options, promtool: link }), /FILE/u);
  writeFileSync(resolve(dir, 'sla-completion-generated.rules.yml'), 'existing');
  assert.throws(() => verifySlaCompletionAlerts(options), { code: 'EEXIST' }); assert.equal(calls, 0);
});
test('generated-rule verifier and tests are reached through existing permanent validation', () => {
  const provisioner = readFileSync(resolve(root, 'scripts/ops/verify-prometheus-rules.mjs'), 'utf8');
  const suite = readFileSync(resolve(root, 'scripts/tests/m4-sla-calendar-boundary.test.mjs'), 'utf8');
  assert.ok(suite.includes("import './ops-sla-completion-alerts.test.mjs';"));
  assert.match(provisioner, /result\.slaCompletionAlerts = verifySlaCompletionAlerts\(\{ directory, promtool: executable,/u);
  assert.ok(provisioner.includes('console.log(JSON.stringify(result.slaCompletionAlerts))'));
  assert.ok(provisioner.indexOf('const result = runPromtoolChecks(executable)') < provisioner.indexOf('result.slaCompletionAlerts ='));
  assert.doesNotMatch(readFileSync(resolve(root, 'scripts/ops/verify-sla-completion-alerts.mjs'), 'utf8'), /\bcurl\b|\bfetch\(|console\.log/u);
});
