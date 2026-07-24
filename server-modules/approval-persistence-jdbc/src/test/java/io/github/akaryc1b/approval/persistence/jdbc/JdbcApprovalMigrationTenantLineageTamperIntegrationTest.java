package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.MigrationProtocolConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptTransition;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.ReconciliationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.UUID;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.INSTANCE_ID;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.OTHER_TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.attemptEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialAttemptEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialIntentEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.intentEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationTenantLineageTamperIntegrationTest
    extends AbstractJdbcApprovalMigrationProtocolIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void sameStableUuidsRemainIndependentAcrossTenantsAndReadsNeverCrossTenant() {
        UUID intentId = UUID.fromString("75000000-0000-0000-0000-000000000001");
        UUID planId = UUID.fromString("75000000-0000-0000-0000-000000000002");
        UUID attemptId = UUID.fromString("75000000-0000-0000-0000-000000000003");
        UUID verificationId = UUID.fromString("75000000-0000-0000-0000-000000000004");
        UUID reconciliationId = UUID.fromString("75000000-0000-0000-0000-000000000005");

        ApprovalMigrationIntent tenantA = intentForTenant(TENANT, intentId, planId, "shared-key");
        ApprovalMigrationIntent tenantB = intentForTenant(OTHER_TENANT, intentId, planId, "shared-key");
        store.createIntent(tenantA, initialIntentEvent(tenantA, "tenant-a-intent"));
        store.createIntent(tenantB, initialIntentEvent(tenantB, "tenant-b-intent"));

        ApprovalMigrationAttempt attemptA = attemptForTenant(
            TENANT, attemptId, intentId, INSTANCE_ID, 1, null
        );
        ApprovalMigrationAttempt attemptB = attemptForTenant(
            OTHER_TENANT, attemptId, intentId, INSTANCE_ID, 1, null
        );
        store.createAttempt(attemptA, initialAttemptEvent(attemptA, "tenant-a-attempt"));
        store.createAttempt(attemptB, initialAttemptEvent(attemptB, "tenant-b-attempt"));

        ApprovalMigrationVerification verificationA = verificationForTenant(
            TENANT, verificationId, tenantA, attemptA, 1, hash('1')
        );
        ApprovalMigrationVerification verificationB = verificationForTenant(
            OTHER_TENANT, verificationId, tenantB, attemptB, 1, hash('1')
        );
        store.appendVerification(verificationA);
        store.appendVerification(verificationB);

        ApprovalMigrationReconciliation reconciliationA = reconciliationForTenant(
            TENANT, reconciliationId, tenantA, attemptA, 1, ReconciliationStatus.OPEN, hash('2')
        );
        ApprovalMigrationReconciliation reconciliationB = reconciliationForTenant(
            OTHER_TENANT, reconciliationId, tenantB, attemptB, 1, ReconciliationStatus.OPEN, hash('2')
        );
        store.appendReconciliation(reconciliationA);
        store.appendReconciliation(reconciliationB);

        assertEquals(TENANT, store.findIntent(TENANT, intentId).orElseThrow().tenantId());
        assertEquals(OTHER_TENANT, store.findIntent(OTHER_TENANT, intentId).orElseThrow().tenantId());
        assertEquals(TENANT, store.findAttempt(TENANT, attemptId).orElseThrow().tenantId());
        assertEquals(OTHER_TENANT, store.findAttempt(OTHER_TENANT, attemptId).orElseThrow().tenantId());
        assertEquals(TENANT, store.findVerifications(TENANT, attemptId).getFirst().tenantId());
        assertEquals(OTHER_TENANT, store.findVerifications(OTHER_TENANT, attemptId).getFirst().tenantId());
        assertEquals(TENANT, store.findReconciliations(TENANT, attemptId).getFirst().tenantId());
        assertEquals(OTHER_TENANT, store.findReconciliations(OTHER_TENANT, attemptId).getFirst().tenantId());
        assertEquals(2, count("ap_process_migration_intent"));
        assertEquals(2, count("ap_process_migration_attempt"));
        assertEquals(2, count("ap_process_migration_verification"));
        assertEquals(2, count("ap_process_migration_reconciliation"));
    }

    @Test
    void crossTenantIntentAttemptEvidenceAndParentBindingsFailClosed() {
        ApprovalMigrationIntent tenantB = intentForTenant(
            OTHER_TENANT,
            UUID.fromString("75100000-0000-0000-0000-000000000001"),
            UUID.fromString("75100000-0000-0000-0000-000000000002"),
            "tenant-b-only"
        );
        store.createIntent(tenantB, initialIntentEvent(tenantB, "tenant-b-only"));
        ApprovalMigrationAttempt attemptB = attemptForTenant(
            OTHER_TENANT,
            UUID.fromString("75100000-0000-0000-0000-000000000003"),
            tenantB.intentId(), INSTANCE_ID, 1, null
        );
        store.createAttempt(attemptB, initialAttemptEvent(attemptB, "tenant-b-attempt"));

        ApprovalMigrationAttempt crossIntent = attemptForTenant(
            TENANT,
            UUID.fromString("75100000-0000-0000-0000-000000000004"),
            tenantB.intentId(), INSTANCE_ID, 1, null
        );
        assertThrows(
            MigrationProtocolConflictException.class,
            () -> store.createAttempt(crossIntent, initialAttemptEvent(crossIntent, "cross-intent"))
        );

        ApprovalMigrationAttempt crossParent = attemptForTenant(
            TENANT,
            UUID.fromString("75100000-0000-0000-0000-000000000005"),
            tenantB.intentId(), INSTANCE_ID, 2, attemptB.attemptId()
        );
        assertThrows(
            MigrationProtocolConflictException.class,
            () -> store.createAttempt(crossParent, initialAttemptEvent(crossParent, "cross-parent"))
        );

        ApprovalMigrationVerification crossEvidence = verificationForTenant(
            TENANT,
            UUID.fromString("75100000-0000-0000-0000-000000000006"),
            tenantB, attemptB, 1, hash('3')
        );
        assertThrows(
            MigrationProtocolConflictException.class,
            () -> store.appendVerification(crossEvidence)
        );
        assertEquals(1, count("ap_process_migration_intent"));
        assertEquals(1, count("ap_process_migration_attempt"));
        assertEquals(0, count("ap_process_migration_verification"));
    }

    @Test
    void retryLineageRequiresImmediateRetryableParentInSameIntentAndInstance() {
        ApprovalMigrationIntent owner = intentForTenant(
            TENANT,
            UUID.fromString("75200000-0000-0000-0000-000000000001"),
            UUID.fromString("75200000-0000-0000-0000-000000000002"),
            "lineage-owner"
        );
        ApprovalMigrationIntent other = intentForTenant(
            TENANT,
            UUID.fromString("75200000-0000-0000-0000-000000000003"),
            UUID.fromString("75200000-0000-0000-0000-000000000004"),
            "lineage-other"
        );
        store.createIntent(owner, initialIntentEvent(owner, "lineage-owner"));
        store.createIntent(other, initialIntentEvent(other, "lineage-other"));
        ApprovalMigrationAttempt parent = attemptForTenant(
            TENANT,
            UUID.fromString("75200000-0000-0000-0000-000000000005"),
            owner.intentId(), INSTANCE_ID, 1, null
        );
        store.createAttempt(parent, initialAttemptEvent(parent, "lineage-parent"));

        ApprovalMigrationAttempt parentNotRetryable = attemptForTenant(
            TENANT,
            UUID.fromString("75200000-0000-0000-0000-000000000006"),
            owner.intentId(), INSTANCE_ID, 2, parent.attemptId()
        );
        assertThrows(DataAccessException.class, () -> insertAttemptDirect(parentNotRetryable));

        ApprovalMigrationAttempt retryable = makeRetryable(parent);
        ApprovalMigrationAttempt wrongIntent = attemptForTenant(
            TENANT,
            UUID.fromString("75200000-0000-0000-0000-000000000007"),
            other.intentId(), INSTANCE_ID, 2, retryable.attemptId()
        );
        ApprovalMigrationAttempt wrongInstance = attemptForTenant(
            TENANT,
            UUID.fromString("75200000-0000-0000-0000-000000000008"),
            owner.intentId(), UUID.randomUUID(), 2, retryable.attemptId()
        );
        ApprovalMigrationAttempt skippedNumber = attemptForTenant(
            TENANT,
            UUID.fromString("75200000-0000-0000-0000-000000000009"),
            owner.intentId(), INSTANCE_ID, 3, retryable.attemptId()
        );
        assertThrows(DataAccessException.class, () -> insertAttemptDirect(wrongIntent));
        assertThrows(DataAccessException.class, () -> insertAttemptDirect(wrongInstance));
        assertThrows(DataAccessException.class, () -> insertAttemptDirect(skippedNumber));
        assertThrows(DataAccessException.class, () -> insertFakeFirstAttempt(retryable));

        ApprovalMigrationAttempt validRetry = attemptForTenant(
            TENANT,
            UUID.fromString("75200000-0000-0000-0000-000000000010"),
            owner.intentId(), INSTANCE_ID, 2, retryable.attemptId()
        );
        assertFalse(store.createAttempt(
            validRetry,
            initialAttemptEvent(validRetry, "valid-retry")
        ).replayedExistingRequest());
        assertEquals(2, store.findAttempts(TENANT, owner.intentId()).size());
    }

    @Test
    void currentRowsRequireMatchingEventsAndCannotBeCreatedOrAdvancedAlone() {
        ApprovalMigrationIntent orphanIntent = intentForTenant(
            TENANT,
            UUID.fromString("75300000-0000-0000-0000-000000000001"),
            UUID.fromString("75300000-0000-0000-0000-000000000002"),
            "orphan-intent"
        );
        assertThrows(DataAccessException.class, () -> insertIntentDirect(orphanIntent));

        ApprovalMigrationIntent intent = intentForTenant(
            TENANT,
            UUID.fromString("75300000-0000-0000-0000-000000000003"),
            UUID.fromString("75300000-0000-0000-0000-000000000004"),
            "event-required"
        );
        store.createIntent(intent, initialIntentEvent(intent, "event-required"));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            update ap_process_migration_intent
             set status='RUNNING',revision=2,updated_at=updated_at+interval '1 second',
              payload_json=jsonb_set(jsonb_set(payload_json,'{status}',to_jsonb('RUNNING'::text)),
                '{revision}',to_jsonb(2::bigint))
             where tenant_id=? and intent_id=?
            """, TENANT, intent.intentId()));

        ApprovalMigrationAttempt orphanAttempt = attemptForTenant(
            TENANT,
            UUID.fromString("75300000-0000-0000-0000-000000000005"),
            intent.intentId(), INSTANCE_ID, 1, null
        );
        assertThrows(DataAccessException.class, () -> insertAttemptDirect(orphanAttempt));
        assertEquals(1, count("ap_process_migration_intent"));
        assertEquals(1, count("ap_process_migration_intent_event"));
        assertEquals(0, count("ap_process_migration_attempt"));
    }

    @Test
    void directStableIdentityPayloadHashRevisionAndDeletionTamperingFailsClosed() {
        ApprovalMigrationIntent intent = intentForTenant(
            TENANT,
            UUID.fromString("75400000-0000-0000-0000-000000000001"),
            UUID.fromString("75400000-0000-0000-0000-000000000002"),
            "tamper-intent"
        );
        store.createIntent(intent, initialIntentEvent(intent, "tamper-intent"));
        ApprovalMigrationAttempt attempt = attemptForTenant(
            TENANT,
            UUID.fromString("75400000-0000-0000-0000-000000000003"),
            intent.intentId(), INSTANCE_ID, 1, null
        );
        store.createAttempt(attempt, initialAttemptEvent(attempt, "tamper-attempt"));

        assertThrows(DataAccessException.class, () -> jdbc.update("""
            update ap_process_migration_intent set plan_hash=?,
             payload_json=jsonb_set(payload_json,'{planHash}',to_jsonb(?::text)),revision=2,status='RUNNING'
             where tenant_id=? and intent_id=?
            """, hash('8'), hash('8'), TENANT, intent.intentId()));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            update ap_process_migration_intent
             set status='RUNNING',revision=2,updated_at=updated_at+interval '1 second',
              payload_json=jsonb_set(jsonb_set(jsonb_set(payload_json,'{status}',to_jsonb('RUNNING'::text)),
                '{revision}',to_jsonb(2::bigint)),'{requestedBy}',to_jsonb('attacker'::text))
             where tenant_id=? and intent_id=?
            """, TENANT, intent.intentId()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "update ap_process_migration_intent set revision=revision+2,status='RUNNING' where tenant_id=? and intent_id=?",
            TENANT, intent.intentId()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            update ap_process_migration_attempt set expected_binding_evidence_hash=?,
             payload_json=jsonb_set(payload_json,'{expectedBindingEvidenceHash}',to_jsonb(?::text)),
             revision=2,status='CLAIMED',lease_owner='attacker',lease_until=now()+interval '1 minute'
             where tenant_id=? and attempt_id=?
            """, hash('9'), hash('9'), TENANT, attempt.attemptId()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_intent where tenant_id=? and intent_id=?",
            TENANT, intent.intentId()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_attempt where tenant_id=? and attempt_id=?",
            TENANT, attempt.attemptId()
        ));
        assertTrue(store.findIntent(TENANT, intent.intentId()).isPresent());
        assertTrue(store.findAttempt(TENANT, attempt.attemptId()).isPresent());
    }

    @Test
    void appendOnlyEvidenceRejectsPayloadHashSequenceUpdateAndDeleteTampering() {
        ApprovalMigrationIntent intent = intentForTenant(
            TENANT,
            UUID.fromString("75500000-0000-0000-0000-000000000001"),
            UUID.fromString("75500000-0000-0000-0000-000000000002"),
            "append-only-intent"
        );
        store.createIntent(intent, initialIntentEvent(intent, "append-only-intent"));
        ApprovalMigrationAttempt attempt = attemptForTenant(
            TENANT,
            UUID.fromString("75500000-0000-0000-0000-000000000003"),
            intent.intentId(), INSTANCE_ID, 1, null
        );
        store.createAttempt(attempt, initialAttemptEvent(attempt, "append-only-attempt"));
        ApprovalMigrationVerification verification = verificationForTenant(
            TENANT,
            UUID.fromString("75500000-0000-0000-0000-000000000004"),
            intent, attempt, 1, hash('4')
        );
        ApprovalMigrationReconciliation reconciliation = reconciliationForTenant(
            TENANT,
            UUID.fromString("75500000-0000-0000-0000-000000000005"),
            intent, attempt, 1, ReconciliationStatus.OPEN, hash('5')
        );
        store.appendVerification(verification);
        store.appendReconciliation(reconciliation);

        assertThrows(DataAccessException.class, () -> jdbc.update(
            "update ap_process_migration_intent_event set payload_json='{}'::jsonb where tenant_id=? and intent_id=?",
            TENANT, intent.intentId()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_attempt_event where tenant_id=? and attempt_id=?",
            TENANT, attempt.attemptId()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "update ap_process_migration_verification set payload_json='{}'::jsonb,evidence_hash=? where tenant_id=? and verification_id=?",
            hash('6'), TENANT, verification.verificationId()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_reconciliation where tenant_id=? and reconciliation_id=?",
            TENANT, reconciliation.reconciliationId()
        ));
        ApprovalMigrationVerification gap = verificationForTenant(
            TENANT, UUID.randomUUID(), intent, attempt, 3, hash('7')
        );
        assertThrows(MigrationProtocolConflictException.class, () -> store.appendVerification(gap));
        assertEquals(1, store.findVerifications(TENANT, attempt.attemptId()).size());
        assertEquals(1, store.findReconciliations(TENANT, attempt.attemptId()).size());
    }

    @Test
    void terminalIntentAttemptAndReconciliationCannotReturnToActiveProgression() {
        ApprovalMigrationIntent intent = intentForTenant(
            TENANT,
            UUID.fromString("75600000-0000-0000-0000-000000000001"),
            UUID.fromString("75600000-0000-0000-0000-000000000002"),
            "terminal-intent"
        );
        store.createIntent(intent, initialIntentEvent(intent, "terminal-intent"));
        ApprovalMigrationIntent cancelled = intent.transitioned(IntentStatus.CANCELLED, NOW.plusSeconds(1));
        store.transitionIntent(
            cancelled, intent.revision(), intentEvent(cancelled, IntentStatus.PENDING, "cancelled")
        );
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            update ap_process_migration_intent set status='RUNNING',revision=3,
             payload_json=jsonb_set(jsonb_set(payload_json,'{status}',to_jsonb('RUNNING'::text)),
               '{revision}',to_jsonb(3::bigint)),updated_at=updated_at+interval '1 second'
             where tenant_id=? and intent_id=?
            """, TENANT, intent.intentId()));

        ApprovalMigrationIntent attemptIntent = intentForTenant(
            TENANT,
            UUID.fromString("75600000-0000-0000-0000-000000000003"),
            UUID.fromString("75600000-0000-0000-0000-000000000004"),
            "terminal-attempt-intent"
        );
        store.createIntent(attemptIntent, initialIntentEvent(attemptIntent, "attempt-intent"));
        ApprovalMigrationAttempt pending = attemptForTenant(
            TENANT,
            UUID.fromString("75600000-0000-0000-0000-000000000005"),
            attemptIntent.intentId(), INSTANCE_ID, 1, null
        );
        store.createAttempt(pending, initialAttemptEvent(pending, "terminal-attempt"));
        ApprovalMigrationAttempt claimed = pending.transitioned(new ApprovalMigrationAttemptTransition(
            AttemptStatus.CLAIMED, EngineOutcome.NOT_REQUESTED, "worker-terminal",
            NOW.plusSeconds(120), null, FailureClass.NONE, null, NOW.plusSeconds(2)
        ));
        store.transitionAttempt(
            claimed, pending.revision(), attemptEvent(claimed, AttemptStatus.PENDING, "claimed-terminal")
        );
        ApprovalMigrationAttempt failed = claimed.transitioned(new ApprovalMigrationAttemptTransition(
            AttemptStatus.FAILED_TERMINAL, EngineOutcome.REJECTED, null, null, null,
            FailureClass.ENGINE_REJECTED, "Migration was rejected", NOW.plusSeconds(3)
        ));
        store.transitionAttempt(
            failed, claimed.revision(), attemptEvent(failed, AttemptStatus.CLAIMED, "failed-terminal")
        );
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            update ap_process_migration_attempt set status='CLAIMED',revision=4,
             engine_outcome='NOT_REQUESTED',lease_owner='stale-worker',lease_until=now()+interval '1 minute',
             payload_json=jsonb_set(jsonb_set(payload_json,'{status}',to_jsonb('CLAIMED'::text)),
               '{revision}',to_jsonb(4::bigint)),updated_at=updated_at+interval '1 second'
             where tenant_id=? and attempt_id=?
            """, TENANT, failed.attemptId()));

        ApprovalMigrationReconciliation open = reconciliationForTenant(
            TENANT, UUID.randomUUID(), attemptIntent, failed, 1, ReconciliationStatus.OPEN, hash('8')
        );
        ApprovalMigrationReconciliation terminal = reconciliationForTenant(
            TENANT, UUID.randomUUID(), attemptIntent, failed, 2,
            ReconciliationStatus.RESOLVED_TERMINAL, hash('9')
        );
        store.appendReconciliation(open);
        store.appendReconciliation(terminal);
        ApprovalMigrationReconciliation afterTerminal = reconciliationForTenant(
            TENANT, UUID.randomUUID(), attemptIntent, failed, 3,
            ReconciliationStatus.OPEN, hash('a')
        );
        assertThrows(
            MigrationProtocolConflictException.class,
            () -> store.appendReconciliation(afterTerminal)
        );
        assertEquals(2, store.findReconciliations(TENANT, failed.attemptId()).size());
    }

    @Test
    void changedEvidenceReplayConflictsInsteadOfReturningStoredObjects() {
        ApprovalMigrationIntent intent = intentForTenant(
            TENANT,
            UUID.fromString("75700000-0000-0000-0000-000000000001"),
            UUID.fromString("75700000-0000-0000-0000-000000000002"),
            "changed-replay-intent"
        );
        store.createIntent(intent, initialIntentEvent(intent, "changed-replay-intent"));
        ApprovalMigrationAttempt attempt = attemptForTenant(
            TENANT,
            UUID.fromString("75700000-0000-0000-0000-000000000003"),
            intent.intentId(), INSTANCE_ID, 1, null
        );
        store.createAttempt(attempt, initialAttemptEvent(attempt, "changed-replay-attempt"));
        UUID verificationId = UUID.fromString("75700000-0000-0000-0000-000000000004");
        ApprovalMigrationVerification first = verificationForTenant(
            TENANT, verificationId, intent, attempt, 1, hash('b')
        );
        ApprovalMigrationVerification changed = verificationForTenant(
            TENANT, verificationId, intent, attempt, 1, hash('c')
        );
        store.appendVerification(first);
        assertThrows(MigrationProtocolConflictException.class, () -> store.appendVerification(changed));

        UUID reconciliationId = UUID.fromString("75700000-0000-0000-0000-000000000005");
        ApprovalMigrationReconciliation open = reconciliationForTenant(
            TENANT, reconciliationId, intent, attempt, 1, ReconciliationStatus.OPEN, hash('d')
        );
        ApprovalMigrationReconciliation changedOpen = reconciliationForTenant(
            TENANT, reconciliationId, intent, attempt, 1, ReconciliationStatus.OPEN, hash('e')
        );
        store.appendReconciliation(open);
        assertThrows(
            MigrationProtocolConflictException.class,
            () -> store.appendReconciliation(changedOpen)
        );
        assertEquals(first, store.findVerifications(TENANT, attempt.attemptId()).getFirst());
        assertEquals(open, store.findReconciliations(TENANT, attempt.attemptId()).getFirst());
    }

    private ApprovalMigrationAttempt makeRetryable(ApprovalMigrationAttempt pending) {
        ApprovalMigrationAttempt claimed = pending.transitioned(new ApprovalMigrationAttemptTransition(
            AttemptStatus.CLAIMED, EngineOutcome.NOT_REQUESTED, "worker-lineage",
            NOW.plusSeconds(120), null, FailureClass.NONE, null, NOW.plusSeconds(2)
        ));
        store.transitionAttempt(
            claimed, pending.revision(), attemptEvent(claimed, AttemptStatus.PENDING, "lineage-claimed")
        );
        ApprovalMigrationAttempt retryable = claimed.transitioned(new ApprovalMigrationAttemptTransition(
            AttemptStatus.FAILED_RETRYABLE, EngineOutcome.NOT_REQUESTED, null, null, null,
            FailureClass.INTERNAL, "Worker failed before engine request", NOW.plusSeconds(3)
        ));
        return store.transitionAttempt(
            retryable, claimed.revision(),
            attemptEvent(retryable, AttemptStatus.CLAIMED, "lineage-retryable")
        );
    }

    private ApprovalMigrationIntent intentForTenant(
        String tenantId,
        UUID intentId,
        UUID planId,
        String key
    ) {
        char planHashSeed = Character.forDigit(
            (int) (intentId.getLeastSignificantBits() & 15),
            16
        );
        return new ApprovalMigrationIntent(
            intentId, tenantId, planId, hash(planHashSeed), ApprovalMigrationJdbcFixtures.DEFINITION_KEY,
            1, hash('b'), 2, hash('c'), 1, IntentStatus.PENDING, 1, key, hash('d'),
            "migration-requester", "M5-B3 tenant and tamper evidence", NOW.plusSeconds(900),
            NOW, NOW, "request-" + key, "trace-m5-b3", "audit-" + key
        );
    }

    private ApprovalMigrationAttempt attemptForTenant(
        String tenantId,
        UUID attemptId,
        UUID intentId,
        UUID instanceId,
        int number,
        UUID parentId
    ) {
        return new ApprovalMigrationAttempt(
            attemptId, tenantId, intentId, instanceId, "engine-instance-m5-b3",
            number, parentId, hash('e'), "source-definition", "target-definition",
            AttemptStatus.PENDING, EngineOutcome.NOT_REQUESTED, 1,
            null, null, null, FailureClass.NONE, null,
            NOW.plusSeconds(number), NOW.plusSeconds(number),
            "request-m5-b3-" + attemptId, "trace-m5-b3"
        );
    }

    private ApprovalMigrationVerification verificationForTenant(
        String tenantId,
        UUID verificationId,
        ApprovalMigrationIntent intent,
        ApprovalMigrationAttempt attempt,
        int sequence,
        String evidenceHash
    ) {
        ApprovalMigrationVerification base = ApprovalMigrationJdbcFixtures.verification(
            intent, attempt, sequence
        );
        return new ApprovalMigrationVerification(
            verificationId, tenantId, intent.intentId(), attempt.attemptId(), sequence,
            base.expectedBindingEvidenceHash(), base.observedBindingEvidenceHash(),
            base.sourceEngineDefinitionId(), base.targetEngineDefinitionId(),
            base.observedEngineDefinitionId(), base.expectedActiveTaskKeys(),
            base.observedActiveTaskKeys(), base.runtimePresent(), base.historyPresent(),
            base.outcome(), evidenceHash, base.recordedAt(), base.requestId(), base.traceId()
        );
    }

    private ApprovalMigrationReconciliation reconciliationForTenant(
        String tenantId,
        UUID reconciliationId,
        ApprovalMigrationIntent intent,
        ApprovalMigrationAttempt attempt,
        int sequence,
        ReconciliationStatus status,
        String evidenceHash
    ) {
        boolean terminal = status != ReconciliationStatus.OPEN
            && status != ReconciliationStatus.MANUAL_REVIEW_REQUIRED;
        return new ApprovalMigrationReconciliation(
            reconciliationId, tenantId, intent.intentId(), attempt.attemptId(), sequence,
            status, FailureClass.RECONCILIATION_REQUIRED,
            "M5-B3 reconciliation evidence", evidenceHash,
            terminal ? hash((char) ('f' - sequence)) : null,
            terminal ? "migration-reviewer" : null,
            NOW.plusSeconds(40 + sequence),
            terminal ? NOW.plusSeconds(50 + sequence) : null,
            "request-reconciliation-" + sequence, "trace-m5-b3",
            "audit-reconciliation-" + sequence
        );
    }

    private void insertIntentDirect(ApprovalMigrationIntent value) {
        jdbc.update("""
            insert into ap_process_migration_intent (
              tenant_id,intent_id,idempotency_key,plan_id,plan_hash,definition_key,
              source_release_version,source_package_hash,target_release_version,target_package_hash,
              status,revision,intent_evidence_hash,payload_json,created_at,updated_at
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),?,?)
            """,
            value.tenantId(), value.intentId(), value.idempotencyKey(), value.planId(),
            value.planHash(), value.definitionKey(), value.sourceReleaseVersion(),
            value.sourcePackageHash(), value.targetReleaseVersion(), value.targetPackageHash(),
            value.status().name(), value.revision(), value.intentEvidenceHash(), json(value),
            JdbcApprovalMigrationJson.offset(value.createdAt()),
            JdbcApprovalMigrationJson.offset(value.updatedAt())
        );
    }

    private void insertAttemptDirect(ApprovalMigrationAttempt value) {
        jdbc.update("""
            insert into ap_process_migration_attempt (
              tenant_id,attempt_id,intent_id,approval_instance_id,attempt_number,parent_attempt_id,
              status,revision,engine_outcome,lease_owner,lease_until,expected_binding_evidence_hash,
              payload_json,created_at,updated_at
            ) values (?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),?,?)
            """,
            value.tenantId(), value.attemptId(), value.intentId(), value.approvalInstanceId(),
            value.attemptNumber(), value.parentAttemptId(), value.status().name(), value.revision(),
            value.engineOutcome().name(), value.leaseOwner(),
            value.leaseUntil() == null ? null : JdbcApprovalMigrationJson.offset(value.leaseUntil()),
            value.expectedBindingEvidenceHash(), json(value),
            JdbcApprovalMigrationJson.offset(value.createdAt()),
            JdbcApprovalMigrationJson.offset(value.updatedAt())
        );
    }

    private void insertFakeFirstAttempt(ApprovalMigrationAttempt parent) {
        UUID attemptId = UUID.fromString("75200000-0000-0000-0000-000000000011");
        jdbc.update("""
            insert into ap_process_migration_attempt (
              tenant_id,attempt_id,intent_id,approval_instance_id,attempt_number,parent_attempt_id,
              status,revision,engine_outcome,lease_owner,lease_until,expected_binding_evidence_hash,
              payload_json,created_at,updated_at
            ) select tenant_id,?,intent_id,approval_instance_id,1,attempt_id,
              'PENDING',1,'NOT_REQUESTED',null,null,expected_binding_evidence_hash,
              jsonb_set(jsonb_set(jsonb_set(payload_json,'{attemptId}',to_jsonb(?::text)),
                '{attemptNumber}',to_jsonb(1)),'{parentAttemptId}',to_jsonb(attempt_id::text)),
              created_at+interval '10 seconds',updated_at+interval '10 seconds'
             from ap_process_migration_attempt where tenant_id=? and attempt_id=?
            """, attemptId, attemptId.toString(), TENANT, parent.attemptId());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize migration fixture", exception);
        }
    }
}
