package io.github.akaryc1b.approval.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.github.akaryc1b.approval.ApprovalPlatformApplication;
import io.github.akaryc1b.approval.connector.generic.GenericRestBusinessCallbackConnector;
import io.github.akaryc1b.approval.connector.generic.GenericWebhookEndpoint;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.BusinessEvent;
import io.github.akaryc1b.approval.integration.jdbc.JdbcOutboxRepository;
import io.github.akaryc1b.approval.integration.outbox.OutboxDispatcher;
import io.github.akaryc1b.approval.integration.outbox.OutboxMessage;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository;
import io.github.akaryc1b.approval.integration.retry.RetryPolicy;
import io.github.akaryc1b.approval.integration.webhook.HmacSha256WebhookSigner;
import io.github.akaryc1b.approval.observability.OutboxTracing;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native Collector + PostgreSQL + signed HTTP. Sources and receiver records are test fixtures. */
@Testcontainers
@ActiveProfiles("local")
@Import(CollectorRehearsalSchemaConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = ApprovalPlatformApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "approval.demo.purchase-payment.enabled=false", "approval.connector.generic.enabled=false",
        "approval.observability.outbox.enabled=false", "approval.observability.workflow.enabled=false",
        "approval.observability.engine-jobs.enabled=false", "flowable.async-executor-activate=false",
        "management.tracing.export.otlp.enabled=false", "management.server.address=127.0.0.1",
        "management.server.port=0", "spring.datasource.hikari.connection-timeout=2000",
        "spring.datasource.hikari.validation-timeout=500", "spring.datasource.hikari.data-source-properties.socketTimeout=5"
    })
