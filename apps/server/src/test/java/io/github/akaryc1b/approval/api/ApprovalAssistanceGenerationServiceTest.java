package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.core.AiAdvisoryAuditSink;
import io.github.akaryc1b.approval.ai.core.AiAdvisoryMetrics;
import io.github.akaryc1b.approval.ai.core.AiAdvisoryService;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidence;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceDurableEvidenceStore;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesAdvisoryProvider;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesTransportException;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskDetails;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskPage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApprovalAssistanceGenerationServiceTest {

    private static final UUID TASK_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000001"
    );
    private static final UUID INSTANCE_ID = UUID.fromString(
        "20000000-0000-0000-0000-000000000001"
    );
    private static final UUID EVIDENCE_ID = UUID.fromString(
        "90000000-0000-0000-0000-000000000001"
    );
    private static final Instant NOW = Instant.parse("2026-08-04T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void disabledRuntimeFailsBeforeTaskQueryOrEvidenceStore() {
        CountingTaskQuery query = new CountingTaskQuery(List.of(task()));
        CountingStore store = new CountingStore(false, false);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ApprovalAssistanceGenerationService service = service(
                query,
                store,
                Optional.empty(),
                executor
            );

            var outcome = service.generate(
                "tenant-a",
                "operator-a",
                "request-a",
                "trace-a",
                TASK_ID,
                UseCase.SUMMARY
            );

            assertEquals(
                ApprovalAssistanceGenerationService.GenerationStatus.DISABLED,
                outcome.status()
            );
            assertEquals(0, query.singleReads.get());
            assertEquals(0, store.writes.get());
        }
    }

    @Test
    void changedTaskSnapshotFailsBeforeRuntimeBinding() {
        PendingTaskDetails changed = taskWithUpdate(NOW.plusSeconds(1));
        CountingTaskQuery query = new CountingTaskQuery(List.of(task(), changed));
        CountingStore store = new CountingStore(false, false);
        OpenAiResponsesProductionRuntimeFactory runtime = mock(
            OpenAiResponsesProductionRuntimeFactory.class
        );

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ApprovalAssistanceGenerationService service = service(
                query,
                store,
                Optional.of(runtime),
                executor
            );

            var outcome = service.generate(
                "tenant-a",
                "operator-a",
                "request-a",
                "trace-a",
                TASK_ID,
                UseCase.SUMMARY
            );

            assertEquals(
                ApprovalAssistanceGenerationService.GenerationStatus.STALE_TASK,
                outcome.status()
            );
            assertEquals(2, query.singleReads.get());
            assertEquals(0, store.writes.get());
            verifyNoInteractions(runtime);
        }
    }

    @Test
    void changedTaskAfterProviderFailsBeforeEvidenceStore() {
        AtomicInteger providerCalls = new AtomicInteger();
        OpenAiResponsesProductionRuntimeFactory runtime = runtime(providerCalls);
        PendingTaskDetails changed = taskWithUpdate(NOW.plusSeconds(1));
        CountingTaskQuery query = new CountingTaskQuery(
            List.of(task(), task(), changed)
        );
        CountingStore store = new CountingStore(false, false);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ApprovalAssistanceGenerationService service = service(
                query,
                store,
                Optional.of(runtime),
                executor
            );

            var outcome = service.generate(
                "tenant-a",
                "operator-a",
                "request-a",
                "trace-a",
                TASK_ID,
                UseCase.SUMMARY
            );

            assertEquals(
                ApprovalAssistanceGenerationService.GenerationStatus.STALE_TASK,
                outcome.status()
            );
            assertEquals(3, query.singleReads.get());
            assertEquals(1, providerCalls.get());
            assertEquals(0, store.writes.get());
        }
    }

    @Test
    void missingTaskAfterProviderFailsBeforeEvidenceStore() {
        AtomicInteger providerCalls = new AtomicInteger();
        OpenAiResponsesProductionRuntimeFactory runtime = runtime(providerCalls);
        CountingTaskQuery query = new CountingTaskQuery(List.of(task(), task()));
        CountingStore store = new CountingStore(false, false);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ApprovalAssistanceGenerationService service = service(
                query,
                store,
                Optional.of(runtime),
                executor
            );

            var outcome = service.generate(
                "tenant-a",
                "operator-a",
                "request-a",
                "trace-a",
                TASK_ID,
                UseCase.SUMMARY
            );

            assertEquals(
                ApprovalAssistanceGenerationService.GenerationStatus.STALE_TASK,
                outcome.status()
            );
            assertEquals(3, query.singleReads.get());
            assertEquals(1, providerCalls.get());
            assertEquals(0, store.writes.get());
        }
    }

    @Test
    void durableConflictDoesNotCauseASecondProviderAttempt() {
        AtomicInteger providerCalls = new AtomicInteger();
        OpenAiResponsesProductionRuntimeFactory runtime = runtime(providerCalls);
        CountingTaskQuery query = new CountingTaskQuery(
            List.of(task(), task(), task())
        );
        CountingStore store = new CountingStore(true, false);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ApprovalAssistanceGenerationService service = service(
                query,
                store,
                Optional.of(runtime),
                executor
            );

            var outcome = service.generate(
                "tenant-a",
                "operator-a",
                "request-a",
                "trace-a",
                TASK_ID,
                UseCase.SUMMARY
            );

            assertEquals(
                ApprovalAssistanceGenerationService.GenerationStatus.EVIDENCE_CONFLICT,
                outcome.status()
            );
            assertEquals(3, query.singleReads.get());
            assertEquals(1, providerCalls.get());
            assertEquals(1, store.writes.get());
        }
    }

    @Test
    void unavailableStoreDoesNotCauseASecondProviderAttempt() {
        AtomicInteger providerCalls = new AtomicInteger();
        OpenAiResponsesProductionRuntimeFactory runtime = runtime(providerCalls);
        CountingTaskQuery query = new CountingTaskQuery(
            List.of(task(), task(), task())
        );
        CountingStore store = new CountingStore(false, true);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ApprovalAssistanceGenerationService service = service(
                query,
                store,
                Optional.of(runtime),
                executor
            );

            var outcome = service.generate(
                "tenant-a",
                "operator-a",
                "request-a",
                "trace-a",
                TASK_ID,
                UseCase.SUMMARY
            );

            assertEquals(
                ApprovalAssistanceGenerationService.GenerationStatus.EVIDENCE_UNAVAILABLE,
                outcome.status()
            );
            assertEquals(3, query.singleReads.get());
            assertEquals(1, providerCalls.get());
            assertEquals(1, store.writes.get());
        }
    }

    private static ApprovalAssistanceGenerationService service(
        ApprovalTaskQuery query,
        ApprovalAssistanceDurableEvidenceStore store,
        Optional<OpenAiResponsesProductionRuntimeFactory> runtime,
        java.util.concurrent.ExecutorService executor
    ) {
        return new ApprovalAssistanceGenerationService(
            query,
            store,
            runtime,
            new AiAdvisoryService(
                executor,
                AiAdvisoryAuditSink.noop(),
                AiAdvisoryMetrics.noop()
            ),
            CLOCK,
            () -> EVIDENCE_ID
        );
    }

    private static OpenAiResponsesProductionRuntimeFactory runtime(
        AtomicInteger providerCalls
    ) {
        OpenAiResponsesAdvisoryProvider provider =
            OpenAiResponsesAdvisoryProvider.production(request -> {
                providerCalls.incrementAndGet();
                throw new OpenAiResponsesTransportException(
                    OpenAiResponsesTransportException.Failure.TIMEOUT
                );
            });
        OpenAiResponsesProductionRuntimeFactory.Binding binding =
            new OpenAiResponsesProductionRuntimeFactory.Binding(
                provider,
                hash("tenant"),
                7,
                hash("kill"),
                hash("cost"),
                hash("secret-version"),
                hash("secret-binding"),
                NOW
            );
        OpenAiResponsesProductionRuntimeFactory runtime = mock(
            OpenAiResponsesProductionRuntimeFactory.class
        );
        when(runtime.bind("tenant-a")).thenReturn(binding);
        return runtime;
    }

    private static PendingTaskDetails task() {
        return taskWithUpdate(NOW);
    }

    private static PendingTaskDetails taskWithUpdate(Instant taskUpdatedAt) {
        return new PendingTaskDetails(
            TASK_ID,
            INSTANCE_ID,
            "purchase-payment",
            3,
            "purchase-payment-form",
            2,
            "compiler-v1",
            "content-hash-v3",
            "managerApproval",
            "部门负责人审批",
            "PAYMENT-2026-0001",
            "initiator-a",
            new BigDecimal("1250.00"),
            "Supplier A",
            "PO-2026-0001",
            List.of("attachment-1"),
            List.of(),
            NOW.minusSeconds(120),
            NOW.minusSeconds(30),
            NOW.minusSeconds(90),
            taskUpdatedAt
        );
    }

    private static String hash(String value) {
        return io.github.akaryc1b.approval.connector.contract.CanonicalPayloadHash
            .sha256Utf8(value);
    }

    private static final class CountingTaskQuery implements ApprovalTaskQuery {

        private final List<PendingTaskDetails> answers;
        private final AtomicInteger singleReads = new AtomicInteger();

        private CountingTaskQuery(List<PendingTaskDetails> answers) {
            this.answers = List.copyOf(answers);
        }

        @Override
        public PendingTaskPage findPendingTasks(PendingTaskCriteria criteria) {
            return new PendingTaskPage(List.of(), 0, criteria.limit(), criteria.offset());
        }

        @Override
        public Optional<PendingTaskDetails> findPendingTask(PendingTaskIdentity identity) {
            int index = singleReads.getAndIncrement();
            if (!identity.tenantId().equals("tenant-a")
                || !identity.assigneeId().equals("operator-a")
                || !identity.taskId().equals(TASK_ID)
                || index >= answers.size()) {
                return Optional.empty();
            }
            return Optional.of(answers.get(index));
        }
    }

    private static final class CountingStore
        implements ApprovalAssistanceDurableEvidenceStore {

        private final boolean conflict;
        private final boolean unavailable;
        private final AtomicInteger writes = new AtomicInteger();

        private CountingStore(boolean conflict, boolean unavailable) {
            this.conflict = conflict;
            this.unavailable = unavailable;
        }

        @Override
        public StoreResult store(ApprovalAssistanceDurableEvidence evidence) {
            writes.incrementAndGet();
            if (unavailable) {
                throw new IllegalStateException("store unavailable");
            }
            return new StoreResult(
                conflict ? StoreDisposition.CONFLICT : StoreDisposition.STORED,
                evidence.evidenceId(),
                1,
                EvidenceState.ACTIVE,
                evidence.evidenceHash(),
                hash("event")
            );
        }

        @Override
        public TombstoneResult tombstone(TombstoneCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EvidenceSnapshot> find(String tenantId, UUID evidenceId) {
            return Optional.empty();
        }
    }
}
