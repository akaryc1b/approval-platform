package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcIdempotencyGuardMySqlIntegrationTest {

    private static final String REQUEST_HASH = "a".repeat(64);
    private static final String DIFFERENT_REQUEST_HASH = "b".repeat(64);
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-07T09:10:11.123456Z"),
        ZoneOffset.UTC
    );

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_idempotency")
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

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private ExecutorService executor;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
            configuredJdbcUrl(),
            MYSQL.getUsername(),
            MYSQL.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/mysqlmigration")
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .load()
            .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void clean() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        jdbc.update("delete from ap_command_idempotency");
    }

    @Test
    void replaysTheExactCompletedResultWithoutRepeatingTheAction() {
        JdbcIdempotencyGuard guard = guard();
        AtomicInteger actions = new AtomicInteger();
        RequestContext context = context("tenant-a", "idempotency-1");
        CommandResult expected = new CommandResult("APPROVED", 7);

        CommandResult first = guard.execute(
            context,
            "approval.complete",
            REQUEST_HASH,
            CommandResult.class,
            () -> {
                actions.incrementAndGet();
                return expected;
            }
        );
        CommandResult replay = guard.execute(
            context,
            "approval.complete",
            REQUEST_HASH,
            CommandResult.class,
            () -> {
                actions.incrementAndGet();
                return new CommandResult("MUST_NOT_RUN", -1);
            }
        );

        assertEquals(expected, first);
        assertEquals(expected, replay);
        assertEquals(1, actions.get());
        assertEquals(1, rowCount());
    }

    @Test
    void rejectsTheSameKeyWithADifferentPayloadHash() {
        JdbcIdempotencyGuard guard = guard();
        RequestContext context = context("tenant-a", "idempotency-2");
        guard.execute(
            context,
            "approval.complete",
            REQUEST_HASH,
            CommandResult.class,
            () -> new CommandResult("APPROVED", 8)
        );

        assertThrows(
            IdempotencyGuard.IdempotencyConflictException.class,
            () -> guard.execute(
                context,
                "approval.complete",
                DIFFERENT_REQUEST_HASH,
                CommandResult.class,
                () -> new CommandResult("REJECTED", 8)
            )
        );
        assertEquals(1, rowCount());
    }

    @Test
    void rollsBackAdmissionWhenTheCommandActionFails() {
        JdbcIdempotencyGuard guard = guard();
        RequestContext context = context("tenant-a", "idempotency-3");

        assertThrows(
            IllegalStateException.class,
            () -> guard.execute(
                context,
                "approval.complete",
                REQUEST_HASH,
                CommandResult.class,
                () -> {
                    throw new IllegalStateException("command failed");
                }
            )
        );
        assertEquals(0, rowCount());

        CommandResult retried = guard.execute(
            context,
            "approval.complete",
            REQUEST_HASH,
            CommandResult.class,
            () -> new CommandResult("APPROVED", 9)
        );
        assertEquals(new CommandResult("APPROVED", 9), retried);
        assertEquals(1, rowCount());
    }

    @Test
    void serializesConcurrentDuplicateAdmissionAndExecutesOnlyOneAction()
        throws Exception {
        JdbcIdempotencyGuard guard = guard();
        RequestContext context = context("tenant-a", "idempotency-4");
        CommandResult expected = new CommandResult("APPROVED", 10);
        AtomicInteger actions = new AtomicInteger();
        CountDownLatch firstActionEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstAction = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        executor = Executors.newFixedThreadPool(2);

        var first = executor.submit(() -> guard.execute(
            context,
            "approval.complete",
            REQUEST_HASH,
            CommandResult.class,
            () -> {
                actions.incrementAndGet();
                firstActionEntered.countDown();
                await(releaseFirstAction);
                return expected;
            }
        ));
        assertTrue(firstActionEntered.await(10, TimeUnit.SECONDS));

        var second = executor.submit(() -> {
            secondStarted.countDown();
            return guard.execute(
                context,
                "approval.complete",
                REQUEST_HASH,
                CommandResult.class,
                () -> {
                    actions.incrementAndGet();
                    return new CommandResult("MUST_NOT_RUN", -1);
                }
            );
        });
        assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
        releaseFirstAction.countDown();

        assertEquals(expected, first.get(30, TimeUnit.SECONDS));
        assertEquals(expected, second.get(30, TimeUnit.SECONDS));
        assertEquals(1, actions.get());
        assertEquals(1, rowCount());
    }

    @Test
    void keepsTenantAndCaseSensitiveKeyScopesIndependent() {
        JdbcIdempotencyGuard guard = guard();
        AtomicInteger actions = new AtomicInteger();

        for (RequestContext context : java.util.List.of(
            context("tenant-a", "Case-Key"),
            context("tenant-a", "case-key"),
            context("tenant-b", "Case-Key")
        )) {
            guard.execute(
                context,
                "approval.complete",
                REQUEST_HASH,
                CommandResult.class,
                () -> new CommandResult("R" + actions.incrementAndGet(), 11)
            );
        }

        assertEquals(3, actions.get());
        assertEquals(3, rowCount());
    }

    @Test
    void mysqlDialectUsesNarrowAdmissionAndCanonicalResultEnvelope() {
        String admission = JdbcIdempotencyDialect.MYSQL.admissionSql().toLowerCase();
        String completion = JdbcIdempotencyDialect.MYSQL.completionSql();
        String replay = JdbcIdempotencyDialect.MYSQL.replaySql();

        assertFalse(admission.contains("insert ignore"));
        assertFalse(admission.contains("on duplicate key update"));
        assertFalse(completion.contains("cast(:resultJson as json)"));
        assertTrue(completion.contains("json_object("));
        assertTrue(completion.contains("'encoding', 'CANONICAL_JSON_TEXT_V1'"));
        assertTrue(completion.contains("'payload', :resultJson"));
        assertTrue(replay.contains("json_type(result_json) = 'OBJECT'"));
        assertTrue(replay.contains("= 'CANONICAL_JSON_TEXT_V1'"));
        assertTrue(replay.contains(
            "json_unquote(json_extract(result_json, '$.payload'))"
        ));
        assertTrue(JdbcIdempotencyDialect.POSTGRESQL.admissionSql().contains(
            "on conflict"
        ));
        assertTrue(JdbcIdempotencyDialect.POSTGRESQL.completionSql().contains(
            "cast(:resultJson as jsonb)"
        ));
    }

    private static JdbcIdempotencyGuard guard() {
        return new JdbcIdempotencyGuard(
            dataSource,
            new ObjectMapper(),
            new JdbcTransactionManager(dataSource),
            CLOCK
        );
    }

    private static RequestContext context(String tenantId, String idempotencyKey) {
        return new RequestContext(
            tenantId,
            "operator-1",
            "request-" + idempotencyKey,
            idempotencyKey,
            "trace-" + idempotencyKey
        );
    }

    private static int rowCount() {
        Integer count = jdbc.queryForObject(
            "select count(*) from ap_command_idempotency",
            Integer.class
        );
        return count == null ? 0 : count;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("latch interrupted", exception);
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

    private record CommandResult(String disposition, int revision) {
    }
}
