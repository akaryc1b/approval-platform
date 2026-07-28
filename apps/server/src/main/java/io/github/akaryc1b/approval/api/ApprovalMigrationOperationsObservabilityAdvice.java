package io.github.akaryc1b.approval.api;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Adds bounded tracing and closed low-cardinality metrics to the M5-E1 read-only Operations API.
 * Tenant, plan, instance, request and trace identities never become metric tags.
 */
@RestControllerAdvice
public final class ApprovalMigrationOperationsObservabilityAdvice
    implements ResponseBodyAdvice<Object> {

    private static final int MAX_MESSAGE_CODE_POINTS = 512;
    private static final int MAX_EVIDENCE_CODE_POINTS = 128;
    private static final String METRIC = "approval.migration.operations.read";
    private static final String MANAGEMENT_PREFIX =
        "/api/approval/management/process-instance-operations";
    private static final String MOBILE_PREFIX =
        "/api/approval/mobile/process-instance-operations";

    private final MeterRegistry meters;
    private final Clock clock;

    public ApprovalMigrationOperationsObservabilityAdvice(
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
        Operation operation = Operation.resolve(request.getURI().getPath());
        if (operation == Operation.NONE || body == null) {
            return body;
        }

        int status = responseStatus(response);
        FailureClass failureClass = FailureClass.resolve(status);
        boolean success = status >= 200 && status < 400;
        meters.counter(
            METRIC,
            "operation", operation.metricValue(),
            "result", success ? "success" : "failure",
            "failure_class", success ? "none" : failureClass.metricValue()
        ).increment();

        if (!success && body instanceof ApprovalMigrationOperationsApiExceptionHandler.ApiError error) {
            return governedError(error, failureClass, request, response);
        }
        return body;
    }

    private OperationsApiError governedError(
        ApprovalMigrationOperationsApiExceptionHandler.ApiError legacy,
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
        return new OperationsApiError(
            legacy.code(),
            boundedMessage(legacy.message()),
            false,
            requestId,
            traceId,
            clock.instant(),
            Map.of("failureClass", failureClass.metricValue())
        );
    }

    private static int responseStatus(ServerHttpResponse response) {
        if (response instanceof ServletServerHttpResponse servletResponse) {
            return servletResponse.getServletResponse().getStatus();
        }
        return 200;
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
            ? "Migration operations evidence could not be read"
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
        return value.isEmpty() ? "Migration operations evidence could not be read" : value;
    }

    public record OperationsApiError(
        String errorCode,
        String message,
        boolean retryable,
        String requestId,
        String traceId,
        Instant timestamp,
        Map<String, String> details
    ) {
        public OperationsApiError {
            errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
            message = Objects.requireNonNull(message, "message must not be null");
            requestId = Objects.requireNonNull(requestId, "requestId must not be null");
            traceId = Objects.requireNonNull(traceId, "traceId must not be null");
            timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    private enum Operation {
        NONE("none"),
        SUMMARY("summary"),
        PLAN_LIST("plan_list"),
        PLAN_DETAIL("plan_detail"),
        INSTANCE_LIST("instance_list");

        private final String metricValue;

        Operation(String metricValue) {
            this.metricValue = metricValue;
        }

        static Operation resolve(String path) {
            String relative = relativePath(path);
            if (relative == null) {
                return NONE;
            }
            if ("/summary".equals(relative)) {
                return SUMMARY;
            }
            if ("/plans".equals(relative)) {
                return PLAN_LIST;
            }
            if (!relative.startsWith("/plans/")) {
                return NONE;
            }
            String remainder = relative.substring("/plans/".length());
            if (!remainder.isEmpty() && !remainder.contains("/")) {
                return PLAN_DETAIL;
            }
            String instancesSuffix = "/instances";
            if (!remainder.endsWith(instancesSuffix)) {
                return NONE;
            }
            String planId = remainder.substring(0, remainder.length() - instancesSuffix.length());
            return !planId.isEmpty() && !planId.contains("/") ? INSTANCE_LIST : NONE;
        }

        private static String relativePath(String path) {
            if (path == null) {
                return null;
            }
            if (path.startsWith(MANAGEMENT_PREFIX)) {
                return path.substring(MANAGEMENT_PREFIX.length());
            }
            if (path.startsWith(MOBILE_PREFIX)) {
                return path.substring(MOBILE_PREFIX.length());
            }
            return null;
        }

        String metricValue() {
            return metricValue;
        }
    }

    private enum FailureClass {
        NONE("none"),
        INVALID_REQUEST("invalid_request"),
        UNAUTHENTICATED("unauthenticated"),
        FORBIDDEN("forbidden"),
        NOT_FOUND("not_found"),
        CONFLICT("conflict"),
        RATE_LIMITED("rate_limited"),
        INTERNAL("internal");

        private final String metricValue;

        FailureClass(String metricValue) {
            this.metricValue = metricValue;
        }

        static FailureClass resolve(int status) {
            return switch (status) {
                case 400, 405, 422 -> INVALID_REQUEST;
                case 401 -> UNAUTHENTICATED;
                case 403 -> FORBIDDEN;
                case 404 -> NOT_FOUND;
                case 409 -> CONFLICT;
                case 429 -> RATE_LIMITED;
                default -> status >= 400 ? INTERNAL : NONE;
            };
        }

        String metricValue() {
            return metricValue;
        }
    }
}
