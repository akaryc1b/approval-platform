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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcControlledAutomationLineageConcurrencyAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T04:30:00Z");
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_controlled_automation_lineage_concurrency_test")
        .withUsername("approval")
        .withPassword("approval");

    private static JdbcTemplate jdbc;
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
        store = new JdbcControlledAutomationLineageStore(
            dataSource,
            new DataSourceTransactionManager(dataSource),
            JdbcControlledAutomationLineageConcurrencyAcceptanceTest::nextEventId
        );
    }

    @Test
    void concurrentExactRegistrationCreatesOneStateOneEventAndExactReplays()
        throws Exception {
        RegistrationCommand command = registration("exact-register");
        List<RegistrationDisposition> dispositions = concurrent(
            8,
            () -> store.register(command).disposition()
        );

        assertEquals(1, count(dispositions, RegistrationDisposition.REGISTERED));
        assertEquals(7, count(dispositions, RegistrationDisposition.REPLAYED));
        assertEquals(1, lineageCount(command.tenantEvidenceHash(), command.proposalId()));
        assertEquals(1, eventCount(command.tenantEvidenceHash(), command.proposalId()));
    }

    @Test
    void concurrentSameIdempotencyKeyDifferentPayloadCreatesOneConflict()
        throws Exception {
        RegistrationCommand first = registration("registration-conflict-a");
        RegistrationCommand second = registrationWithSharedKey(
            "registration-conflict-b",
            first.idempotencyKeyHash(),
            hash("different-payload")
        );
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<RegistrationDisposition> left = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return store.register(first).disposition();
            });
            Future<RegistrationDisposition> right = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return store.register(second).disposition();
            });
            List<RegistrationDisposition> dispositions = List.of(
                left.get(20, TimeUnit.SECONDS),
                right.get(20, TimeUnit.SECONDS)
            );

            assertEquals(1, count(dispositions, RegistrationDisposition.REGISTERED));
            assertEquals(1, count(dispositions, RegistrationDisposition.CONFLICT));
        }
        assertEquals(1, tenantLineageCount(first.tenantEvidenceHash()));
        assertEquals(1, tenantEventCount(first.tenantEvidenceHash()));
    }

    @Test
    void differentTenantsMayRegisterTheSameProposalIdentityConcurrently()
        throws Exception {
        UUID proposalId = uuid("shared-proposal-id");
        RegistrationCommand first = registration(
            "tenant-isolation-a",
            hash("tenant-a"),
            proposalId
        );
        RegistrationCommand second = registration(
            "tenant-isolation-b",
            hash("tenant-b"),
            proposalId
        );
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<RegistrationDisposition> left = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return store.register(first).disposition();
            });
            Future<RegistrationDisposition> right = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return store.register(second).disposition();
            });

            assertEquals(RegistrationDisposition.REGISTERED, left.get(20, TimeUnit.SECONDS));
            assertEquals(RegistrationDisposition.REGISTERED, right.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, lineageCount(first.tenantEvidenceHash(), proposalId));
        assertEquals(1, lineageCount(second.tenantEvidenceHash(), proposalId));
    }

    @Test
    void concurrentExactTerminalTransitionHasOneApplyAndExactReplays()
        throws Exception {
        RegistrationCommand registration = registration("exact-terminal-replay");
        store.register(registration);
        TransitionCommand command = terminal(
            registration,
            "exact-terminal-replay-command",
            LineageStatus.SUCCEEDED,
            LineageOutcome.SUCCESS,
            1
        );
        List<TransitionDisposition> dispositions = concurrent(
            8,
            () -> store.transition(command).disposition()
        );

        assertEquals(1, count(dispositions, TransitionDisposition.APPLIED));
        assertEquals(7, count(dispositions, TransitionDisposition.REPLAYED));
        assertEquals(2, eventCount(
            registration.tenantEvidenceHash(),
            registration.proposalId()
        ));
        var snapshot = store.find(
            registration.tenantEvidenceHash(),
            registration.operatorEvidenceHash(),
            registration.proposalId()
        ).orElseThrow();
        assertEquals(LineageStatus.SUCCEEDED, snapshot.status());
        assertEquals(1, snapshot.commandAttempts());
        assertFalse(snapshot.automaticRetryAllowed());
    }

    @Test
    void competingTerminalPairsProduceOneWinnerAndOneImmutableTerminalEvent()
        throws Exception {
        List<TerminalPair> pairs = List.of(
            pair(
                "success-vs-failed",
                LineageStatus.SUCCEEDED,
                LineageOutcome.SUCCESS,
                1,
                LineageStatus.FAILED,
                LineageOutcome.FAILURE,
                1
            ),
            pair(
                "success-vs-unknown",
                LineageStatus.SUCCEEDED,
                LineageOutcome.SUCCESS,
                1,
                LineageStatus.UNKNOWN,
                LineageOutcome.UNKNOWN,
                1
            ),
            pair(
                "failed-vs-partial",
                LineageStatus.FAILED,
                LineageOutcome.FAILURE,
                1,
                LineageStatus.PARTIAL,
                LineageOutcome.PARTIAL,
                1
            ),
            pair(
                "cancelled-vs-success",
                LineageStatus.CANCELLED,
                LineageOutcome.NONE,
                0,
                LineageStatus.SUCCEEDED,
                LineageOutcome.SUCCESS,
                1
            )
        );

        for (TerminalPair pair : pairs) {
            RegistrationCommand registration = registration(pair.name());
            store.register(registration);
            TransitionCommand first = terminal(
                registration,
                pair.name() + "-first",
                pair.firstStatus(),
                pair.firstOutcome(),
                pair.firstAttempts()
            );
            TransitionCommand second = terminal(
                registration,
                pair.name() + "-second",
                pair.secondStatus(),
                pair.secondOutcome(),
                pair.secondAttempts()
            );
            CyclicBarrier start = new CyclicBarrier(2);

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<TransitionDisposition> left = executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return store.transition(first).disposition();
                });
                Future<TransitionDisposition> right = executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return store.transition(second).disposition();
                });
                List<TransitionDisposition> dispositions = List.of(
                    left.get(20, TimeUnit.SECONDS),
                    right.get(20, TimeUnit.SECONDS)
                );
                assertEquals(1, count(dispositions, TransitionDisposition.APPLIED));
                assertEquals(1, count(dispositions, TransitionDisposition.STATE_CONFLICT));
            }

            var snapshot = store.find(
                registration.tenantEvidenceHash(),
                registration.operatorEvidenceHash(),
                registration.proposalId()
            ).orElseThrow();
            assertTrue(snapshot.status() == pair.firstStatus()
                || snapshot.status() == pair.secondStatus());
            assertTrue(snapshot.status().terminal());
            assertFalse(snapshot.automaticRetryAllowed());
            assertEquals(
                snapshot.status() == LineageStatus.CANCELLED ? 0 : 1,
                snapshot.commandAttempts()
            );
            assertEquals(2, eventCount(
                registration.tenantEvidenceHash(),
                registration.proposalId()
            ));
        }
    }

    private static <T> List<T> concurrent(int count, Supplier<T> operation) throws Exception {
        CyclicBarrier start = new CyclicBarrier(count);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < count; index++) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return operation.get();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return List.copyOf(results);
        }
    }

    private static <T> long count(List<T> values, T expected) {
        return values.stream().filter(expected::equals).count();
    }

    private static TerminalPair pair(
        String name,
        LineageStatus firstStatus,
        LineageOutcome firstOutcome,
        int firstAttempts,
        LineageStatus secondStatus,
        LineageOutcome secondOutcome,
        int secondAttempts
    ) {
        return new TerminalPair(
            name,
            firstStatus,
            firstOutcome,
            firstAttempts,
            secondStatus,
            secondOutcome,
            secondAttempts
        );
    }

    private static TransitionCommand terminal(
        RegistrationCommand registration,
        String suffix,
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
            hash(suffix + "-result"),
            hash(suffix + "-key"),
            hash(suffix + "-payload"),
            NOW.plusSeconds(10),
            attempts
        );
    }

    private static RegistrationCommand registration(String suffix) {
        return registration(
            suffix,
            hash("tenant-" + suffix),
            uuid("proposal-" + suffix)
        );
    }

    private static RegistrationCommand registration(
        String suffix,
        String tenantHash,
        UUID proposalId
    ) {
        return RegistrationCommand.fromEvidence(
            proposalId,
            uuid("confirmation-" + suffix),
            tenantHash,
            hash("operator-" + suffix),
            hash("proposal-lineage-" + suffix),
            hash("confirmation-evidence-" + suffix),
            "TEST_ONLY_NON_EXECUTABLE_ACTION",
            hash("resource-" + suffix),
            "test-whitelist-v1",
            "test-policy-v1",
            hash("registration-key-" + suffix),
            hash("registration-payload-" + suffix),
            NOW,
            NOW.plusSeconds(120)
        );
    }

    private static RegistrationCommand registrationWithSharedKey(
        String suffix,
        String keyHash,
        String payloadHash
    ) {
        RegistrationCommand exact = registration(
            suffix,
            hash("tenant-registration-conflict-a"),
            uuid("proposal-" + suffix)
        );
        return RegistrationCommand.fromEvidence(
            exact.proposalId(),
            exact.confirmationId(),
            exact.tenantEvidenceHash(),
            exact.operatorEvidenceHash(),
            exact.proposalLineageHash(),
            exact.confirmationEvidenceHash(),
            exact.canonicalActionType(),
            exact.resourceEvidenceHash(),
            exact.whitelistVersion(),
            exact.policyVersion(),
            keyHash,
            payloadHash,
            exact.confirmedAt(),
            exact.expiresAt()
        );
    }

    private static int lineageCount(String tenantHash, UUID proposalId) {
        return jdbc.queryForObject("""
            select count(*) from ap_ai_controlled_automation_lineage
            where tenant_evidence_hash=? and proposal_id=?
            """, Integer.class, tenantHash, proposalId);
    }

    private static int eventCount(String tenantHash, UUID proposalId) {
        return jdbc.queryForObject("""
            select count(*) from ap_ai_controlled_automation_lineage_event
            where tenant_evidence_hash=? and proposal_id=?
            """, Integer.class, tenantHash, proposalId);
    }

    private static int tenantLineageCount(String tenantHash) {
        return jdbc.queryForObject("""
            select count(*) from ap_ai_controlled_automation_lineage
            where tenant_evidence_hash=?
            """, Integer.class, tenantHash);
    }

    private static int tenantEventCount(String tenantHash) {
        return jdbc.queryForObject("""
            select count(*) from ap_ai_controlled_automation_lineage_event
            where tenant_evidence_hash=?
            """, Integer.class, tenantHash);
    }

    private static UUID nextEventId() {
        return new UUID(0x9400000000000000L, EVENT_SEQUENCE.incrementAndGet());
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record TerminalPair(
        String name,
        LineageStatus firstStatus,
        LineageOutcome firstOutcome,
        int firstAttempts,
        LineageStatus secondStatus,
        LineageOutcome secondOutcome,
        int secondAttempts
    ) {
    }
}
