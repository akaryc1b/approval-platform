package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalMessageService.MessageStateConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

/** Maps only recognized message command conflicts, without widening resource visibility. */
@RestControllerAdvice(assignableTypes = ApprovalMessageController.class)
public class ApprovalMessageApiExceptionHandler {

    @ExceptionHandler(MessageStateConflictException.class)
    ResponseEntity<ApprovalApiExceptionHandler.ApiError> stateConflict(
        MessageStateConflictException exception,
        HttpServletRequest request
    ) {
        return conflict("APPROVAL_MESSAGE_STATE_CONFLICT", exception.getMessage(), request);
    }

    private static ResponseEntity<ApprovalApiExceptionHandler.ApiError> conflict(
        String code,
        String message,
        HttpServletRequest request
    ) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return ResponseEntity.status(409)
            .header("X-Request-Id", requestId)
            .body(new ApprovalApiExceptionHandler.ApiError(
                code, message, false, requestId, Instant.now()
            ));
    }
}
