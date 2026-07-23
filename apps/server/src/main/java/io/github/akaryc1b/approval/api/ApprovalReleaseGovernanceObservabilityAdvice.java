package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalProcessReleaseMigrationAssessmentService.AssessmentResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Standardizes release-governance API errors and records only closed, low-cardinality metrics.
 * Resource identities remain in traces and immutable audit evidence, never in metric tags.
 */
@RestControllerAdvice
public final class ApprovalReleaseGovernanceObservabilityAdvice
    implements ResponseBodyAdvice<Object> {

    private static final int MAX_MESSAGE_CODE_POINTS = 512;
    private static final int MAX_EVIDENCE_CODE_POINTS = 128;
    private static final String LIFECYCLE_METRIC = "approval.release.lifecycle.operation";
    private static final String ASSESSMENT_METRIC = "approval.release.migration.assessment";

    private final MeterRegistry meters;
    private final Clock clock;

    public ApprovalReleaseGovernanceObservabilityAdvice(
        MeterRegistry meters,
        Clock approvalClock
    ) {
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
        this.clock = Objects.requireNonNull(approvalClock, "approvalClock must not be null");
    }

    @Override
    public boolean supports(
        MethodParameter returnType,
        Class<? extends HttpMessageConverter<?>> converterType
    ) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
        Object body,
        MethodParameter returnType,
        MediaType selectedContentType,
        Class<? extends HttpMessageConverter<?>> selectedConverterType,
        ServerHttpRequest request,
        ServerHttpResponse response
    ) {
        Endpoint endpoint = Endpoint.resolve(request.getURI().getPath());
        if (endpoint == Endpoint.NONE || body == null) {
            return body;
        }

        LegacyError legacyError = legacyError(body);
        if (legacyError != null) {
            FailureClass failureClass = FailureClass.resolve(legacyError.errorCode());
            recordFailure(endpoint, failureClass);
            return governedError(legacyError, failureClass, request, response);
        }

        if (endpoint == Endpoint.ASSESSMENT && body instanceof AssessmentResult assessment) {
            meters.counter(
                ASSESSMENT_METRIC,
                "status", metric(assessment.status()),
                "completeness", assessment.complete() ? "complete" : "partial",
                "result", "success"
            ).increment();
        } else if (endpoint.lifecycle()) {
            meters.counter(
                LIFECYCLE_METRIC,
                "operation", endpoint.metricValue(),
                "result", "success",
                "failure_class", "none"
            ).increment();
        }
        return body;
    }

    private void recordFailure(Endpoint endpoint, FailureClass failureClass) {
        if (endpoint == Endpoint.ASSESSMENT) {
            meters.counter(
                ASSESSMENT_METRIC,
                "status", "unknown",
                "completeness", "unknown",
                "result", "failure"
            ).increment();
            return;
        }
        meters.counter(
            LIFECYCLE_METRIC,
            "operation", endpoint.metricValue(),
            "result", "failure",
            "failure_class", failureClass.metricValue()
        ).increment();
    }

    private GovernanceApiError governedError(
        LegacyError legacy,
        FailureClass failureClass,
        ServerHttpRequest request,
        ServerHttpResponse response
    ) {
        String requestId = firstEvidence(
            MDC.get("requestId"),
            legacy.requestId(),
            request.getHeaders().getFirst("X-Request-Id"),
            UUID.randomUUID().toString()
        );
        String traceId = firstEvidence(
            MDC.get("traceId"),
            request.getHeaders().getFirst("X-Trace-Id"),
            requestId
        );
        response.getHeaders().set("X-Request-Id", requestId);
        response.getHeaders().set("X-Trace-Id", traceId);
        return new GovernanceApiError(
            legacy.errorCode(),
            boundedMessage(legacy.message()),
            legacy.retryable(),
            requestId,
            traceId,
            clock.instant(),
            Map.of("failureClass", failureClass.metricValue())
        );
    }

    private static LegacyError legacyError(Object body) {
        if (body instanceof ApprovalProcessReleaseMigrationAssessmentApiExceptionHandler.ApiError error) {
            return new LegacyError(
                error.code(), error.message(), false, error.requestId(), error.occurredAt()
            );
        }
        if (body instanceof ApprovalProcessReleaseDispositionApiExceptionHandler.ApiError error) {
            return new LegacyError(
                error.code(), error.message(), false, error.requestId(), error.occurredAt()
            );
        }
        if (body instanceof ApprovalReleaseDeploymentApiExceptionHandler.ApiError error) {
            return new LegacyError(
                error.code(), error.message(), false, error.requestId(), error.occurredAt()
            );
        }
        if (body instanceof ApprovalDesignApiExceptionHandler.ApiError error) {
            return new LegacyError(
                error.code(), error.message(), error.retryable(), error.requestId(), error.occurredAt()
            );
        }
        return null;
    }

    private static String firstEvidence(String... candidates) {
        for (String candidate : candidates) {
            String bounded = boundedEvidence(candidate);
            if (bounded != null) {
                return bounded;
            }
        }
        throw new IllegalStateException("request evidence fallback is unavailable");
    }

    private static String boundedEvidence(String supplied) {
        if (supplied == null) {
            return null;
        }
        String value = supplied.trim();
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > MAX_EVIDENCE_CODE_POINTS) {
            return null;
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR) {
                return null;
            }
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    private static String boundedMessage(String supplied) {
        String source = supplied == null || supplied.isBlank()
            ? "Approval governance request failed"
            : Normalizer.normalize(supplied.trim(), Normalizer.Form.NFKC);
        StringBuilder bounded = new StringBuilder();
        int count = 0;
        for (int offset = 0; offset < source.length() && count < MAX_MESSAGE_CODE_POINTS;) {
            int codePoint = source.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR) {
                bounded.append(' ');
            } else {
                bounded.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
            count++;
        }
        String value = bounded.toString().trim();
        return value.isEmpty() ? "Approval governance request failed" : value;
    }

    private static String metric(Enum<?> value) {
        return Objects.requireNonNull(value, "metric value must not be null")
            .name()
            .toLowerCase(Locale.ROOT);
    }

    public record GovernanceApiError(
        String errorCode,
        String message,
        boolean retryable,
        String requestId,
        String traceId,
        Instant timestamp,
        Map<String, String> details
    ) {
        public GovernanceApiError {
            errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
            message = Objects.requireNonNull(message, "message must not be null");
            requestId = Objects.requireNonNull(requestId, "requestId must not be null");
            traceId = Objects.requireNonNull(traceId, "traceId must not be null");
            timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    private record LegacyError(
        String errorCode,
        String message,
        boolean retryable,
        String requestId,
        Instant occurredAt
    ) {
    }

    private enum Endpoint {
        NONE("none"),
        ASSESSMENT("assessment"),
        PUBLISH("publish"),
        ACTIVATE("activate"),
        ROLLBACK("rollback"),
        DEPRECATE("deprecate"),
        RETIRE("retire");

        private final String metricValue;

        Endpoint(String metricValue) {
            this.metricValue = metricValue;
        }

        static Endpoint resolve(String path) {
            if (path == null) {
                return NONE;
            }
            if (path.endsWith("/migration-dry-run")) {
                return ASSESSMENT;
            }
            if (path.startsWith("/api/approval/process-design-drafts/")
                && path.endsWith("/publish")) {
                return PUBLISH;
            }
            if (!path.startsWith("/api/approval/version-management/")) {
                return NONE;
            }
            if (path.endsWith("/activate")) {
                return ACTIVATE;
            }
            if (path.endsWith("/rollback")) {
                return ROLLBACK;
            }
            if (path.endsWith("/deprecate")) {
                return DEPRECATE;
            }
            if (path.endsWith("/retire")) {
                return RETIRE;
            }
            return NONE;
        }

        boolean lifecycle() {
            return this != NONE && this != ASSESSMENT;
        }

        String metricValue() {
            return metricValue;
        }
    }

    private enum FailureClass {
        INVALID_REQUEST("invalid_request"),
        NOT_FOUND("not_found"),
        CONFLICT("conflict"),
        INTERNAL("internal");

        private final String metricValue;

        FailureClass(String metricValue) {
            this.metricValue = metricValue;
        }

        static FailureClass resolve(String errorCode) {
            String value = errorCode == null ? "" : errorCode.toUpperCase(Locale.ROOT);
            if (value.contains("NOT_FOUND")) {
                return NOT_FOUND;
            }
            if (value.contains("CONFLICT")
                || value.contains("HASH_MISMATCH")
                || value.contains("INTEGRITY")) {
                return CONFLICT;
            }
            if (value.contains("INVALID")
                || value.contains("VALIDATION")
                || value.contains("WARNING_ACKNOWLEDGEMENT_REQUIRED")) {
                return INVALID_REQUEST;
            }
            return INTERNAL;
        }

        String metricValue() {
            return metricValue;
        }
    }
}
