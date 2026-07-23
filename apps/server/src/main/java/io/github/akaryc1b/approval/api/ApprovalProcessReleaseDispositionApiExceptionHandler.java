package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalProcessReleaseDispositionService;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = ApprovalProcessReleaseDispositionController.class)
public class ApprovalProcessReleaseDispositionApiExceptionHandler {

    @ExceptionHandler({
        IllegalArgumentException.class,
        MissingRequestHeaderException.class
    })
    ResponseEntity<ApiError> invalidRequest(
        Exception exception,
        HttpServletRequest request
    ) {
        return response(
            400,
            "APPROVAL_RELEASE_DISPOSITION_INVALID_REQUEST",
            safeMessage(exception),
            request
        );
    }

    @ExceptionHandler(
        ApprovalProcessReleaseDispositionService.ProcessReleaseNotFoundException.class
    )
    ResponseEntity<ApiError> notFound(
        Exception exception,
        HttpServletRequest request
    ) {
        return response(
            404,
            "APPROVAL_PROCESS_RELEASE_NOT_FOUND",
            safeMessage(exception),
            request
        );
    }

    @ExceptionHandler({
        ApprovalProcessReleaseDispositionService.DispositionEvidenceConflictException.class,
        IdempotencyGuard.IdempotencyConflictException.class
    })
    ResponseEntity<ApiError> conflict(
        Exception exception,
        HttpServletRequest request
    ) {
        return response(
            409,
            "APPROVAL_RELEASE_DISPOSITION_CONFLICT",
            safeMessage(exception),
            request
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(
        Exception exception,
        HttpServletRequest request
    ) {
        return response(
            500,
            "APPROVAL_RELEASE_DISPOSITION_FAILED",
            "Release lifecycle disposition command failed",
            request
        );
    }

    private static ResponseEntity<ApiError> response(
        int status,
        String code,
        String message,
        HttpServletRequest request
    ) {
        String requestId = requestId(request);
        return ResponseEntity.status(status)
            .header("X-Request-Id", requestId)
            .body(new ApiError(code, message, requestId, Instant.now()));
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
        String requestId,
        Instant occurredAt
    ) {
    }
}
