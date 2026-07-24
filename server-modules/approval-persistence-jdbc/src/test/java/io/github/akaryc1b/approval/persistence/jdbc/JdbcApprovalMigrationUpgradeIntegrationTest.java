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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcApprovalMigrationUpgradeIntegrationTest {

    private static final String LATEST_VERSION = "37";
    private static final List<UpgradeCase> UPGRADE_CASES = List.of(
        new UpgradeCase("approval_latest_fresh", null),
        new UpgradeCase("approval_latest_v1", "1"),
        new UpgradeCase("approval_latest_v13", "13"),
        new UpgradeCase("approval_latest_v23", "23"),
        new UpgradeCase("approval_latest_v31", "31"),
        new UpgradeCase("approval_latest_v36", "36")
    );
    private static final String V27_DATABASE = "approval_latest_v27_heavy";

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
        for (String database : java.util.stream.Stream.concat(
            UPGRADE_CASES.stream().map(UpgradeCase::databaseName),
            java.util.stream.Stream.of(V27_DATABASE)
        ).toList()) {
            admin.execute("create database " + database);
        }
    }

    @Test
    void freshAndHistoricalUpgradePathsReachV37() {
        for (UpgradeCase upgrade : UPGRADE_CASES) {
            assertUpgrade(upgrade);
        }
    }

    @Test
    void upgradesV27WithFiveThousandInstancesAndTasksWithoutChangingEvidence() {
        DataSource dataSource = JdbcApprovalMigrationUpgradeSupport.dataSource(POSTGRES, V27_DATABASE);
        Flyway baseline = JdbcApprovalMigrationUpgradeSupport.flyway(
            dataSource,
            MigrationVersion.fromVersion("27")
        );
        baseline.migrate();
        assertEquals("27", baseline.info().current().getVersion().getVersion());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        JdbcApprovalMigrationUpgradeSupport.seedV27Data(jdbc);
        JdbcApprovalMigrationUpgradeSupport.assertProjectionEvidence(jdbc, 5_000);

        Flyway latest = JdbcApprovalMigrationUpgradeSupport.flyway(dataSource, null);
        latest.migrate();

        assertEquals(LATEST_VERSION, latest.info().current().getVersion().getVersion());
        assertTrue(latest.validateWithResult().validationSuccessful);
        JdbcApprovalMigrationUpgradeSupport.assertProjectionEvidence(jdbc, 5_000);
        JdbcApprovalMigrationUpgradeAssertions.assertLatestSchema(dataSource);
    }

    private static void assertUpgrade(UpgradeCase upgrade) {
        DataSource dataSource = JdbcApprovalMigrationUpgradeSupport.dataSource(
            POSTGRES,
            upgrade.databaseName()
        );
        if (upgrade.startingVersion() != null) {
            MigrationVersion startingVersion = MigrationVersion.fromVersion(upgrade.startingVersion());
            Flyway starting = JdbcApprovalMigrationUpgradeSupport.flyway(dataSource, startingVersion);
            starting.migrate();
            assertEquals(
                startingVersion.getVersion(),
                starting.info().current().getVersion().getVersion()
            );
        }

        Flyway latest = JdbcApprovalMigrationUpgradeSupport.flyway(dataSource, null);
        latest.migrate();
        assertEquals(LATEST_VERSION, latest.info().current().getVersion().getVersion());
        assertTrue(latest.validateWithResult().validationSuccessful);
        JdbcApprovalMigrationUpgradeAssertions.assertLatestSchema(dataSource);
    }

    private record UpgradeCase(String databaseName, String startingVersion) {
    }
}
