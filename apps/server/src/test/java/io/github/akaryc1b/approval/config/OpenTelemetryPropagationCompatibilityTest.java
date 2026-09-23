package io.github.akaryc1b.approval.config;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.extension.trace.propagation.B3Propagator;
import io.opentelemetry.extension.trace.propagation.JaegerPropagator;
import io.opentelemetry.extension.trace.propagation.OtTracePropagator;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the real resolved propagators, without an exporter, collector or network request. */
class OpenTelemetryPropagationCompatibilityTest {
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String SPAN_ID = "0123456789abcdef";
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    @Test
    void ordinaryW3cBaggageRemainsUsable() {
        Baggage value = extract(W3CBaggagePropagator.getInstance(), Map.of("baggage", "test=ok,stage=review"));
        assertEquals(2, value.size());
        assertEquals("ok", value.getEntryValue("test"));
        assertEquals("review", value.getEntryValue("stage"));
    }

    @Test
    void w3cExtractionStopsAtSixtyFourEntries() {
        String header = IntStream.range(0, 80).mapToObj(i -> "key" + i + "=value")
            .collect(Collectors.joining(","));
        Baggage value = extract(W3CBaggagePropagator.getInstance(), Map.of("baggage", header));
        assertEquals(64, value.size());
        assertEquals("value", value.getEntryValue("key0"));
        assertEquals("value", value.getEntryValue("key63"));
        assertNull(value.getEntryValue("key64"));
    }

    @Test
    void w3cRejectsAnOversizedHeaderBeforeExtractingPartialValues() {
        Baggage value = extract(W3CBaggagePropagator.getInstance(), Map.of("baggage", "key=" + "v".repeat(8192)));
        assertTrue(value.isEmpty());
    }

    @Test
    void w3cByteBudgetCoversAllHeaderValues() {
        TextMapGetter<Map<String, List<String>>> getter = new TextMapGetter<>() {
            @Override
            public Iterable<String> keys(Map<String, List<String>> carrier) {
                return carrier.keySet();
            }

            @Override
            public String get(Map<String, List<String>> carrier, String key) {
                return carrier.getOrDefault(key, List.of()).stream().findFirst().orElse(null);
            }

            @Override
            public Iterator<String> getAll(Map<String, List<String>> carrier, String key) {
                return carrier.getOrDefault(key, List.of()).iterator();
            }
        };
        Context context = W3CBaggagePropagator.getInstance().extract(Context.root(),
            Map.of("baggage", List.of("a=" + "v".repeat(8189), "b=overflow")), getter);
        Baggage value = Baggage.fromContext(context);
        assertEquals(1, value.size());
        assertEquals(8189, value.getEntryValue("a").length());
        assertNull(value.getEntryValue("b"));
    }

    @Test
    void oversizedLocalBaggageIsNotForwarded() {
        Map<String, String> carrier = new LinkedHashMap<>();
        Context context = Context.root().with(Baggage.builder().put("key", "v".repeat(8192)).build());
        W3CBaggagePropagator.getInstance().inject(context, carrier, Map::put);
        assertFalse(carrier.containsKey("baggage"));
    }

    @Test
    void jaegerExtractionRetainsOnlyBoundedEntries() {
        assertEntryBudget(JaegerPropagator.getInstance(), "uberctx-");
    }

    @Test
    void otTraceExtractionRetainsOnlyBoundedEntries() {
        assertEntryBudget(OtTracePropagator.getInstance(), "ot-baggage-");
    }

    @Test
    void jaegerRejectsAnOversizedBaggageValue() {
        assertByteBudget(JaegerPropagator.getInstance(), "uberctx-");
    }

    @Test
    void otTraceRejectsAnOversizedBaggageValue() {
        assertByteBudget(OtTracePropagator.getInstance(), "ot-baggage-");
    }

    @Test
    void w3cAndB3StillRoundTripValidTraceIdentity() {
        SpanContext expected = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
        for (TextMapPropagator propagator : List.of(W3CTraceContextPropagator.getInstance(),
            B3Propagator.injectingSingleHeader(), B3Propagator.injectingMultiHeaders())) {
            Map<String, String> carrier = new LinkedHashMap<>();
            propagator.inject(Context.root().with(Span.wrap(expected)), carrier, Map::put);
            SpanContext actual = Span.fromContext(propagator.extract(Context.root(), carrier, GETTER)).getSpanContext();
            assertTrue(actual.isValid());
            assertTrue(actual.isRemote());
            assertEquals(expected.getTraceId(), actual.getTraceId());
            assertEquals(expected.getSpanId(), actual.getSpanId());
            assertEquals(expected.getTraceFlags(), actual.getTraceFlags());
        }
    }

    @Test
    void sdkAndApiRemainLinkedWithoutActivatingAnExporter() {
        try (SdkTracerProvider provider = SdkTracerProvider.builder().build()) {
            Span parent = provider.get("approval-compatibility-test").spanBuilder("parent").startSpan();
            Span child = provider.get("approval-compatibility-test").spanBuilder("child")
                .setParent(Context.root().with(parent)).startSpan();
            try {
                assertTrue(parent.getSpanContext().isValid());
                assertEquals(parent.getSpanContext().getTraceId(), child.getSpanContext().getTraceId());
            } finally {
                child.end();
                parent.end();
            }
        }
    }

    private static Baggage extract(TextMapPropagator propagator, Map<String, String> carrier) {
        return Baggage.fromContext(propagator.extract(Context.root(), carrier, GETTER));
    }

    private static Map<String, String> legacyCarrier() {
        Map<String, String> carrier = new LinkedHashMap<>();
        carrier.put("uber-trace-id", TRACE_ID + ":" + SPAN_ID + ":0:1");
        carrier.put("ot-tracer-traceid", TRACE_ID);
        carrier.put("ot-tracer-spanid", SPAN_ID);
        carrier.put("ot-tracer-sampled", "true");
        return carrier;
    }

    private static void assertEntryBudget(TextMapPropagator propagator, String prefix) {
        Map<String, String> carrier = legacyCarrier();
        IntStream.range(0, 80).forEach(i -> carrier.put(prefix + "key" + i, "value"));
        Baggage value = extract(propagator, carrier);
        assertEquals(64, value.size());
        assertEquals("value", value.getEntryValue("key0"));
        assertNull(value.getEntryValue("key64"));
    }

    private static void assertByteBudget(TextMapPropagator propagator, String prefix) {
        Map<String, String> carrier = legacyCarrier();
        carrier.put(prefix + "key", "v".repeat(8192));
        assertTrue(extract(propagator, carrier).isEmpty());
    }
}
