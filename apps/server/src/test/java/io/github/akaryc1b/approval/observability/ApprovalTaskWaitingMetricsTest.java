package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Savepoint;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Real Spring transaction lifecycle and Micrometer; only JDBC/projection responses are fixtures. */
class ApprovalTaskWaitingMetricsTest {
    private static final Instant CLAIMED = Instant.parse("2026-09-18T06:00:00Z");
    private static final String NAME = "approval.task.waiting.duration";
    private final UUID taskId = UUID.randomUUID();
    private final UUID instanceId = UUID.randomUUID();
    private SimpleMeterRegistry registry;
    private ApprovalBusinessMetrics metrics;
    private ApprovalProjectionStore delegate;
    private ObservedApprovalProjectionStore projections;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws Exception {
        registry = new SimpleMeterRegistry();
        metrics = new ApprovalBusinessMetrics(registry);
        delegate = mock(ApprovalProjectionStore.class);
        projections = new ObservedApprovalProjectionStore(delegate, metrics);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.supportsSavepoints()).thenReturn(true);
        when(connection.setSavepoint(anyString())).thenAnswer(call -> mock(Savepoint.class));
        manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
    }

    @AfterEach
    void close() {
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        registry.close();
    }

    @Test
    void usesPersistedTimestampsAndEmitsOnlyAfterCommitWithoutAdditionalQueries() {
        var task = task(CLAIMED.minus(Duration.ofDays(2)).minusMillis(500), CLAIMED, TaskStatus.COMPLETING);
        // The returned persisted timestamp, not a caller-supplied timing hint, is authoritative.
        Instant supplied = CLAIMED.plusNanos(400);
        when(delegate.claimPendingTask("tenant", taskId, "actor", supplied)).thenReturn(task);
        transaction.executeWithoutResult(status -> {
            assertSame(task, projections.claimPendingTask("tenant", taskId, "actor", supplied));
            assertNull(registry.find(NAME).timer());
        });
        assertEquals(1, count());
        assertEquals(172800.5d, registry.get(NAME).timer().totalTime(TimeUnit.SECONDS), 0.000001d);
        assertTrue(registry.get(NAME).timer().getId().getTags().isEmpty());
        verify(delegate).claimPendingTask("tenant", taskId, "actor", supplied);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void zeroDurationIsValidButLongWaitIsNotClampedToTheHistogramRange() {
        transaction.executeWithoutResult(status -> {
            metrics.taskClaimed(task(CLAIMED, CLAIMED, TaskStatus.COMPLETING));
            metrics.taskClaimed(task(CLAIMED.minus(Duration.ofDays(120)), CLAIMED, TaskStatus.COMPLETING));
        });
        assertEquals(2, count());
        assertEquals(Duration.ofDays(120).toSeconds(), registry.get(NAME).timer().totalTime(TimeUnit.SECONDS), 0.001d);
    }

    @Test
    void rollbackThenRetryRecordsOnlyTheCommittedClaim() {
        var task = task(CLAIMED.minusSeconds(60), CLAIMED, TaskStatus.COMPLETING);
        when(delegate.claimPendingTask("tenant", taskId, "actor", CLAIMED)).thenReturn(task);
        transaction.executeWithoutResult(status -> { claim(); status.setRollbackOnly(); });
        assertEquals(0, count());
        transaction.executeWithoutResult(status -> claim());
        assertEquals(1, count());
        assertEquals(60.0d, registry.get(NAME).timer().totalTime(TimeUnit.SECONDS));
    }

    @Test
    void rejectedClaimPreservesTheOriginalConflictAndRecordsNothing() {
        var failure = new ApprovalProjectionStore.ProjectionConflictException("private fixture detail");
        when(delegate.claimPendingTask("tenant", taskId, "actor", CLAIMED)).thenThrow(failure);
        assertSame(failure, assertThrows(ApprovalProjectionStore.ProjectionConflictException.class,
            () -> transaction.executeWithoutResult(status -> claim())));
        assertEquals(0, count());
        assertEquals(0, metrics.droppedObservations());
    }

    @Test
    void controlClaimsTransfersAndCancellationDoNotPretendToBeNormalTaskWaits() {
        var task = task(CLAIMED.minusSeconds(60), CLAIMED, TaskStatus.COMPLETING);
        when(delegate.claimPendingTaskForControl("tenant", taskId, CLAIMED)).thenReturn(task);
        when(delegate.transferPendingTask("tenant", taskId, "actor", "other", CLAIMED)).thenReturn(task);
        transaction.executeWithoutResult(status -> {
            assertSame(task, projections.claimPendingTaskForControl("tenant", taskId, CLAIMED));
            assertSame(task, projections.transferPendingTask("tenant", taskId, "actor", "other", CLAIMED));
            projections.cancelClaimedTaskAndSynchronize("tenant", instanceId, taskId, 2, List.of(), CLAIMED);
        });
        assertEquals(0, count());
        assertEquals(0, metrics.droppedObservations());
    }

    @Test
    void nontransactionalClaimReturnsNormallyButDropsTheObservation() {
        var task = task(CLAIMED.minusSeconds(60), CLAIMED, TaskStatus.COMPLETING);
        when(delegate.claimPendingTask("tenant", taskId, "actor", CLAIMED)).thenReturn(task);
        assertSame(task, claim());
        assertEquals(0, count());
        assertEquals(1, metrics.droppedObservations());
    }

    @Test
    void nullDelegateResultIsReturnedUnchangedRatherThanBecomingATelemetryError() {
        transaction.executeWithoutResult(status -> assertNull(claim()));
        assertEquals(0, count());
        assertEquals(1, metrics.droppedObservations());
    }

    @Test
    void invalidTimestampsAndNonClaimStatesAreDroppedRatherThanConvertedToZero() {
        transaction.executeWithoutResult(status -> {
            metrics.taskClaimed(task(CLAIMED.plusSeconds(1), CLAIMED, TaskStatus.COMPLETING));
            metrics.taskClaimed(task(null, CLAIMED, TaskStatus.COMPLETING));
            metrics.taskClaimed(task(CLAIMED, null, TaskStatus.COMPLETING));
            metrics.taskClaimed(task(Instant.MIN, Instant.MAX, TaskStatus.COMPLETING));
            for (TaskStatus state : List.of(TaskStatus.PENDING, TaskStatus.COMPLETED, TaskStatus.CANCELED)) {
                metrics.taskClaimed(task(CLAIMED.minusSeconds(1), CLAIMED, state));
            }
        });
        assertEquals(0, count());
        assertEquals(7, metrics.droppedObservations());
    }

    @Test
    void registryFailureCannotChangeTheSuccessfulClaimOrTransaction() {
        var task = task(CLAIMED.minusSeconds(60), CLAIMED, TaskStatus.COMPLETING);
        when(delegate.claimPendingTask("tenant", taskId, "actor", CLAIMED)).thenReturn(task);
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                throw new IllegalStateException("private registry failure");
            }
        });
        assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> assertSame(task, claim())));
        assertEquals(1, metrics.droppedObservations());
    }

    @Test
    void nestedRollbackDropsOnlyTheNestedClaim() {
        var nested = new TransactionTemplate(manager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        transaction.executeWithoutResult(status -> {
            metrics.taskClaimed(task(CLAIMED.minusSeconds(10), CLAIMED, TaskStatus.COMPLETING));
            assertThrows(IllegalStateException.class, () -> nested.executeWithoutResult(inner -> {
                metrics.taskClaimed(task(CLAIMED.minusSeconds(20), CLAIMED, TaskStatus.COMPLETING));
                throw new IllegalStateException("nested rollback");
            }));
        });
        assertEquals(1, count());
        assertEquals(10.0d, registry.get(NAME).timer().totalTime(TimeUnit.SECONDS));
    }

    @Test
    void repeatedCompletionIsOnceOnlyAndUnknownCompletionIsNotSuccess() {
        transaction.executeWithoutResult(status -> {
            metrics.taskClaimed(task(CLAIMED.minusSeconds(10), CLAIMED, TaskStatus.COMPLETING));
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        });
        assertEquals(1, count());
        transaction.executeWithoutResult(status -> {
            metrics.taskClaimed(task(CLAIMED.minusSeconds(20), CLAIMED, TaskStatus.COMPLETING));
            TransactionSynchronizationManager.getSynchronizations().forEach(callback ->
                callback.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN));
        });
        assertEquals(1, count());
    }

    @Test
    void cachedIdempotencyResponseDoesNotClaimOrMeasureAgain() {
        IdempotencyGuard cached = new IdempotencyGuard() {
            @Override
            public <T> T execute(RequestContext context, String operation, String hash, Class<T> type,
                Supplier<T> action) {
                return type.cast("cached");
            }
        };
        var observed = new ObservedIdempotencyGuard(cached, metrics);
        assertEquals("cached", observed.execute(new RequestContext("tenant", "actor", "request", "key", "trace"),
            "purchase-payment.approve.v1", "hash", String.class, () -> { claim(); return "new"; }));
        verifyNoInteractions(delegate);
        assertEquals(0, count());
    }

    private TaskProjection task(Instant created, Instant updated, TaskStatus status) {
        return new TaskProjection(taskId, instanceId, "tenant", "engine-task", "managerApproval",
            "Approval", "actor", status, 2, created, updated, null);
    }

    private TaskProjection claim() {
        return projections.claimPendingTask("tenant", taskId, "actor", CLAIMED);
    }

    private long count() {
        Timer timer = registry.find(NAME).timer();
        return timer == null ? 0 : timer.count();
    }
}
