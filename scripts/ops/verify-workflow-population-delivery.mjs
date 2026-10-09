#!/usr/bin/env node
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstatSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { renderWorkflowDeliveryMetrics, validateWorkflowDeliveryReceipt, workflowDeliveryAlerts,
  workflowFixtureReceiverConfiguration, workflowReceiptBook } from './workflow-delivery-contract.mjs';

const script = fileURLToPath(import.meta.url);
const hash = value => createHash('sha256').update(value).digest('hex');
const file = path => {
  assert.ok(isAbsolute(path) && lstatSync(path).isFile() && !lstatSync(path).isSymbolicLink()
    && realpathSync(path) === path, 'WORKFLOW_DELIVERY_FILE_REQUIRED');
  return path;
};
export async function boundedBody(stream, maximum) {
  let size = 0; const chunks = [];
  for await (const bytes of stream) {
    size += bytes.length;
    assert.ok(size <= maximum, 'WORKFLOW_HTTP_BODY_LIMIT');
    chunks.push(Buffer.from(bytes));
  }
  return Buffer.concat(chunks).toString('utf8');
}
async function listen(server) {
  server.requestTimeout = 3000; server.headersTimeout = 3000;
  await new Promise((done, fail) => { server.once('error', fail); server.listen(0, '127.0.0.1', done); });
  return `127.0.0.1:${server.address().port}`;
}
async function closeServer(server) {
  if (!server.listening) return;
  server.closeAllConnections();
  await new Promise((done, fail) => server.close(error => error ? fail(error) : done()));
}
async function freeAddress() {
  const server = createServer(); const address = await listen(server); await closeServer(server); return address;
}

