package io.github.akaryc1b.approval.domain.migration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable migration ownership at the shared approval-instance command boundary. */
public record ApprovalMigrationCommandFence(
    UUID fenceId,
    String tenantId,
    UUID approvalInstanceId,
    UUID attemptId,
    ApprovalCommandOperation operation,
    FenceStatus status,
    long revision,
    String leaseOwner,
    Instant leaseUntil,
    String idempotencyKey,
    String requestHash,
    Instant acquiredAt,
    Instant updatedAt,
    Instant releasedAt,
    String requestId,
    String traceId
) {
    public ApprovalMigrationCommandFence {
        fenceId = Objects.requireNonNull(fenceId, "fenceId must not be null");
        tenantId = ApprovalMigrationRules.requireText(tenantId, "tenantId", 128);
        approvalInstanceId = Objects.requireNonNull(
            approvalInstanceId,
            "approvalInstanceId must not be null"
        );
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        operation = Objects.requireNonNull(operation, "operation must not be null");
        if (operation != ApprovalCommandOperation.MIGRATION) {
            throw new IllegalArgumentException("durable command fence operation must be MIGRATION");
        }
        status = Objects.requireNonNull(status, "status must not be null");
        ApprovalMigrationRules.requirePositive(revision, "revision");
        leaseOwner = ApprovalMigrationRules.requireText(leaseOwner, "leaseOwner", 200);
        leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        idempotencyKey = ApprovalMigrationRules.requireText(
            idempotencyKey,
            "idempotencyKey",
            200
        );
        requestHash = ApprovalMigrationRules.requireHash(requestHash, "requestHash");
        acquiredAt = Objects.requireNonNull(acquiredAt, "acquiredAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(acquiredAt) || !leaseUntil.isAfter(updatedAt)) {
            throw new IllegalArgumentException("command fence timestamps are inconsistent");
        }
        if (status == FenceStatus.ACTIVE && releasedAt != null) {
            throw new IllegalArgumentException("active command fence cannot have releasedAt");
        }
        if (status == FenceStatus.RELEASED
            && (releasedAt == null || !releasedAt.equals(updatedAt))) {
            throw new IllegalArgumentException("released command fence requires exact releasedAt");
        }
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
    }

    public ApprovalMigrationCommandFence renewed(
        String actor,
        Instant nextLeaseUntil,
        Instant happenedAt
    ) {
        if (status != FenceStatus.ACTIVE) {
            throw new IllegalArgumentException("only an active command fence may be renewed");
        }
        String normalizedActor = ApprovalMigrationRules.requireText(actor, "actor", 200);
        Instant changedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
        Instant changedLease = Objects.requireNonNull(
            nextLeaseUntil,
            "nextLeaseUntil must not be null"
        );
        if (!changedLease.isAfter(changedAt)) {
            throw new IllegalArgumentException("renewed lease must extend beyond transition time");
        }
        if (leaseOwner.equals(normalizedActor)) {
            if (!changedAt.isBefore(leaseUntil) || !changedLease.isAfter(leaseUntil)) {
                throw new IllegalArgumentException(
                    "same-owner renewal requires current ownership and lease extension"
                );
            }
        } else if (changedAt.isBefore(leaseUntil)) {
            throw new IllegalArgumentException("lease takeover requires expiry");
        }
        return new ApprovalMigrationCommandFence(
            fenceId,
            tenantId,
            approvalInstanceId,
            attemptId,
            operation,
            FenceStatus.ACTIVE,
            revision + 1,
            normalizedActor,
            changedLease,
            idempotencyKey,
            requestHash,
            acquiredAt,
            changedAt,
            null,
            requestId,
            traceId
        );
    }

    public enum FenceStatus {
        ACTIVE,
        RELEASED
    }
}
