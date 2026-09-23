# Approval Platform observability

## Status and boundary

This document defines the supported observability path for the executable server.
The foundation exposes Prometheus metrics, exports OpenTelemetry traces through OTLP,
and emits ECS JSON logs with trace correlation. It deliberately reuses the existing SLA
worker, connector diagnostics, operational-failure store and durable notification path.

The following work remains tracked by Issue #146 and must not be inferred as complete
from the foundation alone:

- process start, completion, failure and duration meters across every engine entry point;
- active-process, overdue-process, failed-job, Outbox and notification backlog gauges;
- durable `PROCESS_TIMEOUT_DETECTED` and `PROCESS_EXECUTION_FAILED` events for every
  terminal engine path;
- trace-context persistence and linking across all Outbox and Connector retries;
- a production notification gateway implementation and environment-specific receiver.

## Supported topology

```text
approval-server management port
  -> /actuator/prometheus
  -> Prometheus
  -> Alertmanager
  -> approval-ops-notifier

approval-server observations
  -> W3C trace context
  -> OTLP/HTTP
  -> SkyWalking OAP
  -> SkyWalking UI / Lens

approval-server stdout
  -> ECS JSON
  -> Filebeat or another stdout collector
  -> Logstash / Elasticsearch
  -> Kibana
```

Prometheus is the alerting source of truth. SkyWalking is the distributed-trace backend.
Elasticsearch retains detailed structured diagnostic events. They are complementary and
must not be used as interchangeable stores.

## Runtime activation

The default application configuration keeps OTLP export disabled. Activate production
observability explicitly:

```bash
export SPRING_PROFILES_ACTIVE=observability
export APPROVAL_ENVIRONMENT=production
export APPROVAL_SERVICE_VERSION=<immutable-release-version>
export APPROVAL_MANAGEMENT_PORT=8081
export APPROVAL_MANAGEMENT_ADDRESS=0.0.0.0
export APPROVAL_TRACING_SAMPLING_PROBABILITY=0.10
export APPROVAL_OTLP_TRACES_ENDPOINT=http://skywalking-oap:12800/v1/traces
```

`APPROVAL_MANAGEMENT_ADDRESS=0.0.0.0` is allowed only when port 8081 is protected by a
private service network, NetworkPolicy, security group or equivalent control. Do not
publish the management port through the public ingress. Only `health`, `info`, `metrics`
and `prometheus` are exposed; `env`, `configprops`, `heapdump` and similar endpoints remain
closed.

The OTLP exporter has bounded connect and request timeouts. A collector outage may drop
telemetry but must never fail or roll back an approval transaction.

## Prometheus

Versioned deployment assets are under `deploy/observability/prometheus`:

- `prometheus.yml` scrapes the dedicated management surface;
- `approval-platform.rules.yml` contains actionable alerts;
- `approval-platform.rules.test.yml` proves representative firing and recovery paths.

Validate before applying:

```bash
promtool check config deploy/observability/prometheus/prometheus.yml
promtool check rules deploy/observability/prometheus/approval-platform.rules.yml
(
  cd deploy/observability/prometheus
  promtool test rules approval-platform.rules.test.yml
)
```

### Current custom metrics

`approval_sla_execution_worker_total`

- `action`: bounded enum such as `overdue` or `reminder`;
- `result`: bounded enum such as `succeeded`, `retry_scheduled`, `dead` or
  `persistence_conflict`;
- `failure_class`: bounded failure classification.

`approval_connector_invocation_event_total`

- `provider`, `operation`, `outcome`, `failure` and `duration` are bounded enums;
- raw provider errors and remote payloads are never metric labels.

The Prometheus registry also publishes standard JVM, process, system, datasource and HTTP
server meters. HTTP request histograms have explicit latency objectives at 100 ms, 250 ms,
500 ms, 1 s, 2 s and 5 s.

### Cardinality contract

Approval-owned meters start with `approval.`. The runtime rejects meters that attach any
of these dimensions:

- tenant, user, process-instance, task, request or business identifiers;
- idempotency keys, trace IDs or span IDs;
- exception text, error messages or payloads.

Stable process-definition keys, bounded outcomes, node types, connector keys and error
categories are allowed. High-cardinality identifiers belong in traces and logs, not
metrics.

