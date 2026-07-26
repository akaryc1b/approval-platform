package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalMigrationSingleInstanceExecutor.ExecutionRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptTransition;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.engine.ProcessInstanceMigrationPort;
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

class ApprovalMigrationSingleInstanceExecutorTest {

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void keepsEngineCallBetweenTwoStoreBoundariesAndLeavesReturnedCallVerifying() {
        RecordingStore store = new RecordingStore();
        ProcessInstanceMigrationPort engine = command -> {
            assertFalse(store.transactionOpen);
            store.order.add("engine");
            return returned(snapshot());
        };
        ApprovalMigrationSingleInstanceExecutor executor = executor(store, engine);

        var result = executor.execute(request());

        assertEquals(List.of("prepare", "engine", "finalize"), store.order);
        assertEquals(AttemptStatus.VERIFYING, result.attempt().status());
        assertEquals(EngineOutcome.ACCEPTED, result.attempt().engineOutcome());
        assertEquals("CALL_RETURNED_AWAITING_VERIFICATION", result.disposition());
    }

    @Test
    void persistsDurableUnknownOnceAndNeverRetriesAmbiguousDispatch() {
        RecordingStore store = new RecordingStore();
        int[] calls = {0};
        ProcessInstanceMigrationPort engine = command -> {
            calls[0]++;
            throw new ProcessInstanceMigrationPort.AmbiguousMigrationDispatchException(
                "RESPONSE_LOST",
                "response was lost after dispatch",
                true,
                new IllegalStateException("lost")
            );
        };
        ApprovalMigrationSingleInstanceExecutor executor = executor(store, engine);

        var result = executor.execute(request());

        assertEquals(1, calls[0]);
        assertEquals(AttemptStatus.UNKNOWN, result.attempt().status());
        assertEquals(EngineOutcome.UNKNOWN, result.attempt().engineOutcome());
        assertEquals(FailureClass.ENGINE_OUTCOME_UNKNOWN, result.attempt().failureClass());
        assertEquals(ApprovalMigrationEngineExecutionStore.FinalDisposition.AMBIGUOUS_UNKNOWN,
            store.finalizeRequest.disposition());
    }

    @Test
    void oneShotRunnerFailsClosedUnlessBothExecutionAndWorkerAreEnabled() {
        RecordingStore store = new RecordingStore();
        ApprovalMigrationSingleInstanceExecutor executor = executor(store, command -> returned(snapshot()));

        var disabledExecution = new ApprovalMigrationSingleInstanceExecutor.OneShotRunner(
            false,
            true,
            executor
        );
        var disabledWorker = new ApprovalMigrationSingleInstanceExecutor.OneShotRunner(
            true,
            false,
            executor
        );

        assertThrows(IllegalStateException.class, () -> disabledExecution.runOnce(request()));
        assertThrows(IllegalStateException.class, () -> disabledWorker.runOnce(request()));
        assertTrue(store.order.isEmpty());
    }

    private static ApprovalMigrationSingleInstanceExecutor executor(
        RecordingStore store,
        ProcessInstanceMigrationPort engine
    ) {
        return new ApprovalMigrationSingleInstanceExecutor(
            store,
            engine,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static ExecutionRequest request() {
        return new ExecutionRequest(
            "tenant-d3",
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
            "worker-d3",
            2,
            1,
            "request-d3",
            "trace-d3"
        );
    }

    private static ProcessInstanceMigrationPort.MigrationDispatchResult returned(
        ProcessInstanceMigrationPort.BoundedRuntimeSnapshot snapshot
    ) {
        return new ProcessInstanceMigrationPort.MigrationDispatchResult(
            ProcessInstanceMigrationPort.DispatchDisposition.CALL_RETURNED_AWAITING_VERIFICATION,
            true,
            true,
            snapshot,
            List.of(),
            "returned but not verified"
        );
    }

    private static ProcessInstanceMigrationPort.BoundedRuntimeSnapshot snapshot() {
        return new ProcessInstanceMigrationPort.BoundedRuntimeSnapshot(
            true,
            "source-definition",
            "source-deployment",
            false,
            List.of("review"),
            List.of("review"),
            0,
            0,
            0,
            0,
            false,
            HASH
        );
    }

    private static final class RecordingStore implements ApprovalMigrationEngineExecutionStore {
        private final List<String> order = new ArrayList<>();
        private boolean transactionOpen;
        private FinalizeRequest finalizeRequest;

        @Override
        public PreparedDispatch prepare(PrepareRequest request) {
            transactionOpen = true;
            order.add("prepare");
            ApprovalMigrationAttempt prepared = requestedAttempt(request);
            PreparedDispatch result = new PreparedDispatch(
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                HASH,
                prepared,
                request.expectedFenceRevision(),
                new ProcessInstanceMigrationPort.MigrationCommand(
                    request.tenantId(),
                    prepared.approvalInstanceId(),
                    prepared.attemptId(),
                    prepared.engineInstanceId(),
                    prepared.sourceEngineDefinitionId(),
                    "target-deployment",
                    prepared.targetEngineDefinitionId(),
                    List.of()
                ),
                request.happenedAt(),
                request.requestId(),
                request.traceId()
            );
            transactionOpen = false;
            return result;
        }

        @Override
        public ApprovalMigrationAttempt finalizeOutcome(FinalizeRequest request) {
            assertFalse(transactionOpen);
            transactionOpen = true;
            order.add("finalize");
            finalizeRequest = request;
            ApprovalMigrationAttempt current = request.prepared().attempt();
            ApprovalMigrationAttempt next = switch (request.disposition()) {
                case CALL_RETURNED_AWAITING_VERIFICATION -> current.transitioned(
                    transition(AttemptStatus.VERIFYING, EngineOutcome.ACCEPTED,
                        FailureClass.NONE, null, current.engineRequestReference())
                );
                case AMBIGUOUS_UNKNOWN -> current.transitioned(
                    transition(AttemptStatus.UNKNOWN, EngineOutcome.UNKNOWN,
                        FailureClass.ENGINE_OUTCOME_UNKNOWN, "unknown", current.engineRequestReference())
                );
                case PRE_DISPATCH_REJECTED, ENGINE_REJECTED -> current.transitioned(
                    transition(AttemptStatus.FAILED_TERMINAL, EngineOutcome.REJECTED,
                        FailureClass.ENGINE_REJECTED, "rejected", current.engineRequestReference())
                );
            };
            transactionOpen = false;
            return next;
        }

        private static ApprovalMigrationAttempt requestedAttempt(PrepareRequest request) {
            return new ApprovalMigrationAttempt(
                request.attemptId(),
                request.tenantId(),
                UUID.fromString("00000000-0000-0000-0000-000000000301"),
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                "engine-instance",
                1,
                null,
                HASH,
                "source-definition",
                "target-definition",
                AttemptStatus.ENGINE_REQUESTED,
                EngineOutcome.NOT_REQUESTED,
                request.expectedAttemptRevision() + 1,
                null,
                null,
                "00000000-0000-0000-0000-000000000201",
                FailureClass.NONE,
                null,
                NOW,
                NOW,
                request.requestId(),
                request.traceId()
            );
        }

        private static ApprovalMigrationAttemptTransition transition(
            AttemptStatus status,
            EngineOutcome outcome,
            FailureClass failureClass,
            String error,
            String requestReference
        ) {
            return new ApprovalMigrationAttemptTransition(
                status,
                outcome,
                null,
                null,
                requestReference,
                failureClass,
                error,
                NOW
            );
        }
    }
}
