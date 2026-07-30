package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.connector.operations.ConnectorOperationsDiagnosticsExceptions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

/** Stable redacted status mapping for Connector diagnostics only. */
@RestControllerAdvice(assignableTypes = ConnectorOperationsDiagnosticsController.class)
@ConditionalOnProperty(
    prefix = "approval.connector.operations-diagnostics",
    name = "enabled",
    havingValue = "true"
)
public class ConnectorOperationsDiagnosticsApiExceptionHandler {

    @ExceptionHandler({
        IllegalArgumentException.class,
        ConnectorOperationsDiagnosticsExceptions.InvalidRequest.class
    })
    ResponseEntity<ApiError> invalid(HttpServletRequest request) {
        return response(400, "CONNECTOR_DIAGNOSTICS_INVALID_REQUEST", false, request);
    }

    @ExceptionHandler(ConnectorOperationsDiagnosticsExceptions.NotFound.class)
    ResponseEntity<ApiError> notFound(HttpServletRequest request) {
        return response(404, "CONNECTOR_DIAGNOSTICS_NOT_FOUND", false, request);
    }

    @ExceptionHandler(ConnectorOperationsDiagnosticsExceptions.Conflict.class)
    ResponseEntity<ApiError> conflict(HttpServletRequest request) {
        return response(409, "CONNECTOR_DIAGNOSTICS_CONFLICT", false, request);
    }

    @ExceptionHandler(ConnectorOperationsDiagnosticsExceptions.ResponseTooLarge.class)
    ResponseEntity<ApiError> tooLarge(HttpServletRequest request) {
        return response(422, "CONNECTOR_DIAGNOSTICS_RESPONSE_TOO_LARGE", false, request);
    }

    @ExceptionHandler(ConnectorOperationsDiagnosticsExceptions.SourceUnavailable.class)
    ResponseEntity<ApiError> unavailable(HttpServletRequest request) {
        return response(503, "CONNECTOR_DIAGNOSTICS_SOURCE_UNAVAILABLE", true, request);
    }

    @ExceptionHandler(ConnectorOperationsDiagnosticsExceptions.InternalFailure.class)
    ResponseEntity<ApiError> internal(HttpServletRequest request) {
        return response(500, "CONNECTOR_DIAGNOSTICS_INTERNAL_FAILURE", false, request);
    }

    private static ResponseEntity<ApiError> response(
        int status,
        String code,
        boolean retryable,
        HttpServletRequest request
    ) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return ResponseEntity.status(status)
            .header("X-Request-Id", requestId)
            .cacheControl(org.springframework.http.CacheControl.noStore())
            .body(new ApiError(
                code,
                "connector diagnostics request could not be completed",
                retryable,
                requestId,
                Instant.now()
            ));
    }

    record ApiError(
        String code,
        String message,
        boolean retryable,
        String requestId,
        Instant occurredAt
    ) {
    }
}
