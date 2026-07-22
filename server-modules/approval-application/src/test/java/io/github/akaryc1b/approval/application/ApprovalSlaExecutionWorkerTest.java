package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.Configuration;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.FailureClass;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.WorkerReport;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.WorkerResult;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher.DispatchResult;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionAttempt;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntent;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntentCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntentPage;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.QueueSummary;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ReplayRequest;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ReplayResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalSlaExecutionWorkerTest {

    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");

    @Test
    void disabledWorkerDoesNotClaim() {
        RecordingStore store = new RecordingStore(claimedIntent(0, 3));
        ApprovalSlaExecutionWorker worker = worker(
            store,
            intent -> DispatchResult.succeeded(),
            new MutableClock(NOW),
            configuration(false)
        );

        assertEquals(WorkerReport.empty(), worker.processTenant("tenant-worker"));
        assertEquals(0, store.claimCalls);
    }

    @Test
    void successfulDispatchUsesServerIdempotencyAndCompletesAttempt() {
        RecordingStore store = new RecordingStore(claimedIntent(0, 3));
        MutableClock clock = new MutableClock(NOW);
        List<String> observedKeys = new ArrayList<>();
        List<Metric> metrics = new ArrayList<>();
        ApprovalSlaExecutionWorker worker = worker(
            store,
            intent -> {
                observedKeys.add(intent.idempotencyKey());
                clock.advance(Duration.ofSeconds(2));
                return DispatchResult.succeeded();
            },
            clock,
            configuration(true),
            metrics
        );

        WorkerReport report = worker.processTenant("tenant-worker");

        assertEquals(new WorkerReport(1, 1, 0, 0, 0), report);
        assertEquals(List.of("sla-worker-idempotency"), observedKeys);
        assertEquals(IntentStatus.SUCCEEDED, store.persisted.status());
        assertEquals(NOW.plusSeconds(2), store.persisted.completedAt());
        assertEquals(List.of(new Metric(
            ActionType.REMINDER,
            WorkerResult.SUCCEEDED,
            FailureClass.NONE
        )), metrics);
    }

    @Test
    void retryableFailureUsesDeterministicBoundedBackoff() {
        RecordingStore store = new RecordingStore(claimedIntent(0, 3));
        MutableClock clock = new MutableClock(NOW);
        ApprovalSlaExecutionWorker worker = worker(
            store,
            intent -> {
                clock.advance(Duration.ofSeconds(2));
                return DispatchResult.retryableFailure(
                    "CONNECTOR_TEMPORARY",
                    "temporary connector failure"
                );
            },
            clock,
            configuration(true)
        );

        WorkerReport report = worker.processTenant("tenant-worker");

        assertEquals(new WorkerReport(1, 0, 1, 0, 0), report);
        assertTrue(store.retryable);
        assertEquals(NOW.plusSeconds(12), store.requestedNextAttemptAt);
        assertEquals(IntentStatus.RETRY_WAIT, store.persisted.status());
        assertEquals("CONNECTOR_TEMPORARY", store.errorCode);
    }

    @Test
    void maxAttemptsAndPermanentErrorsBecomeDeadWithoutAnotherRetry() {
        RecordingStore exhausted = new RecordingStore(claimedIntent(2, 3));
        ApprovalSlaExecutionWorker retryingWorker = worker(
            exhausted,
            intent -> DispatchResult.retryableFailure("TEMP", "still unavailable"),
            new MutableClock(NOW),
            configuration(true)
        );

        assertEquals(
            new WorkerReport(1, 0, 0, 1, 0),
            retryingWorker.processTenant("tenant-worker")
        );
        assertNull(exhausted.requestedNextAttemptAt);
        assertEquals(IntentStatus.DEAD, exhausted.persisted.status());

        RecordingStore permanent = new RecordingStore(claimedIntent(0, 3));
        ApprovalSlaExecutionWorker permanentWorker = worker(
            permanent,
            intent -> DispatchResult.permanentFailure(
                "ACTION_NOT_ALLOWED",
                "immutable policy does not allow the action"
            ),
            new MutableClock(NOW),
            configuration(true)
        );
        assertEquals(
            new WorkerReport(1, 0, 0, 1, 0),
            permanentWorker.processTenant("tenant-worker")
        );
        assertFalse(permanent.retryable);
        assertEquals(IntentStatus.DEAD, permanent.persisted.status());
    }

    @Test
    void unexpectedDispatcherExceptionIsSanitizedAndScheduledForRetry() {
        RecordingStore store = new RecordingStore(claimedIntent(0, 3));
        ApprovalSlaExecutionWorker worker = worker(
            store,
            intent -> {
                throw new IllegalStateException("connector\nsecret\tfailed");
            },
            new MutableClock(NOW),
            configuration(true)
        );

        assertEquals(
            new WorkerReport(1, 0, 1, 0, 0),
            worker.processTenant("tenant-worker")
        );
        assertEquals("SLA_ACTION_DISPATCH_EXCEPTION", store.errorCode);
        assertEquals("connector secret failed", store.errorSummary);
        assertFalse(store.errorSummary.contains("\n"));
    }

    @Test
    void unsafeConfigurationFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> new Configuration(
            true,
            "worker",
            0,
            Duration.ofMinutes(5),
            Duration.ofSeconds(10),
            Duration.ofMinutes(5)
        ));
        assertThrows(IllegalArgumentException.class, () -> new Configuration(
            true,
            "worker",
            10,
            Duration.ofHours(2),
            Duration.ofSeconds(10),
            Duration.ofMinutes(5)
        ));
        assertThrows(IllegalArgumentException.class, () -> new Configuration(
            true,
            "worker",
            10,
            Duration.ofMinutes(5),
            Duration.ofMinutes(10),
            Duration.ofMinutes(5)
        ));
    }

    private static ApprovalSlaExecutionWorker worker(
        RecordingStore store,
        io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher dispatcher,
        MutableClock clock,
        Configuration configuration
    ) {
        return worker(store, dispatcher, clock, configuration, new ArrayList<>());
    }

    private static ApprovalSlaExecutionWorker worker(
        RecordingStore store,
        io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher dispatcher,
        MutableClock clock,
        Configuration configuration,
        List<Metric> metrics
    ) {
        AtomicInteger ids = new AtomicInteger();
        return new ApprovalSlaExecutionWorker(
            store,
            dispatcher,
            (action, result, failure) -> metrics.add(new Metric(action, result, failure)),
            clock,
            configuration,
            () -> UUID.fromString(String.format(
                "76000000-0000-0000-0000-%012d",
                ids.incrementAndGet()
            ))
        );
    }

    private static Configuration configuration(boolean enabled) {
        return new Configuration(
            enabled,
            "worker-1",
            10,
            Duration.ofMinutes(5),
            Duration.ofSeconds(10),
            Duration.ofMinutes(5)
        );
    }

    private static ExecutionIntent claimedIntent(int attemptCount, int maxAttempts) {
        return new ExecutionIntent(
            UUID.fromString("75000000-0000-0000-0000-000000000001"),
            "tenant-worker",
            UUID.fromString("75000000-0000-0000-0000-000000000002"),
            UUID.fromString("75000000-0000-0000-0000-000000000003"),
            UUID.fromString("75000000-0000-0000-0000-000000000004"),
            null,
            UUID.fromString("75000000-0000-0000-0000-000000000005"),
            1,
            null,
            null,
            null,
            ActionType.REMINDER,
            1,
            NOW.minus(Duration.ofMinutes(1)),
            NOW.minus(Duration.ofMinutes(1)),
            IntentStatus.CLAIMED,
            "worker-1",
            NOW.plus(Duration.ofMinutes(5)),
            attemptCount,
            maxAttempts,
            NOW.minus(Duration.ofMinutes(1)),
            "sla-worker-idempotency",
            Map.of("schemaVersion", 1),
            "owner-a",
            "request-worker",
            "trace-worker",
            2,
            NOW.minus(Duration.ofMinutes(2)),
            NOW,
            null,
            null,
            null,
            null,
            null
        );
    }

    private record Metric(ActionType action, WorkerResult result, FailureClass failure) {
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static final class RecordingStore implements ApprovalSlaExecutionStore {
        private final ExecutionIntent claimed;
        private int claimCalls;
        private boolean retryable;
        private Instant requestedNextAttemptAt;
        private String errorCode;
        private String errorSummary;
        private ExecutionIntent persisted;

        private RecordingStore(ExecutionIntent claimed) {
            this.claimed = claimed;
        }

        @Override
        public List<ExecutionIntent> claimDue(
            String tenantId,
            Instant now,
            int limit,
            String workerId,
            Instant leaseUntil
        ) {
            claimCalls++;
            return List.of(claimed);
        }

        @Override
        public ExecutionIntent markSucceeded(
            String tenantId,
            UUID intentId,
            long expectedVersion,
            String workerId,
            UUID attemptId,
            Instant claimedAt,
            Instant startedAt,
            Instant finishedAt,
            String requestId,
            String traceId
        ) {
            persisted = transitioned(IntentStatus.SUCCEEDED, finishedAt, null, null);
            return persisted;
        }

        @Override
        public ExecutionIntent markFailed(
            String tenantId,
            UUID intentId,
            long expectedVersion,
            String workerId,
            UUID attemptId,
            Instant claimedAt,
            Instant startedAt,
            Instant finishedAt,
            boolean failureRetryable,
            Instant nextAttemptAt,
            String failureCode,
            String failureSummary,
            String requestId,
            String traceId
        ) {
            retryable = failureRetryable;
            requestedNextAttemptAt = nextAttemptAt;
            errorCode = failureCode;
            errorSummary = failureSummary;
            boolean retryAllowed = failureRetryable
                && claimed.attemptCount() + 1 < claimed.maxAttempts();
            persisted = transitioned(
                retryAllowed ? IntentStatus.RETRY_WAIT : IntentStatus.DEAD,
                finishedAt,
                failureCode,
                failureSummary
            );
            return persisted;
        }

        private ExecutionIntent transitioned(
            IntentStatus status,
            Instant finishedAt,
            String failureCode,
            String failureSummary
        ) {
            return new ExecutionIntent(
                claimed.intentId(),
                claimed.tenantId(),
                claimed.slaInstanceId(),
                claimed.approvalInstanceId(),
                claimed.taskId(),
                claimed.collaborationParticipantId(),
                claimed.policyId(),
                claimed.policyVersion(),
                claimed.calendarId(),
                claimed.calendarVersion(),
                claimed.sourceIntentId(),
                claimed.actionType(),
                claimed.actionSequence(),
                claimed.scheduledAt(),
                claimed.availableAt(),
                status,
                null,
                null,
                claimed.attemptCount() + 1,
                claimed.maxAttempts(),
                requestedNextAttemptAt == null
                    ? claimed.nextAttemptAt()
                    : requestedNextAttemptAt,
                claimed.idempotencyKey(),
                claimed.payload(),
                claimed.responsibleUserId(),
                claimed.requestId(),
                claimed.traceId(),
                claimed.version() + 1,
                claimed.createdAt(),
                finishedAt,
                status == IntentStatus.SUCCEEDED ? finishedAt : null,
                status == IntentStatus.DEAD ? finishedAt : null,
                null,
                failureCode,
                failureSummary
            );
        }

        @Override
        public int enqueue(List<ExecutionIntent> intents) {
            throw unsupported();
        }

        @Override
        public List<String> findRunnableTenants(Instant now, int limit) {
            throw unsupported();
        }

        @Override
        public int cancelActiveForSla(
            String tenantId,
            UUID slaInstanceId,
            Instant cancelledAt,
            String reason
        ) {
            throw unsupported();
        }

        @Override
        public int updateFutureResponsibleUser(
            String tenantId,
            UUID slaInstanceId,
            String responsibleUserId,
            Instant updatedAt
        ) {
            throw unsupported();
        }

        @Override
        public Optional<ExecutionIntent> findIntent(String tenantId, UUID intentId) {
            throw unsupported();
        }

        @Override
        public ExecutionIntentPage findIntents(ExecutionIntentCriteria criteria) {
            throw unsupported();
        }

        @Override
        public List<ExecutionAttempt> findAttempts(String tenantId, UUID intentId) {
            throw unsupported();
        }

        @Override
        public QueueSummary summarize(String tenantId, Instant now) {
            throw unsupported();
        }

        @Override
        public ReplayResult replayDead(ReplayRequest request) {
            throw unsupported();
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by this worker test");
        }
    }
}
