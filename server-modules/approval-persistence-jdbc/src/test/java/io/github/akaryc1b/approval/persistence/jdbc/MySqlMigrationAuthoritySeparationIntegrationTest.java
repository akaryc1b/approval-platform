package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class MySqlMigrationAuthoritySeparationIntegrationTest {

    private static final Set<String> D7_APPEND_ONLY_TRIGGERS = Set.of(
        "trg_process_migration_canary_selection_guard_v47",
        "trg_process_migration_canary_selection_delete_guard_v47",
        "trg_process_migration_orchestration_run_guard_v47",
        "trg_process_migration_orchestration_run_delete_guard_v47",
        "trg_process_migration_orchestration_event_guard_v47",
        "trg_process_migration_orchestration_event_delete_guard_v47",
        "trg_process_migration_orchestration_batch_guard_v47",
        "trg_process_migration_orchestration_batch_delete_guard_v47",
        "trg_process_migration_kill_switch_observation_guard_v47",
        "trg_process_migration_kill_switch_observation_delete_guard_v47"
    );

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_migration_authority")
        .withUsername("approval")
        .withPassword("approval")
        .withCommand(
            "--default-time-zone=+00:00",
            "--character-set-server=utf8mb4",
            "--collation-server=utf8mb4_0900_as_cs",
            "--transaction-isolation=READ-COMMITTED",
            "--innodb-strict-mode=ON",
            "--sql-mode=STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,"
                + "NO_ENGINE_SUBSTITUTION"
        );

    static DataSource bootstrapDataSource;
    static DataSource runtimeDataSource;
    static JdbcTemplate runtimeJdbc;

    @BeforeAll
    static void migrateWithSeparateAuthority() {
        bootstrapDataSource = new DriverManagerDataSource(
            configuredJdbcUrl(),
            MYSQL.getUsername(),
            MYSQL.getPassword()
        );
        MySqlTestDatabaseAuthority.flyway(MYSQL, bootstrapDataSource)
            .locations("classpath:db/mysqlmigration")
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .load()
            .migrate();
        runtimeDataSource = MySqlTestDatabaseAuthority
            .createLeastPrivilegeRuntimeDataSource(
                MYSQL,
                bootstrapDataSource,
                "approval_runtime",
                "runtime_password"
            );
        runtimeJdbc = new JdbcTemplate(runtimeDataSource);
    }

    @Test
    void privilegedMigrationInstallsGuardsWithoutTrustingRuntimeIdentity() {
        JdbcTemplate bootstrapJdbc = new JdbcTemplate(bootstrapDataSource);
        assertTrue(bootstrapJdbc.queryForObject(
            "select @@global.log_bin",
            Boolean.class
        ));
        assertFalse(bootstrapJdbc.queryForObject(
            "select @@global.log_bin_trust_function_creators",
            Boolean.class
        ));

        Set<String> installed = Set.copyOf(runtimeJdbc.query("""
            select trigger_name
            from information_schema.triggers
            where trigger_schema=database()
              and trigger_name like 'trg_process_migration_%_guard_v47'
            """, (row, number) -> row.getString(1)));
        assertEquals(D7_APPEND_ONLY_TRIGGERS, installed);

        String account = runtimeJdbc.queryForObject(
            "select current_user()",
            String.class
        );
        assertTrue(account.startsWith("approval_runtime@"));
        assertFalse(account.toLowerCase(Locale.ROOT).startsWith("root@"));

        List<String> grants = runtimeJdbc.query(
            "show grants",
            (row, number) -> row.getString(1).toUpperCase(Locale.ROOT)
        );
        String grantText = String.join("\n", grants);
        for (String required : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
            assertTrue(grantText.contains(required));
        }
        for (String forbidden : List.of(
            "TRIGGER",
            "CREATE",
            "ALTER",
            "DROP",
            "SUPER",
            "SYSTEM_VARIABLES_ADMIN",
            "GRANT OPTION",
            "FLYWAY_SCHEMA_HISTORY"
        )) {
            assertFalse(grantText.contains(forbidden));
        }

        assertEquals(
            0,
            runtimeJdbc.queryForObject(
                "select count(*) from ap_process_migration_orchestration_run",
                Integer.class
            )
        );
        assertThrows(DataAccessException.class, () -> runtimeJdbc.queryForObject(
            "select count(*) from flyway_schema_history",
            Integer.class
        ));
        assertThrows(DataAccessException.class, () -> runtimeJdbc.execute(
            "drop trigger trg_process_migration_orchestration_run_guard_v47"
        ));
        assertThrows(DataAccessException.class, () -> runtimeJdbc.execute(
            "create table forbidden_runtime_ddl (id integer primary key)"
        ));
        assertThrows(DataAccessException.class, () -> runtimeJdbc.execute(
            "set global log_bin_trust_function_creators=1"
        ));
    }

    private static String configuredJdbcUrl() {
        String base = MYSQL.getJdbcUrl();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator
            + "characterEncoding=UTF-8"
            + "&connectionCollation=utf8mb4_0900_as_cs"
            + "&connectionTimeZone=UTC"
            + "&forceConnectionTimeZoneToSession=true"
            + "&preserveInstants=true"
            + "&useAffectedRows=false";
    }
}
