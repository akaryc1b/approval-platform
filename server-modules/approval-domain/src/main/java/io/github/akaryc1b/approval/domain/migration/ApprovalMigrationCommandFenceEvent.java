package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence.FenceStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only revision evidence for one shared migration command fence. */
public record ApprovalMigrationCommandFenceEvent(
    UUID eventId,
    String tenantId,
    UUID fenceId,
    UUID approvalInstanceId,
    UUID attemptId,
    long revision,
    FenceStatus fromStatus,
    FenceStatus toStatus,
    String leaseActor,
    String leaseOwner,
    Instant leaseUntil,
    Instant happenedAt,
    String requestId,
    String traceId
) {
    public ApprovalMigrationCommandFenceEvent {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        tenantId = ApprovalMigrationRules.requireText(tenantId, "tenantId", 128);
        fenceId = Objects.requireNonNull(fenceId, "fenceId must not be null");
        approvalInstanceId = Objects.requireNonNull(
            approvalInstanceId,
            "approvalInstanceId must not be null"
        );
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        ApprovalMigrationRules.requirePositive(revision, "revision");
        toStatus = Objects.requireNonNull(toStatus, "toStatus must not be null");
        leaseActor = ApprovalMigrationRules.requireText(leaseActor, "leaseActor", 200);
        leaseOwner = ApprovalMigrationRules.requireText(leaseOwner, "leaseOwner", 200);
        leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
        if (!leaseUntil.isAfter(happenedAt)) {
            throw new IllegalArgumentException("fence event lease must exceed happenedAt");
        }
        if (fromStatus == null) {
            if (revision != 1 || toStatus != FenceStatus.ACTIVE
                || !leaseActor.equals(leaseOwner)) {
                throw new IllegalArgumentException(
                    "initial fence event must create an actor-owned ACTIVE revision 1"
                );
            }
        } else if (revision < 2 || fromStatus != FenceStatus.ACTIVE) {
            throw new IllegalArgumentException("fence transition evidence is invalid");
        }
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
    }

    public static ApprovalMigrationCommandFenceEvent from(
        UUID eventId,
        ApprovalMigrationCommandFence previous,
        ApprovalMigrationCommandFence current,
        String actor
    ) {
        Objects.requireNonNull(current, "current must not be null");
        if (previous != null
            && (!previous.fenceId().equals(current.fenceId())
                || previous.revision() + 1 != current.revision())) {
            throw new IllegalArgumentException("fence event chain is inconsistent");
        }
        return new ApprovalMigrationCommandFenceEvent(
            eventId,
            current.tenantId(),
            current.fenceId(),
            current.approvalInstanceId(),
            current.attemptId(),
            current.revision(),
            previous == null ? null : previous.status(),
            current.status(),
            actor,
            current.leaseOwner(),
            current.leaseUntil(),
            current.updatedAt(),
            current.requestId(),
            current.traceId()
        );
    }
}
