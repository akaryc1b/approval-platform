package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.micrometer.core.instrument.Counter;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Real Spring transaction lifecycle and Micrometer; JDBC and projection responses are fixtures. */
class ApprovalBusinessMetricsTest {

    private SimpleMeterRegistry registry;
    private ApprovalBusinessMetrics metrics;
    private ApprovalProjectionStore delegate;
    private ObservedApprovalProjectionStore projections;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transaction;
    private InstanceProjection instance;
    private final RequestContext context = new RequestContext("tenant", "user", "request", "key", "trace");

    @BeforeEach
    void setUp() throws Exception {
        registry = new SimpleMeterRegistry();
        metrics = new ApprovalBusinessMetrics(registry);
        delegate = mock(ApprovalProjectionStore.class);
        projections = new ObservedApprovalProjectionStore(delegate, metrics);
        instance = mock(InstanceProjection.class);
        when(instance.status()).thenReturn(InstanceStatus.RUNNING);
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
    void emitsStartsOnlyAfterActualCommit() {
        transaction.executeWithoutResult(status -> {
            projections.createInstance(instance, List.of());
            assertEquals(0.0d, count("approval.process.started"));
        });
        assertEquals(1.0d, count("approval.process.started"));
        assertEquals(0.0d, count("approval.process.completed"));
        verify(delegate).createInstance(instance, List.of());
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void aTaskCompletionIsNotAlwaysAProcessCompletion() {
        transaction.executeWithoutResult(status -> {
            complete(InstanceStatus.RUNNING);
            complete(InstanceStatus.COMPLETED);
        });
        assertEquals(2.0d, count("approval.task.completed"));
        assertEquals(1.0d, count("approval.process.completed"));
    }

    @Test
    void immediateCompletionCountsOneStartAndOneCompletion() {
        when(instance.status()).thenReturn(InstanceStatus.COMPLETED);
        transaction.executeWithoutResult(status -> projections.createInstance(instance, List.of()));
        assertEquals(1.0d, count("approval.process.started"));
        assertEquals(1.0d, count("approval.process.completed"));
        assertEquals(0.0d, count("approval.task.completed"));
    }

    @Test
    void withdrawalAndTerminalRejectionRemainDistinctFromExecutionFailure() {
        transaction.executeWithoutResult(status -> {
            projections.withdrawRunningInstance("tenant", UUID.randomUUID(), "user", Instant.now());
            complete(InstanceStatus.REJECTED);
        });
        assertEquals(1.0d, count("approval.process.withdrawn"));
        assertEquals(1.0d, count("approval.process.rejected"));
        assertEquals(0.0d, count("approval.process.completed"));
        assertTrue(registry.find("approval.process.failed").meters().isEmpty());
    }

    @Test
    void rolledBackStateChangesEmitNoLifecycleSuccess() {
        transaction.executeWithoutResult(status -> {
            projections.createInstance(instance, List.of());
            complete(InstanceStatus.COMPLETED);
            status.setRollbackOnly();
        });
        assertEquals(0.0d, count("approval.process.started"));
        assertEquals(0.0d, count("approval.process.completed"));
        assertEquals(0.0d, count("approval.task.completed"));
    }

    @Test
    void failedWritesPreserveTheOriginalExceptionAndEmitNoSuccess() {
        RuntimeException failure = new IllegalStateException("private failure details");
        doThrow(failure).when(delegate).createInstance(instance, List.of());
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> transaction.executeWithoutResult(status -> projections.createInstance(instance, List.of()))));
        assertEquals(0.0d, count("approval.process.started"));
    }

    @Test
    void nontransactionalWritesAreNotMisrepresentedAsCommitted() {
        projections.createInstance(instance, List.of());
        assertEquals(0.0d, count("approval.process.started"));
        assertEquals(1, metrics.droppedObservations());
        verify(delegate).createInstance(instance, List.of());
    }

