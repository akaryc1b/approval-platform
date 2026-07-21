package io.github.akaryc1b.approval.persistence.jdbc;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalMigrationUpgradeIntegrationTest {

    private static final String FRESH_DATABASE = "approval_m4_fresh";
    private static final String V1_DATABASE = "approval_m4_v1";
    private static final String V13_DATABASE = "approval_m4_v13";
    private static final String V23_DATABASE = "approval_m4_v23";
    private static final String V27_DATABASE = "approval_m4_v27_heavy";

    private static final Set<String> M4_TABLES = Set.of(
        "ap_work_calendar",
        "ap_work_calendar_version",
        "ap_work_calendar_date_override",
        "ap_work_calendar_interval",
        "ap_sla_policy",
        "ap_sla_policy_version",
        "ap_sla_instance",
        "ap_sla_responsibility_change"
    );

    private static final Set<String> M4_INDEXES = Set.of(
        "idx_work_calendar_active_lookup",
        "idx_sla_policy_active_lookup",
        "idx_sla_instance_responsible_active_due",
        "idx_sla_instance_active_due",
        "idx_sla_instance_approval_instance",
        "idx_sla_instance_task",
        "idx_sla_instance_request_id"
    );

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_migration_upgrade_test")
        .withUsername("approval")
        .withPassword("approval");

    @BeforeAll
    static void createIsolatedDatabases() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        ));
        for (String database : List.of(
            FRESH_DATABASE,
            V1_DATABASE,
            V13_DATABASE,
            V23_DATABASE,
            V27_DATABASE
        )) {
            admin.execute("create database " + database);
        }
    }

    @Test
    void freshInstallReachesV30() {
        assertUpgrade(FRESH_DATABASE, null);
    }

    @Test
    void upgradesV1SchemaToV30() {
        assertUpgrade(V1_DATABASE, MigrationVersion.fromVersion("1"));
    }

    @Test
    void upgradesV13SchemaToV30() {
        assertUpgrade(V13_DATABASE, MigrationVersion.fromVersion("13"));
    }

    @Test
    void upgradesV23SchemaToV30() {
        assertUpgrade(V23_DATABASE, MigrationVersion.fromVersion("23"));
    }

    @Test
    void upgradesV27WithFiveThousandInstancesAndTasksWithoutChangingEvidence() {
        DataSource dataSource = dataSource(V27_DATABASE);
        Flyway baseline = flyway(dataSource, MigrationVersion.fromVersion("27"));
        baseline.migrate();
        assertEquals("27", baseline.info().current().getVersion().getVersion());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedV27Data(jdbc);
        assertProjectionEvidence(jdbc, 5_000);

        Flyway latest = flyway(dataSource, null);
        latest.migrate();

        assertEquals("30", latest.info().current().getVersion().getVersion());
        assertTrue(latest.validateWithResult().validationSuccessful);
        assertProjectionEvidence(jdbc, 5_000);
        assertM4Schema(dataSource);
    }

    private static void assertUpgrade(String databaseName, MigrationVersion startingVersion) {
        DataSource dataSource = dataSource(databaseName);
        if (startingVersion != null) {
            Flyway starting = flyway(dataSource, startingVersion);
            starting.migrate();
            assertEquals(startingVersion.getVersion(), starting.info().current().getVersion().getVersion());
        }

        Flyway latest = flyway(dataSource, null);
        latest.migrate();

        assertEquals("30", latest.info().current().getVersion().getVersion());
        assertTrue(latest.validateWithResult().validationSuccessful);
        assertM4Schema(dataSource);
    }

    private static void seedV27Data(JdbcTemplate jdbc) {
        jdbc.update(
            """
            insert into ap_approval_instance (
                instance_id, tenant_id, business_key, engine_instance_id,
                definition_key, definition_version, form_key, form_version,
                compiler_version, content_hash, initiator_id, amount, supplier,
                purchase_order_reference, attachment_ids_json, assignee_snapshot_json,
                request_hash, status, version, created_at, updated_at
            )
            select
                ('10000000-0000-0000-0000-' || lpad(number::text, 12, '0'))::uuid,
                'tenant-upgrade',
                'M4-UPGRADE-' || number,
                'engine-instance-' || number,
                'purchasePayment',
                1,
                'purchasePayment',
                1,
                'compiler-v1',
                repeat('a', 64),
                'initiator-' || number,
                number::numeric,
                'supplier-' || number,
                'PO-' || number,
                '[]'::jsonb,
                '{}'::jsonb,
                repeat('b', 64),
                'RUNNING',
                1,
                timestamptz '2026-01-01 00:00:00+00' + number * interval '1 second',
                timestamptz '2026-01-01 00:00:00+00' + number * interval '1 second'
            from generate_series(1, 5000) number
            """
        );
        jdbc.update(
            """
            insert into ap_approval_task (
                task_id, instance_id, tenant_id, engine_task_id,
                task_definition_key, task_name, assignee_id, status,
                version, created_at, updated_at, completed_at
            )
            select
                ('20000000-0000-0000-0000-' || lpad(number::text, 12, '0'))::uuid,
                ('10000000-0000-0000-0000-' || lpad(number::text, 12, '0'))::uuid,
                'tenant-upgrade',
                'engine-task-' || number,
                'managerApproval',
                'Manager approval ' || number,
                'manager-' || number,
                'PENDING',
                1,
                timestamptz '2026-01-01 00:00:00+00' + number * interval '1 second',
                timestamptz '2026-01-01 00:00:00+00' + number * interval '1 second',
                null
            from generate_series(1, 5000) number
            """
        );
    }

    private static void assertProjectionEvidence(JdbcTemplate jdbc, int expectedCount) {
        assertEquals(expectedCount, jdbc.queryForObject(
            "select count(*) from ap_approval_instance where tenant_id='tenant-upgrade'",
            Integer.class
        ));
        assertEquals(expectedCount, jdbc.queryForObject(
            "select count(*) from ap_approval_task where tenant_id='tenant-upgrade'",
            Integer.class
        ));
        assertEquals("M4-UPGRADE-2500", jdbc.queryForObject(
            """
            select business_key from ap_approval_instance
            where tenant_id='tenant-upgrade'
              and instance_id='10000000-0000-0000-0000-000000002500'::uuid
            """,
            String.class
        ));
        assertEquals("manager-2500", jdbc.queryForObject(
            """
            select assignee_id from ap_approval_task
            where tenant_id='tenant-upgrade'
              and task_id='20000000-0000-0000-0000-000000002500'::uuid
            """,
            String.class
        ));
        assertEquals("PENDING", jdbc.queryForObject(
            """
            select status from ap_approval_task
            where tenant_id='tenant-upgrade'
              and task_id='20000000-0000-0000-0000-000000002500'::uuid
            """,
            String.class
        ));
    }

    private static void assertM4Schema(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> tables = jdbc.queryForList(
            """
            select table_name
            from information_schema.tables
            where table_schema = current_schema()
              and table_name = any (array[
                'ap_work_calendar',
                'ap_work_calendar_version',
                'ap_work_calendar_date_override',
                'ap_work_calendar_interval',
                'ap_sla_policy',
                'ap_sla_policy_version',
                'ap_sla_instance',
                'ap_sla_responsibility_change'
              ])
            """,
            String.class
        );
        assertEquals(M4_TABLES, Set.copyOf(tables));

        List<String> indexes = jdbc.queryForList(
            """
            select indexname
            from pg_indexes
            where schemaname = current_schema()
              and indexname = any (array[
                'idx_work_calendar_active_lookup',
                'idx_sla_policy_active_lookup',
                'idx_sla_instance_responsible_active_due',
                'idx_sla_instance_active_due',
                'idx_sla_instance_approval_instance',
                'idx_sla_instance_task',
                'idx_sla_instance_request_id'
              ])
            """,
            String.class
        );
        assertEquals(M4_INDEXES, Set.copyOf(indexes));
    }

    private static DataSource dataSource(String databaseName) {
        String url = "jdbc:postgresql://" + POSTGRES.getHost()
            + ":" + POSTGRES.getMappedPort(5432)
            + "/" + databaseName;
        return new DriverManagerDataSource(
            url,
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
    }

    private static Flyway flyway(DataSource dataSource, MigrationVersion target) {
        var configuration = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }
}
