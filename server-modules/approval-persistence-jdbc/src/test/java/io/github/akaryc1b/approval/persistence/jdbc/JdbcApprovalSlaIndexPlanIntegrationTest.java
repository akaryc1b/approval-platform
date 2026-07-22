package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcApprovalSlaIndexPlanIntegrationTest {

    private static final String TENANT_ID = "tenant-sla-index-plan";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_sla_index_plan_test")
        .withUsername("approval")
        .withPassword("approval");

    private JdbcTemplate jdbc;

    @BeforeAll
    void migrateAndSeedPlannerEvidence() {
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

        seedCalendars();
        seedPolicies();
        seedApprovalAndSlaEvidence();
        jdbc.execute("analyze");
    }

    @Test
    void activeCalendarVersionUsesTenantScopedLookupIndexes() throws Exception {
        JsonNode plan = explain(
            """
            select v.*
            from ap_work_calendar c
            join ap_work_calendar_version v
              on v.tenant_id=c.tenant_id
             and v.calendar_id=c.calendar_id
             and v.calendar_version=c.active_version
            where c.tenant_id=? and c.calendar_key=? and c.status='ACTIVE'
            """,
            TENANT_ID,
            "calendar-target"
        );

        assertUsesIndex(
            plan,
            "idx_work_calendar_active_lookup",
            "uk_work_calendar_active_key",
            "uk_work_calendar_key"
        );
    }

    @Test
    void activeSlaPolicyUsesEffectiveTargetIndex() throws Exception {
        JsonNode plan = explain(
            """
            select * from ap_sla_policy_version
            where tenant_id=? and definition_key=? and target_type='TASK'
              and status='ACTIVE'
              and (release_version=? or release_version is null)
              and task_definition_key is not distinct from ?
            order by case when release_version is not distinct from ? then 0 else 1 end,
                     policy_version desc,policy_id
            limit 1
            """,
            TENANT_ID,
            "purchasePayment",
            null,
            "managerApproval",
            null
        );

        assertUsesIndex(plan, "idx_sla_policy_active_lookup", "uk_sla_policy_active_target");
    }

    @Test
    void upcomingSlaUsesActiveDueIndex() throws Exception {
        JsonNode plan = explain(
            """
            select * from ap_sla_instance
            where tenant_id=? and status='ACTIVE'
              and due_at>timestamptz '2026-01-15 00:00:00+00'
              and due_at<=timestamptz '2026-01-15 01:00:00+00'
            order by due_at,sla_instance_id
            limit 50
            """,
            TENANT_ID
        );

        assertUsesIndex(plan, "idx_sla_instance_active_due");
    }

    @Test
    void overdueSlaUsesBoundedActiveTimeIndex() throws Exception {
        JsonNode plan = explain(
            """
            select * from ap_sla_instance
            where tenant_id=? and status='ACTIVE'
              and overdue_at<=timestamptz '2026-01-02 00:00:00+00'
            order by due_at,sla_instance_id
            limit 50
            """,
            TENANT_ID
        );

        assertUsesIndex(plan, "idx_sla_instance_overdue", "idx_sla_instance_active_due");
    }

    @Test
    void responsibleUpcomingSlaUsesResponsibleDueIndex() throws Exception {
        JsonNode plan = explain(
            """
            select * from ap_sla_instance
            where tenant_id=? and status='ACTIVE' and responsible_user_id=?
              and due_at>=timestamptz '2026-01-01 00:00:00+00'
              and due_at<=timestamptz '2026-02-01 00:00:00+00'
            order by due_at,sla_instance_id
            limit 50
            """,
            TENANT_ID,
            "owner-17"
        );

        assertUsesIndex(plan, "idx_sla_instance_responsible_active_due");
    }

    @Test
    void requestIdLookupUsesRequestIndex() throws Exception {
        JsonNode plan = explain(
            """
            select * from ap_sla_instance
            where tenant_id=? and request_id=?
            order by due_at,sla_instance_id
            limit 50
            """,
            TENANT_ID,
            "request-23457"
        );

        assertUsesIndex(plan, "idx_sla_instance_request_id");
    }

    @Test
    void approvalInstanceHistoryUsesApprovalInstanceIndex() throws Exception {
        JsonNode plan = explain(
            """
            select * from ap_sla_instance
            where tenant_id=? and approval_instance_id=md5('approval-777')::uuid
            order by created_at,sla_instance_id
            """,
            TENANT_ID
        );

        assertUsesIndex(plan, "idx_sla_instance_approval_instance");
    }

    @Test
    void participantTaskVisibilityUsesTaskIndexes() throws Exception {
        JsonNode plan = explain(
            """
            select s.* from ap_sla_instance s
            join ap_approval_task t on t.tenant_id=s.tenant_id and t.task_id=s.task_id
            where s.tenant_id=? and s.task_id=md5('task-12345')::uuid
              and s.status in ('ACTIVE','PAUSED')
              and (s.responsible_user_id=? or s.original_responsible_user_id=?
                   or t.assignee_id=?)
            order by s.created_at desc limit 1
            """,
            TENANT_ID,
            "owner-345",
            "owner-345",
            "owner-345"
        );

        assertUsesIndex(plan, "idx_sla_instance_task", "uk_approval_task_tenant_task");
    }

    @Test
    void responsibilityHistoryUsesAppendOnlyHistoryIndex() throws Exception {
        JsonNode plan = explain(
            """
            select * from ap_sla_responsibility_change
            where tenant_id=? and sla_instance_id=md5('sla-12345')::uuid
            order by changed_at desc,responsibility_change_id desc
            limit 100
            """,
            TENANT_ID
        );

        assertUsesIndex(plan, "idx_sla_responsibility_history");
    }

    private JsonNode explain(String sql, Object... arguments) throws Exception {
        String json = jdbc.queryForObject("explain (format json) " + sql, String.class, arguments);
        assertNotNull(json, "PostgreSQL must return a JSON execution plan");
        JsonNode root = JSON.readTree(json);
        assertTrue(root.isArray() && !root.isEmpty(), () -> "invalid JSON plan: " + json);
        JsonNode plan = root.get(0).get("Plan");
        assertNotNull(plan, () -> "missing Plan node: " + json);
        return plan;
    }

    private static void assertUsesIndex(JsonNode plan, String... expectedIndexes) {
        Set<String> indexNames = new HashSet<>();
        List<String> nodeTypes = new ArrayList<>();
        collectPlanEvidence(plan, indexNames, nodeTypes);
        Set<String> expected = Set.of(expectedIndexes);
        assertTrue(
            indexNames.stream().anyMatch(expected::contains),
            () -> "expected one of " + expected + " but indexes were " + indexNames
                + " in plan " + plan.toPrettyString()
        );
        assertTrue(
            nodeTypes.stream().anyMatch(type -> type.contains("Index") || type.contains("Bitmap")),
            () -> "expected index or bitmap execution but nodes were " + nodeTypes
                + " in plan " + plan.toPrettyString()
        );
    }

    private static void collectPlanEvidence(
        JsonNode node,
        Set<String> indexNames,
        List<String> nodeTypes
    ) {
        if (node.hasNonNull("Node Type")) {
            nodeTypes.add(node.get("Node Type").asText());
        }
        if (node.hasNonNull("Index Name")) {
            indexNames.add(node.get("Index Name").asText());
        }
        JsonNode children = node.get("Plans");
        if (children != null && children.isArray()) {
            children.forEach(child -> collectPlanEvidence(child, indexNames, nodeTypes));
        }
    }

    private void seedCalendars() {
        jdbc.update(
            """
            insert into ap_work_calendar (
                calendar_id,tenant_id,calendar_key,display_name,time_zone,status,active_version,
                created_by,created_at,updated_at,version
            )
            select md5('calendar-' || g)::uuid, ?,
                   case when g=777 then 'calendar-target' else 'calendar-' || g end,
                   'Calendar ' || g, 'UTC',
                   case when g=777 then 'ACTIVE' else 'DRAFT' end,
                   null, 'planner-seed',
                   timestamptz '2026-01-01 00:00:00+00',
                   timestamptz '2026-01-01 00:00:00+00', 1
            from generate_series(1,25000) g
            """,
            TENANT_ID
        );
        jdbc.update(
            """
            insert into ap_work_calendar_version (
                calendar_id,tenant_id,calendar_version,time_zone,effective_from,effective_to,
                content_hash,status,immutable,published_by,published_at,created_at,updated_at
            )
            select md5('calendar-' || g)::uuid, ?, 1, 'UTC', null, null,
                   repeat(md5('calendar-version-' || g),2),
                   case when g=777 then 'ACTIVE' else 'DRAFT' end,
                   g=777,
                   case when g=777 then 'planner-publisher' else null end,
                   case when g=777 then timestamptz '2026-01-01 00:00:00+00' else null end,
                   timestamptz '2026-01-01 00:00:00+00',
                   timestamptz '2026-01-01 00:00:00+00'
            from generate_series(1,25000) g
            """,
            TENANT_ID
        );
        jdbc.update(
            """
            update ap_work_calendar set active_version=1
            where tenant_id=? and calendar_key='calendar-target'
            """,
            TENANT_ID
        );
    }

    private void seedPolicies() {
        jdbc.update(
            """
            insert into ap_sla_policy (
                policy_id,tenant_id,policy_key,display_name,status,active_version,
                created_by,created_at,updated_at,version
            )
            select md5('policy-' || g)::uuid, ?,
                   case when g=777 then 'policy-target' else 'policy-' || g end,
                   'Policy ' || g,
                   case when g=777 then 'ACTIVE' else 'DRAFT' end,
                   null, 'planner-seed',
                   timestamptz '2026-01-01 00:00:00+00',
                   timestamptz '2026-01-01 00:00:00+00', 1
            from generate_series(1,25000) g
            """,
            TENANT_ID
        );
        jdbc.update(
            """
            insert into ap_sla_policy_version (
                policy_id,tenant_id,policy_version,definition_key,release_version,
                task_definition_key,target_type,duration_mode,duration_millis,
                calendar_id,calendar_version,calendar_content_hash,time_zone,
                first_reminder_offset_millis,repeat_reminder_interval_millis,
                maximum_reminder_count,overdue_offset_millis,escalation_strategy,
                escalation_target,automatic_action_policy,pause_rules_json,content_hash,
                status,immutable,published_by,published_at,created_at,updated_at
            )
            select md5('policy-' || g)::uuid, ?, 1,
                   case when g=777 then 'purchasePayment' else 'definition-' || g end,
                   null,
                   case when g=777 then 'managerApproval' else 'task-' || g end,
                   'TASK','NATURAL_TIME',7200000,
                   null,null,null,'UTC',1800000,null,1,900000,null,null,'NONE',
                   '{}'::jsonb,repeat(md5('policy-version-' || g),2),
                   case when g=777 then 'ACTIVE' else 'DRAFT' end,
                   g=777,
                   case when g=777 then 'planner-publisher' else null end,
                   case when g=777 then timestamptz '2026-01-01 00:00:00+00' else null end,
                   timestamptz '2026-01-01 00:00:00+00',
                   timestamptz '2026-01-01 00:00:00+00'
            from generate_series(1,25000) g
            """,
            TENANT_ID
        );
        jdbc.update(
            """
            update ap_sla_policy set active_version=1
            where tenant_id=? and policy_key='policy-target'
            """,
            TENANT_ID
        );
    }

    private void seedApprovalAndSlaEvidence() {
        jdbc.update(
            """
            insert into ap_definition_version (
                tenant_id,definition_key,definition_version,form_key,form_version,
                compiler_version,content_hash,deployment_id,engine_definition_id,engine_version,
                published_by,published_at
            ) values (?, 'purchasePayment', 1, 'purchasePayment', 1, 'compiler-plan',
                repeat('a',64), 'deployment-plan', 'engine-definition-plan', 1,
                'planner-publisher', timestamptz '2026-01-01 00:00:00+00')
            """,
            TENANT_ID
        );
        jdbc.update(
            """
            insert into ap_approval_instance (
                instance_id,tenant_id,business_key,engine_instance_id,
                definition_key,definition_version,form_key,form_version,
                compiler_version,content_hash,initiator_id,amount,supplier,
                purchase_order_reference,attachment_ids_json,assignee_snapshot_json,
                request_hash,status,version,created_at,updated_at
            )
            select md5('approval-' || g)::uuid, ?, 'PLAN-' || g, 'engine-plan-' || g,
                   'purchasePayment',1,'purchasePayment',1,'compiler-plan',repeat('a',64),
                   'initiator-' || (g % 200), g, 'supplier-' || g, 'PO-' || g,
                   '[]'::jsonb,'{}'::jsonb,repeat(md5('approval-request-' || g),2),
                   'RUNNING',1,
                   timestamptz '2025-12-31 00:00:00+00' + g * interval '1 second',
                   timestamptz '2025-12-31 00:00:00+00' + g * interval '1 second'
            from generate_series(1,2000) g
            """,
            TENANT_ID
        );
        jdbc.update(
            """
            insert into ap_approval_task (
                task_id,instance_id,tenant_id,engine_task_id,task_definition_key,
                task_name,assignee_id,status,version,created_at,updated_at,completed_at
            )
            select md5('task-' || g)::uuid,
                   md5('approval-' || ((g - 1) / 20 + 1))::uuid,
                   ?, 'engine-task-plan-' || g, 'managerApproval', 'Manager approval',
                   'owner-' || (g % 500), 'PENDING', 1,
                   timestamptz '2025-12-31 00:00:00+00' + g * interval '1 second',
                   timestamptz '2025-12-31 00:00:00+00' + g * interval '1 second', null
            from generate_series(1,40000) g
            """,
            TENANT_ID
        );
        jdbc.update(
            """
            insert into ap_sla_instance (
                sla_instance_id,tenant_id,approval_instance_id,task_id,
                collaboration_participant_id,definition_key,task_definition_key,target_type,
                policy_id,policy_version,calendar_id,calendar_version,time_zone,
                responsible_user_id,original_responsible_user_id,started_at,due_at,
                next_reminder_at,overdue_at,paused_at,pause_reason,accumulated_paused_millis,
                terminal_at,terminal_reason,status,last_action_sequence,request_id,trace_id,
                version,created_at,updated_at
            )
            select md5('sla-' || g)::uuid, ?,
                   md5('approval-' || ((g - 1) / 20 + 1))::uuid,
                   md5('task-' || g)::uuid, null,
                   'purchasePayment','managerApproval','TASK',md5('policy-777')::uuid,1,
                   null,null,'UTC','owner-' || (g % 500),'owner-' || (g % 500),
                   timestamptz '2025-12-31 00:00:00+00',
                   timestamptz '2026-01-01 00:00:00+00' + g * interval '1 minute',
                   timestamptz '2026-01-01 00:00:00+00' + g * interval '1 minute'
                       - interval '30 minutes',
                   timestamptz '2026-01-01 00:00:00+00' + g * interval '1 minute'
                       + interval '15 minutes',
                   null,null,0,
                   case when g % 10=0
                       then timestamptz '2026-01-01 00:00:00+00' + g * interval '1 minute'
                       else null end,
                   case when g % 10=0 then 'TASK_COMPLETED' else null end,
                   case when g % 10=0 then 'TERMINAL' else 'ACTIVE' end,
                   0,'request-' || g,'trace-' || g,1,
                   timestamptz '2025-12-31 00:00:00+00' + g * interval '1 second',
                   timestamptz '2025-12-31 00:00:00+00' + g * interval '1 second'
            from generate_series(1,40000) g
            """,
            TENANT_ID
        );
        jdbc.update(
            """
            insert into ap_sla_responsibility_change (
                responsibility_change_id,tenant_id,sla_instance_id,
                previous_responsible_user_id,new_responsible_user_id,source,reason,
                changed_by,changed_at,request_id,trace_id
            )
            select md5('change-' || g)::uuid, ?, md5('sla-' || g)::uuid,
                   'owner-' || (g % 500), 'owner-' || ((g + 1) % 500),
                   'MANUAL_TRANSFER','planner evidence','planner-operator',
                   timestamptz '2026-01-01 00:00:00+00' + g * interval '1 second',
                   'change-request-' || g,'change-trace-' || g
            from generate_series(1,40000) g
            """,
            TENANT_ID
        );
    }
}
