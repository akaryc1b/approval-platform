package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real transaction synchronization and Micrometer; database responses are controlled here. */
class ApprovalProcessDurationMetricsTest {
    private static final Instant START = Instant.parse("2026-09-18T00:00:00Z");
    private SimpleMeterRegistry registry;
    private ApprovalBusinessMetrics metrics;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws Exception {
        registry = new SimpleMeterRegistry();
        metrics = new ApprovalBusinessMetrics(registry);
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
    void publishesPersistedDurationOnlyAfterCommit() {
        transaction.executeWithoutResult(status -> {
            metrics.processTerminated(InstanceStatus.COMPLETED, START, START.plusSeconds(5400));
            assertEquals(0, count("completed"));
        });
        assertEquals(1, count("completed"));
        assertEquals(5400.0d, timer("completed").totalTime(TimeUnit.SECONDS));
        assertTrue(registry.find("approval.process.completed").meters().isEmpty(),
            "the observer must not duplicate the existing lifecycle counters");
    }

    @Test
    void terminalOutcomesHaveExactlyThreeBoundedLabels() {
        registry.config().commonTags("application", "approval-platform");
        transaction.executeWithoutResult(status -> {
            metrics.processTerminated(InstanceStatus.COMPLETED, START, START.plusSeconds(1));
            metrics.processTerminated(InstanceStatus.REJECTED, START, START.plusSeconds(2));
            metrics.processTerminated(InstanceStatus.WITHDRAWN, START, START.plusSeconds(3));
        });
        assertEquals(3, registry.find("approval.process.duration").timers().size());
        Set<String> outcomes = Set.of("completed", "rejected", "withdrawn");
        for (Timer timer : registry.find("approval.process.duration").timers()) {
            assertEquals(2, timer.getId().getTags().size());
            assertEquals("approval-platform", timer.getId().getTag("application"));
            assertTrue(outcomes.contains(timer.getId().getTag("outcome")));
        }
        assertTrue(registry.find("approval.process.failed").meters().isEmpty());
    }

    @Test
    void runningTransitionsProduceNoTerminalSample() {
        metrics.processTerminated(InstanceStatus.RUNNING, START, START.plusSeconds(5));
        assertTrue(registry.find("approval.process.duration").meters().isEmpty());
        assertEquals(0, metrics.droppedObservations());
    }

    @Test
    void rolledBackTerminalWriteProducesNoSample() {
        transaction.executeWithoutResult(status -> {
            metrics.processTerminated(InstanceStatus.REJECTED, START, START.plusSeconds(5));
            status.setRollbackOnly();
        });
        assertEquals(0, count("rejected"));
    }

    @Test
    void nontransactionalObservationIsNotMisrepresentedAsCommitted() {
        metrics.processTerminated(InstanceStatus.WITHDRAWN, START, START.plusSeconds(5));
        assertEquals(0, count("withdrawn"));
        assertEquals(1, metrics.droppedObservations());
    }

    @Test
    void invalidTimeOrOutcomeIsDroppedRatherThanZero() {
        transaction.executeWithoutResult(status -> {
            metrics.processTerminated(InstanceStatus.COMPLETED, START, START.minusNanos(1));
            metrics.processTerminated(InstanceStatus.COMPLETED, null, START);
            metrics.processTerminated(InstanceStatus.COMPLETED, START, null);
            metrics.processTerminated(null, START, START);
            metrics.processTerminated(InstanceStatus.COMPLETED, Instant.MIN, Instant.MAX);
        });
        assertEquals(5, metrics.droppedObservations());
        assertEquals(0, count("completed"));
    }

    @Test
    void immediateTerminationAndLongValidProcessesAreNotClamped() {
        transaction.executeWithoutResult(status -> {
            metrics.processTerminated(InstanceStatus.COMPLETED, START, START);
            metrics.processTerminated(InstanceStatus.COMPLETED, START, START.plus(Duration.ofDays(120)));
        });
        assertEquals(2, count("completed"));
        assertEquals(Duration.ofDays(120).toSeconds(), timer("completed").totalTime(TimeUnit.SECONDS));
    }

    @Test
    void registryFailureCannotBecomeATransactionFailure() {
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                throw new IllegalStateException("controlled telemetry failure");
            }
        });
        assertDoesNotThrow(() -> transaction.executeWithoutResult(status ->
            metrics.processTerminated(InstanceStatus.COMPLETED, START, START.plusSeconds(10))));
        assertEquals(1, metrics.droppedObservations());
    }

    @Test
    void savepointRollbackDropsOnlyTheNestedTerminalObservation() {
        var nested = new TransactionTemplate(manager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        transaction.executeWithoutResult(status -> {
            metrics.processTerminated(InstanceStatus.COMPLETED, START, START.plusSeconds(10));
            assertThrows(IllegalStateException.class, () -> nested.executeWithoutResult(inner -> {
                metrics.processTerminated(InstanceStatus.REJECTED, START, START.plusSeconds(20));
                throw new IllegalStateException("rollback nested scope");
            }));
        });
        assertEquals(1, count("completed"));
        assertEquals(0, count("rejected"));
    }

    @Test
    void repeatedCompletionCallbackCannotDoubleCount() {
        transaction.executeWithoutResult(status -> {
            metrics.processTerminated(InstanceStatus.COMPLETED, START, START.plusSeconds(10));
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        });
        assertEquals(1, count("completed"));
    }

    @Test
    void unknownCompletionCannotLaterBecomeSuccess() {
        transaction.executeWithoutResult(status -> {
            metrics.processTerminated(InstanceStatus.COMPLETED, START, START.plusSeconds(10));
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN));
            callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        });
        assertEquals(0, count("completed"));
    }

    @Test
    void elapsedTimeDoesNotDependOnProcessLocalStartTracking() {
        var restarted = new ApprovalBusinessMetrics(registry);
        transaction.executeWithoutResult(status -> restarted.processTerminated(
            InstanceStatus.COMPLETED, START.minus(Duration.ofDays(30)), START));
        assertEquals(1, count("completed"));
        assertEquals(Duration.ofDays(30).toSeconds(), timer("completed").totalTime(TimeUnit.SECONDS));
    }

    private Timer timer(String outcome) {
        return registry.find("approval.process.duration").tags("outcome", outcome).timer();
    }

    private long count(String outcome) {
        Timer timer = timer(outcome);
        return timer == null ? 0 : timer.count();
    }
}
