import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';
import { buildWorkflowPopulationRuleFixtures } from './workflow-population-alert-fixtures.mjs';

const hash = bytes => createHash('sha256').update(bytes).digest('hex');

/** Reuse the already digest/version-checked promtool. No download, process launcher or retry. */
export function verifyWorkflowPopulationAlerts({ directory, repositoryRoot, promtool, runCommand }) {
  assert.ok(isAbsolute(promtool), 'WORKFLOW_PROMTOOL_ABSOLUTE_PATH');
  const stat = lstatSync(promtool);
  assert.ok(stat.isFile() && !stat.isSymbolicLink() && realpathSync(promtool) === promtool,
    'WORKFLOW_PROMTOOL_FILE_REQUIRED');
  const ruleFile = resolve(repositoryRoot, 'deploy/observability/prometheus/approval-workflow-population.rules.yml');
  const ruleBytes = readFileSync(ruleFile);
  const fixture = buildWorkflowPopulationRuleFixtures(ruleFile, JSON.parse(ruleBytes));
  const fixtureBytes = JSON.stringify(fixture);
  const fixtureFile = resolve(directory, 'workflow-population-rules.test.yml');
  writeFileSync(fixtureFile, fixtureBytes, { mode: 0o600, flag: 'wx' });
  runCommand(promtool, ['check', 'rules', ruleFile], directory, 30000);
  runCommand(promtool, ['test', 'rules', fixtureFile], directory, 60000);
  // Injected unit runners also return data. Only the native provisioning entrypoint logs a receipt.
  return {
    status: 'OPS_WORKFLOW_POPULATION_RULES_VERIFIED', ruleSha256: hash(ruleBytes),
    fixtureSha256: hash(fixtureBytes), ruleTestGroups: fixture.tests.length,
    alertAssertions: fixture.tests.reduce((total, group) => total + group.alert_rule_test.length, 0),
    expressionAssertions: fixture.tests.reduce((total, group) => total + group.promql_expr_test.length, 0),
    liveScrapeVerified: false, notificationDeliveryVerified: false, databaseBusinessChainVerified: false,
  };
}
