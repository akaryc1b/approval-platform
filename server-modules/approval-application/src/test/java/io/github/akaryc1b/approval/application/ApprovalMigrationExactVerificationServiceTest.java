package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalMigrationExactVerificationService.VerificationRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExactVerificationStore;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.DefinitionEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot.TaskEvidence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification.ExactClassification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalMigrationExactVerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T13:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final String SOURCE = "definition-source";
    private static final String TARGET = "definition-target";

    @Test
    void readsEngineOutsideStoreTransactionsAndPersistsServerDerivedClassification() {
        RecordingStore store = new RecordingStore();
        ProcessInstanceVerificationPort engine = command -> {
            assertFalse(store.transactionOpen);
            store.order.add("engine-read");
            return targetSnapshot();
        };
        ApprovalMigrationExactVerificationService service = service(store, engine);

        ApprovalMigrationExactVerificationStore.StoredVerification result = service.verify(request());

        assertEquals(List.of("prepare", "engine-read", "finalize"), store.order);
        assertEquals(ExactClassification.EXACT_TARGET_RUNTIME, result.evidence().classification());
        assertEquals(HASH, result.evidence().snapshot().snapshotHash());
        assertFalse(result.replayed());
    }

    @Test
    void exactReplayReturnsStoredEvidenceWithoutAnotherEngineRead() {
        RecordingStore store = new RecordingStore();
        store.replay = storedEvidence(true);
        int[] reads = {0};
        ApprovalMigrationExactVerificationService service = service(store, command -> {
            reads[0]++;
            return targetSnapshot();
        });

        ApprovalMigrationExactVerificationStore.StoredVerification result = service.verify(request());

        assertEquals(0, reads[0]);
        assertTrue(result.replayed());
        assertEquals(List.of("prepare"), store.order);
    }

    @Test
    void readFailureBecomesBoundedReconciliationEvidence() {
        RecordingStore store = new RecordingStore();
        ApprovalMigrationExactVerificationService service = service(store, command -> {
            throw new ProcessInstanceVerificationPort.VerificationReadException(
                "CONNECTION_RESET",
                "verification read reset",
                new IllegalStateException("reset")
            );
        });

        ApprovalMigrationExactVerificationStore.StoredVerification result = service.verify(request());

        assertEquals(
            ExactClassification.READ_FAILURE_RECONCILIATION_REQUIRED,
            result.evidence().classification()
        );
        assertFalse(result.evidence().snapshot().readSucceeded());
        assertEquals("CONNECTION_RESET", result.evidence().snapshot().readFailureCode());
    }

    @Test
    void oneShotRunnerFailsClosedUnlessExecutionAndWorkerAreEnabled() {
        RecordingStore store = new RecordingStore();
        ApprovalMigrationExactVerificationService service = service(store, command -> targetSnapshot());

        var executionOff = new ApprovalMigrationExactVerificationService.OneShotRunner(false, true, service);
        var workerOff = new ApprovalMigrationExactVerificationService.OneShotRunner(true, false, service);

        assertThrows(IllegalStateException.class, () -> executionOff.runOnce(request()));
        assertThrows(IllegalStateException.class, () -> workerOff.runOnce(request()));
        assertTrue(store.order.isEmpty());
    }

    private static ApprovalMigrationExactVerificationService service(
        RecordingStore store,
        ProcessInstanceVerificationPort engine
    ) {
        return new ApprovalMigrationExactVerificationService(
            store,
            engine,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static VerificationRequest request() {
        return new VerificationRequest(
            "tenant-d4",
            UUID.fromString("00000000-0000-0000-0000-000000000401"),
            "worker-d4",
            4,
            1,
            "request-d4",
            "trace-d4"
        );
    }

    private static ApprovalMigrationAttempt attempt() {
        return new ApprovalMigrationAttempt(
            request().attemptId(),
            request().tenantId(),
            UUID.fromString("00000000-0000-0000-0000-000000000402"),
            UUID.fromString("00000000-0000-0000-0000-000000000403"),
            "engine-instance-d4",
            1,
            null,
            HASH,
            SOURCE,
            TARGET,
            AttemptStatus.VERIFYING,
            EngineOutcome.ACCEPTED,
            request().expectedAttemptRevision(),
            null,
            null,
            "00000000-0000-0000-0000-000000000404",
            FailureClass.NONE,
            null,
            NOW.minusSeconds(30),
            NOW.minusSeconds(10),
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

    private static ApprovalMigrationExactVerificationStore.StoredVerification storedEvidence(
        boolean replayed
    ) {
        ApprovalMigrationExactVerification evidence = new ApprovalMigrationExactVerification(
            UUID.fromString("00000000-0000-0000-0000-000000000405"),
            attempt().tenantId(),
            attempt().intentId(),
            attempt().attemptId(),
            UUID.fromString(attempt().engineRequestReference()),
            UUID.fromString("00000000-0000-0000-0000-000000000406"),
            SOURCE,
            TARGET,
            ExactClassification.EXACT_TARGET_RUNTIME,
            targetSnapshot(),
            HASH,
            HASH,
            NOW,
            request().requestId(),
            request().traceId()
        );
        return new ApprovalMigrationExactVerificationStore.StoredVerification(evidence, attempt(), replayed);
    }

    private static final class RecordingStore implements ApprovalMigrationExactVerificationStore {
        private final List<String> order = new ArrayList<>();
        private boolean transactionOpen;
        private StoredVerification replay;

        @Override
        public PreparedVerification prepare(PrepareRequest request) {
            transactionOpen = true;
            order.add("prepare");
            PreparedVerification prepared = new PreparedVerification(
                attempt(),
                UUID.fromString(attempt().engineRequestReference()),
                UUID.fromString("00000000-0000-0000-0000-000000000406"),
                request.expectedFenceRevision(),
                request.workerId(),
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
        public StoredVerification finalizeVerification(FinalizeRequest request) {
            assertFalse(transactionOpen);
            transactionOpen = true;
            order.add("finalize");
            ApprovalMigrationExactVerification evidence = new ApprovalMigrationExactVerification(
                UUID.fromString("00000000-0000-0000-0000-000000000405"),
                attempt().tenantId(),
                attempt().intentId(),
                attempt().attemptId(),
                request.prepared().engineRequestId(),
                request.prepared().engineOutcomeId(),
                SOURCE,
                TARGET,
                request.classification(),
                request.snapshot(),
                request.prepared().requestHash(),
                HASH,
                request.happenedAt(),
                request.prepared().requestId(),
                request.prepared().traceId()
            );
            transactionOpen = false;
            return new StoredVerification(evidence, attempt(), false);
        }
    }
}
