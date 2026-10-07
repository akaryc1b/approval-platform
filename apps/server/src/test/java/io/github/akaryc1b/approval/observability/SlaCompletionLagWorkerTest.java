package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.WorkerMetrics;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.WorkerResult;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.FailureClass;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher.DispatchResult;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import static io.github.akaryc1b.approval.observability.SlaLagFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SlaCompletionLagWorkerTest {
    private final ApprovalSlaExecutionStore store = mock(ApprovalSlaExecutionStore.class);
    private final WorkerMetrics metrics = mock(WorkerMetrics.class);
    private ApprovalSlaExecutionWorker worker(ApprovalSlaActionDispatcher dispatcher) {
        return new ApprovalSlaExecutionWorker(store, dispatcher, metrics, Clock.fixed(FINISH, ZoneOffset.UTC),
            new ApprovalSlaExecutionWorker.Configuration(true, "fixture-worker", 50, Duration.ofSeconds(60),
                Duration.ofSeconds(1), Duration.ofSeconds(10)), UUID::randomUUID);
    }
    private void claim() {
        when(store.claimDue(anyString(), any(), anyInt(), anyString(), any()))
            .thenReturn(List.of(intent(IntentStatus.CLAIMED, false)));
    }
    @Test void completionReceivesThePersistedReturnAfterSuccessNotTheClaimedIntent() {
        claim(); var succeeded = intent(IntentStatus.SUCCEEDED, false);
        when(store.markSucceeded(anyString(), any(), anyLong(), anyString(), any(), any(), any(), any(), anyString(), any()))
            .thenReturn(succeeded);
        assertEquals(1, worker(i -> DispatchResult.succeeded()).processTenant(TENANT).succeeded());
        var order = inOrder(store, metrics);
        order.verify(store).claimDue(anyString(), any(), anyInt(), anyString(), any());
        order.verify(store).markSucceeded(anyString(), any(), anyLong(), anyString(), any(), any(), any(), any(), anyString(), any());
        order.verify(metrics).record(ActionType.OVERDUE, WorkerResult.SUCCEEDED, FailureClass.NONE);
        order.verify(metrics).completed(same(succeeded));
    }
    @Test void counterAndCompletionFaultsDoNotInventConflictOrRetryAndDoNotAbortNextItem() {
        when(store.claimDue(anyString(), any(), anyInt(), anyString(), any())).thenReturn(List.of(
            intent(IntentStatus.CLAIMED, false), intent(IntentStatus.CLAIMED, false, "task", false, ActionType.OVERDUE, FINISH, id(9))));
        when(store.markSucceeded(anyString(), any(), anyLong(), anyString(), any(), any(), any(), any(), anyString(), any()))
            .thenReturn(intent(IntentStatus.SUCCEEDED, false));
        doThrow(mock(ExecutionConflictException.class)).when(metrics).record(any(), any(), any());
        doThrow(mock(ExecutionConflictException.class)).when(metrics).completed(any());
        var result = worker(i -> DispatchResult.succeeded()).processTenant(TENANT);
        assertEquals(2, result.succeeded()); assertEquals(0, result.persistenceConflicts());
        verify(metrics, times(2)).completed(any());
        verify(store, never()).markFailed(anyString(), any(), anyLong(), anyString(), any(), any(), any(), any(),
            anyBoolean(), any(), any(), any(), anyString(), any());
    }
    @Test void aRealPersistenceConflictNeverCallsCompletion() {
        claim();
        when(store.markSucceeded(anyString(), any(), anyLong(), anyString(), any(), any(), any(), any(), anyString(), any()))
            .thenThrow(mock(ExecutionConflictException.class));
        assertEquals(1, worker(i -> DispatchResult.succeeded()).processTenant(TENANT).persistenceConflicts());
        verify(metrics, never()).completed(any());
    }
    @Test void retryAndDeadResultsDoNotCallCompletion() {
        for (IntentStatus status : new IntentStatus[] {IntentStatus.RETRY_WAIT, IntentStatus.DEAD}) {
            reset(store, metrics); claim();
            when(store.markFailed(anyString(), any(), anyLong(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), anyString(), any())).thenReturn(intent(status, false));
            var report = worker(i -> status == IntentStatus.RETRY_WAIT ? DispatchResult.retryableFailure("FIXTURE", "fixture")
                : DispatchResult.permanentFailure("FIXTURE", "fixture")).processTenant(TENANT);
            assertEquals(0, report.succeeded()); verify(metrics, never()).completed(any());
        }
    }
    @Test void unknownStoreFailurePropagatesAndNeverBecomesATimingSample() {
        claim(); var expected = new IllegalStateException("store unavailable");
        when(store.markSucceeded(anyString(), any(), anyLong(), anyString(), any(), any(), any(), any(), anyString(), any()))
            .thenThrow(expected);
        assertSame(expected, assertThrows(IllegalStateException.class, () -> worker(i -> DispatchResult.succeeded()).processTenant(TENANT)));
        verify(metrics, never()).completed(any());
    }
}
