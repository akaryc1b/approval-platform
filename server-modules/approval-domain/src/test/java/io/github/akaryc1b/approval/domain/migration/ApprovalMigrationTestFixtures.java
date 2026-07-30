package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;

import java.time.Instant;
import java.util.UUID;

final class ApprovalMigrationTestFixtures {

    static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");
    static final String TENANT = "tenant-migration";

    private ApprovalMigrationTestFixtures() {
    }

    static ApprovalMigrationIntent intent() {
        return new ApprovalMigrationIntent(
            id(1), TENANT, id(2), hash('a'), "purchasePayment",
            1, hash('b'), 2, hash('c'), 1,
            IntentStatus.PENDING, 1, "intent-idempotency", hash('d'),
            "operator-one", "Migrate reviewed running instance",
            NOW.plusSeconds(600), NOW, NOW,
            "request-intent", "trace-intent", "audit:intent"
        );
    }

    static ApprovalMigrationAttempt attempt() {
        return new ApprovalMigrationAttempt(
            id(3), TENANT, id(1), id(4), "engine-instance-one",
            1, null, hash('e'), "source-definition", "target-definition",
            AttemptStatus.PENDING, EngineOutcome.NOT_REQUESTED, 1,
            null, null, null, FailureClass.NONE, null,
            NOW, NOW, "request-attempt", "trace-attempt"
        );
    }

    static UUID id(long value) {
        return new UUID(0, value);
    }

    static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
