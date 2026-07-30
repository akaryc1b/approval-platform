package io.github.akaryc1b.approval.domain.migration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Current independent lease for one UNKNOWN-derived reconciliation readback. */
public record ApprovalMigrationReconciliationLease(
    UUID leaseId,
    String tenantId,
    UUID intentId,
    UUID attemptId,
    ReconciliationLeaseStatus status,
    long revision,
    String workerId,
    Instant leaseUntil,
    Instant acquiredAt,
    Instant updatedAt,
    Instant releasedAt,
    String requestHash,
    String evidenceHash,
    String requestId,
    String traceId
) {
    public ApprovalMigrationReconciliationLease {
        leaseId = Objects.requireNonNull(leaseId, "leaseId must not be null");
        tenantId = ApprovalMigrationRules.requireText(tenantId, "tenantId", 128);
        intentId = Objects.requireNonNull(intentId, "intentId must not be null");
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        ApprovalMigrationRules.requirePositive(revision, "revision");
        workerId = ApprovalMigrationRules.requireText(workerId, "workerId", 200);
        leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        acquiredAt = Objects.requireNonNull(acquiredAt, "acquiredAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        requestHash = ApprovalMigrationRules.requireHash(requestHash, "requestHash");
        evidenceHash = ApprovalMigrationRules.requireHash(evidenceHash, "evidenceHash");
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
        if (updatedAt.isBefore(acquiredAt)) {
            throw new IllegalArgumentException("updatedAt must not precede acquiredAt");
        }
        if (status == ReconciliationLeaseStatus.ACTIVE) {
            if (releasedAt != null || !leaseUntil.isAfter(updatedAt)) {
                throw new IllegalArgumentException("active reconciliation lease requires future expiry");
            }
        } else if (releasedAt == null || !releasedAt.equals(updatedAt)
            || releasedAt.isBefore(acquiredAt)) {
            throw new IllegalArgumentException("released reconciliation lease evidence is inconsistent");
        }
    }

    public enum ReconciliationLeaseStatus {
        ACTIVE,
        RELEASED
    }
}
