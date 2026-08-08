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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcMySqlApprovalTaskCompletionCasIntegrationTest {

    private static final String TENANT = "tenant-task-completion";
    private static final String OTHER_TENANT = "tenant-task-completion-other";
    private static final String DEFINITION_KEY = "task-completion-definition";
    private static final UUID INSTANCE_ID = UUID.fromString(
        "00000000-0000-0000-0000-00000000e001"
    );
    private static final UUID TASK_ID = UUID.fromString(
        "00000000-0000-0000-0000-00000000e101"
    );
    private static final UUID SIBLING_TASK_ID = UUID.fromString(
        "00000000-0000-0000-0000-00000000e102"
    );
    private static final Instant CREATED_AT = Instant.parse(
        "2026-08-08T13:14:15.123456Z"
    );
    private static final Instant CLAIMED_AT = Instant.parse(
        "2026-08-08T13:15:16.234567Z"
    );
    private static final Instant COMPLETED_AT = Instant.parse(
        "2026-08-08T13:16:17.345678Z"
    );

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_task_completion")
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
        seedTask(
            TASK_ID,
            "engine-task-completion-main",
            "Owner-A",
            TaskStatus.COMPLETING,
            2,
            CREATED_AT,
            CLAIMED_AT,
            null
        );
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
    void completesTheExactClaimedTaskAndPersistsCanonicalEvidence() {
        TaskProjection completed = store.completeClaimedTask(
            TENANT,
            TASK_ID,
            "Owner-A",
            2,
            COMPLETED_AT
        );

        assertEquals(TASK_ID, completed.taskId());
        assertEquals(INSTANCE_ID, completed.instanceId());
        assertEquals(TENANT, completed.tenantId());
        assertEquals("engine-task-completion-main", completed.engineTaskId());
        assertEquals("managerApproval", completed.taskDefinitionKey());
        assertEquals("Manager approval", completed.name());
        assertEquals("Owner-A", completed.assigneeId());
        assertEquals(TaskStatus.COMPLETED, completed.status());
        assertEquals(3, completed.version());
        assertEquals(CREATED_AT, completed.createdAt());
        assertEquals(COMPLETED_AT, completed.updatedAt());
        assertEquals(COMPLETED_AT, completed.completedAt());
        assertEquals(completed, store.findTask(TENANT, TASK_ID).orElseThrow());

        assertThrows(
            ProjectionConflictException.class,
            () -> store.completeClaimedTask(
                TENANT,
                TASK_ID,
                "Owner-A",
                2,
                COMPLETED_AT
            )
        );
    }

    @Test
    void wrongTenantOwnerAndCaseVariantFailWithoutMutation() {
        assertThrows(
            ProjectionConflictException.class,
            () -> store.completeClaimedTask(
                OTHER_TENANT,
                TASK_ID,
                "Owner-A",
                2,
                COMPLETED_AT
            )
        );
        assertThrows(
            ProjectionConflictException.class,
            () -> store.completeClaimedTask(
                TENANT,
                TASK_ID,
                "Other-Owner",
                2,
                COMPLETED_AT
            )
        );
        assertThrows(
            ProjectionConflictException.class,
            () -> store.completeClaimedTask(
                TENANT,
                TASK_ID,
                "owner-a",
                2,
                COMPLETED_AT
            )
        );

        assertClaimedTaskUnchanged();
    }

    @Test
    void staleAndFutureClaimedVersionsFailWithoutMutation() {
        assertThrows(
            ProjectionConflictException.class,
            () -> store.completeClaimedTask(
                TENANT,
                TASK_ID,
                "Owner-A",
                1,
                COMPLETED_AT
            )
        );
        assertThrows(
            ProjectionConflictException.class,
            () -> store.completeClaimedTask(
                TENANT,
                TASK_ID,
                "Owner-A",
                3,
                COMPLETED_AT
            )
        );

        assertClaimedTaskUnchanged();
    }

    @Test
    void pendingAndAlreadyCompletedRowsAreRejected() {
        jdbc.update(
            """
            update ap_approval_task
            set status = 'PENDING', version = 1,
                updated_at = ?, completed_at = null
            where task_id = ?
            """,
            Timestamp.from(CREATED_AT),
            TASK_ID.toString()
        );
        assertThrows(
            ProjectionConflictException.class,
            () -> store.completeClaimedTask(
                TENANT,
                TASK_ID,
                "Owner-A",
                1,
                COMPLETED_AT
            )
        );

        jdbc.update(
            """
            update ap_approval_task
            set status = 'COMPLETED', version = 3,
                updated_at = ?, completed_at = ?
            where task_id = ?
            """,
            Timestamp.from(COMPLETED_AT),
            Timestamp.from(COMPLETED_AT),
            TASK_ID.toString()
        );
        assertThrows(
            ProjectionConflictException.class,
            () -> store.completeClaimedTask(
                TENANT,
                TASK_ID,
                "Owner-A",
                3,
                COMPLETED_AT.plusSeconds(1)
            )
        );

        TaskProjection persisted = store.findTask(TENANT, TASK_ID).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, persisted.status());
        assertEquals(3, persisted.version());
        assertEquals(COMPLETED_AT, persisted.completedAt());
    }

    @Test
    void concurrentCompletionsProduceOneWinnerAndOneConflict() throws Exception {
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Attempt> first = executor.submit(() -> afterGate(
            ready,
            start,
            () -> store.completeClaimedTask(
                TENANT,
                TASK_ID,
                "Owner-A",
                2,
                COMPLETED_AT
            )
        ));
        Future<Attempt> second = executor.submit(() -> afterGate(
            ready,
            start,
            () -> store.completeClaimedTask(
                TENANT,
                TASK_ID,
                "Owner-A",
                2,
                COMPLETED_AT
            )
        ));
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        start.countDown();

        List<Attempt> attempts = List.of(
            first.get(30, TimeUnit.SECONDS),
            second.get(30, TimeUnit.SECONDS)
        );
        assertEquals(1, attempts.stream().filter(Attempt::succeeded).count());
        assertEquals(1, attempts.stream()
            .filter(attempt -> attempt.failure() instanceof ProjectionConflictException)
            .count());

        TaskProjection persisted = store.findTask(TENANT, TASK_ID).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, persisted.status());
        assertEquals(3, persisted.version());
        assertEquals(COMPLETED_AT, persisted.updatedAt());
        assertEquals(COMPLETED_AT, persisted.completedAt());
    }

    @Test
    void surroundingTransactionRollbackRestoresTheClaimedTask() {
        TransactionTemplate transaction = new TransactionTemplate(
            new JdbcTransactionManager(dataSource)
        );

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            store.completeClaimedTask(
                TENANT,
                TASK_ID,
                "Owner-A",
                2,
                COMPLETED_AT
            );
            throw new IllegalStateException("roll back after completion");
        }));

        assertClaimedTaskUnchanged();
    }

    @Test
    void rejectsUnsupportedTimeAndVersionBeforeMutation() {
        Instant subMicrosecond = COMPLETED_AT.plusNanos(1);

        assertThrows(
            IllegalArgumentException.class,
            () -> store.completeClaimedTask(
                TENANT,
                TASK_ID,
                "Owner-A",
                2,
                subMicrosecond
            )
        );
        for (long version : List.of(0L, -1L, Long.MAX_VALUE)) {
            assertThrows(
                IllegalArgumentException.class,
                () -> store.completeClaimedTask(
                    TENANT,
                    TASK_ID,
                    "Owner-A",
                    version,
                    COMPLETED_AT
                )
            );
        }

        assertClaimedTaskUnchanged();
    }

    @Test
    void completionDoesNotSynchronizeSiblingsOrAdvanceTheParentInstance() {
        seedTask(
            SIBLING_TASK_ID,
            "engine-task-completion-sibling",
            "Owner-B",
            TaskStatus.PENDING,
            1,
            CREATED_AT.plusSeconds(1),
            CREATED_AT.plusSeconds(1),
            null
        );

        store.completeClaimedTask(
            TENANT,
            TASK_ID,
            "Owner-A",
            2,
            COMPLETED_AT
        );

        TaskProjection sibling = store.findTask(TENANT, SIBLING_TASK_ID).orElseThrow();
        assertEquals(TaskStatus.PENDING, sibling.status());
        assertEquals("Owner-B", sibling.assigneeId());
        assertEquals(1, sibling.version());
        assertNull(sibling.completedAt());
        assertEquals(
            "RUNNING",
            jdbc.queryForObject(
                "select status from ap_approval_instance where instance_id = ?",
                String.class,
                INSTANCE_ID.toString()
            )
        );
        assertEquals(
            1L,
            jdbc.queryForObject(
                "select version from ap_approval_instance where instance_id = ?",
                Long.class,
                INSTANCE_ID.toString()
            )
        );
        assertEquals(
            CREATED_AT,
            jdbc.queryForObject(
                "select updated_at from ap_approval_instance where instance_id = ?",
                Timestamp.class,
                INSTANCE_ID.toString()
            ).toInstant()
        );
    }

    private void assertClaimedTaskUnchanged() {
        TaskProjection unchanged = store.findTask(TENANT, TASK_ID).orElseThrow();
        assertEquals(TaskStatus.COMPLETING, unchanged.status());
        assertEquals("Owner-A", unchanged.assigneeId());
        assertEquals(2, unchanged.version());
        assertEquals(CLAIMED_AT, unchanged.updatedAt());
        assertNull(unchanged.completedAt());
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
            "deployment-task-completion",
            "definition-task-completion",
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
                ?, ?, 'TASK-COMPLETION-001', 'engine-instance-task-completion',
                ?, 1, ?, 1,
                'compiler-1', ?, 'initiator-task-completion',
                ?, 'supplier-task-completion', 'purchase-order-task-completion',
                cast(? as json), cast(? as json), ?,
                'RUNNING', 1, ?, ?
            )
            """,
            INSTANCE_ID.toString(),
            TENANT,
            DEFINITION_KEY,
            DEFINITION_KEY,
            "e".repeat(64),
            new BigDecimal("2250.75"),
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
        TaskStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
    ) {
        jdbc.update(
            """
            insert into ap_approval_task (
                task_id, instance_id, tenant_id, engine_task_id,
                task_definition_key, task_name, assignee_id,
                status, version, created_at, updated_at, completed_at
            ) values (?, ?, ?, ?, 'managerApproval', 'Manager approval', ?, ?, ?, ?, ?, ?)
            """,
            taskId.toString(),
            INSTANCE_ID.toString(),
            TENANT,
            engineTaskId,
            assigneeId,
            status.name(),
            version,
            Timestamp.from(createdAt),
            Timestamp.from(updatedAt),
            completedAt == null ? null : Timestamp.from(completedAt)
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
