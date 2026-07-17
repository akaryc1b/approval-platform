package io.github.akaryc1b.approval.integration.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistence boundary for transactional Outbox delivery.
 */
public interface OutboxRepository {

    AppendResult append(OutboxMessage message);

    List<ClaimedMessage> claimDue(Instant now, int limit, String workerId, Duration leaseDuration);

    boolean markDelivered(
        UUID messageId,
        String workerId,
        String providerRequestId,
        int responseCode,
        Instant deliveredAt
    );

    boolean reschedule(
        UUID messageId,
        String workerId,
        int attempts,
        Instant availableAt,
        String errorMessage,
        Instant updatedAt
    );

    boolean markDead(
        UUID messageId,
        String workerId,
        int attempts,
        String errorMessage,
        Instant deadAt
    );

    enum AppendResult {
        INSERTED,
        DUPLICATE
    }

    record ClaimedMessage(
        OutboxMessage message,
        int attempts,
        String workerId,
        Instant lockedUntil
    ) {
        public ClaimedMessage {
            message = Objects.requireNonNull(message, "message must not be null");
            if (attempts < 0) {
                throw new IllegalArgumentException("attempts must not be negative");
            }
            workerId = requireText(workerId, "workerId");
            lockedUntil = Objects.requireNonNull(lockedUntil, "lockedUntil must not be null");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
