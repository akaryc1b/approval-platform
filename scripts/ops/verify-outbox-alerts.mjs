#!/usr/bin/env node
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmodSync, lstatSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildOutboxRuleFixtures } from './outbox-alert-fixtures.mjs';

// Upstream release archive, checked against https://prometheus.io/download/ on 2026-09-16.
export const alertmanagerPin = Object.freeze({
  version: '0.34.0',
  url: 'https://github.com/prometheus/alertmanager/releases/download/v0.34.0/alertmanager-0.34.0.linux-amd64.tar.gz',
  sha256: '19c75a11d8c03dc4ade7abdbddfb3a8f28c9e7b000d0849cda0cd71dffd74a03',
  directory: 'alertmanager-0.34.0.linux-amd64',
});
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const script = fileURLToPath(import.meta.url);
const onlyFile = file => {
  assert.ok(isAbsolute(file) && lstatSync(file).isFile() && !lstatSync(file).isSymbolicLink()
    && realpathSync(file) === file, 'OUTBOX_TOOL_FILE_REQUIRED');
  return file;
};

// Use the production receiver route, changing only the local destination and test batching delay.
export function fixtureReceiverConfiguration(source, endpoint) {
  const url = new URL(endpoint);
  assert.equal(url.hostname, '127.0.0.1');
  assert.equal(url.protocol, 'http:');
  assert.equal(url.pathname, '/api/v1/alerts');
  assert.ok(url.port && !url.username && !url.password && !url.search && !url.hash);
  for (const [before, after] of [
    ['url: http://approval-ops-notifier:8080/api/v1/alerts', `url: ${endpoint}`],
    ['group_wait: 30s', 'group_wait: 1s'], ['group_interval: 5m', 'group_interval: 1s'],
  ]) {
    assert.equal(source.split(before).length, 2, 'RECEIVER_TEMPLATE_DRIFT');
    source = source.replace(before, after);
  }
  return source;
}

/** Reuses the already verified Prometheus archive; no second Prometheus download. */
export function verifyOutboxAlerting({ directory, repositoryRoot, prometheus, promtool,
  runCommand, verifyArchive }) {
  const ruleFile = resolve(repositoryRoot, 'deploy/observability/prometheus/approval-outbox.rules.yml');
  const document = JSON.parse(readFileSync(ruleFile, 'utf8'));
  const fixture = buildOutboxRuleFixtures(ruleFile, document);
  const fixtureFile = resolve(directory, 'outbox-rules.test.yml');
  writeFileSync(fixtureFile, JSON.stringify(fixture), { mode: 0o600 });
  runCommand(promtool, ['check', 'rules', ruleFile], directory, 30000);
  runCommand(promtool, ['test', 'rules', fixtureFile], directory, 60000);
  const archive = resolve(directory, 'alertmanager.tgz');
  runCommand('curl', ['--fail', '--location', '--silent', '--show-error', '--proto', '=https',
    '--proto-redir', '=https', '--connect-timeout', '15', '--max-time', '90', '--max-filesize',
    String(120 * 1024 * 1024), alertmanagerPin.url, '-o', archive], directory, 120000);
  verifyArchive(archive, alertmanagerPin.sha256);
  runCommand('tar', ['--extract', '--gzip', '--file', archive, '--directory', directory,
    '--no-same-owner', '--no-same-permissions', `${alertmanagerPin.directory}/alertmanager`,
    `${alertmanagerPin.directory}/amtool`], directory, 15000);
  const alertmanager = onlyFile(resolve(directory, alertmanagerPin.directory, 'alertmanager'));
  const amtool = onlyFile(resolve(directory, alertmanagerPin.directory, 'amtool'));
  for (const file of [prometheus, alertmanager, amtool]) chmodSync(onlyFile(file), 0o700);
  assert.match(runCommand(alertmanager, ['--version'], directory, 10000), /alertmanager, version 0\.34\.0(?:\s|$)/u);
  const receiverFile = resolve(repositoryRoot, 'deploy/observability/alertmanager/alertmanager.yml');
  runCommand(amtool, ['check-config', receiverFile], directory, 10000);
  const output = runCommand(process.execPath, [script, '--live', prometheus, alertmanager,
    ruleFile, receiverFile, directory], directory, 120000);
  const lines = output.split('\n').filter(line => line.startsWith('OPS_OUTBOX_DELIVERY_RESULT='));
  assert.equal(lines.length, 1, 'ONE_DELIVERY_RECEIPT_REQUIRED');
  const receipt = JSON.parse(lines[0].slice('OPS_OUTBOX_DELIVERY_RESULT='.length));
  assert.equal(receipt.status, 'OUTBOX_ALERT_ROUTING_REHEARSAL_PASSED');
  assert.equal(receipt.ruleSha256, hash(readFileSync(ruleFile)));
  for (const key of ['cleanupPassed', 'realPrometheus', 'realAlertmanager', 'firingDelivered',
    'resolvedDelivered', 'receiver503Observed']) assert.equal(receipt[key], true, key);
  for (const key of ['databaseBusinessChainVerified', 'humanNotificationVerified',
    'productionBatchTimingVerified']) assert.equal(receipt[key], false, key);
  assert.equal(receipt.metricSource, 'CONTROLLED_HTTP_FIXTURE');
  assert.equal(receipt.uniqueInMemoryFixtureRecords, 2);
  assert.ok(Number.isInteger(receipt.deliveryAttempts) && receipt.deliveryAttempts >= 3);
  const result = { status: 'OPS_OUTBOX_ALERTS_VERIFIED', ruleSha256: receipt.ruleSha256,
    ruleTestGroups: fixture.tests.length,
    ruleAssertions: fixture.tests.reduce((count, group) => count + group.alert_rule_test.length, 0),
    alertmanagerVersion: alertmanagerPin.version, alertmanagerArchiveSha256: alertmanagerPin.sha256, receipt };
  // The caller may inject a unit runner; only the native entrypoint publishes acceptance.
  return result;
}

