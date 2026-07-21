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

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void configureDataSource() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void upgradesPreH8SchemaFromV23ToV27() {
        Flyway preH8 = flyway("upgrade_h8", MigrationVersion.fromVersion("23"));
        preH8.migrate();
        assertEquals("23", preH8.info().current().getVersion().getVersion());

        Flyway latest = flyway("upgrade_h8", null);
        latest.migrate();

        assertEquals("27", latest.info().current().getVersion().getVersion());
        assertTrue(latest.validateWithResult().validationSuccessful);
        assertH8Indexes("upgrade_h8");
    }

    @Test
    void freshInstallReachesV27WithTheSameIndexContract() {
        Flyway latest = flyway("fresh_h8", null);
        latest.migrate();

        assertEquals("27", latest.info().current().getVersion().getVersion());
        assertTrue(latest.validateWithResult().validationSuccessful);
        assertH8Indexes("fresh_h8");
    }

    private static Flyway flyway(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static void assertH8Indexes(String schema) {
        List<String> names = jdbc.queryForList(
            """
            select indexname
            from pg_indexes
            where schemaname = ?
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
            String.class,
            schema
        );
        assertEquals(H8_INDEXES, Set.copyOf(names));

        Integer legacyCount = jdbc.queryForObject(
            """
            select count(*)
            from pg_indexes
            where schemaname = ?
              and indexname = 'ap_approval_task_assignee_idx'
            """,
            Integer.class,
            schema
        );
        assertEquals(0, legacyCount);
    }
}
