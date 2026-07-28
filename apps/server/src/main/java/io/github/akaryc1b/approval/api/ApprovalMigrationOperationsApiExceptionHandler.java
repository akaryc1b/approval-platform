package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationOperationsQuery.MigrationOperationsNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = ApprovalMigrationOperationsController.class)
public class ApprovalMigrationOperationsApiExceptionHandler {

    @ExceptionHandler({
        IllegalArgumentException.class,
        MissingRequestHeaderException.class
    })
    ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(
            400,
            "APPROVAL_MIGRATION_OPERATIONS_INVALID_REQUEST",
            safeMessage(exception),
            request
        );
    }

    @ExceptionHandler(MigrationOperationsNotFoundException.class)
    ResponseEntity<ApiError> notFound(Exception exception, HttpServletRequest request) {
        return response(
            404,
            "APPROVAL_MIGRATION_OPERATIONS_NOT_FOUND",
            safeMessage(exception),
            request
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return response(
            500,
            "APPROVAL_MIGRATION_OPERATIONS_READ_FAILED",
            "Migration operations evidence could not be read",
            request
        );
    }

    private static ResponseEntity<ApiError> response(
        int status,
        String code,
        String message,
        HttpServletRequest request
    ) {
        String requestId = requestId(request);
        return ResponseEntity.status(status)
            .header("X-Request-Id", requestId)
            .body(new ApiError(code, message, requestId, Instant.now()));
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "request is invalid" : message;
    }

    private static String requestId(HttpServletRequest request) {
        String value = request.getHeader("X-Request-Id");
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    public record ApiError(
        String code,
        String message,
        String requestId,
        Instant occurredAt
    ) {
    }
}
