package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalSlaActionStateRecorder.ActionStateException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionStateRecorder.RecordResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalSlaActionStateRecorderIntegrationTest {

    private static final String TENANT_ID = "tenant-overdue-state";
    private static final UUID APPROVAL_INSTANCE_ID = UUID.fromString(
        "78000000-0000-0000-0000-000000000001"
    );
    private static final UUID TASK_ID = UUID.fromString(
        "78000000-0000-0000-0000-000000000002"
    );
    private static final UUID POLICY_ID = UUID.fromString(
        "78000000-0000-0000-0000-000000000003"
    );
    private static final UUID SLA_INSTANCE_ID = UUID.fromString(
        "78000000-0000-0000-0000-000000000004"
    );
    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_sla_action_state_test")
        .withUsername("approval")
        .withPassword("approval");

    private JdbcTemplate jdbc;
    private JdbcApprovalSlaActionStateRecorder recorder;

    @BeforeEach
    void migrateAndSeed() {
        DataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop schema public cascade");
        jdbc.execute("create schema public");
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
        seedEvidence();
        recorder = new JdbcApprovalSlaActionStateRecorder(
            dataSource,
            new DataSourceTransactionManager(dataSource)
        );
    }

    @Test
    void overdueSequenceIsOptimisticIdempotentAndTenantScoped() {
        assertEquals(RecordResult.RECORDED, recorder.recordOverdue(
            TENANT_ID,
            SLA_INSTANCE_ID,
            101,
            "request-overdue",
            "trace-overdue",
            NOW.plus(Duration.ofHours(3))
        ));
        assertEquals(101L, jdbc.queryForObject(
            """
            select last_action_sequence from ap_sla_instance
            where tenant_id=? and sla_instance_id=?
            """,
            Long.class,
            TENANT_ID,
            SLA_INSTANCE_ID
        ));
        assertEquals(2L, jdbc.queryForObject(
            """
            select version from ap_sla_instance
            where tenant_id=? and sla_instance_id=?
            """,
            Long.class,
            TENANT_ID,
            SLA_INSTANCE_ID
        ));
        assertEquals("request-overdue", jdbc.queryForObject(
            """
            select request_id from ap_sla_instance
            where tenant_id=? and sla_instance_id=?
            """,
            String.class,
            TENANT_ID,
            SLA_INSTANCE_ID
        ));

        assertEquals(RecordResult.ALREADY_RECORDED, recorder.recordOverdue(
            TENANT_ID,
            SLA_INSTANCE_ID,
            101,
            "request-overdue-duplicate",
            "trace-overdue-duplicate",
            NOW.plus(Duration.ofHours(3)).plusSeconds(1)
        ));
        assertEquals(2L, jdbc.queryForObject(
            """
            select version from ap_sla_instance
            where tenant_id=? and sla_instance_id=?
            """,
            Long.class,
            TENANT_ID,
            SLA_INSTANCE_ID
        ));

        ActionStateException missing = assertThrows(ActionStateException.class, () ->
            recorder.recordOverdue(
                "tenant-other",
                SLA_INSTANCE_ID,
                102,
                "request-cross-tenant",
                null,
                NOW.plus(Duration.ofHours(3))
            )
        );
        assertEquals("APPROVAL_SLA_INSTANCE_NOT_FOUND", missing.code());
        assertFalse(missing.retryable());

        jdbc.update(
            """
            update ap_sla_instance
            set status='PAUSED', paused_at=?, pause_reason='test', version=version+1
            where tenant_id=? and sla_instance_id=?
            """,
            java.sql.Timestamp.from(NOW.plus(Duration.ofHours(4))),
            TENANT_ID,
            SLA_INSTANCE_ID
        );
        ActionStateException paused = assertThrows(ActionStateException.class, () ->
            recorder.recordOverdue(
                TENANT_ID,
                SLA_INSTANCE_ID,
                102,
                "request-paused",
                null,
                NOW.plus(Duration.ofHours(4))
            )
        );
        assertEquals("APPROVAL_SLA_ACTION_STATE_CONFLICT", paused.code());
        assertFalse(paused.retryable());
    }

    @Test
    void concurrentDuplicateRecordingProducesOneMutationAndOneIdempotentResult()
        throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RecordResult> first = executor.submit(() -> recordAfterSignal(ready, start));
            Future<RecordResult> second = executor.submit(() -> recordAfterSignal(ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<RecordResult> results = List.of(
                first.get(30, TimeUnit.SECONDS),
                second.get(30, TimeUnit.SECONDS)
            );
            assertTrue(results.contains(RecordResult.RECORDED));
            assertTrue(results.contains(RecordResult.ALREADY_RECORDED));
            assertEquals(2L, jdbc.queryForObject(
                """
                select version from ap_sla_instance
                where tenant_id=? and sla_instance_id=?
                """,
                Long.class,
                TENANT_ID,
                SLA_INSTANCE_ID
            ));
        } finally {
            executor.shutdownNow();
        }
    }

    private RecordResult recordAfterSignal(
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return recorder.recordOverdue(
            TENANT_ID,
            SLA_INSTANCE_ID,
            101,
            "request-concurrent",
            "trace-concurrent",
            NOW.plus(Duration.ofHours(3))
        );
    }

    private void seedEvidence() {
        jdbc.update(
            """
            insert into ap_definition_version (
                tenant_id, definition_key, definition_version, form_key, form_version,
                compiler_version, content_hash, deployment_id, engine_definition_id,
                engine_version, published_by, published_at
            ) values (?, 'purchasePayment', 1, 'purchasePayment', 1, 'compiler-v1',
                repeat('a',64), 'deployment-overdue', 'engine-definition-overdue', 1,
                'publisher', ?)
            """,
            TENANT_ID,
            java.sql.Timestamp.from(NOW.minus(Duration.ofDays(2)))
        );
        jdbc.update(
            """
            insert into ap_approval_instance (
                instance_id, tenant_id, business_key, engine_instance_id,
                definition_key, definition_version, form_key, form_version,
                compiler_version, content_hash, initiator_id, amount, supplier,
                purchase_order_reference, attachment_ids_json, assignee_snapshot_json,
                request_hash, status, version, created_at, updated_at
            ) values (?, ?, 'OVERDUE-1', 'engine-instance-overdue', 'purchasePayment', 1,
                'purchasePayment', 1, 'compiler-v1', repeat('a',64), 'initiator', 100,
                'supplier', 'PO-OVERDUE-1', '[]'::jsonb, '{}'::jsonb, repeat('b',64),
                'RUNNING', 1, ?, ?)
            """,
            APPROVAL_INSTANCE_ID,
            TENANT_ID,
            java.sql.Timestamp.from(NOW),
            java.sql.Timestamp.from(NOW)
        );
        jdbc.update(
            """
            insert into ap_approval_task (
                task_id, instance_id, tenant_id, engine_task_id, task_definition_key,
                task_name, assignee_id, status, version, created_at, updated_at, completed_at
            ) values (?, ?, ?, 'engine-task-overdue', 'managerApproval', 'Manager approval',
                'owner-a', 'PENDING', 1, ?, ?, null)
            """,
            TASK_ID,
            APPROVAL_INSTANCE_ID,
            TENANT_ID,
            java.sql.Timestamp.from(NOW),
            java.sql.Timestamp.from(NOW)
        );
        jdbc.update(
            """
            insert into ap_sla_policy (
                policy_id, tenant_id, policy_key, display_name, status, active_version,
                created_by, created_at, updated_at, version
            ) values (?, ?, 'overdue-policy', 'Overdue policy', 'DRAFT', null,
                'designer', ?, ?, 1)
            """,
            POLICY_ID,
            TENANT_ID,
            java.sql.Timestamp.from(NOW.minus(Duration.ofDays(2))),
            java.sql.Timestamp.from(NOW.minus(Duration.ofDays(2)))
        );
        jdbc.update(
            """
            insert into ap_sla_policy_version (
                policy_id, tenant_id, policy_version, definition_key, release_version,
                task_definition_key, target_type, duration_mode, duration_millis,
                calendar_id, calendar_version, calendar_content_hash, time_zone,
                first_reminder_offset_millis, repeat_reminder_interval_millis,
                maximum_reminder_count, overdue_offset_millis, escalation_strategy,
                escalation_target, automatic_action_policy, pause_rules_json, content_hash,
                status, immutable, published_by, published_at, created_at, updated_at
            ) values (?, ?, 1, 'purchasePayment', 1, 'managerApproval', 'TASK',
                'NATURAL_TIME', 7200000, null, null, null, 'UTC', null, null, 0,
                600000, null, null, 'NONE', '{}'::jsonb, repeat('c',64), 'ACTIVE',
                true, 'publisher', ?, ?, ?)
            """,
            POLICY_ID,
            TENANT_ID,
            java.sql.Timestamp.from(NOW.minus(Duration.ofDays(1))),
            java.sql.Timestamp.from(NOW.minus(Duration.ofDays(2))),
            java.sql.Timestamp.from(NOW.minus(Duration.ofDays(1)))
        );
        jdbc.update(
            """
            update ap_sla_policy
            set status='ACTIVE', active_version=1, version=2, updated_at=?
            where tenant_id=? and policy_id=?
            """,
            java.sql.Timestamp.from(NOW.minus(Duration.ofHours(12))),
            TENANT_ID,
            POLICY_ID
        );
        jdbc.update(
            """
            insert into ap_sla_instance (
                sla_instance_id, tenant_id, approval_instance_id, task_id,
                collaboration_participant_id, definition_key, task_definition_key,
                target_type, policy_id, policy_version, calendar_id, calendar_version,
                time_zone, responsible_user_id, original_responsible_user_id,
                started_at, due_at, next_reminder_at, overdue_at, paused_at, pause_reason,
                accumulated_paused_millis, terminal_at, terminal_reason, status,
                last_action_sequence, request_id, trace_id, version, created_at, updated_at
            ) values (?, ?, ?, ?, null, 'purchasePayment', 'managerApproval', 'TASK',
                ?, 1, null, null, 'UTC', 'owner-a', 'owner-a', ?, ?, null, ?, null,
                null, 0, null, null, 'ACTIVE', 0, 'request-create', 'trace-create',
                1, ?, ?)
            """,
            SLA_INSTANCE_ID,
            TENANT_ID,
            APPROVAL_INSTANCE_ID,
            TASK_ID,
            POLICY_ID,
            java.sql.Timestamp.from(NOW),
            java.sql.Timestamp.from(NOW.plus(Duration.ofHours(2))),
            java.sql.Timestamp.from(
                NOW.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(10))
            ),
            java.sql.Timestamp.from(NOW),
            java.sql.Timestamp.from(NOW)
        );
    }
}