## SkyWalking through OpenTelemetry

The server uses the vendor-neutral Spring Boot OpenTelemetry starter and produces W3C
trace context. It also accepts W3C, B3 single-header and B3 multi-header context for
migration compatibility.

Configure SkyWalking OAP to enable its OpenTelemetry receiver and `otlp-traces` handler.
For OTLP/HTTP, OAP accepts trace exports on port 12800 at `/v1/traces`. Keep OAP behind a
private network and add TLS or an authenticated collector hop when traces cross a trust
boundary.

HTTP server spans are auto-instrumented. The approval log-correlation filter keeps two
separate concepts:

- `traceId` and `spanId`: the active OpenTelemetry span identifiers used to navigate to
  SkyWalking;
- `approvalTraceId`: the pre-existing approval business correlation value supplied by
  `X-Trace-Id` and retained for durable evidence compatibility.

Do not copy `approvalTraceId` into metric labels. Complete Outbox retry linking and
Connector child-span coverage are follow-up acceptance items under Issue #146.

## ELK-compatible structured logging

The `observability` profile switches console output to ECS JSON. A stdout collector can
ship these events to Logstash or Elasticsearch without application-side network calls.
The application must not write directly to Elasticsearch from an approval transaction.

Recommended indexed fields include:

- `service.name`, `service.version`, `service.environment` and `service.node.name`;
- `traceId`, `spanId` and `approvalTraceId`;
- `requestId`, `operatorId`, logger, log level and stable error category.

Never log authorization headers, credentials, access tokens, form values, approval
comments, attachment bodies, provider secrets or complete remote request/response
payloads. Apply collector-side retention and access control according to the data
classification of the deployment.

A typical incident pivot is:

```text
Prometheus alert
  -> stable labels and time range
  -> ECS log query by service/environment
  -> traceId
  -> SkyWalking trace
  -> approvalTraceId/requestId for durable business evidence
```

## Timeout and failure semantics

An approval is overdue only when an explicit SLA deadline says it is overdue. Wall-clock
process age by itself is not authority. Process definitions should use engine timers when
the timeout changes process behavior; the SLA execution worker is the safety-net and
notification path for configured deadlines.

Current alerts distinguish:

- an overdue action was detected;
- an SLA action permanently reached `dead`;
- SLA dispatch is repeatedly retrying;
- a Connector transport timeout or elevated Connector failure ratio occurred.

The business notification path remains asynchronous and idempotent. External notification
must never execute inside the transaction that mutates approval state. Alertmanager is for
operator notifications; business-user timeout/failure notifications remain durable domain
or Outbox events.

## Alertmanager

`deploy/observability/alertmanager/alertmanager.yml` defines grouping, repeat intervals and
resolved notifications. The committed receiver is a service contract:

```text
POST http://approval-ops-notifier:8080/api/v1/alerts
```

Production deployment must provide that internal adapter or replace the receiver with the
organization's approved PagerDuty, email, DingTalk, Slack, Teams or webhook integration.
Credentials must come from files or the platform secret store and must not be committed.
The receiver must deduplicate on Alertmanager fingerprint plus status and handle retries
idempotently.

Validate the final environment-specific configuration before rollout:

```bash
amtool check-config deploy/observability/alertmanager/alertmanager.yml
```

## Smoke verification

After the server and private management service are running:

```bash
curl --fail --silent http://127.0.0.1:8081/actuator/health
curl --fail --silent http://127.0.0.1:8081/actuator/prometheus \
  | grep -E '^(jvm_|http_server_requests_seconds|approval_)'
```

Then execute one deterministic successful request, one configured SLA-overdue scenario and
one injected Connector timeout. Verify:

1. expected low-cardinality counters change and no identifiers appear as labels;
2. the overdue/timeout alert fires, reaches the receiver once per Alertmanager group and
   later emits a resolved notification;
3. ECS logs contain `traceId` and `spanId` but no secrets or form payloads;
4. the matching trace is searchable in SkyWalking;
5. stopping Prometheus, OAP or the log collector does not alter the approval result.

Detailed diagnosis and recovery steps are in
`docs/operations/runbooks/approval-platform-alerts.md`.
