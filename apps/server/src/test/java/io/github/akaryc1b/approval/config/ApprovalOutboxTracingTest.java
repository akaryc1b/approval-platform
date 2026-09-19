package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.connector.generic.GenericRestBusinessCallbackConnector;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.BusinessEvent;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.CallbackReceipt;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.DeliveryStatus;
import io.github.akaryc1b.approval.integration.outbox.OutboxDispatcher;
import io.github.akaryc1b.approval.integration.outbox.OutboxMessage;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository;
import io.github.akaryc1b.approval.integration.retry.RetryPolicy;
import io.github.akaryc1b.approval.integration.webhook.HmacSha256WebhookSigner;
import io.github.akaryc1b.approval.observability.OutboxTracing;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real OTel/Micrometer scopes and signed loopback HTTP; no external collector or production recipient. */
class ApprovalOutboxTracingTest {
    private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PRIVATE = "FORM_AND_PROVIDER_PAYLOAD_MUST_NOT_BE_EXPORTED";
    private static final String SECRET = "local-tracing-test-only-secret-32-bytes";
    private static final RetryPolicy RETRY = new RetryPolicy() {
        public int maxAttempts() { return 3; }
        public Duration nextDelay(int attempt) { return Duration.ofSeconds(1); }
    };

    @Test
    void optInIsAbsentByDefaultAndDoesNotRequireOrCreateATracer() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ApprovalOutboxTracingConfiguration.class);
        runner.run(context -> assertFalse(context.containsBean("outboxTracing")));
        runner.withPropertyValues("approval.observability.outbox-tracing.enabled=false")
            .run(context -> assertFalse(context.containsBean("outboxTracing")));
        runner.withPropertyValues("approval.observability.outbox-tracing.enabled=true").run(context -> {
            assertNotNull(context.getBean(OutboxTracing.class));
            assertTrue(context.getBeansOfType(Tracer.class).isEmpty());
            try (var attempt = context.getBean(OutboxTracing.class).start(UUID.randomUUID())) {
                assertNotNull(attempt);
            }
        });
    }

    @Test
    void dispatchAndCallbackHaveRealParentageWithoutInventingTheProducerSpan() {
        try (Fixture f = new Fixture(true)) {
            Span caller = f.tracer.nextSpan().name("unrelated-scheduler").start();
            try (var scope = f.tracer.withSpan(caller)) {
                assertEquals(1, f.dispatch((context, event) -> receipt(DeliveryStatus.DELIVERED)).delivered());
                assertEquals(caller.context().spanId(), f.tracer.currentSpan().context().spanId());
            } finally { caller.end(); }
            SpanData dispatch = f.only("approval.outbox.dispatch");
            SpanData callback = f.only("approval.connector.callback");
            assertEquals("0000000000000000", dispatch.getParentSpanId());
            assertNotEquals(caller.context().traceId(), dispatch.getTraceId());
            assertEquals(dispatch.getTraceId(), callback.getTraceId());
            assertEquals(dispatch.getSpanId(), callback.getParentSpanId());
            assertEquals("CONSUMER", dispatch.getKind().name());
            assertEquals("CLIENT", callback.getKind().name());
            assertEquals("delivered", attribute(dispatch, "approval.outcome"));
            assertEquals(f.message.event().eventId().toString(), attribute(callback, "approval.outbox.event_id"));
            verify(f.repository, times(1)).markDelivered(any(), any(), any(), anyInt(), any());
            f.assertPrivateDataAbsent();
        }
    }

    @Test
    void retryKeepsTheDurableEventButStartsAnIndependentAttempt() {
        try (Fixture f = new Fixture(true)) {
            assertEquals(1, f.dispatch((context, event) -> receipt(DeliveryStatus.RETRYABLE_FAILURE)).rescheduled());
            assertEquals(1, f.dispatch((context, event) -> receipt(DeliveryStatus.DELIVERED)).delivered());
            List<SpanData> roots = f.named("approval.outbox.dispatch");
            assertEquals(2, roots.size());
            assertNotEquals(roots.get(0).getTraceId(), roots.get(1).getTraceId());
            assertEquals("rescheduled", attribute(roots.get(0), "approval.outcome"));
            assertEquals("delivered", attribute(roots.get(1), "approval.outcome"));
            for (SpanData root : roots) assertEquals(f.message.event().eventId().toString(),
                attribute(root, "approval.outbox.event_id"));
            f.assertPrivateDataAbsent();
        }
    }

    @Test
    void deadLetterAndLeaseLossAreNotMisreportedAsDelivery() {
        try (Fixture f = new Fixture(true)) {
            assertEquals(1, f.dispatch((context, event) -> receipt(DeliveryStatus.PERMANENT_FAILURE)).dead());
            assertEquals("dead", attribute(f.only("approval.outbox.dispatch"), "approval.outcome"));
            assertEquals("permanent_failure", attribute(f.only("approval.connector.callback"), "approval.outcome"));
        }
        try (Fixture f = new Fixture(true)) {
            when(f.repository.markDelivered(any(), any(), any(), anyInt(), any())).thenReturn(false);
            assertEquals(1, f.dispatch((context, event) -> receipt(DeliveryStatus.DELIVERED)).leaseLost());
            assertEquals("lease_lost", attribute(f.only("approval.outbox.dispatch"), "approval.outcome"));
        }
    }

    @Test
    void callbackExceptionRestoresAllOwnedMdcWithoutExportingTheException() {
        Map<String, String> before = MDC.getCopyOfContextMap();
        try (Fixture f = new Fixture(true)) {
            MDC.put("traceId", "previous-trace"); MDC.put("spanId", "previous-span");
            MDC.put("approvalEventId", "previous-event"); MDC.put("unrelated", "preserved");
            assertEquals(1, f.dispatch((context, event) -> {
                assertEquals(event.eventId().toString(), MDC.get("approvalEventId"));
                assertTrue(MDC.get("traceId").matches("[0-9a-f]{32}"));
                throw new IllegalStateException(PRIVATE);
            }).rescheduled());
            assertEquals("previous-trace", MDC.get("traceId"));
            assertEquals("previous-span", MDC.get("spanId"));
            assertEquals("previous-event", MDC.get("approvalEventId"));
            assertEquals("preserved", MDC.get("unrelated"));
            assertNull(f.tracer.currentSpan());
            assertEquals("exception", attribute(f.only("approval.connector.callback"), "approval.outcome"));
            f.assertPrivateDataAbsent();
        } finally {
            if (before == null) MDC.clear(); else MDC.setContextMap(before);
        }
    }

    @Test
    void persistenceExceptionEscapesUnchangedAndNeverRepeatsTheExternalCallback() {
        try (Fixture f = new Fixture(true)) {
            IllegalStateException failure = new IllegalStateException(PRIVATE);
            when(f.repository.markDelivered(any(), any(), any(), anyInt(), any())).thenThrow(failure);
            when(f.repository.reschedule(any(), any(), anyInt(), any(), any(), any())).thenThrow(failure);
            AtomicInteger calls = new AtomicInteger();
            assertSame(failure, assertThrows(IllegalStateException.class, () -> f.dispatch((context, event) -> {
                calls.incrementAndGet(); return receipt(DeliveryStatus.DELIVERED);
            })));
            assertEquals(1, calls.get());
            assertEquals("failed", attribute(f.only("approval.outbox.dispatch"), "approval.outcome"));
            assertNull(f.tracer.currentSpan()); f.assertPrivateDataAbsent();
        }
    }

    @Test
    void unavailableTracerAndFailingExporterDoNotChangeDelivery() {
        try (Fixture f = new Fixture(true)) {
            OutboxTracing unavailable = new OutboxTracing(() -> { throw new IllegalStateException(PRIVATE); });
            var dispatcher = new OutboxDispatcher(f.repository, unavailable.observe(key ->
                (context, event) -> receipt(DeliveryStatus.DELIVERED)), RETRY, CLOCK, Duration.ofSeconds(30), unavailable);
            assertEquals(1, dispatcher.dispatchBatch(10, "worker").delivered());
            assertTrue(f.spans.isEmpty());
            f.exportFailure = true;
            assertEquals(1, f.dispatch((context, event) -> receipt(DeliveryStatus.DELIVERED)).delivered());
            assertEquals(2, f.spans.size()); assertNull(f.tracer.currentSpan());
        }
    }

    @Test
    void disabledAndNoopTracingLeaveConfiguredHeadersUntouched() {
        Map<String, String> configured = Map.of("TraceParent", "existing", "X-Extra", "value");
        for (OutboxTracing tracing : List.of(OutboxTracing.disabled(), new OutboxTracing(() -> Tracer.NOOP))) {
            try (var scope = tracing.start(UUID.randomUUID())) {
                assertSame(configured, tracing.headers(configured));
            }
            assertSame(configured, tracing.headers(configured));
        }
    }

    @Test
    void traceHeadersAreIsolatedToOwnedScopesAndRespectUnsampledSpans() {
        try (Fixture f = new Fixture(false)) {
            Map<String, String> configured = Map.of("TraceParent", "old", "tracestate", "secret=canary",
                "baggage", PRIVATE, "b3", "old", "X-Extra", "safe");
            assertSame(configured, f.tracing.headers(configured));
            try (var scope = f.tracing.start(f.message.event().eventId())) {
                Map<String, String> actual = f.tracing.headers(configured);
                assertEquals(2, actual.size()); assertEquals("safe", actual.get("X-Extra"));
                assertTrue(actual.get("traceparent").matches("00-[0-9a-f]{32}-[0-9a-f]{16}-00"));
                assertEquals("old", configured.get("TraceParent"));
            }
            assertSame(configured, f.tracing.headers(configured));
            assertTrue(f.spans.isEmpty());
        }
    }

    @Test
    void twoMessagesOnTheSameWorkerNeverInheritEachOthersContext() throws Exception {
        try (Fixture f = new Fixture(true); var worker = Executors.newSingleThreadExecutor()) {
            OutboxMessage other = message();
            when(f.repository.claimDue(any(), anyInt(), any(), any()))
                .thenReturn(claim(f.message), claim(other));
            for (UUID eventId : List.of(f.message.event().eventId(), other.event().eventId())) {
                worker.submit(() -> {
                    assertNull(f.tracer.currentSpan()); assertNull(MDC.get("approvalEventId"));
                    f.dispatch((context, event) -> {
                        assertEquals(eventId.toString(), MDC.get("approvalEventId"));
                        return receipt(DeliveryStatus.DELIVERED);
                    });
                    assertNull(f.tracer.currentSpan()); assertNull(MDC.get("approvalEventId"));
                }).get(5, TimeUnit.SECONDS);
            }
            List<SpanData> roots = f.named("approval.outbox.dispatch");
            assertEquals(2, roots.size());
            assertNotEquals(roots.get(0).getTraceId(), roots.get(1).getTraceId());
            assertNotEquals(attribute(roots.get(0), "approval.outbox.event_id"),
                attribute(roots.get(1), "approval.outbox.event_id"));
        }
    }

    @Test
    void signedHttpCallbackUsesTheCallbackSpanWithoutChangingBusinessEnvelope() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            Fixture f = new Fixture(true); HttpClient client = HttpClient.newHttpClient();
            var receiver = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var received = receiver.submit(() -> receiveCallback(server));
            URI uri = URI.create("http://127.0.0.1:" + server.getLocalPort() + "/callback");
            GenericConnectorProperties properties = properties(uri);
            var factory = new StaticListableBeanFactory(Map.of("tracing", f.tracing));
            var configuration = new GenericConnectorConfiguration(factory.getBeanProvider(OutboxTracing.class));
            var callback = new GenericRestBusinessCallbackConnector(client,
                configuration.genericWebhookEndpointResolver(properties), new HmacSha256WebhookSigner(),
                CLOCK, () -> "test-nonce");
            OutboxDispatcher dispatcher = configuration.outboxDispatcher(f.repository, key -> callback, properties, CLOCK);
            assertEquals(1, dispatcher.dispatchBatch(10, "worker").delivered());
            CapturedRequest request = received.get(5, TimeUnit.SECONDS);
            SpanData child = f.only("approval.connector.callback");
            assertEquals("00-" + child.getTraceId() + "-" + child.getSpanId() + "-01",
                request.headers().get("traceparent"));
            assertNull(request.headers().get("baggage")); assertNull(request.headers().get("b3"));
            assertEquals("business-correlation", request.headers().get("x-trace-id"));
            assertEquals(f.message.event().idempotencyKey(), request.headers().get("idempotency-key"));
            assertEquals(f.message.context().requestId(), request.headers().get("x-request-id"));
            assertEquals(new HmacSha256WebhookSigner().sign(SECRET.getBytes(StandardCharsets.UTF_8),
                NOW.getEpochSecond(), "test-nonce", request.body()), request.headers().get("x-approval-signature"));
            var json = new ObjectMapper().readTree(request.body());
            assertEquals(f.message.event().eventId().toString(), json.get("eventId").asText());
            assertEquals(PRIVATE, json.get("payload").get("private").asText());
            assertEquals(7, json.size());
            f.assertPrivateDataAbsent();
            verify(f.repository, times(1)).markDelivered(any(), any(), any(), anyInt(), any());
        }
    }

    @Test
    void bothHostAndCallbackEndpointResolversUseTheOwnedSpanOnly() {
        try (Fixture f = new Fixture(true)) {
            var factory = new StaticListableBeanFactory(Map.of("tracing", f.tracing));
            var configuration = new GenericConnectorConfiguration(factory.getBeanProvider(OutboxTracing.class));
            var properties = properties(URI.create("http://127.0.0.1:8080/callback"));
            try (var attempt = f.tracing.start(f.message.event().eventId())) {
                String expected = f.tracing.headers(Map.of()).get("traceparent");
                assertEquals(expected, configuration.genericWebhookEndpointResolver(properties)
                    .resolve(f.message.context()).headers().get("traceparent"));
                assertEquals(expected, configuration.genericRestHostEndpointResolver(properties)
                    .resolve(f.message.context()).headers().get("traceparent"));
            }
            assertEquals("old", configuration.genericWebhookEndpointResolver(properties)
                .resolve(f.message.context()).headers().get("TraceParent"));
        }
    }

    /** One bounded HTTP/1.1 loopback request; no internal JDK API or additional test dependency. */
    private static CapturedRequest receiveCallback(ServerSocket server) throws IOException {
        try (var socket = server.accept()) {
            socket.setSoTimeout(5000);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int end = 0;
            while (end != 0x0d0a0d0a) {
                int next = input.read();
                if (next == -1 || head.size() >= 16384) throw new IOException("Invalid test HTTP header");
                head.write(next); end = (end << 8) | next;
            }
            String[] lines = head.toString(StandardCharsets.US_ASCII).split("\r\n");
            assertEquals("POST /callback HTTP/1.1", lines[0]);
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int separator = lines[i].indexOf(':');
                assertTrue(separator > 0);
                String name = lines[i].substring(0, separator).toLowerCase(Locale.ROOT);
                assertNull(headers.put(name, lines[i].substring(separator + 1).trim()), "duplicate HTTP header");
            }
            assertFalse(headers.containsKey("transfer-encoding"));
            int length = Integer.parseInt(headers.get("content-length"));
            assertTrue(length >= 0 && length <= 65536);
            byte[] body = input.readNBytes(length);
            assertEquals(length, body.length);
            socket.getOutputStream().write(("HTTP/1.1 204 No Content\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new CapturedRequest(new String(body, StandardCharsets.UTF_8), Map.copyOf(headers));
        }
    }

    private record CapturedRequest(String body, Map<String, String> headers) {
    }

    private static GenericConnectorProperties properties(URI callback) {
        GenericConnectorProperties properties = new GenericConnectorProperties();
        properties.setEnabled(true); properties.setHostBaseUri(URI.create("http://127.0.0.1:8080"));
        properties.setCallbackUri(callback); properties.setKeyId("test-key"); properties.setSecret(SECRET);
        properties.setTimeout(Duration.ofSeconds(3));
        properties.setHeaders(Map.of("TraceParent", "old", "baggage", PRIVATE, "b3", "old", "X-Extra", "safe"));
        return properties;
    }

    private static String attribute(SpanData span, String name) {
        return span.getAttributes().get(AttributeKey.stringKey(name));
    }

    private static CallbackReceipt receipt(DeliveryStatus status) {
        return new CallbackReceipt(status, "provider", status == DeliveryStatus.DELIVERED ? 204 : 503, NOW, PRIVATE);
    }

    private static OutboxMessage message() {
        return OutboxMessage.create(new ConnectorContext("generic-rest", "tenant-a", "request-a",
            "business-correlation", NOW), new BusinessEvent(UUID.randomUUID(), "PROCESS_COMPLETED.v1",
            "PROCESS", "process-a", NOW, "original-idempotency", Map.of("private", PRIVATE)), NOW);
    }

    private static List<OutboxRepository.ClaimedMessage> claim(OutboxMessage message) {
        return List.of(new OutboxRepository.ClaimedMessage(message, 0, "worker", NOW.plusSeconds(30)));
    }

    private static final class Fixture implements AutoCloseable {
        final List<SpanData> spans = new CopyOnWriteArrayList<>();
        final OutboxMessage message = message();
        final OutboxRepository repository = mock(OutboxRepository.class);
        final SdkTracerProvider provider;
        final OtelTracer tracer;
        final OutboxTracing tracing;
        boolean exportFailure;

        Fixture(boolean sampled) {
            SpanExporter exporter = new SpanExporter() {
                public CompletableResultCode export(Collection<SpanData> batch) {
                    spans.addAll(batch);
                    return exportFailure ? CompletableResultCode.ofFailure() : CompletableResultCode.ofSuccess();
                }
                public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
                public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
            };
            provider = SdkTracerProvider.builder().setSampler(sampled ? Sampler.alwaysOn() : Sampler.alwaysOff())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
            tracer = new OtelTracer(provider.get("outbox-tracing-test"), new OtelCurrentTraceContext(), event -> {});
            tracing = new OutboxTracing(() -> tracer);
            when(repository.claimDue(any(), anyInt(), any(), any())).thenReturn(claim(message));
            when(repository.markDelivered(any(), any(), any(), anyInt(), any())).thenReturn(true);
            when(repository.reschedule(any(), any(), anyInt(), any(), any(), any())).thenReturn(true);
            when(repository.markDead(any(), any(), anyInt(), any(), any())).thenReturn(true);
        }

        OutboxDispatcher.DispatchReport dispatch(BusinessCallbackConnector connector) {
            return new OutboxDispatcher(repository, tracing.observe(key -> connector), RETRY, CLOCK,
                Duration.ofSeconds(30), tracing).dispatchBatch(10, "worker");
        }

        List<SpanData> named(String name) { return spans.stream().filter(s -> name.equals(s.getName())).toList(); }
        SpanData only(String name) { List<SpanData> found = named(name); assertEquals(1, found.size()); return found.get(0); }
        void assertPrivateDataAbsent() {
            for (SpanData span : spans) {
                assertFalse(span.toString().contains(PRIVATE)); assertFalse(span.toString().contains(SECRET));
                assertTrue(span.getEvents().isEmpty(), "raw exceptions must not become span events");
                span.getAttributes().asMap().keySet().forEach(key -> assertTrue(
                    List.of("approval.outbox.event_id", "approval.outcome", "error").contains(key.getKey())));
            }
        }
        public void close() { provider.close(); }
    }
}
