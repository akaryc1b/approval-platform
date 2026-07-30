package io.github.akaryc1b.approval.domain.migration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable bounded-claim replay evidence, including an exact empty claim result. */
public record ApprovalMigrationClaimBatch(
    UUID claimBatchId,
    String tenantId,
    UUID intentId,
    String workerId,
    int requestedLimit,
    List<UUID> claimedAttemptIds,
    List<UUID> fenceIds,
    String requestHash,
    Instant claimedAt,
    String requestId,
    String traceId
) {
    public ApprovalMigrationClaimBatch {
        claimBatchId = Objects.requireNonNull(claimBatchId, "claimBatchId must not be null");
        tenantId = ApprovalMigrationRules.requireText(tenantId, "tenantId", 128);
        intentId = Objects.requireNonNull(intentId, "intentId must not be null");
        workerId = ApprovalMigrationRules.requireText(workerId, "workerId", 200);
        if (requestedLimit < 1 || requestedLimit > 100) {
            throw new IllegalArgumentException("requestedLimit must be between 1 and 100");
        }
        claimedAttemptIds = claimedAttemptIds == null
            ? List.of()
            : List.copyOf(claimedAttemptIds);
        fenceIds = fenceIds == null ? List.of() : List.copyOf(fenceIds);
        if (claimedAttemptIds.size() != fenceIds.size()
            || claimedAttemptIds.size() > requestedLimit
            || claimedAttemptIds.stream().anyMatch(Objects::isNull)
            || fenceIds.stream().anyMatch(Objects::isNull)
            || claimedAttemptIds.stream().distinct().count() != claimedAttemptIds.size()
            || fenceIds.stream().distinct().count() != fenceIds.size()) {
            throw new IllegalArgumentException("claimed attempt and fence evidence is inconsistent");
        }
        requestHash = ApprovalMigrationRules.requireHash(requestHash, "requestHash");
        claimedAt = Objects.requireNonNull(claimedAt, "claimedAt must not be null");
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
    }

    public int claimedCount() {
        return claimedAttemptIds.size();
    }
}
