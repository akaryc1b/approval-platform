package io.github.akaryc1b.approval.application.port;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Records idempotent optimistic SLA action state. */
public interface ApprovalSlaActionStateRecorder {

    RecordResult recordOverdue(
        String tenantId,
        UUID slaInstanceId,
        long actionSequence,
        String requestId,
        String traceId,
        Instant recordedAt
    );

    enum RecordResult {
        RECORDED,
        ALREADY_RECORDED
    }

    final class ActionStateException extends RuntimeException {
        private final String code;
        private final boolean retryable;

        public ActionStateException(String code, boolean retryable, String message) {
            super(Objects.requireNonNull(message, "message must not be null"));
            this.code = requireText(code, "code", 128);
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }
}
