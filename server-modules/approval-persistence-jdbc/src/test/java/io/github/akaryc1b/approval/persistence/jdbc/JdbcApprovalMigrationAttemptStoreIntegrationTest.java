package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.MigrationProtocolConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptTransition;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.attempt;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.attemptEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialAttemptEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialIntentEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.intent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationAttemptStoreIntegrationTest
    extends AbstractJdbcApprovalMigrationProtocolIntegrationTest {

    @Test
    void attemptTransitionsUseCasExclusiveOwnershipAndRetryLineage() {
        ApprovalMigrationIntent owner = intent();
        store.createIntent(owner, initialIntentEvent(owner, "owner-intent"));
        ApprovalMigrationAttempt pending = attempt();
        ApprovalMigrationAttemptEvent initial = initialAttemptEvent(pending, "initial-attempt");
        assertFalse(store.createAttempt(pending, initial).replayedExistingRequest());
        assertTrue(store.createAttempt(pending, initial).replayedExistingRequest());

        ApprovalMigrationAttempt claimed = pending.transitioned(new ApprovalMigrationAttemptTransition(
            AttemptStatus.CLAIMED, EngineOutcome.NOT_REQUESTED,
            "worker-one", NOW.plusSeconds(120), null,
            FailureClass.NONE, null, NOW.plusSeconds(2)
        ));
        ApprovalMigrationAttemptEvent claimedEvent = attemptEvent(
            claimed,
            AttemptStatus.PENDING,
            "claimed"
        );
        assertEquals(
            AttemptStatus.CLAIMED,
            store.transitionAttempt(claimed, pending.revision(), claimedEvent).status()
        );
        assertThrows(
            MigrationProtocolConflictException.class,
            () -> store.transitionAttempt(claimed, pending.revision(), claimedEvent)
        );

        ApprovalMigrationIntent second = intent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ApprovalMigrationJdbcFixtures.hash('9'),
            "intent-key-two",
            ApprovalMigrationJdbcFixtures.hash('8')
        );
        store.createIntent(second, initialIntentEvent(second, "second-intent"));
        ApprovalMigrationAttempt competing = attempt(UUID.randomUUID(), second.intentId(), 1, null);
        assertThrows(
            MigrationProtocolConflictException.class,
            () -> store.createAttempt(competing, initialAttemptEvent(competing, "competing"))
        );

        ApprovalMigrationAttempt retryable = claimed.transitioned(new ApprovalMigrationAttemptTransition(
            AttemptStatus.FAILED_RETRYABLE, EngineOutcome.NOT_REQUESTED,
            null, null, null, FailureClass.INTERNAL,
            "Worker failed before engine invocation", NOW.plusSeconds(3)
        ));
        store.transitionAttempt(
            retryable,
            claimed.revision(),
            attemptEvent(retryable, AttemptStatus.CLAIMED, "retryable")
        );
        ApprovalMigrationAttempt retry = attempt(
            UUID.fromString("74000000-0000-0000-0000-000000000005"),
            owner.intentId(),
            2,
            pending.attemptId()
        );
        assertFalse(store.createAttempt(
            retry,
            initialAttemptEvent(retry, "retry")
        ).replayedExistingRequest());
        assertEquals(2, store.findAttempts(owner.tenantId(), owner.intentId()).size());
    }
}
