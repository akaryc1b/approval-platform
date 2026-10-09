import assert from 'node:assert/strict';

export const workflowDeliveryAlerts = Object.freeze({
  process: 'ApprovalProcessPopulationOverdue',
  task: 'ApprovalTaskPopulationOverdue',
  unavailable: 'ApprovalWorkflowPopulationMonitoringUnavailable',
});
export const workflowGaugeNames = Object.freeze([
  'approval_process_active', 'approval_process_sla_covered', 'approval_process_overdue',
  'approval_task_active', 'approval_task_sla_covered', 'approval_task_overdue',
]);

/** Complete, bounded controlled metrics, NOT database or human-delivery evidence. */
export function renderWorkflowDeliveryMetrics(kind, triggered) {
  assert.ok(Object.hasOwn(workflowDeliveryAlerts, kind), 'WORKFLOW_FIXTURE_KIND');
  assert.equal(typeof triggered, 'boolean');
  const unknown = triggered && kind === 'unavailable';
  const values = [1, 1, triggered && kind === 'process' ? 1 : 0,
    1, 1, triggered && kind === 'task' ? 1 : 0];
  return [...workflowGaugeNames.map((name, i) => [name, unknown ? 'NaN' : values[i]]),
    ['approval_workflow_sample_up', unknown ? 0 : 1]]
    .map(([name, value]) => `# TYPE ${name} gauge\n${name}{application="approval-platform"} ${value}\n`).join('');
}

/** Preserve the operator receiver route, changing only loopback destination and batching. */
export function workflowFixtureReceiverConfiguration(source, endpoint) {
  const uri = new URL(endpoint);
  assert.equal(uri.protocol, 'http:'); assert.equal(uri.hostname, '127.0.0.1');
  assert.equal(uri.pathname, '/api/v1/alerts');
  assert.ok(uri.port && !uri.username && !uri.password && !uri.search && !uri.hash);
  for (const [before, after] of [
    ['url: http://approval-ops-notifier:8080/api/v1/alerts', `url: ${endpoint}`],
    ['group_wait: 30s', 'group_wait: 1s'], ['group_interval: 5m', 'group_interval: 1s'],
  ]) {
    assert.equal(source.split(before).length, 2, 'WORKFLOW_RECEIVER_TEMPLATE_DRIFT');
    source = source.replace(before, after);
  }
  return source;
}

