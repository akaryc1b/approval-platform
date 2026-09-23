package io.github.akaryc1b.approval.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovalTraceLogCorrelationFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void exposesTelemetryIdsWithoutReplacingDurableBusinessCorrelation() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("0123456789abcdef0123456789abcdef");
        when(context.spanId()).thenReturn("0123456789abcdef");

        MDC.put("traceId", "client-correlation");
        MDC.put("requestId", "request-1");
        MDC.put("operatorId", "operator-1");

        new ApprovalTraceLogCorrelationFilter(tracer).doFilter(
            new MockHttpServletRequest("GET", "/api/approval/tasks/pending"),
            new MockHttpServletResponse(),
            (request, response) -> {
                assertEquals(
                    "0123456789abcdef0123456789abcdef",
                    MDC.get("traceId")
                );
                assertEquals("0123456789abcdef", MDC.get("spanId"));
                assertEquals(
                    "client-correlation",
                    MDC.get(MdcApprovalRequestEvidenceProvider.APPROVAL_TRACE_ID_MDC_KEY)
                );
                assertEquals(
                    "client-correlation",
                    new MdcApprovalRequestEvidenceProvider().current().traceId()
                );
            }
        );

        assertEquals("client-correlation", MDC.get("traceId"));
        assertNull(MDC.get("spanId"));
        assertNull(MDC.get(MdcApprovalRequestEvidenceProvider.APPROVAL_TRACE_ID_MDC_KEY));
    }

    @Test
    void leavesCorrelationUntouchedWhenNoTelemetrySpanIsActive() throws Exception {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);
        MDC.put("traceId", "client-correlation");

        new ApprovalTraceLogCorrelationFilter(tracer).doFilter(
            new MockHttpServletRequest("GET", "/api/approval/tasks/pending"),
            new MockHttpServletResponse(),
            (request, response) -> assertEquals(
                "client-correlation",
                MDC.get("traceId")
            )
        );

        assertEquals("client-correlation", MDC.get("traceId"));
    }
}
