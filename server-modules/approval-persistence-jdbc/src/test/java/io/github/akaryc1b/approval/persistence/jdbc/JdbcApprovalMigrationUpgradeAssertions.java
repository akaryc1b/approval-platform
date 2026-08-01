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
        "ap_process_migration_verification", "ap_process_migration_reconciliation",
        "ap_process_migration_plan", "ap_process_migration_plan_instance",
        "ap_process_migration_plan_authorization", "ap_process_migration_plan_event",
        "ap_process_migration_plan_consumption", "ap_process_migration_engine_request",
        "ap_process_migration_engine_outcome", "ap_process_migration_exact_verification",
        "ap_process_runtime_binding_evidence", "ap_process_migration_instance_completion",
        "ap_process_migration_binding_cas_conflict",
        "ap_process_migration_reconciliation_lease",
        "ap_process_migration_reconciliation_lease_event",
        "ap_process_migration_reconciliation_observation",
        "ap_process_migration_canary_selection",
        "ap_process_migration_orchestration_run",
        "ap_process_migration_orchestration_event",
        "ap_process_migration_orchestration_batch",
        "ap_process_migration_kill_switch_observation",
        "ap_process_migration_plan_aggregate",
        "ap_process_migration_plan_aggregate_event",
        "ap_process_migration_plan_completion"
    );
    private static final Set<String> M6_E_P4_TABLES = Set.of(
        "ap_ai_approval_assistance_evidence",
        "ap_ai_approval_assistance_evidence_state",
        "ap_ai_approval_assistance_evidence_event"
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
    private static final Set<String> M5_PLAN_INDEXES = Set.of(
        "idx_process_migration_plan_status_v38",
        "idx_process_migration_plan_assessment_v38",
        "idx_process_migration_plan_instance_v38",
        "idx_process_migration_plan_event_v38",
        "uq_process_migration_intent_admission_v39",
        "pk_process_migration_plan_consumption_v39",
        "uq_process_migration_plan_consumption_plan_v39",
        "uq_process_migration_plan_consumption_intent_v39",
        "uq_process_migration_plan_consumption_key_v39",
        "idx_process_migration_plan_consumption_time_v39",
        "idx_process_migration_engine_request_attempt_v41",
        "idx_process_migration_engine_outcome_attempt_v41",
        "idx_process_migration_exact_verification_attempt_v43",
        "idx_process_migration_exact_verification_class_v43",
        "idx_process_runtime_binding_evidence_instance_v44",
        "idx_process_runtime_binding_evidence_attempt_v44",
        "idx_process_migration_instance_completion_intent_v44",
        "idx_process_migration_binding_cas_conflict_intent_v44",
        "idx_process_migration_reconciliation_lease_active_v45",
        "idx_process_migration_reconciliation_observation_attempt_v45",
        "idx_process_migration_orchestration_run_plan_v47",
        "idx_process_migration_orchestration_event_run_v47",
        "idx_process_migration_kill_switch_observation_time_v47",
        "idx_process_migration_plan_aggregate_plan_v48",
        "idx_process_migration_plan_aggregate_status_v48",
        "idx_process_migration_plan_aggregate_unresolved_v48",
        "idx_process_migration_plan_aggregate_event_plan_v48",
        "idx_process_migration_plan_completion_time_v48"
    );
    private static final Set<String> M6_E_P4_INDEXES = Set.of(
        "idx_ai_assistance_evidence_retention_v49",
        "idx_ai_assistance_evidence_resource_v49",
        "idx_ai_assistance_evidence_class_v49",
        "idx_ai_assistance_evidence_state_v49",
        "idx_ai_assistance_evidence_event_v49"
    );

    private JdbcApprovalMigrationUpgradeAssertions() {
    }

    static void assertLatestSchema(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals(
            M4_TABLES,
            queryNames(jdbc, "table_name", "information_schema.tables", M4_TABLES)
        );
        assertEquals(
            M5_TABLES,
            queryNames(jdbc, "table_name", "information_schema.tables", M5_TABLES)
        );
        assertEquals(
            M6_E_P4_TABLES,
            queryNames(
                jdbc,
                "table_name",
                "information_schema.tables",
                M6_E_P4_TABLES
            )
        );
        assertEquals(M4_INDEXES, queryNames(jdbc, "indexname", "pg_indexes", M4_INDEXES));
        assertEquals(
            M5_PLAN_INDEXES,
            queryNames(jdbc, "indexname", "pg_indexes", M5_PLAN_INDEXES)
        );
        assertEquals(
            M6_E_P4_INDEXES,
            queryNames(jdbc, "indexname", "pg_indexes", M6_E_P4_INDEXES)
        );
    }

    private static Set<String> queryNames(
        JdbcTemplate jdbc,
        String column,
        String table,
        Set<String> expected
    ) {
        String placeholders = String.join(
            ",",
            java.util.Collections.nCopies(expected.size(), "?")
        );
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
