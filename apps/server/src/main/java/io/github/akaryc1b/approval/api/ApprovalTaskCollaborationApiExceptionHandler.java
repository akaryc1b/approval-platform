package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore;
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

@RestControllerAdvice(assignableTypes = ApprovalTaskCollaborationController.class)
public class ApprovalTaskCollaborationApiExceptionHandler {

    @ExceptionHandler(ApprovalTaskCollaborationStore.CollaborationValidationException.class)
    ResponseEntity<ApiError> governedValidation(
        ApprovalTaskCollaborationStore.CollaborationValidationException exception,
        HttpServletRequest request
    ) {
        return response(400, exception.code(), safeMessage(exception), false, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> invalidModeOrPayload(
        HttpMessageNotReadableException exception,
        HttpServletRequest request
    ) {
        return response(
            400,
            "APPROVAL_TASK_COLLABORATION_INVALID_MODE_OR_PAYLOAD",
            safeMessage(exception),
            false,
            request
        );
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        MissingRequestHeaderException.class
    })
    ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(
            400,
            "APPROVAL_TASK_COLLABORATION_INVALID_REQUEST",
            safeMessage(exception),
            false,
            request
        );
    }

    @ExceptionHandler(ApprovalIdentityDirectory.IdentityResolutionException.class)
    ResponseEntity<ApiError> identityFailure(
        ApprovalIdentityDirectory.IdentityResolutionException exception,
        HttpServletRequest request
    ) {
        return response(
            exception.retryable() ? 503 : 422,
            exception.code(),
            safeMessage(exception),
            exception.retryable(),
            request
        );
    }

    @ExceptionHandler(ApprovalTaskCollaborationStore.CollaborationAuthorizationException.class)
    ResponseEntity<ApiError> unauthorized(Exception exception, HttpServletRequest request) {
        return response(
            403,
            "APPROVAL_TASK_COLLABORATION_UNAUTHORIZED",
            safeMessage(exception),
            false,
            request
        );
    }

    @ExceptionHandler(ApprovalTaskCollaborationStore.CollaborationNotFoundException.class)
    ResponseEntity<ApiError> notFound(Exception exception, HttpServletRequest request) {
        return response(
            404,
            "APPROVAL_TASK_COLLABORATION_NOT_FOUND",
            safeMessage(exception),
            false,
            request
        );
    }

    @ExceptionHandler(ApprovalTaskCollaborationStore.CollaborationConflictException.class)
    ResponseEntity<ApiError> governedConflict(
        ApprovalTaskCollaborationStore.CollaborationConflictException exception,
        HttpServletRequest request
    ) {
        return response(409, exception.code(), safeMessage(exception), false, request);
    }

    @ExceptionHandler(IdempotencyGuard.IdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotencyConflict(
        IdempotencyGuard.IdempotencyConflictException exception,
        HttpServletRequest request
    ) {
        return response(
            409,
            "APPROVAL_TASK_COLLABORATION_IDEMPOTENCY_CONFLICT",
            safeMessage(exception),
            false,
            request
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return response(
            500,
            "APPROVAL_TASK_COLLABORATION_COMMAND_FAILED",
            "Approval task collaboration command failed",
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
