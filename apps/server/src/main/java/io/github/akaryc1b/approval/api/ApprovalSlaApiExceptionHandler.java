package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalSlaService.ApprovalSlaException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaNotFoundException;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Stable API errors for work-calendar and SLA operations. */
@RestControllerAdvice(assignableTypes = {
    ApprovalCalendarManagementController.class,
    ApprovalSlaPolicyManagementController.class,
    ApprovalSlaInstanceManagementController.class,
    ApprovalParticipantSlaController.class
})
public class ApprovalSlaApiExceptionHandler {

    private final Clock clock;

    public ApprovalSlaApiExceptionHandler(Clock approvalClock) {
        this.clock = approvalClock;
    }

    @ExceptionHandler(SlaNotFoundException.class)
    ResponseEntity<ApiError> notFound(
        SlaNotFoundException exception,
        HttpServletRequest request
    ) {
        return response(404, exception.code(), exception.getMessage(), false, request);
    }

    @ExceptionHandler(SlaConflictException.class)
    ResponseEntity<ApiError> conflict(
        SlaConflictException exception,
        HttpServletRequest request
    ) {
        return response(409, exception.code(), exception.getMessage(), false, request);
    }

    @ExceptionHandler(ApprovalSlaException.class)
    ResponseEntity<ApiError> serviceFailure(
        ApprovalSlaException exception,
        HttpServletRequest request
    ) {
        return response(
            status(exception.code()),
            exception.code(),
            exception.getMessage(),
            exception.retryable(),
            request
        );
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
        return response(
            400,
            validationCode(exception),
            safeMessage(exception),
            false,
            request
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        return response(
            500,
            "APPROVAL_SLA_CALCULATION_FAILED",
            "calendar or SLA operation failed",
            true,
            request
        );
    }

    private ResponseEntity<ApiError> response(
        int status,
        String code,
        String message,
        boolean retryable,
        HttpServletRequest request
    ) {
        String requestId = requestId(request);
        return ResponseEntity.status(status)
            .header("X-Request-Id", requestId)
            .body(new ApiError(
                code,
                message,
                retryable,
                requestId,
                clock.instant()
            ));
    }

    private static int status(String code) {
        if (code.endsWith("_NOT_FOUND")) {
            return 404;
        }
        if (code.contains("CONFLICT") || code.contains("ALREADY_PUBLISHED")) {
            return 409;
        }
        if (code.equals("APPROVAL_SLA_CALCULATION_FAILED")
            || code.equals("APPROVAL_WORKING_TIME_UNAVAILABLE")) {
            return 422;
        }
        return 400;
    }

    private static String validationCode(Exception exception) {
        String message = safeMessage(exception).toLowerCase(Locale.ROOT);
        if (message.contains("time zone") || message.contains("timezone")
            || message.contains("zoneid")) {
            return "APPROVAL_TIME_ZONE_INVALID";
        }
        if (message.contains("override")) {
            return "APPROVAL_CALENDAR_OVERRIDE_CONFLICT";
        }
        if (message.contains("interval") || message.contains("overlap")) {
            return "APPROVAL_CALENDAR_INTERVAL_INVALID";
        }
        if (message.contains("policy")) {
            return "APPROVAL_SLA_POLICY_VERSION_CONFLICT";
        }
        return "INVALID_REQUEST";
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
