import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';

export const isolationRulePaths = Object.freeze(['approval-platform', 'approval-outbox',
  'approval-workflow-population'].map(name => `deploy/observability/prometheus/${name}.rules.yml`));
const definitions = [
  ['ApprovalPlatformDown', 'critical', 'runtime'], ['ApprovalPlatformHighHttp5xxRatio', 'critical', 'api'],
  ['ApprovalPlatformHighHttpP95Latency', 'warning', 'api'], ['ApprovalPlatformScrapeMissing', 'critical', 'runtime'],
  ['ApprovalWorkflowOverdueDetected', 'warning', 'workflow-sla'], ['ApprovalWorkflowSlaActionDead', 'critical', 'workflow-sla'],
  ['ApprovalWorkflowSlaActionRetryStorm', 'warning', 'workflow-sla'], ['ApprovalConnectorFailureRatio', 'critical', 'connector'],
  ['ApprovalConnectorTimeoutDetected', 'warning', 'connector'], ['ApprovalOutboxBacklogHigh', 'warning', 'outbox'],
  ['ApprovalOutboxOldestUnfinished', 'warning', 'outbox'], ['ApprovalOutboxExpiredLeases', 'warning', 'outbox'],
  ['ApprovalOutboxDeadLetters', 'critical', 'outbox'], ['ApprovalOutboxMonitoringUnavailable', 'critical', 'outbox'],
  ['ApprovalNotificationOutboxBacklogHigh', 'warning', 'notification-outbox'],
  ['ApprovalNotificationOutboxOldestUnfinished', 'warning', 'notification-outbox'],
  ['ApprovalNotificationOutboxDeadLetters', 'critical', 'notification-outbox'],
  ['ApprovalProcessPopulationOverdue', 'warning', 'workflow-population'],
  ['ApprovalTaskPopulationOverdue', 'warning', 'workflow-population'],
  ['ApprovalWorkflowPopulationMonitoringUnavailable', 'critical', 'workflow-population'],
];
export const isolationAlertNames = Object.freeze(definitions.map(([name]) => name));
const suffixes = ['pending', 'due', 'in_flight', 'expired_leases', 'dead', 'oldest_unfinished_age_seconds'];
export const outboxIsolationGauges = Object.freeze(['approval_outbox_', 'approval_notification_outbox_']
  .flatMap(prefix => suffixes.map(suffix => prefix + suffix)));
const workflowGauges = ['process', 'task'].flatMap(scope => ['active', 'sla_covered', 'overdue']
  .map(suffix => `approval_${scope}_${suffix}`));
const healthRecord = 'approval:workflow_population_sample_healthy';
const sample = value => Array.isArray(value) ? value.join(' ') : Array(31).fill(String(value)).join(' ');
const labelText = (name, labels) => name + '{' + Object.entries(labels).sort(([a], [b]) => a.localeCompare(b))
  .map(([key, value]) => `${key}=${JSON.stringify(value)}`).join(',') + '}';
const target = (environment, monitor) => ({ job: 'approval-platform', instance: 'same-host:8081',
  ...(environment === null ? {} : { environment }), ...(monitor ? { [monitor + '_monitor']: 'enabled' } : {}) });
const app = tags => ({ ...tags, application: 'approval-platform' });
const row = (name, tags, value) => ({ series: labelText(name, tags), values: sample(value) });
function population(kind, environment, overrides = {}) {
  const tags = target(environment, kind);
  const gauges = kind === 'outbox' ? outboxIsolationGauges : workflowGauges;
  const values = { up: 1, [`approval_${kind}_sample_up`]: 1, ...Object.fromEntries(gauges.map(g => [g, 0])), ...overrides };
  return Object.entries(values).filter(([, value]) => value !== null)
    .map(([name, value]) => row(name, name === 'up' ? tags : app(tags), value));
}
const entry = (name, tags) => [name, tags];
const monitorAlert = (kind, environment) => entry(kind === 'outbox'
  ? 'ApprovalOutboxMonitoringUnavailable' : 'ApprovalWorkflowPopulationMonitoringUnavailable', target(environment, kind));
