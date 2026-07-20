package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = ApprovalDelegationController.class)
public class ApprovalDelegationApiExceptionHandler {

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        MissingRequestHeaderException.class
    })
    ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(
            400,
            "APPROVAL_DELEGATION_INVALID_REQUEST",
            safeMessage(exception),
            false,
            request
        );
    }

    @ExceptionHandler(ApprovalDelegationStore.DelegationNotFoundException.class)
    ResponseEntity<ApiError> notFound(Exception exception, HttpServletRequest request) {
        return response(
            404,
            "APPROVAL_DELEGATION_NOT_FOUND",
            safeMessage(exception),
            false,
            request
        );
    }

    @ExceptionHandler({
        ApprovalDelegationStore.DelegationConflictException.class,
        IdempotencyGuard.IdempotencyConflictException.class
    })
    ResponseEntity<ApiError> conflict(Exception exception, HttpServletRequest request) {
        return response(
            409,
            "APPROVAL_DELEGATION_CONFLICT",
            safeMessage(exception),
            false,
            request
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return response(
            500,
            "APPROVAL_DELEGATION_COMMAND_FAILED",
            "Approval delegation command failed",
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
