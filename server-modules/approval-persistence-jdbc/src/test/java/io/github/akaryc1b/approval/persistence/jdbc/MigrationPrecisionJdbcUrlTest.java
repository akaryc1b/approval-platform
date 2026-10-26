package io.github.akaryc1b.approval.persistence.jdbc;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MigrationPrecisionJdbcUrlTest {
    @Test void preservesExistingContainerQueryAndAppliesSchemaForBothSupportedUrlShapes() {
        for (String url : new String[] { "jdbc:postgresql://127.0.0.1:55432/approval",
            "jdbc:postgresql://127.0.0.1:55432/approval?loggerLevel=OFF" }) {
            Properties parsed = org.postgresql.Driver.parseURL(
                JdbcApprovalMigrationTimestampPrecisionIntegrationTest.withSchema(url, "precision_fixture"), new Properties());
            assertEquals("precision_fixture,public", parsed.getProperty("currentSchema"));
            if (url.contains("?")) assertEquals("OFF", parsed.getProperty("loggerLevel"));
        }
        Properties broken = org.postgresql.Driver.parseURL(
            "jdbc:postgresql://127.0.0.1:55432/approval?loggerLevel=OFF?currentSchema=precision_fixture,public", new Properties());
        assertNull(broken.getProperty("currentSchema"), "old URL composition loses the schema parameter");
    }
}
