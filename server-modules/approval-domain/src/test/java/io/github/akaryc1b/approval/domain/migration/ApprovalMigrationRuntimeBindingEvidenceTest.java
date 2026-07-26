package io.github.akaryc1b.approval.domain.migration;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalMigrationRuntimeBindingEvidenceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T16:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    void releasesOnlyTheCurrentFenceOwnerBeforeExactExpiry() {
        ApprovalMigrationCommandFence active = activeFence();

        ApprovalMigrationCommandFence released = active.released("worker-d5", NOW.plusSeconds(30));

        assertEquals(ApprovalMigrationCommandFence.FenceStatus.RELEASED, released.status());
        assertEquals(2, released.revision());
        assertEquals(released.updatedAt(), released.releasedAt());
        assertEquals(active.leaseUntil(), released.leaseUntil());
        assertThrows(
            IllegalArgumentException.class,
            () -> active.released("stale-worker", NOW.plusSeconds(30))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> active.released("worker-d5", active.leaseUntil())
        );
    }

    @Test
    void initialAndMigratedBindingEvidenceHaveClosedLineage() {
        ApprovalMigrationRuntimeBindingEvidence initial = new ApprovalMigrationRuntimeBindingEvidence(
            UUID.fromString("52000000-0000-0000-0000-000000000001"),
            "tenant-d5",
            UUID.fromString("52000000-0000-0000-0000-000000000002"),
            1,
            null,
            null,
            null,
            HASH_A,
            "approval",
            1,
            HASH_A,
            "deployment-v1",
            "definition-v1",
            1,
            HASH_B,
            NOW,
            "request-d5",
            "trace-d5"
        );
        assertNull(initial.attemptId());

        ApprovalMigrationRuntimeBindingEvidence migrated = new ApprovalMigrationRuntimeBindingEvidence(
            UUID.fromString("52000000-0000-0000-0000-000000000003"),
            "tenant-d5",
            initial.approvalInstanceId(),
            2,
            UUID.fromString("52000000-0000-0000-0000-000000000004"),
            UUID.fromString("52000000-0000-0000-0000-000000000005"),
            initial.bindingEvidenceHash(),
            HASH_B,
            "approval",
            2,
            HASH_B,
            "deployment-v2",
            "definition-v2",
            2,
            HASH_A,
            NOW.plusSeconds(30),
            "request-d5-complete",
            "trace-d5"
        );
        assertEquals(initial.bindingEvidenceHash(), migrated.previousBindingEvidenceHash());

        assertThrows(
            IllegalArgumentException.class,
            () -> new ApprovalMigrationRuntimeBindingEvidence(
                UUID.randomUUID(),
                "tenant-d5",
                initial.approvalInstanceId(),
                2,
                null,
                null,
                null,
                HASH_B,
                "approval",
                2,
                HASH_B,
                "deployment-v2",
                "definition-v2",
                2,
                HASH_A,
                NOW,
                "request-invalid",
                null
            )
        );
    }

    @Test
    void completionEvidenceRejectsAFalseNoOpMigration() {
        UUID instanceId = UUID.fromString("52000000-0000-0000-0000-000000000010");
        assertThrows(
            IllegalArgumentException.class,
            () -> new ApprovalMigrationInstanceCompletionEvidence(
                UUID.randomUUID(),
                "tenant-d5",
                UUID.randomUUID(),
                UUID.randomUUID(),
                instanceId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                2,
                4,
                1,
                HASH_A,
                HASH_A,
                HASH_A,
                1,
                HASH_A,
                "definition-v1",
                1,
                HASH_A,
                "deployment-v1",
                "definition-v1",
                HASH_A,
                HASH_B,
                NOW,
                "request-d5",
                null
            )
        );
    }

    private static ApprovalMigrationCommandFence activeFence() {
        return new ApprovalMigrationCommandFence(
            UUID.fromString("52000000-0000-0000-0000-000000000020"),
            "tenant-d5",
            UUID.fromString("52000000-0000-0000-0000-000000000021"),
            UUID.fromString("52000000-0000-0000-0000-000000000022"),
            ApprovalCommandOperation.MIGRATION,
            ApprovalMigrationCommandFence.FenceStatus.ACTIVE,
            1,
            "worker-d5",
            NOW.plusSeconds(60),
            "fence-d5",
            HASH_A,
            NOW,
            NOW,
            null,
            "request-d5",
            "trace-d5"
        );
    }
}
