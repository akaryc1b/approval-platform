import assert from 'node:assert/strict';

export const engineJobQueues = Object.freeze(['executable', 'timer', 'suspended', 'dead_letter']);
export const engineJobAlerts = Object.freeze(['ApprovalEngineJobDeadLetters', 'ApprovalEngineJobMonitoringUnavailable']);
export const engineJobHealthRecord = 'approval:engine_job_sample_healthy';
const samples = value => Array(8).fill(String(value)).join(' ');
const labels = instance => ({ job: 'approval-platform', instance, environment: 'test', engine_job_monitor: 'enabled' });
const format = object => '{' + Object.entries(object).sort(([a], [b]) => a.localeCompare(b))
  .map(([key, value]) => key + '=' + JSON.stringify(value)).join(',') + '}';

export function buildEngineJobRuleFixtures(ruleFile, document) {
  const rules = document.groups.flatMap(group => group.rules);
  assert.deepEqual(rules.map(rule => rule.record || rule.alert), [engineJobHealthRecord, ...engineJobAlerts],
    'ENGINE_JOB_RULE_INVENTORY');
  const metrics = ['up', 'approval_engine_jobs_sample_up', ...engineJobQueues];
  const series = (overrides = {}, instance = 'node-a', extra = {}) => metrics.filter(key => overrides[key] !== null)
    .map(key => ({
      series: (engineJobQueues.includes(key) ? 'approval_engine_jobs' : key) + format({
        ...labels(instance), ...extra, ...(key === 'up' ? {} : { application: 'approval-platform' }),
        ...(engineJobQueues.includes(key) ? { queue: key } : {}),
      }),
      values: overrides[key] ?? samples(key === 'up' || key === 'approval_engine_jobs_sample_up' ? 1 : 0),
    }));
  const tests = [];
  function scenario(name, input, checks) {
    const alert_rule_test = [], promql_expr_test = [];
    for (const [minute, healthy = [], dead = [], unavailable = []] of checks) {
      promql_expr_test.push({ expr: engineJobHealthRecord, eval_time: minute + 'm',
        exp_samples: healthy.map(instance => ({ labels: engineJobHealthRecord + format({
          ...labels(instance), application: 'approval-platform' }), value: 1 })) });
      for (const [index, instances] of [dead, unavailable].entries()) {
        const rule = rules[index + 1];
        alert_rule_test.push({ eval_time: minute + 'm', alertname: rule.alert,
          exp_alerts: instances.map(instance => ({
            exp_labels: { ...labels(instance), ...(index === 0 ? {application: 'approval-platform', queue: 'dead_letter'} : {}), ...rule.labels },
            exp_annotations: Object.fromEntries(Object.entries(rule.annotations)
              .map(([key, value]) => [key, value.replaceAll('{{ $labels.instance }}', instance)])),
          })) });
      }
    }
    tests.push({ name, interval: '1m', input_series: input, alert_rule_test, promql_expr_test });
  }
  scenario('empty queues are healthy genuine zero', series(), [[0, ['node-a']], [3, ['node-a']]]);
  scenario('dead letters hold then fire and recover', series({ dead_letter: '1 1 1 0 0 0 0 0' }),
    [[1, ['node-a']], [2, ['node-a'], ['node-a']], [3, ['node-a']]]);
  scenario('planned timers and suspended jobs are not errors', series({ timer: samples(500), suspended: samples(500), executable: samples(500) }),
    [[3, ['node-a']]]);
  scenario('unhealthy sampling is unavailable even with old positive dead letters', series({ approval_engine_jobs_sample_up: samples(0), dead_letter: samples(2) }),
    [[1], [2, [], [], ['node-a']]]);
  scenario('sample recovery restarts dead-letter hold rather than proving recovery', series({ approval_engine_jobs_sample_up: '0 0 0 1 1 1 1 1', dead_letter: samples(1) }),
    [[2, [], [], ['node-a']], [3, ['node-a']], [4, ['node-a']], [5, ['node-a'], ['node-a']]]);
  for (const missing of metrics.filter(key => key !== 'up')) {
    scenario('missing ' + missing + ' invalidates entire sample', series({ [missing]: null }),
      [[2, [], [], ['node-a']]]);
  }
  for (const queue of engineJobQueues) {
    for (const invalid of ['NaN', '+Inf', '-Inf', '-1']) {
      scenario('invalid ' + queue + '=' + invalid, series({ [queue]: samples(invalid) }), [[2, [], [], ['node-a']]]);
    }
    scenario('stale ' + queue, series({ [queue]: '0 0 stale _ _ _ _ _' }),
      [[1, ['node-a']], [2], [4, [], [], ['node-a']]]);
  }
  scenario('target down does not fire a duplicate engine page', series({ up: samples(0), dead_letter: samples(9) }), [[3]]);
  scenario('missing up belongs to target-discovery monitoring', series({ up: null, dead_letter: samples(9) }), [[3]]);
  scenario('disabled expectation does not page', series({ dead_letter: samples(9) }, 'node-a', {engine_job_monitor: 'disabled'}), [[3]]);
  scenario('replica counts are not added', [...series(), ...series({ dead_letter: samples(1) }, 'node-b')],
    [[2, ['node-a', 'node-b'], ['node-b']]]);
  scenario('incomplete replicas cannot combine into health', [...series({ timer: null }), ...series({ executable: null }, 'node-b')],
    [[2, [], [], ['node-a', 'node-b']]]);
  const mismatch = series();
  mismatch.find(item => item.series.startsWith('approval_engine_jobs{')).series =
    mismatch.find(item => item.series.startsWith('approval_engine_jobs{')).series.replace('application="approval-platform"', 'application="other"');
  scenario('mismatched common labels do not join', mismatch, [[2, [], [], ['node-a']]]);
  return { rule_files: [ruleFile], evaluation_interval: '1m', group_eval_order: ['approval-platform-engine-jobs'], tests };
}
