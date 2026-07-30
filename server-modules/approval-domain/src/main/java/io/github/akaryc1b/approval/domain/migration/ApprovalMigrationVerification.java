package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.VerificationOutcome;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable authoritative public-readback verification snapshot. */
public record ApprovalMigrationVerification(
    UUID verificationId,
    String tenantId,
    UUID intentId,
    UUID attemptId,
    int sequence,
    String expectedBindingEvidenceHash,
    String observedBindingEvidenceHash,
    String sourceEngineDefinitionId,
    String targetEngineDefinitionId,
    String observedEngineDefinitionId,
    List<String> expectedActiveTaskKeys,
    List<String> observedActiveTaskKeys,
    boolean runtimePresent,
    boolean historyPresent,
    VerificationOutcome outcome,
    String evidenceHash,
    Instant recordedAt,
    String requestId,
    String traceId
) {
    public ApprovalMigrationVerification {
        verificationId = Objects.requireNonNull(verificationId, "verificationId must not be null");
        tenantId = ApprovalMigrationRules.requireText(tenantId, "tenantId", 128);
        intentId = Objects.requireNonNull(intentId, "intentId must not be null");
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        ApprovalMigrationRules.requirePositive(sequence, "sequence");
        expectedBindingEvidenceHash = ApprovalMigrationRules.requireHash(
            expectedBindingEvidenceHash,
            "expectedBindingEvidenceHash"
        );
        observedBindingEvidenceHash = ApprovalMigrationRules.optionalHash(
            observedBindingEvidenceHash,
            "observedBindingEvidenceHash"
        );
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
        observedEngineDefinitionId = ApprovalMigrationRules.optionalText(
            observedEngineDefinitionId,
            "observedEngineDefinitionId",
            256
        );
        expectedActiveTaskKeys = ApprovalMigrationRules.canonicalKeys(
            expectedActiveTaskKeys,
            "expectedActiveTaskKeys"
        );
        observedActiveTaskKeys = ApprovalMigrationRules.canonicalKeys(
            observedActiveTaskKeys,
            "observedActiveTaskKeys"
        );
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        evidenceHash = ApprovalMigrationRules.requireHash(evidenceHash, "evidenceHash");
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
        validateOutcome(
            outcome,
            runtimePresent,
            historyPresent,
            sourceEngineDefinitionId,
            targetEngineDefinitionId,
            observedEngineDefinitionId
        );
    }

    private static void validateOutcome(
        VerificationOutcome outcome,
        boolean runtimePresent,
        boolean historyPresent,
        String sourceEngineDefinitionId,
        String targetEngineDefinitionId,
        String observedEngineDefinitionId
    ) {
        if (outcome == VerificationOutcome.SOURCE_CONFIRMED
            && (!runtimePresent || !sourceEngineDefinitionId.equals(observedEngineDefinitionId))) {
            throw new IllegalArgumentException("source confirmation requires matching runtime definition");
        }
        if (outcome == VerificationOutcome.TARGET_CONFIRMED
            && (!runtimePresent || !targetEngineDefinitionId.equals(observedEngineDefinitionId))) {
            throw new IllegalArgumentException("target confirmation requires matching runtime definition");
        }
        if (outcome == VerificationOutcome.TARGET_TERMINAL_CONFIRMED
            && (runtimePresent || !historyPresent
                || !targetEngineDefinitionId.equals(observedEngineDefinitionId))) {
            throw new IllegalArgumentException("target terminal confirmation requires target history only");
        }
        if (outcome == VerificationOutcome.MISSING_NO_EVIDENCE
            && (runtimePresent || historyPresent || observedEngineDefinitionId != null)) {
            throw new IllegalArgumentException("missing evidence requires no runtime or history");
        }
    }
}
