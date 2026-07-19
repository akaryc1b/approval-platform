package io.github.akaryc1b.approval.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice
public class ApprovalManagementPermissionExceptionHandler {

    @ExceptionHandler(ApprovalManagementPermissionDeniedException.class)
    ResponseEntity<ApiError> denied(
        ApprovalManagementPermissionDeniedException exception,
        HttpServletRequest request
    ) {
        String requestId = requestId(request);
        return ResponseEntity.status(403)
            .header("X-Request-Id", requestId)
            .body(new ApiError(
                "APPROVAL_MANAGEMENT_PERMISSION_DENIED",
                exception.getMessage(),
                false,
                requestId,
                Instant.now()
            ));
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
