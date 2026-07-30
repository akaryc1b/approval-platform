package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalMigrationReconciliationService.ReconciliationRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.DefinitionEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.TaskEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.ReconciliationStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationLease;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationLease.ReconciliationLeaseStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationObservation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationObservation.ReconciliationDisposition;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalMigrationReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T02:30:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String SOURCE = "definition-source";
    private static final String TARGET = "definition-target";

    @Test
    void readsEngineBetweenShortStoreTransactionsAndRequiresSeparateTargetCas() {
        RecordingStore store = new RecordingStore();
        ProcessInstanceVerificationPort engine = command -> {
            assertFalse(store.transactionOpen);
            store.order.add("engine-read");
            return targetSnapshot();
        };

        var result = service(store, engine).reconcile(request());

        assertEquals(List.of("prepare", "engine-read", "finalize"), store.order);
        assertEquals(
            ReconciliationDisposition.TARGET_CONFIRMED_BINDING_CAS_REQUIRED,
            result.disposition()
        );
        assertFalse(result.replayed());
    }

    @Test
    void exactReplaySkipsAnotherEngineRead() {
        RecordingStore store = new RecordingStore();
        store.replay = stored(targetSnapshot(), true);
        int[] reads = {0};

        var result = service(store, command -> {
            reads[0]++;
            return targetSnapshot();
        }).reconcile(request());

        assertEquals(0, reads[0]);
        assertEquals(List.of("prepare"), store.order);
        assertTrue(result.replayed());
    }

    @Test
    void readFailureBecomesManualReviewWithoutMigrationRetry() {
        RecordingStore store = new RecordingStore();
        var result = service(store, command -> {
            throw new ProcessInstanceVerificationPort.VerificationReadException(
                "CONNECTION_RESET",
                "reconciliation read reset",
                new IllegalStateException("reset")
            );
        }).reconcile(request());

        assertEquals(ReconciliationDisposition.MANUAL_REVIEW_REQUIRED, result.disposition());
        assertFalse(result.observation().snapshot().readSucceeded());
        assertEquals("CONNECTION_RESET", result.observation().snapshot().readFailureCode());
    }

    @Test
    void oneShotRunnerRequiresAllThreeExplicitFlags() {
        RecordingStore store = new RecordingStore();
        ApprovalMigrationReconciliationService service = service(store, command -> targetSnapshot());

        var executionOff = new ApprovalMigrationReconciliationService.OneShotRunner(
            false,
            true,
            true,
            service
        );
        var workerOff = new ApprovalMigrationReconciliationService.OneShotRunner(
            true,
            false,
            true,
            service
        );
        var reconciliationOff = new ApprovalMigrationReconciliationService.OneShotRunner(
            true,
            true,
            false,
            service
        );

        assertThrows(IllegalStateException.class, () -> executionOff.runOnce(request()));
        assertThrows(IllegalStateException.class, () -> workerOff.runOnce(request()));
        assertThrows(IllegalStateException.class, () -> reconciliationOff.runOnce(request()));
        assertTrue(store.order.isEmpty());
    }

    private static ApprovalMigrationReconciliationService service(
        RecordingStore store,
        ProcessInstanceVerificationPort engine
    ) {
        return new ApprovalMigrationReconciliationService(
            store,
            engine,
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(5)
        );
    }

    private static ReconciliationRequest request() {
        return new ReconciliationRequest(
            "tenant-d6",
            UUID.fromString("57000000-0000-0000-0000-000000000001"),
            "worker-d6",
            4,
            "request-d6",
            "trace-d6"
        );
    }

    private static ApprovalMigrationAttempt attempt() {
        return new ApprovalMigrationAttempt(
            request().attemptId(),
            request().tenantId(),
            UUID.fromString("57000000-0000-0000-0000-000000000002"),
            UUID.fromString("57000000-0000-0000-0000-000000000003"),
            "engine-instance-d6",
            1,
            null,
            HASH,
            SOURCE,
            TARGET,
            AttemptStatus.RECONCILING,
            EngineOutcome.UNKNOWN,
            5,
            null,
            null,
            "57000000-0000-0000-0000-000000000004",
            FailureClass.RECONCILIATION_REQUIRED,
            "Ambiguous engine outcome requires reconciliation",
            NOW.minusSeconds(60),
            NOW,
            request().requestId(),
            request().traceId()
        );
    }

    private static ApprovalMigrationReconciliation reconciliation() {
        return new ApprovalMigrationReconciliation(
            UUID.fromString("57000000-0000-0000-0000-000000000005"),
            request().tenantId(),
            attempt().intentId(),
            attempt().attemptId(),
            1,
            ReconciliationStatus.OPEN,
            FailureClass.ENGINE_OUTCOME_UNKNOWN,
            "Ambiguous migration call requires public readback",
            HASH,
            null,
            null,
            NOW,
            null,
            request().requestId(),
            request().traceId(),
            "audit:reconciliation-open"
        );
    }

    private static ApprovalMigrationReconciliationLease activeLease(Instant leaseUntil) {
        return new ApprovalMigrationReconciliationLease(
            UUID.fromString("57000000-0000-0000-0000-000000000006"),
            request().tenantId(),
            attempt().intentId(),
            attempt().attemptId(),
            ReconciliationLeaseStatus.ACTIVE,
            1,
            request().workerId(),
            leaseUntil,
            NOW,
            NOW,
            null,
            HASH,
            HASH,
            request().requestId(),
            request().traceId()
        );
    }

    private static ApprovalMigrationEngineSnapshot targetSnapshot() {
        return new ApprovalMigrationEngineSnapshot(
            true,
            null,
            true,
            TARGET,
            "deployment-target",
            false,
            List.of("review"),
            List.of(new DefinitionEvidence("EXECUTION", "execution", TARGET)),
            List.of(new TaskEvidence(HASH, "review", TARGET, false)),
            List.of(),
            List.of(),
            List.of(HASH),
            List.of(HASH),
            true,
            TARGET,
            null,
            null,
            List.of(new TaskEvidence(HASH, "review", TARGET, false)),
            false,
            HASH
        );
    }

    private static ApprovalMigrationReconciliationStore.StoredReconciliation stored(
        ApprovalMigrationEngineSnapshot snapshot,
        boolean replayed
    ) {
        var classification = io.github.akaryc1b.approval.domain.migration
            .ApprovalMigrationExactVerification.classify(snapshot, SOURCE, TARGET);
        var disposition = ApprovalMigrationReconciliationObservation.dispositionFor(classification);
        ApprovalMigrationReconciliationObservation observation =
            new ApprovalMigrationReconciliationObservation(
                UUID.fromString("57000000-0000-0000-0000-000000000007"),
                request().tenantId(),
                attempt().intentId(),
                attempt().attemptId(),
                reconciliation().reconciliationId(),
                activeLease(NOW.plusSeconds(300)).leaseId(),
                request().workerId(),
                attempt().revision(),
                1,
                SOURCE,
                TARGET,
                classification,
                disposition,
                snapshot,
                HASH,
                HASH,
                NOW,
                request().requestId(),
                request().traceId()
            );
        ApprovalMigrationReconciliationLease released = new ApprovalMigrationReconciliationLease(
            activeLease(NOW.plusSeconds(300)).leaseId(),
            request().tenantId(),
            attempt().intentId(),
            attempt().attemptId(),
            ReconciliationLeaseStatus.RELEASED,
            2,
            request().workerId(),
            NOW.plusSeconds(300),
            NOW,
            NOW,
            NOW,
            HASH,
            HASH,
            request().requestId(),
            request().traceId()
        );
        return new ApprovalMigrationReconciliationStore.StoredReconciliation(
            observation,
            attempt(),
            reconciliation(),
            released,
            disposition,
            replayed
        );
    }

    private static final class RecordingStore implements ApprovalMigrationReconciliationStore {
        private final List<String> order = new ArrayList<>();
        private boolean transactionOpen;
        private StoredReconciliation replay;

        @Override
        public PreparedReconciliation prepare(PrepareRequest request) {
            transactionOpen = true;
            order.add("prepare");
            PreparedReconciliation prepared = new PreparedReconciliation(
                attempt(),
                reconciliation(),
                activeLease(request.leaseUntil()),
                HASH,
                new ProcessInstanceVerificationPort.VerificationCommand(
                    request.tenantId(),
                    attempt().engineInstanceId(),
                    List.of()
                ),
                request.happenedAt(),
                request.requestId(),
                request.traceId(),
                replay
            );
            transactionOpen = false;
            return prepared;
        }

        @Override
        public StoredReconciliation finalizeObservation(FinalizeRequest request) {
            assertFalse(transactionOpen);
            transactionOpen = true;
            order.add("finalize");
            StoredReconciliation stored = stored(request.snapshot(), false);
            transactionOpen = false;
            return stored;
        }
    }
}
