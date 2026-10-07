#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const lagMetric = 'approval_sla_overdue_completion_lag_seconds';
export const droppedMetric = 'approval_sla_overdue_completion_observations_dropped_total';
export const slaCompletionAlertNames = Object.freeze(['ApprovalSlaOverdueCompletionLagHigh',
  'ApprovalSlaCompletionObservationsDropped', 'ApprovalSlaCompletionMonitoringUnavailable']);
const guide = 'https://github.com/akaryc1b/approval-platform/blob/main/docs/operations/sla-completion-alerts.md#';
const requireValue = (value, message) => { if (!value) throw new Error(message); };
function keys(value, expected) {
  requireValue(value && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).sort().join(',') === [...expected].sort().join(','), 'SLA_ALERT_CONFIG_KEYS');
}

/** One deliberately configured scrape target, with independent objectives; no production defaults. */
export function validateSlaCompletionConfig(input) {
  keys(input, ['environment', 'instance', 'objectives']);
  requireValue(typeof input.environment === 'string' && /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/u.test(input.environment),
    'SLA_ALERT_ENVIRONMENT');
  requireValue(typeof input.instance === 'string' && input.instance.length <= 200
    && /^[A-Za-z0-9_.:[\]-]+$/u.test(input.instance), 'SLA_ALERT_INSTANCE');
  requireValue(Array.isArray(input.objectives) && input.objectives.length > 0 && input.objectives.length <= 6,
    'SLA_ALERT_OBJECTIVES');
  const identities = new Set();
  const objectives = input.objectives.map(objective => {
    keys(objective, ['target', 'timeBasis', 'thresholdSeconds', 'minimumSamples']);
    requireValue(['process', 'task', 'collaboration'].includes(objective.target), 'SLA_ALERT_TARGET');
    requireValue(['natural_time', 'working_time'].includes(objective.timeBasis), 'SLA_ALERT_TIME_BASIS');
    requireValue(Number.isFinite(objective.thresholdSeconds) && objective.thresholdSeconds > 0
      && objective.thresholdSeconds < 604800, 'SLA_ALERT_THRESHOLD');
    requireValue(Number.isSafeInteger(objective.minimumSamples) && objective.minimumSamples > 0
      && objective.minimumSamples <= 1000000, 'SLA_ALERT_MINIMUM_SAMPLES');
    const identity = objective.target + ':' + objective.timeBasis;
    requireValue(!identities.has(identity), 'SLA_ALERT_DUPLICATE_OBJECTIVE'); identities.add(identity);
    return { ...objective };
  });
  return { environment: input.environment, instance: input.instance, objectives };
}

export function buildSlaCompletionRules(input) {
  const config = validateSlaCompletionConfig(input);
  const scope = `job="approval-platform",instance=${JSON.stringify(config.instance)},environment=${JSON.stringify(config.environment)},sla_lag_monitor="enabled"`;
  const metricScope = scope + ',application="approval-platform"';
  const drops = `${droppedMetric}{${metricScope}}`;
  const healthy = `((${drops} >= 0) and (${drops} < +Inf))`;
  const reachable = `(up{${scope}} == 1)`;
  const targetJoin = 'on (job, instance, environment)';
  const timingJoin = 'ignoring (target, source, time_basis)';
  const recentDrops = `(increase(${drops}[30m]) > 0)`;
  const alert = (index, expr, hold, severity, summary, description) => ({
    alert: slaCompletionAlertNames[index], expr, for: hold,
    labels: { severity, owner: 'approval-platform', component: 'sla-completion' },
    annotations: { summary, description, runbook_url: guide + slaCompletionAlertNames[index].toLowerCase() },
  });
  const rules = config.objectives.map(objective => {
    const labels = metricScope + `,target="${objective.target}",source="original",time_basis="${objective.timeBasis}"`;
    const count = `${lagMetric}_count{${labels}}`;
    const histogram = `${lagMetric}_bucket{${labels}}`;
    const infinityBucket = `${lagMetric}_bucket{${labels},le="+Inf"}`;
    const expr = `(histogram_quantile(0.95, rate(${histogram}[30m])) > ${objective.thresholdSeconds})\n`
      + `and (increase(${count}[30m]) >= ${objective.minimumSamples})\n`
      + `and ((${count} >= 0) and (${count} < +Inf))\n`
      + `and ignoring (le) (${infinityBucket} == ignoring (le) ${count})\n`
      + `and ${timingJoin} ${healthy}\n`
      + `and ${targetJoin} ${reachable}\n`
      + `unless ${timingJoin} ${recentDrops}`;
    return alert(0, expr, '5m', 'warning', 'Recorded overdue-action completion P95 exceeds its configured objective',
      `Original ${objective.target} overdue actions on {{ $labels.instance }} in {{ $labels.environment }} exceed ${objective.thresholdSeconds} ${objective.timeBasis} seconds for five minutes, with at least ${objective.minimumSamples} estimated samples in 30 minutes. This is successful action completion lag, not process duration or first timeout detection.`);
  });
  rules.push(alert(1, `${recentDrops}\nand ${healthy}\nand ${targetJoin} ${reachable}`, '2m', 'warning',
    'SLA completion timing observations were dropped',
    'The SLA observer on {{ $labels.instance }} in {{ $labels.environment }} omitted observations in the last 30 minutes. The timing distribution is incomplete; this does not mean an approval or notification failed.'),
  alert(2, `${reachable}\nunless ${targetJoin} ${healthy}`, '2m', 'critical',
    'Expected SLA completion monitoring is unavailable',
    'The reachable target {{ $labels.instance }} in {{ $labels.environment }} has no valid SLA observer counter. Check explicit enablement and registry health. No completed actions alone is not a monitoring failure.'));
  const identity = createHash('sha256').update(config.environment + '\0' + config.instance).digest('hex').slice(0, 16);
  return { groups: [{ name: 'approval-sla-completion-' + identity, rules }] };
}

/** Exclusive creation: do not overwrite deployed rules or follow an occupied output symlink. */
export function writeSlaCompletionRules(input, output) {
  const document = buildSlaCompletionRules(input);
  writeFileSync(output, JSON.stringify(document, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  return document;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2);
    requireValue(args.length === 4 && args[0] === '--config' && args[2] === '--output',
      'usage: sla-completion-alerts.mjs --config config.json --output NEW.rules.yml');
    const stat = lstatSync(args[1]);
    requireValue(stat.isFile() && !stat.isSymbolicLink() && stat.size <= 8192, 'SLA_ALERT_CONFIG_FILE');
    writeSlaCompletionRules(JSON.parse(readFileSync(args[1], 'utf8')), args[3]);
    console.log('SLA completion rules generated; validate and explicitly provision before use.');
  } catch (error) {
    // Never print operator file paths or arbitrary JSON input in CLI diagnostics.
    console.error(error.code === 'EEXIST' ? 'SLA_ALERT_OUTPUT_EXISTS'
      : /^SLA_ALERT_|^usage:/u.test(error.message) ? error.message : 'SLA_ALERT_GENERATION_FAILED');
    process.exitCode = 1;
  }
}
