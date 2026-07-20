package io.github.akaryc1b.approval.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Unified operational visibility without merging the owning failure state machines. */
public interface ApprovalOperationalFailureStore {

    OperationalFailurePage findFailures(OperationalFailureCriteria criteria);

    Optional<OperationalFailure> findFailure(
        String tenantId,
        FailureCategory category,
        UUID sourceId
    );

    List<OperationalFailureAttempt> findAttempts(
        String tenantId,
        FailureCategory category,
        UUID sourceId
    );

    boolean replayOutboxDead(
        String tenantId,
        UUID sourceId,
        String replayedBy,
        String requestId,
        Instant availableAt
    );

    enum FailureCategory {
        NOTIFICATION_DELIVERY,
        BUSINESS_OUTBOX,
        CONSISTENCY_CHECK
    }

    enum FailureKind {
        CONNECTOR,
        EMAIL,
        BUSINESS_CALLBACK,
        INTERNAL
    }

    record OperationalFailureCriteria(
        String tenantId,
        FailureCategory category,
        FailureKind failureKind,
        String connectorKey,
        int limit,
        int offset
    ) {
        public OperationalFailureCriteria {
            tenantId = requireText(tenantId, "tenantId");
            connectorKey = normalizeOptional(connectorKey);
            validatePage(limit, offset);
        }
    }

    record OperationalFailure(
        FailureCategory category,
        FailureKind failureKind,
        UUID sourceId,
        String responsibility,
        String status,
        String connectorKey,
        String recipientId,
        String aggregateType,
        String aggregateId,
        int attemptCount,
        Integer maxAttempts,
        Instant nextAttemptAt,
        String lastErrorCode,
        String lastErrorMessage,
        Instant createdAt,
        Instant updatedAt,
        boolean replayable
    ) {
        public OperationalFailure {
            category = Objects.requireNonNull(category, "category must not be null");
            failureKind = Objects.requireNonNull(failureKind, "failureKind must not be null");
            sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
            responsibility = requireText(responsibility, "responsibility");
            status = requireText(status, "status");
            connectorKey = normalizeOptional(connectorKey);
            recipientId = normalizeOptional(recipientId);
            aggregateType = normalizeOptional(aggregateType);
            aggregateId = normalizeOptional(aggregateId);
            if (attemptCount < 0) {
                throw new IllegalArgumentException("attemptCount must not be negative");
            }
            if (maxAttempts != null && maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be positive");
            }
            lastErrorCode = normalizeOptional(lastErrorCode);
            lastErrorMessage = normalizeOptional(lastErrorMessage);
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        }
    }

    record OperationalFailureAttempt(
        UUID attemptId,
        int attemptNumber,
        Instant startedAt,
        Instant completedAt,
        boolean successful,
        boolean retryable,
        String providerReference,
        Integer responseCode,
        String errorCode,
        String errorMessage,
        Instant nextAttemptAt,
        String workerId
    ) {
        public OperationalFailureAttempt {
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            if (attemptNumber < 1) {
                throw new IllegalArgumentException("attemptNumber must be positive");
            }
            startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
            completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
            providerReference = normalizeOptional(providerReference);
            errorCode = normalizeOptional(errorCode);
            errorMessage = normalizeOptional(errorMessage);
            workerId = normalizeOptional(workerId);
        }
    }

    record OperationalFailurePage(
        List<OperationalFailure> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public OperationalFailurePage(
            List<OperationalFailure> items,
            long total,
            int limit,
            int offset
        ) {
            this(items, total, limit, offset, offset + (items == null ? 0 : items.size()) < total);
        }

        public OperationalFailurePage {
            items = items == null ? List.of() : List.copyOf(items);
            if (total < 0) {
                throw new IllegalArgumentException("total must not be negative");
            }
            validatePage(limit, offset);
        }
    }

    final class OperationalFailureNotFoundException extends RuntimeException {
        public OperationalFailureNotFoundException(String message) {
            super(message);
        }
    }

    final class OperationalFailureConflictException extends RuntimeException {
        public OperationalFailureConflictException(String message) {
            super(message);
        }
    }

    private static void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