class ApprovalOutboxCollectorPostgresTest {
    private static final String IMAGE = "otel/opentelemetry-collector:0.160.0";
    private static final String SCOPE = "approval-outbox-collector-rehearsal";
    private static final String SECRET = "local-collector-fixture-only-signing-key";
    private static final String PRIVATE = "private-form-and-baggage-canary";
    private static final Clock CLOCK = Clock.systemUTC();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger NATIVE_ERRORS = new AtomicInteger();
    private static final AtomicBoolean NATIVE_MEMORY_REFUSAL = new AtomicBoolean();
    private static final AtomicBoolean NATIVE_WRITE_FAILURE = new AtomicBoolean();
    private static final RetryPolicy RETRY = new RetryPolicy() {
        public int maxAttempts() { return 3; }
        public Duration nextDelay(int attempt) { return Duration.ofMillis(100); }
    };

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("collector_outbox_test").withUsername("approval")
        .withPassword(UUID.randomUUID().toString());
    @Container
    static final GenericContainer<?> COLLECTOR = new GenericContainer<>(IMAGE)
        .withExposedPorts(4318)
        .withLogConsumer(frame -> {
            // Classify only fixed conditions; never print native log text or event contents.
            String line = frame.getUtf8String().toLowerCase(java.util.Locale.ROOT);
            if (line.contains("error")) NATIVE_ERRORS.incrementAndGet();
            if (line.contains("memory") && (line.contains("refus") || line.contains("limit exceeded"))) {
                NATIVE_MEMORY_REFUSAL.set(true);
            }
            if (line.contains("permission denied") || line.contains("no space left")
                || line.contains("read-only file system")) NATIVE_WRITE_FAILURE.set(true);
        })
        // Docker archive/cp cannot reliably read tmpfs mounts. Use this disposable
        // container's writable layer, never a host bind or external telemetry service.
        .withCopyToContainer(Transferable.of(new byte[0], 0666), "/evidence/traces.jsonl")
        .withCopyToContainer(Transferable.of(configuration(), 0444), "/etc/approval-collector.yaml")
        .withCommand("--config=/etc/approval-collector.yaml")
        .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(4318))))
        .waitingFor(Wait.forHttp("/v1/traces").forPort(4318).forStatusCode(405))
        .withStartupTimeout(Duration.ofSeconds(60));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OutboxRepository repository;

    @Test
    void nativeCollectorPauseCannotBlockDurableDeliveryAndFreshTracesResume() throws Exception {
        assertTrue(InetAddress.getByName(COLLECTOR.getHost()).isLoopbackAddress(), "local Docker rehearsal only");
        // The very same archive API must see the empty sink before any OTLP export.
        assertEquals("", collectorFile(), "native evidence file starts empty and is archive-readable");
        OutboxMessage healthy = message("before");
        OutboxMessage outage = message("outage");
        OutboxMessage recovered = message("after");
        OutboxMessage rolledBack = message("rollback");
        Map<String, OutboxMessage> expected = new HashMap<>();
        for (OutboxMessage message : List.of(healthy, outage, recovered)) {
            expected.put(message.event().idempotencyKey(), message);
        }
        var exporter = new ObservedExporter(OtlpHttpSpanExporter.builder()
            .setEndpoint("http://" + COLLECTOR.getHost() + ":" + COLLECTOR.getMappedPort(4318) + "/v1/traces")
            .setConnectTimeout(Duration.ofSeconds(1)).setTimeout(Duration.ofSeconds(2))
            .setRetryPolicy(null).build());
        // This finite rehearsal configuration is explicit; it is not a change to Boot's defaults.
        var processor = BatchSpanProcessor.builder(exporter).setMaxQueueSize(16).setMaxExportBatchSize(8)
            .setScheduleDelay(Duration.ofMillis(100)).setExporterTimeout(Duration.ofSeconds(3)).build();
        Map<String, Object> receipt;
        try (var provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn())
                .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), SCOPE)))
                .addSpanProcessor(processor).build();
             var probe = new SignedCallbackProbe(request -> verifyRequest(request, expected));
             HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var tracer = new OtelTracer(provider.get(SCOPE), new OtelCurrentTraceContext(), event -> { });
            var tracing = new OutboxTracing(() -> tracer);
            var callback = new GenericRestBusinessCallbackConnector(http, context -> new GenericWebhookEndpoint(
                probe.uri(), "fixture-key", SECRET.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2),
                tracing.headers(Map.of("baggage", PRIVATE, "b3", "untrusted-static-propagation"))),
                new HmacSha256WebhookSigner(), CLOCK, () -> UUID.randomUUID().toString());

            append(rolledBack, true);
            assertEquals(0L, rowCount(rolledBack));
            assertEquals(0, dispatch(repository, tracing, callback).claimed());
            append(healthy, false);
            assertEquals(1, dispatch(repository, tracing, callback).delivered());
            assertRow(healthy, "DELIVERED", 0);
            flush(provider);
            List<JsonNode> first = awaitPair(healthy, exporter);
            verifyPair(first, healthy, probe);
            int failuresBefore = exporter.failures.get();

            // Pause the actual native process, preserving its endpoint and network namespace.
            COLLECTOR.getDockerClient().pauseContainerCmd(COLLECTOR.getContainerId()).exec();
            try {
                assertTrue(Boolean.TRUE.equals(COLLECTOR.getDockerClient()
                    .inspectContainerCmd(COLLECTOR.getContainerId()).exec().getState().getPaused()));
                append(outage, false); // Real transaction commits while the collector cannot answer.
                assertRow(outage, "PENDING", 0);
                probe.failFirst(outage.event().idempotencyKey());
                assertEquals(1, dispatch(repository, tracing, callback).rescheduled());
                assertRow(outage, "PENDING", 1);
                // Reconstruct the DB reader/dispatcher, retaining the real durable event and retry state.
                OutboxRepository recreated = new JdbcOutboxRepository(dataSource, JSON);
                await("durable callback retry while collector paused", () ->
                    dispatch(recreated, tracing, callback).delivered() == 1);
                assertRow(outage, "DELIVERED", 1);
                assertEquals(2, probe.acceptedCount());
                assertEquals(2L, probe.deliveries().stream().filter(delivery -> delivery.request().headers()
                    .get("idempotency-key").equals(outage.event().idempotencyKey())).count());
                assertEquals(0, dispatch(recreated, tracing, callback).claimed());
                await("actual OTLP transport failure", () -> exporter.failures.get() > failuresBefore);
                assertFalse(exporter.businessThreadExport.get(), "export must not run in the dispatch thread");
                assertTrue(Boolean.TRUE.equals(COLLECTOR.getDockerClient()
                    .inspectContainerCmd(COLLECTOR.getContainerId()).exec().getState().getPaused()));
            } finally {
                COLLECTOR.getDockerClient().unpauseContainerCmd(COLLECTOR.getContainerId()).exec();
            }

            // The same provider/exporter reconnects; no app restart and no business message replay.
            append(recovered, false);
            assertEquals(1, dispatch(repository, tracing, callback).delivered());
            assertRow(recovered, "DELIVERED", 0);
            flush(provider);
            List<JsonNode> last = awaitPair(recovered, exporter);
            verifyPair(last, recovered, probe);
            assertNotEquals(first.get(0).path("traceId").asText(), last.get(0).path("traceId").asText());
            assertEquals(3, probe.acceptedCount());
            assertEquals(4, probe.deliveries().size());
            assertEquals(0, dispatch(repository, tracing, callback).claimed());
            assertEquals(0L, rowCount(rolledBack));
            assertNull(tracer.currentSpan());
            assertNull(MDC.get("approvalEventId"));
            probe.assertHealthy();
            String evidence = collectorFile();
            for (String canary : List.of(SECRET, PRIVATE, SignedCallbackProbe.PROVIDER_CANARY,
                "private-fixture-tenant", "private-business-trace", "private-request-", "private-aggregate-")) {
                assertFalse(evidence.contains(canary), "collector exported private fixture data");
            }
            for (JsonNode span : spans(evidence)) {
                for (JsonNode attribute : span.path("attributes")) {
                    assertTrue(Set.of("approval.outbox.event_id", "approval.outcome", "error")
                        .contains(attribute.path("key").asText()), "unexpected span attribute");
                }
                assertTrue(span.path("events").isMissingNode() || span.path("events").isEmpty());
            }
            CompletableResultCode stopped = provider.shutdown().join(5, TimeUnit.SECONDS);
            assertTrue(stopped.isSuccess(), "owned telemetry provider stopped within the rehearsal bound");
            assertEquals(3, probe.acceptedCount());
            receipt = Map.of(
                "status", "OUTBOX_NATIVE_COLLECTOR_REHEARSAL_PASSED", "collectorImage", IMAGE,
                "committedEvents", 3, "callbackAttempts", 4, "uniqueReceiverRecords", 3,
                "actualOtlpFailuresObserved", exporter.failures.get(), "freshTraceAfterRecovery", true,
                "rollbackPreserved", true, "sourceIsTestFixture", true, "humanNotificationVerified", false);
        }
        System.out.println(JSON.writeValueAsString(receipt));
    }

    private OutboxDispatcher.DispatchReport dispatch(OutboxRepository store, OutboxTracing tracing,
        GenericRestBusinessCallbackConnector callback) throws Exception {
        var worker = Executors.newSingleThreadExecutor(action -> new Thread(action, "outbox-business-rehearsal"));
        try {
            return worker.submit(() -> {
                assertNull(MDC.get("approvalEventId"));
                var report = new OutboxDispatcher(store, tracing.observe(key -> callback), RETRY, CLOCK,
                    Duration.ofSeconds(30), tracing).dispatchBatch(8, "collector-test-worker");
                assertNull(MDC.get("approvalEventId"));
                return report;
            }).get(5, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(6, TimeUnit.SECONDS), "owned dispatch worker stopped");
        }
    }

    private void append(OutboxMessage message, boolean rollback) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertEquals(OutboxRepository.AppendResult.INSERTED, repository.append(message));
            if (rollback) status.setRollbackOnly();
        });
    }
    private long rowCount(OutboxMessage message) {
        return new JdbcTemplate(dataSource).queryForObject("select count(*) from ap_outbox where id = ?",
            Long.class, message.id());
    }
    private void assertRow(OutboxMessage message, String status, int attempts) throws IOException {
        var row = new JdbcTemplate(dataSource).queryForMap("""
            select status, attempts, event_id, idempotency_key, request_id, trace_id, payload_json::text as payload
            from ap_outbox where id = ?
            """, message.id());
        assertEquals(status, row.get("status"));
        assertEquals(attempts, ((Number) row.get("attempts")).intValue());
        assertEquals(message.event().eventId(), row.get("event_id"));
        assertEquals(message.event().idempotencyKey(), row.get("idempotency_key"));
        assertEquals(message.context().requestId(), row.get("request_id"));
        assertEquals(message.context().traceId(), row.get("trace_id"));
        assertEquals(JSON.valueToTree(message.event().payload()), JSON.readTree((String) row.get("payload")));
    }
    private static OutboxMessage message(String phase) {
        // Fixture timestamps use the PostgreSQL column's microsecond precision before persistence.
        Instant now = CLOCK.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return OutboxMessage.create(new ConnectorContext("generic-rest", "private-fixture-tenant",
            "private-request-" + phase, "private-business-trace", now), new BusinessEvent(UUID.randomUUID(),
            "OBSERVABILITY_FIXTURE.v1", "FIXTURE", "private-aggregate-" + phase, now,
            UUID.randomUUID().toString(), Map.of("private", PRIVATE)), now);
    }

    private static void verifyRequest(SignedCallbackProbe.Request request, Map<String, OutboxMessage> expected) {
        try {
            OutboxMessage message = expected.get(request.headers().get("idempotency-key"));
            assertTrue(message != null, "only the three exact fixture events are authorized");
            JsonNode body = JSON.readTree(request.body());
            assertEquals(7, body.size());
            assertEquals(message.event().eventId().toString(), body.path("eventId").asText());
            assertEquals(message.event().eventId().toString(), request.headers().get("x-approval-event-id"));
            assertEquals(message.event().idempotencyKey(), body.path("idempotencyKey").asText());
            assertEquals(message.event().aggregateId(), body.path("aggregateId").asText());
            assertEquals(message.event().eventType(), body.path("eventType").asText());
            assertEquals(message.event().aggregateType(), body.path("aggregateType").asText());
            assertEquals(message.event().occurredAt(), Instant.parse(body.path("occurredAt").asText()));
            assertEquals(JSON.valueToTree(message.event().payload()), body.path("payload"));
            assertEquals(message.context().tenantId(), request.headers().get("x-tenant-id"));
            assertEquals(message.context().traceId(), request.headers().get("x-trace-id"));
            assertEquals(message.context().requestId(), request.headers().get("x-request-id"));
            assertEquals("fixture-key", request.headers().get("x-approval-key-id"));
            assertEquals(new HmacSha256WebhookSigner().sign(SECRET.getBytes(StandardCharsets.UTF_8),
                Long.parseLong(request.headers().get("x-approval-timestamp")),
                request.headers().get("x-approval-nonce"), request.body()), request.headers().get("x-approval-signature"));
            assertTrue(request.headers().getOrDefault("traceparent", "").matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01"));
            assertNull(request.headers().get("baggage"));
            assertNull(request.headers().get("b3"));
        } catch (IOException malformed) {
            throw new AssertionError("invalid signed callback fixture JSON", malformed);
        }
    }

    private static void flush(SdkTracerProvider provider) {
        assertTrue(provider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess(), "bounded test-only flush");
    }
    private static List<JsonNode> awaitPair(OutboxMessage message, ObservedExporter exporter) throws Exception {
        var found = new ArrayList<JsonNode>();
        String[] observed = {""};
        boolean[] present = {false};
        try {
            await("collector file contains the exact event's spans", () -> {
                found.clear();
                try { observed[0] = collectorFile(); present[0] = true; }
                catch (NotFoundException firstBatchNotYetWritten) { return false; }
                for (JsonNode span : spans(observed[0])) {
                    if (message.event().eventId().toString().equals(attribute(span, "approval.outbox.event_id"))) {
                        found.add(span);
                    }
                }
                return found.size() >= 2;
            });
        } catch (AssertionError timeout) {
            // A successful SDK flush alone is not proof of native ingestion or file visibility.
            String facts = "COLLECTOR_EVIDENCE_TIMEOUT filePresent=" + present[0]
                + " bytes=" + observed[0].getBytes(StandardCharsets.UTF_8).length
                + " lineBreaks=" + observed[0].chars().filter(value -> value == '\n').count()
                + " resourceEnvelope=" + observed[0].contains("\"resourceSpans\"")
                + " scopeEnvelope=" + observed[0].contains("\"scopeSpans\"")
                + " expectedScope=" + observed[0].contains(SCOPE)
                + " expectedEvent=" + observed[0].contains(message.event().eventId().toString())
                + " matchedSpans=" + found.size() + " submittedSpans=" + exporter.submitted.get()
                + " successfulExports=" + exporter.successes.get() + " failedExports=" + exporter.failures.get()
                + " nativeErrors=" + NATIVE_ERRORS.get() + " nativeMemoryRefusal=" + NATIVE_MEMORY_REFUSAL.get()
                + " nativeWriteFailure=" + NATIVE_WRITE_FAILURE.get();
            throw new AssertionError(facts, timeout);
        }
        assertEquals(2, found.size(), "one consumer and one client span for a successful attempt");
        return List.copyOf(found);
    }
    private static void verifyPair(List<JsonNode> pair, OutboxMessage message, SignedCallbackProbe probe) {
        JsonNode root = pair.stream().filter(span -> "approval.outbox.dispatch".equals(span.path("name").asText()))
            .findFirst().orElseThrow();
        JsonNode child = pair.stream().filter(span -> "approval.connector.callback".equals(span.path("name").asText()))
            .findFirst().orElseThrow();
        assertEquals(5, root.path("kind").asInt());
        assertEquals(3, child.path("kind").asInt());
        assertTrue(Set.of("", "0000000000000000").contains(root.path("parentSpanId").asText()));
        assertEquals(root.path("traceId").asText(), child.path("traceId").asText());
        assertEquals(root.path("spanId").asText(), child.path("parentSpanId").asText());
        assertEquals("delivered", attribute(root, "approval.outcome"));
        assertEquals("delivered", attribute(child, "approval.outcome"));
        var request = probe.deliveries().stream().filter(delivery -> delivery.request().headers()
            .get("idempotency-key").equals(message.event().idempotencyKey())).findFirst().orElseThrow().request();
        assertEquals("00-" + child.path("traceId").asText() + "-" + child.path("spanId").asText() + "-01",
            request.headers().get("traceparent"));
    }
    private static String attribute(JsonNode span, String key) {
        for (JsonNode attribute : span.path("attributes")) {
            if (key.equals(attribute.path("key").asText())) return attribute.path("value").path("stringValue").asText();
        }
        return null;
    }
    private static List<JsonNode> spans(String text) throws IOException {
        List<JsonNode> spans = new ArrayList<>();
        // A concurrent file copy may end in an incomplete last line; never ignore a malformed complete line.
        int end = text.lastIndexOf('\n');
        if (end < 0) return spans;
        for (String line : text.substring(0, end).split("\n")) {
            if (line.isBlank()) continue;
            for (JsonNode resource : JSON.readTree(line).path("resourceSpans")) {
                for (JsonNode scope : resource.path("scopeSpans")) {
                    if (SCOPE.equals(scope.path("scope").path("name").asText())) {
                        scope.path("spans").forEach(spans::add);
                    }
                }
            }
        }
        return spans;
    }
    private static String collectorFile() {
        return COLLECTOR.copyFileFromContainer("/evidence/traces.jsonl", input -> {
            byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
            assertTrue(bytes.length <= 4 * 1024 * 1024, "bounded native trace evidence");
            return new String(bytes, StandardCharsets.UTF_8);
        });
    }
    private static void await(String label, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            if (condition.call()) return;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(label);
    }
    private static byte[] configuration() {
        try (var input = ApprovalOutboxCollectorPostgresTest.class.getResourceAsStream("/observability/collector-rehearsal.yaml")) {
            if (input == null) throw new IllegalStateException("collector configuration missing");
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("collector configuration unavailable", failure);
        }
    }

    /** Observe the real transport result; never replace a delegate failure with a synthetic success. */
    private static final class ObservedExporter implements SpanExporter {
        private final SpanExporter delegate;
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger submitted = new AtomicInteger();
        final AtomicBoolean businessThreadExport = new AtomicBoolean();
        ObservedExporter(SpanExporter delegate) { this.delegate = delegate; }
        public CompletableResultCode export(Collection<SpanData> batch) {
            if (Thread.currentThread().getName().equals("outbox-business-rehearsal")) businessThreadExport.set(true);
            submitted.addAndGet(batch.size());
            CompletableResultCode result = delegate.export(batch);
            result.whenComplete(() -> {
                if (result.isSuccess()) successes.incrementAndGet();
                else failures.incrementAndGet();
            });
            return result;
        }
        public CompletableResultCode flush() { return delegate.flush(); }
        public CompletableResultCode shutdown() { return delegate.shutdown(); }
    }
}
