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
 * Adds bounded tracing and closed low-cardinality metrics to the M5-E1/E2 read-only
 * Operations API. Tenant, plan, instance, request and trace identities never become tags.
 */
@RestControllerAdvice
public final class ApprovalMigrationOperationsObservabilityAdvice
    implements ResponseBodyAdvice<Object> {

    private static final int MAX_MESSAGE_CODE_POINTS = 512;
    private static final int MAX_EVIDENCE_CODE_POINTS = 128;

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
        int status = responseStatus(response);
        var classification = ApprovalMigrationOperationsTelemetryClassifier.classify(
            request.getURI().getPath(),
            status
        );
        if (classification.operation()
            == ApprovalMigrationOperationsTelemetryClassifier.Operation.NONE || body == null) {
            return body;
        }

        meters.counter(
            ApprovalMigrationOperationsTelemetryClassifier.READ_COUNT_METRIC,
            "operation", classification.operation().metricValue(),
            "result", classification.result().metricValue(),
            "failure_class", classification.failureClass().metricValue()
        ).increment();

        if (classification.result()
            == ApprovalMigrationOperationsTelemetryClassifier.Result.FAILURE
            && body instanceof ApprovalMigrationOperationsApiExceptionHandler.ApiError error) {
            return governedError(error, classification.failureClass(), request, response);
        }
        return body;
    }

    private OperationsApiError governedError(
        ApprovalMigrationOperationsApiExceptionHandler.ApiError legacy,
        ApprovalMigrationOperationsTelemetryClassifier.FailureClass failureClass,
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
}
