package io.github.akaryc1b.approval.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/** Keeps the business correlation ID while exposing the real telemetry trace and span IDs. */
final class ApprovalTraceLogCorrelationFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_MDC_KEY = "traceId";
    private static final String SPAN_ID_MDC_KEY = "spanId";

    private final Tracer tracer;

    ApprovalTraceLogCorrelationFilter(Tracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        Span span = tracer.currentSpan();
        TraceContext context = span == null ? null : span.context();
        if (context == null || blank(context.traceId()) || blank(context.spanId())) {
            filterChain.doFilter(request, response);
            return;
        }

        String previousTraceId = MDC.get(TRACE_ID_MDC_KEY);
        String previousSpanId = MDC.get(SPAN_ID_MDC_KEY);
        String previousApprovalTraceId = MDC.get(
            MdcApprovalRequestEvidenceProvider.APPROVAL_TRACE_ID_MDC_KEY
        );
        try {
            if (!blank(previousTraceId)) {
                MDC.put(
                    MdcApprovalRequestEvidenceProvider.APPROVAL_TRACE_ID_MDC_KEY,
                    previousTraceId
                );
            }
            MDC.put(TRACE_ID_MDC_KEY, context.traceId());
            MDC.put(SPAN_ID_MDC_KEY, context.spanId());
            filterChain.doFilter(request, response);
        } finally {
            restore(TRACE_ID_MDC_KEY, previousTraceId);
            restore(SPAN_ID_MDC_KEY, previousSpanId);
            restore(
                MdcApprovalRequestEvidenceProvider.APPROVAL_TRACE_ID_MDC_KEY,
                previousApprovalTraceId
            );
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void restore(String key, String value) {
        if (blank(value)) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
