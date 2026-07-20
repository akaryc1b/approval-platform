package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore.ConsistencyCheckNotFoundException;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

/** Stable errors for detect-only consistency administration. */
@RestControllerAdvice(assignableTypes = ApprovalConsistencyController.class)
public class ApprovalConsistencyApiExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(400, "INVALID_REQUEST", safeMessage(exception), false, request);
    }

    @ExceptionHandler(ConsistencyCheckNotFoundException.class)
    ResponseEntity<ApiError> notFound(
        ConsistencyCheckNotFoundException exception,
        HttpServletRequest request
    ) {
        return response(
            404,
            "APPROVAL_CONSISTENCY_CHECK_NOT_FOUND",
            exception.getMessage(),
            false,
            request
        );
    }

    @ExceptionHandler(IdempotencyGuard.IdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotencyConflict(
        IdempotencyGuard.IdempotencyConflictException exception,
        HttpServletRequest request
    ) {
        return response(409, "IDEMPOTENCY_CONFLICT", exception.getMessage(), false, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return response(
            500,
            "APPROVAL_CONSISTENCY_CHECK_FAILED",
            "approval consistency check failed",
            true,
            request
        );
    }

    private static ResponseEntity<ApiError> response(
        int status,
        String code,
        String message,
        boolean retryable,
        HttpServletRequest request
    ) {
        String requestId = requestId(request);
        return ResponseEntity.status(status)
            .header("X-Request-Id", requestId)
            .body(new ApiError(code, message, retryable, requestId, Instant.now()));
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
        boolean retryable,
        String requestId,
        Instant occurredAt
    ) {
    }
}
