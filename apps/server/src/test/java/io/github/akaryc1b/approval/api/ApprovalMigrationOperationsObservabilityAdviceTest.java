package io.github.akaryc1b.approval.api;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class ApprovalMigrationOperationsObservabilityAdviceTest {

    private static final Instant NOW = Instant.parse("2026-07-28T02:00:00Z");

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void recordsSuccessfulManagementAndMobileReadsWithClosedTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApprovalMigrationOperationsObservabilityAdvice advice = advice(meters);
        Object summary = new Object();
        Object instances = new Object();

        assertSame(summary, write(
            advice,
            summary,
            "/api/approval/management/process-instance-operations/summary",
            200,
            new MockHttpServletResponse()
        ));
        assertSame(instances, write(
            advice,
            instances,
            "/api/approval/mobile/process-instance-operations/plans/plan-1/instances",
            200,
            new MockHttpServletResponse()
        ));

        assertEquals(
            1.0,
            meters.get("approval.migration.operations.read")
                .tags("operation", "summary", "result", "success", "failure_class", "none")
                .counter()
                .count()
        );
        assertEquals(
            1.0,
            meters.get("approval.migration.operations.read")
                .tags(
                    "operation", "instance_list",
                    "result", "success",
                    "failure_class", "none"
                )
                .counter()
                .count()
        );
        assertLowCardinalityTagKeys(meters);
    }

    @Test
    void transformsKnownErrorsIntoBoundedTraceableEvidence() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApprovalMigrationOperationsObservabilityAdvice advice = advice(meters);
        MDC.put("requestId", "trusted-request");
        MDC.put("traceId", "trusted-trace");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var legacy = new ApprovalMigrationOperationsApiExceptionHandler.ApiError(
            "APPROVAL_MIGRATION_OPERATIONS_INVALID_REQUEST",
            "invalid\nreason",
            "browser-request",
            NOW.minusSeconds(10)
        );

        Object returned = write(
            advice,
            legacy,
            "/api/approval/management/process-instance-operations/plans",
            400,
            response
        );
        var error = (ApprovalMigrationOperationsObservabilityAdvice.OperationsApiError) returned;

        assertEquals("APPROVAL_MIGRATION_OPERATIONS_INVALID_REQUEST", error.errorCode());
        assertEquals("invalid reason", error.message());
        assertFalse(error.retryable());
        assertEquals("trusted-request", error.requestId());
        assertEquals("trusted-trace", error.traceId());
        assertEquals(NOW, error.timestamp());
        assertEquals(Map.of("failureClass", "invalid_request"), error.details());
        assertEquals("trusted-request", response.getHeader("X-Request-Id"));
        assertEquals("trusted-trace", response.getHeader("X-Trace-Id"));
        assertEquals(
            1.0,
            meters.get("approval.migration.operations.read")
                .tags(
                    "operation", "plan_list",
                    "result", "failure",
                    "failure_class", "invalid_request"
                )
                .counter()
                .count()
        );
        assertLowCardinalityTagKeys(meters);
    }

    @Test
    void countsUnknownSecurityBodiesWithoutRewritingAuthorityEvidence() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApprovalMigrationOperationsObservabilityAdvice advice = advice(meters);
        Map<String, String> forbidden = Map.of("error", "forbidden");

        assertSame(forbidden, write(
            advice,
            forbidden,
            "/api/approval/mobile/process-instance-operations/plans/plan-1",
            403,
            new MockHttpServletResponse()
        ));
        assertEquals(
            1.0,
            meters.get("approval.migration.operations.read")
                .tags(
                    "operation", "plan_detail",
                    "result", "failure",
                    "failure_class", "forbidden"
                )
                .counter()
                .count()
        );
        assertLowCardinalityTagKeys(meters);
    }

    private static ApprovalMigrationOperationsObservabilityAdvice advice(
        SimpleMeterRegistry meters
    ) {
        return new ApprovalMigrationOperationsObservabilityAdvice(
            meters,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static Object write(
        ApprovalMigrationOperationsObservabilityAdvice advice,
        Object body,
        String path,
        int status,
        MockHttpServletResponse servletResponse
    ) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", path);
        servletRequest.addHeader("X-Request-Id", "header-request");
        servletRequest.addHeader("X-Trace-Id", "header-trace");
        servletResponse.setStatus(status);
        ServletServerHttpResponse serverResponse = new ServletServerHttpResponse(servletResponse);
        Object result = advice.beforeBodyWrite(
            body,
            null,
            MediaType.APPLICATION_JSON,
            MappingJackson2HttpMessageConverter.class,
            new ServletServerHttpRequest(servletRequest),
            serverResponse
        );
        try {
            serverResponse.close();
        } catch (Exception exception) {
            throw new IllegalStateException("failed to flush operations response headers", exception);
        }
        return result;
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
            "reason"
        );
        for (Meter meter : meters.getMeters()) {
            meter.getId().getTags().forEach(tag ->
                assertFalse(forbidden.contains(tag.getKey()), "forbidden metric tag " + tag.getKey())
            );
        }
    }
}
