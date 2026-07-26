package io.github.akaryc1b.approval.domain.migration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable evidence that exact engine migration could not complete platform binding CAS. */
public record ApprovalMigrationBindingCasConflictEvidence(
    UUID conflictId,
    String tenantId,
    UUID intentId,
    UUID attemptId,
    UUID approvalInstanceId,
    UUID verificationId,
    long expectedBindingRevision,
    String expectedBindingEvidenceHash,
    int expectedSourceReleaseVersion,
    String expectedSourcePackageHash,
    String expectedSourceEngineDefinitionId,
    Long observedBindingRevision,
    String observedBindingEvidenceHash,
    Integer observedReleaseVersion,
    String observedPackageHash,
    String observedEngineDefinitionId,
    String verificationEvidenceHash,
    String requestHash,
    String conflictEvidenceHash,
    Instant recordedAt,
    String requestId,
    String traceId
) {
    public ApprovalMigrationBindingCasConflictEvidence {
        conflictId = Objects.requireNonNull(conflictId, "conflictId must not be null");
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
        ApprovalMigrationRules.requirePositive(expectedBindingRevision, "expectedBindingRevision");
        expectedBindingEvidenceHash = ApprovalMigrationRules.requireHash(
            expectedBindingEvidenceHash,
            "expectedBindingEvidenceHash"
        );
        ApprovalMigrationRules.requirePositive(
            expectedSourceReleaseVersion,
            "expectedSourceReleaseVersion"
        );
        expectedSourcePackageHash = ApprovalMigrationRules.requireHash(
            expectedSourcePackageHash,
            "expectedSourcePackageHash"
        );
        expectedSourceEngineDefinitionId = ApprovalMigrationRules.requireText(
            expectedSourceEngineDefinitionId,
            "expectedSourceEngineDefinitionId",
            256
        );
        if (observedBindingRevision != null) {
            ApprovalMigrationRules.requirePositive(observedBindingRevision, "observedBindingRevision");
            observedBindingEvidenceHash = ApprovalMigrationRules.requireHash(
                observedBindingEvidenceHash,
                "observedBindingEvidenceHash"
            );
            if (observedReleaseVersion == null) {
                throw new IllegalArgumentException("observed release version is required with binding");
            }
            ApprovalMigrationRules.requirePositive(observedReleaseVersion, "observedReleaseVersion");
            observedPackageHash = ApprovalMigrationRules.requireHash(
                observedPackageHash,
                "observedPackageHash"
            );
            observedEngineDefinitionId = ApprovalMigrationRules.requireText(
                observedEngineDefinitionId,
                "observedEngineDefinitionId",
                256
            );
        } else if (observedBindingEvidenceHash != null || observedReleaseVersion != null
            || observedPackageHash != null || observedEngineDefinitionId != null) {
            throw new IllegalArgumentException("missing binding cannot retain partial observed identity");
        }
        verificationEvidenceHash = ApprovalMigrationRules.requireHash(
            verificationEvidenceHash,
            "verificationEvidenceHash"
        );
        requestHash = ApprovalMigrationRules.requireHash(requestHash, "requestHash");
        conflictEvidenceHash = ApprovalMigrationRules.requireHash(
            conflictEvidenceHash,
            "conflictEvidenceHash"
        );
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
    }
}
