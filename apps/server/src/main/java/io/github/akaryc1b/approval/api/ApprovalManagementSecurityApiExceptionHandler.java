package io.github.akaryc1b.approval.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

/** Stable management security failures across every management controller. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ApprovalManagementSecurityApiExceptionHandler {

    @ExceptionHandler(ApprovalManagementPermissionDeniedException.class)
    ResponseEntity<ApiError> permissionDenied(
        ApprovalManagementPermissionDeniedException exception,
        HttpServletRequest request
    ) {
        int status = exception.reason()
            == ApprovalManagementPermissionDeniedException.Reason.UNAUTHENTICATED
            ? 401
            : 403;
        String code = status == 401
            ? "APPROVAL_AUTHENTICATION_REQUIRED"
            : "APPROVAL_MANAGEMENT_PERMISSION_DENIED";
        return response(status, code, exception.getMessage(), false, request);
    }

    @ExceptionHandler(ApprovalManagementGovernanceException.class)
    ResponseEntity<ApiError> governance(
        ApprovalManagementGovernanceException exception,
        HttpServletRequest request
    ) {
        return response(
            exception.status(),
            exception.code(),
            exception.getMessage(),
            exception.retryable(),
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

    private static String requestId(HttpServletRequest request) {
        String value = request.getHeader("X-Request-Id");
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    record ApiError(
        String code,
        String message,
        boolean retryable,
        String requestId,
        Instant occurredAt
    ) {
    }
}
