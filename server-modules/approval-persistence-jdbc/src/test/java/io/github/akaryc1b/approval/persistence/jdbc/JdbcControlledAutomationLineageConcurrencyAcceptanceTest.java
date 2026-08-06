package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.LineageOutcome;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.LineageStatus;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.RegistrationCommand;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.RegistrationDisposition;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.TransitionCommand;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.TransitionDisposition;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcControlledAutomationLineageConcurrencyAcceptanceTest {

    private static final Instant BASE = Instant.parse("2026-08-06T05:45:00Z");
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong(10_000);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_controlled_automation_concurrency_test")
        .withUsername("approval")
        .withPassword("approval");

    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactionManager;
    private static JdbcControlledAutomationLineageStore store;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        store = new JdbcControlledAutomationLineageStore(
            dataSource,
            transactionManager,
            JdbcControlledAutomationLineageConcurrencyAcceptanceTest::nextEventId
        );
    }

    @Test
    void concurrentExactRegistrationProducesOneStateAndOneEvent() throws Exception {
        RegistrationCommand command = registration("exact-registration-race");
        int contenders = 16;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RegistrationDisposition>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < contenders; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return store.register(command).disposition();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<RegistrationDisposition> dispositions = values(results);
            assertEquals(1, count(dispositions, RegistrationDisposition.REGISTERED));
            assertEquals(contenders - 1L, count(dispositions, RegistrationDisposition.REPLAYED));
            assertEquals(0, count(dispositions, RegistrationDisposition.CONFLICT));
        }

        assertEquals(1, lineageCount(command));
        assertEquals(1, eventCount(command));
    }

    @Test
    void concurrentSameKeyDifferentPayloadHasOneOwnerAndOneConflict() throws Exception {
        String tenant = "tenant-registration-key-race";
        String keyHash = hash("shared-registration-key");
        RegistrationCommand first = registration(
            tenant,
            "operator-registration-key-race",
            uuid("proposal-registration-key-first"),
            "registration-key-first",
            keyHash,
            hash("payload-first")
        );
        RegistrationCommand second = registration(
            tenant,
            "operator-registration-key-race",
            uuid("proposal-registration-key-second"),
            "registration-key-second",
            keyHash,
            hash("payload-second")
        );

        List<RegistrationDisposition> dispositions = raceRegistrations(first, second);

        assertEquals(1, count(dispositions, RegistrationDisposition.REGISTERED));
        assertEquals(1, count(dispositions, RegistrationDisposition.CONFLICT));
        assertEquals(1, lineageCountByTenant(first.tenantEvidenceHash()));
        assertEquals(1, eventCountByTenant(first.tenantEvidenceHash()));
    }

    @Test
    void mixedReplayAndConflictRaceCannotDuplicateStateOrEvent() throws Exception {
        String tenant = "tenant-registration-mixed-race";
        String keyHash = hash("mixed-registration-key");
        RegistrationCommand first = registration(
            tenant,
            "operator-registration-mixed-race",
            uuid("proposal-registration-mixed-first"),
            "registration-mixed-first",
            keyHash,
            hash("payload-first")
        );
        RegistrationCommand conflicting = registration(
            tenant,
            "operator-registration-mixed-race",
            uuid("proposal-registration-mixed-conflict"),
            "registration-mixed-conflict",
            keyHash,
            hash("payload-conflict")
        );
        int contenders = 20;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RegistrationDisposition>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < contenders; index++) {
                RegistrationCommand command = index % 2 == 0 ? first : conflicting;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return store.register(command).disposition();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<RegistrationDisposition> dispositions = values(results);
            assertEquals(1, count(dispositions, RegistrationDisposition.REGISTERED));
            assertTrue(count(dispositions, RegistrationDisposition.REPLAYED) > 0);
            assertTrue(count(dispositions, RegistrationDisposition.CONFLICT) > 0);
        }

        assertEquals(1, lineageCountByTenant(first.tenantEvidenceHash()));
        assertEquals(1, eventCountByTenant(first.tenantEvidenceHash()));
    }

    @Test
    void identicalProposalIdsRemainIsolatedAcrossConcurrentTenants() throws Exception {
        UUID proposalId = uuid("shared-external-proposal-id");
        RegistrationCommand first = registration(
            "tenant-isolated-race-a",
            "operator-isolated-race-a",
            proposalId,
            "tenant-isolated-race-a",
            hash("tenant-isolated-key-a"),
            hash("tenant-isolated-payload-a")
        );
        RegistrationCommand second = registration(
            "tenant-isolated-race-b",
            "operator-isolated-race-b",
            proposalId,
            "tenant-isolated-race-b",
            hash("tenant-isolated-key-b"),
            hash("tenant-isolated-payload-b")
        );

        List<RegistrationDisposition> dispositions = raceRegistrations(first, second);

        assertEquals(2, count(dispositions, RegistrationDisposition.REGISTERED));
        assertEquals(1, lineageCount(first));
        assertEquals(1, lineageCount(second));
        assertEquals(1, eventCount(first));
        assertEquals(1, eventCount(second));
        assertTrue(store.find(
            first.tenantEvidenceHash(),
            first.operatorEvidenceHash(),
            proposalId
        ).isPresent());
        assertTrue(store.find(
            second.tenantEvidenceHash(),
            second.operatorEvidenceHash(),
            proposalId
        ).isPresent());
        assertFalse(store.find(
            first.tenantEvidenceHash(),
            second.operatorEvidenceHash(),
            proposalId
        ).isPresent());
    }

    @Test
    void everyTerminalPairHasOneWinnerAndOneConsistentState() throws Exception {
        List<TerminalPair> pairs = List.of(
            new TerminalPair(
                LineageStatus.SUCCEEDED,
                LineageOutcome.SUCCESS,
                1,
                LineageStatus.FAILED,
                LineageOutcome.FAILURE,
                1
            ),
            new TerminalPair(
                LineageStatus.SUCCEEDED,
                LineageOutcome.SUCCESS,
                1,
                LineageStatus.UNKNOWN,
                LineageOutcome.UNKNOWN,
                1
            ),
            new TerminalPair(
                LineageStatus.FAILED,
                LineageOutcome.FAILURE,
                1,
                LineageStatus.PARTIAL,
                LineageOutcome.PARTIAL,
                1
            ),
            new TerminalPair(
                LineageStatus.CANCELLED,
                LineageOutcome.NONE,
                0,
                LineageStatus.SUCCEEDED,
                LineageOutcome.SUCCESS,
                1
            )
        );

        for (int index = 0; index < pairs.size(); index++) {
            TerminalPair pair = pairs.get(index);
            RegistrationCommand registration = registration("terminal-pair-" + index);
            assertEquals(
                RegistrationDisposition.REGISTERED,
                store.register(registration).disposition()
            );
            TransitionCommand first = terminal(
                registration,
                "terminal-pair-" + index + "-first",
                pair.firstStatus(),
                pair.firstOutcome(),
                pair.firstAttempts()
            );
            TransitionCommand second = terminal(
                registration,
                "terminal-pair-" + index + "-second",
                pair.secondStatus(),
                pair.secondOutcome(),
                pair.secondAttempts()
            );

            List<TransitionDisposition> dispositions = raceTransitions(first, second);
            var snapshot = store.find(
                registration.tenantEvidenceHash(),
                registration.operatorEvidenceHash(),
                registration.proposalId()
            ).orElseThrow();

            assertEquals(1, count(dispositions, TransitionDisposition.APPLIED));
            assertEquals(1, count(dispositions, TransitionDisposition.STATE_CONFLICT));
            assertEquals(2, snapshot.revision());
            assertTrue(
                snapshot.status() == pair.firstStatus()
                    || snapshot.status() == pair.secondStatus()
            );
            assertEquals(
                snapshot.status() == LineageStatus.CANCELLED ? 0 : 1,
                snapshot.commandAttempts()
            );
            assertFalse(snapshot.automaticRetryAllowed());
            assertEquals(2, eventCount(registration));
        }
    }

    @Test
    void unknownWinsAgainstAConcurrentRetryAndRemainsTerminal() throws Exception {
        RegistrationCommand registration = registration("unknown-retry-race");
        store.register(registration);
        TransitionCommand unknown = terminal(
            registration,
            "unknown-retry-race-unknown",
            LineageStatus.UNKNOWN,
            LineageOutcome.UNKNOWN,
            1
        );
        TransitionCommand retry = TransitionCommand.create(
            registration.tenantEvidenceHash(),
            registration.operatorEvidenceHash(),
            registration.proposalId(),
            2,
            LineageStatus.UNKNOWN,
            LineageStatus.FAILED,
            LineageOutcome.FAILURE,
            hash("unknown-retry-race-retry-result"),
            hash("unknown-retry-race-retry-key"),
            hash("unknown-retry-race-retry-payload"),
            BASE.plusSeconds(20),
            1
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TransitionDisposition> unknownResult = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return store.transition(unknown).disposition();
            });
            Future<TransitionDisposition> retryResult = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return store.transition(retry).disposition();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            assertEquals(TransitionDisposition.APPLIED, unknownResult.get());
            assertTrue(
                retryResult.get() == TransitionDisposition.REVISION_CONFLICT
                    || retryResult.get() == TransitionDisposition.STATE_CONFLICT
            );
        }

        var snapshot = store.find(
            registration.tenantEvidenceHash(),
            registration.operatorEvidenceHash(),
            registration.proposalId()
        ).orElseThrow();
        assertEquals(LineageStatus.UNKNOWN, snapshot.status());
        assertEquals(LineageOutcome.UNKNOWN, snapshot.outcome());
        assertEquals(1, snapshot.commandAttempts());
        assertFalse(snapshot.automaticRetryAllowed());
        assertEquals(2, eventCount(registration));
    }

    @Test
    void exactReplayAfterRowLockReleaseDoesNotAppendAnotherEvent() throws Exception {
        RegistrationCommand registration = registration("row-lock-replay");
        store.register(registration);
        TransitionCommand command = terminal(
            registration,
            "row-lock-replay-transition",
            LineageStatus.SUCCEEDED,
            LineageOutcome.SUCCESS,
            1
        );
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Void> lockOwner = executor.submit(() -> {
                transaction.executeWithoutResult(status -> {
                    jdbc.queryForObject("""
                        select revision
                        from ap_ai_controlled_automation_lineage
                        where tenant_evidence_hash=? and proposal_id=?
                        for update
                        """, Long.class,
                        registration.tenantEvidenceHash(),
                        registration.proposalId());
                    locked.countDown();
                    await(release);
                });
                return null;
            });
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<TransitionDisposition> transition = executor.submit(() -> {
                attempting.countDown();
                return store.transition(command).disposition();
            });
            assertTrue(attempting.await(10, TimeUnit.SECONDS));
            release.countDown();

            lockOwner.get();
            assertEquals(TransitionDisposition.APPLIED, transition.get());
        }

        assertEquals(TransitionDisposition.REPLAYED, store.transition(command).disposition());
        assertEquals(2, eventCount(registration));
    }

    @Test
    void concurrentRegistrationsUseUniqueAppendOnlyEventIdentities() throws Exception {
        int count = 24;
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RegistrationDisposition>> results = new ArrayList<>();
        List<RegistrationCommand> commands = new ArrayList<>();
        String tenant = "tenant-event-sequence-race";

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < count; index++) {
                RegistrationCommand command = registration(
                    tenant,
                    "operator-event-sequence-race",
                    uuid("proposal-event-sequence-" + index),
                    "event-sequence-" + index,
                    hash("event-sequence-key-" + index),
                    hash("event-sequence-payload-" + index)
                );
                commands.add(command);
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return store.register(command).disposition();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(count, count(values(results), RegistrationDisposition.REGISTERED));
        }

        String tenantHash = commands.getFirst().tenantEvidenceHash();
        assertEquals(count, lineageCountByTenant(tenantHash));
        assertEquals(count, eventCountByTenant(tenantHash));
        assertEquals(count, distinctEventCountByTenant(tenantHash));
    }

    private static List<RegistrationDisposition> raceRegistrations(
        RegistrationCommand first,
        RegistrationCommand second
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<RegistrationDisposition> left = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return store.register(first).disposition();
            });
            Future<RegistrationDisposition> right = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return store.register(second).disposition();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(left.get(), right.get());
        }
    }

    private static List<TransitionDisposition> raceTransitions(
        TransitionCommand first,
        TransitionCommand second
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TransitionDisposition> left = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return store.transition(first).disposition();
            });
            Future<TransitionDisposition> right = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return store.transition(second).disposition();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(left.get(), right.get());
        }
    }

    private static TransitionCommand terminal(
        RegistrationCommand registration,
        String key,
        LineageStatus status,
        LineageOutcome outcome,
        int attempts
    ) {
        return TransitionCommand.create(
            registration.tenantEvidenceHash(),
            registration.operatorEvidenceHash(),
            registration.proposalId(),
            1,
            LineageStatus.CONFIRMED,
            status,
            outcome,
            hash(key + "-result"),
            hash(key + "-key"),
            hash(key + "-payload"),
            BASE.plusSeconds(10),
            attempts
        );
    }

    private static RegistrationCommand registration(String suffix) {
        return registration(
            "tenant-" + suffix,
            "operator-" + suffix,
            uuid("proposal-" + suffix),
            suffix,
            hash("registration-key-" + suffix),
            hash("registration-payload-" + suffix)
        );
    }

    private static RegistrationCommand registration(
        String tenant,
        String operator,
        UUID proposalId,
        String suffix,
        String keyHash,
        String payloadHash
    ) {
        return RegistrationCommand.fromEvidence(
            proposalId,
            uuid("confirmation-" + suffix),
            hash(tenant),
            hash(operator),
            hash("proposal-lineage-" + suffix),
            hash("confirmation-evidence-" + suffix),
            "TEST_ONLY_NON_EXECUTABLE_ACTION",
            hash("resource-" + suffix),
            "test-whitelist-v1",
            "test-policy-v1",
            keyHash,
            payloadHash,
            BASE,
            BASE.plusSeconds(120)
        );
    }

    private static int lineageCount(RegistrationCommand command) {
        return jdbc.queryForObject("""
            select count(*)
            from ap_ai_controlled_automation_lineage
            where tenant_evidence_hash=? and proposal_id=?
            """, Integer.class, command.tenantEvidenceHash(), command.proposalId());
    }

    private static int eventCount(RegistrationCommand command) {
        return jdbc.queryForObject("""
            select count(*)
            from ap_ai_controlled_automation_lineage_event
            where tenant_evidence_hash=? and proposal_id=?
            """, Integer.class, command.tenantEvidenceHash(), command.proposalId());
    }

    private static int lineageCountByTenant(String tenantHash) {
        return jdbc.queryForObject("""
            select count(*)
            from ap_ai_controlled_automation_lineage
            where tenant_evidence_hash=?
            """, Integer.class, tenantHash);
    }

    private static int eventCountByTenant(String tenantHash) {
        return jdbc.queryForObject("""
            select count(*)
            from ap_ai_controlled_automation_lineage_event
            where tenant_evidence_hash=?
            """, Integer.class, tenantHash);
    }

    private static int distinctEventCountByTenant(String tenantHash) {
        return jdbc.queryForObject("""
            select count(distinct event_id)
            from ap_ai_controlled_automation_lineage_event
            where tenant_evidence_hash=?
            """, Integer.class, tenantHash);
    }

    private static <T> List<T> values(List<Future<T>> futures) throws Exception {
        List<T> values = new ArrayList<>();
        for (Future<T> future : futures) {
            values.add(future.get());
        }
        return values;
    }

    private static <T> long count(List<T> values, T expected) {
        return values.stream().filter(expected::equals).count();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static String hash(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID nextEventId() {
        return new UUID(0x7000000000000000L, EVENT_SEQUENCE.incrementAndGet());
    }

    private record TerminalPair(
        LineageStatus firstStatus,
        LineageOutcome firstOutcome,
        int firstAttempts,
        LineageStatus secondStatus,
        LineageOutcome secondOutcome,
        int secondAttempts
    ) {
    }
}
