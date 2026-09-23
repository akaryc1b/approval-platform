# Native OTLP Collector and Outbox outage rehearsal

This #146 scenario exercises the existing Outbox tracing implementation with a real
PostgreSQL database, signed loopback HTTP callbacks, the OpenTelemetry Java HTTP
exporter, and a native OpenTelemetry Collector. It replaces neither the approval
E2E nor the existing capacity/upgrade-restore regression.

## Run

From the repository root, with Java 21, Maven and a local Linux Docker daemon:

```sh
mvn -pl apps/server -am \
  -Dtest=ApprovalOutboxCollectorPostgresTest,SignedCallbackProbeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The ordinary server Maven tests discover these tests without a new workflow or
manual dispatch. Docker is required; the native test is not marked skip-if-Docker-
missing. The existing test-only dependencies and resolved OTel family are reused.
The native image is version-pinned to `otel/opentelemetry-collector:0.160.0`, not
`latest`; that tag is not represented as a cryptographic image digest or production
supply-chain approval. The initial 0.161.0 Docker Hub pin returned manifest
unknown in CI; this explicit 0.160.0 core distribution retains the same required
OTLP, batch, memory-limiter and file components. There is no mutable-tag fallback
or skip-on-pull-failure.

The test imports its own schema initializer: Flowable creates its engine schema,
then the complete existing repository Flyway migrations run before scheduled
work starts. Demo business seeding stays disabled; no replacement Outbox table
or shortened migration set is used.

The receiver configuration is
`apps/server/src/test/resources/observability/collector-rehearsal.yaml`.
Only OTLP HTTP is enabled. Its dynamic host port is bound to loopback. Telemetry
is written to an initially empty file in the disposable container's writable
layer, not a host bind mount or external service. This lets the Docker archive
API used by Testcontainers read the same file that the Collector writes. Docker
explicitly documents tmpfs as a corner case unsupported by its copy interface;
a missing archive view must not be mistaken for missing exported spans.

The fixture copies only an empty, mode-0666 sink so the image's existing non-root
user can write it; it does not change the container user or host permissions.
Before any business append, the test requires that the archive API reads that
file as empty. File rotation still limits each segment to 1 MiB with one backup,
and each evidence read is capped at 4 MiB. The former hard 4 MiB tmpfs filesystem
quota is not claimed for the writable layer. Only the fixed fixture events feed
this sink, and Testcontainers removes it with its container.

The receiver verifies each callback's signature, exact event and original
business identifiers before recording any simulated effect.

## What the scenario proves

Three exact synthetic events are appended through the real transactional Outbox;
a fourth append is rolled back. They are explicitly `OBSERVABILITY_FIXTURE.v1`
events, not fabricated authoritative approval-failure or timeout events.

1. With the collector available, the first event is delivered and the collector's
   actual OTLP JSON file contains its CONSUMER dispatch span and CLIENT callback
   child. Their parentage and W3C header match the actual signed HTTP request.
2. The native collector process is paused, retaining its endpoint. A second
   database append commits. Its first callback returns 503 and remains PENDING;
   a reconstructed JDBC reader/dispatcher retries the same event and reaches
   DELIVERED with one receiver record while the collector is still paused.
   The real HTTP export reports failure; export does not execute on the business
   dispatch thread. No test exporter manufactures the failure/success result.
3. The collector is unpaused. A third event produces fresh traces through the
   same provider/exporter without an application restart or business replay.
   Total callback attempts are four; unique in-memory receiver records are three.
   The rolled-back event remains absent. A final empty dispatch cannot redeliver
   any completed event.

The downloaded collector bytes, not a pre-export span list, are checked for
credentials, payload, baggage, provider-error and business-identity canaries.
Only the existing event UUID, bounded outcome and error marker are allowed as
application span attributes. Raw span exception events are not accepted. File
parsing tolerates only an incomplete trailing line during concurrent copying;
malformed complete JSON records fail the test.

The receipt `OUTBOX_NATIVE_COLLECTOR_REHEARSAL_PASSED` is printed only after all
assertions, provider shutdown and receiver checks. Inspect the actual current
Maven run; source existence or the receipt's name alone is not acceptance.

On a span-file timeout, diagnostics report only fixed counts and booleans:
SDK submissions, real exporter results, file visibility/bytes/newlines, expected
schema/scope/event presence, and native memory/write-failure categories. Native
log text, raw file contents, credentials and business identifiers are not printed.
A successful SDK flush alone is never treated as proof of Collector ingestion.

## Bounds, cleanup and interpretation

The rehearsal owns a BatchSpanProcessor with a 16-span queue, eight-span batch,
100 ms scheduling delay and three-second processor timeout. The real OTLP exporter
uses one-second connection and two-second request limits. **Exporter retries are
disabled only in this test configuration** so an actual transport failure can be
observed deterministically. Business retries remain enabled and separate.
Test database acquisition/socket and worker waits are also bounded. These settings
are not a change to Spring Boot's production defaults or a production throughput
claim. The test never calls forceFlush from an approval transaction or callback;
its explicit flush and shutdown checks are test orchestration only.

A finally block unpauses the collector even on failure; the owned provider, callback
listener and worker are closed, and Testcontainers removes its containers. The
shared Spring engine/database lifecycle is not repurposed as exporter cleanup.

This is **not** producer-HTTP-to-durable-Outbox trace propagation, browser approval,
a real human notification, an ELK/SkyWalking deployment, or production disaster
recovery. Sources are fixture events and recipient deduplication is in-memory.
Outage traces can be lost or appear after an acknowledgement timeout; the test
asserts resumed **fresh** telemetry, not lossless historical replay. Collector
recovery is not business-resolution authority. The collector file exporter is a
test observation sink, not a production trace store.

## Versioned upstream references

Collector distribution/components:
https://github.com/open-telemetry/opentelemetry-collector-releases/blob/v0.160.0/distributions/otelcol/manifest.yaml

Collector JSON file format and rotation:
https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/v0.160.0/exporter/fileexporter/README.md

Docker copy limitations (tmpfs and other special mounts):
https://docs.docker.com/reference/cli/docker/container/cp/#corner-cases

Java exporter limits and retries:
https://github.com/open-telemetry/opentelemetry-java/blob/v1.62.0/exporters/otlp/all/src/main/java/io/opentelemetry/exporter/otlp/http/trace/OtlpHttpSpanExporterBuilder.java

Java bounded asynchronous batching:
https://github.com/open-telemetry/opentelemetry-java/blob/v1.62.0/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/export/BatchSpanProcessor.java
