import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';
import { buildEngineJobRuleFixtures } from './engine-job-alert-fixtures.mjs';

/** Reuses the existing digest/version-checked native tool; never downloads or retries. */
export function verifyEngineJobAlerts({ directory, repositoryRoot, promtool, runCommand }) {
  assert.ok(isAbsolute(promtool), 'ENGINE_JOB_TOOL_ABSOLUTE_REQUIRED');
  const stat = lstatSync(promtool);
  assert.ok(stat.isFile() && !stat.isSymbolicLink() && realpathSync(promtool) === promtool, 'ENGINE_JOB_TOOL_FILE_REQUIRED');
  const ruleFile = resolve(repositoryRoot, 'deploy/observability/prometheus/approval-engine-jobs.rules.yml');
  const bytes = readFileSync(ruleFile);
  const fixture = buildEngineJobRuleFixtures(ruleFile, JSON.parse(bytes));
  const content = JSON.stringify(fixture);
  const fixtureFile = resolve(directory, 'engine-job-rules.test.yml');
  writeFileSync(fixtureFile, content, { mode: 0o600, flag: 'wx' });
  runCommand(promtool, ['check', 'rules', ruleFile], directory, 30000);
  runCommand(promtool, ['test', 'rules', fixtureFile], directory, 60000);
  const hash = value => createHash('sha256').update(value).digest('hex');
  return { status: 'OPS_ENGINE_JOB_RULES_VERIFIED', ruleSha256: hash(bytes), fixtureSha256: hash(content),
    ruleTestGroups: fixture.tests.length,
    alertAssertions: fixture.tests.reduce((n, group) => n + group.alert_rule_test.length, 0),
    expressionAssertions: fixture.tests.reduce((n, group) => n + group.promql_expr_test.length, 0),
    liveScrapeVerified: false, notificationDeliveryVerified: false, processFailureEventVerified: false };
}
