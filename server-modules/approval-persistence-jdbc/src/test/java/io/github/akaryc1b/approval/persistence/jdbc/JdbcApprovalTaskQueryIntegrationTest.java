package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.AssigneeSnapshot;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalTaskQuery.PendingTaskIdentity;
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
class JdbcApprovalTaskQueryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T01:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_task_query_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private ApprovalProjectionStore projectionStore;
    private JdbcApprovalTaskQuery taskQuery;

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
                ap_approval_task,
                ap_approval_instance,
                ap_definition_version
            cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        projectionStore = new JdbcApprovalProjectionStore(dataSource, objectMapper);
        taskQuery = new JdbcApprovalTaskQuery(dataSource, objectMapper);
        saveDefinition("tenant-a");
        saveDefinition("tenant-b");
    }

    @Test
    void pendingTasksAreScopedByTenantAndAssigneeAndSupportKeywordSearch() {
        createInstance(
            1,
            "tenant-a",
            "manager-a",
            "PO-SEARCH-001",
            "Supplier Alpha",
            TaskStatus.PENDING,
            NOW
        );
        createInstance(
            2,
            "tenant-a",
            "manager-b",
            "PO-SEARCH-002",
            "Supplier Beta",
            TaskStatus.PENDING,
            NOW.plusSeconds(1)
        );
        createInstance(
            3,
            "tenant-b",
            "manager-a",
            "PO-SEARCH-003",
            "Supplier Gamma",
            TaskStatus.PENDING,
            NOW.plusSeconds(2)
        );
        createInstance(
            4,
            "tenant-a",
            "manager-a",
            "PO-COMPLETED-004",
            "Supplier Alpha",
            TaskStatus.COMPLETED,
            NOW.plusSeconds(3)
        );

        var page = taskQuery.findPendingTasks(new PendingTaskCriteria(
            "tenant-a",
            "manager-a",
            "search",
            20,
            0
        ));

        assertEquals(1, page.total());
        assertEquals(1, page.items().size());
        assertEquals("PO-SEARCH-001", page.items().getFirst().businessKey());
        assertFalse(page.hasMore());

        var supplierSearch = taskQuery.findPendingTasks(new PendingTaskCriteria(
            "tenant-a",
            "manager-a",
            "SUPPLIER ALPHA",
            20,
            0
        ));
        assertEquals(1, supplierSearch.total());

        var details = taskQuery.findPendingTask(new PendingTaskIdentity(
            "tenant-a",
            "manager-a",
            identifier(1001)
        ));
        assertTrue(details.isPresent());
        assertEquals("PO-SEARCH-001", details.orElseThrow().businessKey());
        assertEquals(List.of("attachment-1"), details.orElseThrow().attachmentIds());

        assertTrue(taskQuery.findPendingTask(new PendingTaskIdentity(
            "tenant-a",
            "manager-b",
            identifier(1001)
        )).isEmpty());
        assertTrue(taskQuery.findPendingTask(new PendingTaskIdentity(
            "tenant-b",
            "manager-a",
            identifier(1001)
        )).isEmpty());
        assertTrue(taskQuery.findPendingTask(new PendingTaskIdentity(
            "tenant-a",
            "manager-a",
            identifier(1004)
        )).isEmpty());
    }

    @Test
    void pendingTaskPagesKeepTheFullTotalAndUseStableOldestFirstOrdering() {
        createInstance(
            10,
            "tenant-a",
            "manager-a",
            "PO-PAGE-010",
            "Supplier A",
            TaskStatus.PENDING,
            NOW.plusSeconds(10)
        );
        createInstance(
            11,
            "tenant-a",
            "manager-a",
            "PO-PAGE-011",
            "Supplier B",
            TaskStatus.PENDING,
            NOW.plusSeconds(11)
        );
        createInstance(
            12,
            "tenant-a",
            "manager-a",
            "PO-PAGE-012",
            "Supplier C",
            TaskStatus.PENDING,
            NOW.plusSeconds(12)
        );

        var firstPage = taskQuery.findPendingTasks(new PendingTaskCriteria(
            "tenant-a",
            "manager-a",
            null,
            2,
            0
        ));
        assertEquals(3, firstPage.total());
        assertEquals(List.of("PO-PAGE-010", "PO-PAGE-011"), firstPage.items().stream()
            .map(item -> item.businessKey())
            .toList());
        assertTrue(firstPage.hasMore());

        var secondPage = taskQuery.findPendingTasks(new PendingTaskCriteria(
            "tenant-a",
            "manager-a",
            null,
            2,
            2
        ));
        assertEquals(3, secondPage.total());
        assertEquals(List.of("PO-PAGE-012"), secondPage.items().stream()
            .map(item -> item.businessKey())
            .toList());
        assertFalse(secondPage.hasMore());
    }

    private void saveDefinition(String tenantId) {
        projectionStore.saveDefinition(new PublishedDefinition(
            tenantId,
            "purchase-payment",
            1,
            "purchase-payment-form",
            1,
            "approval-compiler-v1",
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
        String assigneeId,
        String businessKey,
        String supplier,
        TaskStatus taskStatus,
        Instant createdAt
    ) {
        UUID instanceId = identifier(sequence);
        UUID taskId = identifier(sequence + 1000);
        InstanceProjection instance = new InstanceProjection(
            instanceId,
            tenantId,
            businessKey,
            "engine-instance-" + sequence,
            "purchase-payment",
            1,
            "purchase-payment-form",
            1,
            "approval-compiler-v1",
            "a".repeat(64),
            "initiator-" + sequence,
            new BigDecimal("1000.00").add(BigDecimal.valueOf(sequence)),
            supplier,
            "PURCHASE-ORDER-" + sequence,
            List.of("attachment-" + sequence),
            new AssigneeSnapshot(
                assigneeId,
                "finance-reviewer",
                List.of("finance-a", "finance-b"),
                Map.of("source", "test")
            ),
            "b".repeat(64),
            InstanceStatus.RUNNING,
            1,
            createdAt,
            createdAt
        );
        TaskProjection task = new TaskProjection(
            taskId,
            instanceId,
            tenantId,
            "engine-task-" + sequence,
            "managerApproval",
            "Manager approval",
            assigneeId,
            taskStatus,
            1,
            createdAt,
            createdAt,
            taskStatus == TaskStatus.COMPLETED ? createdAt : null
        );
        projectionStore.createInstance(instance, List.of(task));
    }

    private static UUID identifier(int sequence) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(sequence));
    }
}
