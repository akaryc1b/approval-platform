package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.OperationalFailureConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore.OperationalFailureNotFoundException;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard.IdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

/** Stable API errors for operational queue administration. */
@RestControllerAdvice(assignableTypes = {
    ApprovalOperationalFailureQueryController.class,
    ApprovalOperationalFailureReplayController.class
})
public class ApprovalOperationalFailureApiExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(400, "INVALID_REQUEST", safeMessage(exception), false, request);
    }

    @ExceptionHandler(OperationalFailureNotFoundException.class)
    ResponseEntity<ApiError> notFound(
        OperationalFailureNotFoundException exception,
        HttpServletRequest request
    ) {
        return response(
            404,
            "APPROVAL_OPERATIONAL_FAILURE_NOT_FOUND",
            exception.getMessage(),
            false,
            request
        );
    }

    @ExceptionHandler({
        OperationalFailureConflictException.class,
        NotificationConflictException.class,
        IdempotencyConflictException.class
    })
    ResponseEntity<ApiError> conflict(RuntimeException exception, HttpServletRequest request) {
        return response(409, "APPROVAL_OPERATIONAL_FAILURE_CONFLICT", exception.getMessage(), false, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return response(
            500,
            "APPROVAL_OPERATIONAL_FAILURE_OPERATION_FAILED",
            "operational queue operation failed",
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