/** Three original two-minute alerts run concurrently against real native servers, no rule rewrite. */
export async function runWorkflowPopulationDelivery({ prometheus, alertmanager, ruleFile, receiverFile, parent }) {
  for (const path of [prometheus, alertmanager, ruleFile, receiverFile]) file(path);
  const ruleBytes = readFileSync(ruleFile); const receiverBytes = readFileSync(receiverFile);
  const rules = JSON.parse(ruleBytes).groups.flatMap(group => group.rules).filter(rule => rule.alert);
  assert.deepEqual(rules.map(rule => rule.alert).sort(), Object.values(workflowDeliveryAlerts).sort());
  rules.forEach(rule => assert.equal(rule.for, '2m', 'WORKFLOW_ORIGINAL_HOLD_REQUIRED'));
  const directory = mkdtempSync(resolve(parent, 'workflow-delivery-'));
  const children = []; const servers = []; const targets = {}; const reads = {};
  const started = performance.now(); const deadline = started + 210000;
  let interrupted = false; let triggered = false; let receiverError; let result; let failure;
  const interrupt = () => { interrupted = true; };
  process.once('SIGTERM', interrupt); process.once('SIGINT', interrupt);
  function check() {
    assert.ok(!interrupted && performance.now() < deadline, 'WORKFLOW_DELIVERY_DEADLINE');
    if (receiverError) throw receiverError;
    for (const child of children) {
      if (child.failure) throw child.failure;
      assert.ok(child.process.exitCode === null && child.process.signalCode === null,
        'WORKFLOW_NATIVE_EXIT: ' + child.log.slice(-3000));
    }
  }
  async function waitFor(probe) {
    while (true) { check(); if (await probe()) return;
      await new Promise(done => setTimeout(done, 100)); }
  }
  async function get(url) {
    try {
      const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(2000) });
      return { status: response.status, text: await boundedBody(response.body, 262144) };
    } catch { return { status: 0, text: '' }; }
  }
  function launch(executable, args) {
    const handle = spawn(executable, args, { cwd: directory, shell: false,
      env: { PATH: process.env.PATH, LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8' }, stdio: ['ignore', 'pipe', 'pipe'] });
    const child = { process: handle, log: '', failure: null };
    child.closed = new Promise(done => handle.once('close', done));
    handle.once('error', error => { child.failure = error; });
    for (const stream of [handle.stdout, handle.stderr]) stream.on('data', bytes => {
      child.log = (child.log + bytes.toString()).slice(-65536);
    });
    children.push(child);
  }
  try {
    for (const kind of Object.keys(workflowDeliveryAlerts)) {
      reads[kind] = 0;
      const source = createServer((request, response) => {
        if (request.method !== 'GET' || request.url !== '/actuator/prometheus') {
          response.writeHead(404).end(); return;
        }
        reads[kind]++;
        response.writeHead(200, { 'Content-Type': 'text/plain; version=0.0.4' });
        response.end(renderWorkflowDeliveryMetrics(kind, triggered));
      });
      servers.push(source); targets[kind] = await listen(source);
    }
    const book = workflowReceiptBook(targets, rules);
    const receiver = createServer(async (request, response) => {
      try {
        assert.equal(request.method, 'POST'); assert.equal(request.url, '/api/v1/alerts');
        const status = book.accept(JSON.parse(await boundedBody(request, 65536)));
        response.writeHead(status, { 'Content-Type': 'application/json' }).end('{}');
      } catch (error) { receiverError = error; response.writeHead(400).end(); }
    });
    servers.push(receiver); const receiverAddress = await listen(receiver);
    const amAddress = await freeAddress(); const amConfig = resolve(directory, 'alertmanager.yml');
    writeFileSync(amConfig, workflowFixtureReceiverConfiguration(receiverBytes.toString(),
      `http://${receiverAddress}/api/v1/alerts`), { mode: 0o600 });
    launch(alertmanager, [`--config.file=${amConfig}`, `--storage.path=${resolve(directory, 'am-data')}`,
      `--web.listen-address=${amAddress}`, '--cluster.listen-address=']);
    await waitFor(async () => (await get(`http://${amAddress}/-/ready`)).status === 200);
    const promAddress = await freeAddress(); const promConfig = resolve(directory, 'prometheus.yml');
    writeFileSync(promConfig, JSON.stringify({ global: { scrape_interval: '1s', evaluation_interval: '1s' },
      rule_files: [ruleFile], alerting: { alertmanagers: [{ static_configs: [{ targets: [amAddress] }] }] },
      scrape_configs: [{ job_name: 'approval-platform', metrics_path: '/actuator/prometheus',
        static_configs: [{ targets: Object.values(targets), labels: { workflow_monitor: 'enabled', environment: 'test' } }] }],
    }), { mode: 0o600 });
    launch(prometheus, [`--config.file=${promConfig}`, `--storage.tsdb.path=${resolve(directory, 'prom-data')}`,
      '--storage.tsdb.retention.time=1h', `--web.listen-address=${promAddress}`]);
    await waitFor(async () => (await get(`http://${promAddress}/-/ready`)).status === 200
      && Object.values(reads).every(value => value >= 3));
    async function api(path) {
      const response = await get(`http://${promAddress}/api/v1/${path}`);
      assert.equal(response.status, 200); const body = JSON.parse(response.text);
      assert.equal(body.status, 'success'); return body.data;
    }
    const alerts = async () => (await api('alerts')).alerts;
    async function healthy() {
      const data = await api('query?query=' + encodeURIComponent('approval:workflow_population_sample_healthy'));
      return data.result.length === 3 && data.result.every(value => value.value[1] === '1')
        && Object.values(targets).every(target => data.result.some(value => value.metric.instance === target));
    }
    await waitFor(healthy); assert.deepEqual(await alerts(), []);
    triggered = true;
    const exactAlerts = values => values.length === 3 && Object.entries(workflowDeliveryAlerts).every(([kind, name]) =>
      values.some(value => value.labels.alertname === name && value.labels.instance === targets[kind]));
    await waitFor(async () => { const current = await alerts();
      return exactAlerts(current) && current.every(value => value.state === 'pending'); });
    const pendingObserved = true;
    await waitFor(async () => { const current = await alerts();
      return exactAlerts(current) && current.every(value => value.state === 'firing'); });
    await waitFor(book.firingDelivered);
    book.permitResolution(); triggered = false;
    await waitFor(healthy); await waitFor(book.resolvedDelivered);
    await waitFor(async () => (await alerts()).length === 0);
    assert.equal(hash(readFileSync(ruleFile)), hash(ruleBytes), 'WORKFLOW_RULE_BYTES_CHANGED');
    result = { status: 'OPS_WORKFLOW_POPULATION_DELIVERY_VERIFIED',
      ruleSha256: hash(ruleBytes), receiverSha256: hash(receiverBytes),
      realPrometheus: true, realAlertmanager: true, pendingObserved, originalHoldsPreserved: true,
      healthyBeforeAndAfter: true, metricSource: 'CONTROLLED_HTTP_FIXTURE',
      databaseBusinessChainVerified: false, humanNotificationVerified: false, productionBatchTimingVerified: false,
      ...book.summary() };
  } catch (error) { failure = error; }
  finally {
    for (const child of children.reverse()) {
      if (child.process.exitCode === null && child.process.signalCode === null) child.process.kill('SIGTERM');
      let timer;
      const closed = await Promise.race([child.closed.then(() => true),
        new Promise(done => { timer = setTimeout(() => done(false), 5000); })]);
      clearTimeout(timer);
      if (!closed) {
        child.process.kill('SIGKILL'); failure ||= new Error('WORKFLOW_NATIVE_FORCED_EXIT');
        let killTimer;
        await Promise.race([child.closed, new Promise(done => { killTimer = setTimeout(done, 5000); })]);
        clearTimeout(killTimer);
      }
    }
    for (const server of servers.reverse()) {
      try { await closeServer(server); } catch (error) { failure ||= error; }
    }
    process.removeListener('SIGTERM', interrupt); process.removeListener('SIGINT', interrupt);
    rmSync(directory, { recursive: true, force: true });
  }
  if (failure) throw failure;
  return validateWorkflowDeliveryReceipt({ ...result, cleanupPassed: true,
    elapsedMs: Math.round(performance.now() - started) }, hash(ruleBytes), hash(receiverBytes));
}

/** Called only after the existing provisioner has digest/version-checked both native binaries. */
export function verifyWorkflowPopulationDelivery({ directory, repositoryRoot, prometheus, alertmanager, runCommand }) {
  const ruleFile = resolve(repositoryRoot, 'deploy/observability/prometheus/approval-workflow-population.rules.yml');
  const receiverFile = resolve(repositoryRoot, 'deploy/observability/alertmanager/alertmanager.yml');
  for (const path of [prometheus, alertmanager, ruleFile, receiverFile]) file(path);
  const output = runCommand(process.execPath, [script, '--live', prometheus, alertmanager,
    ruleFile, receiverFile, directory], directory, 240000);
  const marker = 'OPS_WORKFLOW_DELIVERY_RESULT=';
  const lines = output.split('\n').filter(line => line.startsWith(marker));
  assert.equal(lines.length, 1, 'WORKFLOW_ONE_DELIVERY_RECEIPT');
  return validateWorkflowDeliveryReceipt(JSON.parse(lines[0].slice(marker.length)),
    hash(readFileSync(ruleFile)), hash(readFileSync(receiverFile)));
}
if (process.argv[1] && resolve(process.argv[1]) === script) {
  try {
    assert.equal(process.argv[2], '--live'); assert.equal(process.argv.length, 8);
    const [prometheus, alertmanager, ruleFile, receiverFile, parent] = process.argv.slice(3);
    console.log('OPS_WORKFLOW_DELIVERY_RESULT=' + JSON.stringify(await runWorkflowPopulationDelivery(
      { prometheus, alertmanager, ruleFile, receiverFile, parent })));
  } catch (error) { console.error(error.stack); process.exitCode = 1; }
}
