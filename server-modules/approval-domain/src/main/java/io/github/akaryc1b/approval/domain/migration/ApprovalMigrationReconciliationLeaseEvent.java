package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationLease.ReconciliationLeaseStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only acquisition, takeover, renewal or release evidence for a reconciliation lease. */
public record ApprovalMigrationReconciliationLeaseEvent(
    UUID eventId,
    String tenantId,
    UUID leaseId,
    UUID attemptId,
    long revision,
    ReconciliationLeaseStatus fromStatus,
    ReconciliationLeaseStatus toStatus,
    String workerId,
    Instant leaseUntil,
    Instant happenedAt,
    String requestHash,
    String evidenceHash,
    String requestId,
    String traceId
) {
    public ApprovalMigrationReconciliationLeaseEvent {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        tenantId = ApprovalMigrationRules.requireText(tenantId, "tenantId", 128);
        leaseId = Objects.requireNonNull(leaseId, "leaseId must not be null");
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        ApprovalMigrationRules.requirePositive(revision, "revision");
        toStatus = Objects.requireNonNull(toStatus, "toStatus must not be null");
        workerId = ApprovalMigrationRules.requireText(workerId, "workerId", 200);
        leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
        requestHash = ApprovalMigrationRules.requireHash(requestHash, "requestHash");
        evidenceHash = ApprovalMigrationRules.requireHash(evidenceHash, "evidenceHash");
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
        if (fromStatus == null) {
            if (revision != 1 || toStatus != ReconciliationLeaseStatus.ACTIVE
                || !leaseUntil.isAfter(happenedAt)) {
                throw new IllegalArgumentException("initial reconciliation lease event is invalid");
            }
        } else if (fromStatus != ReconciliationLeaseStatus.ACTIVE
            || (toStatus != ReconciliationLeaseStatus.ACTIVE
                && toStatus != ReconciliationLeaseStatus.RELEASED)) {
            throw new IllegalArgumentException("reconciliation lease transition is not permitted");
        }
    }
}
