# Approval Platform alert runbook

## Operating rules

1. Acknowledge the alert and record its fingerprint, first-seen time, environment and
   affected instance or bounded business dimension.
2. Do not paste credentials, approval form values, comments, attachments or raw Connector
   payloads into incident channels.
3. Use Prometheus labels to choose the time range, ECS logs to locate a `traceId`, and
   SkyWalking to inspect causality. Use `approvalTraceId` or `requestId` only for durable
   business-evidence lookup.
4. Do not repair incidents by writing directly to Flowable `ACT_*` tables, Outbox tables or
   operational-failure tables. Use supported application recovery controls.
5. Do not silence a critical alert before a named owner, impact statement and bounded
   silence expiry are recorded.
6. A telemetry backend outage is not permission to stop approval traffic unless business
   behavior or data integrity is also affected.

## ApprovalPlatformDown

**Meaning:** Prometheus could not scrape an Approval Platform management target for more
than two minutes.

**Triage**

- Check whether only one instance or every instance is missing.
- Confirm the application pod/process is running and the private management service still
  routes to port 8081.
- Query `/actuator/health` from inside the management network.
- Check recent deployment, restart, OOM, readiness, DNS, certificate and NetworkPolicy
  events.
- If the application is healthy but scraping fails, inspect Prometheus target errors and
  management-network policy before restarting the application.

**Mitigation**

- Remove an unhealthy instance from service through the orchestrator and preserve logs.
- Roll back only through the approved release mechanism when a release regression is
  established.
- Restore the Prometheus route independently when approval traffic is healthy.

**Resolved when:** at least two consecutive scrapes succeed, health is ready, and no
unexplained restart loop remains.

## ApprovalPlatformHighHttp5xxRatio

**Meaning:** more than 5% of HTTP requests returned 5xx for ten minutes while request rate
was above the minimum traffic floor.

**Triage**

- Split by instance, URI template, method and exception category; never group by raw URL.
- Compare the first error time with deployments, database events and dependency failures.
- Find representative ECS events and open their `traceId` in SkyWalking.
- Check datasource pool saturation, Flowable failed jobs, Connector failures and Outbox
  health before assuming the controller is the root cause.
- Determine whether errors are deterministic for one operation or systemic.

**Mitigation**

- Disable only the affected optional integration through its governed kill switch when the
  core approval path remains healthy.
- Apply traffic shedding or rollback through approved controls for a confirmed runtime
  regression.
- Preserve failed-operation evidence for replay; do not manually mark work successful.

**Resolved when:** the ratio remains below threshold for ten minutes and a deterministic
success probe passes.

## ApprovalPlatformHighHttpP95Latency

**Meaning:** P95 HTTP latency exceeded two seconds for ten minutes under meaningful load.

**Triage**

- Confirm whether latency is server time or upstream/client wait.
- Compare HTTP histograms with JVM pause, CPU, thread-pool, datasource-pool and database
  metrics.
- Inspect slow representative traces rather than the single slowest request.
- Check Connector timeouts, Flowable async executor backlog and Outbox dispatch latency.
- Verify that high-cardinality identifiers were not introduced into metrics or logs.

**Mitigation**

- Scale only after identifying a saturated bounded resource.
- Isolate a slow optional Connector with its kill switch when safe.
- Avoid increasing timeouts as the first response; that can amplify queueing.

**Resolved when:** P95 remains below the configured service objective for ten minutes and
backlogs are stable or falling.

## ApprovalWorkflowOverdueDetected

**Meaning:** the SLA worker emitted an explicit `overdue` action. This alert is based on a
configured deadline, not merely process age.

**Triage**

- Determine the affected SLA policy and deadline through the supported management view.
- Check whether the action succeeded, scheduled a retry or later reached `dead`.
- Correlate the worker event with durable evidence using `approvalTraceId` or `requestId`.
- Confirm the process is genuinely waiting on a user, timer, engine job or integration.
- Check business calendars and timezone configuration before declaring a false positive.

**Mitigation**

- Allow the idempotent worker to continue when the action succeeded or is safely retrying.
- Use the supported recovery control for a stuck action; do not insert a replacement row.
- Notify the process owner through the durable notification path when policy requires it.

