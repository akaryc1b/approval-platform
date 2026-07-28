package io.github.akaryc1b.approval.api;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalMigrationOperationsTelemetryFilterTest {

    private static final String PATH =
        "/api/approval/management/process-instance-operations/plans/plan-a/diagnostics";

    @Test
    void repeatedDiagnosticReadsReuseOneBoundedTimer() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApprovalMigrationOperationsTelemetryFilter filter = new ApprovalMigrationOperationsTelemetryFilter(
            meters
        );
        MockHttpServletResponse lastResponse = null;

        for (int index = 0; index < 100; index++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (incoming, outgoing) ->
                ((MockHttpServletResponse) outgoing).setStatus(200)
            );
            lastResponse = response;
        }

        assertEquals(
            100,
            meters.get(ApprovalMigrationOperationsTelemetryClassifier.READ_LATENCY_METRIC)
                .tags(
                    "operation", "plan_diagnostics",
                    "result", "success",
                    "failure_class", "none"
                )
                .timer()
                .count()
        );
        assertEquals(
            1,
            meters.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(
                    ApprovalMigrationOperationsTelemetryClassifier.READ_LATENCY_METRIC
                ))
                .count()
        );
        assertEquals("no-store, max-age=0", lastResponse.getHeader("Cache-Control"));
        assertEquals("no-cache", lastResponse.getHeader("Pragma"));
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", lastResponse.getHeader("Expires"));
        assertLowCardinalityTagKeys(meters);
    }

    @Test
    void statusAndThrownFailureUseClosedClasses() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApprovalMigrationOperationsTelemetryFilter filter = new ApprovalMigrationOperationsTelemetryFilter(
            meters
        );

        MockHttpServletRequest limited = new MockHttpServletRequest("GET", PATH);
        MockHttpServletResponse limitedResponse = new MockHttpServletResponse();
        filter.doFilter(limited, limitedResponse, (incoming, outgoing) ->
            ((MockHttpServletResponse) outgoing).setStatus(429)
        );
        assertEquals(
            1,
            meters.get(ApprovalMigrationOperationsTelemetryClassifier.READ_LATENCY_METRIC)
                .tags(
                    "operation", "plan_diagnostics",
                    "result", "failure",
                    "failure_class", "rate_limited"
                )
                .timer()
                .count()
        );

        MockHttpServletRequest failed = new MockHttpServletRequest("GET", PATH);
        MockHttpServletResponse failedResponse = new MockHttpServletResponse();
        assertThrows(
            ServletException.class,
            () -> filter.doFilter(failed, failedResponse, (incoming, outgoing) -> {
                throw new ServletException("synthetic observability-safe fault");
            })
        );
        assertEquals(
            1,
            meters.get(ApprovalMigrationOperationsTelemetryClassifier.READ_LATENCY_METRIC)
                .tags(
                    "operation", "plan_diagnostics",
                    "result", "failure",
                    "failure_class", "server_error"
                )
                .timer()
                .count()
        );
        assertLowCardinalityTagKeys(meters);
    }

    @Test
    void ignoresNonGetAndUnknownRoutes() throws ServletException, IOException {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApprovalMigrationOperationsTelemetryFilter filter = new ApprovalMigrationOperationsTelemetryFilter(
            meters
        );
        MockHttpServletRequest post = new MockHttpServletRequest("POST", PATH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post, response, (incoming, outgoing) -> {
        });

        assertFalse(meters.getMeters().stream().anyMatch(meter -> meter.getId().getName().equals(
            ApprovalMigrationOperationsTelemetryClassifier.READ_LATENCY_METRIC
        )));
        assertFalse(response.containsHeader("Cache-Control"));
    }

    private static void assertLowCardinalityTagKeys(SimpleMeterRegistry meters) {
        Set<String> forbidden = Set.of(
            "tenantId",
            "operatorId",
            "definitionKey",
            "planId",
            "intentId",
            "attemptId",
            "instanceId",
            "requestId",
            "traceId",
            "message",
            "exception"
        );
        for (Meter meter : meters.getMeters()) {
            meter.getId().getTags().forEach(tag ->
                assertFalse(forbidden.contains(tag.getKey()), "forbidden metric tag " + tag.getKey())
            );
        }
    }
}
