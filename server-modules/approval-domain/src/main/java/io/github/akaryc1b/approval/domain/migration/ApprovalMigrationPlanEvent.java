package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only current-plan transition evidence. */
public record ApprovalMigrationPlanEvent(
    UUID eventId,
    String tenantId,
    UUID planId,
    String planHash,
    long revision,
    PlanStatus fromStatus,
    PlanStatus toStatus,
    String actorId,
    String reason,
    UUID authorizationId,
    String authorizationEvidenceHash,
    Instant happenedAt,
    String requestId,
    String traceId,
    String auditChainReference
) {
    public ApprovalMigrationPlanEvent {
        eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        tenantId = ApprovalMigrationPlanRules.requireText(tenantId, "tenantId", 128);
        planId = Objects.requireNonNull(planId, "planId must not be null");
        planHash = ApprovalMigrationPlanRules.requireHash(planHash, "planHash");
        ApprovalMigrationPlanRules.requirePositive(revision, "revision");
        toStatus = Objects.requireNonNull(toStatus, "toStatus must not be null");
        actorId = ApprovalMigrationPlanRules.requireText(actorId, "actorId", 256);
        reason = ApprovalMigrationPlanRules.requireText(reason, "reason", 1000);
        happenedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
        requestId = ApprovalMigrationPlanRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationPlanRules.optionalText(traceId, "traceId", 256);
        auditChainReference = ApprovalMigrationPlanRules.requireText(
            auditChainReference,
            "auditChainReference",
            256
        );
        if (revision == 1) {
            if (fromStatus != null || toStatus != PlanStatus.PROPOSED
                || authorizationId != null || authorizationEvidenceHash != null) {
                throw new IllegalArgumentException(
                    "initial migration plan event must create PROPOSED revision 1"
                );
            }
        } else {
            ApprovalMigrationPlanProtocol.requireTransition(fromStatus, toStatus);
            if (toStatus == PlanStatus.AUTHORIZED) {
                authorizationId = Objects.requireNonNull(
                    authorizationId,
                    "authorizationId must not be null"
                );
                authorizationEvidenceHash = ApprovalMigrationPlanRules.requireHash(
                    authorizationEvidenceHash,
                    "authorizationEvidenceHash"
                );
            } else if (authorizationId != null || authorizationEvidenceHash != null) {
                throw new IllegalArgumentException(
                    "only authorization transition may add authorization evidence"
                );
            }
        }
    }
}
