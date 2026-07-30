package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalArtifactTransferExceptions;
import io.github.akaryc1b.approval.application.ProcessTemplateException;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Stable, redacted error mapping for governed process-template management imports. */
@RestControllerAdvice(assignableTypes = ProcessTemplateManagementController.class)
public class ProcessTemplateManagementApiExceptionHandler {

    @ExceptionHandler(ApprovalArtifactTransferExceptions.InvalidFormat.class)
    ResponseEntity<ApiError> transferInvalidFormat(
        ApprovalArtifactTransferExceptions.InvalidFormat exception,
        HttpServletRequest request
    ) {
        return response(400, "APPROVAL_TRANSFER_INVALID_FORMAT", safeMessage(exception), false,
            request);
    }

    @ExceptionHandler(ApprovalArtifactTransferExceptions.UnsupportedVersion.class)
    ResponseEntity<ApiError> transferUnsupportedVersion(
        ApprovalArtifactTransferExceptions.UnsupportedVersion exception,
        HttpServletRequest request
    ) {
        return response(400, "APPROVAL_TRANSFER_UNSUPPORTED_VERSION", safeMessage(exception),
            false, request);
    }

    @ExceptionHandler(ApprovalArtifactTransferExceptions.TooLarge.class)
    ResponseEntity<ApiError> transferTooLarge(
        ApprovalArtifactTransferExceptions.TooLarge exception,
        HttpServletRequest request
    ) {
        return response(400, "APPROVAL_TRANSFER_TOO_LARGE", safeMessage(exception), false,
            request);
    }

    @ExceptionHandler(ApprovalArtifactTransferExceptions.HashMismatch.class)
    ResponseEntity<ApiError> transferHashMismatch(
        ApprovalArtifactTransferExceptions.HashMismatch exception,
        HttpServletRequest request
    ) {
        return response(400, "APPROVAL_TRANSFER_HASH_MISMATCH", safeMessage(exception), false,
            request);
    }

    @ExceptionHandler(ApprovalArtifactTransferExceptions.ArtifactIntegrityFailed.class)
    ResponseEntity<ApiError> transferIntegrityFailed(
        ApprovalArtifactTransferExceptions.ArtifactIntegrityFailed exception,
        HttpServletRequest request
    ) {
        return response(400, "APPROVAL_TRANSFER_ARTIFACT_INTEGRITY_FAILED",
            safeMessage(exception), false, request);
    }

    @ExceptionHandler(ApprovalArtifactTransferExceptions.SourceNotFound.class)
    ResponseEntity<ApiError> transferSourceNotFound(
        ApprovalArtifactTransferExceptions.SourceNotFound exception,
        HttpServletRequest request
    ) {
        return response(404, "APPROVAL_TRANSFER_SOURCE_NOT_FOUND", safeMessage(exception), false,
            request);
    }

    @ExceptionHandler(ApprovalArtifactTransferExceptions.FormPackageIncompatible.class)
    ResponseEntity<ApiError> transferFormPackageIncompatible(
        ApprovalArtifactTransferExceptions.FormPackageIncompatible exception,
        HttpServletRequest request
    ) {
        return response(409, "APPROVAL_TRANSFER_FORM_PACKAGE_INCOMPATIBLE",
            safeMessage(exception), false, request);
    }

    @ExceptionHandler(ApprovalArtifactTransferExceptions.ImportConflict.class)
    ResponseEntity<ApiError> transferImportConflict(
        ApprovalArtifactTransferExceptions.ImportConflict exception,
        HttpServletRequest request
    ) {
        return response(409, "APPROVAL_TRANSFER_IMPORT_CONFLICT", safeMessage(exception), false,
            request);
    }

    @ExceptionHandler(ApprovalArtifactTransferExceptions.ValidationFailed.class)
    ResponseEntity<ApiError> transferValidationFailed(
        ApprovalArtifactTransferExceptions.ValidationFailed exception,
        HttpServletRequest request
    ) {
        return response(422, "APPROVAL_TRANSFER_VALIDATION_FAILED", safeMessage(exception), false,
            request);
    }

    @ExceptionHandler(ProcessTemplateException.PackageTooLarge.class)
    ResponseEntity<ApiError> tooLarge(Exception exception, HttpServletRequest request) {
        return response(413, "APPROVAL_TEMPLATE_IMPORT_TOO_LARGE", safeMessage(exception), false,
            request);
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        MissingRequestHeaderException.class
    })
    ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(400, "APPROVAL_TEMPLATE_IMPORT_INVALID_REQUEST", safeMessage(exception),
            false, request);
    }

    @ExceptionHandler(ProcessTemplateException.CrossTenantBinding.class)
    ResponseEntity<ApiError> notFound(Exception exception, HttpServletRequest request) {
        return response(404, "APPROVAL_TEMPLATE_IMPORT_NOT_FOUND",
            "Process template import resource was not found", false, request);
    }

    @ExceptionHandler({
        ProcessTemplateException.StalePlan.class,
        ProcessTemplateException.HashMismatch.class,
        ProcessTemplateException.DraftCreationRejected.class,
        IdempotencyGuard.IdempotencyConflictException.class
    })
    ResponseEntity<ApiError> conflict(Exception exception, HttpServletRequest request) {
        return response(409, "APPROVAL_TEMPLATE_IMPORT_CONFLICT", safeMessage(exception), false,
            request);
    }

    @ExceptionHandler(ProcessTemplateException.RegistryResolutionFailed.class)
    ResponseEntity<ApiError> unavailable(Exception exception, HttpServletRequest request) {
        return response(503, "APPROVAL_TEMPLATE_REGISTRY_UNAVAILABLE",
            "Target tenant import registry is unavailable", true, request);
    }

    @ExceptionHandler(ProcessTemplateException.class)
    ResponseEntity<ApiError> rejected(Exception exception, HttpServletRequest request) {
        return response(422, "APPROVAL_TEMPLATE_IMPORT_REJECTED", safeMessage(exception), false,
            request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return response(500, "APPROVAL_TEMPLATE_IMPORT_FAILED",
            "Process template import failed", false, request);
    }

    private static ResponseEntity<ApiError> response(
        int status,
        String code,
        String message,
        boolean retryable,
        HttpServletRequest request
    ) {
        String requestId = correlation(request, "X-Request-Id");
        String traceId = correlation(request, "X-Trace-Id");
        return ResponseEntity.status(status)
            .header("X-Request-Id", requestId)
            .header("X-Trace-Id", traceId)
            .body(new ApiError(
                code,
                message,
                retryable,
                requestId,
                traceId,
                Instant.now(),
                Map.of()
            ));
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "request is invalid" : message;
    }

    private static String correlation(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    public record ApiError(
        String errorCode,
        String message,
        boolean retryable,
        String requestId,
        String traceId,
        Instant timestamp,
        Map<String, String> details
    ) {
    }
}
