package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalFormRuntimeService;
import io.github.akaryc1b.approval.application.FormDataValidator;
import io.github.akaryc1b.approval.application.PurchasePaymentFormSubmissionStarter;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = {
    ApprovalFormRuntimeController.class,
    ApprovalFormSubmissionController.class
})
public class ApprovalFormRuntimeApiExceptionHandler {

    @ExceptionHandler({
        FormDataValidator.FormDataValidationException.class,
        ApprovalFormRuntimeService.FieldPermissionException.class
    })
    ResponseEntity<ApprovalApiExceptionHandler.ApiError> invalidForm(
        Exception exception,
        HttpServletRequest request
    ) {
        return response(400, "FORM_DATA_INVALID", exception.getMessage(), false, request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApprovalApiExceptionHandler.ApiError> invalidRequest(
        Exception exception,
        HttpServletRequest request
    ) {
        return response(400, "INVALID_REQUEST", exception.getMessage(), false, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApprovalApiExceptionHandler.ApiError> invalidFields(
        MethodArgumentNotValidException exception,
        HttpServletRequest request
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .sorted()
            .collect(java.util.stream.Collectors.joining("; "));
        return response(400, "INVALID_REQUEST", message, false, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApprovalApiExceptionHandler.ApiError> unreadable(HttpServletRequest request) {
        return response(400, "INVALID_REQUEST", "request body is invalid", false, request);
    }

    @ExceptionHandler(IdempotencyGuard.IdempotencyConflictException.class)
    ResponseEntity<ApprovalApiExceptionHandler.ApiError> idempotencyConflict(
        IdempotencyGuard.IdempotencyConflictException exception,
        HttpServletRequest request
    ) {
        return response(409, "IDEMPOTENCY_CONFLICT", exception.getMessage(), false, request);
    }

    @ExceptionHandler(ApprovalProjectionStore.ProjectionConflictException.class)
    ResponseEntity<ApprovalApiExceptionHandler.ApiError> projectionConflict(
        ApprovalProjectionStore.ProjectionConflictException exception,
        HttpServletRequest request
    ) {
        return response(409, "PROJECTION_CONFLICT", exception.getMessage(), false, request);
    }

    @ExceptionHandler(PurchasePaymentAssigneeResolver.AssigneeResolutionException.class)
    ResponseEntity<ApprovalApiExceptionHandler.ApiError> assigneeResolution(
        PurchasePaymentAssigneeResolver.AssigneeResolutionException exception,
        HttpServletRequest request
    ) {
        return response(422, exception.code(), exception.getMessage(), false, request);
    }

    @ExceptionHandler(PurchasePaymentFormSubmissionStarter.UnsupportedFormSubmissionException.class)
    ResponseEntity<ApprovalApiExceptionHandler.ApiError> unsupported(
        PurchasePaymentFormSubmissionStarter.UnsupportedFormSubmissionException exception,
        HttpServletRequest request
    ) {
        return response(422, "FORM_SUBMISSION_UNSUPPORTED", exception.getMessage(), false, request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApprovalApiExceptionHandler.ApiError> invalidState(HttpServletRequest request) {
        return response(409, "INVALID_STATE", "form state conflict", false, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApprovalApiExceptionHandler.ApiError> unexpected(HttpServletRequest request) {
        return response(500, "FORM_COMMAND_FAILED", "form command failed", true, request);
    }

    private static ResponseEntity<ApprovalApiExceptionHandler.ApiError> response(
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
            .body(new ApprovalApiExceptionHandler.ApiError(
                code, message, retryable, requestId, Instant.now()
            ));
    }
}
