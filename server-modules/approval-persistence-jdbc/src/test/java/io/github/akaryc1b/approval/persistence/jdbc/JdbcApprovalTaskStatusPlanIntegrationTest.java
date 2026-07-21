package io.github.akaryc1b.approval.persistence.jdbc;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalTaskStatusPlanIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_task_status_plan_test")
        .withUsername("approval")
        .withPassword("approval");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAndSeed() {
        DataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        seedTaskStatusDistribution();
        jdbc.execute("analyze ap_approval_instance");
        jdbc.execute("analyze ap_approval_task");
    }

    @Test
    void statusSpecificIndexesReplaceLegacyAssigneeIndex() {
        List<String> names = jdbc.queryForList(
            """
            select indexname
            from pg_indexes
            where schemaname = current_schema()
              and indexname in (
                'idx_approval_task_pending_assignee_page',
                'idx_approval_task_completed_assignee_page',
                'ap_approval_task_assignee_idx'
              )
            order by indexname
            """,
            String.class
        );
        assertEquals(
            Set.of(
                "idx_approval_task_pending_assignee_page",
                "idx_approval_task_completed_assignee_page"
            ),
            Set.copyOf(names)
        );
        assertFalse(names.contains("ap_approval_task_assignee_idx"));
    }

    @Test
    void pendingAndCompletedViewsUseTheirStableOrderingIndexes() {
        assertPlanUses(
            "select task.task_id from ap_approval_task task "
                + "join ap_approval_instance instance "
                + "on instance.tenant_id = task.tenant_id "
                + "and instance.instance_id = task.instance_id "
                + "where task.tenant_id = 'tenant-a' "
                + "and task.assignee_id = 'operator-target' "
                + "and task.status = 'PENDING' and instance.status = 'RUNNING' "
                + "order by task.created_at, task.task_id limit 20 offset 500",
            "idx_approval_task_pending_assignee_page"
        );
        assertPlanUses(
            "select task.task_id from ap_approval_task task "
                + "join ap_approval_instance instance "
                + "on instance.tenant_id = task.tenant_id "
                + "and instance.instance_id = task.instance_id "
                + "where task.tenant_id = 'tenant-a' "
                + "and task.assignee_id = 'operator-target' "
                + "and task.status = 'COMPLETED' "
                + "order by task.completed_at desc, task.task_id desc "
                + "limit 20 offset 500",
            "idx_approval_task_completed_assignee_page"
        );
    }

    @Test
    void completedDeepPageIsStableAndTenantScoped() {
        List<UUID> page = completedPage("tenant-a", 500);
        assertEquals(20, page.size());
        assertEquals(page, completedPage("tenant-a", 500));
        assertTrue(page.stream().noneMatch(completedPage("tenant-b", 500)::contains));
    }

    private static List<UUID> completedPage(String tenantId, int offset) {
        return jdbc.queryForList(
            """
            select task.task_id
            from ap_approval_task task
            join ap_approval_instance instance
              on instance.tenant_id = task.tenant_id
             and instance.instance_id = task.instance_id
            where task.tenant_id = ?
              and task.assignee_id = 'operator-target'
              and task.status = 'COMPLETED'
            order by task.completed_at desc, task.task_id desc
            limit 20 offset ?
            """,
            UUID.class,
            tenantId,
            offset
        );
    }

    private static void seedTaskStatusDistribution() {
        jdbc.execute("""
            insert into ap_definition_version (
                tenant_id, definition_key, definition_version,
                form_key, form_version, compiler_version, content_hash,
                deployment_id, engine_definition_id, engine_version,
                published_by, published_at
            )
            select tenant_id, 'purchase-payment', 1,
                   'purchase-payment-form', 1, 'approval-compiler-v1', repeat('a', 64),
                   'deployment-' || tenant_id, 'definition-' || tenant_id, 1,
                   'publisher', timestamptz '2026-07-20 00:00:00+00'
            from (values ('tenant-a'), ('tenant-b')) tenants(tenant_id)
            """);
        jdbc.execute("""
            insert into ap_approval_instance (
                instance_id, tenant_id, business_key, engine_instance_id,
                definition_key, definition_version, form_key, form_version,
                compiler_version, content_hash, initiator_id, amount, supplier,
                purchase_order_reference, attachment_ids_json,
                assignee_snapshot_json, request_hash, status, version,
                created_at, updated_at
            )
            select
                md5('status-instance-' || tenant_id || '-' || sample)::uuid,
                tenant_id,
                'status-business-' || tenant_id || '-' || sample,
                'status-engine-instance-' || tenant_id || '-' || sample,
                'purchase-payment', 1, 'purchase-payment-form', 1,
                'approval-compiler-v1', repeat('a', 64),
                'initiator-' || (sample % 100), sample::numeric,
                'supplier-' || sample, 'status-po-' || tenant_id || '-' || sample,
                '[]'::jsonb, '{}'::jsonb, repeat('b', 64),
                'RUNNING', 1,
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second'
            from (values ('tenant-a'), ('tenant-b')) tenants(tenant_id)
            cross join generate_series(1, 12000) sample
            """);
        jdbc.execute("""
            insert into ap_approval_task (
                task_id, instance_id, tenant_id, engine_task_id,
                task_definition_key, task_name, assignee_id,
                status, version, created_at, updated_at, completed_at
            )
            select
                md5('status-task-' || tenant_id || '-' || sample)::uuid,
                md5('status-instance-' || tenant_id || '-' || sample)::uuid,
                tenant_id,
                'status-engine-task-' || tenant_id || '-' || sample,
                'managerApproval', 'Manager approval',
                case when sample % 5 = 0 then 'operator-target'
                     else 'operator-' || (sample % 200) end,
                case when sample % 2 = 0 then 'COMPLETED' else 'PENDING' end,
                1,
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                case when sample % 2 = 0
                     then timestamptz '2026-07-21 00:00:00+00'
                         + sample * interval '1 second' else null end
            from (values ('tenant-a'), ('tenant-b')) tenants(tenant_id)
            cross join generate_series(1, 12000) sample
            """);
    }

    private static void assertPlanUses(String query, String indexName) {
        String plan = explain(query);
        assertTrue(plan.contains(indexName), () -> indexName + " missing from " + plan);
    }

    private static String explain(String query) {
        String result = jdbc.execute((ConnectionCallback<String>) connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                     "explain (analyze, buffers, format json, costs false, "
                         + "timing false, summary false) " + query
                 )) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        });
        assertNotNull(result);
        return result;
    }
}
