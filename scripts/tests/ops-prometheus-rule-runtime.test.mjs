import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, symlinkSync, truncateSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { promtoolPin, provisionAndVerifyPrometheusRules, runPromtoolChecks,
  verifyArchiveDigest } from '../ops/verify-prometheus-rules.mjs';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const dir = resolve(root, 'deploy/observability/prometheus');
const rules = readFileSync(resolve(dir, 'approval-platform.rules.yml'), 'utf8');
// JSON is valid YAML. These checks inventory cases; only promtool evaluates PromQL.
const fixture = JSON.parse(readFileSync(resolve(dir, 'approval-platform.rules.regression.test.yml'), 'utf8'));
const expected = ['ApprovalPlatformDown', 'ApprovalPlatformScrapeMissing',
  'ApprovalPlatformHighHttp5xxRatio', 'ApprovalPlatformHighHttpP95Latency',
  'ApprovalWorkflowOverdueDetected', 'ApprovalWorkflowSlaActionDead',
  'ApprovalWorkflowSlaActionRetryStorm', 'ApprovalConnectorFailureRatio', 'ApprovalConnectorTimeoutDetected'];
const assertions = fixture.tests.flatMap(group => group.alert_rule_test);
const sha = bytes => createHash('sha256').update(bytes).digest('hex');

test('runtime inventory includes the existing eight alerts plus complete scrape disappearance', () => {
  const names = [...rules.matchAll(/^      - alert: (\w+)$/gmu)].map(match => match[1]);
  assert.deepEqual(names.sort(), [...expected].sort());
  assert.match(rules, /expr: absent\(up\{job="approval-platform"\}\)/u);
  assert.equal(new Set(fixture.tests.map(group => group.name)).size, fixture.tests.length);
  assert.deepEqual(fixture.rule_files, ['approval-platform.rules.yml']);
  assert.equal(fixture.evaluation_interval, '1m');
});
for (const alert of expected) {
  test(`${alert} has firing and non-firing cases with exact diagnostic labels and annotations`, () => {
    const cases = assertions.filter(value => value.alertname === alert);
    assert.ok(cases.some(value => value.exp_alerts.length > 0));
    assert.ok(cases.some(value => value.exp_alerts.length === 0));
    for (const value of cases.flatMap(value => value.exp_alerts)) {
      assert.equal(value.exp_labels.owner, 'approval-platform');
      assert.ok(['warning', 'critical'].includes(value.exp_labels.severity));
      assert.deepEqual(Object.keys(value.exp_annotations).sort(), ['description', 'runbook_url', 'summary']);
      assert.ok(value.exp_annotations.runbook_url.endsWith('#' + alert.toLowerCase()));
      assert.doesNotMatch(JSON.stringify(value), /tenant_id|task_id|process_instance_id|request_id|business_key|trace_id|span_id/u);
    }
  });
}
test('coverage includes hold periods, recovery, absent targets, low traffic and counter resets', () => {
  assert.equal(fixture.tests.length, 14); assert.equal(assertions.length, 48);
  for (const fragment of ['counter reset', 'no-traffic', 'another job', 'traffic floors', 'absent']) {
    assert.ok(fixture.tests.some(group => group.name.includes(fragment)), fragment);
  }
  const source = readFileSync(resolve(root, 'scripts/tests/ops-observability-boundary.test.mjs'), 'utf8');
  assert.match(source, /import '\.\/ops-prometheus-rule-runtime\.test\.mjs'/u);
});

