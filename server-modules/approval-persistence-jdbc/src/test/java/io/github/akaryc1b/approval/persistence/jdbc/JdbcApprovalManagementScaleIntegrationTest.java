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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalManagementScaleIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_management_scale_test")
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
        seedAuditEvents();
        seedNotifications();
        seedConsistencyEvidence();
        seedOutboxMessages();
        for (String table : List.of(
            "ap_audit_event",
            "ap_notification_intent",
            "ap_consistency_check",
            "ap_consistency_finding",
            "ap_outbox"
        )) {
            jdbc.execute("analyze " + table);
        }
    }

    @Test
    void managementQueriesUseNaturalIndexesAtScale() {
        assertPlanUses(
            "select count(*) from ap_audit_event where tenant_id = 'tenant-a' "
                + "and action = 'TASK_APPROVED' "
                + "and occurred_at >= timestamptz '2026-07-19 00:00:00+00' "
                + "and occurred_at < timestamptz '2026-07-21 00:00:00+00'",
            "idx_audit_event_tenant_action_time"
        );
        assertPlanUses(
            "select event_id from ap_audit_event where tenant_id = 'tenant-a' "
                + "and aggregate_type = 'APPROVAL_INSTANCE' and aggregate_id = 'instance-42' "
                + "order by occurred_at desc, tenant_sequence desc limit 100",
            "idx_audit_event_tenant_aggregate"
        );
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
        assertPlanUses(
            "select id from ap_outbox where tenant_id = 'tenant-a' and status = 'DEAD' "
                + "order by updated_at desc, id limit 50",
            "idx_outbox_failure_queue"
        );
    }

    @Test
    void notificationHistoryPaginationIsStableAndTenantScoped() {
        List<UUID> first = notificationPage("tenant-a", 0);
        List<UUID> second = notificationPage("tenant-a", 20);
        List<UUID> repeated = notificationPage("tenant-a", 20);
        List<UUID> otherTenant = notificationPage("tenant-b", 20);

        assertEquals(20, first.size());
        assertEquals(20, second.size());
        assertEquals(second, repeated);
        assertFalse(new HashSet<>(first).removeAll(second));
        assertTrue(new HashSet<>(first).stream().noneMatch(otherTenant::contains));
        assertPlanUses(
            "select intent_id from ap_notification_intent where tenant_id = 'tenant-a' "
                + "and recipient_id = 'recipient-target' "
                + "order by created_at desc, intent_id desc limit 20 offset 20",
            "idx_notification_recipient_history"
        );
    }

    private static List<UUID> notificationPage(String tenantId, int offset) {
        return jdbc.queryForList(
            """
            select intent_id
            from ap_notification_intent
            where tenant_id = ?
              and recipient_id = 'recipient-target'
            order by created_at desc, intent_id desc
            limit 20 offset ?
            """,
            UUID.class,
            tenantId,
            offset
        );
    }

    private static void seedAuditEvents() {
        jdbc.execute("""
            insert into ap_audit_event (
                event_id, tenant_id, operator_id, action,
                aggregate_type, aggregate_id, request_id, trace_id,
                occurred_at, attributes_json, schema_name, schema_version,
                tenant_sequence, previous_hash, payload_hash, current_hash
            )
            select
                md5('audit-' || tenant_id || '-' || sample)::uuid,
                tenant_id,
                'operator-' || (sample % 200),
                case when sample % 100 = 0 then 'TASK_APPROVED' else 'INSTANCE_STARTED' end,
                'APPROVAL_INSTANCE',
                'instance-' || (sample % 100),
                'audit-request-' || tenant_id || '-' || sample,
                'audit-trace-' || tenant_id || '-' || (sample % 500),
                timestamptz '2026-07-20 12:00:00+00' - sample * interval '1 second',
                '{}'::jsonb,
                'approval.generic',
                1,
                sample,
                repeat('0', 64),
                encode(digest('payload-' || tenant_id || '-' || sample, 'sha256'), 'hex'),
                encode(digest('current-' || tenant_id || '-' || sample, 'sha256'), 'hex')
            from (values ('tenant-a'), ('tenant-b')) tenants(tenant_id)
            cross join generate_series(1, 12000) sample
            """);
    }

    private static void seedNotifications() {
        jdbc.execute("""
            insert into ap_notification_intent (
                intent_id, tenant_id, event_type, channel, recipient_id, sender_id,
                aggregate_type, aggregate_id, template_key, template_version,
                title, body, metadata_json, business_event_key, urgent,
                status, attempt_count, max_attempts, next_attempt_at,
                delivered_at, read_at, last_error_code, last_error_message,
                locked_by, locked_until, created_at, updated_at, version
            )
            select
                md5('intent-' || tenant_id || '-' || sample)::uuid,
                tenant_id,
                'TASK_ASSIGNED',
                case when sample % 100 = 0 then 'CONNECTOR' else 'IN_APP' end,
                case when sample % 200 = 0 then 'recipient-target'
                     else 'recipient-' || (sample % 200) end,
                'system',
                'APPROVAL_INSTANCE',
                'instance-' || (sample % 500),
                'task-assigned',
                1,
                'Approval task',
                'A task requires attention',
                case when sample % 100 = 0
                     then '{"connectorKey":"erp"}'::jsonb else '{}'::jsonb end,
                'notification-event-' || tenant_id || '-' || sample,
                false,
                case when sample % 100 = 0 then 'DEAD_LETTER' else 'DELIVERED' end,
                case when sample % 100 = 0 then 5 else 1 end,
                5,
                timestamptz '2026-07-20 12:00:00+00' + sample * interval '1 second',
                case when sample % 100 = 0 then null
                     else timestamptz '2026-07-20 12:00:00+00'
                         + sample * interval '1 second' end,
                null,
                case when sample % 100 = 0 then 'PROVIDER_FAILED' else null end,
                case when sample % 100 = 0 then 'provider failed' else null end,
                null,
                null,
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                1
            from (values ('tenant-a'), ('tenant-b')) tenants(tenant_id)
            cross join generate_series(1, 12000) sample
            """);
    }

    private static void seedConsistencyEvidence() {
        jdbc.execute("""
            insert into ap_consistency_check (
                check_id, tenant_id, requested_by, request_id, trace_id,
                scope, status, started_at, completed_at, finding_count,
                error_code, error_message, version
            )
            select
                ('00000000-0000-0000-' || case when tenant_id = 'tenant-a' then '0000' else '0001' end
                    || '-' || lpad(sample::text, 12, '0'))::uuid,
                tenant_id,
                'operator-' || (sample % 100),
                'consistency-request-' || tenant_id || '-' || sample,
                'consistency-trace-' || tenant_id || '-' || sample,
                'TENANT',
                case when sample % 100 = 0 then 'FAILED' else 'COMPLETED' end,
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                timestamptz '2026-07-20 00:00:01+00' + sample * interval '1 second',
                case when sample % 100 = 0 then 0 else 2 end,
                case when sample % 100 = 0 then 'CONSISTENCY_SCAN_FAILED' else null end,
                case when sample % 100 = 0 then 'scan failed' else null end,
                1
            from (values ('tenant-a'), ('tenant-b')) tenants(tenant_id)
            cross join generate_series(1, 12000) sample
            """);
        jdbc.execute("""
            insert into ap_consistency_finding (
                finding_id, tenant_id, check_id, check_type, severity,
                aggregate_type, aggregate_id, detected_at,
                details_json, suggested_action
            )
            select
                md5('finding-' || tenant_id || '-' || sample)::uuid,
                tenant_id,
                ('00000000-0000-0000-' || case when tenant_id = 'tenant-a' then '0000' else '0001' end
                    || '-' || lpad((((sample - 1) % 12000) + 1)::text, 12, '0'))::uuid,
                'NOTIFICATION_DELIVERY',
                'ERROR',
                'NOTIFICATION_INTENT',
                'intent-' || sample,
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                '{}'::jsonb,
                'Review notification evidence'
            from (values ('tenant-a'), ('tenant-b')) tenants(tenant_id)
            cross join generate_series(1, 24000) sample
            """);
    }

    private static void seedOutboxMessages() {
        jdbc.execute("""
            insert into ap_outbox (
                id, tenant_id, connector_key, request_id, trace_id,
                event_id, event_type, aggregate_type, aggregate_id,
                occurred_at, idempotency_key, payload_json,
                status, attempts, available_at, provider_request_id,
                response_code, last_error, created_at, updated_at,
                delivered_at, dead_at
            )
            select
                md5('outbox-' || tenant_id || '-' || sample)::uuid,
                tenant_id,
                case when sample % 2 = 0 then 'erp' else 'crm' end,
                'outbox-request-' || tenant_id || '-' || sample,
                'outbox-trace-' || tenant_id || '-' || sample,
                md5('outbox-event-' || tenant_id || '-' || sample)::uuid,
                'approval.completed',
                'APPROVAL_INSTANCE',
                'instance-' || (sample % 500),
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                'outbox-key-' || tenant_id || '-' || sample,
                '{}'::jsonb,
                case when sample % 100 = 0 then 'DEAD' else 'DELIVERED' end,
                case when sample % 100 = 0 then 5 else 1 end,
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                case when sample % 100 = 0 then null else 'provider-' || sample end,
                case when sample % 100 = 0 then 500 else 200 end,
                case when sample % 100 = 0 then 'callback failed' else null end,
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                timestamptz '2026-07-20 00:00:00+00' + sample * interval '1 second',
                case when sample % 100 = 0 then null
                     else timestamptz '2026-07-20 00:00:00+00'
                         + sample * interval '1 second' end,
                case when sample % 100 = 0
                     then timestamptz '2026-07-20 00:00:00+00'
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
