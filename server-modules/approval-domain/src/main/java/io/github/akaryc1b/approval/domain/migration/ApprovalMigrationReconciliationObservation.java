package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification.ExactClassification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable server-derived public-engine observation for one reconciliation lease. */
public record ApprovalMigrationReconciliationObservation(
    UUID observationId,
    String tenantId,
    UUID intentId,
    UUID attemptId,
    UUID reconciliationId,
    UUID leaseId,
    String workerId,
    long expectedAttemptRevision,
    long expectedLeaseRevision,
    String sourceEngineDefinitionId,
    String targetEngineDefinitionId,
    ExactClassification classification,
    ReconciliationDisposition disposition,
    ApprovalMigrationEngineSnapshot snapshot,
    String requestHash,
    String evidenceHash,
    Instant recordedAt,
    String requestId,
    String traceId
) {
    public ApprovalMigrationReconciliationObservation {
        observationId = Objects.requireNonNull(observationId, "observationId must not be null");
        tenantId = ApprovalMigrationRules.requireText(tenantId, "tenantId", 128);
        intentId = Objects.requireNonNull(intentId, "intentId must not be null");
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        reconciliationId = Objects.requireNonNull(
            reconciliationId,
            "reconciliationId must not be null"
        );
        leaseId = Objects.requireNonNull(leaseId, "leaseId must not be null");
        workerId = ApprovalMigrationRules.requireText(workerId, "workerId", 200);
        ApprovalMigrationRules.requirePositive(expectedAttemptRevision, "expectedAttemptRevision");
        ApprovalMigrationRules.requirePositive(expectedLeaseRevision, "expectedLeaseRevision");
        sourceEngineDefinitionId = ApprovalMigrationRules.requireText(
            sourceEngineDefinitionId,
            "sourceEngineDefinitionId",
            256
        );
        targetEngineDefinitionId = ApprovalMigrationRules.requireText(
            targetEngineDefinitionId,
            "targetEngineDefinitionId",
            256
        );
        if (sourceEngineDefinitionId.equals(targetEngineDefinitionId)) {
            throw new IllegalArgumentException("source and target definitions must be distinct");
        }
        classification = Objects.requireNonNull(classification, "classification must not be null");
        disposition = Objects.requireNonNull(disposition, "disposition must not be null");
        snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        ExactClassification derived = ApprovalMigrationExactVerification.classify(
            snapshot,
            sourceEngineDefinitionId,
            targetEngineDefinitionId
        );
        if (classification != derived || disposition != dispositionFor(derived)) {
            throw new IllegalArgumentException("reconciliation observation is not server-derived");
        }
        requestHash = ApprovalMigrationRules.requireHash(requestHash, "requestHash");
        evidenceHash = ApprovalMigrationRules.requireHash(evidenceHash, "evidenceHash");
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
    }

    public static ReconciliationDisposition dispositionFor(ExactClassification classification) {
        Objects.requireNonNull(classification, "classification must not be null");
        return switch (classification) {
            case EXACT_SOURCE_RUNTIME -> ReconciliationDisposition.SOURCE_CONFIRMED_NO_RETRY;
            case SOURCE_HISTORY_TERMINAL ->
                ReconciliationDisposition.SOURCE_TERMINAL_CONFIRMED_NO_RETRY;
            case EXACT_TARGET_RUNTIME ->
                ReconciliationDisposition.TARGET_CONFIRMED_BINDING_CAS_REQUIRED;
            case TARGET_HISTORY_TERMINAL ->
                ReconciliationDisposition.TARGET_TERMINAL_BINDING_CAS_REQUIRED;
            default -> ReconciliationDisposition.MANUAL_REVIEW_REQUIRED;
        };
    }

    public enum ReconciliationDisposition {
        SOURCE_CONFIRMED_NO_RETRY,
        SOURCE_TERMINAL_CONFIRMED_NO_RETRY,
        TARGET_CONFIRMED_BINDING_CAS_REQUIRED,
        TARGET_TERMINAL_BINDING_CAS_REQUIRED,
        MANUAL_REVIEW_REQUIRED
    }
}
