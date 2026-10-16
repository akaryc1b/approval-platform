package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard.IdempotencyConflictException;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcIdempotencyActorBindingIntegrationTest {

    private static final String TENANT = "tenant-actor-binding";
    private static final String OPERATION = "approve";
    private static final String KEY = "shared-key";
    private static final String HASH = "a".repeat(64);
    private static final String ACTOR_CONFLICT =
        "idempotency key was already used with a different actor or request payload";
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC
    );

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_idempotency_actor_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private JdbcIdempotencyGuard guard;
    private AtomicInteger callbacks;

    @BeforeAll
    static void migrateDatabase() {
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        new JdbcTemplate(dataSource).execute("""
            create table idempotency_test_effect (operator_id text not null)
            """);
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("truncate table ap_command_idempotency, idempotency_test_effect");
        guard = newGuard();
        callbacks = new AtomicInteger();
    }

    @Test
    void sameActorDurablyReplaysOnceWithNewCorrelationIds() {
        Receipt first = execute(guard, context("alice"), OPERATION, HASH);
        Receipt replay = execute(newGuard(), context("alice"), OPERATION, HASH);

        assertEquals(first, replay);
        assertEquals(1, callbacks.get());
        assertEquals(1, effectCount());
        assertEquals(1, commandCount());
        String storedHash = (String) commandRow().get("request_hash");
        assertTrue(storedHash.matches("[0-9a-f]{64}"));
        assertNotEquals(HASH, storedHash);
    }

    @Test
    void differentActorIsRejectedBeforeDecodingAnInvalidCachedResult() {
        execute(guard, context("alice"), OPERATION, HASH);
        jdbc.update("""
            update ap_command_idempotency
            set result_json = '{"operatorId":"alice","sequence":{"invalid":true}}'::jsonb
            """);
        Map<String, Object> before = commandRow();

        assertActorConflict("bob", HASH);
        IllegalStateException decodeFailure = assertThrows(
            IllegalStateException.class,
            () -> execute(guard, context("alice"), OPERATION, HASH)
        );

        assertEquals("unable to decode idempotent command result", decodeFailure.getMessage());
        assertEquals(before, commandRow());
        assertEquals(1, callbacks.get());
        assertEquals(1, effectCount());
    }

    @Test
    void differentActorIsRejectedBeforeStatusAndResultTypeChecks() {
        execute(guard, context("alice"), OPERATION, HASH);
        jdbc.update("""
            update ap_command_idempotency set status = 'IN_PROGRESS', result_type = 'wrong-type'
            """);
        Map<String, Object> before = commandRow();

        assertActorConflict("bob", HASH);
        assertEquals(before, commandRow());
        assertEquals(1, callbacks.get());
        assertEquals(1, effectCount());
    }

    @Test
    void sameActorCannotReuseKeyWithChangedPayload() {
        execute(guard, context("alice"), OPERATION, HASH);
        Map<String, Object> before = commandRow();

        assertActorConflict("alice", "b".repeat(64));

        assertEquals(before, commandRow());
        assertEquals(1, callbacks.get());
        assertEquals(1, effectCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "IN_PROGRESS"})
    void legacyUnboundRowsRejectOriginalAndDifferentActorsWithoutSideEffects(String status) {
        jdbc.update("""
            insert into ap_command_idempotency (
                tenant_id, operation, idempotency_key, request_hash, request_id,
                status, result_type, result_json, created_at
            ) values (?, ?, ?, ?, 'legacy-request', ?, ?,
                '{"operatorId":"alice","sequence":1}'::jsonb, current_timestamp)
            """, TENANT, OPERATION, KEY, HASH, status, Receipt.class.getName());
        Map<String, Object> before = commandRow();

        assertActorConflict("alice", HASH);
        assertActorConflict("bob", HASH);

        assertEquals(before, commandRow());
        assertEquals(0, callbacks.get());
        assertEquals(0, effectCount());
        assertEquals(1, commandCount());
    }

    @Test
    void tenantAndOperationRemainIndependentNamespaces() {
        Receipt first = execute(guard, context("alice"), OPERATION, HASH);
        RequestContext otherTenant = context("tenant-other", "bob", KEY);
        Receipt second = execute(guard, otherTenant, OPERATION, HASH);
        Receipt third = execute(guard, context("bob"), "withdraw", HASH);

        assertEquals(first, execute(newGuard(), context("alice"), OPERATION, HASH));
        assertEquals(second, execute(newGuard(), otherTenant, OPERATION, HASH));
        assertEquals(third, execute(newGuard(), context("bob"), "withdraw", HASH));
        assertEquals(3, callbacks.get());
        assertEquals(3, effectCount());
        assertEquals(3, commandCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"审批人:😀\n64:", "é", "e\u0301", "alice:64:"})
    void unicodeAndDelimiterCharactersPreserveExactActorIdentity(String actor) {
        Receipt first = execute(guard, context(actor), OPERATION, HASH);

        assertEquals(first, execute(newGuard(), context(actor), OPERATION, HASH));
        assertActorConflict(actor + ":", HASH);
        assertActorConflict(actor.equals("é") ? "e\u0301" : "é", HASH);
        assertEquals(1, callbacks.get());
        assertEquals(1, effectCount());
    }

    @Test
    void malformedUnicodeCannotAliasAReplacementCharacterActor() {
        execute(guard, context("?"), OPERATION, HASH);
        Map<String, Object> before = commandRow();

        assertThrows(
            IllegalArgumentException.class,
            () -> execute(guard, context("\uD800"), OPERATION, HASH)
        );

        assertEquals(before, commandRow());
        assertEquals(1, callbacks.get());
        assertEquals(1, effectCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentActorsCannotBothExecuteTheSameKey(boolean sameActor) throws Exception {
        RequestContext firstContext = context("alice");
        RequestContext secondContext = context(sameActor ? "alice" : "bob");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Object first;
        Object second;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> firstFuture = executor.submit(
                () -> race(firstContext, ready, release)
            );
            Future<Object> secondFuture = executor.submit(
                () -> race(secondContext, ready, release)
            );
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS), "both callers must be ready");
            } finally {
                release.countDown();
            }
            first = firstFuture.get(20, TimeUnit.SECONDS);
            second = secondFuture.get(20, TimeUnit.SECONDS);
        }

        List<Object> outcomes = List.of(first, second);
        Receipt winner = outcomes.stream().filter(Receipt.class::isInstance)
            .map(Receipt.class::cast).findFirst().orElseThrow();
        if (sameActor) {
            assertEquals(winner, first);
            assertEquals(winner, second);
        } else {
            assertEquals(1L, outcomes.stream().filter(Receipt.class::isInstance).count());
            Object loser = first instanceof Receipt ? second : first;
            assertEquals(ACTOR_CONFLICT, assertInstanceOf(
                IdempotencyConflictException.class, loser
            ).getMessage());
        }
        assertEquals(winner, execute(newGuard(), context(winner.operatorId()), OPERATION, HASH));
        assertEquals(1, callbacks.get());
        assertEquals(1, effectCount());
        assertEquals(1, commandCount());
        assertEquals(winner.operatorId(), jdbc.queryForObject(
            "select operator_id from idempotency_test_effect", String.class
        ));
    }

    @Test
    void failedCommandRollsBackItsReservationAndDatabaseEffects() {
        assertThrows(IllegalStateException.class, () -> guard.execute(
            context("alice"), OPERATION, HASH, Receipt.class, () -> {
                effect("alice");
                throw new IllegalStateException("command failed");
            }
        ));
        assertEquals(0, effectCount());
        assertEquals(0, commandCount());

        Receipt retry = execute(newGuard(), context("bob"), OPERATION, HASH);

        assertEquals("bob", retry.operatorId());
        assertEquals(1, effectCount());
        assertEquals(1, commandCount());
    }

    private void assertActorConflict(String actor, String hash) {
        IdempotencyConflictException conflict = assertThrows(
            IdempotencyConflictException.class,
            () -> execute(guard, context(actor), OPERATION, hash)
        );
        assertEquals(ACTOR_CONFLICT, conflict.getMessage());
    }

    private Object race(RequestContext context, CountDownLatch ready, CountDownLatch release)
        throws InterruptedException {
        JdbcIdempotencyGuard independentGuard = newGuard();
        ready.countDown();
        assertTrue(release.await(10, TimeUnit.SECONDS), "both callers must be released");
        try {
            return execute(independentGuard, context, OPERATION, HASH);
        } catch (IdempotencyConflictException exception) {
            return exception;
        }
    }

    private Receipt execute(
        JdbcIdempotencyGuard target, RequestContext context, String operation, String hash
    ) {
        return target.execute(context, operation, hash, Receipt.class, () -> {
            int sequence = callbacks.incrementAndGet();
            effect(context.operatorId());
            return new Receipt(context.operatorId(), sequence);
        });
    }

    private void effect(String actor) {
        jdbc.update("insert into idempotency_test_effect (operator_id) values (?)", actor);
    }

    private int effectCount() {
        return jdbc.queryForObject("select count(*) from idempotency_test_effect", Integer.class);
    }

    private int commandCount() {
        return jdbc.queryForObject("select count(*) from ap_command_idempotency", Integer.class);
    }

    private Map<String, Object> commandRow() {
        return jdbc.queryForMap("select * from ap_command_idempotency");
    }

    private static RequestContext context(String actor) {
        return context(TENANT, actor, KEY);
    }

    private static RequestContext context(String tenant, String actor, String key) {
        return new RequestContext(
            tenant, actor, UUID.randomUUID().toString(), key, UUID.randomUUID().toString()
        );
    }

    private static JdbcIdempotencyGuard newGuard() {
        return new JdbcIdempotencyGuard(
            dataSource, new ObjectMapper().findAndRegisterModules(),
            new JdbcTransactionManager(dataSource), CLOCK
        );
    }

    record Receipt(String operatorId, int sequence) {
    }
}