async function listen(server) {
  server.requestTimeout = 3000;
  server.headersTimeout = 3000;
  await new Promise((done, fail) => { server.once('error', fail); server.listen(0, '127.0.0.1', done); });
  return `127.0.0.1:${server.address().port}`;
}
async function closeServer(server) {
  if (!server.listening) return;
  server.closeAllConnections();
  await new Promise((done, fail) => server.close(error => error ? fail(error) : done()));
}
async function freeAddress() {
  const server = createServer();
  const address = await listen(server);
  await closeServer(server);
  return address;
}

/** Complete controlled sample for the existing global-DEAD routing rehearsal, not business data. */
export function renderOutboxRehearsalMetrics(dead) {
  assert.ok(Number.isSafeInteger(dead) && dead >= 0, 'OUTBOX_FIXTURE_DEAD_INVALID');
  const global = { pending: 0, due: 0, in_flight: 0, expired_leases: 0,
    dead, oldest_unfinished_age_seconds: 0, sample_up: 1 };
  const notification = { pending: 0, due: 0, in_flight: 0, expired_leases: 0,
    dead: 0, oldest_unfinished_age_seconds: 0 };
  // The shared completeness rule requires both populations even when no notification exists.
  // Keep notification DEAD zero: this rehearsal deliberately delivers only the global alert.
  return [['approval_outbox_', global], ['approval_notification_outbox_', notification]]
    .flatMap(([prefix, values]) => Object.entries(values).map(([key, value]) =>
      `# TYPE ${prefix}${key} gauge\n${prefix}${key}{application="approval-platform"} ${value}\n`)).join('');
}