/** Test-only receipt book. A persisted-in-memory record is deliberately ACKed with 503 once. */
export function workflowReceiptBook(targets, rules) {
  assert.deepEqual(Object.keys(targets).sort(), Object.keys(workflowDeliveryAlerts).sort());
  assert.equal(new Set(Object.values(targets)).size, 3, 'WORKFLOW_DISTINCT_TARGETS');
  const entries = new Map();
  const records = new Map();
  let allowResolution = false;
  function accept(body) {
    assert.equal(body.version, '4'); assert.equal(body.receiver, 'approval-platform-operations');
    assert.equal(body.truncatedAlerts, 0); assert.equal(body.alerts.length, 1);
    const alert = body.alerts[0];
    assert.ok(['firing', 'resolved'].includes(alert.status));
    assert.equal(body.status, alert.status);
    const kind = Object.keys(workflowDeliveryAlerts).find(key => workflowDeliveryAlerts[key] === alert.labels.alertname);
    assert.ok(kind, 'WORKFLOW_UNEXPECTED_ALERT');
    const rule = rules.find(value => value.alert === alert.labels.alertname);
    assert.ok(rule, 'WORKFLOW_RULE_MISSING');
    const expectedLabels = { alertname: rule.alert, job: 'approval-platform', instance: targets[kind],
      workflow_monitor: 'enabled', environment: 'test', ...rule.labels };
    if (kind !== 'unavailable') expectedLabels.application = 'approval-platform';
    assert.deepEqual(alert.labels, expectedLabels, 'WORKFLOW_ROUTING_LABELS');
    assert.deepEqual(alert.annotations, Object.fromEntries(Object.entries(rule.annotations)
      .map(([key, text]) => [key, text.replaceAll('{{ $labels.instance }}', targets[kind])])));
    assert.match(alert.fingerprint, /^[0-9a-f]{16,64}$/u);
    assert.ok(Number.isFinite(Date.parse(alert.startsAt)), 'WORKFLOW_START_TIME');
    let entry = entries.get(kind);
    if (!entry) {
      assert.equal(alert.status, 'firing', 'WORKFLOW_RESOLUTION_BEFORE_FIRING');
      entry = { kind, fingerprint: alert.fingerprint, startsAt: alert.startsAt,
        attempts: 0, receiver503Observed: false, firingDelivered: false, resolvedDelivered: false };
      entries.set(kind, entry);
    }
    assert.equal(alert.fingerprint, entry.fingerprint, 'WORKFLOW_FINGERPRINT_CHANGED');
    assert.equal(alert.startsAt, entry.startsAt, 'WORKFLOW_START_CHANGED');
    assert.ok(entry.attempts < 20, 'WORKFLOW_RECEIVER_ATTEMPT_LIMIT');
    if (alert.status === 'resolved') {
      assert.ok(allowResolution && entry.firingDelivered, 'WORKFLOW_UNEXPECTED_RESOLUTION');
      assert.ok(Date.parse(alert.endsAt) > Date.parse(alert.startsAt), 'WORKFLOW_END_TIME');
    }
    records.set(`${kind}/${alert.status}`, true);
    entry.attempts++;
    if (!entry.receiver503Observed) {
      entry.receiver503Observed = true;
      return 503;
    }
    if (alert.status === 'firing') entry.firingDelivered = true;
    else entry.resolvedDelivered = true;
    return 200;
  }
  const every = property => entries.size === 3 && [...entries.values()].every(entry => entry[property]);
  return {
    accept,
    firingDelivered: () => every('firingDelivered'),
    resolvedDelivered: () => every('resolvedDelivered'),
    permitResolution: () => { assert.ok(every('firingDelivered')); allowResolution = true; },
    summary: () => {
      assert.ok(every('firingDelivered') && every('resolvedDelivered') && every('receiver503Observed'));
      assert.equal(records.size, 6);
      return { uniqueInMemoryFixtureRecords: records.size,
        alerts: Object.keys(workflowDeliveryAlerts).map(kind => ({ ...entries.get(kind) })) };
    },
  };
}

export function validateWorkflowDeliveryReceipt(receipt, ruleSha256, receiverSha256) {
  assert.equal(receipt.status, 'OPS_WORKFLOW_POPULATION_DELIVERY_VERIFIED');
  assert.equal(receipt.ruleSha256, ruleSha256);
  assert.equal(receipt.receiverSha256, receiverSha256);
  for (const key of ['realPrometheus', 'realAlertmanager', 'pendingObserved', 'originalHoldsPreserved',
    'healthyBeforeAndAfter', 'cleanupPassed']) assert.equal(receipt[key], true, key);
  for (const key of ['databaseBusinessChainVerified', 'humanNotificationVerified',
    'productionBatchTimingVerified']) assert.equal(receipt[key], false, key);
  assert.equal(receipt.metricSource, 'CONTROLLED_HTTP_FIXTURE');
  assert.equal(receipt.uniqueInMemoryFixtureRecords, 6);
  assert.deepEqual(receipt.alerts.map(value => value.kind).sort(), Object.keys(workflowDeliveryAlerts).sort());
  for (const alert of receipt.alerts) {
    assert.equal(alert.firingDelivered, true); assert.equal(alert.resolvedDelivered, true);
    assert.equal(alert.receiver503Observed, true);
    assert.ok(Number.isInteger(alert.attempts) && alert.attempts >= 3 && alert.attempts <= 20);
    assert.match(alert.fingerprint, /^[0-9a-f]{16,64}$/u);
    assert.ok(Number.isFinite(Date.parse(alert.startsAt)));
  }
  return receipt;
}
