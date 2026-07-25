package io.github.akaryc1b.approval.persistence.jdbc;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalReleaseLifecycleMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_release_lifecycle_migration_test")
        .withUsername("approval")
        .withPassword("approval");

    @Test
    void v32BackfillRemainsValidWhenRepositoryAdvancesThroughV39() {
        DataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("31")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcApprovalReleaseLifecycleMigrationFixtures.seedRollbackHistory(jdbc);

        Flyway latest = Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/migration").load();
        latest.migrate();

        assertEquals("39", latest.info().current().getVersion().getVersion());
        assertTrue(latest.validateWithResult().validationSuccessful);
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_plan_consumption",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_intent",
            Integer.class
        ));
    }
}
