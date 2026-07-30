package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.KillSwitchObservation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalMigrationOrchestrationEvidenceTest {

    @Test
    void rejectsForgedKillSwitchDecision() {
        assertThrows(IllegalArgumentException.class, () -> new KillSwitchObservation(
            UUID.randomUUID(),
            "tenant-a",
            UUID.randomUUID(),
            UUID.randomUUID(),
            3,
            3,
            true,
            true,
            "DISPATCH_ALLOWED",
            "a".repeat(64),
            "b".repeat(64),
            Instant.parse("2026-07-27T00:00:00Z"),
            "request-1",
            null
        ));
    }

    @Test
    void rejectsNonCanonicalCanarySequence() {
        assertThrows(IllegalArgumentException.class, () ->
            new ApprovalMigrationOrchestrationEvidence.CanarySelection(
                UUID.randomUUID(),
                "tenant-a",
                UUID.randomUUID(),
                UUID.randomUUID(),
                ApprovalMigrationOrchestrationEvidence.CANARY_ALGORITHM_VERSION,
                2,
                UUID.randomUUID(),
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                Instant.parse("2026-07-27T00:00:00Z"),
                "request-1",
                null
            )
        );
    }
}
