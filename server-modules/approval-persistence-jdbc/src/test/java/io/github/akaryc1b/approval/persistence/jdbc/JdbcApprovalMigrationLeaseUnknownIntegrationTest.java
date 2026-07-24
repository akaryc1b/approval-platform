package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.MigrationProtocolConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptTransition;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.ReconciliationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.attempt;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.attemptEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialAttemptEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialIntentEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.intent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.reconciliation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationLeaseUnknownIntegrationTest
    extends AbstractJdbcApprovalMigrationProtocolIntegrationTest {

    @Test
    void currentOwnerClaimsAndRenewsLeaseWithDurableActorEvidence() {
        ApprovalMigrationAttempt pending = persistAttempt();
        ApprovalMigrationAttempt claimed = claim(pending, "worker-one", NOW.plusSeconds(2), NOW.plusSeconds(60));
        store.transitionAttempt(
            claimed, pending.revision(), "worker-one",
            attemptEvent(claimed, AttemptStatus.PENDING, "claim-worker-one")
        );
        ApprovalMigrationAttempt renewed = claim(
            claimed, "worker-one", NOW.plusSeconds(10), NOW.plusSeconds(120)
        );
        store.transitionAttempt(
            renewed, claimed.revision(), "worker-one",
            attemptEvent(renewed, AttemptStatus.CLAIMED, "renew-worker-one")
        );

        assertEquals("worker-one", store.findAttempt(TENANT, pending.attemptId()).orElseThrow().leaseOwner());
        assertEquals(Arrays.asList(null, "worker-one", "worker-one"), jdbc.queryForList("""
            select lease_actor from ap_process_migration_attempt_event
            where tenant_id=? and attempt_id=? order by revision
            """, String.class, TENANT, pending.attemptId()));
        assertEquals(NOW.plusSeconds(120), store.findAttemptEvents(TENANT, pending.attemptId())
            .getLast().leaseUntil());
    }

    @Test
    void staleOrDifferentOwnerCannotRenewOrAdvanceAnUnexpiredClaim() {
        ApprovalMigrationAttempt pending = persistAttempt();
        ApprovalMigrationAttempt claimed = claim(pending, "worker-one", NOW.plusSeconds(2), NOW.plusSeconds(60));
        store.transitionAttempt(
            claimed, pending.revision(), "worker-one",
            attemptEvent(claimed, AttemptStatus.PENDING, "claim-owner-fence")
        );
        ApprovalMigrationAttempt renewal = claim(
            claimed, "worker-one", NOW.plusSeconds(10), NOW.plusSeconds(90)
        );
        assertThrows(MigrationProtocolConflictException.class, () -> store.transitionAttempt(
            renewal, claimed.revision(), "worker-two",
            attemptEvent(renewal, AttemptStatus.CLAIMED, "wrong-renew-owner")
        ));
        ApprovalMigrationAttempt requested = requested(claimed, NOW.plusSeconds(11));
        assertThrows(MigrationProtocolConflictException.class, () -> store.transitionAttempt(
            requested, claimed.revision(), "worker-two",
            attemptEvent(requested, AttemptStatus.CLAIMED, "wrong-exit-owner")
        ));

        ApprovalMigrationAttempt current = store.findAttempt(TENANT, pending.attemptId()).orElseThrow();
        assertEquals(AttemptStatus.CLAIMED, current.status());
        assertEquals(2, current.revision());
    }

    @Test
    void expiredOwnerCannotAdvanceAndTakeoverFencesTheFormerOwner() {
        ApprovalMigrationAttempt pending = persistAttempt();
        ApprovalMigrationAttempt claimed = claim(pending, "worker-one", NOW.plusSeconds(2), NOW.plusSeconds(20));
        store.transitionAttempt(
            claimed, pending.revision(), "worker-one",
            attemptEvent(claimed, AttemptStatus.PENDING, "short-claim")
        );
        assertThrows(IllegalArgumentException.class, () -> requested(claimed, NOW.plusSeconds(20)));

        ApprovalMigrationAttempt takeover = claim(
            claimed, "worker-two", NOW.plusSeconds(20), NOW.plusSeconds(80)
        );
        store.transitionAttempt(
            takeover, claimed.revision(), "worker-two",
            attemptEvent(takeover, AttemptStatus.CLAIMED, "expired-takeover")
        );
        ApprovalMigrationAttempt renewed = claim(
            takeover, "worker-two", NOW.plusSeconds(30), NOW.plusSeconds(100)
        );
        assertThrows(MigrationProtocolConflictException.class, () -> store.transitionAttempt(
            renewed, takeover.revision(), "worker-one",
            attemptEvent(renewed, AttemptStatus.CLAIMED, "former-owner-renewal")
        ));
        assertEquals("worker-two", store.findAttempt(TENANT, pending.attemptId()).orElseThrow().leaseOwner());
    }

    @Test
    void concurrentExpiredTakeoverHasExactlyOneRevisionOwner() throws Exception {
        ApprovalMigrationAttempt pending = persistAttempt();
        ApprovalMigrationAttempt claimed = claim(pending, "worker-one", NOW.plusSeconds(2), NOW.plusSeconds(20));
        store.transitionAttempt(
            claimed, pending.revision(), "worker-one",
            attemptEvent(claimed, AttemptStatus.PENDING, "concurrent-claim")
        );
        ApprovalMigrationAttempt workerTwo = claim(
            claimed, "worker-two", NOW.plusSeconds(20), NOW.plusSeconds(80)
        );
        ApprovalMigrationAttempt workerThree = claim(
            claimed, "worker-three", NOW.plusSeconds(20), NOW.plusSeconds(80)
        );
        JdbcApprovalMigrationProtocolStore second = independentStore();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(takeoverCall(
                store, workerTwo, "worker-two", "takeover-two", ready, start
            ));
            Future<Boolean> secondResult = executor.submit(takeoverCall(
                second, workerThree, "worker-three", "takeover-three", ready, start
            ));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int successes = (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                + (secondResult.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        } finally {
            executor.shutdownNow();
        }
        ApprovalMigrationAttempt current = store.findAttempt(TENANT, pending.attemptId()).orElseThrow();
        assertTrue(List.of("worker-two", "worker-three").contains(current.leaseOwner()));
        assertEquals(3, current.revision());
        assertEquals(3, store.findAttemptEvents(TENANT, pending.attemptId()).size());
    }

    @Test
    void unknownPersistsIndependentRequestFailureAndEventColumns() {
        ApprovalMigrationAttempt unknown = persistUnknown();
        assertEquals("UNKNOWN|engine-request-one|ENGINE_OUTCOME_UNKNOWN", jdbc.queryForObject("""
            select engine_outcome || '|' || engine_request_reference || '|' || failure_class
            from ap_process_migration_attempt where tenant_id=? and attempt_id=?
            """, String.class, TENANT, unknown.attemptId()));
        assertEquals("UNKNOWN|engine-request-one|ENGINE_OUTCOME_UNKNOWN", jdbc.queryForObject("""
            select engine_outcome || '|' || engine_request_reference || '|' || failure_class
            from ap_process_migration_attempt_event
            where tenant_id=? and attempt_id=? and revision=?
            """, String.class, TENANT, unknown.attemptId(), unknown.revision()));
        assertEquals("Engine response was not observed", jdbc.queryForObject("""
            select error_summary from ap_process_migration_attempt
            where tenant_id=? and attempt_id=?
            """, String.class, TENANT, unknown.attemptId()));
    }

    @Test
    void unknownCannotProgressWithoutOpenReconciliationEvidence() {
        ApprovalMigrationAttempt unknown = persistUnknown();
        ApprovalMigrationAttempt reconciling = reconciling(unknown, NOW.plusSeconds(42));
        assertThrows(MigrationProtocolConflictException.class, () -> store.transitionAttempt(
            reconciling, unknown.revision(), null,
            attemptEvent(reconciling, AttemptStatus.UNKNOWN, "unknown-without-open")
        ));
        ApprovalMigrationIntent owner = store.findIntent(TENANT, unknown.intentId()).orElseThrow();
        store.appendReconciliation(reconciliation(owner, unknown, 1, ReconciliationStatus.OPEN));
        store.transitionAttempt(
            reconciling, unknown.revision(), null,
            attemptEvent(reconciling, AttemptStatus.UNKNOWN, "unknown-with-open")
        );
        assertEquals("engine-request-one", store.findAttempt(TENANT, unknown.attemptId())
            .orElseThrow().engineRequestReference());
    }

    @Test
    void unknownDerivedAttemptRequiresTerminalReconciliationBeforeClosure() {
        ApprovalMigrationAttempt unknown = persistUnknown();
        ApprovalMigrationIntent owner = store.findIntent(TENANT, unknown.intentId()).orElseThrow();
        store.appendReconciliation(reconciliation(owner, unknown, 1, ReconciliationStatus.OPEN));
        ApprovalMigrationAttempt reconciling = reconciling(unknown, NOW.plusSeconds(42));
        store.transitionAttempt(
            reconciling, unknown.revision(), null,
            attemptEvent(reconciling, AttemptStatus.UNKNOWN, "unknown-reconciling")
        );
        ApprovalMigrationAttempt failed = terminalFailure(reconciling, NOW.plusSeconds(60));
        assertThrows(MigrationProtocolConflictException.class, () -> store.transitionAttempt(
            failed, reconciling.revision(), null,
            attemptEvent(failed, AttemptStatus.RECONCILING, "premature-terminal")
        ));
        store.appendReconciliation(reconciliation(
            owner, reconciling, 2, ReconciliationStatus.UNRESOLVED
        ));
        store.transitionAttempt(
            failed, reconciling.revision(), null,
            attemptEvent(failed, AttemptStatus.RECONCILING, "terminal-after-evidence")
        );
        assertEquals(AttemptStatus.FAILED_TERMINAL, store.findAttempt(TENANT, failed.attemptId())
            .orElseThrow().status());
    }

    @Test
    void directLeaseUnknownAndAppendOnlyTamperingFailsClosed() {
        ApprovalMigrationAttempt pending = persistAttempt();
        ApprovalMigrationAttempt claimed = claim(pending, "worker-one", NOW.plusSeconds(2), NOW.plusSeconds(60));
        store.transitionAttempt(
            claimed, pending.revision(), "worker-one",
            attemptEvent(claimed, AttemptStatus.PENDING, "tamper-claim")
        );
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            update ap_process_migration_attempt set lease_actor='attacker',revision=3,
             lease_until=?,updated_at=?,
             payload_json=jsonb_set(jsonb_set(jsonb_set(payload_json,'{revision}',to_jsonb(3::bigint)),
               '{leaseUntil}',to_jsonb(?::text)),'{updatedAt}',to_jsonb(?::text))
             where tenant_id=? and attempt_id=?
            """, NOW.plusSeconds(90), NOW.plusSeconds(10),
            NOW.plusSeconds(90).toString(), NOW.plusSeconds(10).toString(),
            TENANT, claimed.attemptId()));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            update ap_process_migration_attempt_event set lease_actor='attacker'
            where tenant_id=? and attempt_id=? and revision=2
            """, TENANT, claimed.attemptId()));
        assertFalse(store.findAttemptEvents(TENANT, claimed.attemptId()).isEmpty());
    }

    private ApprovalMigrationAttempt persistAttempt() {
        ApprovalMigrationIntent value = intent();
        store.createIntent(value, initialIntentEvent(value, "lease-unknown-intent"));
        ApprovalMigrationAttempt pending = attempt();
        store.createAttempt(pending, initialAttemptEvent(pending, "lease-unknown-attempt"));
        return pending;
    }

    private ApprovalMigrationAttempt persistUnknown() {
        ApprovalMigrationAttempt pending = persistAttempt();
        ApprovalMigrationAttempt claimed = claim(pending, "worker-one", NOW.plusSeconds(2), NOW.plusSeconds(100));
        store.transitionAttempt(
            claimed, pending.revision(), "worker-one",
            attemptEvent(claimed, AttemptStatus.PENDING, "unknown-claim")
        );
        ApprovalMigrationAttempt requested = requested(claimed, NOW.plusSeconds(3));
        store.transitionAttempt(
            requested, claimed.revision(), "worker-one",
            attemptEvent(requested, AttemptStatus.CLAIMED, "engine-requested")
        );
        ApprovalMigrationAttempt unknown = requested.transitioned(new ApprovalMigrationAttemptTransition(
            AttemptStatus.UNKNOWN, EngineOutcome.UNKNOWN, null, null, "engine-request-one",
            FailureClass.ENGINE_OUTCOME_UNKNOWN, "Engine response was not observed", NOW.plusSeconds(4)
        ));
        store.transitionAttempt(
            unknown, requested.revision(), null,
            attemptEvent(unknown, AttemptStatus.ENGINE_REQUESTED, "engine-unknown")
        );
        return unknown;
    }

    private static ApprovalMigrationAttempt claim(
        ApprovalMigrationAttempt current,
        String owner,
        Instant happenedAt,
        Instant leaseUntil
    ) {
        return current.transitioned(new ApprovalMigrationAttemptTransition(
            AttemptStatus.CLAIMED, EngineOutcome.NOT_REQUESTED, owner, leaseUntil, null,
            FailureClass.NONE, null, happenedAt
        ));
    }

    private static ApprovalMigrationAttempt requested(
        ApprovalMigrationAttempt claimed,
        Instant happenedAt
    ) {
        return claimed.transitioned(new ApprovalMigrationAttemptTransition(
            AttemptStatus.ENGINE_REQUESTED, EngineOutcome.ACCEPTED, null, null,
            "engine-request-one", FailureClass.NONE, null, happenedAt
        ));
    }

    private static ApprovalMigrationAttempt reconciling(
        ApprovalMigrationAttempt unknown,
        Instant happenedAt
    ) {
        return unknown.transitioned(new ApprovalMigrationAttemptTransition(
            AttemptStatus.RECONCILING, EngineOutcome.UNKNOWN, null, null,
            "engine-request-one", FailureClass.RECONCILIATION_REQUIRED,
            "Authoritative reconciliation is required", happenedAt
        ));
    }

    private static ApprovalMigrationAttempt terminalFailure(
        ApprovalMigrationAttempt reconciling,
        Instant happenedAt
    ) {
        return reconciling.transitioned(new ApprovalMigrationAttemptTransition(
            AttemptStatus.FAILED_TERMINAL, EngineOutcome.UNKNOWN, null, null, null,
            FailureClass.RECONCILIATION_REQUIRED, "Reconciliation remained unresolved", happenedAt
        ));
    }

    private JdbcApprovalMigrationProtocolStore independentStore() {
        return new JdbcApprovalMigrationProtocolStore(
            dataSource,
            new ObjectMapper().findAndRegisterModules(),
            new JdbcTransactionManager(dataSource)
        );
    }

    private static Callable<Boolean> takeoverCall(
        JdbcApprovalMigrationProtocolStore target,
        ApprovalMigrationAttempt next,
        String actor,
        String suffix,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            try {
                target.transitionAttempt(
                    next, next.revision() - 1, actor,
                    attemptEvent(next, AttemptStatus.CLAIMED, suffix)
                );
                return true;
            } catch (MigrationProtocolConflictException expected) {
                return false;
            }
        };
    }
}
