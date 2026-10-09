# Outbox delivery tracing

## Enable and roll back

Set `APPROVAL_OBSERVABILITY_OUTBOX_TRACING_ENABLED=true` to opt in. It is off by
default; the existing generic connector and dispatcher must already be configured.
This setting reuses the host Micrometer tracer. It does not activate an exporter,
create an executor, open a database connection or send a notification. Configure
OTLP export and sampling separately using the existing observability profile.
Without a tracer, delivery keeps working without new spans or headers.

Disable the setting and restart to roll back instrumentation. No database or
message migration is necessary. The original five-argument dispatcher constructor
and standalone generic-connector configuration remain usable without tracing.

## What is connected

```text
persisted Outbox event UUID
  -> new approval.outbox.dispatch trace (CONSUMER, one per claimed attempt)
     -> approval.connector.callback child span (CLIENT)
        -> signed Generic REST HTTP request with W3C traceparent
```

The callback resolver is decorated at the dispatcher boundary, including the
reserved SLA-notification route. Both callback and organization endpoint resolvers
supply the currently owned span context to their existing transport.

The persisted `ConnectorContext.traceId` is a business correlation value, not a
complete telemetry SpanContext. It remains unchanged in the database and in
`X-Trace-Id`. This implementation does **not** manufacture an upstream span ID or
claim one continuous HTTP-entry-to-Outbox trace across restart. Each retry has a
new trace; correlate attempts using `approval.outbox.event_id`, the original event
UUID. Durable producer SpanContext persistence remains separate work.

## Outcomes and privacy

The dispatcher span reports `delivered`, `rescheduled`, `dead`, `lease_lost`, or
`failed` after the existing repository operation. Connector acknowledgement and
successful durable marking are different: a successful callback followed by lost
lease ownership is `lease_lost`, not `delivered`. An escaping storage failure is
`failed`; it is not an authoritative process-execution-failure event.

Only the event UUID and fixed outcome/error tags are added to spans. No tenant,
user, task, business key, payload, comments, URL, credentials, provider response
text or raw exception is exported by this instrumentation. Event UUID is a trace
attribute, never a Prometheus label. `traceId`, `spanId` and `approvalEventId` MDC
keys are scoped and restored after every callback/attempt, including failures;
unrelated MDC keys and the worker's previous trace context are preserved.

Only W3C trace identity is injected, preserving the sampled flag. While an owned
span is active, configured W3C/B3/Jaeger propagation headers are removed before the
current `traceparent` is added; baggage and tracestate are not forwarded. Outside
an owned attempt, existing header configuration is unchanged. The signed business
body, HMAC algorithm, request ID, `X-Trace-Id`, event ID, idempotency key, timeout,
retry/backoff and lease rules remain unchanged. Trace headers do not grant any
business authority and are not part of the existing HMAC signature.

## Failure isolation and verification

Observer start/outcome/close exceptions cannot change dispatcher results, trigger
a second external call or replace a business exception. The adapter performs no
flush, shutdown or network export call; export remains owned by the configured
host tracer. Custom observer/tracer implementations must remain nonblocking.

`OutboxDispatchObserverTest` runs 28 dependency-free delivery/observer scenarios.
`ApprovalOutboxTracingTest` covers actual OTel/Micrometer parentage, same-worker
isolation, default-off/no-tracer behavior, sampled propagation, retry/lease/error
outcomes, an exporter reporting failure, both endpoint resolvers and a real signed
loopback HTTP callback with unchanged business payload and HMAC verification.
The regular Maven suites discover these tests; no additional workflow is needed.

An in-process test exporter returning failure is not an OTLP collector outage
exercise. The loopback host is not a production recipient. Collector availability,
SkyWalking ingestion, production resource bounds and durable producer-to-consumer
trace continuity require their own verification.
