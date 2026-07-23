package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionPlanner;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntentCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.AutomaticAction;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.EscalationTargetType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.PolicyStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.ResponsibilityChange;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.ResponsibilityChangeSource;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaDurationMode;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTargetType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTerminalReason;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class TransactionalApprovalSlaStoreIntegrationTest {

    private static final String TENANT_ID = "tenant-transactional-sla";
    private static final UUID POLICY_ID = UUID.fromString(
        "73000000-0000-0000-0000-000000000001"
    );
    private static final UUID APPROVAL_INSTANCE_ID = UUID.fromString(
        "73000000-0000-0000-0000-000000000002"
    );
    private static final UUID TASK_ID = UUID.fromString(
        "73000000-0000-0000-0000-000000000003"
    );
    private static final UUID SECOND_TASK_ID = UUID.fromString(
        "73000000-0000-0000-0000-000000000005"
    );
    private static final UUID SLA_INSTANCE_ID = UUID.fromString(
        "73000000-0000-0000-0000-000000000004"
    );
    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_transactional_sla_test")
        .withUsername("approval")
        .withPassword("approval");

    private JdbcApprovalSlaStore rawStore;
    private JdbcApprovalSlaExecutionStore executionStore;
    private TransactionalApprovalSlaStore store;
    private TransactionTemplate transactions;

    @BeforeEach
    void migrateAndSeed() {
        DataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop schema public cascade");
        jdbc.execute("create schema public");
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
        seedApprovalEvidence(jdbc);

        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(
            dataSource
        );
        transactions = new TransactionTemplate(transactionManager);
        rawStore = new JdbcApprovalSlaStore(dataSource, transactionManager);
        executionStore = new JdbcApprovalSlaExecutionStore(
            dataSource,
            new ObjectMapper(),
            transactionManager
        );
        AtomicInteger identifiers = new AtomicInteger();
        store = new TransactionalApprovalSlaStore(
            rawStore,
            executionStore,
            new ApprovalSlaExecutionPlanner(
                3,
                () -> UUID.fromString(String.format(
                    "74000000-0000-0000-0000-%012d",
                    identifiers.incrementAndGet()
                ))
            ),
            transactionManager
        );
        seedImmutablePolicy();
    }

    @Test
    void createAndIntentPlanningAreIdempotentAndRollbackTogether() {
        SlaInstance instance = slaInstance(SLA_INSTANCE_ID, "request-create");

        assertEquals(1, store.createInstances(List.of(instance)));
        assertEquals(0, store.createInstances(List.of(instance)));
        assertEquals(5, executionStore.summarize(TENANT_ID, NOW).ready());
        assertEquals(5, intents().total());

        UUID rollbackId = UUID.fromString("73000000-0000-0000-0000-000000000099");
        assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
            assertEquals(1, store.createInstances(List.of(
                slaInstance(rollbackId, SECOND_TASK_ID, "request-rollback")
            )));
            throw new IllegalStateException("force outer rollback");
        }));

        assertTrue(rawStore.findInstance(TENANT_ID, rollbackId).isEmpty());
        assertEquals(5, intents().total());
    }

    @Test
    void pauseCancelsAndResumeCreatesVersionedReplacementIntents() {
        assertEquals(1, store.createInstances(List.of(
            slaInstance(SLA_INSTANCE_ID, "request-lifecycle")
        )));

        SlaInstance paused = store.pause(
            TENANT_ID,
            SLA_INSTANCE_ID,
            1,
            NOW.plus(Duration.ofMinutes(10)),
            "planned maintenance"
        );
        assertEquals(SlaStatus.PAUSED, paused.status());
        assertEquals(5, executionStore.summarize(TENANT_ID, NOW).cancelled());

        SlaInstance resumed = store.resume(
            TENANT_ID,
            SLA_INSTANCE_ID,
            2,
            NOW.plus(Duration.ofHours(3)),
            NOW.plus(Duration.ofHours(2)),
            NOW.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(10)),
            Duration.ofMinutes(10),
            NOW.plus(Duration.ofMinutes(20))
        );
        assertEquals(SlaStatus.ACTIVE, resumed.status());
        assertEquals(3, resumed.version());
        assertEquals(5, executionStore.summarize(TENANT_ID, NOW).ready());
        assertEquals(5, executionStore.summarize(TENANT_ID, NOW).cancelled());
        assertEquals(10, intents().total());
        assertTrue(intents().items().stream()
            .filter(intent -> intent.status() == IntentStatus.READY)
            .allMatch(intent -> intent.actionSequence() >= 257));
    }

    @Test
    void responsibilityAndTerminalChangesUpdateOrCancelOnlyFutureExecution() {
        assertEquals(1, store.createInstances(List.of(
            slaInstance(SLA_INSTANCE_ID, "request-responsibility")
        )));
        SlaInstance changed = store.changeResponsibility(new ResponsibilityChange(
            UUID.fromString("73000000-0000-0000-0000-000000000010"),
            SLA_INSTANCE_ID,
            TENANT_ID,
            "owner-a",
            "owner-b",
            ResponsibilityChangeSource.MANUAL_TRANSFER,
            "manual workload transfer",
            "operator-a",
            NOW.plus(Duration.ofMinutes(5)),
            "request-transfer",
            "trace-transfer"
        ), 1);

        assertEquals("owner-b", changed.responsibleUserId());
        assertTrue(intents().items().stream()
            .allMatch(intent -> "owner-b".equals(intent.responsibleUserId())));

        assertEquals(1, store.terminalTask(
            TENANT_ID,
            TASK_ID,
            SlaTerminalReason.TASK_COMPLETED,
            NOW.plus(Duration.ofMinutes(10))
        ));
        assertEquals(SlaStatus.TERMINAL, rawStore.findInstance(
            TENANT_ID,
            SLA_INSTANCE_ID
        ).orElseThrow().status());
        assertEquals(5, executionStore.summarize(TENANT_ID, NOW).cancelled());
        assertFalse(intents().items().stream().anyMatch(
            intent -> intent.status() == IntentStatus.READY
        ));
    }

    private io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore
        .ExecutionIntentPage intents() {
        return executionStore.findIntents(new ExecutionIntentCriteria(
            TENANT_ID,
            Set.of(),
            Set.of(),
            null,
            null,
            null,
            null,
            50,
            0
        ));
    }

    private void seedImmutablePolicy() {
        rawStore.createPolicy(new SlaPolicyIdentity(
            POLICY_ID,
            TENANT_ID,
            "transactional-policy",
            "Transactional policy",
            PolicyStatus.DRAFT,
            null,
            "designer-a",
            NOW.minus(Duration.ofDays(2)),
            NOW.minus(Duration.ofDays(2)),
            1
        ));
        rawStore.savePolicyVersion(policyVersion(), 1);
        rawStore.publishPolicyVersion(
            TENANT_ID,
            POLICY_ID,
            1,
            "publisher-a",
            NOW.minus(Duration.ofDays(1)),
            2
        );
        rawStore.activatePolicyVersion(
            TENANT_ID,
            POLICY_ID,
            1,
            "activator-a",
            NOW.minus(Duration.ofHours(12)),
            3
        );
    }

    private static SlaPolicyVersion policyVersion() {
        return new SlaPolicyVersion(
            POLICY_ID,
            TENANT_ID,
            1,
            "purchasePayment",
            1,
            "managerApproval",
            SlaTargetType.TASK,
            SlaDurationMode.NATURAL_TIME,
            Duration.ofHours(2),
            null,
            null,
            Duration.ofMinutes(30),
            Duration.ofMinutes(15),
            2,
            Duration.ofMinutes(10),
            EscalationTargetType.USER,
            "manager-review",
            AutomaticAction.AUTO_TRANSFER,
            true,
            "c".repeat(64),
            PolicyStatus.DRAFT,
            false,
            null,
            null,
            NOW.minus(Duration.ofDays(2)),
            NOW.minus(Duration.ofDays(2))
        );
    }

    private static SlaInstance slaInstance(UUID slaInstanceId, String requestId) {
        return slaInstance(slaInstanceId, TASK_ID, requestId);
    }

    private static SlaInstance slaInstance(
        UUID slaInstanceId,
        UUID taskId,
        String requestId
    ) {
        return new SlaInstance(
            slaInstanceId,
            TENANT_ID,
            APPROVAL_INSTANCE_ID,
            taskId,
            null,
            "purchasePayment",
            "managerApproval",
            SlaTargetType.TASK,
            POLICY_ID,
            1,
            null,
            null,
            "UTC",
            "owner-a",
            "owner-a",
            NOW,
            NOW.plus(Duration.ofHours(2)),
            NOW.plus(Duration.ofHours(1)),
            NOW.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(10)),
            null,
            null,
            Duration.ZERO,
            null,
            null,
            SlaStatus.ACTIVE,
            0,
            requestId,
            "trace-create",
            1,
            NOW,
            NOW
        );
    }

    private static void seedApprovalEvidence(JdbcTemplate jdbc) {
        jdbc.update(
            """
            insert into ap_definition_version (
                tenant_id, definition_key, definition_version,
                form_key, form_version, compiler_version, content_hash,
                deployment_id, engine_definition_id, engine_version,
                published_by, published_at
            ) values (?, 'purchasePayment', 1, 'purchasePayment', 1, 'compiler-v1',
                repeat('a', 64), 'deployment-transactional',
                'engine-definition-transactional', 1, 'publisher-a', ?)
            """,
            TENANT_ID,
            java.sql.Timestamp.from(NOW.minus(Duration.ofDays(3)))
        );
        jdbc.update(
            """
            insert into ap_approval_instance (
                instance_id, tenant_id, business_key, engine_instance_id,
                definition_key, definition_version, form_key, form_version,
                compiler_version, content_hash, initiator_id, amount, supplier,
                purchase_order_reference, attachment_ids_json, assignee_snapshot_json,
                request_hash, status, version, created_at, updated_at
            ) values (?, ?, 'SLA-TRANSACTIONAL-1', 'engine-instance-transactional',
                'purchasePayment', 1, 'purchasePayment', 1,
                'compiler-v1', repeat('a', 64), 'initiator-a', 100, 'supplier-a',
                'PO-TRANSACTIONAL-1', '[]'::jsonb, '{}'::jsonb,
                repeat('b', 64), 'RUNNING', 1, ?, ?)
            """,
            APPROVAL_INSTANCE_ID,
            TENANT_ID,
            java.sql.Timestamp.from(NOW),
            java.sql.Timestamp.from(NOW)
        );
        insertTask(jdbc, TASK_ID, "engine-task-transactional", "owner-a");
        insertTask(jdbc, SECOND_TASK_ID, "engine-task-transactional-second", "owner-b");
    }

    private static void insertTask(
        JdbcTemplate jdbc,
        UUID taskId,
        String engineTaskId,
        String assigneeId
    ) {
        jdbc.update(
            """
            insert into ap_approval_task (
                task_id, instance_id, tenant_id, engine_task_id,
                task_definition_key, task_name, assignee_id, status,
                version, created_at, updated_at, completed_at
            ) values (?, ?, ?, ?, 'managerApproval', 'Manager approval', ?, 'PENDING',
                1, ?, ?, null)
            """,
            taskId,
            APPROVAL_INSTANCE_ID,
            TENANT_ID,
            engineTaskId,
            assigneeId,
            java.sql.Timestamp.from(NOW),
            java.sql.Timestamp.from(NOW)
        );
    }
}
