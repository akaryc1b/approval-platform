package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only intent creation or transition evidence. */
public record ApprovalMigrationIntentEvent(
    UUID eventId,
    String tenantId,
    UUID intentId,
    long revision,
    IntentStatus fromStatus,
    IntentStatus toStatus,
    String reason,
    String operatorId,
    Instant happenedAt,
    String requestId,
    String traceId,
    String auditChainReference
) {
    public ApprovalMigrationIntentEvent {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        tenantId = ApprovalMigrationRules.requireText(tenantId, "tenantId", 128);
        intentId = Objects.requireNonNull(intentId, "intentId must not be null");
        ApprovalMigrationRules.requirePositive(revision, "revision");
        toStatus = Objects.requireNonNull(toStatus, "toStatus must not be null");
        if (fromStatus == null) {
            if (revision != 1 || toStatus != IntentStatus.PENDING) {
                throw new IllegalArgumentException("initial intent event must create PENDING revision 1");
            }
        } else {
            if (revision < 2) {
                throw new IllegalArgumentException("transition event revision must exceed one");
            }
            ApprovalMigrationProtocol.requireIntentTransition(fromStatus, toStatus);
        }
        reason = ApprovalMigrationRules.requireText(reason, "reason", 1000);
        operatorId = ApprovalMigrationRules.requireText(operatorId, "operatorId", 256);
        happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
        auditChainReference = ApprovalMigrationRules.requireText(
            auditChainReference,
            "auditChainReference",
            256
        );
    }
}
