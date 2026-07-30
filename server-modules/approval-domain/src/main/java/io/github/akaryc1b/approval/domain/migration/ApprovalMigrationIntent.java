package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Tenant-scoped current migration intent state. */
public record ApprovalMigrationIntent(
    UUID intentId,
    String tenantId,
    UUID planId,
    String planHash,
    String definitionKey,
    int sourceReleaseVersion,
    String sourcePackageHash,
    int targetReleaseVersion,
    String targetPackageHash,
    int selectedInstanceCount,
    IntentStatus status,
    long revision,
    String idempotencyKey,
    String intentEvidenceHash,
    String requestedBy,
    String operationReason,
    Instant expiresAt,
    Instant createdAt,
    Instant updatedAt,
    String requestId,
    String traceId,
    String auditChainReference
) {
    public ApprovalMigrationIntent {
        intentId = Objects.requireNonNull(intentId, "intentId must not be null");
        tenantId = ApprovalMigrationRules.requireText(tenantId, "tenantId", 128);
        planId = Objects.requireNonNull(planId, "planId must not be null");
        planHash = ApprovalMigrationRules.requireHash(planHash, "planHash");
        definitionKey = ApprovalMigrationRules.requireText(definitionKey, "definitionKey", 64);
        ApprovalMigrationRules.requirePositive(sourceReleaseVersion, "sourceReleaseVersion");
        sourcePackageHash = ApprovalMigrationRules.requireHash(sourcePackageHash, "sourcePackageHash");
        ApprovalMigrationRules.requirePositive(targetReleaseVersion, "targetReleaseVersion");
        targetPackageHash = ApprovalMigrationRules.requireHash(targetPackageHash, "targetPackageHash");
        if (sourceReleaseVersion == targetReleaseVersion || sourcePackageHash.equals(targetPackageHash)) {
            throw new IllegalArgumentException("source and target release must be distinct");
        }
        if (selectedInstanceCount < 1 || selectedInstanceCount > 1000) {
            throw new IllegalArgumentException("selectedInstanceCount must be between 1 and 1000");
        }
        status = Objects.requireNonNull(status, "status must not be null");
        ApprovalMigrationRules.requirePositive(revision, "revision");
        idempotencyKey = ApprovalMigrationRules.requireText(idempotencyKey, "idempotencyKey", 200);
        intentEvidenceHash = ApprovalMigrationRules.requireHash(intentEvidenceHash, "intentEvidenceHash");
        requestedBy = ApprovalMigrationRules.requireText(requestedBy, "requestedBy", 256);
        operationReason = ApprovalMigrationRules.requireText(operationReason, "operationReason", 1000);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (!expiresAt.isAfter(createdAt) || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("intent timestamps are inconsistent");
        }
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
        auditChainReference = ApprovalMigrationRules.requireText(
            auditChainReference,
            "auditChainReference",
            256
        );
    }

    public ApprovalMigrationIntent transitioned(IntentStatus next, Instant happenedAt) {
        ApprovalMigrationProtocol.requireIntentTransition(status, next);
        Instant changedAt = Objects.requireNonNull(happenedAt, "happenedAt must not be null");
        if (changedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("intent transition time moved backwards");
        }
        return new ApprovalMigrationIntent(
            intentId, tenantId, planId, planHash, definitionKey,
            sourceReleaseVersion, sourcePackageHash, targetReleaseVersion, targetPackageHash,
            selectedInstanceCount, next, revision + 1, idempotencyKey, intentEvidenceHash,
            requestedBy, operationReason, expiresAt, createdAt, changedAt,
            requestId, traceId, auditChainReference
        );
    }

    public boolean terminal() {
        return ApprovalMigrationProtocol.terminal(status);
    }
}
