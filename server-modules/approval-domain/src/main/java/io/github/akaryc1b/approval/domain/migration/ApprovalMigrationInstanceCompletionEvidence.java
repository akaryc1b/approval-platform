package io.github.akaryc1b.approval.domain.migration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable per-instance completion evidence after exact target binding CAS. */
public record ApprovalMigrationInstanceCompletionEvidence(
    UUID completionId,
    String tenantId,
    UUID intentId,
    UUID attemptId,
    UUID approvalInstanceId,
    UUID verificationId,
    UUID bindingEvidenceId,
    long bindingRevision,
    long expectedAttemptRevision,
    long expectedFenceRevision,
    String requestHash,
    String sourceBindingEvidenceHash,
    String targetBindingEvidenceHash,
    int sourceReleaseVersion,
    String sourcePackageHash,
    String sourceEngineDefinitionId,
    int targetReleaseVersion,
    String targetPackageHash,
    String targetEngineDeploymentId,
    String targetEngineDefinitionId,
    String verificationEvidenceHash,
    String completionEvidenceHash,
    Instant completedAt,
    String requestId,
    String traceId
) {
    public ApprovalMigrationInstanceCompletionEvidence {
        completionId = Objects.requireNonNull(completionId, "completionId must not be null");
        tenantId = ApprovalMigrationRules.requireText(tenantId, "tenantId", 128);
        intentId = Objects.requireNonNull(intentId, "intentId must not be null");
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        approvalInstanceId = Objects.requireNonNull(
            approvalInstanceId,
            "approvalInstanceId must not be null"
        );
        verificationId = Objects.requireNonNull(
            verificationId,
            "verificationId must not be null"
        );
        bindingEvidenceId = Objects.requireNonNull(
            bindingEvidenceId,
            "bindingEvidenceId must not be null"
        );
        ApprovalMigrationRules.requirePositive(bindingRevision, "bindingRevision");
        if (bindingRevision < 2) {
            throw new IllegalArgumentException("migration completion requires binding revision 2 or later");
        }
        ApprovalMigrationRules.requirePositive(expectedAttemptRevision, "expectedAttemptRevision");
        ApprovalMigrationRules.requirePositive(expectedFenceRevision, "expectedFenceRevision");
        requestHash = ApprovalMigrationRules.requireHash(requestHash, "requestHash");
        sourceBindingEvidenceHash = ApprovalMigrationRules.requireHash(
            sourceBindingEvidenceHash,
            "sourceBindingEvidenceHash"
        );
        targetBindingEvidenceHash = ApprovalMigrationRules.requireHash(
            targetBindingEvidenceHash,
            "targetBindingEvidenceHash"
        );
        if (sourceBindingEvidenceHash.equals(targetBindingEvidenceHash)) {
            throw new IllegalArgumentException("source and target binding evidence must be distinct");
        }
        ApprovalMigrationRules.requirePositive(sourceReleaseVersion, "sourceReleaseVersion");
        sourcePackageHash = ApprovalMigrationRules.requireHash(
            sourcePackageHash,
            "sourcePackageHash"
        );
        sourceEngineDefinitionId = ApprovalMigrationRules.requireText(
            sourceEngineDefinitionId,
            "sourceEngineDefinitionId",
            256
        );
        ApprovalMigrationRules.requirePositive(targetReleaseVersion, "targetReleaseVersion");
        targetPackageHash = ApprovalMigrationRules.requireHash(
            targetPackageHash,
            "targetPackageHash"
        );
        targetEngineDeploymentId = ApprovalMigrationRules.requireText(
            targetEngineDeploymentId,
            "targetEngineDeploymentId",
            128
        );
        targetEngineDefinitionId = ApprovalMigrationRules.requireText(
            targetEngineDefinitionId,
            "targetEngineDefinitionId",
            256
        );
        if (sourceReleaseVersion == targetReleaseVersion
            || sourcePackageHash.equals(targetPackageHash)
            || sourceEngineDefinitionId.equals(targetEngineDefinitionId)) {
            throw new IllegalArgumentException("completion must move from exact source to distinct target");
        }
        verificationEvidenceHash = ApprovalMigrationRules.requireHash(
            verificationEvidenceHash,
            "verificationEvidenceHash"
        );
        completionEvidenceHash = ApprovalMigrationRules.requireHash(
            completionEvidenceHash,
            "completionEvidenceHash"
        );
        completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
    }
}
