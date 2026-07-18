package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalParticipationQuery.ProcessedTaskCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalParticipationQuery.StartedInstanceCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalParticipationQueryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T05:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_participation_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcApprovalProjectionStore projections;
    private JdbcApprovalParticipationQuery query;

    @BeforeAll
    static void migrateDatabase() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    @BeforeEach
    void setUp() {
        new JdbcTemplate(dataSource).execute("""
            truncate table
                ap_audit_event,
                ap_approval_task,
                ap_approval_instance,
                ap_definition_version
            cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        projections = new JdbcApprovalProjectionStore(dataSource, objectMapper);
        query = new JdbcApprovalParticipationQuery(dataSource, objectMapper);
        saveDefinition("tenant-a");
        saveDefinition("tenant-b");
    }

    @Test
    void startedInstancesAreScopedAndExposeWithdrawalEligibility() {
        createInstance(
            1,
            "tenant-a",
            "initiator-a",
            "PO-STARTED-001",
            InstanceStatus.RUNNING,
            List.of(pendingTask(1, "managerApproval", "manager-1", NOW))
        );
        createInstance(
            2,
            "tenant-a",
            "initiator-a",
            "PO-REVISION-002",
            InstanceStatus.RUNNING,
            List.of(pendingTask(
                2,
                PurchasePaymentTemplate.REVISION_TASK_KEY,
                "initiator-a",
                NOW.plusSeconds(2)
            ))
        );
        createInstance(
            3,
            "tenant-a",
            "other-initiator",
            "PO-OTHER-003",
            InstanceStatus.RUNNING,
            List.of(pendingTask(3, "managerApproval", "manager-1", NOW.plusSeconds(3)))
        );
        createInstance(
            4,
            "tenant-b",
            "initiator-a",
            "PO-OTHER-TENANT-004",
            InstanceStatus.RUNNING,
            List.of(pendingTask(4, "managerApproval", "manager-1", NOW.plusSeconds(4)))
        );

        var page = query.findStartedInstances(new StartedInstanceCriteria(
            "tenant-a",
            "initiator-a",
            "po-",
            20,
            0
        ));

        assertEquals(2, page.total());
        assertEquals("PO-REVISION-002", page.items().getFirst().businessKey());
        assertFalse(page.items().getFirst().withdrawable());
        assertEquals("PO-STARTED-001", page.items().get(1).businessKey());
        assertTrue(page.items().get(1).withdrawable());
        assertEquals("managerApproval", page.items().get(1).currentTaskDefinitionKey());
    }

    @Test
    void processedTasksExposeOnlySafeLinearRetrieveCandidates() {
        createInstance(
            10,
            "tenant-a",
            "initiator-a",
            "PO-LINEAR-010",
            InstanceStatus.RUNNING,
            List.of(
                completedTask(10, "managerApproval", "manager-1", NOW.plusSeconds(1)),
                pendingTask(11, "financeReview", "finance-reviewer", NOW.plusSeconds(2))
            )
        );
        createInstance(
            20,
            "tenant-a",
            "initiator-a",
            "PO-PARALLEL-020",
            InstanceStatus.RUNNING,
            List.of(
                completedTask(20, "managerApproval", "manager-1", NOW.plusSeconds(3)),
                pendingTask(21, "financeCountersign", "finance-a", NOW.plusSeconds(4)),
                pendingTask(22, "financeCountersign", "finance-b", NOW.plusSeconds(4))
            )
        );
        createInstance(
            30,
            "tenant-a",
            "initiator-a",
            "PO-COMPLETED-030",
            InstanceStatus.COMPLETED,
            List.of(completedTask(30, "managerApproval", "manager-1", NOW.plusSeconds(5)))
        );

        var page = query.findProcessedTasks(new ProcessedTaskCriteria(
            "tenant-a",
            "manager-1",
            null,
            20,
            0
        ));

        assertEquals(3, page.total());
        assertFalse(page.items().getFirst().retrievable());
        assertEquals("PO-COMPLETED-030", page.items().getFirst().businessKey());
        assertFalse(page.items().get(1).retrievable());
        assertEquals("PO-PARALLEL-020", page.items().get(1).businessKey());
        assertTrue(page.items().get(2).retrievable());
        assertEquals("PO-LINEAR-010", page.items().get(2).businessKey());
    }

    private void saveDefinition(String tenantId) {
        projections.saveDefinition(new PublishedDefinition(
            tenantId,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            ApprovalDslCompiler.COMPILER_VERSION,
            "a".repeat(64),
            "deployment-" + tenantId,
            "definition-" + tenantId,
            1,
            "publisher",
            NOW
        ));
    }

    private void createInstance(
        int sequence,
        String tenantId,
        String initiatorId,
        String businessKey,
        InstanceStatus status,
        List<TaskProjection> sourceTasks
    ) {
        UUID instanceId = identifier(sequence);
        List<TaskProjection> tasks = sourceTasks.stream()
            .map(task -> new TaskProjection(
                task.taskId(),
                instanceId,
                tenantId,
                task.engineTaskId(),
                task.taskDefinitionKey(),
                task.name(),
                task.assigneeId(),
                task.status(),
                task.version(),
                task.createdAt(),
                task.updatedAt(),
                task.completedAt()
            ))
            .toList();
        projections.createInstance(new InstanceProjection(
            instanceId,
            tenantId,
            businessKey,
            "engine-instance-" + sequence,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.PROCESS_VERSION,
            PurchasePaymentTemplate.DEFINITION_KEY,
            PurchasePaymentTemplate.FORM_VERSION,
            ApprovalDslCompiler.COMPILER_VERSION,
            "a".repeat(64),
            initiatorId,
            new BigDecimal("1000.00").add(BigDecimal.valueOf(sequence)),
            "Supplier " + sequence,
            "PURCHASE-ORDER-" + sequence,
            List.of("attachment-" + sequence),
            new AssigneeSnapshot(
                "manager-1",
                "finance-reviewer",
                List.of("finance-a", "finance-b"),
                Map.of("source", "test")
            ),
            "b".repeat(64),
            status,
            1,
            NOW,
            NOW.plusSeconds(sequence)
        ), tasks);
    }

    private TaskProjection pendingTask(
        int sequence,
        String taskDefinitionKey,
        String assigneeId,
        Instant createdAt
    ) {
        return new TaskProjection(
            identifier(sequence + 1000),
            identifier(9999),
            "placeholder",
            "engine-task-" + sequence,
            taskDefinitionKey,
            taskDefinitionKey,
            assigneeId,
            TaskStatus.PENDING,
            1,
            createdAt,
            createdAt,
            null
        );
    }

    private TaskProjection completedTask(
        int sequence,
        String taskDefinitionKey,
        String assigneeId,
        Instant completedAt
    ) {
        return new TaskProjection(
            identifier(sequence + 1000),
            identifier(9999),
            "placeholder",
            "engine-task-" + sequence,
            taskDefinitionKey,
            taskDefinitionKey,
            assigneeId,
            TaskStatus.COMPLETED,
            2,
            completedAt.minusSeconds(1),
            completedAt,
            completedAt
        );
    }

    private static UUID identifier(int sequence) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(sequence));
    }
}
