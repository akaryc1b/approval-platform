package io.github.akaryc1b.approval.demo;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real pgjdbc OffsetDateTime binding, not a simulated database rounding function. */
@Testcontainers(disabledWithoutDocker = true)
class PurchasePaymentDemoProjectionPostgresTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_test")
        .withUsername("approval")
        .withPassword("approval");

    @Test
    void seedPrecisionMatchesActualPostgresBindingsIncludingRollover() throws Exception {
        var normalize = PurchasePaymentDemoSeeder.class.getDeclaredMethod("micros", Instant.class);
        normalize.setAccessible(true);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(), POSTGRES.getPassword());
            var query = connection.prepareStatement("select cast(? as timestamp with time zone)")) {
            for (int nanos : new int[] {0, 499, 500, 999, 123456499, 123456500,
                123456789, 999999499, 999999500, 999999999}) {
                Instant original = Instant.parse("2026-09-10T12:00:00Z").plusNanos(nanos);
                query.setObject(1, original.atOffset(ZoneOffset.UTC));
                try (var result = query.executeQuery()) {
                    assertTrue(result.next());
                    Instant persisted = result.getObject(1, OffsetDateTime.class).toInstant();
                    assertEquals(persisted, normalize.invoke(null, original));
                    assertEquals(persisted, normalize.invoke(null, persisted));
                }
            }
        }
    }
}
