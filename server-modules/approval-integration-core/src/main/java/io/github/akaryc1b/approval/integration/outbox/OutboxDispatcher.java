package io.github.akaryc1b.approval.integration.outbox;

import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.CallbackReceipt;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.DeliveryStatus;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository.ClaimedMessage;
import io.github.akaryc1b.approval.integration.retry.RetryPolicy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Claims due messages and delivers them outside the approval transaction.
 */
public final class OutboxDispatcher {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final OutboxRepository repository;
    private final BusinessCallbackResolver callbackResolver;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final Duration leaseDuration;

    public OutboxDispatcher(
        OutboxRepository repository,
        BusinessCallbackResolver callbackResolver,
        RetryPolicy retryPolicy,
        Clock clock,
        Duration leaseDuration
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.callbackResolver = Objects.requireNonNull(callbackResolver, "callbackResolver must not be null");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
    }

    public DispatchReport dispatchBatch(int limit, String workerId) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        workerId = requireText(workerId, "workerId");
        Instant now = clock.instant();
        var claimedMessages = repository.claimDue(now, limit, workerId, leaseDuration);
        int delivered = 0;
        int rescheduled = 0;
        int dead = 0;
        int leaseLost = 0;

        for (ClaimedMessage claimed : claimedMessages) {
            DispatchOutcome outcome = dispatchOne(claimed);
            switch (outcome) {
                case DELIVERED -> delivered++;
                case RESCHEDULED -> rescheduled++;
                case DEAD -> dead++;
                case LEASE_LOST -> leaseLost++;
            }
        }
        return new DispatchReport(claimedMessages.size(), delivered, rescheduled, dead, leaseLost);
    }

    private DispatchOutcome dispatchOne(ClaimedMessage claimed) {
        try {
            var connector = callbackResolver.resolve(claimed.message().context().connectorKey());
            CallbackReceipt receipt = connector.deliver(
                claimed.message().context(),
                claimed.message().event()
            );
            if (receipt.status() == DeliveryStatus.DELIVERED) {
                boolean updated = repository.markDelivered(
                    claimed.message().id(),
                    claimed.workerId(),
                    receipt.providerRequestId(),
                    receipt.responseCode(),
                    clock.instant()
                );
                return updated ? DispatchOutcome.DELIVERED : DispatchOutcome.LEASE_LOST;
            }
            if (receipt.status() == DeliveryStatus.PERMANENT_FAILURE) {
                return markDead(claimed, describe(receipt.errorMessage(), receipt.responseCode()));
            }
            return retryOrDead(claimed, describe(receipt.errorMessage(), receipt.responseCode()));
        } catch (RuntimeException exception) {
            return retryOrDead(claimed, describe(exception));
        }
    }

    private DispatchOutcome retryOrDead(ClaimedMessage claimed, String errorMessage) {
        int attempts = claimed.attempts() + 1;
        if (attempts >= retryPolicy.maxAttempts()) {
            return markDead(claimed, attempts, errorMessage);
        }
        Instant updatedAt = clock.instant();
        Instant availableAt = updatedAt.plus(retryPolicy.nextDelay(attempts));
        boolean updated = repository.reschedule(
            claimed.message().id(),
            claimed.workerId(),
            attempts,
            availableAt,
            errorMessage,
            updatedAt
        );
        return updated ? DispatchOutcome.RESCHEDULED : DispatchOutcome.LEASE_LOST;
    }

    private DispatchOutcome markDead(ClaimedMessage claimed, String errorMessage) {
        return markDead(claimed, claimed.attempts() + 1, errorMessage);
    }

    private DispatchOutcome markDead(ClaimedMessage claimed, int attempts, String errorMessage) {
        boolean updated = repository.markDead(
            claimed.message().id(),
            claimed.workerId(),
            attempts,
            errorMessage,
            clock.instant()
        );
        return updated ? DispatchOutcome.DEAD : DispatchOutcome.LEASE_LOST;
    }

    private static String describe(RuntimeException exception) {
        String message = exception.getMessage();
        String description = exception.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ": " + message);
        return truncate(description);
    }

    private static String describe(String message, int responseCode) {
        String description = "HTTP " + responseCode;
        if (message != null && !message.isBlank()) {
            description += ": " + message;
        }
        return truncate(description);
    }

    private static String truncate(String value) {
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private enum DispatchOutcome {
        DELIVERED,
        RESCHEDULED,
        DEAD,
        LEASE_LOST
    }

    public record DispatchReport(
        int claimed,
        int delivered,
        int rescheduled,
        int dead,
        int leaseLost
    ) {
    }
}
