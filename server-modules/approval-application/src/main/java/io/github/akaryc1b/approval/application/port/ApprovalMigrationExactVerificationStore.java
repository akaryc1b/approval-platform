package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification.ExactClassification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Short platform transactions around one read-only public-engine verification. */
public interface ApprovalMigrationExactVerificationStore {

    PreparedVerification prepare(PrepareRequest request);

    StoredVerification finalizeVerification(FinalizeRequest request);

    record PrepareRequest(
        String tenantId,
        UUID attemptId,
        String workerId,
        long expectedAttemptRevision,
        long expectedFenceRevision,
        Instant happenedAt,
        String requestId,
        String traceId
    ) {
        public PrepareRequest {
            tenantId = requireText(tenantId, "tenantId", 128);
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            workerId = requireText(workerId, "workerId", 200);
            requirePositive(expectedAttemptRevision, "expectedAttemptRevision");
            requirePositive(expectedFenceRevision, "expectedFenceRevision");
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            requestId = requireText(requestId, "requestId", 256);
            traceId = optionalText(traceId, "traceId", 256);
        }
    }

    record PreparedVerification(
        ApprovalMigrationAttempt attempt,
        UUID engineRequestId,
        UUID engineOutcomeId,
        long fenceRevision,
        String workerId,
        String requestHash,
        ProcessInstanceVerificationPort.VerificationCommand engineCommand,
        Instant preparedAt,
        String requestId,
        String traceId,
        StoredVerification replay
    ) {
        public PreparedVerification {
            attempt = Objects.requireNonNull(attempt, "attempt must not be null");
            engineRequestId = Objects.requireNonNull(engineRequestId, "engineRequestId must not be null");
            engineOutcomeId = Objects.requireNonNull(engineOutcomeId, "engineOutcomeId must not be null");
            requirePositive(fenceRevision, "fenceRevision");
            workerId = requireText(workerId, "workerId", 200);
            requestHash = requireHash(requestHash, "requestHash");
            engineCommand = Objects.requireNonNull(engineCommand, "engineCommand must not be null");
            preparedAt = Objects.requireNonNull(preparedAt, "preparedAt must not be null");
            requestId = requireText(requestId, "requestId", 256);
            traceId = optionalText(traceId, "traceId", 256);
            if (!attempt.tenantId().equals(engineCommand.tenantId())
                || !attempt.engineInstanceId().equals(engineCommand.engineInstanceId())) {
                throw new IllegalArgumentException("prepared verification engine identity mismatch");
            }
        }

        public boolean replayed() {
            return replay != null;
        }
    }

    record FinalizeRequest(
        PreparedVerification prepared,
        ApprovalMigrationEngineSnapshot snapshot,
        ExactClassification classification,
        Instant happenedAt
    ) {
        public FinalizeRequest {
            prepared = Objects.requireNonNull(prepared, "prepared must not be null");
            snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
            classification = Objects.requireNonNull(classification, "classification must not be null");
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            if (happenedAt.isBefore(prepared.preparedAt())) {
                throw new IllegalArgumentException("verification finalization cannot precede preparation");
            }
            ExactClassification derived = ApprovalMigrationExactVerification.classify(
                snapshot,
                prepared.attempt().sourceEngineDefinitionId(),
                prepared.attempt().targetEngineDefinitionId()
            );
            if (classification != derived) {
                throw new IllegalArgumentException("verification classification is not server-derived");
            }
        }
    }

    record StoredVerification(
        ApprovalMigrationExactVerification evidence,
        ApprovalMigrationAttempt attempt,
        boolean replayed
    ) {
        public StoredVerification {
            evidence = Objects.requireNonNull(evidence, "evidence must not be null");
            attempt = Objects.requireNonNull(attempt, "attempt must not be null");
            if (!evidence.attemptId().equals(attempt.attemptId())
                || !evidence.tenantId().equals(attempt.tenantId())) {
                throw new IllegalArgumentException("stored verification attempt mismatch");
            }
        }
    }

    final class VerificationConflictException extends RuntimeException {
        public VerificationConflictException(String message) {
            super(message);
        }

        public VerificationConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name, 64);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    private static String optionalText(String value, String name, int maximum) {
        return value == null || value.isBlank() ? null : requireText(value, name, maximum);
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
