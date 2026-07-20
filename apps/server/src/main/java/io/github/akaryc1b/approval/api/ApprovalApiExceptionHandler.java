package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = PurchasePaymentController.class)
public class ApprovalApiExceptionHandler {

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        MissingRequestHeaderException.class
    })
    ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(400, "INVALID_REQUEST", safeMessage(exception), false, request);
    }

    @ExceptionHandler(PurchasePaymentAssigneeResolver.AssigneeResolutionException.class)
    ResponseEntity<ApiError> assigneeResolution(
        PurchasePaymentAssigneeResolver.AssigneeResolutionException exception,
        HttpServletRequest request
    ) {
        return response(422, exception.code(), exception.getMessage(), false, request);
    }

    @ExceptionHandler(IdempotencyGuard.IdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotencyConflict(
        IdempotencyGuard.IdempotencyConflictException exception,
        HttpServletRequest request
    ) {
        return response(409, "IDEMPOTENCY_CONFLICT", exception.getMessage(), false, request);
    }

    @ExceptionHandler(ApprovalProjectionStore.ProjectionConflictException.class)
    ResponseEntity<ApiError> projectionConflict(
        ApprovalProjectionStore.ProjectionConflictException exception,
        HttpServletRequest request
    ) {
        return response(409, "PROJECTION_CONFLICT", exception.getMessage(), false, request);
    }

    @ExceptionHandler(ApprovalTaskCollaborationStore.CollaborationConflictException.class)
    ResponseEntity<ApiError> collaborationConflict(
        ApprovalTaskCollaborationStore.CollaborationConflictException exception,
        HttpServletRequest request
    ) {
        return response(
            409,
            "APPROVAL_TASK_COLLABORATION_CONFLICT",
            exception.getMessage(),
            false,
            request
        );
    }

    @ExceptionHandler(ApprovalEngine.EngineOperationException.class)
    ResponseEntity<ApiError> engineConflict(
        ApprovalEngine.EngineOperationException exception,
        HttpServletRequest request
    ) {
        int status = "TASK_NOT_FOUND".equals(exception.code()) ? 404 : 409;
        return response(status, exception.code(), exception.getMessage(), false, request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> stateConflict(
        IllegalStateException exception,
        HttpServletRequest request
    ) {
        return response(409, "INVALID_STATE", exception.getMessage(), false, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return response(500, "APPROVAL_COMMAND_FAILED", "approval command failed", true, request);
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