function sandbox(t) {
  const directory = mkdtempSync(resolve(tmpdir(), 'promtool-unit-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const tool = resolve(directory, 'promtool'); writeFileSync(tool, 'unit fixture: never executed\n');
  return { directory, tool };
}
function engineFixture(tool, failAt = -1, version = promtoolPin.version) {
  const calls = []; const logs = [];
  const run = (executable, args, options) => {
    assert.equal(executable, tool); calls.push({ args, options });
    if (calls.length === failAt) return { status: 1, stdout: 'rule evaluation failed', stderr: '' };
    return { status: 0, stdout: calls.length === 1 ? `promtool, version ${version}\n` : 'SUCCESS\n', stderr: '' };
  };
  return { calls, logs, options: { repositoryRoot: root, run, log: value => logs.push(value) } };
}

test('launcher fixture invokes version, real-rule parser and both rule-test files in order', t => {
  const { tool } = sandbox(t); const f = engineFixture(tool);
  const result = runPromtoolChecks(tool, f.options);
  assert.deepEqual(f.calls.map(call => call.args), [['--version'], ['check', 'rules', 'approval-platform.rules.yml'],
    ['test', 'rules', 'approval-platform.rules.test.yml', 'approval-platform.rules.regression.test.yml']]);
  assert.equal(result.status, 'OPS_PROMETHEUS_RULES_VERIFIED');
  assert.equal(result.liveScrapeVerified, false); assert.equal(result.notificationDeliveryVerified, false);
  for (const input of result.inputs) assert.equal(input.sha256, sha(readFileSync(resolve(dir, input.file))));
  for (const call of f.calls) {
    assert.equal(call.options.shell, false); assert.ok(call.options.timeout > 0 && call.options.timeout <= 60000);
    assert.deepEqual(Object.keys(call.options.env).sort(), ['LANG', 'LC_ALL', 'PATH']);
    assert.deepEqual(call.options.stdio, ['ignore', 'pipe', 'pipe']);
  }
});
for (const failAt of [1, 2, 3]) {
  test(`launcher fixture stops on failing command ${failAt}, never retries or emits success`, t => {
    const { tool } = sandbox(t); const f = engineFixture(tool, failAt);
    assert.throws(() => runPromtoolChecks(tool, f.options), /PROMTOOL_COMMAND_FAILED/u);
    assert.equal(f.calls.length, failAt);
    assert.equal(f.logs.some(log => log.includes('OPS_PROMETHEUS_RULES_VERIFIED')), false);
  });
}
test('wrong tool version, process errors and missing executable fail closed', t => {
  const { tool, directory } = sandbox(t); const f = engineFixture(tool, -1, '0.0.0');
  assert.throws(() => runPromtoolChecks(tool, f.options), /VERSION_MISMATCH/u); assert.equal(f.calls.length, 1);
  assert.throws(() => runPromtoolChecks(tool, { run: () => ({ status: null, error: { code: 'ETIMEDOUT' } }) }), /ETIMEDOUT/u);
  assert.throws(() => runPromtoolChecks('promtool'), /ABSOLUTE_PATH/u);
  assert.throws(() => runPromtoolChecks(resolve(directory, 'missing')));
  const link = resolve(directory, 'link'); symlinkSync(tool, link);
  assert.throws(() => runPromtoolChecks(link), /EXECUTABLE_REJECTED/u);
});
test('archive digest and file-kind checks are performed on real bytes, not reported download text', t => {
  const { directory } = sandbox(t); const file = resolve(directory, 'archive');
  const bytes = Buffer.from('cryptographic verification fixture'); writeFileSync(file, bytes);
  verifyArchiveDigest(file, sha(bytes));
  assert.throws(() => verifyArchiveDigest(file, '0'.repeat(64)), /DIGEST_MISMATCH/u);
  assert.throws(() => verifyArchiveDigest(file, 'invalid'), /DIGEST_REQUIRED/u);
  const link = resolve(directory, 'alias'); symlinkSync(file, link);
  assert.throws(() => verifyArchiveDigest(link, sha(bytes)), /ARCHIVE_REJECTED/u);
  writeFileSync(file, ''); assert.throws(() => verifyArchiveDigest(file, sha(bytes)), /ARCHIVE_REJECTED/u);
  truncateSync(file, 120 * 1024 * 1024 + 1);
  assert.throws(() => verifyArchiveDigest(file, sha(bytes)), /ARCHIVE_REJECTED/u);
});
test('CI uses the fixed upstream digest before extraction, bounded downloads, and owned cleanup', () => {
  assert.equal(promtoolPin.version, '3.13.3');
  assert.equal(promtoolPin.sha256, 'b349c732d8a853e657d0e7ae1bbad4d11b586615fb65fdc59d896b9f869c001e');
  const source = readFileSync(resolve(root, 'scripts/ops/verify-prometheus-rules.mjs'), 'utf8');
  assert.ok(source.indexOf('verifyArchiveDigest(archive, promtoolPin.sha256)') < source.indexOf("command(spawnSync, 'tar'"));
  assert.match(source, /'--proto-redir', '=https'/u); assert.match(source, /'--max-filesize'/u);
  assert.doesNotMatch(source, /--retry|--insecure|shell: true|continue-on-error|exec\(/u);
  assert.match(source, /finally \{\s+rmSync\(directory, \{ recursive: true, force: true \}\)/u);
});

test('actual pinned promtool parses and evaluates all original and regression fixtures', {
  timeout: 360000,
  skip: process.env.GITHUB_ACTIONS !== 'true' ? 'real promtool provisioning executes in GitHub CI; local launcher tests use fixtures' : false,
}, () => {
  assert.equal(provisionAndVerifyPrometheusRules().status, 'OPS_PROMETHEUS_RULES_VERIFIED');
});
