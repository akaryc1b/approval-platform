import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildSlaCompletionRules, droppedMetric, lagMetric, slaCompletionAlertNames } from './sla-completion-alerts.mjs';

export const slaAlertFixtureConfig = Object.freeze({ environment: 'production', instance: 'node-a:8081',
  objectives: [
    { target: 'task', timeBasis: 'working_time', thresholdSeconds: 60, minimumSamples: 20 },
    { target: 'task', timeBasis: 'natural_time', thresholdSeconds: 3600, minimumSamples: 20 },
  ] });
const labels = { job: 'approval-platform', instance: 'node-a:8081', environment: 'production', sla_lag_monitor: 'enabled' };
const duration = minute => `${minute}m`;
const samples = callback => Array.from({ length: 91 }, (_, minute) => String(callback(minute))).join(' ');
const labelText = values => Object.entries(values).map(([key, value]) => `${key}=${JSON.stringify(value)}`).join(',');
const row = (name, tags, values) => ({ series: `${name}{${labelText(tags)}}`, values });

export function buildSlaCompletionAlertFixtures(ruleFile, document) {
  assert.deepEqual(document, buildSlaCompletionRules(slaAlertFixtureConfig));
  const rules = document.groups[0].rules;
  const annotations = rule => Object.fromEntries(Object.entries(rule.annotations).map(([key, value]) =>
    [key, value.replaceAll('{{ $labels.instance }}', labels.instance)
      .replaceAll('{{ $labels.environment }}', labels.environment)]));
  const counterTags = { ...labels, application: 'approval-platform' };
  const series = ({ scope = {}, up = samples(() => 1), drops = samples(() => 0), basis = 'working_time',
    source = 'original', target = 'task', mode = 'high', countOverride, infOverride, app = 'approval-platform' } = {}) => {
    const selected = { ...labels, ...scope };
    const common = { ...selected, application: app };
    const result = [];
    if (up !== null) result.push(row('up', selected, up));
    if (drops !== null) result.push(row(droppedMetric, common, drops));
    if (mode === 'unused') return result;
    const tags = { ...common, target, source, time_basis: basis };
    const total = minute => mode === 'low-volume' ? Math.floor(minute / 10)
      : mode === 'idle' ? 10 : mode === 'reset' && minute >= 35 ? minute - 35
      : mode === 'recovery' && minute > 30 ? 30 + (minute - 30) * 10 : minute;
    const slow = mode !== 'fast';
    const first = minute => !slow ? total(minute) : mode === 'recovery' && minute > 30 ? (minute - 30) * 10 : 0;
    const mid = minute => basis === 'natural_time' && slow ? first(minute) : total(minute);
    const count = countOverride === undefined ? samples(total) : countOverride;
    if (count !== null) result.push(row(lagMetric + '_count', tags, count));
    result.push(row(lagMetric + '_bucket', { ...tags, le: '1' }, samples(first)),
      row(lagMetric + '_bucket', { ...tags, le: '120' }, samples(mid)),
      row(lagMetric + '_bucket', { ...tags, le: '7200' }, samples(total)));
    const infinite = infOverride === undefined ? samples(total) : infOverride;
    if (infinite !== null) result.push(row(lagMetric + '_bucket', { ...tags, le: '+Inf' }, infinite));
    return result;
  };
  const tests = [];
  function scenario(name, input, checks) {
    const group = { name, interval: '1m', input_series: input, alert_rule_test: [], promql_expr_test: [] };
    for (const { minute, high = [], dropped = false, unavailable = false } of checks) {
      const alerts = high.map(basis => {
        const rule = rules[basis === 'working_time' ? 0 : 1];
        return { exp_labels: { ...counterTags, target: 'task', source: 'original', time_basis: basis, ...rule.labels },
          exp_annotations: annotations(rule) };
      });
      for (const [index, expected] of [[0, alerts], [1, dropped ? [{ exp_labels: { ...counterTags, ...rules[2].labels },
        exp_annotations: annotations(rules[2]) }] : []], [2, unavailable ? [{ exp_labels: { ...labels, ...rules[3].labels },
        exp_annotations: annotations(rules[3]) }] : []]]) {
        group.alert_rule_test.push({ eval_time: duration(minute), alertname: slaCompletionAlertNames[index], exp_alerts: expected });
      }
    }
    tests.push(group);
  }
  scenario('working P95 requires sample floor then original five minute hold', series(),
    [{ minute: 19 }, { minute: 24 }, { minute: 26, high: ['working_time'] }, { minute: 31, high: ['working_time'] }]);
  scenario('natural-time objective is independently configured', series({ basis: 'natural_time' }),
    [{ minute: 24 }, { minute: 26, high: ['natural_time'] }]);
  const both = [...series(), ...series({ basis: 'natural_time', up: null, drops: null })];
  scenario('both time bases fire separately, never added or averaged', both,
    [{ minute: 31, high: ['working_time', 'natural_time'] }]);
  scenario('new low-lag completions clear a previously high distribution', series({ mode: 'recovery' }),
    [{ minute: 31, high: ['working_time'] }, { minute: 70 }]);
  for (const mode of ['unused', 'idle', 'fast', 'low-volume']) {
    scenario(`${mode} does not manufacture latency or missing-monitor alerts`, series({ mode }), [{ minute: 31 }, { minute: 70 }]);
  }
  scenario('governed replay history is excluded from original-action objectives', series({ source: 'replay' }), [{ minute: 31 }]);
  scenario('task objective does not consume process completion samples', series({ target: 'process' }), [{ minute: 31 }]);
  scenario('counter resets are not lost observations and do not defeat latency detection',
    series({ mode: 'reset', drops: samples(minute => minute < 35 ? 4 : 0) }),
    [{ minute: 31, high: ['working_time'] }, { minute: 60, high: ['working_time'] }]);
  scenario('dropped observations suppress P95 and restoration restarts its hold',
    series({ drops: samples(minute => minute < 35 ? 0 : 1) }),
    [{ minute: 31, high: ['working_time'] }, { minute: 35 }, { minute: 38, dropped: true },
      { minute: 66 }, { minute: 71, high: ['working_time'] }]);
  for (const [name, drops] of [['missing', null], ['NaN', samples(() => 'NaN')],
    ['infinite', samples(() => '+Inf')], ['negative', samples(() => -1)]]) {
    scenario(`${name} observer counter means unavailable, not healthy zero`, series({ drops }),
      [{ minute: 1 }, { minute: 2, unavailable: true }, { minute: 31, unavailable: true }]);
  }
  scenario('stale observer counter invalidates retained historic timing',
    series({ drops: samples(minute => minute < 35 ? 0 : minute === 35 ? 'stale' : '_') }),
    [{ minute: 31, high: ['working_time'] }, { minute: 35 }, { minute: 38, unavailable: true }]);
  scenario('another environment cannot hide a missing observer', [...series({ drops: null }),
    ...series({ scope: { environment: 'test' } })], [{ minute: 31, unavailable: true }]);
  scenario('another environment cannot dilute a high local distribution', [...series(),
    ...series({ scope: { environment: 'test' }, mode: 'fast' })], [{ minute: 31, high: ['working_time'] }]);
  scenario('another environment cannot supply the minimum sample floor', [...series({ mode: 'low-volume' }),
    ...series({ scope: { environment: 'test' } })], [{ minute: 31 }]);
  scenario('foreign application cannot supply observer health', series({ app: 'other-application' }),
    [{ minute: 31, unavailable: true }]);
  for (const up of [samples(() => 0), null]) {
    scenario(up === null ? 'removed targets use existing availability rules' : 'down targets cannot borrow foreign reachability',
      [...series({ up }), ...series({ scope: { environment: 'test' } })], [{ minute: 31 }]);
  }
  scenario('monitor expectation remains explicitly opt-in', series({ scope: { sla_lag_monitor: 'disabled' } }), [{ minute: 31 }]);
  for (const countOverride of [null, samples(() => 'NaN'), samples(() => '+Inf'), samples(() => -1)]) {
    scenario(`absent or invalid denominator ${String(countOverride).slice(0, 8)} cannot raise P95`,
      series({ countOverride }), [{ minute: 31 }]);
  }
  for (const infOverride of [null, samples(() => 0)]) {
    scenario(infOverride === null ? 'missing infinity bucket cannot raise P95' : 'infinity bucket must agree with current count',
      series({ infOverride }), [{ minute: 31 }]);
  }
  for (const group of tests) assert.equal(new Set(group.input_series.map(value => value.series)).size, group.input_series.length);
  return { rule_files: [ruleFile], evaluation_interval: '1m', tests };
}