/** Real Prometheus -> real Alertmanager -> bounded loopback receiver, controlled metric source. */
export async function runOutboxDeliveryRehearsal({ prometheus, alertmanager, ruleFile, receiverFile, parent }) {
  for (const file of [prometheus, alertmanager, ruleFile, receiverFile]) onlyFile(file);
  const directory = mkdtempSync(resolve(parent, 'outbox-delivery-'));
  const children = []; const servers = []; const started = performance.now();
  const deadline = started + 90000;
  let stopped = false; let sourceDead = 0; let sourceReads = 0; let accept = false;
  let rejected = 0; let acknowledgedFiring = 0; let acknowledgedResolved = 0; let receiverError;
  const records = new Map(); const attempts = [];
  const interrupt = () => { stopped = true; };
  process.once('SIGTERM', interrupt); process.once('SIGINT', interrupt);
  function check() {
    assert.ok(!stopped && performance.now() < deadline, 'OUTBOX_REHEARSAL_DEADLINE');
    if (receiverError) throw receiverError;
    for (const child of children) {
      if (child.failure) throw child.failure;
      assert.ok(child.process.exitCode === null && child.process.signalCode === null,
        'OUTBOX_CHILD_EXITED: ' + child.log.slice(-3000));
    }
  }
  const pause = () => new Promise(done => setTimeout(done, 100));
  async function waitFor(probe) {
    while (true) { check(); if (await probe()) return; await pause(); }
  }
  async function get(url) {
    try {
      const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(2000) });
      const text = await response.text();
      assert.ok(text.length <= 262144, 'OUTBOX_RESPONSE_LIMIT');
      return { status: response.status, text };
    } catch { return { status: 0, text: '' }; }
  }
  function launch(file, args) {
    const processHandle = spawn(file, args, { cwd: directory, shell: false,
      env: { PATH: process.env.PATH, LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8' },
      stdio: ['ignore', 'pipe', 'pipe'] });
    const child = { process: processHandle, log: '', failure: null };
    child.closed = new Promise(done => processHandle.once('close', done));
    processHandle.once('error', error => { child.failure = error; });
    for (const stream of [processHandle.stdout, processHandle.stderr]) stream.on('data', bytes => {
      child.log = (child.log + bytes.toString()).slice(-65536);
    });
    children.push(child);
  }
  let receipt; let failure;
  try {
    const source = createServer((request, response) => {
      if (request.method !== 'GET' || request.url !== '/actuator/prometheus') {
        response.writeHead(404).end(); return;
      }
      sourceReads++;
      response.writeHead(200, { 'Content-Type': 'text/plain; version=0.0.4' });
      response.end(renderOutboxRehearsalMetrics(sourceDead));
    });
    servers.push(source); const sourceAddress = await listen(source);
    const receiver = createServer(async (request, response) => {
      try {
        assert.equal(request.method, 'POST'); assert.equal(request.url, '/api/v1/alerts');
        let size = 0; const parts = [];
        for await (const chunk of request) { size += chunk.length; assert.ok(size <= 65536); parts.push(chunk); }
        const body = JSON.parse(Buffer.concat(parts).toString());
        assert.equal(body.version, '4'); assert.equal(body.receiver, 'approval-platform-operations');
        assert.equal(body.truncatedAlerts, 0); assert.equal(body.alerts.length, 1);
        const alert = body.alerts[0];
        assert.equal(alert.labels.alertname, 'ApprovalOutboxDeadLetters');
        assert.equal(alert.labels.instance, sourceAddress);
        assert.equal(alert.labels.outbox_monitor, 'enabled');
        assert.ok(['firing', 'resolved'].includes(alert.status));
        assert.match(alert.fingerprint, /^[0-9a-f]{16,64}$/u);
        assert.ok(Number.isFinite(Date.parse(alert.startsAt)));
        if (alert.status === 'resolved') assert.ok(Date.parse(alert.endsAt) > Date.parse(alert.startsAt));
        assert.ok(attempts.length < 32, 'RECEIVER_ATTEMPT_LIMIT');
        // In-memory fixture dedup only, deliberately not a production durable notifier.
        const key = `${alert.fingerprint}/${alert.startsAt}/${alert.status}`;
        records.set(key, { fingerprint: alert.fingerprint, startsAt: alert.startsAt, status: alert.status });
        assert.ok(records.size <= 2, 'UNEXPECTED_NEW_ALERT_IDENTITY');
        attempts.push({ status: alert.status, acknowledged: accept });
        if (!accept) { rejected++; response.writeHead(503).end(); }
        else {
          if (alert.status === 'firing') acknowledgedFiring++; else acknowledgedResolved++;
          response.writeHead(200).end('{}');
        }
      } catch (error) { receiverError = error; response.writeHead(400).end(); }
    });
    servers.push(receiver); const receiverAddress = await listen(receiver);
    const amAddress = await freeAddress();
    const amConfig = resolve(directory, 'alertmanager.yml');
    writeFileSync(amConfig, fixtureReceiverConfiguration(readFileSync(receiverFile, 'utf8'),
      `http://${receiverAddress}/api/v1/alerts`), { mode: 0o600 });
    launch(alertmanager, [`--config.file=${amConfig}`, `--storage.path=${resolve(directory, 'am-data')}`,
      `--web.listen-address=${amAddress}`, '--cluster.listen-address=']);
    await waitFor(async () => (await get(`http://${amAddress}/-/ready`)).status === 200);
    const promAddress = await freeAddress();
    const promConfig = resolve(directory, 'prometheus.yml');
    writeFileSync(promConfig, JSON.stringify({ global: { scrape_interval: '1s', evaluation_interval: '1s' },
      rule_files: [ruleFile], alerting: { alertmanagers: [{ static_configs: [{ targets: [amAddress] }] }] },
      scrape_configs: [{ job_name: 'approval-platform', metrics_path: '/actuator/prometheus',
        static_configs: [{ targets: [sourceAddress], labels: { outbox_monitor: 'enabled', environment: 'test' } }] }],
    }), { mode: 0o600 });
    launch(prometheus, [`--config.file=${promConfig}`, `--storage.tsdb.path=${resolve(directory, 'prom-data')}`,
      '--storage.tsdb.retention.time=1h', `--web.listen-address=${promAddress}`]);
    await waitFor(async () => (await get(`http://${promAddress}/-/ready`)).status === 200 && sourceReads >= 3);
    async function currentAlerts() {
      const response = await get(`http://${promAddress}/api/v1/alerts`);
      assert.equal(response.status, 200); const body = JSON.parse(response.text);
      assert.equal(body.status, 'success'); return body.data.alerts;
    }
    assert.deepEqual(await currentAlerts(), []);
    sourceDead = 1;
    await waitFor(async () => (await currentAlerts()).some(alert =>
      alert.labels.alertname === 'ApprovalOutboxDeadLetters' && alert.state === 'firing'));
    await waitFor(() => rejected > 0);
    accept = true; // Same alert must retry after a lost/failed receiver acknowledgement.
    await waitFor(() => acknowledgedFiring > 0);
    assert.equal(records.size, 1); assert.ok(attempts.filter(value => value.status === 'firing').length >= 2);
    sourceDead = 0;
    await waitFor(() => acknowledgedResolved > 0);
    await waitFor(async () => (await currentAlerts()).length === 0);
    const events = [...records.values()];
    assert.equal(events.length, 2); assert.equal(events[0].fingerprint, events[1].fingerprint);
    assert.equal(events[0].startsAt, events[1].startsAt);
    assert.deepEqual(events.map(value => value.status).sort(), ['firing', 'resolved']);
    receipt = { status: 'OUTBOX_ALERT_ROUTING_REHEARSAL_PASSED', ruleSha256: hash(readFileSync(ruleFile)),
      metricSource: 'CONTROLLED_HTTP_FIXTURE', realPrometheus: true, realAlertmanager: true,
      firingDelivered: true, resolvedDelivered: true, receiver503Observed: true,
      deliveryAttempts: attempts.length, uniqueInMemoryFixtureRecords: records.size,
      databaseBusinessChainVerified: false, humanNotificationVerified: false,
      productionBatchTimingVerified: false };
  } catch (error) { failure = error; }
  finally {
    for (const child of children.reverse()) {
      if (child.process.exitCode === null && child.process.signalCode === null) child.process.kill('SIGTERM');
      let timer;
      const closed = await Promise.race([child.closed.then(() => true), new Promise(done => {
        timer = setTimeout(() => done(false), 5000);
      })]);
      clearTimeout(timer);
      if (!closed) { child.process.kill('SIGKILL'); failure ||= new Error('OUTBOX_CHILD_FORCED_EXIT'); }
    }
    for (const server of servers.reverse()) await closeServer(server);
    process.removeListener('SIGTERM', interrupt); process.removeListener('SIGINT', interrupt);
    rmSync(directory, { recursive: true, force: true });
  }
  if (failure) throw failure;
  return { ...receipt, cleanupPassed: true, elapsedMs: Math.round(performance.now() - started) };
}

if (process.argv[1] && resolve(process.argv[1]) === script) {
  try {
    assert.equal(process.argv[2], '--live'); assert.equal(process.argv.length, 8);
    const [prometheus, alertmanager, ruleFile, receiverFile, parent] = process.argv.slice(3);
    console.log('OPS_OUTBOX_DELIVERY_RESULT=' + JSON.stringify(await runOutboxDeliveryRehearsal(
      { prometheus, alertmanager, ruleFile, receiverFile, parent })));
  } catch (error) { console.error(error.stack); process.exitCode = 1; }
}