const workflowHealthy = envs => envs.map(env => ({ labels: labelText(healthRecord, app(target(env, 'workflow'))), value: 1 }));
const allOutboxPositive = Object.fromEntries(outboxIsolationGauges.map(name => [name,
  name.endsWith('pending') ? 101 : name.endsWith('age_seconds') ? 301 : 1]));

/** Independent expectations use real alert outputs; no expression implementation or mock evaluator. */
export function monitoringIsolationFixtures(ruleFiles) {
  assert.equal(ruleFiles.length, 3);
  const tests = [];
  function scenario(name, input_series, checkpoints) {
    assert.equal(new Set(input_series.map(s => s.series)).size, input_series.length, 'ISOLATION_DUPLICATE_SERIES');
    const promql_expr_test = [];
    for (const [time, expected = [], healthy = []] of checkpoints) {
      assert.ok(expected.every(([name]) => isolationAlertNames.includes(name)), 'ISOLATION_UNKNOWN_ALERT');
      for (const [name, severity, component] of definitions) {
        promql_expr_test.push({ expr: `ALERTS{alertname="${name}",alertstate="firing"}`, eval_time: time,
          exp_samples: expected.filter(([alert]) => alert === name).map(([, tags]) => ({
            labels: labelText('ALERTS', { ...tags, alertname: name, alertstate: 'firing',
              severity, owner: 'approval-platform', component }), value: 1,
          })) });
      }
      promql_expr_test.push({ expr: healthRecord, eval_time: time, exp_samples: workflowHealthy(healthy) });
    }
    tests.push({ name, interval: '1m', input_series, promql_expr_test });
  }
  for (const kind of ['workflow', 'outbox']) {
    const sampleName = `approval_${kind}_sample_up`;
    const busy = kind === 'outbox' ? allOutboxPositive : {
      approval_process_active: 1, approval_process_sla_covered: 1, approval_process_overdue: 1,
      approval_task_active: 1, approval_task_sla_covered: 1, approval_task_overdue: 1,
    };
    const healthyStage = kind === 'workflow' ? ['staging'] : [];
    for (const [label, value] of [['failed', 0], ['missing', null], ['stale', [1, 'stale', ...Array(29).fill('_')]]]) {
      const fireTime = label === 'stale' ? '3m' : '2m';
      scenario(`${kind}: ${label} production sample cannot borrow staging health`, [
        ...population(kind, 'production', { ...busy, [sampleName]: value }), ...population(kind, 'staging')],
      [['1m', [], healthyStage], [fireTime, [monitorAlert(kind, 'production')], healthyStage],
        ['6m', [monitorAlert(kind, 'production')], healthyStage]]);
    }
    for (const [label, up] of [['down', 0], ['removed', null]]) {
      scenario(`${kind}: ${label} production target cannot borrow staging reachability`, [
        ...population(kind, 'production', { ...busy, up }), ...population(kind, 'staging')],
      [['1m', [], healthyStage], ['6m', up === 0 ? [entry('ApprovalPlatformDown', target('production', kind))] : [], healthyStage]]);
    }
    scenario(`${kind}: unlabeled legacy target cannot borrow a labeled environment`, [
      ...population(kind, null, { ...busy, [sampleName]: 0 }), ...population(kind, 'staging')],
    [['1m', [], healthyStage], ['6m', [monitorAlert(kind, null)], healthyStage]]);
  }
  for (const gauge of outboxIsolationGauges) {
    scenario(`outbox: missing ${gauge} is not supplied by staging`, [
      ...population('outbox', 'production', { [gauge]: null }), ...population('outbox', 'staging')],
    [['1m'], ['2m', [monitorAlert('outbox', 'production')]]]);
  }
  for (const kind of ['outbox', 'workflow']) {
    const first = kind === 'outbox' ? 'approval_outbox_pending' : 'approval_process_active';
    const second = kind === 'outbox' ? 'approval_outbox_in_flight' : 'approval_task_active';
    scenario(`${kind}: partial environments cannot complete one another`, [
      ...population(kind, 'production', { [first]: null }), ...population(kind, 'staging', { [second]: null })],
    [['1m'], ['2m', [monitorAlert(kind, 'production'), monitorAlert(kind, 'staging')]]]);
  }
  for (const gauge of outboxIsolationGauges) {
    const production = population('outbox', 'production').map(s => s.series.startsWith(gauge + '{')
      ? { ...s, series: s.series.replace('application="approval-platform"', 'application="unrelated"') } : s);
    scenario(`outbox: mismatched ${gauge} cannot complete an application sample`, [
      ...production, ...population('outbox', 'staging')], [['2m', [monitorAlert('outbox', 'production')]]]);
  }
  scenario('outbox: foreign application dead letter cannot use another application sampler', [
    ...population('outbox', 'production', { approval_outbox_dead: 1 }).map(s => s.series.startsWith('approval_outbox_dead{')
      ? { ...s, series: s.series.replace('application="approval-platform"', 'application="unrelated"') } : s),
    ...population('outbox', 'staging')], [['2m', [monitorAlert('outbox', 'production')]]]);
  const outboxAlerts = ['ApprovalOutboxBacklogHigh', 'ApprovalOutboxOldestUnfinished', 'ApprovalOutboxExpiredLeases',
    'ApprovalOutboxDeadLetters', 'ApprovalNotificationOutboxBacklogHigh', 'ApprovalNotificationOutboxOldestUnfinished',
    'ApprovalNotificationOutboxDeadLetters'];
  scenario('outbox: independent busy environments retain both routing identities', [
    ...population('outbox', 'production', allOutboxPositive), ...population('outbox', 'staging', allOutboxPositive)],
  [['6m', ['production', 'staging'].flatMap(env => outboxAlerts.map(name => entry(name, app(target(env, 'outbox')))))]]);
  const recovered = [0, 0, 0, 1, 1, 1, 1, ...Array(24).fill(1)];
  const workflowBusy = { approval_process_active: 1, approval_process_sla_covered: 1, approval_process_overdue: 1 };
  scenario('workflow: recovery is local and restarts the original overdue hold', [
    ...population('workflow', 'production', { ...workflowBusy, approval_workflow_sample_up: recovered }), ...population('workflow', 'staging')],
  [['2m', [monitorAlert('workflow', 'production')], ['staging']], ['3m', [], ['production', 'staging']],
    ['4m', [], ['production', 'staging']], ['5m', [entry('ApprovalProcessPopulationOverdue', app(target('production', 'workflow')))], ['production', 'staging']]]);
  scenario('outbox: recovery cannot inherit another environment backlog hold', [
    ...population('outbox', 'production', { approval_outbox_pending: 101, approval_outbox_sample_up: recovered }), ...population('outbox', 'staging')],
  [['2m', [monitorAlert('outbox', 'production')]], ['3m'], ['7m'], ['8m', [entry('ApprovalOutboxBacklogHigh', app(target('production', 'outbox')))]]]);

  const ups = ['production', 'staging'].map(env => row('up', target(env), 1));
  const counter = (name, tags, step) => ({ series: labelText(name, tags), values: `0+${step}x30` });
  const http = (env, bad, good) => [counter('http_server_requests_seconds_count', { ...target(env), status: '500' }, bad),
    counter('http_server_requests_seconds_count', { ...target(env), status: '200' }, good)];
  scenario('HTTP: high staging traffic cannot dilute production errors', [...ups, ...http('production', 12, 108), ...http('staging', 0, 12000)],
    [['10m'], ['11m', [entry('ApprovalPlatformHighHttp5xxRatio', target('production'))]]]);
  scenario('HTTP: low-traffic production cannot borrow the staging traffic floor', [...ups, ...http('production', 1, 2), ...http('staging', 120, 0)],
    [['10m'], ['11m', [entry('ApprovalPlatformHighHttp5xxRatio', target('staging'))]]]);
  const histogram = (env, fast, slow) => ['0.5', '2', '4', '+Inf'].map((le, i) =>
    counter('http_server_requests_seconds_bucket', { ...target(env), le }, i < 2 ? fast : fast + slow));
  scenario('HTTP: fast staging histograms cannot hide slow production P95', [...ups,
    ...histogram('production', 0, 120), ...histogram('staging', 12000, 0), ...http('production', 0, 120), ...http('staging', 0, 12000)],
  [['10m'], ['11m', [entry('ApprovalPlatformHighHttpP95Latency', target('production'))]]]);
  const provider = env => ({ application: 'approval-platform', provider: 'generic', operation: 'callback', environment: env });
  const connector = (env, bad, good) => [counter('approval_connector_invocation_event_total', { ...provider(env), outcome: 'failure' }, bad),
    counter('approval_connector_invocation_event_total', { ...provider(env), outcome: 'success' }, good)];
  scenario('Connector: staging successes cannot dilute production failures', [...ups, ...connector('production', 24, 96), ...connector('staging', 0, 12000)],
    [['10m'], ['11m', [entry('ApprovalConnectorFailureRatio', provider('production'))]]]);
  scenario('Connector: the traffic floor remains local to each environment', [...ups, ...connector('production', 1, 0), ...connector('staging', 120, 0)],
    [['10m'], ['11m', [entry('ApprovalConnectorFailureRatio', provider('staging'))]]]);
  const stepped = n => [0, 0, ...Array(29).fill(n)];
  scenario('Connector: transport timeout preserves the originating environment', [...ups,
    row('approval_connector_invocation_event_total', { ...provider('production'), failure: 'transport_timeout', outcome: 'failure' }, stepped(1)),
    row('approval_connector_invocation_event_total', { ...provider('staging'), failure: 'transport_timeout', outcome: 'failure' }, 0)],
  [['1m'], ['3m', [entry('ApprovalConnectorTimeoutDetected', provider('production'))]]]);
  const sla = env => ({ application: 'approval-platform', environment: env });
  scenario('SLA: overdue and dead notifications retain their originating environment', [...ups,
    row('approval_sla_execution_worker_total', { ...sla('production'), action: 'overdue', result: 'dead' }, stepped(1)),
    row('approval_sla_execution_worker_total', { ...sla('staging'), action: 'overdue', result: 'dead' }, 0)],
  [['1m'], ['3m', [entry('ApprovalWorkflowOverdueDetected', sla('production')),
    entry('ApprovalWorkflowSlaActionDead', { ...sla('production'), action: 'overdue' })]]]);
  const retry = (env, n) => row('approval_sla_execution_worker_total', { ...sla(env), action: 'remind', result: 'retry_scheduled' }, [0, ...Array(30).fill(n)]);
  scenario('SLA: two environments below retry threshold must not be summed', [...ups, retry('production', 2), retry('staging', 2)], [['6m'], ['10m']]);
  scenario('SLA: retry hold and resolution are independent of another environment', [...ups, retry('production', 4), retry('staging', 0)],
    [['5m'], ['6m', [entry('ApprovalWorkflowSlaActionRetryStorm', { ...sla('production'), action: 'remind' })]], ['16m']]);
  return { rule_files: ruleFiles, evaluation_interval: '1m', tests };
}

