package io.github.akaryc1b.approval.persistence.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class JdbcApprovalMigrationUpgradeAssertions {

    private static final Set<String> M4_TABLES = Set.of(
        "ap_work_calendar", "ap_work_calendar_version",
        "ap_work_calendar_date_override", "ap_work_calendar_interval",
        "ap_sla_policy", "ap_sla_policy_version", "ap_sla_instance",
        "ap_sla_responsibility_change", "ap_sla_execution_intent",
        "ap_sla_execution_attempt", "ap_sla_execution_replay",
        "ap_process_release_lifecycle", "ap_process_release_lifecycle_history",
        "ap_process_runtime_binding"
    );
    private static final Set<String> M5_TABLES = Set.of(
        "ap_process_migration_intent", "ap_process_migration_intent_event",
        "ap_process_migration_attempt", "ap_process_migration_attempt_event",
        "ap_process_migration_verification", "ap_process_migration_reconciliation"
    );
    private static final Set<String> M4_INDEXES = Set.of(
        "idx_work_calendar_active_lookup", "idx_sla_policy_active_lookup",
        "idx_sla_instance_responsible_active_due", "idx_sla_instance_active_due",
        "idx_sla_instance_approval_instance", "idx_sla_instance_task",
        "idx_sla_instance_request_id", "idx_sla_execution_intent_ready_poll",
        "idx_sla_execution_intent_expired_lease", "idx_sla_execution_intent_dead_management",
        "idx_sla_execution_intent_sla_history", "idx_sla_execution_intent_request",
        "idx_sla_execution_attempt_history", "idx_sla_execution_replay_original",
        "uk_process_release_single_active", "idx_process_release_lifecycle_list",
        "idx_process_release_lifecycle_state", "idx_process_release_history_timeline",
        "idx_process_release_history_request", "idx_process_runtime_binding_release_usage",
        "idx_process_runtime_binding_business_key"
    );

    private JdbcApprovalMigrationUpgradeAssertions() {
    }

    static void assertLatestSchema(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals(M4_TABLES, queryNames(jdbc, "table_name", "information_schema.tables", M4_TABLES));
        assertEquals(M5_TABLES, queryNames(jdbc, "table_name", "information_schema.tables", M5_TABLES));
        assertEquals(M4_INDEXES, queryNames(jdbc, "indexname", "pg_indexes", M4_INDEXES));
    }

    private static Set<String> queryNames(
        JdbcTemplate jdbc,
        String column,
        String table,
        Set<String> expected
    ) {
        String placeholders = String.join(",", java.util.Collections.nCopies(expected.size(), "?"));
        List<String> names = jdbc.queryForList(
            "select " + column + " from " + table
                + " where " + (table.equals("pg_indexes") ? "schemaname" : "table_schema")
                + " = current_schema() and " + column + " in (" + placeholders + ")",
            String.class,
            expected.toArray()
        );
        return Set.copyOf(names);
    }
}
