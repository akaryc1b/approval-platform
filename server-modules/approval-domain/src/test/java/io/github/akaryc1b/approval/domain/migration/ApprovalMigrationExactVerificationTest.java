package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.DefinitionEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.JobEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.TaskEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification.ExactClassification;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApprovalMigrationExactVerificationTest {

    private static final String SOURCE = "definition-source";
    private static final String TARGET = "definition-target";
    private static final String HASH = "a".repeat(64);

    @Test
    void classifiesExactTargetOnlyWhenEveryDefinitionEvidenceIsTarget() {
        assertEquals(ExactClassification.EXACT_TARGET_RUNTIME, classify(runtime(TARGET, TARGET, false,
            List.of(new JobEvidence(HASH, "TIMER", "WAITING", TARGET, "wait")))));
    }

    @Test
    void classifiesExactSourceWithoutAuthorizingRetry() {
        assertEquals(ExactClassification.EXACT_SOURCE_RUNTIME, classify(runtime(SOURCE, SOURCE, false,
            List.of())));
    }

    @Test
    void classifiesMixedSourceBoundResidualJob() {
        assertEquals(ExactClassification.MIXED_SOURCE_TARGET_EVIDENCE, classify(runtime(
            TARGET,
            TARGET,
            false,
            List.of(new JobEvidence(HASH, "EXECUTABLE", "PENDING", SOURCE, "async"))
        )));
    }

    @Test
    void classifiesTerminalSourceAndTargetHistorySeparately() {
        assertEquals(ExactClassification.SOURCE_HISTORY_TERMINAL, classify(historyOnly(SOURCE)));
        assertEquals(ExactClassification.TARGET_HISTORY_TERMINAL, classify(historyOnly(TARGET)));
    }

    @Test
    void classifiesMissingRuntimeAndHistory() {
        assertEquals(ExactClassification.MISSING_NO_EVIDENCE, classify(new ApprovalMigrationEngineSnapshot(
            true, null, false, null, null, false,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            false, null, null, null, List.of(), false, HASH
        )));
    }

    @Test
    void truncationAlwaysRequiresManualReview() {
        ApprovalMigrationEngineSnapshot target = runtime(TARGET, TARGET, true, List.of());
        assertEquals(ExactClassification.TRUNCATED_MANUAL_REVIEW_REQUIRED, classify(target));
    }

    @Test
    void readFailureRequiresReconciliation() {
        assertEquals(
            ExactClassification.READ_FAILURE_RECONCILIATION_REQUIRED,
            classify(ApprovalMigrationEngineSnapshot.readFailure("READ_FAILED", HASH))
        );
    }

    @Test
    void suspendedOrTerminalHistoryWithRuntimeIsContradictory() {
        ApprovalMigrationEngineSnapshot value = new ApprovalMigrationEngineSnapshot(
            true, null, true, TARGET, "deployment-target", true,
            List.of("review"),
            List.of(new DefinitionEvidence("EXECUTION", "execution", TARGET)),
            List.of(new TaskEvidence(HASH, "review", TARGET, true)),
            List.of(), List.of(), List.of(), List.of(),
            true, TARGET, Instant.parse("2026-07-26T12:00:00Z"), null,
            List.of(new TaskEvidence(HASH, "review", TARGET, false)),
            false, HASH
        );
        assertEquals(ExactClassification.STALE_OR_CONTRADICTORY_EVIDENCE, classify(value));
    }

    @Test
    void missingDefinitionIdentityCannotBeExactTarget() {
        ApprovalMigrationEngineSnapshot value = new ApprovalMigrationEngineSnapshot(
            true, null, true, TARGET, "deployment-target", false,
            List.of("review"),
            List.of(new DefinitionEvidence("EXECUTION", "execution", null)),
            List.of(new TaskEvidence(HASH, "review", TARGET, false)),
            List.of(), List.of(), List.of(), List.of(),
            true, TARGET, null, null,
            List.of(new TaskEvidence(HASH, "review", TARGET, false)),
            false, HASH
        );
        assertEquals(ExactClassification.STALE_OR_CONTRADICTORY_EVIDENCE, classify(value));
    }

    private static ExactClassification classify(ApprovalMigrationEngineSnapshot snapshot) {
        return ApprovalMigrationExactVerification.classify(snapshot, SOURCE, TARGET);
    }

    private static ApprovalMigrationEngineSnapshot runtime(
        String runtimeDefinition,
        String historyDefinition,
        boolean truncated,
        List<JobEvidence> jobs
    ) {
        return new ApprovalMigrationEngineSnapshot(
            true,
            null,
            true,
            runtimeDefinition,
            "deployment",
            false,
            List.of("review"),
            List.of(new DefinitionEvidence("EXECUTION", "execution", runtimeDefinition)),
            List.of(new TaskEvidence(HASH, "review", runtimeDefinition, false)),
            jobs,
            List.of(),
            List.of(HASH),
            List.of(HASH),
            true,
            historyDefinition,
            null,
            null,
            List.of(new TaskEvidence(HASH, "review", historyDefinition, false)),
            truncated,
            HASH
        );
    }

    private static ApprovalMigrationEngineSnapshot historyOnly(String definition) {
        return new ApprovalMigrationEngineSnapshot(
            true,
            null,
            false,
            null,
            null,
            false,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            true,
            definition,
            Instant.parse("2026-07-26T12:00:00Z"),
            "completed",
            List.of(new TaskEvidence(HASH, "review", definition, false)),
            false,
            HASH
        );
    }
}
