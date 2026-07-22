package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher.DispatchResult;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionStateRecorder;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntent;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalSlaGovernedActionConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");

    @Test
    void overdueRecordsOptimisticStateWithoutCallingFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        RecordingStateRecorder recorder = new RecordingStateRecorder();
        ApprovalSlaActionDispatcher dispatcher = new ApprovalSlaGovernedActionConfiguration()
            .governedApprovalSlaActionDispatcher(
                intent -> {
                    fallbackCalls.incrementAndGet();
                    return DispatchResult.permanentFailure("UNEXPECTED", "fallback invoked");
                },
                recorder,
                Clock.fixed(NOW, ZoneOffset.UTC)
            );

        DispatchResult result = dispatcher.dispatch(intent(ActionType.OVERDUE));

        assertTrue(result.successful());
        assertEquals(0, fallbackCalls.get());
        assertEquals(1, recorder.calls);
        assertEquals(101, recorder.actionSequence);
        assertEquals(NOW, recorder.recordedAt);
    }

    @Test
    void overdueStateConflictsPreserveRetryClassification() {
        ApprovalSlaActionDispatcher dispatcher = new ApprovalSlaGovernedActionConfiguration()
            .governedApprovalSlaActionDispatcher(
                intent -> DispatchResult.succeeded(),
                (tenantId, slaInstanceId, actionSequence, requestId, traceId, recordedAt) -> {
                    throw new ApprovalSlaActionStateRecorder.ActionStateException(
                        "APPROVAL_SLA_ACTION_VERSION_CONFLICT",
                        true,
                        "SLA action sequence changed concurrently"
                    );
                },
                Clock.fixed(NOW, ZoneOffset.UTC)
            );

        DispatchResult result = dispatcher.dispatch(intent(ActionType.OVERDUE));

        assertFalse(result.successful());
        assertTrue(result.retryable());
        assertEquals("APPROVAL_SLA_ACTION_VERSION_CONFLICT", result.errorCode());
    }

    @Test
    void reminderAndUnsupportedActionsReuseExistingFailClosedDispatcher() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        ApprovalSlaActionDispatcher dispatcher = new ApprovalSlaGovernedActionConfiguration()
            .governedApprovalSlaActionDispatcher(
                intent -> {
                    fallbackCalls.incrementAndGet();
                    return DispatchResult.permanentFailure("FALLBACK", "delegated");
                },
                new RecordingStateRecorder(),
                Clock.fixed(NOW, ZoneOffset.UTC)
            );

        assertEquals("FALLBACK", dispatcher.dispatch(intent(ActionType.REMINDER)).errorCode());
        assertEquals("FALLBACK", dispatcher.dispatch(intent(ActionType.ESCALATION)).errorCode());
        assertEquals(2, fallbackCalls.get());
    }

    private static ExecutionIntent intent(ActionType actionType) {
        return new ExecutionIntent(
            UUID.fromString("77000000-0000-0000-0000-000000000001"),
            "tenant-action",
            UUID.fromString("77000000-0000-0000-0000-000000000002"),
            UUID.fromString("77000000-0000-0000-0000-000000000003"),
            UUID.fromString("77000000-0000-0000-0000-000000000004"),
            null,
            UUID.fromString("77000000-0000-0000-0000-000000000005"),
            1,
            null,
            null,
            null,
            actionType,
            actionType == ActionType.OVERDUE ? 101 : 1,
            NOW,
            NOW,
            IntentStatus.CLAIMED,
            "worker-action",
            NOW.plus(Duration.ofMinutes(5)),
            0,
            3,
            NOW,
            "action-idempotency",
            Map.of("dueAt", NOW.toString()),
            "owner-a",
            "request-action",
            "trace-action",
            2,
            NOW.minus(Duration.ofMinutes(1)),
            NOW,
            null,
            null,
            null,
            null,
            null
        );
    }

    private static final class RecordingStateRecorder implements ApprovalSlaActionStateRecorder {
        private int calls;
        private long actionSequence;
        private Instant recordedAt;

        @Override
        public RecordResult recordOverdue(
            String tenantId,
            UUID slaInstanceId,
            long sequence,
            String requestId,
            String traceId,
            Instant occurredAt
        ) {
            calls++;
            actionSequence = sequence;
            recordedAt = occurredAt;
            return RecordResult.RECORDED;
        }
    }
}
