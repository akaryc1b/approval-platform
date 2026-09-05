package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.ProjectionConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcMySqlApprovalTaskCasStoreIntegrationTest {

    private static final String TENANT = "tenant-task-cas";
    private static final String OTHER_TENANT = "tenant-task-cas-other";
    private static final String DEFINITION_KEY = "task-cas-definition";
    private static final UUID INSTANCE_ID = UUID.fromString(
        "00000000-0000-0000-0000-00000000d001"
    );
    private static final UUID TASK_ID = UUID.fromString(
        "00000000-0000-0000-0000-00000000d101"
    );
    private static final UUID EARLY_TASK_ID = UUID.fromString(
        "00000000-0000-0000-0000-00000000d102"
    );
    private static final UUID LATE_FIRST_TASK_ID = UUID.fromString(
        "00000000-0000-0000-0000-00000000d100"
    );
    private static final UUID LATE_SECOND_TASK_ID = UUID.fromString(
        "00000000-0000-0000-0000-00000000d103"
    );
    private static final Instant CREATED_AT = Instant.parse(
        "2026-08-08T12:13:14.123456Z"
    );
    private static final Instant CLAIMED_AT = Instant.parse(
        "2026-08-08T12:14:15.654321Z"
    );
    private static final Instant TRANSFERRED_AT = Instant.parse(
        "2026-08-08T12:15:16.111222Z"
    );

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_task_cas")
        .withUsername("approval")
        .withPassword("approval")
        .withCommand(MySql84ProductionTestServer.command());

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private JdbcMySqlApprovalTaskCasStore store;
    private ExecutorService executor;

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
        seedDefinition();
        seedInstance();
        seedTask(TASK_ID, "engine-task-main", "Owner-A", CREATED_AT);
        store = new JdbcMySqlApprovalTaskCasStore(dataSource);
    }

    @AfterEach
    void stopExecutor() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Test
    void claimsTheExactPendingTaskAndReadsCanonicalValues() {
        TaskProjection claimed = store.claimPendingTask(
            TENANT,
            TASK_ID,
            "Owner-A",
            CLAIMED_AT
        );

        assertEquals(TASK_ID, claimed.taskId());
        assertEquals(INSTANCE_ID, claimed.instanceId());
        assertEquals(TENANT, claimed.tenantId());
        assertEquals("Owner-A", claimed.assigneeId());
        assertEquals(TaskStatus.COMPLETING, claimed.status());
        assertEquals(2, claimed.version());
        assertEquals(CREATED_AT, claimed.createdAt());
        assertEquals(CLAIMED_AT, claimed.updatedAt());
        assertEquals(claimed, store.findTask(TENANT, TASK_ID).orElseThrow());
        assertFalse(store.findTask(OTHER_TENANT, TASK_ID).isPresent());

        assertThrows(
            ProjectionConflictException.class,
            () -> store.claimPendingTask(TENANT, TASK_ID, "Owner-A", CLAIMED_AT)
        );
    }

    @Test
    void wrongTenantOwnerAndCaseVariantFailWithoutMutation() {
        assertThrows(
            ProjectionConflictException.class,
            () -> store.claimPendingTask(
                OTHER_TENANT,
                TASK_ID,
                "Owner-A",
                CLAIMED_AT
            )
        );
        assertThrows(
            ProjectionConflictException.class,
            () -> store.claimPendingTask(
                TENANT,
                TASK_ID,
                "Other-Owner",
                CLAIMED_AT
            )
        );
        assertThrows(
            ProjectionConflictException.class,
            () -> store.claimPendingTask(
                TENANT,
                TASK_ID,
                "owner-a",
                CLAIMED_AT
            )
        );

        TaskProjection unchanged = store.findTask(TENANT, TASK_ID).orElseThrow();
        assertEquals(TaskStatus.PENDING, unchanged.status());
        assertEquals("Owner-A", unchanged.assigneeId());
        assertEquals(1, unchanged.version());
        assertEquals(CREATED_AT, unchanged.updatedAt());
    }

    @Test
    void controlClaimPreservesThePersistedAssignee() {
        TaskProjection claimed = store.claimPendingTaskForControl(
            TENANT,
            TASK_ID,
            CLAIMED_AT
        );

        assertEquals(TaskStatus.COMPLETING, claimed.status());
        assertEquals("Owner-A", claimed.assigneeId());
        assertEquals(2, claimed.version());
        assertEquals(CLAIMED_AT, claimed.updatedAt());
    }

    @Test
    void transfersOnlyFromTheExactCurrentAssignee() {
        assertThrows(
            ProjectionConflictException.class,
            () -> store.transferPendingTask(
                TENANT,
                TASK_ID,
                "owner-a",
                "Owner-B",
                TRANSFERRED_AT
            )
        );

        TaskProjection transferred = store.transferPendingTask(
            TENANT,
            TASK_ID,
            "Owner-A",
            "Owner-B",
            TRANSFERRED_AT
        );

        assertEquals(TaskStatus.PENDING, transferred.status());
        assertEquals("Owner-B", transferred.assigneeId());
        assertEquals(2, transferred.version());
        assertEquals(TRANSFERRED_AT, transferred.updatedAt());
        assertThrows(
            ProjectionConflictException.class,
            () -> store.transferPendingTask(
                TENANT,
                TASK_ID,
                "Owner-A",
                "Owner-C",
                TRANSFERRED_AT
            )
        );
    }

    @Test
    void concurrentClaimsProduceOneWinnerAndOneConflict() throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Attempt> first = executor.submit(() -> afterGate(
            ready,
            start,
            () -> store.claimPendingTask(TENANT, TASK_ID, "Owner-A", CLAIMED_AT)
        ));
        Future<Attempt> second = executor.submit(() -> afterGate(
            ready,
            start,
            () -> store.claimPendingTask(TENANT, TASK_ID, "Owner-A", CLAIMED_AT)
        ));
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        start.countDown();

        List<Attempt> attempts = List.of(
            first.get(30, TimeUnit.SECONDS),
            second.get(30, TimeUnit.SECONDS)
        );
        assertSingleWinner(attempts);

        TaskProjection persisted = store.findTask(TENANT, TASK_ID).orElseThrow();
        assertEquals(TaskStatus.COMPLETING, persisted.status());
        assertEquals("Owner-A", persisted.assigneeId());
        assertEquals(2, persisted.version());
        assertEquals(CLAIMED_AT, persisted.updatedAt());
    }

    @Test
    void concurrentTransfersProduceOneWinnerAndOneConflict() throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Attempt> first = executor.submit(() -> afterGate(
            ready,
            start,
            () -> store.transferPendingTask(
                TENANT,
                TASK_ID,
                "Owner-A",
                "Owner-B",
                TRANSFERRED_AT
            )
        ));
        Future<Attempt> second = executor.submit(() -> afterGate(
            ready,
            start,
            () -> store.transferPendingTask(
                TENANT,
                TASK_ID,
                "Owner-A",
                "Owner-C",
                TRANSFERRED_AT
            )
        ));
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        start.countDown();

        List<Attempt> attempts = List.of(
            first.get(30, TimeUnit.SECONDS),
            second.get(30, TimeUnit.SECONDS)
        );
        assertSingleWinner(attempts);

        TaskProjection persisted = store.findTask(TENANT, TASK_ID).orElseThrow();
        assertEquals(TaskStatus.PENDING, persisted.status());
        assertTrue(List.of("Owner-B", "Owner-C").contains(persisted.assigneeId()));
        assertEquals(2, persisted.version());
        assertEquals(TRANSFERRED_AT, persisted.updatedAt());
    }

    @Test
    void surroundingTransactionRollbackRestoresThePendingTask() {
        TransactionTemplate transaction = new TransactionTemplate(
            new JdbcTransactionManager(dataSource)
        );

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            store.claimPendingTask(TENANT, TASK_ID, "Owner-A", CLAIMED_AT);
            throw new IllegalStateException("roll back after claim");
        }));

        TaskProjection restored = store.findTask(TENANT, TASK_ID).orElseThrow();
        assertEquals(TaskStatus.PENDING, restored.status());
        assertEquals("Owner-A", restored.assigneeId());
        assertEquals(1, restored.version());
        assertEquals(CREATED_AT, restored.updatedAt());
    }

    @Test
    void listsTasksInDeterministicTenantScopedOrder() {
        jdbc.update("delete from ap_approval_task");
        seedTask(EARLY_TASK_ID, "engine-task-early", "Owner-Early", CREATED_AT);
        seedTask(
            LATE_SECOND_TASK_ID,
            "engine-task-late-second",
            "Owner-Late",
            CREATED_AT.plusSeconds(1)
        );
        seedTask(
            LATE_FIRST_TASK_ID,
            "engine-task-late-first",
            "Owner-Late",
            CREATED_AT.plusSeconds(1)
        );

        List<TaskProjection> tasks = store.findTasks(TENANT, INSTANCE_ID);

        assertEquals(
            List.of(EARLY_TASK_ID, LATE_FIRST_TASK_ID, LATE_SECOND_TASK_ID),
            tasks.stream().map(TaskProjection::taskId).toList()
        );
        assertTrue(store.findTasks(OTHER_TENANT, INSTANCE_ID).isEmpty());
    }

    @Test
    void rejectsSubMicrosecondOperationTimesBeforeMutation() {
        Instant unsupported = CLAIMED_AT.plusNanos(1);

        assertThrows(
            IllegalArgumentException.class,
            () -> store.claimPendingTask(TENANT, TASK_ID, "Owner-A", unsupported)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> store.transferPendingTask(
                TENANT,
                TASK_ID,
                "Owner-A",
                "Owner-B",
                unsupported
            )
        );

        TaskProjection unchanged = store.findTask(TENANT, TASK_ID).orElseThrow();
        assertEquals(TaskStatus.PENDING, unchanged.status());
        assertEquals("Owner-A", unchanged.assigneeId());
        assertEquals(1, unchanged.version());
        assertEquals(CREATED_AT, unchanged.updatedAt());
    }

    private static Attempt afterGate(
        CountDownLatch ready,
        CountDownLatch start,
        Supplier<TaskProjection> action
    ) {
        ready.countDown();
        await(start);
        try {
            return new Attempt(action.get(), null);
        } catch (Throwable failure) {
            return new Attempt(null, failure);
        }
    }

    private static void assertSingleWinner(List<Attempt> attempts) {
        assertEquals(1, attempts.stream().filter(Attempt::succeeded).count());
        assertEquals(1, attempts.stream()
            .filter(attempt -> attempt.failure() instanceof ProjectionConflictException)
            .count());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrency gate timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrency gate interrupted", exception);
        }
    }

    private static void seedDefinition() {
        jdbc.update(
            """
            insert into ap_definition_version (
                tenant_id, definition_key, definition_version,
                form_key, form_version, compiler_version, content_hash,
                deployment_id, engine_definition_id, engine_version,
                published_by, published_at
            ) values (?, ?, 1, ?, 1, 'compiler-1', ?, ?, ?, 1, 'publisher', ?)
            """,
            TENANT,
            DEFINITION_KEY,
            DEFINITION_KEY,
            "d".repeat(64),
            "deployment-task-cas",
            "definition-task-cas",
            Timestamp.from(CREATED_AT)
        );
    }

    private static void seedInstance() {
        jdbc.update(
            """
            insert into ap_approval_instance (
                instance_id, tenant_id, business_key, engine_instance_id,
                definition_key, definition_version, form_key, form_version,
                compiler_version, content_hash, initiator_id,
                amount, supplier, purchase_order_reference,
                attachment_ids_json, assignee_snapshot_json, request_hash,
                status, version, created_at, updated_at
            ) values (
                ?, ?, 'TASK-CAS-001', 'engine-instance-task-cas', ?, 1, ?, 1,
                'compiler-1', ?, 'initiator-task-cas',
                ?, 'supplier-task-cas', 'purchase-order-task-cas',
                cast(? as json), cast(? as json), ?,
                'RUNNING', 1, ?, ?
            )
            """,
            INSTANCE_ID.toString(),
            TENANT,
            DEFINITION_KEY,
            DEFINITION_KEY,
            "e".repeat(64),
            new BigDecimal("1250.50"),
            "[]",
            "{}",
            "f".repeat(64),
            Timestamp.from(CREATED_AT),
            Timestamp.from(CREATED_AT)
        );
    }

    private static void seedTask(
        UUID taskId,
        String engineTaskId,
        String assigneeId,
        Instant createdAt
    ) {
        jdbc.update(
            """
            insert into ap_approval_task (
                task_id, instance_id, tenant_id, engine_task_id,
                task_definition_key, task_name, assignee_id,
                status, version, created_at, updated_at, completed_at
            ) values (?, ?, ?, ?, 'managerApproval', 'Manager approval', ?,
                'PENDING', 1, ?, ?, null)
            """,
            taskId.toString(),
            INSTANCE_ID.toString(),
            TENANT,
            engineTaskId,
            assigneeId,
            Timestamp.from(createdAt),
            Timestamp.from(createdAt)
        );
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

    private record Attempt(TaskProjection result, Throwable failure) {
        boolean succeeded() {
            return result != null && failure == null;
        }
    }
}
