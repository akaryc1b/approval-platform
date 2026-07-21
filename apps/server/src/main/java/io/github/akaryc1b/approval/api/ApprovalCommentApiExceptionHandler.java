package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalCommentService.CommentOperationException;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore.CommentNotFoundException;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

/** Stable comment-specific HTTP errors without widening unrelated approval handlers. */
@RestControllerAdvice(assignableTypes = ApprovalCommentController.class)
public class ApprovalCommentApiExceptionHandler {

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        MissingRequestHeaderException.class
    })
    ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(400, "INVALID_REQUEST", safeMessage(exception), false, request);
    }

    @ExceptionHandler(CommentOperationException.class)
    ResponseEntity<ApiError> commentOperation(
        CommentOperationException exception,
        HttpServletRequest request
    ) {
        return response(
            exception.status(),
            exception.code(),
            exception.getMessage(),
            false,
            request
        );
    }

    @ExceptionHandler(CommentNotFoundException.class)
    ResponseEntity<ApiError> commentNotFound(
        CommentNotFoundException exception,
        HttpServletRequest request
    ) {
        return response(404, "APPROVAL_COMMENT_NOT_FOUND", exception.getMessage(), false, request);
    }

    @ExceptionHandler(CommentConflictException.class)
    ResponseEntity<ApiError> commentConflict(
        CommentConflictException exception,
        HttpServletRequest request
    ) {
        return response(409, exception.code(), exception.getMessage(), false, request);
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
            "APPROVAL_COMMENT_COMMAND_FAILED",
            "approval comment command failed",
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
