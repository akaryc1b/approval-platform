import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();

async function text(relativePath) {
  return readFile(path.join(root, relativePath), 'utf8');
}

test('Prometheus and OTLP runtime dependencies are explicit and fail isolated', async () => {
  const pom = await text('apps/server/pom.xml');
  const application = await text('apps/server/src/main/resources/application.yml');
  const profile = await text(
    'apps/server/src/main/resources/application-observability.yml',
  );

  assert.match(pom, /<artifactId>micrometer-registry-prometheus<\/artifactId>/);
  assert.match(pom, /<artifactId>spring-boot-starter-opentelemetry<\/artifactId>/);

  assert.match(application, /include: health,info,prometheus/);
  for (const sensitiveEndpoint of ['env', 'configprops', 'heapdump', 'threaddump']) {
    assert.doesNotMatch(
      application,
      new RegExp(`include:[^\\n]*\\b${sensitiveEndpoint}\\b`),
    );
  }
  assert.match(
    application,
    /enabled: \$\{APPROVAL_OTLP_TRACING_ENABLED:false\}/,
  );
  assert.match(application, /produce: W3C/);
  assert.match(application, /consume: W3C,B3,B3_MULTI/);
  assert.match(application, /connect-timeout: 2s/);
  assert.match(application, /timeout: 5s/);

  assert.match(profile, /on-profile: observability/);
  assert.match(profile, /port: \$\{APPROVAL_MANAGEMENT_PORT:8081\}/);
  assert.match(profile, /address: \$\{APPROVAL_MANAGEMENT_ADDRESS:127\.0\.0\.1\}/);
  assert.match(profile, /format:\s*\n\s+console: \$\{APPROVAL_STRUCTURED_LOG_FORMAT:ecs\}/);
});

test('approval metrics reject identifier and payload dimensions', async () => {
  const guard = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/config/ApprovalObservabilityConfiguration.java',
  );
  assert.match(guard, /APPROVAL_METRIC_PREFIX = "approval\."/);
  for (const forbidden of [
    'tenant_id',
    'user_id',
    'process_instance_id',
    'task_id',
    'request_id',
    'business_key',
    'idempotency_key',
    'trace_id',
    'span_id',
    'exception',
    'error_message',
    'payload',
  ]) {
    assert.match(guard, new RegExp(`"${forbidden}"`));
  }
  assert.match(guard, /MeterFilterReply\.DENY/);
});

test('alert rules have owners severities runbooks and bounded traffic floors', async () => {
  const rules = await text(
    'deploy/observability/prometheus/approval-platform.rules.yml',
  );
  const expectedAlerts = [
    'ApprovalPlatformDown',
    'ApprovalPlatformHighHttp5xxRatio',
    'ApprovalPlatformHighHttpP95Latency',
    'ApprovalWorkflowOverdueDetected',
    'ApprovalWorkflowSlaActionDead',
    'ApprovalWorkflowSlaActionRetryStorm',
    'ApprovalConnectorFailureRatio',
    'ApprovalConnectorTimeoutDetected',
  ];

  for (const alert of expectedAlerts) {
    const start = rules.indexOf(`- alert: ${alert}`);
    assert.notEqual(start, -1, `missing alert ${alert}`);
    const next = rules.indexOf('\n      - alert:', start + 1);
    const block = rules.slice(start, next === -1 ? rules.length : next);
    assert.match(block, /severity: (warning|critical)/);
    assert.match(block, /owner: approval-platform/);
    assert.match(block, /runbook_url: https:\/\/github\.com\/akaryc1b\/approval-platform/);
  }

  assert.match(rules, /rate\(http_server_requests_seconds_count[\s\S]*\) > 0\.1/);
  assert.match(rules, /rate\(approval_connector_invocation_event_total\[5m\]\)[\s\S]*> 0\.05/);
  assert.match(rules, /action="overdue"/);
  assert.match(rules, /result="dead"/);
  assert.match(rules, /failure="transport_timeout"/);
  assert.doesNotMatch(
    rules,
    /tenant_id|user_id|process_instance_id|task_id|request_id|business_key|trace_id|span_id/,
  );
});

test('Prometheus rule fixtures prove firing and resolution paths', async () => {
  const fixture = await text(
    'deploy/observability/prometheus/approval-platform.rules.test.yml',
  );
  assert.match(fixture, /alertname: ApprovalPlatformDown/);
  assert.match(fixture, /eval_time: 3m[\s\S]*exp_alerts:/);
  assert.match(
    fixture,
    /eval_time: 4m\s+alertname: ApprovalPlatformDown\s+exp_alerts: \[\]/,
  );
  assert.match(fixture, /alertname: ApprovalWorkflowSlaActionDead/);
  assert.match(fixture, /alertname: ApprovalConnectorTimeoutDetected/);
});

test('trace logs preserve business correlation beside telemetry identifiers', async () => {
  const provider = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/config/MdcApprovalRequestEvidenceProvider.java',
  );
  const filter = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/config/ApprovalTraceLogCorrelationFilter.java',
  );

  assert.match(provider, /APPROVAL_TRACE_ID_MDC_KEY = "approvalTraceId"/);
  assert.match(provider, /optionalMdc\("traceId"\)/);
  assert.match(filter, /MDC\.put\(TRACE_ID_MDC_KEY, context\.traceId\(\)\)/);
  assert.match(filter, /MDC\.put\(SPAN_ID_MDC_KEY, context\.spanId\(\)\)/);
  assert.match(filter, /restore\(TRACE_ID_MDC_KEY, previousTraceId\)/);
  assert.match(filter, /restore\(SPAN_ID_MDC_KEY, previousSpanId\)/);
});

test('operations documentation separates operator and business notifications', async () => {
  const guide = await text('docs/operations/observability.md');
  const runbook = await text(
    'docs/operations/runbooks/approval-platform-alerts.md',
  );
  const alertmanager = await text(
    'deploy/observability/alertmanager/alertmanager.yml',
  );

  assert.match(guide, /Prometheus is the alerting source of truth/);
  assert.match(guide, /SkyWalking is the distributed-trace backend/);
  assert.match(guide, /External notification must never execute inside the transaction/);
  assert.match(guide, /must not be inferred as complete/);
  assert.match(runbook, /Do not repair incidents by writing directly to Flowable `ACT_\*` tables/);
  assert.match(runbook, /distinct side effects with distinct idempotency keys/);
  assert.match(alertmanager, /send_resolved: true/);
  assert.match(alertmanager, /group_wait: 30s/);
});