    @Test
    void commandTimerFollowsCommitAndDoesNotContainRequestIdentity() {
        ObservedIdempotencyGuard guard = new ObservedIdempotencyGuard(transactionalGuard(), metrics);
        assertEquals("ok", guard.execute(context, "purchase-payment.approve.v1", "hash", String.class, () -> {
            assertEquals(0, timed("approve", "committed"));
            return "ok";
        }));
        assertEquals(1, timed("approve", "committed"));
        for (Meter meter : registry.getMeters()) {
            for (var tag : meter.getId().getTags()) {
                assertTrue(List.of("operation", "outcome").contains(tag.getKey()));
                assertTrue(List.of("approve", "committed").contains(tag.getValue()));
            }
        }
    }

    @Test
    void commandFailurePropagatesAndIsMeasuredAsRolledBackNotProcessFailed() {
        ObservedIdempotencyGuard guard = new ObservedIdempotencyGuard(transactionalGuard(), metrics);
        RuntimeException failure = new IllegalStateException("private failure details");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> guard.execute(context, "purchase-payment.approve.v1", "hash", String.class, () -> {
                throw failure;
            })));
        assertEquals(1, timed("approve", "rolled_back"));
        assertEquals(0, timed("approve", "committed"));
    }

    @Test
    void idempotentReplayDoesNotInvokeOrMeasureTheAction() {
        IdempotencyGuard cached = new IdempotencyGuard() {
            @Override
            public <T> T execute(
                RequestContext input, String operation, String hash, Class<T> type, Supplier<T> action
            ) {
                return type.cast("cached");
            }
        };
        var guard = new ObservedIdempotencyGuard(cached, metrics);
        assertEquals("cached", guard.execute(context, "purchase-payment.start.v1", "hash", String.class, () -> {
            throw new AssertionError("replayed action must not execute");
        }));
        assertEquals(0, timed("start", "committed"));
        assertEquals(0, metrics.droppedObservations());
    }

    @Test
    void unknownOperationDoesNotCreateAnUnboundedMetricLabel() {
        var guard = new ObservedIdempotencyGuard(transactionalGuard(), metrics);
        assertEquals("ok", guard.execute(context, "customer-supplied-unique-operation", "hash",
            String.class, () -> "ok"));
        assertTrue(registry.find("approval.command.duration").meters().isEmpty());
    }

    @Test
    void aMetricsFailureCannotTurnACommittedWriteIntoAnApprovalError() {
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                throw new IllegalStateException("private registry failure");
            }
        });
        assertDoesNotThrow(() -> transaction.executeWithoutResult(
            status -> projections.createInstance(instance, List.of())));
        assertEquals(1, metrics.droppedObservations());
    }

    @Test
    void nestedSavepointRollbackDoesNotPublishTheNestedMutation() {
        var nested = new TransactionTemplate(manager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        transaction.executeWithoutResult(status -> {
            projections.createInstance(instance, List.of());
            assertThrows(IllegalStateException.class, () -> nested.executeWithoutResult(inner -> {
                projections.createInstance(instance, List.of());
                throw new IllegalStateException("rollback nested scope");
            }));
        });
        assertEquals(1.0d, count("approval.process.started"));
    }

    @Test
    void duplicateCompletionCallbacksCannotDoubleCountAndUnknownIsNotSuccess() {
        transaction.executeWithoutResult(status -> {
            projections.createInstance(instance, List.of());
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN));
            callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        });
        assertEquals(0.0d, count("approval.process.started"));
    }

    private IdempotencyGuard transactionalGuard() {
        return new IdempotencyGuard() {
            @Override
            public <T> T execute(
                RequestContext input, String operation, String hash, Class<T> type, Supplier<T> action
            ) {
                return transaction.execute(status -> action.get());
            }
        };
    }

    private void complete(InstanceStatus status) {
        projections.completeTaskAndSynchronize("tenant", UUID.randomUUID(), UUID.randomUUID(),
            2, List.of(), status, Instant.now());
    }

    private double count(String name) {
        Counter counter = registry.find(name).counter();
        return counter == null ? 0.0d : counter.count();
    }

    private long timed(String operation, String outcome) {
        Timer timer = registry.find("approval.command.duration")
            .tags("operation", operation, "outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }
}
