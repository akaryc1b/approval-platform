import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { Readable } from 'node:stream';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { workflowDeliveryAlerts, workflowGaugeNames, renderWorkflowDeliveryMetrics,
  workflowFixtureReceiverConfiguration, workflowReceiptBook,
  validateWorkflowDeliveryReceipt } from '../ops/workflow-delivery-contract.mjs';
import { boundedBody, runWorkflowPopulationDelivery,
  verifyWorkflowPopulationDelivery } from '../ops/verify-workflow-population-delivery.mjs';
import { operationsDashboardPath, operationsDashboardQueries, dashboardQueryFixtures,
  verifyOperationsDashboard } from '../ops/verify-operations-dashboard.mjs';

const root = fileURLToPath(new URL('../..', import.meta.url));
const ruleFile = resolve(root, 'deploy/observability/prometheus/approval-workflow-population.rules.yml');
const receiverFile = resolve(root, 'deploy/observability/alertmanager/alertmanager.yml');
const ruleBytes = readFileSync(ruleFile); const receiverBytes = readFileSync(receiverFile);
const rules = JSON.parse(ruleBytes).groups.flatMap(group => group.rules).filter(rule => rule.alert);
const hash = value => createHash('sha256').update(value).digest('hex');
const targets = { process: '127.0.0.1:12341', task: '127.0.0.1:12342', unavailable: '127.0.0.1:12343' };
const startedAt = '2026-09-19T00:00:00Z';
function message(kind, status = 'firing') {
  const rule = rules.find(value => value.alert === workflowDeliveryAlerts[kind]);
  const labels = { alertname: rule.alert, job: 'approval-platform', instance: targets[kind], workflow_monitor: 'enabled',
    environment: 'test', ...rule.labels };
  if (kind !== 'unavailable') labels.application = 'approval-platform';
  return { version: '4', receiver: 'approval-platform-operations', status, truncatedAlerts: 0,
    alerts: [{ labels, status, fingerprint: Object.keys(targets).indexOf(kind).toString().repeat(16),
      startsAt: startedAt, endsAt: status === 'resolved' ? '2026-09-19T00:03:00Z' : '0001-01-01T00:00:00Z',
      annotations: Object.fromEntries(Object.entries(rule.annotations)
        .map(([key, value]) => [key, value.replaceAll('{{ $labels.instance }}', targets[kind])])) }] };
}
function completedBook() {
  const book = workflowReceiptBook(targets, rules);
  for (const kind of Object.keys(targets)) { assert.equal(book.accept(message(kind)), 503); assert.equal(book.accept(message(kind)), 200); }
  book.permitResolution();
  for (const kind of Object.keys(targets)) assert.equal(book.accept(message(kind, 'resolved')), 200);
  return book;
}
function receipt() {
  return { status: 'OPS_WORKFLOW_POPULATION_DELIVERY_VERIFIED', ruleSha256: hash(ruleBytes), receiverSha256: hash(receiverBytes),
    realPrometheus: true, realAlertmanager: true, pendingObserved: true, originalHoldsPreserved: true,
    healthyBeforeAndAfter: true, cleanupPassed: true, metricSource: 'CONTROLLED_HTTP_FIXTURE',
    databaseBusinessChainVerified: false, humanNotificationVerified: false, productionBatchTimingVerified: false,
    ...completedBook().summary() };
}
function sandbox(t) {
  const directory = mkdtempSync(resolve(tmpdir(), 'workflow-delivery-unit-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const prometheus = resolve(directory, 'prometheus'), alertmanager = resolve(directory, 'alertmanager');
  writeFileSync(prometheus, 'not a native server'); writeFileSync(alertmanager, 'not a native server');
  return { directory, prometheus, alertmanager, repositoryRoot: root };
}
for (const kind of Object.keys(targets)) {
  test(`controlled ${kind} source has all seven series, preserves scopes and never zero-fills unknown`, () => {
    for (const triggered of [false, true]) {
      const text = renderWorkflowDeliveryMetrics(kind, triggered);
      const lines = text.split('\n').filter(line => line && !line.startsWith('#'));
      assert.equal(lines.length, 7);
      for (const name of [...workflowGaugeNames, 'approval_workflow_sample_up']) assert.equal(lines.filter(line => line.startsWith(name + '{')).length, 1);
      assert.ok(lines.every(line => line.includes('{application="approval-platform"}')));
      if (triggered && kind === 'unavailable') {
        assert.equal(lines.filter(line => line.endsWith(' NaN')).length, 6);
        assert.ok(lines.at(-1).endsWith(' 0'));
      } else {
        assert.ok(lines[2].endsWith(triggered && kind === 'process' ? ' 1' : ' 0'));
        assert.ok(lines[5].endsWith(triggered && kind === 'task' ? ' 1' : ' 0'));
        assert.ok(lines.at(-1).endsWith(' 1'));
      }
    }
  });
}
test('renderer rejects unknown state rather than creating arbitrary labels', () => {
  assert.throws(() => renderWorkflowDeliveryMetrics('customer', true));
  assert.throws(() => renderWorkflowDeliveryMetrics('process', 'true'));
});
test('local receiver substitutions preserve all production settings except address and batching', () => {
  const endpoint = 'http://127.0.0.1:12345/api/v1/alerts';
  const actual = workflowFixtureReceiverConfiguration(receiverBytes.toString(), endpoint);
  assert.equal(actual.replace(endpoint, 'http://approval-ops-notifier:8080/api/v1/alerts')
    .replace('group_wait: 1s', 'group_wait: 30s').replace('group_interval: 1s', 'group_interval: 5m'), receiverBytes.toString());
  assert.ok(actual.includes('send_resolved: true'));
});
for (const url of ['https://127.0.0.1:1/api/v1/alerts', 'http://example.com:1/api/v1/alerts',
  'http://127.0.0.1:1/other', 'http://127.0.0.1:1/api/v1/alerts?key=test', 'http://user@127.0.0.1:1/api/v1/alerts']) {
  test('receiver rejects unsafe fixture endpoint ' + url, () => {
    assert.throws(() => workflowFixtureReceiverConfiguration(receiverBytes.toString(), url));
  });
}
test('changed production batching template fails closed', () => {
  assert.throws(() => workflowFixtureReceiverConfiguration(receiverBytes.toString().replace('group_wait: 30s', 'group_wait: 40s'),
    'http://127.0.0.1:12345/api/v1/alerts'), /TEMPLATE_DRIFT/u);
});
test('three independently retried alerts retain six unique firing/resolved records', () => {
  const result = completedBook().summary(); assert.equal(result.uniqueInMemoryFixtureRecords, 6);
  assert.equal(result.alerts.reduce((n, value) => n + value.attempts, 0), 9);
});
test('receipt book bounds retries and rejects unexpected identity or resolution', () => {
  const book = workflowReceiptBook(targets, rules);
  assert.throws(() => book.accept(message('process', 'resolved')));
  assert.throws(() => book.permitResolution());
  const first = message('process'); book.accept(first);
  const changed = structuredClone(first); changed.alerts[0].fingerprint = 'f'.repeat(16);
  assert.throws(() => book.accept(changed), /FINGERPRINT_CHANGED/u);
  const leaked = structuredClone(first); leaked.alerts[0].labels.tenant_id = 'private';
  assert.throws(() => book.accept(leaked), /ROUTING_LABELS/u);
  for (let i = 1; i < 20; i++) book.accept(first);
  assert.throws(() => book.accept(first), /ATTEMPT_LIMIT/u);
});
test('bounded HTTP body reader counts bytes and rejects oversized input', async () => {
  assert.equal(await boundedBody(Readable.from([Buffer.from('abc'), Buffer.from('de')]), 5), 'abcde');
  await assert.rejects(boundedBody(Readable.from([Buffer.alloc(6)]), 5), /BODY_LIMIT/u);
});
test('real local HTTP receiver exercises 503, retry and resolution for every scope', async t => {
  const book = workflowReceiptBook(targets, rules);
  const server = createServer(async (request, response) => {
    try { response.writeHead(book.accept(JSON.parse(await boundedBody(request, 65536)))).end('{}'); }
    catch { response.writeHead(400).end('{}'); }
  });
  await new Promise(done => server.listen(0, '127.0.0.1', done));
  t.after(async () => { server.closeAllConnections(); await new Promise(done => server.close(done)); });
  const post = async body => (await fetch(`http://127.0.0.1:${server.address().port}/api/v1/alerts`, {
    method: 'POST', body: JSON.stringify(body), signal: AbortSignal.timeout(2000),
  })).status;
  for (const kind of Object.keys(targets)) { assert.equal(await post(message(kind)), 503); assert.equal(await post(message(kind)), 200); }
  book.permitResolution();
  for (const kind of Object.keys(targets)) assert.equal(await post(message(kind, 'resolved')), 200);
  assert.equal(book.summary().uniqueInMemoryFixtureRecords, 6);
});
for (const mutation of [ { cleanupPassed: false }, { originalHoldsPreserved: false }, { pendingObserved: false },
  { ruleSha256: '0'.repeat(64) }, { receiverSha256: '0'.repeat(64) }, { humanNotificationVerified: true },
  { databaseBusinessChainVerified: true }, { uniqueInMemoryFixtureRecords: 3 }, { alerts: [] } ]) {
  test('delivery receipt rejects incomplete or inflated claim ' + Object.keys(mutation)[0], () => {
    const valid = receipt();
    validateWorkflowDeliveryReceipt(valid, hash(ruleBytes), hash(receiverBytes));
    assert.throws(() => validateWorkflowDeliveryReceipt({ ...valid, ...mutation }, hash(ruleBytes), hash(receiverBytes)));
  });
}
test('native driver has one bounded call, requires exact receipt and emits no unit acceptance log', t => {
  const options = sandbox(t); const calls = []; const logs = [];
  t.mock.method(console, 'log', (...args) => logs.push(args));
  const result = verifyWorkflowPopulationDelivery({ ...options, runCommand: (...args) => {
    calls.push(args); return 'OPS_WORKFLOW_DELIVERY_RESULT=' + JSON.stringify(receipt()) + '\n';
  } });
  assert.equal(calls.length, 1); assert.equal(calls[0][0], process.execPath);
  assert.equal(calls[0][3], 240000); assert.equal(result.alerts.length, 3); assert.deepEqual(logs, []);
  assert.throws(() => verifyWorkflowPopulationDelivery({ ...options, runCommand: () => '' }), /ONE_DELIVERY_RECEIPT/u);
  assert.throws(() => verifyWorkflowPopulationDelivery({ ...options, runCommand: () => { throw new Error('native failure'); } }), /native failure/u);
});
test('native startup failure cleans listeners, children and owned temporary directories', async t => {
  const options = sandbox(t);
  writeFileSync(options.alertmanager, '#!/bin/sh\nexit 19\n', { mode: 0o700 });
  // Existing fixture file mode is set separately so this tests real spawn/exit, not ENOENT.
  const { chmodSync } = await import('node:fs'); chmodSync(options.alertmanager, 0o700);
  const before = { term: process.listenerCount('SIGTERM'), int: process.listenerCount('SIGINT') };
  await assert.rejects(runWorkflowPopulationDelivery({ ...options, ruleFile, receiverFile, parent: options.directory }), /NATIVE_EXIT/u);
  assert.deepEqual(readdirSync(options.directory).sort(), ['alertmanager', 'prometheus']);
  assert.equal(process.listenerCount('SIGTERM'), before.term); assert.equal(process.listenerCount('SIGINT'), before.int);
});

const dashboard = JSON.parse(readFileSync(resolve(root, operationsDashboardPath)));
test('dashboard has 25 scoped queries, correct units and unknown-data semantics', () => {
  assert.equal(operationsDashboardQueries(dashboard).length, 25);
  for (const panel of dashboard.panels) {
    assert.ok(panel.gridPos.x >= 0 && panel.gridPos.x + panel.gridPos.w <= 24);
    assert.ok(panel.gridPos.h > 0);
  }
  for (let i = 0; i < dashboard.panels.length; i++) {
    for (const b of dashboard.panels.slice(i + 1)) {
      const a = dashboard.panels[i].gridPos, other = b.gridPos;
      assert.ok(a.x + a.w <= other.x || other.x + other.w <= a.x || a.y + a.h <= other.y || other.y + other.h <= a.y,
        'DASHBOARD_PANEL_OVERLAP');
    }
  }
});
for (const [name, mutate] of [
  ['all replicas', d => { d.templating.list[2].includeAll = true; }],
  ['zero on missing', d => { d.panels.find(p => p.id === 7).fieldConfig.defaults.noValue = '0'; }],
  ['unguarded populations', d => { const p = d.panels.find(p => p.id === 7); p.targets[0].expr = p.targets[0].expr.split(' and ')[0]; }],
  ['unguarded outbox infinity', d => { const p = d.panels.find(p => p.id === 18); p.targets[0].expr = p.targets[0].expr.replace('< +Inf', '!= 0'); }],
  ['wrong process units', d => { d.panels.find(p => p.id === 13).fieldConfig.defaults.unit = 'ms'; }],
]) {
  test('dashboard rejects ' + name, () => { const changed = structuredClone(dashboard); mutate(changed);
    assert.throws(() => operationsDashboardQueries(changed)); });
}
test('dashboard native fixtures evaluate every snapshot panel, empty, failed and replica cases', () => {
  const queries = operationsDashboardQueries(dashboard);
  const fixtures = dashboardQueryFixtures(ruleFile, 'generated-queries.yml', queries);
  assert.equal(fixtures.tests.length, 6);
  assert.equal(fixtures.tests.reduce((n, value) => n + value.promql_expr_test.length, 0), 65);
  assert.ok(fixtures.tests[0].input_series.some(value => value.series.includes('node-b')));
  assert.ok(fixtures.tests[1].promql_expr_test.every(value => value.exp_samples.length === 0));
  assert.ok(fixtures.tests.at(-1).promql_expr_test.every(value => value.exp_samples[0].value === 0));
});
test('dashboard native driver checks syntax before behavior and returns bounded honest evidence', t => {
  const options = sandbox(t); const calls = [];
  const result = verifyOperationsDashboard({ ...options, promtool: options.prometheus,
    runCommand: (...args) => { calls.push(args); return 'SUCCESS'; } });
  assert.equal(calls.length, 2); assert.equal(calls[0][1][0], 'check'); assert.equal(calls[1][1][0], 'test');
  assert.equal(result.queryCount, 25); assert.equal(result.assertions, 65);
  assert.equal(result.grafanaBrowserVerified, false);
  assert.equal(result.dashboardSha256, hash(readFileSync(resolve(root, operationsDashboardPath))));
});
