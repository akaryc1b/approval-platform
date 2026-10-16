package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalParticipantResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

/** Gives absent and unauthorized participant reads the same non-retryable response. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
    PurchasePaymentController.class,
    ApprovalFormRuntimeController.class,
    ApprovalFormSubmissionController.class
})
public class ApprovalParticipantReadApiExceptionHandler {

    @ExceptionHandler(ApprovalParticipantResourceNotFoundException.class)
    ResponseEntity<ApprovalApiExceptionHandler.ApiError> notFound(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return ResponseEntity.status(404)
            .header("X-Request-Id", requestId)
            .body(new ApprovalApiExceptionHandler.ApiError(
                "APPROVAL_RESOURCE_NOT_FOUND",
                "approval resource was not found",
                false,
                requestId,
                Instant.now()
            ));
    }
}
