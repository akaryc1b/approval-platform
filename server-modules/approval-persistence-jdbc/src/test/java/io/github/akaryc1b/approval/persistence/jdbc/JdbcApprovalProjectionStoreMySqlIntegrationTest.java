package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.ProjectionConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.UserIdentitySnapshot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalProjectionStoreMySqlIntegrationTest {

    private static final String TENANT = "Tenant-Projection-MySQL";
    private static final String OTHER_TENANT = "tenant-projection-other";
    private static final String DEFINITION_KEY = "purchase-payment";
    private static final Instant CREATED_AT = Instant.parse(
        "2026-08-08T01:02:03.123456Z"
    );
    private static final Instant CHANGED_AT = Instant.parse(
        "2026-08-08T01:03:04.654321Z"
    );
    private static final UUID INSTANCE_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000008501"
    );
    private static final UUID OTHER_INSTANCE_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000008502"
    );
    private static final UUID THIRD_INSTANCE_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000008503"
    );
    private static final UUID TASK_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000008511"
    );
    private static final UUID OTHER_TASK_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000008512"
    );
    private static final UUID NEXT_TASK_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000008513"
    );

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_projection")
        .withUsername("approval")
        .withPassword("approval")
        .withCommand(
            "--default-time-zone=+00:00",
            "--character-set-server=utf8mb4",
            "--collation-server=utf8mb4_0900_as_cs",
            "--transaction-isolation=READ-COMMITTED",
            "--innodb-strict-mode=ON",
            "--sql-mode=STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,"
                + "NO_ENGINE_SUBSTITUTION"
        );

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private ObjectMapper objectMapper;
    private ApprovalProjectionStore store;
    private TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
            configuredJdbcUrl(),
            MYSQL.getUsername(),
            MYSQL.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/mysqlmigration")
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .load()
            .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void reset() {
        jdbc.update("delete from ap_approval_task");
        jdbc.update("delete from ap_approval_instance");
        jdbc.update("delete from ap_definition_version");
        objectMapper = new ObjectMapper().findAndRegisterModules();
        store = JdbcApprovalProjectionStoreFactory.create(dataSource, objectMapper);
        transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }

    @Test
    void selectsMySqlFromTrustedMetadataAndRejectsMutationWithoutTransaction() {
        assertInstanceOf(JdbcMySqlApprovalProjectionStore.class, store);

        assertThrows(
            IllegalStateException.class,
            () -> store.lockBusinessKey(TENANT, "business-no-transaction")
        );
        assertThrows(
            IllegalStateException.class,
            () -> store.createInstance(
                instance(INSTANCE_ID, "engine-no-transaction", "business-no-transaction"),
                List.of()
            )
        );
    }

    @Test
    void roundTripsDefinitionInstanceTaskJsonUuidAndMicrosecondTime() {
        PublishedDefinition definition = definition(TENANT);
        InstanceProjection instance = instance(
            INSTANCE_ID,
            "engine-instance-round-trip",
            "business-round-trip"
        );
        TaskProjection task = task(
            TASK_ID,
            INSTANCE_ID,
            "engine-task-round-trip",
            "Manager-A",
            TaskStatus.PENDING,
            1,
            CREATED_AT
        );

        inTransaction(() -> {
            store.lockDefinition(TENANT, DEFINITION_KEY, 1);
            store.saveDefinition(definition);
            store.lockBusinessKey(TENANT, instance.businessKey());
            store.createInstance(instance, List.of(task));
        });

        assertEquals(
            definition,
            store.findDefinition(TENANT, DEFINITION_KEY, 1).orElseThrow()
        );
        assertEquals(instance, store.findInstance(TENANT, INSTANCE_ID).orElseThrow());
        assertEquals(instance, store.findByBusinessKey(
            TENANT,
            instance.businessKey()
        ).orElseThrow());
        assertEquals(List.of(task), store.findTasks(TENANT, INSTANCE_ID));
        assertEquals(task, store.findTask(TENANT, TASK_ID).orElseThrow());
        assertFalse(store.findInstance(OTHER_TENANT, INSTANCE_ID).isPresent());
        assertFalse(store.findTask(OTHER_TENANT, TASK_ID).isPresent());
    }

    @Test
    void businessKeyLockSerializesFirstCreationUntilCommit() throws Exception {
        saveDefinition();
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        AtomicInteger sideEffects = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> inTransaction(() -> {
                store.lockBusinessKey(TENANT, "business-serialized");
                firstLocked.countDown();
                await(releaseFirst);
                if (store.findByBusinessKey(TENANT, "business-serialized").isPresent()) {
                    return false;
                }
                sideEffects.incrementAndGet();
                store.createInstance(
                    instance(
                        INSTANCE_ID,
                        "engine-instance-serialized-first",
                        "business-serialized"
                    ),
                    List.of()
                );
                return true;
            }));
            Future<Boolean> second = executor.submit(() -> {
                await(firstLocked);
                secondAttempting.countDown();
                return inTransaction(() -> {
                    store.lockBusinessKey(TENANT, "business-serialized");
                    if (store.findByBusinessKey(
                        TENANT,
                        "business-serialized"
                    ).isPresent()) {
                        return false;
                    }
                    sideEffects.incrementAndGet();
                    store.createInstance(
                        instance(
                            OTHER_INSTANCE_ID,
                            "engine-instance-serialized-second",
                            "business-serialized"
                        ),
                        List.of()
                    );
                    return true;
                });
            });

            assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
            assertTrue(secondAttempting.await(10, TimeUnit.SECONDS));
            assertThrows(
                TimeoutException.class,
                () -> second.get(300, TimeUnit.MILLISECONDS)
            );
            releaseFirst.countDown();

            assertTrue(first.get(20, TimeUnit.SECONDS));
            assertFalse(second.get(20, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
        }

        assertEquals(1, sideEffects.get());
        assertEquals(1, count(
            "select count(*) from ap_approval_instance "
                + "where tenant_id=? and business_key=?",
            TENANT,
            "business-serialized"
        ));
    }

    @Test
    void rollbackReleasesBusinessLockAndLeavesNoProjection() {
        saveDefinition();

        assertThrows(RollbackMarker.class, () -> inTransaction(() -> {
            store.lockBusinessKey(TENANT, "business-rollback");
            throw new RollbackMarker();
        }));

        inTransaction(() -> {
            store.lockBusinessKey(TENANT, "business-rollback");
            store.createInstance(
                instance(
                    INSTANCE_ID,
                    "engine-instance-after-rollback",
                    "business-rollback"
                ),
                List.of()
            );
        });

        assertEquals(1, count(
            "select count(*) from ap_approval_instance "
                + "where tenant_id=? and business_key=?",
            TENANT,
            "business-rollback"
        ));
    }

    @Test
    void concurrentTaskClaimHasExactlyOneWinnerAndExactVersion() throws Exception {
        seedInstanceWithTasks(
            instance(INSTANCE_ID, "engine-instance-claim", "business-claim"),
            List.of(task(
                TASK_ID,
                INSTANCE_ID,
                "engine-task-claim",
                "Manager-A",
                TaskStatus.PENDING,
                1,
                CREATED_AT
            ))
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> claimConcurrently(ready, start));
            Future<Object> second = executor.submit(() -> claimConcurrently(ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            Object firstResult = first.get(20, TimeUnit.SECONDS);
            Object secondResult = second.get(20, TimeUnit.SECONDS);
            assertNotEquals(firstResult.getClass(), secondResult.getClass());
            TaskProjection claimed = firstResult instanceof TaskProjection task
                ? task
                : (TaskProjection) secondResult;
            assertTrue(firstResult instanceof ProjectionConflictException
                || secondResult instanceof ProjectionConflictException);
            assertEquals(TaskStatus.COMPLETING, claimed.status());
            assertEquals(2, claimed.version());
            assertEquals(CHANGED_AT, claimed.updatedAt());
        } finally {
            start.countDown();
        }

        TaskProjection stored = store.findTask(TENANT, TASK_ID).orElseThrow();
        assertEquals(TaskStatus.COMPLETING, stored.status());
        assertEquals(2, stored.version());
    }

    @Test
    void transferCompletionAndActiveTaskSynchronizationPreserveCas() {
        seedInstanceWithTasks(
            instance(INSTANCE_ID, "engine-instance-cas", "business-cas"),
            List.of(task(
                TASK_ID,
                INSTANCE_ID,
                "engine-task-cas",
                "Manager-A",
                TaskStatus.PENDING,
                1,
                CREATED_AT
            ))
        );

        TaskProjection transferred = inTransaction(() -> store.transferPendingTask(
            TENANT,
            TASK_ID,
            "Manager-A",
            "Manager-B",
            CHANGED_AT
        ));
        assertEquals("Manager-B", transferred.assigneeId());
        assertEquals(TaskStatus.PENDING, transferred.status());
        assertEquals(2, transferred.version());

        TaskProjection claimed = inTransaction(() -> store.claimPendingTask(
            TENANT,
            TASK_ID,
            "Manager-B",
            CHANGED_AT.plusSeconds(1)
        ));
        assertEquals(3, claimed.version());

        TaskProjection next = task(
            NEXT_TASK_ID,
            INSTANCE_ID,
            "engine-task-next",
            "Finance-A",
            TaskStatus.PENDING,
            1,
            CHANGED_AT.plusSeconds(2)
        );
        assertThrows(ProjectionConflictException.class, () -> inTransaction(() ->
            store.completeTaskAndSynchronize(
                TENANT,
                INSTANCE_ID,
                TASK_ID,
                claimed.version() - 1,
                List.of(next),
                InstanceStatus.RUNNING,
                CHANGED_AT.plusSeconds(2)
            )
        ));
        assertFalse(store.findTask(TENANT, NEXT_TASK_ID).isPresent());
        assertEquals(
            TaskStatus.COMPLETING,
            store.findTask(TENANT, TASK_ID).orElseThrow().status()
        );
        assertEquals(1, store.findInstance(TENANT, INSTANCE_ID).orElseThrow().version());

        inTransaction(() -> store.completeTaskAndSynchronize(
            TENANT,
            INSTANCE_ID,
            TASK_ID,
            claimed.version(),
            List.of(next),
            InstanceStatus.RUNNING,
            CHANGED_AT.plusSeconds(2)
        ));

        TaskProjection completed = store.findTask(TENANT, TASK_ID).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, completed.status());
        assertEquals(4, completed.version());
        assertEquals(CHANGED_AT.plusSeconds(2), completed.completedAt());
        assertEquals(next, store.findTask(TENANT, NEXT_TASK_ID).orElseThrow());
        InstanceProjection instance = store.findInstance(TENANT, INSTANCE_ID).orElseThrow();
        assertEquals(InstanceStatus.RUNNING, instance.status());
        assertEquals(2, instance.version());

        assertThrows(ProjectionConflictException.class, () -> inTransaction(() ->
            store.completeTaskAndSynchronize(
                TENANT,
                INSTANCE_ID,
                TASK_ID,
                claimed.version(),
                List.of(next),
                InstanceStatus.RUNNING,
                CHANGED_AT.plusSeconds(3)
            )
        ));
    }

    @Test
    void controlCancellationWithdrawalAndEngineTaskOwnershipRemainFailClosed() {
        saveDefinition();
        InstanceProjection firstInstance = instance(
            INSTANCE_ID,
            "engine-instance-control",
            "business-control"
        );
        InstanceProjection secondInstance = instance(
            OTHER_INSTANCE_ID,
            "engine-instance-owner",
            "business-owner"
        );
        TaskProjection controlled = task(
            TASK_ID,
            INSTANCE_ID,
            "engine-task-controlled",
            "Manager-A",
            TaskStatus.PENDING,
            1,
            CREATED_AT
        );
        TaskProjection remaining = task(
            NEXT_TASK_ID,
            INSTANCE_ID,
            "engine-task-remaining",
            "Finance-A",
            TaskStatus.PENDING,
            1,
            CREATED_AT.plusSeconds(1)
        );
        TaskProjection foreignOwner = task(
            OTHER_TASK_ID,
            OTHER_INSTANCE_ID,
            "engine-task-foreign-owner",
            "Manager-X",
            TaskStatus.PENDING,
            1,
            CREATED_AT
        );
        inTransaction(() -> {
            store.lockBusinessKey(TENANT, firstInstance.businessKey());
            store.createInstance(firstInstance, List.of(controlled, remaining));
            store.lockBusinessKey(TENANT, secondInstance.businessKey());
            store.createInstance(secondInstance, List.of(foreignOwner));
        });

        TaskProjection claimed = inTransaction(() -> store.claimPendingTaskForControl(
            TENANT,
            TASK_ID,
            CHANGED_AT
        ));
        inTransaction(() -> store.cancelClaimedTaskAndSynchronize(
            TENANT,
            INSTANCE_ID,
            TASK_ID,
            claimed.version(),
            List.of(remaining),
            CHANGED_AT.plusSeconds(1)
        ));
        assertEquals(
            TaskStatus.CANCELED,
            store.findTask(TENANT, TASK_ID).orElseThrow().status()
        );
        assertEquals(
            TaskStatus.PENDING,
            store.findTask(TENANT, NEXT_TASK_ID).orElseThrow().status()
        );

        TaskProjection remainingClaim = inTransaction(() -> store.claimPendingTask(
            TENANT,
            NEXT_TASK_ID,
            "Finance-A",
            CHANGED_AT.plusSeconds(2)
        ));
        TaskProjection illegalOwnerReuse = task(
            OTHER_TASK_ID,
            INSTANCE_ID,
            foreignOwner.engineTaskId(),
            "Finance-B",
            TaskStatus.PENDING,
            1,
            CHANGED_AT.plusSeconds(3)
        );
        assertThrows(ProjectionConflictException.class, () -> inTransaction(() ->
            store.completeTaskAndSynchronize(
                TENANT,
                INSTANCE_ID,
                NEXT_TASK_ID,
                remainingClaim.version(),
                List.of(illegalOwnerReuse),
                InstanceStatus.RUNNING,
                CHANGED_AT.plusSeconds(3)
            )
        ));
        assertEquals(
            TaskStatus.COMPLETING,
            store.findTask(TENANT, NEXT_TASK_ID).orElseThrow().status()
        );
        assertEquals(
            TaskStatus.PENDING,
            store.findTask(TENANT, OTHER_TASK_ID).orElseThrow().status()
        );

        assertThrows(ProjectionConflictException.class, () -> inTransaction(() ->
            store.withdrawRunningInstance(
                OTHER_TENANT,
                INSTANCE_ID,
                "Initiator-A",
                CHANGED_AT.plusSeconds(4)
            )
        ));
        assertThrows(ProjectionConflictException.class, () -> inTransaction(() ->
            store.withdrawRunningInstance(
                TENANT,
                INSTANCE_ID,
                "initiator-a",
                CHANGED_AT.plusSeconds(4)
            )
        ));
        inTransaction(() -> store.withdrawRunningInstance(
            TENANT,
            INSTANCE_ID,
            "Initiator-A",
            CHANGED_AT.plusSeconds(4)
        ));

        assertEquals(
            InstanceStatus.WITHDRAWN,
            store.findInstance(TENANT, INSTANCE_ID).orElseThrow().status()
        );
        assertEquals(
            TaskStatus.CANCELED,
            store.findTask(TENANT, NEXT_TASK_ID).orElseThrow().status()
        );
        assertEquals(
            InstanceStatus.RUNNING,
            store.findInstance(TENANT, OTHER_INSTANCE_ID).orElseThrow().status()
        );
    }

    private Object claimConcurrently(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        try {
            return inTransaction(() -> store.claimPendingTask(
                TENANT,
                TASK_ID,
                "Manager-A",
                CHANGED_AT
            ));
        } catch (ProjectionConflictException exception) {
            return exception;
        }
    }

    private void saveDefinition() {
        inTransaction(() -> {
            store.lockDefinition(TENANT, DEFINITION_KEY, 1);
            store.saveDefinition(definition(TENANT));
        });
    }

    private void seedInstanceWithTasks(
        InstanceProjection instance,
        List<TaskProjection> tasks
    ) {
        saveDefinition();
        inTransaction(() -> {
            store.lockBusinessKey(TENANT, instance.businessKey());
            store.createInstance(instance, tasks);
        });
    }

    private static PublishedDefinition definition(String tenantId) {
        return new PublishedDefinition(
            tenantId,
            DEFINITION_KEY,
            1,
            DEFINITION_KEY,
            1,
            "compiler-1",
            "a".repeat(64),
            "deployment-1",
            "engine-definition-1",
            1,
            "Publisher-A",
            CREATED_AT
        );
    }

    private static InstanceProjection instance(
        UUID instanceId,
        String engineInstanceId,
        String businessKey
    ) {
        return new InstanceProjection(
            instanceId,
            TENANT,
            businessKey,
            engineInstanceId,
            DEFINITION_KEY,
            1,
            DEFINITION_KEY,
            1,
            "compiler-1",
            "a".repeat(64),
            "Initiator-A",
            new BigDecimal("123456789012.123456"),
            "供应商-A",
            "PO-Projection-1",
            List.of("attachment-2", "attachment-1"),
            new AssigneeSnapshot(
                "Manager-A",
                "Finance-Reviewer",
                List.of("Finance-A", "Finance-B"),
                Map.of("connectorKey", "DingTalk-A", "unicode", "审批"),
                Map.of(
                    "Manager-A",
                    new UserIdentitySnapshot(
                        "external-manager-a",
                        "manager-a",
                        "经理-A",
                        "manager-a@example.com",
                        "+15550000001",
                        List.of("department-a"),
                        Set.of("MANAGER"),
                        Set.of("POSITION-A"),
                        Map.of("source", "connector")
                    )
                )
            ),
            "b".repeat(64),
            InstanceStatus.RUNNING,
            1,
            CREATED_AT,
            CREATED_AT
        );
    }

    private static TaskProjection task(
        UUID taskId,
        UUID instanceId,
        String engineTaskId,
        String assigneeId,
        TaskStatus status,
        long version,
        Instant updatedAt
    ) {
        return new TaskProjection(
            taskId,
            instanceId,
            TENANT,
            engineTaskId,
            "managerApproval",
            "经理审批",
            assigneeId,
            status,
            version,
            CREATED_AT,
            updatedAt,
            status == TaskStatus.COMPLETED ? updatedAt : null
        );
    }

    private <T> T inTransaction(Supplier<T> action) {
        T result = transactions.execute(status -> action.get());
        return Objects.requireNonNull(result, "transaction action must return a result");
    }

    private void inTransaction(Runnable action) {
        transactions.executeWithoutResult(status -> action.run());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrency latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrency wait interrupted", exception);
        }
    }

    private static int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private static String configuredJdbcUrl() {
        String base = MYSQL.getJdbcUrl();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator
            + "characterEncoding=UTF-8"
            + "&connectionCollation=utf8mb4_0900_as_cs"
            + "&connectionTimeZone=UTC"
            + "&forceConnectionTimeZoneToSession=true"
            + "&preserveInstants=true";
    }

    private static final class RollbackMarker extends RuntimeException {
    }
}
