package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = ApprovalSlaExecutionManagementController.class)
public class ApprovalSlaExecutionApiExceptionHandler {

    private final Clock clock;

    public ApprovalSlaExecutionApiExceptionHandler(Clock approvalClock) {
        this.clock = approvalClock;
    }

    @ExceptionHandler(ExecutionNotFoundException.class)
    ResponseEntity<ApiError> notFound(
        ExecutionNotFoundException exception,
        HttpServletRequest request
    ) {
        return response(
            404,
            "APPROVAL_SLA_EXECUTION_NOT_FOUND",
            exception.getMessage(),
            false,
            request
        );
    }

    @ExceptionHandler(ExecutionConflictException.class)
    ResponseEntity<ApiError> conflict(
        ExecutionConflictException exception,
        HttpServletRequest request
    ) {
        return response(
            409,
            "APPROVAL_SLA_EXECUTION_CONFLICT",
            exception.getMessage(),
            false,
            request
        );
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(400, "INVALID_REQUEST", safeMessage(exception), false, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return response(
            500,
            "APPROVAL_SLA_EXECUTION_FAILED",
            "SLA execution operation failed",
            true,
            request
        );
    }

    private ResponseEntity<ApiError> response(
        int status,
        String code,
        String message,
        boolean retryable,
        HttpServletRequest request
    ) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return ResponseEntity.status(status)
            .header("X-Request-Id", requestId)
            .body(new ApiError(code, message, retryable, requestId, clock.instant()));
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "request is invalid" : message;
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
