package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalMigrationPlanEventTest {

    @Test
    void consumedTransitionPreservesExactAuthorizationEvidence() {
        UUID authorizationId = UUID.fromString("79100000-0000-0000-0000-000000000001");
        ApprovalMigrationPlanEvent event = event(
            authorizationId,
            hash('a')
        );

        assertEquals(PlanStatus.AUTHORIZED, event.fromStatus());
        assertEquals(PlanStatus.CONSUMED, event.toStatus());
        assertEquals(authorizationId, event.authorizationId());
        assertEquals(hash('a'), event.authorizationEvidenceHash());
    }

    @Test
    void consumedTransitionRejectsMissingAuthorizationEvidence() {
        assertThrows(NullPointerException.class, () -> event(null, hash('a')));
        assertThrows(IllegalArgumentException.class, () -> event(
            UUID.fromString("79100000-0000-0000-0000-000000000001"),
            "invalid"
        ));
    }

    private static ApprovalMigrationPlanEvent event(
        UUID authorizationId,
        String authorizationEvidenceHash
    ) {
        return new ApprovalMigrationPlanEvent(
            UUID.fromString("79100000-0000-0000-0000-000000000002"),
            "tenant-migration-plan",
            UUID.fromString("79100000-0000-0000-0000-000000000003"),
            hash('1'),
            3,
            PlanStatus.AUTHORIZED,
            PlanStatus.CONSUMED,
            "migration-executor",
            "Consume exact authorized migration plan",
            authorizationId,
            authorizationEvidenceHash,
            Instant.parse("2026-07-25T16:00:00Z"),
            "request-plan-consumption",
            "trace-plan-consumption",
            "audit-plan-consumption"
        );
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
