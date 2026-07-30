package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalMigrationRuntimeBindingCasService.CompletionCommand;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalMigrationRuntimeBindingCasServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T16:00:00Z");

    @Test
    void translatesOneExactCommandToTheStoreWithServerTime() {
        RecordingStore store = new RecordingStore();
        ApprovalMigrationRuntimeBindingCasService service = new ApprovalMigrationRuntimeBindingCasService(
            store,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertNull(service.complete(command()));
        assertEquals("tenant-d5", store.request.tenantId());
        assertEquals(command().attemptId(), store.request.attemptId());
        assertEquals(command().verificationId(), store.request.verificationId());
        assertEquals("worker-d5", store.request.workerId());
        assertEquals(4, store.request.expectedAttemptRevision());
        assertEquals(1, store.request.expectedFenceRevision());
        assertEquals(1, store.request.expectedBindingRevision());
        assertEquals(NOW, store.request.happenedAt());
    }

    @Test
    void oneShotRunnerFailsClosedUnlessExecutionAndWorkerAreEnabled() {
        RecordingStore store = new RecordingStore();
        ApprovalMigrationRuntimeBindingCasService service = new ApprovalMigrationRuntimeBindingCasService(
            store,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        var disabledExecution = new ApprovalMigrationRuntimeBindingCasService.OneShotRunner(
            false,
            true,
            service
        );
        var disabledWorker = new ApprovalMigrationRuntimeBindingCasService.OneShotRunner(
            true,
            false,
            service
        );

        assertThrows(IllegalStateException.class, () -> disabledExecution.runOnce(command()));
        assertThrows(IllegalStateException.class, () -> disabledWorker.runOnce(command()));
        assertNull(store.request);
    }

    private static CompletionCommand command() {
        return new CompletionCommand(
            "tenant-d5",
            UUID.fromString("51000000-0000-0000-0000-000000000001"),
            UUID.fromString("51000000-0000-0000-0000-000000000002"),
            "worker-d5",
            4,
            1,
            1,
            "request-d5",
            "trace-d5"
        );
    }

    private static final class RecordingStore implements ApprovalMigrationRuntimeBindingCasStore {
        private CompletionRequest request;

        @Override
        public BindingCasResult complete(CompletionRequest request) {
            this.request = request;
            return null;
        }
    }
}
