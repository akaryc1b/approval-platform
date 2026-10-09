// Test data for the real PromQL engine. No PromQL evaluator or inferred business state here.
import assert from 'node:assert/strict';

export const workflowPopulationRecord = 'approval:workflow_population_sample_healthy';
export const workflowPopulationGauges = Object.freeze([
  'approval_process_active', 'approval_process_sla_covered', 'approval_process_overdue',
  'approval_task_active', 'approval_task_sla_covered', 'approval_task_overdue',
]);
export const workflowPopulationAlerts = Object.freeze([
  'ApprovalProcessPopulationOverdue', 'ApprovalTaskPopulationOverdue',
  'ApprovalWorkflowPopulationMonitoringUnavailable',
]);

export function buildWorkflowPopulationRuleFixtures(ruleFile, document) {
  const rules = document.groups.flatMap(group => group.rules);
  assert.deepEqual(rules.map(rule => rule.record || rule.alert),
    [workflowPopulationRecord, ...workflowPopulationAlerts], 'WORKFLOW_RULE_INVENTORY');
  const [processAlert, taskAlert, unavailable] = workflowPopulationAlerts;
  const target = (instance = 'node-a', extra = {}) => ({ instance, extra });
  const a = target(); const b = target('node-b');
  const labels = ({ instance, extra }) => Object.fromEntries(Object.entries({
    job: 'approval-platform', instance, workflow_monitor: 'enabled',
    application: 'approval-platform', environment: 'test', ...extra,
  }).filter(([, value]) => value !== null));
  const notation = (name, tags) => `${name}{${Object.entries(tags)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => `${key}="${value}"`).join(',')}}`;
  function series(overrides = {}, owner = a) {
    const values = { up: '1x15', approval_workflow_sample_up: '1x15',
      ...Object.fromEntries(workflowPopulationGauges.map(name => [name, '0x15'])), ...overrides };
    return Object.entries(values).filter(([, value]) => value !== null).map(([name, values]) => {
      const tags = labels(owner); if (name === 'up') delete tags.application;
      return { series: notation(name, tags), values };
    });
  }
  // Every checkpoint inventories all three alerts, including unexpected cross-scope firing.
  function checkpoint(at, healthy = [], firing = {}) {
    return {
      alerts: workflowPopulationAlerts.map(name => {
        const rule = rules.find(value => value.alert === name);
        return { eval_time: `${at}m`, alertname: name, exp_alerts: (firing[name] || []).map(owner => {
          const tags = labels(owner); if (name === unavailable) delete tags.application;
          return { exp_labels: { ...tags, ...rule.labels },
            exp_annotations: Object.fromEntries(Object.entries(rule.annotations).map(([key, value]) =>
              [key, value.replaceAll('{{ $labels.instance }}', owner.instance)])) };
        }) };
      }),
      record: { expr: workflowPopulationRecord, eval_time: `${at}m`, exp_samples: healthy.map(owner =>
        ({ labels: notation(workflowPopulationRecord, labels(owner)), value: 1 })) },
    };
  }
  const tests = [];
  function scenario(name, inputs, checkpoints) {
    tests.push({ name, interval: '1m', input_series: inputs,
      alert_rule_test: checkpoints.flatMap(value => value.alerts),
      promql_expr_test: checkpoints.map(value => value.record) });
  }
  const busy = { approval_process_active: '2x15', approval_process_sla_covered: '2x15',
    approval_process_overdue: '1x15', approval_task_active: '4x15', approval_task_sla_covered: '4x15',
    approval_task_overdue: '2x15' };
  scenario('healthy empty sample is not missing data', series(), [checkpoint(0, [a]), checkpoint(10, [a])]);
  scenario('zero SLA coverage is visible but does not invent an overdue condition', series({
    approval_process_active: '1000000x15', approval_task_active: '2000000x15' }), [checkpoint(10, [a])]);
  scenario('process hold boundary and true zero recovery do not fire task alert', series({
    approval_process_active: '1x15', approval_process_sla_covered: '1x15', approval_process_overdue: '1x2 0x12' }),
  [checkpoint(1, [a]), checkpoint(2, [a], { [processAlert]: [a] }), checkpoint(3, [a])]);
  scenario('task hold boundary and true zero recovery do not fire process alert', series({
    approval_process_active: '1x15', approval_task_active: '1x15',
    approval_task_sla_covered: '1x15', approval_task_overdue: '1x2 0x12' }),
  [checkpoint(1, [a]), checkpoint(2, [a], { [taskAlert]: [a] }), checkpoint(3, [a])]);
  scenario('two scopes remain separate in the same complete sample', series(busy),
    [checkpoint(2, [a], { [processAlert]: [a], [taskAlert]: [a] })]);
  scenario('transient overdue shorter than hold does not page', series({
    approval_process_active: '1x15', approval_process_sla_covered: '1x15', approval_process_overdue: '0 1 0x13' }),
  [checkpoint(1, [a]), checkpoint(2, [a]), checkpoint(3, [a])]);
  scenario('failed sampling suppresses stale positive populations and recovery restarts hold', series({
    ...busy, approval_workflow_sample_up: '0x2 1x12' }),
  [checkpoint(1), checkpoint(2, [], { [unavailable]: [a] }), checkpoint(3, [a]),
    checkpoint(4, [a]), checkpoint(5, [a], { [processAlert]: [a], [taskAlert]: [a] })]);
  scenario('entire monitor absent on an independently expected target', series({
    approval_workflow_sample_up: null, ...Object.fromEntries(workflowPopulationGauges.map(name => [name, null])) }),
  [checkpoint(1), checkpoint(2, [], { [unavailable]: [a] })]);
  const required = ['approval_workflow_sample_up', ...workflowPopulationGauges];
  for (const name of required) {
    scenario(`missing ${name} cannot be masked by other healthy gauges`, series({ [name]: null }),
      [checkpoint(2, [], { [unavailable]: [a] })]);
    scenario(`stale ${name} invalidates the whole snapshot`, series({ [name]: `${name.endsWith('sample_up') ? 1 : 0} stale _ _ _ _ _ _` }),
      [checkpoint(0, [a]), checkpoint(2), checkpoint(3, [], { [unavailable]: [a] })]);
  }
  for (const name of workflowPopulationGauges) {
    for (const invalid of ['NaN', '+Inf', '-1']) {
      scenario(`invalid ${name}=${invalid} is unavailable not zero`, series({ [name]: Array(16).fill(invalid).join(' ') }),
        [checkpoint(2, [], { [unavailable]: [a] })]);
    }
  }
  scenario('NaN sample health is unavailable', series({ approval_workflow_sample_up: Array(16).fill('NaN').join(' ') }),
    [checkpoint(2, [], { [unavailable]: [a] })]);
  for (const scope of ['process', 'task']) {
    scenario(`${scope} covered greater than active is inconsistent`, series({ [`approval_${scope}_sla_covered`]: '1x15' }),
      [checkpoint(2, [], { [unavailable]: [a] })]);
    scenario(`${scope} overdue greater than covered is inconsistent`, series({ [`approval_${scope}_active`]: '1x15',
      [`approval_${scope}_overdue`]: '1x15' }), [checkpoint(2, [], { [unavailable]: [a] })]);
  }
  scenario('unreachable target belongs to platform availability rules', series({ ...busy, up: '0x15' }), [checkpoint(10)]);
  scenario('disappeared scrape target needs separate target inventory', series({ ...busy, up: '1 stale _ _ _ _ _ _' }),
    [checkpoint(0, [a]), checkpoint(3)]);
  for (const [name, extra] of [
    ['default disabled target', { workflow_monitor: null }],
    ['outbox opt in does not enable workflow monitoring', { workflow_monitor: null, outbox_monitor: 'enabled' }],
    ['another job is outside rule scope', { job: 'unrelated' }],
  ]) scenario(name, series({ ...busy, approval_workflow_sample_up: null }, target('node-a', extra)), [checkpoint(10)]);
  scenario('healthy replica cannot mask missing data on another target', [
    ...series(busy), ...series({ approval_task_active: null }, b) ],
  [checkpoint(2, [a], { [processAlert]: [a], [taskAlert]: [a], [unavailable]: [b] })]);
  scenario('partial populations on different targets cannot combine into health', [
    ...series({ approval_task_active: null }), ...series({ approval_process_active: null }, b) ],
  [checkpoint(2, [], { [unavailable]: [a, b] })]);
  const cluster = target('node-a', { cluster: 'cluster-a' });
  scenario('deployment labels survive recording and alert joins without replica sums', [
    ...series(busy, cluster), ...series({}, b) ],
  [checkpoint(2, [cluster, b], { [processAlert]: [cluster], [taskAlert]: [cluster] })]);
  const mismatched = series();
  mismatched.find(value => value.series.startsWith('approval_task_active{')).series =
    notation('approval_task_active', labels(target('node-a', { application: 'other-application' })));
  scenario('inconsistent common labels cannot manufacture a complete sample', mismatched,
    [checkpoint(2, [], { [unavailable]: [a] })]);
  return { rule_files: [ruleFile], evaluation_interval: '1m',
    group_eval_order: ['approval-platform-workflow-population'], tests };
}
