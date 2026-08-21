package io.github.akaryc1b.approval.persistence.jdbc;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class MySqlFlywayCleanMigrationIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_migration")
        .withUsername("approval")
        .withPassword("approval")
        .withCommand(MySql84ProductionTestServer.command());

    private static Flyway flyway;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = new DriverManagerDataSource(
            configuredJdbcUrl(),
            MYSQL.getUsername(),
            MYSQL.getPassword()
        );
        flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/mysqlmigration")
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .load();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void cleanMySql84HistoryReachesExactGovernedVersion50() {
        var current = flyway.info().current();
        assertNotNull(current);
        assertEquals("50", current.getVersion().getVersion());
        assertEquals(1, flyway.info().applied().length);
    }

    @Test
    void retainsBinaryLoggingAndTrustedD7TriggerInstallationAuthority() {
        assertEquals(
            1,
            jdbc.queryForObject("select @@global.log_bin", Integer.class)
        );
        assertEquals(
            1,
            jdbc.queryForObject(
                "select @@global.log_bin_trust_function_creators",
                Integer.class
            )
        );
    }

    @Test
    void installsAllTenGovernedD7AppendOnlyGuards() {
        Integer guards = jdbc.queryForObject("""
            select count(*)
            from information_schema.triggers
            where trigger_schema = database()
              and trigger_name in (
                'trg_process_migration_canary_selection_guard_v47',
                'trg_process_migration_canary_selection_delete_guard_v47',
                'trg_process_migration_orchestration_run_guard_v47',
                'trg_process_migration_orchestration_run_delete_guard_v47',
                'trg_process_migration_orchestration_event_guard_v47',
                'trg_process_migration_orchestration_event_delete_guard_v47',
                'trg_process_migration_orchestration_batch_guard_v47',
                'trg_process_migration_orchestration_batch_delete_guard_v47',
                'trg_process_migration_kill_switch_observation_guard_v47',
                'trg_process_migration_kill_switch_observation_delete_guard_v47'
              )
              and lower(action_statement) like '%m5-d7 evidence is append-only%'
            """, Integer.class);

        assertEquals(10, guards);
    }

    @Test
    void everyPlatformTableUsesInnoDbAndCaseSensitiveUtf8mb4Collation() {
        Integer nonInnoDb = jdbc.queryForObject("""
            select count(*)
            from information_schema.tables
            where table_schema = database()
              and table_name like 'ap\\_%' escape '\\\\'
              and engine <> 'InnoDB'
            """, Integer.class);
        Integer wrongCollation = jdbc.queryForObject("""
            select count(*)
            from information_schema.tables
            where table_schema = database()
              and table_name like 'ap\\_%' escape '\\\\'
              and table_collation <> 'utf8mb4_0900_as_cs'
            """, Integer.class);

        assertEquals(0, nonInnoDb);
        assertEquals(0, wrongCollation);
    }

    @Test
    void governedForeignKeysAreActuallyEnforced() {
        Integer governedForeignKeys = jdbc.queryForObject("""
            select count(*)
            from information_schema.referential_constraints
            where constraint_schema = database()
              and constraint_name in (
                'fk_approval_task_instance',
                'fk_approval_message_instance',
                'fk_approval_message_task',
                'fk_approval_comment_instance',
                'fk_approval_attachment_instance',
                'fk_form_submission_instance',
                'fk_form_submission_revision_instance',
                'fk_approval_comment_revision_comment'
              )
            """, Integer.class);

        assertEquals(8, governedForeignKeys);
    }

    @Test
    void commentAuditAndMigrationEvidenceColumnsRemainRequired() {
        Integer governedColumns = jdbc.queryForObject("""
            select count(*)
            from information_schema.columns
            where table_schema = database()
              and (
                (table_name = 'ap_approval_comment'
                    and column_name = 'updated_at')
                or (table_name = 'ap_audit_event'
                    and column_name in (
                        'schema_name',
                        'schema_version',
                        'tenant_sequence',
                        'previous_hash',
                        'payload_hash',
                        'current_hash'
                    ))
                or (table_name = 'ap_process_migration_attempt'
                    and column_name = 'failure_class')
                or (table_name = 'ap_process_migration_attempt_event'
                    and column_name in ('engine_outcome', 'failure_class'))
              )
            """, Integer.class);
        Integer nullableColumns = jdbc.queryForObject("""
            select count(*)
            from information_schema.columns
            where table_schema = database()
              and is_nullable <> 'NO'
              and (
                (table_name = 'ap_approval_comment'
                    and column_name = 'updated_at')
                or (table_name = 'ap_audit_event'
                    and column_name in (
                        'schema_name',
                        'schema_version',
                        'tenant_sequence',
                        'previous_hash',
                        'payload_hash',
                        'current_hash'
                    ))
                or (table_name = 'ap_process_migration_attempt'
                    and column_name = 'failure_class')
                or (table_name = 'ap_process_migration_attempt_event'
                    and column_name in ('engine_outcome', 'failure_class'))
              )
            """, Integer.class);

        assertEquals(10, governedColumns);
        assertEquals(0, nullableColumns);
    }

    @Test
    void uniqueEvidenceAndNotificationDeduplicationConstraintsRemainPresent() {
        Integer uniqueConstraints = jdbc.queryForObject("""
            select count(*)
            from information_schema.table_constraints
            where constraint_schema = database()
              and constraint_type = 'UNIQUE'
              and constraint_name in (
                'uk_approval_comment_tenant_comment',
                'uk_audit_event_tenant_sequence',
                'uk_audit_event_tenant_hash',
                'uk_notification_business_recipient_channel'
              )
            """, Integer.class);
        String generationExpression = jdbc.queryForObject("""
            select generation_expression
            from information_schema.columns
            where table_schema = database()
              and table_name = 'ap_notification_intent'
              and column_name = 'business_recipient_channel_hash'
            """, String.class);

        assertEquals(4, uniqueConstraints);
        assertNotNull(generationExpression);
        assertTrue(generationExpression.toLowerCase().contains("sha2"));
        assertTrue(generationExpression.toLowerCase().contains("hex"));
    }

    @Test
    void migrationHistoryContainsNoFailedEntry() {
        Integer failed = jdbc.queryForObject("""
            select count(*)
            from flyway_schema_history
            where success = 0
            """, Integer.class);
        assertEquals(0, failed);
    }

    private static String configuredJdbcUrl() {
        String base = MYSQL.getJdbcUrl();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator
            + "characterEncoding=UTF-8"
            + "&connectionCollation=utf8mb4_0900_as_cs"
            + "&connectionTimeZone=UTC"
            + "&forceConnectionTimeZoneToSession=true"
            + "&preserveInstants=true";
    }
}
