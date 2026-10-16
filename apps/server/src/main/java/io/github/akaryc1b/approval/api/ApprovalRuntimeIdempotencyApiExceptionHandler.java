package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

/** Covers only legacy runtime controllers with no existing idempotency error contract. */
@RestControllerAdvice(assignableTypes = {
    ApprovalAttachmentController.class,
    ApprovalMessageController.class,
    ApprovalFormController.class,
    ApprovalUiSchemaController.class
})
public class ApprovalRuntimeIdempotencyApiExceptionHandler {

    @ExceptionHandler(IdempotencyGuard.IdempotencyConflictException.class)
    ResponseEntity<ApprovalApiExceptionHandler.ApiError> conflict(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return ResponseEntity.status(409)
            .header("X-Request-Id", requestId)
            .body(new ApprovalApiExceptionHandler.ApiError(
                "IDEMPOTENCY_CONFLICT",
                "idempotency key conflicts with the current command",
                false,
                requestId,
                Instant.now()
            ));
    }
}
