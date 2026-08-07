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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcIdempotencyGuardMySqlContractIntegrationTest {

    private static final String REQUEST_HASH = "c".repeat(64);
    private static final String DIFFERENT_REQUEST_HASH = "d".repeat(64);
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-07T09:31:42.654321Z"),
        ZoneOffset.UTC
    );

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("approval_mysql_idempotency_contract")
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
    void keepsTheSameIdempotencyKeyIndependentAcrossOperations() {
        JdbcIdempotencyGuard guard = guard();
        AtomicInteger actions = new AtomicInteger();
        RequestContext context = context(
            "tenant-a",
            "shared-operation-key",
            "request-operation",
            "trace-operation"
        );

        CommandResult completed = guard.execute(
            context,
            "approval.complete",
            REQUEST_HASH,
            CommandResult.class,
            () -> new CommandResult("APPROVED", actions.incrementAndGet())
        );
        CommandResult rejected = guard.execute(
            context,
            "approval.reject",
            REQUEST_HASH,
            CommandResult.class,
            () -> new CommandResult("REJECTED", actions.incrementAndGet())
        );

        assertEquals(new CommandResult("APPROVED", 1), completed);
        assertEquals(new CommandResult("REJECTED", 2), rejected);
        assertEquals(2, actions.get());
        assertEquals(2, rowCount());
    }

    @Test
    void rejectsAReplayThatChangesOnlyTheDeclaredResultType() {
        JdbcIdempotencyGuard guard = guard();
        AtomicInteger actions = new AtomicInteger();
        RequestContext context = context(
            "tenant-a",
            "result-type-key",
            "request-result-type",
            "trace-result-type"
        );

        guard.execute(
            context,
            "approval.complete",
            REQUEST_HASH,
            CommandResult.class,
            () -> {
                actions.incrementAndGet();
                return new CommandResult("APPROVED", 3);
            }
        );

        assertThrows(
            IdempotencyGuard.IdempotencyConflictException.class,
            () -> guard.execute(
                context,
                "approval.complete",
                REQUEST_HASH,
                AlternativeResult.class,
                () -> {
                    actions.incrementAndGet();
                    return new AlternativeResult("MUST_NOT_RUN");
                }
            )
        );
        assertEquals(1, actions.get());
        assertEquals(1, rowCount());
    }

    @Test
    void concurrentDifferentPayloadFailsClosedAfterOnlyTheAcceptedAction()
        throws Exception {
        JdbcIdempotencyGuard guard = guard();
        RequestContext context = context(
            "tenant-a",
            "concurrent-conflict-key",
            "request-concurrent",
            "trace-concurrent"
        );
        CommandResult expected = new CommandResult("APPROVED", 4);
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
                DIFFERENT_REQUEST_HASH,
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
        ExecutionException conflict = assertThrows(
            ExecutionException.class,
            () -> second.get(30, TimeUnit.SECONDS)
        );
        assertTrue(
            conflict.getCause()
                instanceof IdempotencyGuard.IdempotencyConflictException
        );
        assertEquals(1, actions.get());
        assertEquals(1, rowCount());
    }

    @Test
    void roundTripsUnicodeAndExactNumericJsonWithoutRepeatingTheAction() {
        JdbcIdempotencyGuard guard = guard();
        AtomicInteger actions = new AtomicInteger();
        RequestContext context = context(
            "tenant-a",
            "json-round-trip-key",
            "request-json",
            "trace-json"
        );
        RichResult expected = new RichResult(
            "审批✅ / Straße / İstanbul",
            new BigDecimal("123456789012.123456"),
            new BigInteger("9223372036854775807")
        );

        RichResult first = guard.execute(
            context,
            "approval.complete",
            REQUEST_HASH,
            RichResult.class,
            () -> {
                actions.incrementAndGet();
                return expected;
            }
        );
        RichResult replay = guard.execute(
            context,
            "approval.complete",
            REQUEST_HASH,
            RichResult.class,
            () -> {
                actions.incrementAndGet();
                return new RichResult(
                    "MUST_NOT_RUN",
                    BigDecimal.ZERO,
                    BigInteger.ZERO
                );
            }
        );

        assertEquals(expected, first);
        assertEquals(expected, replay);
        assertEquals(1, actions.get());
        assertEquals(1, rowCount());
    }

    @Test
    void replayNeverRewritesTheFirstRequestAndTraceEvidence() {
        JdbcIdempotencyGuard guard = guard();
        RequestContext firstContext = context(
            "tenant-a",
            "immutable-evidence-key",
            "request-original",
            "trace-original"
        );
        RequestContext replayContext = context(
            "tenant-a",
            "immutable-evidence-key",
            "request-replay",
            "trace-replay"
        );

        guard.execute(
            firstContext,
            "approval.complete",
            REQUEST_HASH,
            CommandResult.class,
            () -> new CommandResult("APPROVED", 5)
        );
        guard.execute(
            replayContext,
            "approval.complete",
            REQUEST_HASH,
            CommandResult.class,
            () -> new CommandResult("MUST_NOT_RUN", -1)
        );

        Map<String, Object> row = jdbc.queryForMap("""
            select request_id, trace_id
            from ap_command_idempotency
            where tenant_id = ?
              and operation = ?
              and idempotency_key = ?
            """,
            "tenant-a",
            "approval.complete",
            "immutable-evidence-key"
        );
        assertEquals("request-original", row.get("request_id"));
        assertEquals("trace-original", row.get("trace_id"));
        assertEquals(1, rowCount());
    }

    private static JdbcIdempotencyGuard guard() {
        return new JdbcIdempotencyGuard(
            dataSource,
            new ObjectMapper(),
            new JdbcTransactionManager(dataSource),
            CLOCK
        );
    }

    private static RequestContext context(
        String tenantId,
        String idempotencyKey,
        String requestId,
        String traceId
    ) {
        return new RequestContext(
            tenantId,
            "operator-1",
            requestId,
            idempotencyKey,
            traceId
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

    private record AlternativeResult(String disposition) {
    }

    private record RichResult(
        String message,
        BigDecimal amount,
        BigInteger sequence
    ) {
    }
}
