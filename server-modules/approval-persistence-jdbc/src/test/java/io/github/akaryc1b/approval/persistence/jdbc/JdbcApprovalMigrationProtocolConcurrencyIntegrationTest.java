package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.AttemptCreationResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.IntentCreationResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.MigrationProtocolConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.ReconciliationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.INSTANCE_ID;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.NOW;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.hash;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialAttemptEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialIntentEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.intent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.reconciliation;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.verification;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcApprovalMigrationProtocolConcurrencyIntegrationTest
    extends AbstractJdbcApprovalMigrationProtocolIntegrationTest {

    @Test
    void identicalIntentCreationHasOneInsertAndOneAuthoritativeReplay() throws Exception {
        JdbcApprovalMigrationProtocolStore peer = independentStore();
        ApprovalMigrationIntent value = intent(
            new UUID(110, 1), new UUID(111, 1), hash('1'), "concurrent-intent-key", hash('2')
        );
        ApprovalMigrationIntentEvent event = initialIntentEvent(
            value,
            "concurrent-identical-intent"
        );

        List<ConcurrentOutcome<IntentCreationResult>> outcomes = race(
            () -> store.createIntent(value, event),
            () -> peer.createIntent(value, event)
        );
        List<IntentCreationResult> successes = assertTwoSuccesses(outcomes);

        assertEquals(1, successes.stream().filter(IntentCreationResult::replayedExistingRequest).count());
        assertEquals(1, successes.stream().filter(result -> !result.replayedExistingRequest()).count());
        assertTrue(successes.stream().allMatch(result -> result.intent().equals(value)));
        assertEquals(1, count("ap_process_migration_intent"));
        assertEquals(1, count("ap_process_migration_intent_event"));
    }

    @Test
    void reusedIntentIdempotencyKeyHasOneWinnerAndNoSecondEvent() throws Exception {
        JdbcApprovalMigrationProtocolStore peer = independentStore();
        ApprovalMigrationIntent first = intent(
            new UUID(112, 1), new UUID(113, 1), hash('3'), "shared-intent-key", hash('4')
        );
        ApprovalMigrationIntent second = intent(
            new UUID(112, 2), new UUID(113, 2), hash('5'), "shared-intent-key", hash('6')
        );

        List<ConcurrentOutcome<IntentCreationResult>> outcomes = race(
            () -> store.createIntent(first, initialIntentEvent(first, "shared-key-first")),
            () -> peer.createIntent(second, initialIntentEvent(second, "shared-key-second"))
        );
        IntentCreationResult winner = assertOneSuccessOneConflict(outcomes);

        assertEquals(
            winner.intent(),
            store.findIntentByIdempotencyKey(TENANT, "shared-intent-key").orElseThrow()
        );
        assertEquals(1, count("ap_process_migration_intent"));
        assertEquals(1, count("ap_process_migration_intent_event"));
    }

    @Test
    void sharedPlanHashHasOneIntentWinnerAndLoserFailsClosed() throws Exception {
        JdbcApprovalMigrationProtocolStore peer = independentStore();
        ApprovalMigrationIntent first = intent(
            new UUID(114, 1), new UUID(115, 1), hash('7'), "plan-first", hash('8')
        );
        ApprovalMigrationIntent second = intent(
            new UUID(114, 2), new UUID(115, 2), hash('7'), "plan-second", hash('9')
        );

        List<ConcurrentOutcome<IntentCreationResult>> outcomes = race(
            () -> store.createIntent(first, initialIntentEvent(first, "plan-hash-first")),
            () -> peer.createIntent(second, initialIntentEvent(second, "plan-hash-second"))
        );
        IntentCreationResult winner = assertOneSuccessOneConflict(outcomes);

        assertEquals(winner.intent(), store.findIntent(TENANT, winner.intent().intentId()).orElseThrow());
        assertEquals(1, count("ap_process_migration_intent"));
        assertEquals(1, count("ap_process_migration_intent_event"));
    }

    @Test
    void concurrentIntentsCannotOwnTheSameActiveInstance() throws Exception {
        JdbcApprovalMigrationProtocolStore peer = independentStore();
        ApprovalMigrationIntent firstIntent = createIntent('1');
        ApprovalMigrationIntent secondIntent = createIntent('2');
        ApprovalMigrationAttempt first = newAttempt(
            new UUID(120, 1), firstIntent.intentId(), INSTANCE_ID, '1'
        );
        ApprovalMigrationAttempt second = newAttempt(
            new UUID(120, 2), secondIntent.intentId(), INSTANCE_ID, '2'
        );

        List<ConcurrentOutcome<AttemptCreationResult>> outcomes = race(
            () -> store.createAttempt(first, initialAttemptEvent(first, "active-owner-first")),
            () -> peer.createAttempt(second, initialAttemptEvent(second, "active-owner-second"))
        );
        AttemptCreationResult winner = assertOneSuccessOneConflict(outcomes);

        assertEquals(winner.attempt(), store.findAttempt(TENANT, winner.attempt().attemptId()).orElseThrow());
        assertEquals(1, count("ap_process_migration_attempt"));
        assertEquals(1, count("ap_process_migration_attempt_event"));
        int activeOwners = jdbc.queryForObject("""
            select count(*) from ap_process_migration_attempt
            where tenant_id=? and approval_instance_id=?
              and status in ('PENDING','CLAIMED','ENGINE_REQUESTED','VERIFYING','UNKNOWN','RECONCILING')
            """, Integer.class, TENANT, INSTANCE_ID);
        assertEquals(1, activeOwners);
    }

    @Test
    void identicalAttemptCreationHasOneInsertAndOneReplay() throws Exception {
        JdbcApprovalMigrationProtocolStore peer = independentStore();
        ApprovalMigrationIntent owner = createIntent('3');
        ApprovalMigrationAttempt value = newAttempt(
            new UUID(121, 1), owner.intentId(), new UUID(122, 1), '3'
        );
        ApprovalMigrationAttemptEvent event = initialAttemptEvent(
            value,
            "concurrent-identical-attempt"
        );

        List<ConcurrentOutcome<AttemptCreationResult>> outcomes = race(
            () -> store.createAttempt(value, event),
            () -> peer.createAttempt(value, event)
        );
        List<AttemptCreationResult> successes = assertTwoSuccesses(outcomes);

        assertEquals(1, successes.stream().filter(AttemptCreationResult::replayedExistingRequest).count());
        assertEquals(1, successes.stream().filter(result -> !result.replayedExistingRequest()).count());
        assertTrue(successes.stream().allMatch(result -> result.attempt().equals(value)));
        assertEquals(1, count("ap_process_migration_attempt"));
        assertEquals(1, count("ap_process_migration_attempt_event"));
    }

    @Test
    void changedAttemptIdentityHasOneWinnerAndNoOrphanEvent() throws Exception {
        JdbcApprovalMigrationProtocolStore peer = independentStore();
        ApprovalMigrationIntent owner = createIntent('4');
        ApprovalMigrationAttempt first = newAttempt(
            new UUID(123, 1), owner.intentId(), new UUID(124, 1), '4'
        );
        ApprovalMigrationAttempt second = withBindingHash(first, hash('f'));

        List<ConcurrentOutcome<AttemptCreationResult>> outcomes = race(
            () -> store.createAttempt(first, initialAttemptEvent(first, "attempt-payload-first")),
            () -> peer.createAttempt(second, initialAttemptEvent(second, "attempt-payload-second"))
        );
        AttemptCreationResult winner = assertOneSuccessOneConflict(outcomes);

        assertEquals(winner.attempt(), store.findAttempt(TENANT, first.attemptId()).orElseThrow());
        assertEquals(1, count("ap_process_migration_attempt"));
        assertEquals(1, count("ap_process_migration_attempt_event"));
    }

    @Test
    void verificationIdentityReplayAndChangedPayloadAreClosedUnderConcurrency() throws Exception {
        JdbcApprovalMigrationProtocolStore peer = independentStore();
        ProtocolOwner replayOwner = createOwner('5');
        ApprovalMigrationVerification replay = verificationEvidence(replayOwner, 8101, 1, '1');

        List<ConcurrentOutcome<ApprovalMigrationVerification>> replayOutcomes = race(
            () -> appendVerification(store, replay),
            () -> appendVerification(peer, replay)
        );
        assertTwoSuccesses(replayOutcomes);
        assertEquals(List.of(replay), store.findVerifications(TENANT, replayOwner.attempt().attemptId()));

        ProtocolOwner conflictOwner = createOwner('6');
        ApprovalMigrationVerification first = verificationEvidence(conflictOwner, 8102, 1, '2');
        ApprovalMigrationVerification second = verificationEvidence(conflictOwner, 8102, 1, '3');
        List<ConcurrentOutcome<ApprovalMigrationVerification>> conflictOutcomes = race(
            () -> appendVerification(store, first),
            () -> appendVerification(peer, second)
        );
        ApprovalMigrationVerification winner = assertOneSuccessOneConflict(conflictOutcomes);

        assertEquals(
            List.of(winner),
            store.findVerifications(TENANT, conflictOwner.attempt().attemptId())
        );
    }

    @Test
    void verificationSequenceCompetitionHasOneWinnerAndNoGap() throws Exception {
        JdbcApprovalMigrationProtocolStore peer = independentStore();
        ProtocolOwner owner = createOwner('7');
        ApprovalMigrationVerification first = verificationEvidence(owner, 8201, 1, '4');
        ApprovalMigrationVerification second = verificationEvidence(owner, 8202, 1, '5');

        ApprovalMigrationVerification winner = assertOneSuccessOneConflict(race(
            () -> appendVerification(store, first),
            () -> appendVerification(peer, second)
        ));
        ApprovalMigrationVerification next = verificationEvidence(owner, 8203, 2, '6');
        store.appendVerification(next);

        assertEquals(
            List.of(winner, next),
            store.findVerifications(TENANT, owner.attempt().attemptId())
        );
    }

    @Test
    void reconciliationIdentityReplayAndChangedPayloadAreClosedUnderConcurrency() throws Exception {
        JdbcApprovalMigrationProtocolStore peer = independentStore();
        ProtocolOwner replayOwner = createOwner('8');
        ApprovalMigrationReconciliation replay = reconciliationEvidence(
            replayOwner, 9101, 1, ReconciliationStatus.OPEN, '7'
        );

        List<ConcurrentOutcome<ApprovalMigrationReconciliation>> replayOutcomes = race(
            () -> appendReconciliation(store, replay),
            () -> appendReconciliation(peer, replay)
        );
        assertTwoSuccesses(replayOutcomes);
        assertEquals(
            List.of(replay),
            store.findReconciliations(TENANT, replayOwner.attempt().attemptId())
        );

        ProtocolOwner conflictOwner = createOwner('9');
        ApprovalMigrationReconciliation first = reconciliationEvidence(
            conflictOwner, 9102, 1, ReconciliationStatus.OPEN, '8'
        );
        ApprovalMigrationReconciliation second = reconciliationEvidence(
            conflictOwner, 9102, 1, ReconciliationStatus.OPEN, '9'
        );
        ApprovalMigrationReconciliation winner = assertOneSuccessOneConflict(race(
            () -> appendReconciliation(store, first),
            () -> appendReconciliation(peer, second)
        ));

        assertEquals(
            List.of(winner),
            store.findReconciliations(TENANT, conflictOwner.attempt().attemptId())
        );
    }

    @Test
    void reconciliationSequenceCompetitionHasOneWinnerAndNoGap() throws Exception {
        JdbcApprovalMigrationProtocolStore peer = independentStore();
        ProtocolOwner owner = createOwner('a');
        ApprovalMigrationReconciliation first = reconciliationEvidence(
            owner, 9201, 1, ReconciliationStatus.OPEN, 'a'
        );
        ApprovalMigrationReconciliation second = reconciliationEvidence(
            owner, 9202, 1, ReconciliationStatus.OPEN, 'b'
        );

        ApprovalMigrationReconciliation winner = assertOneSuccessOneConflict(race(
            () -> appendReconciliation(store, first),
            () -> appendReconciliation(peer, second)
        ));
        ApprovalMigrationReconciliation terminal = reconciliationEvidence(
            owner, 9203, 2, ReconciliationStatus.RESOLVED_TARGET, 'c'
        );
        store.appendReconciliation(terminal);

        assertEquals(
            List.of(winner, terminal),
            store.findReconciliations(TENANT, owner.attempt().attemptId())
        );
    }

    @Test
    void terminalReconciliationAndCompetingEvidenceProduceAClosedResult() throws Exception {
        JdbcApprovalMigrationProtocolStore peer = independentStore();
        ProtocolOwner owner = createOwner('b');
        ApprovalMigrationReconciliation open = reconciliationEvidence(
            owner, 9301, 1, ReconciliationStatus.OPEN, 'd'
        );
        store.appendReconciliation(open);
        ApprovalMigrationReconciliation terminal = reconciliationEvidence(
            owner, 9302, 2, ReconciliationStatus.RESOLVED_TARGET, 'e'
        );
        ApprovalMigrationReconciliation manual = reconciliationEvidence(
            owner, 9303, 2, ReconciliationStatus.MANUAL_REVIEW_REQUIRED, 'f'
        );

        ApprovalMigrationReconciliation winner = assertOneSuccessOneConflict(race(
            () -> appendReconciliation(store, terminal),
            () -> appendReconciliation(peer, manual)
        ));
        if (winner.status() == ReconciliationStatus.RESOLVED_TARGET) {
            ApprovalMigrationReconciliation afterTerminal = reconciliationEvidence(
                owner, 9304, 3, ReconciliationStatus.UNRESOLVED, '1'
            );
            assertThrows(
                MigrationProtocolConflictException.class,
                () -> store.appendReconciliation(afterTerminal)
            );
        } else {
            assertEquals(ReconciliationStatus.MANUAL_REVIEW_REQUIRED, winner.status());
            ApprovalMigrationReconciliation finalTerminal = reconciliationEvidence(
                owner, 9304, 3, ReconciliationStatus.RESOLVED_TARGET, '1'
            );
            store.appendReconciliation(finalTerminal);
            ApprovalMigrationReconciliation afterTerminal = reconciliationEvidence(
                owner, 9305, 4, ReconciliationStatus.UNRESOLVED, '2'
            );
            assertThrows(
                MigrationProtocolConflictException.class,
                () -> store.appendReconciliation(afterTerminal)
            );
        }

        List<ApprovalMigrationReconciliation> evidence = store.findReconciliations(
            TENANT,
            owner.attempt().attemptId()
        );
        List<Integer> expectedSequences = winner.status() == ReconciliationStatus.RESOLVED_TARGET
            ? List.of(1, 2)
            : List.of(1, 2, 3);
        assertEquals(expectedSequences, evidence.stream()
            .map(ApprovalMigrationReconciliation::sequence)
            .toList());
        assertTrue(evidence.get(evidence.size() - 1).status().name().startsWith("RESOLVED_"));
    }

    private ApprovalMigrationIntent createIntent(char token) {
        int ordinal = Character.digit(token, 16);
        ApprovalMigrationIntent value = intent(
            new UUID(200, ordinal),
            new UUID(201, ordinal),
            hash(hex(token, 1)),
            "concurrent-owner-" + token,
            hash(hex(token, 2))
        );
        assertFalse(store.createIntent(
            value,
            initialIntentEvent(value, "concurrent-owner-intent-" + token)
        ).replayedExistingRequest());
        return value;
    }

    private ProtocolOwner createOwner(char token) {
        int ordinal = Character.digit(token, 16);
        ApprovalMigrationIntent owner = createIntent(token);
        ApprovalMigrationAttempt attempt = newAttempt(
            new UUID(202, ordinal), owner.intentId(), new UUID(203, ordinal), token
        );
        assertFalse(store.createAttempt(
            attempt,
            initialAttemptEvent(attempt, "concurrent-owner-attempt-" + token)
        ).replayedExistingRequest());
        return new ProtocolOwner(owner, attempt);
    }

    private static ApprovalMigrationAttempt newAttempt(
        UUID attemptId,
        UUID intentId,
        UUID instanceId,
        char token
    ) {
        int ordinal = Character.digit(token, 16);
        return new ApprovalMigrationAttempt(
            attemptId, TENANT, intentId, instanceId, "engine-instance-" + token,
            1, null, hash(hex(token, 3)), "source-definition-" + token,
            "target-definition-" + token, AttemptStatus.PENDING, EngineOutcome.NOT_REQUESTED,
            1, null, null, null, FailureClass.NONE, null,
            NOW.plusSeconds(ordinal), NOW.plusSeconds(ordinal),
            "request-attempt-" + token, "trace-migration-protocol"
        );
    }

    private static ApprovalMigrationAttempt withBindingHash(
        ApprovalMigrationAttempt value,
        String bindingHash
    ) {
        return new ApprovalMigrationAttempt(
            value.attemptId(), value.tenantId(), value.intentId(), value.approvalInstanceId(),
            value.engineInstanceId(), value.attemptNumber(), value.parentAttemptId(), bindingHash,
            value.sourceEngineDefinitionId(), value.targetEngineDefinitionId(), value.status(),
            value.engineOutcome(), value.revision(), value.leaseOwner(), value.leaseUntil(),
            value.engineRequestReference(), value.failureClass(), value.errorSummary(),
            value.createdAt(), value.updatedAt(), value.requestId(), value.traceId()
        );
    }

    private static ApprovalMigrationVerification verificationEvidence(
        ProtocolOwner owner,
        long identity,
        int sequence,
        char evidenceToken
    ) {
        ApprovalMigrationVerification base = verification(owner.intent(), owner.attempt(), sequence);
        return new ApprovalMigrationVerification(
            new UUID(identity, sequence), base.tenantId(), base.intentId(), base.attemptId(),
            base.sequence(), base.expectedBindingEvidenceHash(), base.observedBindingEvidenceHash(),
            base.sourceEngineDefinitionId(), base.targetEngineDefinitionId(),
            base.observedEngineDefinitionId(), base.expectedActiveTaskKeys(),
            base.observedActiveTaskKeys(), base.runtimePresent(), base.historyPresent(), base.outcome(),
            hash(evidenceToken), base.recordedAt(), "request-verification-" + identity + '-' + sequence,
            base.traceId()
        );
    }

    private static ApprovalMigrationReconciliation reconciliationEvidence(
        ProtocolOwner owner,
        long identity,
        int sequence,
        ReconciliationStatus status,
        char evidenceToken
    ) {
        ApprovalMigrationReconciliation base = reconciliation(
            owner.intent(), owner.attempt(), sequence, status
        );
        return new ApprovalMigrationReconciliation(
            new UUID(identity, sequence), base.tenantId(), base.intentId(), base.attemptId(),
            base.sequence(), base.status(), base.failureClass(), base.reason(), hash(evidenceToken),
            base.resolutionEvidenceHash(), base.resolvedBy(), base.recordedAt(), base.resolvedAt(),
            "request-reconciliation-" + identity + '-' + sequence, base.traceId(),
            "audit-reconciliation-" + identity + '-' + sequence
        );
    }

    private static ApprovalMigrationVerification appendVerification(
        JdbcApprovalMigrationProtocolStore target,
        ApprovalMigrationVerification value
    ) {
        target.appendVerification(value);
        return value;
    }

    private static ApprovalMigrationReconciliation appendReconciliation(
        JdbcApprovalMigrationProtocolStore target,
        ApprovalMigrationReconciliation value
    ) {
        target.appendReconciliation(value);
        return value;
    }

    private JdbcApprovalMigrationProtocolStore independentStore() {
        return new JdbcApprovalMigrationProtocolStore(
            dataSource,
            new ObjectMapper().findAndRegisterModules(),
            new JdbcTransactionManager(dataSource)
        );
    }

    private static <T> List<ConcurrentOutcome<T>> race(
        Callable<T> first,
        Callable<T> second
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ConcurrentOutcome<T>> firstFuture = executor.submit(gated(first, ready, start));
            Future<ConcurrentOutcome<T>> secondFuture = executor.submit(gated(second, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<ConcurrentOutcome<T>> outcomes = List.of(
                firstFuture.get(30, TimeUnit.SECONDS),
                secondFuture.get(30, TimeUnit.SECONDS)
            );
            assertEquals(2, outcomes.stream().map(ConcurrentOutcome::threadId).distinct().count());
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static <T> Callable<ConcurrentOutcome<T>> gated(
        Callable<T> operation,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        return () -> {
            long threadId = Thread.currentThread().threadId();
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                return new ConcurrentOutcome<>(
                    threadId,
                    null,
                    new IllegalStateException("concurrent start gate timed out")
                );
            }
            try {
                return new ConcurrentOutcome<>(threadId, operation.call(), null);
            } catch (Exception failure) {
                return new ConcurrentOutcome<>(threadId, null, failure);
            }
        };
    }

    private static <T> List<T> assertTwoSuccesses(List<ConcurrentOutcome<T>> outcomes) {
        List<T> successes = outcomes.stream()
            .filter(ConcurrentOutcome::successful)
            .map(ConcurrentOutcome::value)
            .toList();
        assertEquals(2, successes.size());
        return successes;
    }

    private static <T> T assertOneSuccessOneConflict(List<ConcurrentOutcome<T>> outcomes) {
        List<T> successes = outcomes.stream()
            .filter(ConcurrentOutcome::successful)
            .map(ConcurrentOutcome::value)
            .toList();
        assertEquals(1, successes.size());
        assertEquals(
            1,
            outcomes.stream()
                .filter(outcome -> outcome.failure() instanceof MigrationProtocolConflictException)
                .count()
        );
        assertEquals(
            0,
            outcomes.stream()
                .filter(outcome -> !outcome.successful())
                .filter(outcome -> !(outcome.failure() instanceof MigrationProtocolConflictException))
                .count()
        );
        return successes.get(0);
    }

    private static char hex(char token, int delta) {
        int value = Math.floorMod(Character.digit(token, 16) + delta, 16);
        return Integer.toHexString(value).charAt(0);
    }

    private record ProtocolOwner(
        ApprovalMigrationIntent intent,
        ApprovalMigrationAttempt attempt
    ) {
    }

    private record ConcurrentOutcome<T>(
        long threadId,
        T value,
        Exception failure
    ) {
        boolean successful() {
            return failure == null;
        }
    }
}
