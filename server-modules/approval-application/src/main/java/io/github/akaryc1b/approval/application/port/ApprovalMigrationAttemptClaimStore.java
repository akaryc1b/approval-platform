package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationClaimBatch;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Tenant-bounded deterministic claim and lease-renewal persistence for migration attempts. */
public interface ApprovalMigrationAttemptClaimStore {

    ClaimResult claim(ClaimRequest request);

    RenewalResult renew(RenewalRequest request);

    Optional<ApprovalMigrationCommandFence> findFence(String tenantId, UUID attemptId);

    record ClaimRequest(
        String tenantId,
        UUID intentId,
        String workerId,
        int limit,
        Instant claimedAt,
        Instant leaseUntil,
        String requestId,
        String traceId,
        String requestHash
    ) {
        public ClaimRequest {
            tenantId = requireText(tenantId, "tenantId", 128);
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            workerId = requireText(workerId, "workerId", 200);
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            claimedAt = Objects.requireNonNull(claimedAt, "claimedAt must not be null");
            leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
            if (!leaseUntil.isAfter(claimedAt)) {
                throw new IllegalArgumentException("leaseUntil must follow claimedAt");
            }
            requestId = requireText(requestId, "requestId", 128);
            traceId = optionalText(traceId, 256);
            requestHash = requireHash(requestHash, "requestHash");
        }
    }

    record ClaimResult(
        ApprovalMigrationClaimBatch batch,
        List<ApprovalMigrationAttempt> attempts,
        List<ApprovalMigrationCommandFence> fences,
        boolean replayedExistingClaim
    ) {
        public ClaimResult {
            batch = Objects.requireNonNull(batch, "batch must not be null");
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
            fences = fences == null ? List.of() : List.copyOf(fences);
            if (attempts.size() != fences.size()
                || attempts.size() != batch.claimedCount()) {
                throw new IllegalArgumentException("claim result evidence count is inconsistent");
            }
        }
    }

    record RenewalRequest(
        String tenantId,
        UUID attemptId,
        String workerId,
        Instant happenedAt,
        Instant leaseUntil,
        String requestId,
        String traceId
    ) {
        public RenewalRequest {
            tenantId = requireText(tenantId, "tenantId", 128);
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            workerId = requireText(workerId, "workerId", 200);
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
            if (!leaseUntil.isAfter(happenedAt)) {
                throw new IllegalArgumentException("leaseUntil must follow happenedAt");
            }
            requestId = requireText(requestId, "requestId", 128);
            traceId = optionalText(traceId, 256);
        }
    }

    record RenewalResult(
        ApprovalMigrationAttempt attempt,
        ApprovalMigrationCommandFence fence
    ) {
        public RenewalResult {
            attempt = Objects.requireNonNull(attempt, "attempt must not be null");
            fence = Objects.requireNonNull(fence, "fence must not be null");
        }
    }

    final class MigrationAttemptClaimConflictException extends RuntimeException {
        public MigrationAttemptClaimConflictException(String message) {
            super(message);
        }

        public MigrationAttemptClaimConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String optionalText(String value, int maximum) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, "traceId", maximum);
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(name + " is blank or exceeds maximum length " + maximum);
        }
        return normalized;
    }
}
