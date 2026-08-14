package db.mysqlmigration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class MySqlV50TriggerInstallationAuthorityIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_untrusted_trigger_creator")
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

    @Test
    void binaryLoggedUntrustedServerFailsBeforeAnyPlatformDdl() {
        DataSource dataSource = new DriverManagerDataSource(
            configuredJdbcUrl(),
            MYSQL.getUsername(),
            MYSQL.getPassword()
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertEquals(
            1,
            jdbc.queryForObject("select @@global.log_bin", Integer.class)
        );
        assertEquals(
            0,
            jdbc.queryForObject(
                "select @@global.log_bin_trust_function_creators",
                Integer.class
            )
        );

        FlywayException failure = assertThrows(
            FlywayException.class,
            () -> Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/mysqlmigration")
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .load()
                .migrate()
        );

        assertTrue(causalMessages(failure).contains(
            MySqlV50TriggerInstallationAuthority.requirement()
        ));
        assertEquals(
            0,
            jdbc.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = database()
                  and table_name like 'ap\\_%' escape '\\\\'
                """, Integer.class)
        );
        assertEquals(
            0,
            jdbc.queryForObject(
                "select @@global.log_bin_trust_function_creators",
                Integer.class
            )
        );
    }

    private static String causalMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
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
