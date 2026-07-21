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

    private static final String FRESH_DATABASE = "approval_h8_fresh";
    private static final String UPGRADE_DATABASE = "approval_h8_upgrade";

    private static final Set<String> H8_INDEXES = Set.of(
        "idx_approval_task_pending_assignee_page",
        "idx_approval_task_completed_assignee_page",
        "idx_delegation_principal_history",
        "idx_handover_principal_history",
        "idx_collaboration_participant_pending_page",
        "idx_audit_event_tenant_action_time",
        "idx_notification_dead_management",
        "idx_consistency_failed_management",
        "idx_outbox_failure_queue"
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
        admin.execute("create database " + FRESH_DATABASE);
        admin.execute("create database " + UPGRADE_DATABASE);
    }

    @Test
    void upgradesPreH8SchemaFromV23ToV27() {
        DataSource dataSource = dataSource(UPGRADE_DATABASE);
        Flyway preH8 = flyway(dataSource, MigrationVersion.fromVersion("23"));
        preH8.migrate();
        assertEquals("23", preH8.info().current().getVersion().getVersion());

        Flyway latest = flyway(dataSource, null);
        latest.migrate();

        assertEquals("27", latest.info().current().getVersion().getVersion());
        assertTrue(latest.validateWithResult().validationSuccessful);
        assertH8Indexes(dataSource);
    }

    @Test
    void freshInstallReachesV27WithTheSameIndexContract() {
        DataSource dataSource = dataSource(FRESH_DATABASE);
        Flyway latest = flyway(dataSource, null);
        latest.migrate();

        assertEquals("27", latest.info().current().getVersion().getVersion());
        assertTrue(latest.validateWithResult().validationSuccessful);
        assertH8Indexes(dataSource);
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

    private static void assertH8Indexes(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> names = jdbc.queryForList(
            """
            select indexname
            from pg_indexes
            where schemaname = current_schema()
              and indexname = any (array[
                'idx_approval_task_pending_assignee_page',
                'idx_approval_task_completed_assignee_page',
                'idx_delegation_principal_history',
                'idx_handover_principal_history',
                'idx_collaboration_participant_pending_page',
                'idx_audit_event_tenant_action_time',
                'idx_notification_dead_management',
                'idx_consistency_failed_management',
                'idx_outbox_failure_queue'
              ])
            """,
            String.class
        );
        assertEquals(H8_INDEXES, Set.copyOf(names));

        Integer legacyCount = jdbc.queryForObject(
            """
            select count(*)
            from pg_indexes
            where schemaname = current_schema()
              and indexname = 'ap_approval_task_assignee_idx'
            """,
            Integer.class
        );
        assertEquals(0, legacyCount);
    }
}
