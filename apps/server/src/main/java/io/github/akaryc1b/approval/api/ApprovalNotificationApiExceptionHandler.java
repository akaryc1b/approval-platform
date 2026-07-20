package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.time.zone.ZoneRulesException;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = ApprovalNotificationController.class)
public class ApprovalNotificationApiExceptionHandler {

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        MissingRequestHeaderException.class,
        ZoneRulesException.class
    })
    ResponseEntity<ApiError> invalid(Exception exception, HttpServletRequest request) {
        return response(400, "APPROVAL_NOTIFICATION_INVALID_REQUEST", safeMessage(exception), false, request);
    }

    @ExceptionHandler(ApprovalNotificationStore.NotificationNotFoundException.class)
    ResponseEntity<ApiError> notFound(Exception exception, HttpServletRequest request) {
        return response(404, "APPROVAL_NOTIFICATION_NOT_FOUND", safeMessage(exception), false, request);
    }

    @ExceptionHandler(ApprovalNotificationStore.NotificationConflictException.class)
    ResponseEntity<ApiError> conflict(Exception exception, HttpServletRequest request) {
        return response(409, "APPROVAL_NOTIFICATION_CONFLICT", safeMessage(exception), false, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return response(
            500,
            "APPROVAL_NOTIFICATION_COMMAND_FAILED",
            "Approval notification command failed",
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
