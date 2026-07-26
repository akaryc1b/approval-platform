package io.github.akaryc1b.approval.domain.migration;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable exact D4 verification evidence bound to one engine request and outcome. */
public record ApprovalMigrationExactVerification(
    UUID verificationId,
    String tenantId,
    UUID intentId,
    UUID attemptId,
    UUID engineRequestId,
    UUID engineOutcomeId,
    String sourceEngineDefinitionId,
    String targetEngineDefinitionId,
    ExactClassification classification,
    ApprovalMigrationEngineSnapshot snapshot,
    String requestHash,
    String verificationEvidenceHash,
    Instant recordedAt,
    String requestId,
    String traceId
) {
    public ApprovalMigrationExactVerification {
        verificationId = Objects.requireNonNull(verificationId, "verificationId must not be null");
        tenantId = ApprovalMigrationRules.requireText(tenantId, "tenantId", 128);
        intentId = Objects.requireNonNull(intentId, "intentId must not be null");
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        engineRequestId = Objects.requireNonNull(engineRequestId, "engineRequestId must not be null");
        engineOutcomeId = Objects.requireNonNull(engineOutcomeId, "engineOutcomeId must not be null");
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
            throw new IllegalArgumentException("source and target engine definitions must be distinct");
        }
        classification = Objects.requireNonNull(classification, "classification must not be null");
        snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        requestHash = ApprovalMigrationRules.requireHash(requestHash, "requestHash");
        verificationEvidenceHash = ApprovalMigrationRules.requireHash(
            verificationEvidenceHash,
            "verificationEvidenceHash"
        );
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        requestId = ApprovalMigrationRules.requireText(requestId, "requestId", 256);
        traceId = ApprovalMigrationRules.optionalText(traceId, "traceId", 256);
        ExactClassification derived = classify(
            snapshot,
            sourceEngineDefinitionId,
            targetEngineDefinitionId
        );
        if (classification != derived) {
            throw new IllegalArgumentException(
                "verification classification does not match bounded snapshot: " + derived
            );
        }
    }

    public boolean exactTargetRuntime() {
        return classification == ExactClassification.EXACT_TARGET_RUNTIME
            && snapshot.readSucceeded()
            && !snapshot.truncated();
    }

    public static ExactClassification classify(
        ApprovalMigrationEngineSnapshot snapshot,
        String sourceEngineDefinitionId,
        String targetEngineDefinitionId
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
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
        if (!snapshot.readSucceeded()) {
            return ExactClassification.READ_FAILURE_RECONCILIATION_REQUIRED;
        }
        if (snapshot.truncated()) {
            return ExactClassification.TRUNCATED_MANUAL_REVIEW_REQUIRED;
        }
        if (!snapshot.runtimePresent() && !snapshot.historyPresent()) {
            return ExactClassification.MISSING_NO_EVIDENCE;
        }
        if (!snapshot.runtimePresent()) {
            if (snapshot.historicEndTime() == null) {
                return ExactClassification.STALE_OR_CONTRADICTORY_EVIDENCE;
            }
            if (sourceEngineDefinitionId.equals(snapshot.historicEngineDefinitionId())) {
                return ExactClassification.SOURCE_HISTORY_TERMINAL;
            }
            if (targetEngineDefinitionId.equals(snapshot.historicEngineDefinitionId())) {
                return ExactClassification.TARGET_HISTORY_TERMINAL;
            }
            return ExactClassification.STALE_OR_CONTRADICTORY_EVIDENCE;
        }
        if (!snapshot.historyPresent() || snapshot.historicEndTime() != null || snapshot.suspended()) {
            return ExactClassification.STALE_OR_CONTRADICTORY_EVIDENCE;
        }

        Set<String> observedDefinitions = observedDefinitions(snapshot);
        boolean sourceObserved = observedDefinitions.contains(sourceEngineDefinitionId);
        boolean targetObserved = observedDefinitions.contains(targetEngineDefinitionId);
        boolean unknownObserved = observedDefinitions.stream().anyMatch(
            definition -> !sourceEngineDefinitionId.equals(definition)
                && !targetEngineDefinitionId.equals(definition)
        );
        boolean missingDefinition = hasMissingDefinition(snapshot);
        if (sourceObserved && targetObserved) {
            return ExactClassification.MIXED_SOURCE_TARGET_EVIDENCE;
        }
        if (unknownObserved || missingDefinition) {
            return ExactClassification.STALE_OR_CONTRADICTORY_EVIDENCE;
        }
        if (targetEngineDefinitionId.equals(snapshot.runtimeEngineDefinitionId())
            && targetEngineDefinitionId.equals(snapshot.historicEngineDefinitionId())
            && targetObserved && !sourceObserved) {
            return ExactClassification.EXACT_TARGET_RUNTIME;
        }
        if (sourceEngineDefinitionId.equals(snapshot.runtimeEngineDefinitionId())
            && sourceEngineDefinitionId.equals(snapshot.historicEngineDefinitionId())
            && sourceObserved && !targetObserved) {
            return ExactClassification.EXACT_SOURCE_RUNTIME;
        }
        return ExactClassification.INCOMPLETE_RECONCILIATION_REQUIRED;
    }

    private static Set<String> observedDefinitions(ApprovalMigrationEngineSnapshot snapshot) {
        LinkedHashSet<String> definitions = new LinkedHashSet<>();
        add(definitions, snapshot.runtimeEngineDefinitionId());
        add(definitions, snapshot.historicEngineDefinitionId());
        snapshot.executions().forEach(value -> add(definitions, value.engineDefinitionId()));
        snapshot.activeTasks().forEach(value -> add(definitions, value.engineDefinitionId()));
        snapshot.jobs().forEach(value -> add(definitions, value.engineDefinitionId()));
        snapshot.subscriptions().forEach(value -> add(definitions, value.engineDefinitionId()));
        snapshot.historicTasks().forEach(value -> add(definitions, value.engineDefinitionId()));
        return Set.copyOf(definitions);
    }

    private static boolean hasMissingDefinition(ApprovalMigrationEngineSnapshot snapshot) {
        return snapshot.runtimeEngineDefinitionId() == null
            || snapshot.historicEngineDefinitionId() == null
            || snapshot.executions().stream().anyMatch(value -> value.engineDefinitionId() == null)
            || snapshot.activeTasks().stream().anyMatch(value -> value.engineDefinitionId() == null)
            || snapshot.jobs().stream().anyMatch(value -> value.engineDefinitionId() == null)
            || snapshot.subscriptions().stream().anyMatch(value -> value.engineDefinitionId() == null)
            || snapshot.historicTasks().stream().anyMatch(value -> value.engineDefinitionId() == null);
    }

    private static void add(Set<String> values, String value) {
        if (value != null) {
            values.add(value);
        }
    }

    public enum ExactClassification {
        EXACT_TARGET_RUNTIME,
        EXACT_SOURCE_RUNTIME,
        SOURCE_HISTORY_TERMINAL,
        TARGET_HISTORY_TERMINAL,
        MIXED_SOURCE_TARGET_EVIDENCE,
        MISSING_NO_EVIDENCE,
        STALE_OR_CONTRADICTORY_EVIDENCE,
        TRUNCATED_MANUAL_REVIEW_REQUIRED,
        READ_FAILURE_RECONCILIATION_REQUIRED,
        INCOMPLETE_RECONCILIATION_REQUIRED
    }
}