**Resolved when:** the configured overdue action is delivered or explicitly resolved and
no duplicate external notification occurred.

## ApprovalWorkflowSlaActionDead

**Meaning:** an overdue or reminder action exhausted its bounded retry policy and reached a
terminal `dead` result.

**Triage**

- Read the durable operational-failure evidence and stable failure class.
- Verify the action's idempotency key, attempt count and last failure category without
  exposing secret payloads.
- Inspect Connector availability, credentials, routing policy and kill-switch state.
- Confirm whether the underlying approval state changed; a notification failure must not
  be mistaken for an approval failure.

**Mitigation**

- Correct the dependency or policy cause first.
- Replay through the existing governed recovery control using the original idempotency
  identity.
- Escalate immediately when the action represents a legally or contractually required
  timeout notification.

**Resolved when:** recovery records one successful terminal outcome, external side effects
occur exactly once, and the failure record has linked resolution evidence.

## ApprovalWorkflowSlaActionRetryStorm

**Meaning:** at least three SLA actions of one bounded type scheduled retries in fifteen
minutes and the condition persisted for five minutes.

**Triage**

- Compare failure classes and affected Connector operation.
- Check for rate limiting, authentication expiry, network timeouts or a broad downstream
  outage.
- Verify retry backoff and jitter are active; look for synchronized retry waves.
- Check that a process/template rollout did not create an abnormal deadline burst.

**Mitigation**

- Pause the affected optional dispatch path through its governed control when retries are
  harming the downstream service.
- Restore credentials or routing centrally rather than editing individual actions.
- Do not reset attempt counters to hide the condition.

**Resolved when:** retry rate returns below threshold, pending work drains at a bounded
rate, and no duplicate notification is observed.

## ApprovalConnectorFailureRatio

**Meaning:** more than 10% of calls for one provider and operation failed for ten minutes
under meaningful load.

**Triage**

- Break down by the bounded `failure` label and duration bucket.
- Inspect downstream status, routing decisions, credential refresh, rate limits and kill
  switch state.
- Open representative trace IDs and verify whether failure is before transport, during
  transport or in response validation.
- Distinguish policy refusal and malformed response from transport failure.

**Mitigation**

- Activate the existing Connector kill switch for a harmful or non-idempotent downstream
  path.
- Correct credentials or routing without logging secret material.
- Permit Outbox retries only after confirming the operation's idempotency boundary.

**Resolved when:** the ratio stays below threshold for ten minutes and a deterministic
Connector probe succeeds.

## ApprovalConnectorTimeoutDetected

**Meaning:** one or more Connector invocations reached the bounded transport timeout in the
last ten minutes.

**Triage**

- Check provider and operation labels, then inspect duration buckets and related traces.
- Verify DNS, connection establishment, TLS, proxy and downstream response latency.
- Compare timeout configuration with the downstream service objective; do not assume a
  longer timeout is safer.
- Check whether the Outbox dispatcher safely rescheduled the event and retained the same
  idempotency identity.

**Mitigation**

- Isolate an unhealthy optional provider when the core approval flow can continue.
- Restore network/downstream health before replaying a backlog.
- Drain with bounded concurrency and monitor duplicate-side-effect evidence.

**Resolved when:** timeout increments stop, pending events drain, and deterministic probes
complete within the configured bound.

## Notification delivery and resolution

Alertmanager groups alerts and sends both firing and resolved webhook messages to the
approved operations notifier. The notifier must deduplicate on Alertmanager fingerprint
plus alert status, persist delivery attempts, use bounded retry/backoff and retain dead
letter evidence. A chat message alone is not durable incident evidence.

For business-facing approval timeout/failure notices, use the domain/Outbox notification
path instead of Alertmanager. The same incident may produce an operator alert and a
business notice, but those are distinct side effects with distinct idempotency keys.

## Post-incident checks

- Confirm the alert fired at the intended threshold and resolved without manual metric
  manipulation.
- Confirm no high-cardinality or sensitive label was introduced.
- Confirm traces and logs cover the incident window and obey retention policy.
- Add a deterministic regression or rule test before closing a code defect.
- Link the incident, durable failure evidence, fix PR and validation run.
