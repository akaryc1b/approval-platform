package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.DefinitionEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.TaskEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification.ExactClassification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationLease.ReconciliationLeaseStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationObservation.ReconciliationDisposition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalMigrationReconciliationObservationTest {

    private static final Instant NOW = Instant.parse("2026-07-27T02:00:00Z");
    private static final String SOURCE = "definition-source";
    private static final String TARGET = "definition-target";
    private static final String HASH = "a".repeat(64);

    @Test
    void closedDispositionNeverTreatsSourceEvidenceAsRetryAuthority() {
        assertEquals(
            ReconciliationDisposition.SOURCE_CONFIRMED_NO_RETRY,
            ApprovalMigrationReconciliationObservation.dispositionFor(
                ExactClassification.EXACT_SOURCE_RUNTIME
            )
        );
        assertEquals(
            ReconciliationDisposition.SOURCE_TERMINAL_CONFIRMED_NO_RETRY,
            ApprovalMigrationReconciliationObservation.dispositionFor(
                ExactClassification.SOURCE_HISTORY_TERMINAL
            )
        );
    }

    @Test
    void targetEvidenceRequiresSeparateBindingCas() {
        assertEquals(
            ReconciliationDisposition.TARGET_CONFIRMED_BINDING_CAS_REQUIRED,
            ApprovalMigrationReconciliationObservation.dispositionFor(
                ExactClassification.EXACT_TARGET_RUNTIME
            )
        );
        assertEquals(
            ReconciliationDisposition.TARGET_TERMINAL_BINDING_CAS_REQUIRED,
            ApprovalMigrationReconciliationObservation.dispositionFor(
                ExactClassification.TARGET_HISTORY_TERMINAL
            )
        );
    }

    @Test
    void incompleteEvidenceRequiresManualReview() {
        for (ExactClassification classification : List.of(
            ExactClassification.MIXED_SOURCE_TARGET_EVIDENCE,
            ExactClassification.MISSING_NO_EVIDENCE,
            ExactClassification.STALE_OR_CONTRADICTORY_EVIDENCE,
            ExactClassification.TRUNCATED_MANUAL_REVIEW_REQUIRED,
            ExactClassification.READ_FAILURE_RECONCILIATION_REQUIRED,
            ExactClassification.INCOMPLETE_RECONCILIATION_REQUIRED
        )) {
            assertEquals(
                ReconciliationDisposition.MANUAL_REVIEW_REQUIRED,
                ApprovalMigrationReconciliationObservation.dispositionFor(classification)
            );
        }
    }

    @Test
    void rejectsCallerSuppliedClassificationOrDisposition() {
        ApprovalMigrationEngineSnapshot snapshot = runtime(TARGET);
        assertThrows(
            IllegalArgumentException.class,
            () -> observation(
                snapshot,
                ExactClassification.EXACT_SOURCE_RUNTIME,
                ReconciliationDisposition.SOURCE_CONFIRMED_NO_RETRY
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> observation(
                snapshot,
                ExactClassification.EXACT_TARGET_RUNTIME,
                ReconciliationDisposition.MANUAL_REVIEW_REQUIRED
            )
        );
    }

    @Test
    void leaseRequiresFutureExpiryAndExactReleaseTime() {
        assertThrows(
            IllegalArgumentException.class,
            () -> lease(ReconciliationLeaseStatus.ACTIVE, NOW, null)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ApprovalMigrationReconciliationLease(
                UUID.fromString("56000000-0000-0000-0000-000000000005"),
                "tenant-d6",
                UUID.fromString("56000000-0000-0000-0000-000000000002"),
                UUID.fromString("56000000-0000-0000-0000-000000000003"),
                ReconciliationLeaseStatus.RELEASED,
                2,
                "worker-d6",
                NOW.plusSeconds(60),
                NOW,
                NOW.plusSeconds(40),
                NOW.plusSeconds(30),
                HASH,
                HASH,
                "request-d6",
                "trace-d6"
            )
        );
    }

    private static ApprovalMigrationReconciliationObservation observation(
        ApprovalMigrationEngineSnapshot snapshot,
        ExactClassification classification,
        ReconciliationDisposition disposition
    ) {
        return new ApprovalMigrationReconciliationObservation(
            UUID.fromString("56000000-0000-0000-0000-000000000001"),
            "tenant-d6",
            UUID.fromString("56000000-0000-0000-0000-000000000002"),
            UUID.fromString("56000000-0000-0000-0000-000000000003"),
            UUID.fromString("56000000-0000-0000-0000-000000000004"),
            UUID.fromString("56000000-0000-0000-0000-000000000005"),
            "worker-d6",
            5,
            1,
            SOURCE,
            TARGET,
            classification,
            disposition,
            snapshot,
            HASH,
            HASH,
            NOW,
            "request-d6",
            "trace-d6"
        );
    }

    private static ApprovalMigrationReconciliationLease lease(
        ReconciliationLeaseStatus status,
        Instant leaseUntil,
        Instant releasedAt
    ) {
        Instant updatedAt = releasedAt == null ? NOW : releasedAt;
        return new ApprovalMigrationReconciliationLease(
            UUID.fromString("56000000-0000-0000-0000-000000000005"),
            "tenant-d6",
            UUID.fromString("56000000-0000-0000-0000-000000000002"),
            UUID.fromString("56000000-0000-0000-0000-000000000003"),
            status,
            status == ReconciliationLeaseStatus.ACTIVE ? 1 : 2,
            "worker-d6",
            leaseUntil,
            NOW,
            updatedAt,
            releasedAt,
            HASH,
            HASH,
            "request-d6",
            "trace-d6"
        );
    }

    private static ApprovalMigrationEngineSnapshot runtime(String definition) {
        return new ApprovalMigrationEngineSnapshot(
            true,
            null,
            true,
            definition,
            "deployment",
            false,
            List.of("review"),
            List.of(new DefinitionEvidence("EXECUTION", "execution", definition)),
            List.of(new TaskEvidence(HASH, "review", definition, false)),
            List.of(),
            List.of(),
            List.of(HASH),
            List.of(HASH),
            true,
            definition,
            null,
            null,
            List.of(new TaskEvidence(HASH, "review", definition, false)),
            false,
            HASH
        );
    }
}
