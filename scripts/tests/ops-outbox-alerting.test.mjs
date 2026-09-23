import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { buildOutboxRuleFixtures, outboxAlertNames } from '../ops/outbox-alert-fixtures.mjs';
import { alertmanagerPin, fixtureReceiverConfiguration, renderOutboxRehearsalMetrics, verifyOutboxAlerting } from '../ops/verify-outbox-alerts.mjs';

const root = fileURLToPath(new URL('../..', import.meta.url));
const ruleFile = resolve(root, 'deploy/observability/prometheus/approval-outbox.rules.yml');
const ruleBytes = readFileSync(ruleFile);
const document = JSON.parse(ruleBytes);
const rules = document.groups.flatMap(group => group.rules);
const fixture = buildOutboxRuleFixtures(ruleFile, document);
const receiver = readFileSync(resolve(root, 'deploy/observability/alertmanager/alertmanager.yml'), 'utf8');
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

test('eight operational conditions retain bounded deployment-only labels and runbooks', () => {
  assert.deepEqual(rules.map(rule => rule.alert), outboxAlertNames);
  for (const rule of rules) {
    assert.ok(['outbox', 'notification-outbox'].includes(rule.labels.component));
    assert.equal(rule.labels.owner, 'approval-platform');
    assert.match(rule.expr, /job="approval-platform",outbox_monitor="enabled"/u);
    assert.match(rule.expr, /on \(job, instance, environment\)/u);
    assert.doesNotMatch(rule.expr, /sum\s*\(|tenant_id|task_id|trace_id|payload/u);
    assert.ok(rule.annotations.runbook_url.endsWith('#' + rule.alert.toLowerCase()));
  }
  assert.deepEqual(rules.map(rule => rule.for ?? null), ['5m', '2m', '2m', null, '2m', '5m', '2m', null]);
  assert.match(rules[0].expr, /approval_outbox_pending\{[^}]+\} \+ approval_outbox_in_flight/u);
  assert.doesNotMatch(rules[0].expr, /approval_outbox_(due|expired_leases)/u);
});
for (const name of outboxAlertNames) {
  test(`${name} has independent positive and negative native-engine expectations`, () => {
    const expectations = fixture.tests.flatMap(group => group.alert_rule_test).filter(check => check.alertname === name);
    assert.ok(expectations.some(check => check.exp_alerts.length > 0));
    assert.ok(expectations.some(check => check.exp_alerts.length === 0));
    for (const expected of expectations.flatMap(check => check.exp_alerts)) {
      assert.ok(['node-a', 'node-b'].includes(expected.exp_labels.instance));
      assert.equal(expected.exp_labels.outbox_monitor, 'enabled');
      assert.ok(expected.exp_annotations.description.includes(expected.exp_labels.instance));
    }
  });
}
test('fixtures cover unavailable, NaN, absence, staleness, replicas, backoff and recovery', () => {
  assert.equal(fixture.tests.length, 25);
  assert.equal(new Set(fixture.tests.map(group => group.name)).size, 25);
  for (const word of ['NaN', 'stale', 'disabled', 'another target', 'not summed', 'future retry', 'recovery', 'subsets', 'notification']) {
    assert.ok(fixture.tests.some(group => group.name.includes(word)), word);
  }
  assert.equal(fixture.evaluation_interval, '1m');
  assert.deepEqual(fixture.rule_files, [ruleFile]);
  const invalid = structuredClone(document); invalid.groups[0].rules.pop();
  assert.throws(() => buildOutboxRuleFixtures(ruleFile, invalid));
});
test('production rules use explicit files, and monitoring expectation stays opt-in', () => {
  const config = readFileSync(resolve(root, 'deploy/observability/prometheus/prometheus.yml'), 'utf8');
  assert.ok(config.includes('/etc/prometheus/rules/approval-outbox.rules.yml'));
  assert.doesNotMatch(config, /\/rules\/\*\.yml/u);
  assert.doesNotMatch(config.replace(/^\s*#.*$/gmu, ''), /outbox_monitor:/u);
});
test('loopback rehearsal preserves production routing and changes only address and batch timing', () => {
  const result = fixtureReceiverConfiguration(receiver, 'http://127.0.0.1:12345/api/v1/alerts');
  const undone = result.replace('url: http://127.0.0.1:12345/api/v1/alerts',
    'url: http://approval-ops-notifier:8080/api/v1/alerts').replace('group_wait: 1s', 'group_wait: 30s')
    .replace('group_interval: 1s', 'group_interval: 5m');
  assert.equal(undone, receiver);
  assert.match(result, /send_resolved: true/u); assert.match(result, /repeat_interval: 4h/u);
});
for (const url of ['https://127.0.0.1:1/api/v1/alerts', 'http://example.com:1/api/v1/alerts',
  'http://user:password@127.0.0.1:1/api/v1/alerts', 'http://127.0.0.1:1/other',
  'http://127.0.0.1:1/api/v1/alerts?token=test']) {
  test(`test destination rejects non-local or unexpected URL ${new URL(url).pathname}`, () => {
    assert.throws(() => fixtureReceiverConfiguration(receiver, url));
  });
}
test('changed receiver template fails closed rather than quietly testing a different route', () => {
  assert.throws(() => fixtureReceiverConfiguration(receiver.replace('group_wait: 30s', 'group_wait: 40s'),
    'http://127.0.0.1:12345/api/v1/alerts'), /TEMPLATE_DRIFT/u);
});
function runnerFixture(t, options = {}) {
  const directory = mkdtempSync(resolve(tmpdir(), 'outbox-alert-unit-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const prometheus = resolve(directory, 'prometheus'); const promtool = resolve(directory, 'promtool');
  writeFileSync(prometheus, 'not an actual binary'); writeFileSync(promtool, 'not an actual binary');
  const bin = resolve(directory, alertmanagerPin.directory); mkdirSync(bin);
  for (const file of ['alertmanager', 'amtool']) writeFileSync(resolve(bin, file), 'unit fixture');
  const calls = []; let checked = false;
  const receipt = { status: 'OUTBOX_ALERT_ROUTING_REHEARSAL_PASSED', ruleSha256: digest(ruleBytes),
    metricSource: 'CONTROLLED_HTTP_FIXTURE', realPrometheus: true, realAlertmanager: true,
    firingDelivered: true, resolvedDelivered: true, receiver503Observed: true,
    cleanupPassed: true, deliveryAttempts: 3, uniqueInMemoryFixtureRecords: 2,
    databaseBusinessChainVerified: false, humanNotificationVerified: false, productionBatchTimingVerified: false,
    ...options.receipt };
  const runCommand = (file, args, cwd, timeout) => {
    calls.push({ file, args, cwd, timeout });
    if (options.failAt === calls.length) throw new Error('EXPECTED_NATIVE_COMMAND_FAILURE');
    if (file === 'tar') assert.equal(checked, true, 'extract only after digest verification');
    if (args[0] === '--version') return 'alertmanager, version 0.34.0\n';
    if (file === process.execPath) return 'OPS_OUTBOX_DELIVERY_RESULT=' + JSON.stringify(receipt) + '\n';
    return 'SUCCESS\n';
  };
  return { calls, options: { directory, repositoryRoot: root, prometheus, promtool, runCommand,
    verifyArchive: (file, expected) => { assert.equal(expected, alertmanagerPin.sha256); checked = true; } } };
}
test('provisioning fixture uses real-rule tests before downloads and requires a current delivery receipt', t => {
  const f = runnerFixture(t); const result = verifyOutboxAlerting(f.options);
  assert.equal(result.status, 'OPS_OUTBOX_ALERTS_VERIFIED'); assert.equal(result.ruleTestGroups, 25);
  assert.equal(result.receipt.databaseBusinessChainVerified, false);
  assert.equal(result.receipt.humanNotificationVerified, false);
  assert.deepEqual(f.calls[0].args, ['check', 'rules', ruleFile]);
  assert.equal(f.calls[1].args[0], 'test'); assert.equal(f.calls[2].file, 'curl');
  assert.equal(f.calls.filter(call => call.file === 'curl').length, 1);
  assert.ok(f.calls[2].args.includes(alertmanagerPin.url));
  assert.ok(f.calls[2].args.includes('--proto-redir')); assert.ok(f.calls[2].args.includes('--max-filesize'));
  assert.equal(f.calls.at(-1).file, process.execPath);
  assert.equal(f.calls.length, 7);
});
for (const failAt of [1, 2, 3, 4, 5, 6, 7]) {
  test(`failed native stage ${failAt} is not retried or turned into success`, t => {
    const f = runnerFixture(t, { failAt });
    assert.throws(() => verifyOutboxAlerting(f.options), /EXPECTED_NATIVE_COMMAND_FAILURE/u);
    assert.equal(f.calls.length, failAt);
  });
}
for (const receipt of [{ status: 'FAILED' }, { ruleSha256: '0'.repeat(64) }, { cleanupPassed: false },
  { resolvedDelivered: false }, { receiver503Observed: false }, { humanNotificationVerified: true }]) {
  test(`reject invalid receipt ${Object.keys(receipt)[0]}`, t => {
    const f = runnerFixture(t, { receipt }); assert.throws(() => verifyOutboxAlerting(f.options));
  });
}

// These assert fixture inventory and driver behavior; native PromQL runs in permanent CI.
test('native label regressions retain deployment labels and isolate incomplete targets', () => {
  const labeled = fixture.tests.find(group => group.name.startsWith('additional deployment labels'));
  assert.ok(labeled);
  assert.deepEqual(labeled.alert_rule_test.map(check => check.eval_time), ['4m', '5m', '6m']);
  const expected = labeled.alert_rule_test[1].exp_alerts[0].exp_labels;
  assert.deepEqual(expected, { job: 'approval-platform', instance: 'node-a', outbox_monitor: 'enabled',
    application: 'approval-platform', environment: 'test', severity: 'warning', owner: 'approval-platform',
    component: 'outbox', cluster: 'cluster-a' });
  assert.ok(labeled.input_series.every(input => input.series.includes('cluster="cluster-a"')));
  const partial = fixture.tests.find(group => group.name.startsWith('partial populations'));
  assert.deepEqual(partial.alert_rule_test[0].exp_alerts, []);
  assert.deepEqual(partial.alert_rule_test[1].exp_alerts.map(alert => alert.exp_labels.instance), ['node-a', 'node-b']);
  assert.equal(partial.input_series.filter(input => input.series.startsWith('approval_outbox_pending{')).length, 1);
  assert.equal(partial.input_series.filter(input => input.series.startsWith('approval_outbox_in_flight{')).length, 1);
  assert.equal(fixture.tests.reduce((total, group) => total + group.alert_rule_test.length, 0), 81);
});
test('a mocked runner cannot publish a native acceptance record to the CI log', t => {
  const f = runnerFixture(t); const messages = [];
  t.mock.method(console, 'log', (...args) => messages.push(args));
  const result = verifyOutboxAlerting(f.options);
  assert.equal(result.status, 'OPS_OUTBOX_ALERTS_VERIFIED');
  assert.deepEqual(messages, [], 'only the real provisioning entrypoint publishes native results');
});

// These read the exact text emitted by the live HTTP source; they do not interpret PromQL.
const notificationMetrics = ['pending', 'due', 'in_flight', 'expired_leases', 'dead',
  'oldest_unfinished_age_seconds'].map(name => 'approval_notification_outbox_' + name);
function parseRehearsalMetrics(dead) {
  const output = renderOutboxRehearsalMetrics(dead);
  const rows = output.trim().split('\n');
  assert.equal(rows.length, 26);
  const values = new Map();
  for (let index = 0; index < rows.length; index += 2) {
    const match = /^(approval_[a-z_]+)\{application="approval-platform"\} (\d+)$/u.exec(rows[index + 1]);
    assert.ok(match, rows[index + 1]);
    assert.equal(rows[index], `# TYPE ${match[1]} gauge`);
    assert.equal(values.has(match[1]), false, 'one sample per series');
    values.set(match[1], Number(match[2]));
  }
  return values;
}
test('live fixture provides every series required by the unchanged completeness rule', () => {
  const values = parseRehearsalMetrics(0);
  const rule = rules.find(rule => rule.alert === 'ApprovalOutboxMonitoringUnavailable');
  const required = [...new Set([...rule.expr.matchAll(/\b(approval_[a-z_]+)\{/gu)].map(match => match[1]))];
  assert.equal(required.length, 13);
  assert.deepEqual([...values.keys()].sort(), required.sort());
  for (const name of notificationMetrics) assert.equal(values.get(name), 0, name);
  assert.equal(values.get('approval_outbox_sample_up'), 1);
  for (const [name, value] of values) {
    if (name !== 'approval_outbox_sample_up') assert.equal(value, 0, name);
  }
});
test('global DEAD trigger and recovery never fabricate a second notification alert', () => {
  const before = parseRehearsalMetrics(0);
  const firing = parseRehearsalMetrics(1);
  const recovered = parseRehearsalMetrics(0);
  assert.equal(firing.get('approval_outbox_dead'), 1);
  for (const [name, value] of before) {
    if (name !== 'approval_outbox_dead') assert.equal(firing.get(name), value, name);
  }
  assert.deepEqual(recovered, before);
  const source = readFileSync(resolve(root, 'scripts/ops/verify-outbox-alerts.mjs'), 'utf8');
  assert.match(source, /response\.end\(renderOutboxRehearsalMetrics\(sourceDead\)\)/u);
  assert.match(source, /assert\.deepEqual\(await currentAlerts\(\), \[\]\)/u);
});
for (const value of [-1, 0.5, NaN, Infinity, '1', null, Number.MAX_SAFE_INTEGER + 1]) {
  test(`live metric fixture rejects invalid DEAD input ${String(value)}`, () => {
    assert.throws(() => renderOutboxRehearsalMetrics(value), /OUTBOX_FIXTURE_DEAD_INVALID/u);
  });
}

test('native assertion inventory retains all eight alert populations', () => {
  const assertions = fixture.tests.flatMap(group => group.alert_rule_test);
  assert.deepEqual(outboxAlertNames.map(name =>
    assertions.filter(check => check.alertname === name).length), [16, 8, 8, 9, 19, 7, 7, 7]);
});
