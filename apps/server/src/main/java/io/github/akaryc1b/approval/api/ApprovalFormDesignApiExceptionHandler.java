package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalFormDesignService;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = {
    ApprovalFormDesignController.class,
    ApprovalFormPackageController.class
})
public class ApprovalFormDesignApiExceptionHandler {

    @ExceptionHandler({
        IllegalArgumentException.class,
        MissingRequestHeaderException.class
    })
    ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(400, "FORM_DESIGN_INVALID_REQUEST", safeMessage(exception), false, request);
    }

    @ExceptionHandler({
        ApprovalFormDesignService.DraftNotFoundException.class,
        ApprovalFormDesignService.PublishedSchemaNotFoundException.class
    })
    ResponseEntity<ApiError> notFound(Exception exception, HttpServletRequest request) {
        return response(404, "FORM_DESIGN_NOT_FOUND", safeMessage(exception), false, request);
    }

    @ExceptionHandler(ApprovalFormDesignService.DraftRevisionConflictException.class)
    ResponseEntity<ApiError> revisionConflict(
        ApprovalFormDesignService.DraftRevisionConflictException exception,
        HttpServletRequest request
    ) {
        return response(
            409,
            "FORM_DESIGN_REVISION_CONFLICT",
            exception.getMessage(),
            false,
            request
        );
    }

    @ExceptionHandler(ApprovalFormDesignService.DraftStateConflictException.class)
    ResponseEntity<ApiError> stateConflict(
        ApprovalFormDesignService.DraftStateConflictException exception,
        HttpServletRequest request
    ) {
        return response(
            409,
            "FORM_DESIGN_STATE_CONFLICT",
            exception.getMessage(),
            false,
            request
        );
    }

    @ExceptionHandler(ApprovalFormDesignService.PackageVersionConflictException.class)
    ResponseEntity<ApiError> packageConflict(
        ApprovalFormDesignService.PackageVersionConflictException exception,
        HttpServletRequest request
    ) {
        return response(
            409,
            "FORM_PACKAGE_VERSION_CONFLICT",
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

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> invalidState(
        IllegalStateException exception,
        HttpServletRequest request
    ) {
        return response(409, "FORM_DESIGN_INVALID_STATE", safeMessage(exception), false, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return response(
            500,
            "FORM_DESIGN_COMMAND_FAILED",
            "form design command failed",
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
