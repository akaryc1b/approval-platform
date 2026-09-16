// Deterministic inputs for the real promtool engine. No PromQL is interpreted here.
import assert from 'node:assert/strict';

export const outboxAlertNames = Object.freeze(['ApprovalOutboxBacklogHigh',
  'ApprovalOutboxOldestUnfinished', 'ApprovalOutboxExpiredLeases',
  'ApprovalOutboxDeadLetters', 'ApprovalOutboxMonitoringUnavailable']);
const gauges = ['pending', 'due', 'in_flight', 'expired_leases', 'dead', 'oldest_unfinished_age_seconds'];

export function buildOutboxRuleFixtures(ruleFile, document) {
  const rules = document.groups.flatMap(group => group.rules);
  assert.deepEqual(rules.map(rule => rule.alert), outboxAlertNames);
  const labels = instance => ({ job: 'approval-platform', instance, outbox_monitor: 'enabled',
    application: 'approval-platform', environment: 'test' });
  function series(overrides = {}, instance = 'node-a', extra = {}) {
    const values = { up: '1x15', sample_up: '1x15', ...Object.fromEntries(gauges.map(name => [name, '0x15'])), ...overrides };
    const tags = { ...labels(instance), ...extra };
    return Object.entries(values).filter(([, value]) => value !== null).map(([name, values]) => ({
      series: `${name === 'up' ? 'up' : 'approval_outbox_' + name}{${Object.entries(name === 'up' ? Object.fromEntries(Object.entries(tags).filter(([key]) => key !== 'application')) : tags)
        .filter(([, value]) => value !== null).map(([key, value]) => `${key}="${value}"`).join(',')}}`, values,
    }));
  }
  function expectation(name, at, instances = []) {
    const rule = rules.find(value => value.alert === name);
    return { eval_time: `${at}m`, alertname: name, exp_alerts: instances.map(instance => ({
      exp_labels: { ...Object.fromEntries(Object.entries(labels(instance)).filter(([key]) =>
        name !== 'ApprovalOutboxMonitoringUnavailable' || key !== 'application')), ...rule.labels },
      exp_annotations: Object.fromEntries(Object.entries(rule.annotations).map(([key, value]) =>
        [key, value.replaceAll('{{ $labels.instance }}', instance)])),
    })) };
  }
  const tests = [];
  function scenario(name, inputs, checks) {
    tests.push({ name, interval: '1m', input_series: inputs,
      alert_rule_test: checks.map(([alert, time, instances]) => expectation(alert, time, instances)) });
  }
  const a = ['node-a'];
  const h = outboxAlertNames;
  scenario('healthy empty monitoring and exact non-firing thresholds', series({ pending: '100x15',
    due: '100x15', oldest_unfinished_age_seconds: '300x15' }), h.map(name => [name, 10, []]));
  scenario('backlog hold boundary and recovery without counting subsets twice', series({
    pending: '99x5 0x9', in_flight: '2x5 0x9', due: '99x5 0x9' }),
  [[h[0], 4, []], [h[0], 5, a], [h[0], 6, []]]);
  scenario('due and expired counts are not added to parent populations', series({
    pending: '50x15', due: '50x15', in_flight: '50x15', expired_leases: '50x15' }), [[h[0], 10, []]]);
  scenario('old unfinished messages including intentional future retry', series({
    pending: '1x2 0x10', oldest_unfinished_age_seconds: '301x2 0x10' }),
  [[h[1], 1, []], [h[1], 2, a], [h[1], 3, []]]);
  scenario('expired lease hold and successful recovery', series({ in_flight: '1x2 0x10', expired_leases: '1x2 0x10' }),
    [[h[2], 1, []], [h[2], 2, a], [h[2], 3, []]]);
  scenario('terminal dead letters fire immediately and clear when no longer present', series({ dead: '1x2 0x10' }),
    [[h[3], 0, a], [h[3], 2, a], [h[3], 3, []]]);
  scenario('failed read is unknown not a recovered healthy queue', series({ sample_up: '0x2 1x10',
    pending: 'NaN NaN NaN 0x10', dead: 'NaN NaN NaN 0x10' }),
  [[h[4], 1, []], [h[4], 2, a], [h[4], 3, []], [h[3], 2, []], [h[0], 2, []]]);
  scenario('entire monitor missing on an expected reachable target', series({ sample_up: null,
    ...Object.fromEntries(gauges.map(name => [name, null])) }), [[h[4], 1, []], [h[4], 2, a]]);
  scenario('one missing gauge must not be hidden by sample up', series({ dead: null }), [[h[4], 2, a]]);
  scenario('NaN sample health is unavailable', series({ sample_up: 'NaN NaN NaN NaN NaN NaN NaN NaN NaN NaN NaN NaN NaN NaN NaN NaN' }), [[h[4], 2, a]]);
  scenario('NaN gauge with sample up is unavailable', series({ pending: 'NaN NaN NaN NaN NaN NaN NaN NaN NaN NaN NaN NaN NaN NaN NaN NaN' }), [[h[4], 2, a]]);
  scenario('stale markers remove formerly healthy series', series({ sample_up: '1 stale _ _ _ _ _ _' }),
    [[h[4], 2, []], [h[4], 3, a]]);
  scenario('unreachable targets defer to platform availability rules', series({ up: '0x15', sample_up: '0x15', dead: '9x15' }),
    h.map(name => [name, 10, []]));
  scenario('default disabled targets are not expected monitors', series({ sample_up: null, dead: '9x15' }, 'node-a',
    { outbox_monitor: null }), h.map(name => [name, 10, []]));
  scenario('other jobs are outside this rule scope', series({ sample_up: '0x15', dead: '9x15' }, 'node-a',
    { job: 'unrelated' }), h.map(name => [name, 10, []]));
  scenario('a healthy replica cannot mask a missing monitor on another target', [
    ...series(), ...series({ sample_up: null }, 'node-b')], [[h[4], 2, ['node-b']]]);
  scenario('replicas sharing a database are not summed', [
    ...series({ pending: '75x15' }), ...series({ pending: '75x15' }, 'node-b')], [[h[0], 10, []]]);
  scenario('failed sampling blocks old queue values from new business alerts', series({
    sample_up: '0x15', pending: '101x15', dead: '1x15', in_flight: '1x15',
    expired_leases: '1x15', oldest_unfinished_age_seconds: '301x15' }),
  [[h[4], 2, a], ...h.slice(0, 4).map(name => [name, 10, []])]);
  return { rule_files: [ruleFile], evaluation_interval: '1m', tests };
}
