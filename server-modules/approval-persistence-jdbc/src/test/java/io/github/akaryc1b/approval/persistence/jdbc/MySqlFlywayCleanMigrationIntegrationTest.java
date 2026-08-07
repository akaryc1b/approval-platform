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

@Testcontainers(disabledWithoutDocker = true)
class MySqlFlywayCleanMigrationIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_migration")
        .withUsername("approval")
        .withPassword("approval")
        .withCommand(
            "--default-time-zone=+00:00",
            "--character-set-server=utf8mb4",
            "--collation-server=utf8mb4_0900_as_cs",
            "--transaction-isolation=READ-COMMITTED",
            "--innodb-strict-mode=ON",
            "--sql-mode=STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION"
        );

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
