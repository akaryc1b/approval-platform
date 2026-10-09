package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalProcessTimingObserver;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.ProjectionConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real PostgreSQL mutation/RETURNING semantics; input rows use the existing projection APIs. */
@Testcontainers
class JdbcApprovalProcessTimingIntegrationTest {
    private static final String TENANT = "process-timing-tenant";
    private static final Instant START = Instant.parse("2026-09-18T00:00:00.123456789Z");
    private static final Instant END = START.plusSeconds(5400);
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("process_timing_test").withUsername("approval")
        .withPassword(UUID.randomUUID().toString());
    private static DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<Timing> committed = new CopyOnWriteArrayList<>();
    private TransactionTemplate transaction;
    private JdbcApprovalProjectionStore store;
    private final ApprovalProcessTimingObserver observer = (outcome, createdAt, terminalAt) ->
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) committed.add(new Timing(outcome, createdAt, terminalAt));
            }
        });

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void setUp() {
        new JdbcTemplate(dataSource).execute("truncate table ap_approval_task, ap_approval_instance, ap_definition_version cascade");
        transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transaction.setTimeout(10);
        store = new JdbcApprovalProjectionStore(dataSource, mapper, observer);
        store.saveDefinition(new PublishedDefinition(TENANT, "timing", 1, "timing-form", 1,
            "1", "a".repeat(64), "timing-deployment", "timing-engine", 1, "initiator", START));
    }

    @Test
    void immediateTerminalInsertReturnsStoredTimestampPrecisionForEachOutcome() {
        for (InstanceStatus outcome : List.of(InstanceStatus.COMPLETED, InstanceStatus.REJECTED, InstanceStatus.WITHDRAWN)) {
            InstanceProjection instance = instance(outcome);
            int before = committed.size();
            transaction.executeWithoutResult(status -> {
                store.createInstance(instance, List.of());
                assertEquals(before, committed.size());
            });
            assertEquals(before + 1, committed.size());
            assertStoredTiming(instance.instanceId(), committed.getLast());
        }
    }

    @Test
    void successfulCompletionAndRejectionRequireTheOriginalClaimAndOnlyRecordOnce() {
        for (InstanceStatus outcome : List.of(InstanceStatus.COMPLETED, InstanceStatus.REJECTED)) {
            InstanceProjection instance = createRunning();
            TaskProjection task = store.findTasks(TENANT, instance.instanceId()).getFirst();
            int before = committed.size();
            assertThrows(ProjectionConflictException.class, () -> transaction.executeWithoutResult(status ->
                store.completeTaskAndSynchronize(TENANT, instance.instanceId(), task.taskId(), 999, List.of(), outcome, END)));
            assertEquals(before, committed.size());
            transaction.executeWithoutResult(status -> {
                TaskProjection claimed = store.claimPendingTask(TENANT, task.taskId(), "assignee", END);
                store.completeTaskAndSynchronize(TENANT, instance.instanceId(), task.taskId(), claimed.version(), List.of(), outcome, END);
                assertEquals(before, committed.size());
            });
            assertEquals(before + 1, committed.size());
            assertStoredTiming(instance.instanceId(), committed.getLast());
            assertThrows(ProjectionConflictException.class, () -> transaction.executeWithoutResult(status ->
                store.claimPendingTask(TENANT, task.taskId(), "assignee", END)));
            assertEquals(before + 1, committed.size());
        }
    }

    @Test
    void runningAndControlTransitionsAreNotTerminalButWithdrawalIs() {
        InstanceProjection instance = createRunning();
        TaskProjection first = store.findTasks(TENANT, instance.instanceId()).getFirst();
        TaskProjection next = task(instance.instanceId());
        transaction.executeWithoutResult(status -> {
            var claimed = store.claimPendingTask(TENANT, first.taskId(), "assignee", END);
            store.completeTaskAndSynchronize(TENANT, instance.instanceId(), first.taskId(), claimed.version(),
                List.of(next), InstanceStatus.RUNNING, END);
        });
        transaction.executeWithoutResult(status -> {
            var claimed = store.claimPendingTaskForControl(TENANT, next.taskId(), END);
            store.cancelClaimedTaskAndSynchronize(TENANT, instance.instanceId(), next.taskId(), claimed.version(), List.of(), END);
        });
        assertTrue(committed.isEmpty());
        transaction.executeWithoutResult(status -> store.withdrawRunningInstance(TENANT, instance.instanceId(), "initiator", END));
        assertEquals(1, committed.size());
        assertEquals(InstanceStatus.WITHDRAWN, committed.getFirst().outcome());
        assertStoredTiming(instance.instanceId(), committed.getFirst());
        assertTrue(store.findTasks(TENANT, instance.instanceId()).stream()
            .noneMatch(task -> task.status() == TaskStatus.PENDING || task.status() == TaskStatus.COMPLETING));
    }

    @Test
    void rollbackRetainsRunningStateAndReconstructedStoreCanObserveLaterCommit() {
        InstanceProjection instance = createRunning();
        transaction.executeWithoutResult(status -> {
            store.withdrawRunningInstance(TENANT, instance.instanceId(), "initiator", END);
            status.setRollbackOnly();
        });
        assertTrue(committed.isEmpty());
        assertEquals(InstanceStatus.RUNNING, store.findInstance(TENANT, instance.instanceId()).orElseThrow().status());
        var restarted = new JdbcApprovalProjectionStore(dataSource, mapper, observer);
        transaction.executeWithoutResult(status -> restarted.withdrawRunningInstance(TENANT, instance.instanceId(), "initiator", END));
        assertEquals(1, committed.size());
        assertStoredTiming(instance.instanceId(), committed.getFirst());
    }

    @Test
    void crossTenantWrongInitiatorAndMissingInstanceCannotPublishTiming() {
        InstanceProjection instance = createRunning();
        assertThrows(ProjectionConflictException.class, () -> transaction.executeWithoutResult(status ->
            store.withdrawRunningInstance("other-tenant", instance.instanceId(), "initiator", END)));
        assertThrows(ProjectionConflictException.class, () -> transaction.executeWithoutResult(status ->
            store.withdrawRunningInstance(TENANT, instance.instanceId(), "other-user", END)));
        assertThrows(ProjectionConflictException.class, () -> transaction.executeWithoutResult(status ->
            store.withdrawRunningInstance(TENANT, UUID.randomUUID(), "initiator", END)));
        assertTrue(committed.isEmpty());
    }

    @Test
    void concurrentTerminalAttemptsHaveOneWinnerAndOneObservation() throws Exception {
        InstanceProjection instance = createRunning();
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> attempt = () -> {
                ready.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                try {
                    transaction.executeWithoutResult(status -> store.withdrawRunningInstance(TENANT, instance.instanceId(), "initiator", END));
                    return true;
                } catch (ProjectionConflictException conflict) {
                    return false;
                }
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertFalse(first.get(15, TimeUnit.SECONDS).equals(second.get(15, TimeUnit.SECONDS)));
        } finally {
            release.countDown();
        }
        assertEquals(1, committed.size());
        assertStoredTiming(instance.instanceId(), committed.getFirst());
    }

    @Test
    void observerExceptionCannotRollbackInsertionCompletionOrWithdrawal() {
        var broken = new JdbcApprovalProjectionStore(dataSource, mapper, (outcome, createdAt, terminalAt) -> {
            throw new IllegalStateException("controlled observer failure");
        });
        InstanceProjection immediate = instance(InstanceStatus.COMPLETED);
        assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> broken.createInstance(immediate, List.of())));
        assertEquals(InstanceStatus.COMPLETED, store.findInstance(TENANT, immediate.instanceId()).orElseThrow().status());
        InstanceProjection withdrawn = createRunning();
        assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> broken.withdrawRunningInstance(TENANT, withdrawn.instanceId(), "initiator", END)));
        assertEquals(InstanceStatus.WITHDRAWN, store.findInstance(TENANT, withdrawn.instanceId()).orElseThrow().status());
        InstanceProjection completed = createRunning();
        TaskProjection task = store.findTasks(TENANT, completed.instanceId()).getFirst();
        assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> {
            var claimed = broken.claimPendingTask(TENANT, task.taskId(), "assignee", END);
            broken.completeTaskAndSynchronize(TENANT, completed.instanceId(), task.taskId(), claimed.version(), List.of(), InstanceStatus.COMPLETED, END);
        }));
        assertEquals(InstanceStatus.COMPLETED, store.findInstance(TENANT, completed.instanceId()).orElseThrow().status());
        assertTrue(committed.isEmpty());
    }

    @Test
    void existingConstructorRemainsAnUnobservedCompatibleStore() {
        var original = new JdbcApprovalProjectionStore(dataSource, mapper);
        InstanceProjection instance = instance(InstanceStatus.COMPLETED);
        transaction.executeWithoutResult(status -> original.createInstance(instance, List.of()));
        assertEquals(InstanceStatus.COMPLETED, original.findInstance(TENANT, instance.instanceId()).orElseThrow().status());
        assertTrue(committed.isEmpty());
    }

    private InstanceProjection createRunning() {
        InstanceProjection instance = instance(InstanceStatus.RUNNING);
        transaction.executeWithoutResult(status -> store.createInstance(instance, List.of(task(instance.instanceId()))));
        return instance;
    }

    private static InstanceProjection instance(InstanceStatus status) {
        String key = UUID.randomUUID().toString();
        return new InstanceProjection(UUID.randomUUID(), TENANT, key, "engine-" + key,
            "timing", 1, "timing-form", 1, "1", "a".repeat(64), "initiator", BigDecimal.ONE,
            "Timing Supplier", "PO-" + key, List.of(),
            new AssigneeSnapshot("assignee", "reviewer", List.of("finance"), Map.of()),
            "b".repeat(64), status, 1, START, status == InstanceStatus.RUNNING ? START : END);
    }

    private static TaskProjection task(UUID instanceId) {
        return new TaskProjection(UUID.randomUUID(), instanceId, TENANT, UUID.randomUUID().toString(),
            "approval", "Approval", "assignee", TaskStatus.PENDING, 1, START, START, null);
    }

    private void assertStoredTiming(UUID id, Timing timing) {
        InstanceProjection persisted = store.findInstance(TENANT, id).orElseThrow();
        assertEquals(persisted.status(), timing.outcome());
        assertEquals(persisted.createdAt(), timing.createdAt());
        assertEquals(persisted.updatedAt(), timing.terminalAt());
    }

    private record Timing(InstanceStatus outcome, Instant createdAt, Instant terminalAt) {
    }
}
