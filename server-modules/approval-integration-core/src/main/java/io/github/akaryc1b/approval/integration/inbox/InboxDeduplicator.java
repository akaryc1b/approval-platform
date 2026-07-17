package io.github.akaryc1b.approval.integration.inbox;

import io.github.akaryc1b.approval.integration.inbox.InboxRepository.BeginStatus;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

public final class InboxDeduplicator {

    private final InboxRepository repository;
    private final Clock clock;
    private final Duration leaseDuration;

    public InboxDeduplicator(InboxRepository repository, Clock clock, Duration leaseDuration) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
    }

    public <T> ExecutionResult<T> execute(
        InboxMessageKey key,
        String payloadHash,
        String workerId,
        Supplier<T> handler
    ) {
        Objects.requireNonNull(key, "key must not be null");
        payloadHash = requireText(payloadHash, "payloadHash");
        workerId = requireText(workerId, "workerId");
        Objects.requireNonNull(handler, "handler must not be null");

        var begin = repository.begin(key, payloadHash, clock.instant(), workerId, leaseDuration);
        if (begin.status() == BeginStatus.ALREADY_COMPLETED) {
            return new ExecutionResult<>(ExecutionStatus.DUPLICATE, null);
        }
        if (begin.status() == BeginStatus.IN_PROGRESS) {
            return new ExecutionResult<>(ExecutionStatus.IN_PROGRESS, null);
        }
        if (begin.status() == BeginStatus.PAYLOAD_MISMATCH) {
            throw new IllegalStateException("messageId was replayed with a different payload");
        }

        try {
            T value = handler.get();
            if (!repository.complete(key, workerId, clock.instant())) {
                throw new InboxLeaseLostException("Inbox lease was lost before completion");
            }
            return new ExecutionResult<>(ExecutionStatus.EXECUTED, value);
        } catch (RuntimeException exception) {
            repository.fail(key, workerId, describe(exception), clock.instant());
            throw exception;
        }
    }

    private static String describe(RuntimeException exception) {
        String message = exception.getMessage();
        String value = exception.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ": " + message);
        return value.length() <= 2000 ? value : value.substring(0, 2000);
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

    public enum ExecutionStatus {
        EXECUTED,
        DUPLICATE,
        IN_PROGRESS
    }

    public record ExecutionResult<T>(ExecutionStatus status, T value) {
        public ExecutionResult {
            status = Objects.requireNonNull(status, "status must not be null");
        }
    }

    public static final class InboxLeaseLostException extends RuntimeException {
        public InboxLeaseLostException(String message) {
            super(message);
        }
    }
}
