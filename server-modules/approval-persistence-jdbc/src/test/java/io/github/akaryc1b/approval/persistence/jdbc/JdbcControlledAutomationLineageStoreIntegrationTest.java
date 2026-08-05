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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcControlledAutomationLineageStoreIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-08-05T06:00:00Z");
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_controlled_automation_lineage_test")
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
            JdbcControlledAutomationLineageStoreIntegrationTest::nextEventId
        );
    }

    @Test
    void registersAndReplaysExactHashOnlyLineage() {
        RegistrationCommand command = registration("register-replay");

        var registered = store.register(command);
        var replayed = store.register(command);
        var snapshot = store.find(
            command.tenantEvidenceHash(),
            command.operatorEvidenceHash(),
            command.proposalId()
        ).orElseThrow();

        assertEquals(RegistrationDisposition.REGISTERED, registered.disposition());
        assertEquals(RegistrationDisposition.REPLAYED, replayed.disposition());
        assertEquals(1, snapshot.revision());
        assertEquals(LineageStatus.CONFIRMED, snapshot.status());
        assertEquals(LineageOutcome.NONE, snapshot.outcome());
        assertEquals(0, snapshot.commandAttempts());
        assertFalse(snapshot.automaticRetryAllowed());
        assertEquals(1, eventCount(command));
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadConflicts() {
        RegistrationCommand first = registration("registration-conflict");
        RegistrationCommand second = RegistrationCommand.fromEvidence(
            uuid("proposal-registration-conflict-second"),
            uuid("confirmation-registration-conflict-second"),
            first.tenantEvidenceHash(),
            first.operatorEvidenceHash(),
            hash("proposal-lineage-second"),
            hash("confirmation-evidence-second"),
            first.canonicalActionType(),
            hash("resource-second"),
            first.whitelistVersion(),
            first.policyVersion(),
            first.idempotencyKeyHash(),
            hash("different-registration-payload"),
            BASE,
            BASE.plusSeconds(120)
        );

        assertEquals(RegistrationDisposition.REGISTERED, store.register(first).disposition());
        assertEquals(RegistrationDisposition.CONFLICT, store.register(second).disposition());
        assertEquals(1, eventCount(first));
        assertEquals(0, lineageCount(second));
    }

    @Test
    void sameProposalIdentityIsTenantAndOperatorScoped() {
        UUID proposalId = uuid("shared-proposal-id");
        RegistrationCommand first = registration(
            "tenant-scope-a",
            "operator-scope-a",
            proposalId,
            "scope-a"
        );
        RegistrationCommand second = registration(
            "tenant-scope-b",
            "operator-scope-b",
            proposalId,
            "scope-b"
        );

        assertEquals(RegistrationDisposition.REGISTERED, store.register(first).disposition());
        assertEquals(RegistrationDisposition.REGISTERED, store.register(second).disposition());
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
    void concurrentTerminalTransitionProducesOneWinner() throws Exception {
        RegistrationCommand registration = registration("concurrent-transition");
        store.register(registration);
        TransitionCommand first = terminal(
            registration,
            "concurrent-first",
            LineageStatus.SUCCEEDED,
            LineageOutcome.SUCCESS,
            1
        );
        TransitionCommand second = terminal(
            registration,
            "concurrent-second",
            LineageStatus.FAILED,
            LineageOutcome.FAILURE,
            1
        );
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
            List<TransitionDisposition> dispositions = List.of(left.get(), right.get());

            assertEquals(1, dispositions.stream()
                .filter(disposition -> disposition == TransitionDisposition.APPLIED)
                .count());
            assertEquals(1, dispositions.stream()
                .filter(disposition -> disposition == TransitionDisposition.STATE_CONFLICT)
                .count());
        }
        assertEquals(2, eventCount(registration));
    }

    @Test
    void cancellationRecordsZeroAttempts() {
        RegistrationCommand registration = registration("cancel-zero-attempts");
        store.register(registration);
        TransitionCommand cancellation = terminal(
            registration,
            "cancel-zero-attempts-transition",
            LineageStatus.CANCELLED,
            LineageOutcome.NONE,
            0
        );

        var applied = store.transition(cancellation);
        var replayed = store.transition(cancellation);
        var snapshot = applied.snapshot().orElseThrow();

        assertEquals(TransitionDisposition.APPLIED, applied.disposition());
        assertEquals(TransitionDisposition.REPLAYED, replayed.disposition());
        assertEquals(LineageStatus.CANCELLED, snapshot.status());
        assertEquals(0, snapshot.commandAttempts());
        assertFalse(snapshot.automaticRetryAllowed());
    }

    @Test
    void unknownIsTerminalAndCannotBeRetried() {
        RegistrationCommand registration = registration("unknown-terminal");
        store.register(registration);
        TransitionCommand unknown = terminal(
            registration,
            "unknown-terminal-transition",
            LineageStatus.UNKNOWN,
            LineageOutcome.UNKNOWN,
            1
        );

        var applied = store.transition(unknown);
        var replayed = store.transition(unknown);
        TransitionCommand secondAttempt = TransitionCommand.create(
            registration.tenantEvidenceHash(),
            registration.operatorEvidenceHash(),
            registration.proposalId(),
            2,
            LineageStatus.UNKNOWN,
            LineageStatus.FAILED,
            LineageOutcome.FAILURE,
            hash("unknown-second-attempt-result"),
            hash("unknown-second-attempt-key"),
            hash("unknown-second-attempt-payload"),
            BASE.plusSeconds(20),
            1
        );
        var blocked = store.transition(secondAttempt);

        assertEquals(TransitionDisposition.APPLIED, applied.disposition());
        assertEquals(TransitionDisposition.REPLAYED, replayed.disposition());
        assertEquals(TransitionDisposition.STATE_CONFLICT, blocked.disposition());
        assertEquals(LineageStatus.UNKNOWN, blocked.snapshot().orElseThrow().status());
        assertEquals(2, eventCount(registration));
    }

    @Test
    void eventsAndLineageRejectPhysicalMutationOrDeletion() {
        RegistrationCommand registration = registration("physical-mutation");
        store.register(registration);

        assertThrows(RuntimeException.class, () -> jdbc.update("""
            update ap_ai_controlled_automation_lineage_event
            set result_evidence_hash=?
            where tenant_evidence_hash=? and proposal_id=?
            """, hash("tampered"), registration.tenantEvidenceHash(), registration.proposalId()));
        assertThrows(RuntimeException.class, () -> jdbc.update("""
            delete from ap_ai_controlled_automation_lineage
            where tenant_evidence_hash=? and proposal_id=?
            """, registration.tenantEvidenceHash(), registration.proposalId()));
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
            suffix
        );
    }

    private static RegistrationCommand registration(
        String tenant,
        String operator,
        UUID proposalId,
        String suffix
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
            hash("registration-key-" + suffix),
            hash("registration-payload-" + suffix),
            BASE,
            BASE.plusSeconds(120)
        );
    }

    private static int eventCount(RegistrationCommand command) {
        return jdbc.queryForObject("""
            select count(*) from ap_ai_controlled_automation_lineage_event
            where tenant_evidence_hash=? and proposal_id=?
            """, Integer.class, command.tenantEvidenceHash(), command.proposalId());
    }

    private static int lineageCount(RegistrationCommand command) {
        return jdbc.queryForObject("""
            select count(*) from ap_ai_controlled_automation_lineage
            where tenant_evidence_hash=? and proposal_id=?
            """, Integer.class, command.tenantEvidenceHash(), command.proposalId());
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
        long sequence = EVENT_SEQUENCE.incrementAndGet();
        return new UUID(0x5000000000000000L, sequence);
    }
}
