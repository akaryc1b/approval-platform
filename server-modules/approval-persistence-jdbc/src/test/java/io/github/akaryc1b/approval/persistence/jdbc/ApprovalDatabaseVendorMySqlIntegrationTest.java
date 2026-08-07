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
        .withCommand(
            "--default-time-zone=+00:00",
            "--character-set-server=utf8mb4",
            "--collation-server=utf8mb4_0900_as_cs",
            "--sql-mode=STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION"
        );

    @Test
    void resolvesRealMySql84FromJdbcMetadata() {
        var dataSource = new DriverManagerDataSource(
            MYSQL.getJdbcUrl(),
            MYSQL.getUsername(),
            MYSQL.getPassword()
        );

        var identity = new ApprovalDatabaseVendorResolver().resolve(
            dataSource,
            ApprovalDatabaseVendor.MYSQL
        );

        assertEquals(ApprovalDatabaseVendor.MYSQL, identity.vendor());
        assertEquals(8, identity.majorVersion());
        assertEquals(4, identity.minorVersion());
        assertTrue(identity.productVersion().startsWith("8.4."));
    }

    @Test
    void enforcesProductionCharacterTimeAndStrictModeBaseline() throws Exception {
        try (var connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(),
            MYSQL.getUsername(),
            MYSQL.getPassword()
        ); var statement = connection.createStatement(); var result = statement.executeQuery("""
            select
                @@character_set_server,
                @@collation_server,
                @@session.time_zone,
                @@session.sql_mode
            """)) {
            assertTrue(result.next());
            assertEquals("utf8mb4", result.getString(1));
            assertEquals("utf8mb4_0900_as_cs", result.getString(2));
            assertEquals("+00:00", result.getString(3));
            String sqlMode = result.getString(4).toUpperCase(Locale.ROOT);
            assertTrue(sqlMode.contains("STRICT_TRANS_TABLES"));
            assertTrue(sqlMode.contains("ERROR_FOR_DIVISION_BY_ZERO"));
            assertTrue(sqlMode.contains("NO_ENGINE_SUBSTITUTION"));
        }
    }
}
