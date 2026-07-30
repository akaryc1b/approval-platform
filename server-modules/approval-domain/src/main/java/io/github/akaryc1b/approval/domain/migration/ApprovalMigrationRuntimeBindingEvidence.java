package io.github.akaryc1b.approval.domain.migration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable evidence for one exact runtime-binding revision. */
public record ApprovalMigrationRuntimeBindingEvidence(
    UUID bindingEvidenceId,
    String tenantId,
    UUID approvalInstanceId,
    long bindingRevision,
    UUID attemptId,
    UUID verificationId,
    String previousBindingEvidenceHash,
    String bindingEvidenceHash,
    String definitionKey,
    int releaseVersion,
    String releasePackageHash,
    String engineDeploymentId,
    String engineDefinitionId,
    int engineVersion,
    String evidenceHash,
    Instant recordedAt,
    String requestId,
    String traceId
) {
    public ApprovalMigrationRuntimeBindingEvidence {
        bindingEvidenceId = Objects.requireNonNull(
            bindingEvidenceId,
            "bindingEvidenceId must not be null"
        );
        tenantId = ApprovalMigrationRules.requireText(tenantId, "tenantId", 128);
        approvalInstanceId = Objects.requireNonNull(
            approvalInstanceId,
            "approvalInstanceId must not be null"
        );
        ApprovalMigrationRules.requirePositive(bindingRevision, "bindingRevision");
        if (bindingRevision == 1) {
            if (attemptId != null || verificationId != null || previousBindingEvidenceHash != null) {
                throw new IllegalArgumentException(
                    "initial runtime-binding evidence cannot claim migration lineage"
                );
            }
        } else {
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            verificationId = Objects.requireNonNull(
                verificationId,
                "verificationId must not be null"
            );
            previousBindingEvidenceHash = ApprovalMigrationRules.requireHash(
                previousBindingEvidenceHash,
                "previousBindingEvidenceHash"
            );
        }
        bindingEvidenceHash = ApprovalMigrationRules.requireHash(
            bindingEvidenceHash,
            "bindingEvidenceHash"
        );
        definitionKey = ApprovalMigrationRules.requireText(definitionKey, "definitionKey", 64);
        ApprovalMigrationRules.requirePositive(releaseVersion, "releaseVersion");
        releasePackageHash = ApprovalMigrationRules.requireHash(
            releasePackageHash,
            "releasePackageHash"
        );
        engineDeploymentId = ApprovalMigrationRules.requireText(
            engineDeploymentId,
            "engineDeploymentId",
            128
        );
        engineDefinitionId = ApprovalMigrationRules.requireText(
            engineDefinitionId,
            "engineDefinitionId",
            256
        );
        ApprovalMigrationRules.requirePositive(engineVersion, "engineVersion");
        evidenceHash = ApprovalMigrationRules.requireHash(evidenceHash, "evidenceHash");
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
    }
}
