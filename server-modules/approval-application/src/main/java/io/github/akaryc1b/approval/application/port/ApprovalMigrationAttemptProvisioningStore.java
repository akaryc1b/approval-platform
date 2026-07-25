package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Creates the initial durable attempt queue from one exact consumed migration plan. */
public interface ApprovalMigrationAttemptProvisioningStore {

    ProvisioningResult ensureInitialAttempts(ProvisioningRequest request);

    record ProvisioningRequest(
        String tenantId,
        UUID intentId,
        String workerId,
        Instant happenedAt,
        String requestId,
        String traceId,
        String requestHash
    ) {
        public ProvisioningRequest {
            tenantId = requireText(tenantId, "tenantId", 128);
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            workerId = requireText(workerId, "workerId", 200);
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            requestId = requireText(requestId, "requestId", 256);
            traceId = optionalText(traceId, "traceId", 256);
            if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "requestHash must be a lowercase SHA-256 value"
                );
            }
        }
    }

    record ProvisioningResult(
        List<ApprovalMigrationAttempt> initialAttempts,
        int createdCount
    ) {
        public ProvisioningResult {
            initialAttempts = initialAttempts == null ? List.of() : List.copyOf(initialAttempts);
            if (createdCount < 0 || createdCount > initialAttempts.size()) {
                throw new IllegalArgumentException("createdCount is outside attempt result bounds");
            }
        }

        public boolean replayedExistingProvisioning() {
            return createdCount == 0;
        }
    }

    final class MigrationAttemptProvisioningConflictException extends RuntimeException {
        public MigrationAttemptProvisioningConflictException(String message) {
            super(message);
        }

        public MigrationAttemptProvisioningConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String optionalText(String value, String name, int maximum) {
        return value == null || value.isBlank() ? null : requireText(value, name, maximum);
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                name + " is blank or exceeds maximum length " + maximum
            );
        }
        return normalized;
    }
}
