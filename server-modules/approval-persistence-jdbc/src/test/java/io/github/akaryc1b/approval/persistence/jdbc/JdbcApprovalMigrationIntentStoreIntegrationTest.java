package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.MigrationProtocolConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.INTENT_ID;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.OTHER_TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.PLAN_ID;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialIntentEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.intent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.intentEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationIntentStoreIntegrationTest
    extends AbstractJdbcApprovalMigrationProtocolIntegrationTest {

    @Test
    void intentCreationIsIdempotentTenantScopedAndPayloadConflictFailsClosed() {
        ApprovalMigrationIntent value = intent();
        ApprovalMigrationIntentEvent initial = initialIntentEvent(value, "initial-intent");

        assertFalse(store.createIntent(value, initial).replayedExistingRequest());
        assertTrue(store.createIntent(value, initial).replayedExistingRequest());
        assertTrue(store.findIntent(OTHER_TENANT, INTENT_ID).isEmpty());
        assertEquals(1, store.findIntentEvents(TENANT, INTENT_ID).size());

        ApprovalMigrationIntent conflicting = intent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            value.idempotencyKey(),
            hash('9')
        );
        assertThrows(
            MigrationProtocolConflictException.class,
            () -> store.createIntent(conflicting, initialIntentEvent(conflicting, "conflict"))
        );
        assertEquals(1, count("ap_process_migration_intent"));
        assertEquals(1, count("ap_process_migration_intent_event"));
    }

    @Test
    void intentTransitionUsesRevisionCasAndAtomicEventAppend() {
        ApprovalMigrationIntent pending = intent(INTENT_ID, PLAN_ID, "intent-key-one", hash('d'));
        store.createIntent(pending, initialIntentEvent(pending, "initial-intent"));
        ApprovalMigrationIntent running = pending.transitioned(IntentStatus.RUNNING, NOW.plusSeconds(1));
        ApprovalMigrationIntentEvent event = intentEvent(running, IntentStatus.PENDING, "running");

        assertEquals(
            IntentStatus.RUNNING,
            store.transitionIntent(running, pending.revision(), event).status()
        );
        assertThrows(
            MigrationProtocolConflictException.class,
            () -> store.transitionIntent(running, pending.revision(), event)
        );
        assertEquals(2, store.findIntentEvents(TENANT, INTENT_ID).size());
        assertEquals(2, store.findIntent(TENANT, INTENT_ID).orElseThrow().revision());
    }
}
