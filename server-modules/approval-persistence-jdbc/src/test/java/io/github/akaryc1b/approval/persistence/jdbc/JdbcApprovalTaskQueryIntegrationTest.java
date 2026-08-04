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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalTaskQueryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T01:00:00Z");
    private static final int FORM_VERSION = 1;
    private static final int FORM_PACKAGE_VERSION = 7;
    private static final String FORM_PACKAGE_HASH = "c".repeat(64);
    private static final String FORM_CONTENT_HASH = "d".repeat(64);
    private static final int UI_SCHEMA_VERSION = 5;
    private static final String UI_SCHEMA_HASH = "e".repeat(64);
    private static final String FORM_SCHEMA_VERSION = "form-schema-2026-08";
    private static final int FORM_SCHEMA_FIELD_COUNT = 17;

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
                ap_definition_version,
                ap_form_package,
                ap_form_design_draft,
                ap_form_ui_schema,
                ap_form_definition
            cascade
            """);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        projectionStore = new JdbcApprovalProjectionStore(dataSource, objectMapper);
        taskQuery = new JdbcApprovalTaskQuery(dataSource, objectMapper);
        saveDefinition("tenant-a");
        saveDefinition("tenant-b");
        saveSchemaProvenance("tenant-a");
        saveSchemaProvenance("tenant-b");
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
        var pending = details.orElseThrow();
        assertEquals("PO-SEARCH-001", pending.businessKey());
        assertEquals(List.of("attachment-1"), pending.attachmentIds());
        assertNull(pending.releaseVersion());
        assertNull(pending.releasePackageHash());
        assertEquals(FORM_PACKAGE_VERSION, pending.formPackageVersion());
        assertEquals(FORM_PACKAGE_HASH, pending.formPackageHash());
        assertEquals(FORM_CONTENT_HASH, pending.formContentHash());
        assertEquals(UI_SCHEMA_VERSION, pending.uiSchemaVersion());
        assertEquals(UI_SCHEMA_HASH, pending.uiSchemaHash());
        assertEquals(FORM_SCHEMA_VERSION, pending.formSchemaVersion());
        assertEquals(FORM_SCHEMA_FIELD_COUNT, pending.formSchemaFieldCount());

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
            FORM_VERSION,
            "approval-compiler-v1",
            "a".repeat(64),
            "deployment-" + tenantId,
            "definition-" + tenantId,
            1,
            "publisher",
            NOW
        ));
    }

    private void saveSchemaProvenance(String tenantId) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update(
            """
            insert into ap_form_definition (
                tenant_id, form_key, form_version, schema_version, name,
                field_count, schema_json, content_hash, published_by, published_at
            ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
            """,
            tenantId,
            "purchase-payment-form",
            FORM_VERSION,
            FORM_SCHEMA_VERSION,
            "Purchase payment form",
            FORM_SCHEMA_FIELD_COUNT,
            "{}",
            FORM_CONTENT_HASH,
            "publisher",
            Timestamp.from(NOW)
        );
        jdbc.update(
            """
            insert into ap_form_ui_schema (
                tenant_id, form_key, form_version, ui_schema_version, schema_version,
                name, section_count, schema_json, content_hash, published_by, published_at
            ) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
            """,
            tenantId,
            "purchase-payment-form",
            FORM_VERSION,
            UI_SCHEMA_VERSION,
            "ui-schema-2026-08",
            "Purchase payment UI",
            1,
            "{}",
            UI_SCHEMA_HASH,
            "publisher",
            Timestamp.from(NOW)
        );
        UUID formDraftId = UUID.randomUUID();
        jdbc.update(
            """
            insert into ap_form_design_draft (
                tenant_id, draft_id, form_key, name, form_version,
                ui_schema_version, form_schema_json, ui_schema_json,
                revision, status, created_by, updated_by, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb),
                      1, 'DRAFT', 'publisher', 'publisher', ?, ?)
            """,
            tenantId,
            formDraftId,
            "purchase-payment-form",
            "Purchase payment form",
            FORM_VERSION,
            UI_SCHEMA_VERSION,
            "{}",
            "{}",
            Timestamp.from(NOW),
            Timestamp.from(NOW)
        );
        jdbc.update(
            """
            insert into ap_form_package (
                tenant_id, form_key, package_version, form_version, form_hash,
                ui_schema_version, ui_schema_hash, package_hash, source_draft_id,
                published_by, published_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            tenantId,
            "purchase-payment-form",
            FORM_PACKAGE_VERSION,
            FORM_VERSION,
            FORM_CONTENT_HASH,
            UI_SCHEMA_VERSION,
            UI_SCHEMA_HASH,
            FORM_PACKAGE_HASH,
            formDraftId,
            "publisher",
            Timestamp.from(NOW)
        );
        jdbc.update(
            """
            update ap_form_design_draft
            set status = 'PUBLISHED', published_package_version = ?
            where tenant_id = ? and draft_id = ?
            """,
            FORM_PACKAGE_VERSION,
            tenantId,
            formDraftId
        );
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
            FORM_VERSION,
            "approval-compiler-v1",
            "a".repeat(64),
            null,
            null,
            FORM_PACKAGE_VERSION,
            FORM_PACKAGE_HASH,
            UI_SCHEMA_VERSION,
            UI_SCHEMA_HASH,
            "definition-" + tenantId,
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
