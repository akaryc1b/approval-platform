package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;
import io.github.akaryc1b.approval.integration.outbox.BusinessCallbackResolver;
import io.github.akaryc1b.approval.integration.outbox.OutboxDispatchObserver;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Local spans only; export lifecycle and sampling remain owned by the host tracing configuration. */
public final class OutboxTracing implements OutboxDispatchObserver {
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Set<String> PROPAGATION_HEADERS = Set.of(
        "traceparent", "tracestate", "baggage", "b3", "x-b3-traceid", "x-b3-spanid",
        "x-b3-parentspanid", "x-b3-sampled", "x-b3-flags", "uber-trace-id"
    );
    private static final String[] MDC_KEYS = {"traceId", "spanId", "approvalEventId"};

    private final Supplier<Tracer> tracers;
    private final ThreadLocal<Handle> active = new ThreadLocal<>();

    public OutboxTracing(Supplier<Tracer> tracers) {
        this.tracers = Objects.requireNonNull(tracers, "tracers must not be null");
    }

    public static OutboxTracing disabled() {
        return new OutboxTracing(() -> null);
    }

    @Override
    public Attempt start(UUID eventId) {
        try {
            Tracer tracer = tracers.get();
            if (tracer == null) return Attempt.NOOP;
            // The stored business traceId is not an OTel SpanContext. Never invent a parent.
            Handle handle = open(tracer, eventId, null);
            return handle == null ? Attempt.NOOP : handle;
        } catch (RuntimeException ignored) {
            return Attempt.NOOP;
        }
    }

    /** Decorates all resolved routes, including the reserved organization-notification route. */
    public BusinessCallbackResolver observe(BusinessCallbackResolver delegate) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        return key -> {
            BusinessCallbackConnector connector = delegate.resolve(key);
            return (context, event) -> {
                Handle parent = active.get();
                if (parent == null) return connector.deliver(context, event);
                Handle child = open(parent.tracer, event.eventId(), parent);
                try {
                    var receipt = connector.deliver(context, event);
                    if (child != null) {
                        child.result(receipt == null ? "invalid_receipt"
                            : receipt.status().name().toLowerCase(Locale.ROOT));
                    }
                    return receipt;
                } catch (RuntimeException failure) {
                    if (child != null) child.result("exception");
                    throw failure;
                } finally {
                    if (child != null) child.close();
                }
            };
        };
    }

    /** Only trace identity crosses the wire; never propagate caller baggage or static trace identities. */
    public Map<String, String> headers(Map<String, String> configured) {
        Handle handle = active.get();
        if (handle == null || configured == null) return configured;
        try {
            String traceparent = traceparent(handle.span.context());
            if (traceparent == null) return configured;
            Map<String, String> headers = new LinkedHashMap<>();
            configured.forEach((name, value) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!PROPAGATION_HEADERS.contains(lower) && !lower.startsWith("uberctx-")) {
                    headers.put(name, value);
                }
            });
            headers.put("traceparent", traceparent);
            return Map.copyOf(headers);
        } catch (RuntimeException ignored) {
            return configured;
        }
    }

    private Handle open(Tracer tracer, UUID eventId, Handle parent) {
        Span span = null;
        Tracer.SpanInScope scope = null;
        Handle handle = null;
        try {
            String[] previousMdc = new String[MDC_KEYS.length];
            for (int i = 0; i < MDC_KEYS.length; i++) previousMdc[i] = MDC.get(MDC_KEYS[i]);
            Span.Builder builder = tracer.spanBuilder()
                .name(parent == null ? "approval.outbox.dispatch" : "approval.connector.callback")
                .kind(parent == null ? Span.Kind.CONSUMER : Span.Kind.CLIENT);
            if (parent == null) builder.setNoParent();
            else builder.setParent(parent.span.context());
            span = builder.start();
            String propagation = traceparent(span.context());
            if (propagation == null) {
                span.end();
                return null;
            }
            // UUID is the sole diagnostic dimension. No payload, provider error text or business labels.
            span.tag("approval.outbox.event_id", eventId.toString());
            scope = tracer.withSpan(span);
            handle = new Handle(tracer, span, scope, active.get(), previousMdc);
            active.set(handle);
            MDC.put("traceId", propagation.substring(3, 35));
            MDC.put("spanId", propagation.substring(36, 52));
            MDC.put("approvalEventId", eventId.toString());
            return handle;
        } catch (RuntimeException ignored) {
            if (handle != null) handle.close();
            else {
                if (scope != null) safe(scope::close);
                if (span != null) safe(span::end);
            }
            return null;
        }
    }

    private static String traceparent(TraceContext context) {
        if (context == null) return null;
        String trace = context.traceId();
        String span = context.spanId();
        if (trace == null || span == null) return null;
        if (SPAN_ID.matcher(trace).matches()) trace = "0000000000000000" + trace;
        if (!TRACE_ID.matcher(trace).matches() || !SPAN_ID.matcher(span).matches()
            || trace.equals("00000000000000000000000000000000") || span.equals("0000000000000000")) {
            return null;
        }
        return "00-" + trace + "-" + span + "-" + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
    }

    private static void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // No retry, logging of exception/payload, exporter flush or business side effect.
        }
    }

    private final class Handle implements Attempt {
        private final Tracer tracer;
        private final Span span;
        private final Tracer.SpanInScope scope;
        private final Handle previous;
        private final String[] previousMdc;
        private boolean closed;

        private Handle(Tracer tracer, Span span, Tracer.SpanInScope scope, Handle previous, String[] previousMdc) {
            this.tracer = tracer;
            this.span = span;
            this.scope = scope;
            this.previous = previous;
            this.previousMdc = previousMdc;
        }

        @Override
        public void outcome(Outcome outcome) {
            result(outcome.name().toLowerCase(Locale.ROOT));
        }

        private void result(String outcome) {
            safe(() -> span.tag("approval.outcome", outcome));
            if (!"delivered".equals(outcome)) safe(() -> span.tag("error", "true"));
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            safe(scope::close);
            for (int i = 0; i < MDC_KEYS.length; i++) {
                int index = i;
                safe(() -> {
                    if (previousMdc[index] == null) MDC.remove(MDC_KEYS[index]);
                    else MDC.put(MDC_KEYS[index], previousMdc[index]);
                });
            }
            if (previous == null) active.remove();
            else active.set(previous);
            safe(span::end);
        }
    }
}
