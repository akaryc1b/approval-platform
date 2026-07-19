package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalDesignExceptions;
import io.github.akaryc1b.approval.application.ApprovalReleasePreflightService;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = {
    ApprovalBatchSimulationController.class,
    ApprovalDesignController.class,
    ApprovalReleasePackageController.class,
    ApprovalReleasePreflightController.class,
    ApprovalVersionManagementController.class
})
public class ApprovalDesignApiExceptionHandler {

    @ExceptionHandler({
        IllegalArgumentException.class,
        MissingRequestHeaderException.class
    })
    ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(
            400,
            "APPROVAL_DESIGN_INVALID_REQUEST",
            safeMessage(exception),
            false,
            request
        );
    }

    @ExceptionHandler({
        ApprovalDesignExceptions.DraftNotFound.class,
        ApprovalDesignExceptions.FormPackageNotFound.class,
        ApprovalDesignExceptions.PublishedDefinitionNotFound.class,
        ApprovalReleasePreflightService.ReleasePackageNotFoundException.class
    })
    ResponseEntity<ApiError> notFound(Exception exception, HttpServletRequest request) {
        return response(
            404,
            "APPROVAL_DESIGN_NOT_FOUND",
            safeMessage(exception),
            false,
            request
        );
    }

    @ExceptionHandler(ApprovalDefinitionValidator.DefinitionValidationException.class)
    ResponseEntity<ApiError> validationFailed(
        ApprovalDefinitionValidator.DefinitionValidationException exception,
        HttpServletRequest request
    ) {
        return response(
            422,
            "APPROVAL_DESIGN_VALIDATION_FAILED",
            exception.getMessage(),
            false,
            request
        );
    }

    @ExceptionHandler(ApprovalDesignExceptions.WarningAcknowledgementRequired.class)
    ResponseEntity<ApiError> warningAcknowledgementRequired(
        ApprovalDesignExceptions.WarningAcknowledgementRequired exception,
        HttpServletRequest request
    ) {
        return response(
            422,
            "APPROVAL_PREFLIGHT_WARNING_ACKNOWLEDGEMENT_REQUIRED",
            exception.getMessage(),
            false,
            request
        );
    }

    @ExceptionHandler(ApprovalDesignExceptions.DraftRevisionConflict.class)
    ResponseEntity<ApiError> revisionConflict(
        ApprovalDesignExceptions.DraftRevisionConflict exception,
        HttpServletRequest request
    ) {
        return response(
            409,
            "APPROVAL_DESIGN_REVISION_CONFLICT",
            exception.getMessage(),
            false,
            request
        );
    }

    @ExceptionHandler({
        ApprovalDesignExceptions.DraftStateConflict.class,
        ApprovalDesignExceptions.DefinitionVersionConflict.class,
        ApprovalDesignExceptions.ReleaseVersionConflict.class,
        ApprovalDesignExceptions.CompiledArtifactConflict.class,
        ApprovalDesignExceptions.FormPackageIntegrity.class,
        ApprovalDesignExceptions.PreflightConflict.class,
        IdempotencyGuard.IdempotencyConflictException.class
    })
    ResponseEntity<ApiError> stateConflict(Exception exception, HttpServletRequest request) {
        return response(
            409,
            "APPROVAL_DESIGN_STATE_CONFLICT",
            safeMessage(exception),
            false,
            request
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> invalidState(
        IllegalStateException exception,
        HttpServletRequest request
    ) {
        return response(
            409,
            "APPROVAL_DESIGN_INVALID_STATE",
            "Approval design state conflict",
            false,
            request
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return response(
            500,
            "APPROVAL_DESIGN_COMMAND_FAILED",
            "Approval design command failed",
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
