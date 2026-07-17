package io.github.akaryc1b.approval.integration.inbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Persistence boundary for replay-safe incoming messages.
 */
public interface InboxRepository {

    BeginResult begin(
        InboxMessageKey key,
        String payloadHash,
        Instant now,
        String workerId,
        Duration leaseDuration
    );

    boolean complete(InboxMessageKey key, String workerId, Instant completedAt);

    boolean fail(InboxMessageKey key, String workerId, String errorMessage, Instant failedAt);

    enum BeginStatus {
        ACQUIRED,
        ALREADY_COMPLETED,
        IN_PROGRESS,
        PAYLOAD_MISMATCH
    }

    record BeginResult(BeginStatus status, int attempts) {
        public BeginResult {
            status = Objects.requireNonNull(status, "status must not be null");
            if (attempts < 0) {
                throw new IllegalArgumentException("attempts must not be negative");
            }
        }
    }
}
