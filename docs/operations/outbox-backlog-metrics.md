# Outbox backlog observations

This #146 / PR #147 slice measures the existing PostgreSQL `ap_outbox` queue.
It does not change append, claim, retry, delivery, lease, payment or approval behavior.
It is not notification delivery, a durable incident stream, MySQL support or production acceptance.

## Enable the existing private monitoring surface

Set `approval.observability.outbox.enabled=true` (environment:
`APPROVAL_OBSERVABILITY_OUTBOX_ENABLED=true`). It is off by default, including when
only OTLP tracing is enabled. The interval is `approval.observability.outbox.interval-ms`
(default 30000; allowed 5000 through 300000). No new public endpoint is created.
Use the existing private Prometheus management listener; do not expose it to tenants.

The monitor owns one extra read-only Hikari connection at most, not another business
DataSource bean. It reuses resolved `spring.datasource` JDBC credentials and
`spring.datasource.hikari.data-source-properties`, including TLS properties. It
supports explicit PostgreSQL JDBC configuration, not JNDI or custom DataSource-only
configuration. Connection acquisition/validation bounds are 500/250 ms; query timeout
is 2 seconds. PostgreSQL connect/socket/cancel properties are set to 2/3/1 seconds.
These three timeout keys cannot be overridden in the JDBC URL. Host/network/DNS
failure behavior and aggregate-query capacity still require deployment-specific validation.

Polling starts after ApplicationReadyEvent, runs on one owned daemon thread with fixed
delay, and never overlaps. Closing cancels sampling, closes only its private pool and
waits up to five seconds for its executor. Scraping never queries the database or
waits for a poll; a late response cannot reactivate a closed monitor. Default-disabled
operation adds neither a polling thread nor a connection.

## Definitions

One PostgreSQL statement uses database statement time and one statement snapshot:

| Prometheus series | Meaning |
| --- | --- |
| `approval_outbox_pending` | All PENDING rows, including future retry/backoff |
| `approval_outbox_due` | PENDING rows whose available_at has arrived; subset of pending |
| `approval_outbox_in_flight` | All IN_FLIGHT rows, including expired leases |
| `approval_outbox_expired_leases` | IN_FLIGHT rows whose locked_until has expired; subset of in-flight |
| `approval_outbox_dead` | DEAD rows; terminal failures, not retryable backlog |
| `approval_outbox_oldest_unfinished_age_seconds` | Age at snapshot time of oldest PENDING/IN_FLIGHT created_at; zero for an empty unfinished queue |
| `approval_outbox_sample_up` | 1 only for a successful, non-stale observation on a running monitor |
| `approval_outbox_sample_age_seconds` | Monotonic age since the last successful read began; includes query delay |
| `approval_outbox_sample_timestamp_seconds` | Database timestamp of the last successful observation |
| `approval_outbox_sample_errors_total` | Local failed polls; not failed business deliveries |

No payload, tenant, connector, worker, event, task, exception or user value is exported
as a label. Only deployment-wide aggregates are read; these are not a tenant-facing
management API. Delivered history is excluded. Counts do not depend on process-local
lifecycle counters, and due/expired subsets must not be added to their parent totals.

Before the first observation, after a failed read, after close or after two intervals
without fresh data, queue gauges are **NaN**, not zero; sample_up is zero. Last-success
time/age remain available after failure. The oldest age is sampled, not an advancing
clock, and is not a process SLA or end-to-end delivery latency. A stalled poll therefore
cannot leave a healthy-looking backlog reading. Do not sum duplicate snapshots across
application replicas sharing a database; group by deployment/database and use max.

Example condition (only meaningful alongside monitoring availability):

```promql
(approval_outbox_pending > 100) and (approval_outbox_sample_up == 1)
approval_outbox_sample_up == 0
```

Choose thresholds and an absence alert for explicitly enabled deployments; rule syntax
alone does not prove Alertmanager notification delivery. There is no unbounded result
set or event-ID cache. The database may still scan the unfinished/dead population;
statement timeout failure is unavailable evidence, never a successful truncated count.

## Verification boundaries

`JdbcOutboxBacklogIntegrationTest` requires real PostgreSQL and the existing repository:
empty/future-backoff, claim/reschedule/dead, expired-lease recovery/delivery, and mixed
tenants with delivered history. It performs no direct status mutation.
`OutboxBacklogMetricsTest` uses real Micrometer with controlled reads and time, plus an
actual owned executor; configuration tests verify opt-in and no second DataSource bean.
Local Java compilation of the reader/contract and JDBC protocol fixtures are not a
substitute for those current-commit Maven/PostgreSQL tests, live collector ingestion,
production load/stop behavior or the signed payment-sandbox end-to-end rehearsal.

The existing real Spring Boot/Flowable metrics test also opts in to the new monitor,
checks the completion Outbox row through its private reader pool, and requires the
backlog series on the actual HTTP Prometheus response. It retains all existing
approval, rollback, idempotency and identity assertions.
