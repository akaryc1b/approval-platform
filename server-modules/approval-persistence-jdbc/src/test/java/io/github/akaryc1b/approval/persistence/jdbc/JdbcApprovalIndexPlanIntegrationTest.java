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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalIndexPlanIntegrationTest {

    private static final Set<String> REQUIRED_INDEXES = Set.of(
        "idx_audit_event_tenant_action_time",
        "idx_audit_event_tenant_aggregate",
        "idx_notification_dead_management",
        "idx_notification_dead_connector",
        "idx_notification_recipient_history",
        "idx_consistency_failed_management",
        "idx_consistency_finding_check",
        "idx_outbox_failure_queue"
    );

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_index_plan_test")
        .withUsername("approval")
        .withPassword("approval");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void requiredIndexesExist() {
        List<String> names = jdbc.queryForList(
            """
            select indexname from pg_indexes
            where schemaname = current_schema()
              and indexname = any (array[
                'idx_audit_event_tenant_action_time',
                'idx_audit_event_tenant_aggregate',
                'idx_notification_dead_management',
                'idx_notification_dead_connector',
                'idx_notification_recipient_history',
                'idx_consistency_failed_management',
                'idx_consistency_finding_check',
                'idx_outbox_failure_queue'
              ])
            """,
            String.class
        );
        assertEquals(REQUIRED_INDEXES, Set.copyOf(names));
    }

    @Test
    void auditManagementAndTimelinePlansUseAuditIndexes() {
        assertPlanUses(
            "select count(*) from ap_audit_event where tenant_id = 'tenant-a' "
                + "and action = 'TASK_APPROVED' and occurred_at >= now() - interval '1 day' "
                + "and occurred_at < now()",
            "idx_audit_event_tenant_action_time"
        );
        assertPlanUses(
            "select event_id from ap_audit_event where tenant_id = 'tenant-a' "
                + "and aggregate_type = 'APPROVAL_INSTANCE' and aggregate_id = 'instance-a' "
                + "order by occurred_at desc, tenant_sequence desc limit 100",
            "idx_audit_event_tenant_aggregate"
        );
    }

    @Test
    void notificationPlansUseDeadConnectorAndHistoryIndexes() {
        assertPlanUses(
            "select intent_id from ap_notification_intent where tenant_id = 'tenant-a' "
                + "and status = 'DEAD_LETTER' order by updated_at desc, intent_id limit 50",
            "idx_notification_dead_management"
        );
        assertPlanUses(
            "select intent_id from ap_notification_intent where tenant_id = 'tenant-a' "
                + "and status = 'DEAD_LETTER' and channel = 'CONNECTOR' "
                + "and coalesce(metadata_json ->> 'connectorKey', 'approval-connector') = 'erp' "
                + "order by updated_at desc, intent_id limit 50",
            "idx_notification_dead_connector"
        );
        assertPlanUses(
            "select intent_id from ap_notification_intent where tenant_id = 'tenant-a' "
                + "and recipient_id = 'operator-a' order by created_at desc, intent_id desc limit 20",
            "idx_notification_recipient_history"
        );
    }

    @Test
    void consistencyPlansUseFailedAndFindingIndexes() {
        assertPlanUses(
            "select check_id from ap_consistency_check where tenant_id = 'tenant-a' "
                + "and status = 'FAILED' order by completed_at desc, check_id limit 50",
            "idx_consistency_failed_management"
        );
        assertPlanUses(
            "select finding_id from ap_consistency_finding where tenant_id = 'tenant-a' "
                + "and check_id = uuid '00000000-0000-0000-0000-000000000001' "
                + "and severity = 'ERROR' and check_type = 'NOTIFICATION_DELIVERY' "
                + "order by detected_at, finding_id limit 100",
            "idx_consistency_finding_check"
        );
    }

    @Test
    void outboxDeadPlanUsesFailureQueueIndex() {
        assertPlanUses(
            "select id from ap_outbox where tenant_id = 'tenant-a' and status = 'DEAD' "
                + "order by updated_at desc, id limit 50",
            "idx_outbox_failure_queue"
        );
    }

    private static void assertPlanUses(String query, String indexName) {
        String plan = explain(query);
        assertTrue(plan.contains(indexName), () -> indexName + " missing from " + plan);
    }

    private static String explain(String query) {
        String result = jdbc.execute((ConnectionCallback<String>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set enable_seqscan = off");
                try (ResultSet resultSet = statement.executeQuery(
                    "explain (format json, costs false) " + query
                )) {
                    assertTrue(resultSet.next());
                    return resultSet.getString(1);
                }
            }
        });
        assertNotNull(result);
        return result;
    }
}
