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

    private static final String LATEST_VERSION = "45";
    private static final List<UpgradeCase> UPGRADE_CASES = List.of(
        new UpgradeCase("approval_latest_fresh", null),
        new UpgradeCase("approval_latest_v1", "1"),
        new UpgradeCase("approval_latest_v13", "13"),
        new UpgradeCase("approval_latest_v23", "23"),
        new UpgradeCase("approval_latest_v31", "31"),
        new UpgradeCase("approval_latest_v36", "36"),
        new UpgradeCase("approval_latest_v37", "37"),
        new UpgradeCase("approval_latest_v38", "38"),
        new UpgradeCase("approval_latest_v39", "39"),
        new UpgradeCase("approval_latest_v40", "40"),
        new UpgradeCase("approval_latest_v41", "41"),
        new UpgradeCase("approval_latest_v42", "42"),
        new UpgradeCase("approval_latest_v43", "43"),
        new UpgradeCase("approval_latest_v44", "44")
    );
    private static final String V27_DATABASE = "approval_latest_v27_heavy";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_migration_upgrade_admin")
        .withUsername("approval")
        .withPassword("approval");

    @BeforeAll
    static void createIsolatedDatabases() {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
                + "/postgres",
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
    void freshAndHistoricalUpgradePathsReachV45WithoutExecutionSideEffects() {
        for (UpgradeCase upgrade : UPGRADE_CASES) {
            assertUpgrade(upgrade);
        }
    }

    @Test
    void upgradesV27WithFiveThousandInstancesAndTasksWithoutChangingEvidence() {
        DataSource dataSource = JdbcApprovalMigrationUpgradeSupport.dataSource(
            POSTGRES,
            V27_DATABASE
        );
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
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_plan_consumption",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_intent",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_engine_request",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_engine_outcome",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_exact_verification",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_instance_completion",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_binding_cas_conflict",
            Integer.class
        ));
        assertD6Empty(jdbc);
        JdbcApprovalMigrationUpgradeAssertions.assertLatestSchema(dataSource);
    }

    private static void assertUpgrade(UpgradeCase upgrade) {
        DataSource dataSource = JdbcApprovalMigrationUpgradeSupport.dataSource(
            POSTGRES,
            upgrade.databaseName()
        );
        if (upgrade.startingVersion() != null) {
            MigrationVersion startingVersion = MigrationVersion.fromVersion(
                upgrade.startingVersion()
            );
            Flyway starting = JdbcApprovalMigrationUpgradeSupport.flyway(
                dataSource,
                startingVersion
            );
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
        assertNoExecutionSideEffects(new JdbcTemplate(dataSource));
        JdbcApprovalMigrationUpgradeAssertions.assertLatestSchema(dataSource);
    }

    private static void assertNoExecutionSideEffects(JdbcTemplate jdbc) {
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_plan_consumption",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_intent",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_runtime_binding",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_runtime_binding_evidence",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_engine_request",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_engine_outcome",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_exact_verification",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_instance_completion",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_binding_cas_conflict",
            Integer.class
        ));
        assertD6Empty(jdbc);
    }

    private static void assertD6Empty(JdbcTemplate jdbc) {
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_reconciliation_lease",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_reconciliation_lease_event",
            Integer.class
        ));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from ap_process_migration_reconciliation_observation",
            Integer.class
        ));
    }

    private record UpgradeCase(String databaseName, String startingVersion) {
    }
}
