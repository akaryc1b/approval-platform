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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalParticipantQueryScaleIntegrationTest {

    private static final Set<String> REQUIRED_INDEXES = Set.of(
        "idx_approval_task_pending_assignee_page",
        "idx_delegation_principal_history",
        "idx_handover_principal_history",
        "idx_collaboration_participant_pending_page"
    );

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_participant_query_scale_test")
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
        seedDefinitionsInstancesAndTasks();
        seedDelegationRules();
        seedHandovers();
        seedCollaborationParticipants();
        seedCommentsAndRevisions();
        for (String table : List.of(
            "ap_approval_instance",
            "ap_approval_task",
            "ap_delegation_rule",
            "ap_principal_handover",
            "ap_task_collaboration_policy",
            "ap_task_collaboration_participant",
            "ap_approval_comment",
            "ap_approval_comment_revision"
        )) {
            jdbc.execute("analyze " + table);
        }
    }

    @Test
    void scaledParticipantIndexesExist() {
        List<String> names = jdbc.queryForList(
            """
            select indexname
            from pg_indexes
            where schemaname = current_schema()
              and indexname = any (array[
                'idx_approval_task_pending_assignee_page',
                'idx_delegation_principal_history',
                'idx_handover_principal_history',
                'idx_collaboration_participant_pending_page'
              ])
            """,
            String.class
        );
        assertEquals(REQUIRED_INDEXES, Set.copyOf(names));
    }

    @Test
    void participantQueriesUseNaturalIndexesAtScale() {
        assertPlanUses(
            "select task.task_id from ap_approval_task task "
                + "join ap_approval_instance instance "
                + "on instance.tenant_id = task.tenant_id "
                + "and instance.instance_id = task.instance_id "
                + "where task.tenant_id = 'tenant-a' "
                + "and task.assignee_id = 'assignee-target' "
                + "and task.status = 'PENDING' and instance.status = 'RUNNING' "
                + "order by task.created_at, task.task_id limit 20 offset 40",
            "idx_approval_task_pending_assignee_page"
        );
        assertPlanUses(
            "select rule_id from ap_delegation_rule where tenant_id = 'tenant-a' "
                + "and principal_id = 'principal-target' "
                + "order by created_at desc, rule_id desc limit 20 offset 40",
            "idx_delegation_principal_history"
        );
        assertPlanUses(
            "select handover_id from ap_principal_handover where tenant_id = 'tenant-a' "
                + "and principal_id = 'principal-target' "
                + "order by created_at desc, handover_id desc limit 20 offset 40",
            "idx_handover_principal_history"
        );
        assertPlanUses(
            "select participant.participant_id "
                + "from ap_task_collaboration_participant participant "
                + "join ap_task_collaboration_policy policy "
                + "on policy.tenant_id = participant.tenant_id "
                + "and policy.policy_id = participant.policy_id "
                + "join ap_approval_task task on task.tenant_id = policy.tenant_id "
                + "and task.task_id = policy.task_id "
                + "where participant.tenant_id = 'tenant-a' "
                + "and participant.participant_user_id = 'participant-target' "
                + "and participant.status = 'PENDING' "
                + "and policy.status = 'ACTIVE' and task.status = 'PENDING' "
                + "order by participant.added_at, participant.participant_id "
                + "limit 20 offset 40",
            "idx_collaboration_participant_pending_page"
        );
        assertPlanUsesAny(
            "select comment_id from ap_approval_comment comment "
                + "where comment.tenant_id = 'tenant-a' "
                + "and comment.instance_id = md5('instance-tenant-a-1')::uuid "
                + "and (comment.visibility = 'PARTICIPANTS' "
                + "or comment.author_id = 'viewer-a' "
                + "or jsonb_exists(comment.mention_ids_json, 'viewer-a')) "
                + "order by comment.created_at, comment.comment_id "
                + "limit 20 offset 1000",
            Set.of("ap_approval_comment_instance_idx", "idx_approval_comment_visibility")
        );
    }

    @Test
    void normalAndDeepPagesRemainStableAndTenantScoped() {
        List<UUID> firstTasks = taskPage("tenant-a", 0);
        List<UUID> deepTasks = taskPage("tenant-a", 80);
        List<UUID> repeatedTasks = taskPage("tenant-a", 80);
        List<UUID> otherTasks = taskPage("tenant-b", 80);

        assertEquals(20, firstTasks.size());
        assertEquals(20, deepTasks.size());
        assertEquals(deepTasks, repeatedTasks);
        assertFalse(new HashSet<>(firstTasks).removeAll(deepTasks));
        assertTrue(new HashSet<>(deepTasks).stream().noneMatch(otherTasks::contains));

        List<UUID> firstComments = commentPage("tenant-a", 0);
        List<UUID> deepComments = commentPage("tenant-a", 1000);
        List<UUID> repeatedComments = commentPage("tenant-a", 1000);
        List<UUID> otherComments = commentPage("tenant-b", 1000);

        assertEquals(20, firstComments.size());
        assertEquals(20, deepComments.size());
        assertEquals(deepComments, repeatedComments);
        assertFalse(new HashSet<>(firstComments).removeAll(deepComments));
        assertTrue(new HashSet<>(deepComments).stream().noneMatch(otherComments::contains));
    }

    private static List<UUID> taskPage(String tenantId, int offset) {
        return jdbc.queryForList(
            """
            select task.task_id
            from ap_approval_task task
            join ap_approval_instance instance
              on instance.tenant_id = task.tenant_id
             and instance.instance_id = task.instance_id
            where task.tenant_id = ?
              and task.assignee_id = 'assignee-target'
              and task.status = 'PENDING'
              and instance.status = 'RUNNING'
            order by task.created_at, task.task_id
            limit 20 offset ?
            """,
            UUID.class,
            tenantId,
            offset
        );
    }

    private static List<UUID> commentPage(String tenantId, int offset) {
        return jdbc.queryForList(
            """
            select comment_id
            from ap_approval_comment
            where tenant_id = ?
              and instance_id = md5('instance-' || ? || '-1')::uuid
              and visibility = 'PARTICIPANTS'
            order by created_at, comment_id
            limit 20 offset ?
            """,
            UUID.class,
            tenantId,
            tenantId,
            offset
        );
    }

    private static void seedDefinitionsInstancesAndTasks() {
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
                md5('instance-' || tenant_id || '-' || sample)::uuid,
                tenant_id,
                'business-' || tenant_id || '-' || sample,
                'engine-instance-' || tenant_id || '-' || sample,
                'purchase-payment', 1, 'purchase-payment-form', 1,
                'approval-compiler-v1', repeat('a', 64),
                'initiator-' || (sample % 200), sample::numeric,
                'supplier-' || sample, 'po-' || tenant_id || '-' || sample,
                '[]'::jsonb, '{}'::jsonb, repeat('b', 64),
                'RUNNING', 1,
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second'
            from (values ('tenant-a'), ('tenant-b')) tenants(tenant_id)
            cross join generate_series(1, 6000) sample
            """);
        jdbc.execute("""
            insert into ap_approval_task (
                task_id, instance_id, tenant_id, engine_task_id,
                task_definition_key, task_name, assignee_id,
                status, version, created_at, updated_at, completed_at
            )
            select
                md5('task-' || tenant_id || '-' || sample)::uuid,
                md5('instance-' || tenant_id || '-' || sample)::uuid,
                tenant_id,
                'engine-task-' || tenant_id || '-' || sample,
                'managerApproval', 'Manager approval',
                case when sample % 50 = 0 then 'assignee-target'
                     else 'assignee-' || (sample % 200) end,
                case when sample % 97 = 0 then 'COMPLETED' else 'PENDING' end,
                1,
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                case when sample % 97 = 0
                     then timestamptz '2026-07-20 00:00:00+00'
                         + sample * interval '1 second' else null end
            from (values ('tenant-a'), ('tenant-b')) tenants(tenant_id)
            cross join generate_series(1, 6000) sample
            """);
    }

    private static void seedDelegationRules() {
        jdbc.execute("""
            insert into ap_delegation_rule (
                tenant_id, rule_id, principal_id, delegate_id,
                scope, definition_key, valid_from, valid_until,
                status, reason, created_by, created_at,
                revoked_by, revoked_at, revoke_reason, version
            )
            select
                tenant_id,
                md5('delegation-' || tenant_id || '-' || sample)::uuid,
                case when sample % 50 = 0 then 'principal-target'
                     else 'principal-' || sample end,
                'delegate-' || sample,
                'ALL', null,
                timestamptz '2026-07-01 00:00:00+00',
                timestamptz '2026-08-01 00:00:00+00',
                case when sample % 3 = 0 then 'REVOKED' else 'ACTIVE' end,
                'scale evidence', 'operator',
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                case when sample % 3 = 0 then 'operator' else null end,
                case when sample % 3 = 0
                     then timestamptz '2026-07-21 00:00:00+00'
                         + sample * interval '1 second' else null end,
                case when sample % 3 = 0 then 'revoked for scale evidence' else null end,
                1
            from (values ('tenant-a'), ('tenant-b')) tenants(tenant_id)
            cross join generate_series(1, 6000) sample
            """);
    }

    private static void seedHandovers() {
        jdbc.execute("""
            insert into ap_principal_handover (
                handover_id, tenant_id, connector_key,
                principal_id, principal_source, principal_object_type,
                principal_external_value, successor_id, successor_source,
                successor_object_type, successor_external_value,
                reason, status, created_by, created_at,
                revoked_by, revoked_at, revoke_reason, version
            )
            select
                md5('handover-' || tenant_id || '-' || sample)::uuid,
                tenant_id, 'directory',
                case when sample % 50 = 0 then 'principal-target'
                     else 'principal-' || sample end,
                'DIRECTORY', 'USER', 'principal-external-' || sample,
                'successor-' || sample, 'DIRECTORY', 'USER',
                'successor-external-' || sample,
                'scale evidence', 'REVOKED', 'operator',
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                'operator',
                timestamptz '2026-07-21 00:00:00+00' + sample * interval '1 second',
                'revoked for scale evidence', 2
            from (values ('tenant-a'), ('tenant-b')) tenants(tenant_id)
            cross join generate_series(1, 6000) sample
            """);
    }

    private static void seedCollaborationParticipants() {
        jdbc.execute("""
            insert into ap_task_collaboration_policy (
                policy_id, tenant_id, task_id, instance_id,
                engine_task_id, engine_instance_id,
                definition_key, task_definition_key, task_name,
                owner_assignee_id, collaboration_mode,
                approval_threshold, approval_weight_threshold,
                status, reason, created_by, created_at, version
            )
            select
                md5('policy-' || tenant_id || '-' || sample)::uuid,
                tenant_id,
                md5('task-' || tenant_id || '-' || sample)::uuid,
                md5('instance-' || tenant_id || '-' || sample)::uuid,
                'engine-task-' || tenant_id || '-' || sample,
                'engine-instance-' || tenant_id || '-' || sample,
                'purchase-payment', 'managerApproval', 'Manager approval',
                'assignee-' || (sample % 200), 'ANY', null, null,
                'ACTIVE', 'scale evidence', 'operator',
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                1
            from (values ('tenant-a'), ('tenant-b')) tenants(tenant_id)
            cross join generate_series(1, 6000) sample
            """);
        jdbc.execute("""
            insert into ap_task_collaboration_participant (
                participant_id, tenant_id, policy_id, participant_user_id,
                identity_source, identity_object_type, identity_external_value,
                status, added_by, added_at, version, participant_weight
            )
            select
                md5('participant-' || tenant_id || '-' || sample)::uuid,
                tenant_id,
                md5('policy-' || tenant_id || '-' || sample)::uuid,
                case when sample % 50 = 0 then 'participant-target'
                     else 'participant-' || sample end,
                'DIRECTORY', 'USER', 'participant-external-' || sample,
                'PENDING', 'operator',
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                1, 1
            from (values ('tenant-a'), ('tenant-b')) tenants(tenant_id)
            cross join generate_series(1, 6000) sample
            """);
    }

    private static void seedCommentsAndRevisions() {
        jdbc.execute("""
            insert into ap_approval_comment (
                comment_id, tenant_id, instance_id, parent_comment_id,
                author_id, body, mention_ids_json, attachment_ids_json,
                status, visibility, current_revision,
                created_at, updated_at, version
            )
            select
                md5('comment-' || tenant_id || '-' || sample)::uuid,
                tenant_id,
                case when sample % 10 = 0
                     then md5('instance-' || tenant_id || '-1')::uuid
                     else md5('instance-' || tenant_id || '-'
                         || (((sample - 1) % 6000) + 1))::uuid end,
                null,
                'author-' || (sample % 200),
                'comment body ' || sample,
                '[]'::jsonb, '[]'::jsonb,
                'ACTIVE', 'PARTICIPANTS', 1,
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                1
            from (values ('tenant-a'), ('tenant-b')) tenants(tenant_id)
            cross join generate_series(1, 20000) sample
            """);
        jdbc.execute("""
            insert into ap_approval_comment_revision (
                tenant_id, comment_id, revision_number, revision_type,
                body, mention_ids_json, attachment_ids_json, visibility,
                operator_id, reason, occurred_at
            )
            select
                tenant_id,
                md5('comment-' || tenant_id || '-' || sample)::uuid,
                1, 'CREATE', 'comment body ' || sample,
                '[]'::jsonb, '[]'::jsonb, 'PARTICIPANTS',
                'author-' || (sample % 200), null,
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second'
            from (values ('tenant-a'), ('tenant-b')) tenants(tenant_id)
            cross join generate_series(1, 20000) sample
            """);
    }

    private static void assertPlanUses(String query, String indexName) {
        String plan = explain(query);
        assertTrue(plan.contains(indexName), () -> indexName + " missing from " + plan);
    }

    private static void assertPlanUsesAny(String query, Set<String> indexNames) {
        String plan = explain(query);
        assertTrue(
            indexNames.stream().anyMatch(plan::contains),
            () -> indexNames + " missing from " + plan
        );
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
