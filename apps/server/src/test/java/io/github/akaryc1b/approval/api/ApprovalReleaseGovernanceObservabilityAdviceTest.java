package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentResult;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentStatus;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovalReleaseGovernanceObservabilityAdviceTest {

    private static final Instant NOW = Instant.parse("2026-07-23T06:00:00Z");

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void assessmentSuccessUsesClosedLowCardinalityTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApprovalReleaseGovernanceObservabilityAdvice advice = advice(meters);
        AssessmentResult assessment = mock(AssessmentResult.class);
        when(assessment.status()).thenReturn(AssessmentStatus.READY);
        when(assessment.complete()).thenReturn(true);

        Object returned = write(
            advice,
            assessment,
            "/api/approval/version-management/purchasePayment/releases/1/migration-dry-run",
            new MockHttpServletResponse()
        );

        assertSame(assessment, returned);
        assertEquals(
            1.0,
            meters.get("approval.release.migration.assessment")
                .tags("status", "ready", "completeness", "complete", "result", "success")
                .counter()
                .count()
        );
        assertLowCardinalityTagKeys(meters);
    }

    @Test
    void governedErrorsExposeTraceTimestampAndBoundedDetails() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApprovalReleaseGovernanceObservabilityAdvice advice = advice(meters);
        MDC.put("requestId", "trusted-request");
        MDC.put("traceId", "trusted-trace");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var legacy = new ApprovalProcessReleaseMigrationAssessmentApiExceptionHandler.ApiError(
            "APPROVAL_RELEASE_MIGRATION_DRY_RUN_INVALID_REQUEST",
            "invalid\nreason",
            "browser-request",
            NOW.minusSeconds(10)
        );

        Object returned = write(
            advice,
            legacy,
            "/api/approval/version-management/purchasePayment/releases/1/migration-dry-run",
            response
        );
        var error = (ApprovalReleaseGovernanceObservabilityAdvice.GovernanceApiError) returned;

        assertEquals("APPROVAL_RELEASE_MIGRATION_DRY_RUN_INVALID_REQUEST", error.errorCode());
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
            meters.get("approval.release.migration.assessment")
                .tags("status", "unknown", "completeness", "unknown", "result", "failure")
                .counter()
                .count()
        );
        assertLowCardinalityTagKeys(meters);
    }

    @Test
    void lifecycleSuccessAndFailureNeverUseResourceIdentityTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApprovalReleaseGovernanceObservabilityAdvice advice = advice(meters);
        Object publication = new Object();

        assertSame(
            publication,
            write(
                advice,
                publication,
                "/api/approval/process-design-drafts/85000000-0000-0000-0000-000000000001/publish",
                new MockHttpServletResponse()
            )
        );
        var conflict = new ApprovalReleaseDeploymentApiExceptionHandler.ApiError(
            "APPROVAL_RELEASE_DEPLOYMENT_CONFLICT",
            "Release activation conflict",
            "request-conflict",
            NOW.minusSeconds(5)
        );
        Object returned = write(
            advice,
            conflict,
            "/api/approval/version-management/purchasePayment/releases/2/activate",
            new MockHttpServletResponse()
        );

        assertTrue(returned instanceof ApprovalReleaseGovernanceObservabilityAdvice.GovernanceApiError);
        assertEquals(
            1.0,
            meters.get("approval.release.lifecycle.operation")
                .tags(
                    "operation", "publish",
                    "result", "success",
                    "failure_class", "none"
                )
                .counter()
                .count()
        );
        assertEquals(
            1.0,
            meters.get("approval.release.lifecycle.operation")
                .tags(
                    "operation", "activate",
                    "result", "failure",
                    "failure_class", "conflict"
                )
                .counter()
                .count()
        );
        assertLowCardinalityTagKeys(meters);
    }

    private static ApprovalReleaseGovernanceObservabilityAdvice advice(
        SimpleMeterRegistry meters
    ) {
        return new ApprovalReleaseGovernanceObservabilityAdvice(
            meters,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static Object write(
        ApprovalReleaseGovernanceObservabilityAdvice advice,
        Object body,
        String path,
        MockHttpServletResponse servletResponse
    ) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", path);
        servletRequest.addHeader("X-Request-Id", "header-request");
        servletRequest.addHeader("X-Trace-Id", "header-trace");
        return advice.beforeBodyWrite(
            body,
            null,
            MediaType.APPLICATION_JSON,
            MappingJackson2HttpMessageConverter.class,
            new ServletServerHttpRequest(servletRequest),
            new ServletServerHttpResponse(servletResponse)
        );
    }

    private static void assertLowCardinalityTagKeys(SimpleMeterRegistry meters) {
        Set<String> forbidden = Set.of(
            "tenantId",
            "operatorId",
            "definitionKey",
            "releaseVersion",
            "instanceId",
            "taskId",
            "packageHash",
            "reportHash",
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