/** Uses the already pinned native tool. Injected unit runners return data but never log acceptance. */
export function verifySlaCompletionAlerts({ directory, promtool, runCommand }) {
  assert.ok(isAbsolute(promtool), 'SLA_PROMTOOL_ABSOLUTE');
  assert.ok(lstatSync(promtool).isFile() && !lstatSync(promtool).isSymbolicLink()
    && realpathSync(promtool) === promtool, 'SLA_PROMTOOL_FILE');
  const ruleFile = resolve(directory, 'sla-completion-generated.rules.yml');
  const fixtureFile = resolve(directory, 'sla-completion-generated.test.yml');
  const document = buildSlaCompletionRules(slaAlertFixtureConfig);
  const fixtures = buildSlaCompletionAlertFixtures(ruleFile, document);
  const ruleBytes = JSON.stringify(document, null, 2) + '\n';
  const fixtureBytes = JSON.stringify(fixtures, null, 2) + '\n';
  writeFileSync(ruleFile, ruleBytes, { flag: 'wx', mode: 0o600 });
  writeFileSync(fixtureFile, fixtureBytes, { flag: 'wx', mode: 0o600 });
  runCommand(promtool, ['check', 'rules', ruleFile], directory, 30000);
  runCommand(promtool, ['test', 'rules', fixtureFile], directory, 60000);
  const hash = bytes => createHash('sha256').update(bytes).digest('hex');
  return { status: 'OPS_SLA_COMPLETION_ALERTS_VERIFIED', ruleSha256: hash(ruleBytes), fixtureSha256: hash(fixtureBytes),
    generatorSha256: hash(readFileSync(fileURLToPath(new URL('./sla-completion-alerts.mjs', import.meta.url)))),
    scenarios: fixtures.tests.length, alertAssertions: fixtures.tests.reduce((n, value) => n + value.alert_rule_test.length, 0),
    deployed: false, humanNotificationVerified: false, liveMetricScrapeVerified: false };
}
