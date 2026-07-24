package io.github.akaryc1b.approval.persistence.jdbc;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class JdbcApprovalMigrationUpgradeSupport {

    private JdbcApprovalMigrationUpgradeSupport() {
    }

    static DataSource dataSource(PostgreSQLContainer postgres, String databaseName) {
        return new DriverManagerDataSource(
            "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/" + databaseName,
            postgres.getUsername(),
            postgres.getPassword()
        );
    }

    static Flyway flyway(DataSource dataSource, MigrationVersion target) {
        var configuration = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    static void seedV27Data(JdbcTemplate jdbc) {
        jdbc.update("""
            insert into ap_definition_version (
                tenant_id, definition_key, definition_version,
                form_key, form_version, compiler_version, content_hash,
                deployment_id, engine_definition_id, engine_version,
                published_by, published_at
            ) values (
                'tenant-upgrade', 'purchasePayment', 1,
                'purchasePayment', 1, 'compiler-v1', repeat('a', 64),
                'deployment-upgrade', 'engine-definition-upgrade', 1,
                'upgrade-test', timestamptz '2025-12-31 00:00:00+00'
            )
            """);
        jdbc.update("""
            insert into ap_approval_instance (
                instance_id, tenant_id, business_key, engine_instance_id,
                definition_key, definition_version, form_key, form_version,
                compiler_version, content_hash, initiator_id, amount, supplier,
                purchase_order_reference, attachment_ids_json, assignee_snapshot_json,
                request_hash, status, version, created_at, updated_at
            )
            select
                ('10000000-0000-0000-0000-' || lpad(number::text, 12, '0'))::uuid,
                'tenant-upgrade', 'M4-UPGRADE-' || number, 'engine-instance-' || number,
                'purchasePayment', 1, 'purchasePayment', 1, 'compiler-v1', repeat('a', 64),
                'initiator-' || number, number::numeric, 'supplier-' || number, 'PO-' || number,
                '[]'::jsonb, '{}'::jsonb, repeat('b', 64), 'RUNNING', 1,
                timestamptz '2026-01-01 00:00:00+00' + number * interval '1 second',
                timestamptz '2026-01-01 00:00:00+00' + number * interval '1 second'
            from generate_series(1, 5000) number
            """);
        jdbc.update("""
            insert into ap_approval_task (
                task_id, instance_id, tenant_id, engine_task_id,
                task_definition_key, task_name, assignee_id, status,
                version, created_at, updated_at, completed_at
            )
            select
                ('20000000-0000-0000-0000-' || lpad(number::text, 12, '0'))::uuid,
                ('10000000-0000-0000-0000-' || lpad(number::text, 12, '0'))::uuid,
                'tenant-upgrade', 'engine-task-' || number, 'managerApproval',
                'Manager approval ' || number, 'manager-' || number, 'PENDING', 1,
                timestamptz '2026-01-01 00:00:00+00' + number * interval '1 second',
                timestamptz '2026-01-01 00:00:00+00' + number * interval '1 second', null
            from generate_series(1, 5000) number
            """);
    }

    static void assertProjectionEvidence(JdbcTemplate jdbc, int expectedCount) {
        assertEquals(expectedCount, jdbc.queryForObject(
            "select count(*) from ap_approval_instance where tenant_id='tenant-upgrade'",
            Integer.class
        ));
        assertEquals(expectedCount, jdbc.queryForObject(
            "select count(*) from ap_approval_task where tenant_id='tenant-upgrade'",
            Integer.class
        ));
        assertEquals("M4-UPGRADE-2500", jdbc.queryForObject("""
            select business_key from ap_approval_instance
            where tenant_id='tenant-upgrade'
              and instance_id='10000000-0000-0000-0000-000000002500'::uuid
            """, String.class));
        assertEquals("manager-2500", jdbc.queryForObject("""
            select assignee_id from ap_approval_task
            where tenant_id='tenant-upgrade'
              and task_id='20000000-0000-0000-0000-000000002500'::uuid
            """, String.class));
        assertEquals("PENDING", jdbc.queryForObject("""
            select status from ap_approval_task
            where tenant_id='tenant-upgrade'
              and task_id='20000000-0000-0000-0000-000000002500'::uuid
            """, String.class));
    }
}
