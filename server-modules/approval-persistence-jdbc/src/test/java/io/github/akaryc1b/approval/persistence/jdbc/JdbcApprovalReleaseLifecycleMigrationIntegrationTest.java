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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalReleaseLifecycleMigrationFixtures.DEFINITION_KEY;
import static io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalReleaseLifecycleMigrationFixtures.TENANT;
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
    void v32BackfillRemainsValidWhenRepositoryAdvancesThroughV37() {
        DataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("31")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcApprovalReleaseLifecycleMigrationFixtures.seedRollbackHistory(jdbc);

        Flyway latest = Flyway.configure()
            .dataSource(dataSource).locations("classpath:db/migration").load();
        latest.migrate();

        assertEquals("37", latest.info().current().getVersion().getVersion());
        assertTrue(latest.validateWithResult().validationSuccessful);
        assertCurrentLifecycle(jdbc);
        assertTransitionHistory(jdbc);
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_intent", Integer.class
        ));
    }

    private static void assertCurrentLifecycle(JdbcTemplate jdbc) {
        LifecycleRow releaseOne = jdbc.queryForObject("""
            select lifecycle_state,revision,activated_at,deprecated_at,last_transition_at
            from ap_process_release_lifecycle
            where tenant_id=? and definition_key=? and release_version=1
            """, (resultSet, rowNumber) -> new LifecycleRow(
                resultSet.getString("lifecycle_state"),
                resultSet.getLong("revision"),
                resultSet.getObject("activated_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("deprecated_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("last_transition_at", OffsetDateTime.class).toInstant()
            ), TENANT, DEFINITION_KEY);
        assertEquals("ACTIVE", releaseOne.lifecycleState());
        assertEquals(4L, releaseOne.revision());
        assertEquals(Instant.parse("2026-01-01T00:02:00Z"), releaseOne.activatedAt());
        assertEquals(Instant.parse("2026-01-01T00:03:00Z"), releaseOne.deprecatedAt());
        assertEquals(Instant.parse("2026-01-01T00:04:00Z"), releaseOne.lastTransitionAt());
        assertEquals("DEPRECATED", jdbc.queryForObject("""
            select lifecycle_state from ap_process_release_lifecycle
            where tenant_id=? and definition_key=? and release_version=2
            """, String.class, TENANT, DEFINITION_KEY));
    }

    private static void assertTransitionHistory(JdbcTemplate jdbc) {
        assertEquals(List.of(
            "DRAFT->PUBLISHED", "PUBLISHED->ACTIVE", "ACTIVE->DEPRECATED", "DEPRECATED->ACTIVE"
        ), jdbc.queryForList("""
            select from_state || '->' || to_state
            from ap_process_release_lifecycle_history
            where tenant_id=? and definition_key=? and release_version=1
            order by revision
            """, String.class, TENANT, DEFINITION_KEY));
    }

    private record LifecycleRow(
        String lifecycleState,
        long revision,
        Instant activatedAt,
        Instant deprecatedAt,
        Instant lastTransitionAt
    ) {
    }
}
