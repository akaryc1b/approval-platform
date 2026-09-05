package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.DriverManager;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalDatabaseVendorMySqlIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_vendor_test")
        .withUsername("approval")
        .withPassword("approval")
        .withCommand(MySql84ProductionTestServer.command());

    @Test
    void resolvesAndValidatesRealMySql84ProductionBaseline() {
        var dataSource = new DriverManagerDataSource(
            configuredJdbcUrl(),
            MYSQL.getUsername(),
            MYSQL.getPassword()
        );

        var identity = new ApprovalDatabaseVendorResolver().resolve(
            dataSource,
            ApprovalDatabaseVendor.MYSQL
        );
        var baseline = new ApprovalDatabaseRuntimeBaselineValidator().validate(
            dataSource,
            identity
        );

        assertEquals(ApprovalDatabaseVendor.MYSQL, identity.vendor());
        assertEquals(8, identity.majorVersion());
        assertEquals(4, identity.minorVersion());
        assertTrue(identity.productVersion().startsWith("8.4."));
        assertEquals(ApprovalDatabaseVendor.MYSQL, baseline.vendor());
        assertEquals("utf8mb4_0900_as_cs", baseline.settings().get("connectionCollation"));
        assertEquals("READ-COMMITTED", baseline.settings().get("transactionIsolation"));
    }

    @Test
    void exposesExactCharacterTimeIsolationAndStrictModeSettings() throws Exception {
        try (var connection = DriverManager.getConnection(
            configuredJdbcUrl(),
            MYSQL.getUsername(),
            MYSQL.getPassword()
        ); var statement = connection.createStatement(); var result = statement.executeQuery("""
            select
                @@character_set_server,
                @@collation_server,
                @@character_set_connection,
                @@collation_connection,
                @@session.time_zone,
                @@session.sql_mode,
                @@default_storage_engine,
                @@session.transaction_isolation,
                @@innodb_strict_mode
            """)) {
            assertTrue(result.next());
            assertEquals("utf8mb4", result.getString(1));
            assertEquals("utf8mb4_0900_as_cs", result.getString(2));
            assertEquals("utf8mb4", result.getString(3));
            assertEquals("utf8mb4_0900_as_cs", result.getString(4));
            assertEquals("+00:00", result.getString(5));
            String sqlMode = result.getString(6).toUpperCase(Locale.ROOT);
            assertTrue(sqlMode.contains("STRICT_TRANS_TABLES"));
            assertTrue(sqlMode.contains("ERROR_FOR_DIVISION_BY_ZERO"));
            assertTrue(sqlMode.contains("NO_ENGINE_SUBSTITUTION"));
            assertEquals("InnoDB", result.getString(7));
            assertEquals("READ-COMMITTED", result.getString(8));
            assertTrue(result.getBoolean(9));
        }
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
