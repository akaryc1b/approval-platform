package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.ReconciliationStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable reconciliation progression and resolution evidence. */
public record ApprovalMigrationReconciliation(
    UUID reconciliationId,
    String tenantId,
    UUID intentId,
    UUID attemptId,
    int sequence,
    ReconciliationStatus status,
    FailureClass failureClass,
    String reason,
    String evidenceHash,
    String resolutionEvidenceHash,
    String resolvedBy,
    Instant recordedAt,
    Instant resolvedAt,
    String requestId,
    String traceId,
    String auditChainReference
) {
    public ApprovalMigrationReconciliation {
        reconciliationId = Objects.requireNonNull(
            reconciliationId,
            "reconciliationId must not be null"
        );
        tenantId = ApprovalMigrationRules.requireText(tenantId, "tenantId", 128);
        intentId = Objects.requireNonNull(intentId, "intentId must not be null");
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        ApprovalMigrationRules.requirePositive(sequence, "sequence");
        status = Objects.requireNonNull(status, "status must not be null");
        failureClass = Objects.requireNonNull(failureClass, "failureClass must not be null");
        if (failureClass == FailureClass.NONE) {
            throw new IllegalArgumentException("reconciliation requires a bounded failure class");
        }
        reason = ApprovalMigrationRules.requireText(reason, "reason", 1000);
        evidenceHash = ApprovalMigrationRules.requireHash(evidenceHash, "evidenceHash");
        resolutionEvidenceHash = ApprovalMigrationRules.optionalHash(
            resolutionEvidenceHash,
            "resolutionEvidenceHash"
        );
        resolvedBy = ApprovalMigrationRules.optionalText(resolvedBy, "resolvedBy", 256);
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
        auditChainReference = ApprovalMigrationRules.requireText(
            auditChainReference,
            "auditChainReference",
            256
        );
        boolean terminal = ApprovalMigrationProtocol.terminal(status);
        if (terminal != (resolutionEvidenceHash != null && resolvedBy != null && resolvedAt != null)) {
            throw new IllegalArgumentException("reconciliation resolution evidence is inconsistent");
        }
        if (!terminal && resolvedAt != null) {
            throw new IllegalArgumentException("open reconciliation cannot have resolvedAt");
        }
        if (resolvedAt != null && resolvedAt.isBefore(recordedAt)) {
            throw new IllegalArgumentException("resolvedAt must not precede recordedAt");
        }
    }
}
