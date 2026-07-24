package io.github.akaryc1b.approval.domain.migration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only server-side approval evidence bound to one exact migration plan hash. */
public record ApprovalMigrationPlanAuthorization(
    UUID authorizationId,
    String tenantId,
    UUID planId,
    String planHash,
    int selectedInstanceCount,
    int sourceReleaseVersion,
    String sourcePackageHash,
    int targetReleaseVersion,
    String targetPackageHash,
    String authorizationPolicy,
    String authorizationPolicyVersion,
    String authorizationEvidenceHash,
    String authorizedBy,
    String reason,
    String idempotencyKey,
    Instant decidedAt,
    Instant expiresAt,
    String requestId,
    String traceId,
    String auditChainReference
) {
    public ApprovalMigrationPlanAuthorization {
        authorizationId = Objects.requireNonNull(
            authorizationId,
            "authorizationId must not be null"
        );
        tenantId = ApprovalMigrationPlanRules.requireText(tenantId, "tenantId", 128);
        planId = Objects.requireNonNull(planId, "planId must not be null");
        planHash = ApprovalMigrationPlanRules.requireHash(planHash, "planHash");
        if (selectedInstanceCount < 1 || selectedInstanceCount > 1000) {
            throw new IllegalArgumentException(
                "selectedInstanceCount must be between 1 and 1000"
            );
        }
        ApprovalMigrationPlanRules.requirePositive(
            sourceReleaseVersion,
            "sourceReleaseVersion"
        );
        sourcePackageHash = ApprovalMigrationPlanRules.requireHash(
            sourcePackageHash,
            "sourcePackageHash"
        );
        ApprovalMigrationPlanRules.requirePositive(
            targetReleaseVersion,
            "targetReleaseVersion"
        );
        targetPackageHash = ApprovalMigrationPlanRules.requireHash(
            targetPackageHash,
            "targetPackageHash"
        );
        authorizationPolicy = ApprovalMigrationPlanRules.requireText(
            authorizationPolicy,
            "authorizationPolicy",
            128
        );
        authorizationPolicyVersion = ApprovalMigrationPlanRules.requireText(
            authorizationPolicyVersion,
            "authorizationPolicyVersion",
            64
        );
        authorizationEvidenceHash = ApprovalMigrationPlanRules.requireHash(
            authorizationEvidenceHash,
            "authorizationEvidenceHash"
        );
        authorizedBy = ApprovalMigrationPlanRules.requireText(
            authorizedBy,
            "authorizedBy",
            256
        );
        reason = ApprovalMigrationPlanRules.requireText(reason, "reason", 1000);
        idempotencyKey = ApprovalMigrationPlanRules.requireText(
            idempotencyKey,
            "idempotencyKey",
            200
        );
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(decidedAt)) {
            throw new IllegalArgumentException("authorization expiry must follow decision time");
        }
        requestId = ApprovalMigrationPlanRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationPlanRules.optionalText(traceId, "traceId", 256);
        auditChainReference = ApprovalMigrationPlanRules.requireText(
            auditChainReference,
            "auditChainReference",
            256
        );
    }
}
