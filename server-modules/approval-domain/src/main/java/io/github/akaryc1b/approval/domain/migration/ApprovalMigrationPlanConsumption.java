package io.github.akaryc1b.approval.domain.migration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only admission evidence binding one consumed plan to one exact execution intent. */
public record ApprovalMigrationPlanConsumption(
    UUID consumptionId,
    String tenantId,
    UUID planId,
    String planHash,
    UUID authorizationId,
    String authorizationEvidenceHash,
    UUID intentId,
    String intentEvidenceHash,
    String idempotencyKey,
    String requestHash,
    String consumedBy,
    String reason,
    Instant consumedAt,
    String requestId,
    String traceId,
    String auditChainReference
) {
    public ApprovalMigrationPlanConsumption {
        consumptionId = Objects.requireNonNull(consumptionId, "consumptionId must not be null");
        tenantId = ApprovalMigrationPlanRules.requireText(tenantId, "tenantId", 128);
        planId = Objects.requireNonNull(planId, "planId must not be null");
        planHash = ApprovalMigrationPlanRules.requireHash(planHash, "planHash");
        authorizationId = Objects.requireNonNull(
            authorizationId,
            "authorizationId must not be null"
        );
        authorizationEvidenceHash = ApprovalMigrationPlanRules.requireHash(
            authorizationEvidenceHash,
            "authorizationEvidenceHash"
        );
        intentId = Objects.requireNonNull(intentId, "intentId must not be null");
        intentEvidenceHash = ApprovalMigrationPlanRules.requireHash(
            intentEvidenceHash,
            "intentEvidenceHash"
        );
        idempotencyKey = ApprovalMigrationPlanRules.requireText(
            idempotencyKey,
            "idempotencyKey",
            200
        );
        requestHash = ApprovalMigrationPlanRules.requireHash(requestHash, "requestHash");
        consumedBy = ApprovalMigrationPlanRules.requireText(consumedBy, "consumedBy", 256);
        reason = ApprovalMigrationPlanRules.requireText(reason, "reason", 1000);
        consumedAt = Objects.requireNonNull(consumedAt, "consumedAt must not be null");
        requestId = ApprovalMigrationPlanRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationPlanRules.optionalText(traceId, "traceId", 256);
        auditChainReference = ApprovalMigrationPlanRules.requireText(
            auditChainReference,
            "auditChainReference",
            256
        );
    }
}
