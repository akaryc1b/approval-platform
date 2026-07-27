package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationLease;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationObservation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationObservation.ReconciliationDisposition;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Two short platform transactions around one independent reconciliation readback. */
public interface ApprovalMigrationReconciliationStore {

    PreparedReconciliation prepare(PrepareRequest request);

    StoredReconciliation finalizeObservation(FinalizeRequest request);

    record PrepareRequest(
        String tenantId,
        UUID attemptId,
        String workerId,
        long expectedAttemptRevision,
        Instant happenedAt,
        Instant leaseUntil,
        String requestId,
        String traceId
    ) {
        public PrepareRequest {
            tenantId = requireText(tenantId, "tenantId", 128);
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            workerId = requireText(workerId, "workerId", 200);
            requirePositive(expectedAttemptRevision, "expectedAttemptRevision");
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
            if (!leaseUntil.isAfter(happenedAt)) {
                throw new IllegalArgumentException("reconciliation leaseUntil must be in the future");
            }
            requestId = requireText(requestId, "requestId", 256);
            traceId = optionalText(traceId, "traceId", 256);
        }
    }

    record PreparedReconciliation(
        ApprovalMigrationAttempt attempt,
        ApprovalMigrationReconciliation reconciliation,
        ApprovalMigrationReconciliationLease lease,
        String requestHash,
        ProcessInstanceVerificationPort.VerificationCommand engineCommand,
        Instant preparedAt,
        String requestId,
        String traceId,
        StoredReconciliation replay
    ) {
        public PreparedReconciliation {
            attempt = Objects.requireNonNull(attempt, "attempt must not be null");
            reconciliation = Objects.requireNonNull(
                reconciliation,
                "reconciliation must not be null"
            );
            lease = Objects.requireNonNull(lease, "lease must not be null");
            requestHash = requireHash(requestHash, "requestHash");
            engineCommand = Objects.requireNonNull(engineCommand, "engineCommand must not be null");
            preparedAt = Objects.requireNonNull(preparedAt, "preparedAt must not be null");
            requestId = requireText(requestId, "requestId", 256);
            traceId = optionalText(traceId, "traceId", 256);
            if (!attempt.tenantId().equals(reconciliation.tenantId())
                || !attempt.attemptId().equals(reconciliation.attemptId())
                || !attempt.tenantId().equals(lease.tenantId())
                || !attempt.attemptId().equals(lease.attemptId())
                || !attempt.tenantId().equals(engineCommand.tenantId())
                || !attempt.engineInstanceId().equals(engineCommand.engineInstanceId())) {
                throw new IllegalArgumentException("prepared reconciliation lineage mismatch");
            }
        }

        public boolean replayed() {
            return replay != null;
        }
    }

    record FinalizeRequest(
        PreparedReconciliation prepared,
        ApprovalMigrationEngineSnapshot snapshot,
        ApprovalMigrationExactVerification.ExactClassification classification,
        Instant happenedAt
    ) {
        public FinalizeRequest {
            prepared = Objects.requireNonNull(prepared, "prepared must not be null");
            snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
            classification = Objects.requireNonNull(classification, "classification must not be null");
            happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
            if (happenedAt.isBefore(prepared.preparedAt())) {
                throw new IllegalArgumentException("reconciliation finalization cannot precede preparation");
            }
            var derived = ApprovalMigrationExactVerification.classify(
                snapshot,
                prepared.attempt().sourceEngineDefinitionId(),
                prepared.attempt().targetEngineDefinitionId()
            );
            if (classification != derived) {
                throw new IllegalArgumentException("reconciliation classification is not server-derived");
            }
        }
    }

    record StoredReconciliation(
        ApprovalMigrationReconciliationObservation observation,
        ApprovalMigrationAttempt attempt,
        ApprovalMigrationReconciliation reconciliation,
        ApprovalMigrationReconciliationLease lease,
        ReconciliationDisposition disposition,
        boolean replayed
    ) {
        public StoredReconciliation {
            observation = Objects.requireNonNull(observation, "observation must not be null");
            attempt = Objects.requireNonNull(attempt, "attempt must not be null");
            reconciliation = Objects.requireNonNull(
                reconciliation,
                "reconciliation must not be null"
            );
            lease = Objects.requireNonNull(lease, "lease must not be null");
            disposition = Objects.requireNonNull(disposition, "disposition must not be null");
            if (disposition != observation.disposition()
                || !attempt.tenantId().equals(observation.tenantId())
                || !attempt.attemptId().equals(observation.attemptId())
                || !reconciliation.tenantId().equals(observation.tenantId())
                || !reconciliation.attemptId().equals(observation.attemptId())
                || reconciliation.sequence() < 2
                || !lease.tenantId().equals(observation.tenantId())
                || !lease.attemptId().equals(observation.attemptId())
                || !lease.leaseId().equals(observation.leaseId())) {
                throw new IllegalArgumentException("stored reconciliation evidence is inconsistent");
            }
        }
    }

    final class ReconciliationConflictException extends RuntimeException {
        public ReconciliationConflictException(String message) {
            super(message);
        }

        public ReconciliationConflictException(String message, Throwable cause) {
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