/** Add only two bounded native calls to the existing pinned-tool lifecycle. */
export function verifyMonitoringIsolation({ directory, repositoryRoot, promtool, runCommand }) {
  assert.ok(isAbsolute(promtool), 'ISOLATION_ABSOLUTE_TOOL');
  const stat = lstatSync(promtool);
  assert.ok(stat.isFile() && !stat.isSymbolicLink() && realpathSync(promtool) === promtool, 'ISOLATION_TOOL_FILE');
  const ruleFiles = isolationRulePaths.map(path => resolve(repositoryRoot, path));
  const hash = bytes => createHash('sha256').update(bytes).digest('hex');
  const inputs = ruleFiles.map((path, i) => ({ path: isolationRulePaths[i], sha256: hash(readFileSync(path)) }));
  const fixture = monitoringIsolationFixtures(ruleFiles);
  const bytes = JSON.stringify(fixture);
  const file = resolve(directory, 'monitoring-isolation.test.yml');
  writeFileSync(file, bytes, { mode: 0o600, flag: 'wx' });
  runCommand(promtool, ['check', 'rules', ...ruleFiles], directory, 30000);
  runCommand(promtool, ['test', 'rules', file], directory, 60000);
  return { status: 'OPS_MONITORING_ISOLATION_VERIFIED', inputs, fixtureSha256: hash(bytes),
    scenarios: fixture.tests.length, assertions: fixture.tests.reduce((n, t) => n + t.promql_expr_test.length, 0),
    liveDeliveryVerified: false, productionDeploymentVerified: false };
}
