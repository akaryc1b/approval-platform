package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.LineageOutcome;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.LineageStatus;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.RegistrationCommand;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.TransitionCommand;
import io.github.akaryc1b.approval.ai.core.ControlledAutomationLineageStore.TransitionDisposition;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcControlledAutomationLineageStore
    .LineagePersistenceException;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class JdbcControlledAutomationLineageFaultIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-06T04:00:00Z");
    private static final String EVENT_FAILURE_TENANT = hash("p7-event-failure-tenant");
    private static final String STATE_FAILURE_TENANT = hash("p7-state-failure-tenant");
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_controlled_automation_lineage_fault_test")
        .withUsername("approval")
        .withPassword("approval");

    private static JdbcTemplate jdbc;
    private static JdbcControlledAutomationLineageStore store;

    @BeforeAll
    static void migrateAndInstallFaultTriggers() {
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
            JdbcControlledAutomationLineageFaultIntegrationTest::nextEventId
        );
        installEventFailureTrigger();
        installStateFailureTrigger();
    }

    @Test
    void registrationEventFailureRollsBackLineageAndEventTogether() {
        RegistrationCommand command = registration(
            EVENT_FAILURE_TENANT,
            "registration-event-failure"
        );

        assertThrows(LineagePersistenceException.class, () -> store.register(command));

        assertEquals(0, lineageCount(command));
        assertEquals(0, eventCount(command));
    }

    @Test
    void terminalStateUpdateFailureRollsBackInsertedEvent() {
        RegistrationCommand command = registration(
            STATE_FAILURE_TENANT,
            "terminal-state-failure"
        );
        store.register(command);
        TransitionCommand transition = terminal(
            command,
            "terminal-state-failure-transition",
            LineageStatus.SUCCEEDED,
            LineageOutcome.SUCCESS,
            1
        );

        assertThrows(LineagePersistenceException.class, () -> store.transition(transition));

        var snapshot = store.find(
            command.tenantEvidenceHash(),
            command.operatorEvidenceHash(),
            command.proposalId()
        ).orElseThrow();
        assertEquals(1, snapshot.revision());
        assertEquals(LineageStatus.CONFIRMED, snapshot.status());
        assertEquals(LineageOutcome.NONE, snapshot.outcome());
        assertEquals(0, snapshot.commandAttempts());
        assertEquals(1, eventCount(command));
    }

    @Test
    void partialOutcomePersistsAsPartialAndCannotBecomeSuccess() {
        RegistrationCommand command = registration(
            hash("p7-partial-tenant"),
            "partial-outcome"
        );
        store.register(command);
        TransitionCommand partial = terminal(
            command,
            "partial-outcome-transition",
            LineageStatus.PARTIAL,
            LineageOutcome.PARTIAL,
            1
        );

        var result = store.transition(partial);
        var snapshot = result.snapshot().orElseThrow();

        assertEquals(TransitionDisposition.APPLIED, result.disposition());
        assertEquals(LineageStatus.PARTIAL, snapshot.status());
        assertEquals(LineageOutcome.PARTIAL, snapshot.outcome());
        assertEquals(1, snapshot.commandAttempts());
        assertFalse(snapshot.automaticRetryAllowed());
        assertEquals(2, eventCount(command));
    }

    private static void installEventFailureTrigger() {
        jdbc.execute("""
            create function ap_p7_fail_lineage_event()
            returns trigger language plpgsql as $$
            begin
              if new.tenant_evidence_hash='%s' then
                raise exception using errcode='55000',
                  message='P7 injected lineage event failure';
              end if;
              return new;
            end;
            $$
            """.formatted(EVENT_FAILURE_TENANT));
        jdbc.execute("""
            create trigger trg_p7_fail_lineage_event
            before insert on ap_ai_controlled_automation_lineage_event
            for each row execute function ap_p7_fail_lineage_event()
            """);
    }

    private static void installStateFailureTrigger() {
        jdbc.execute("""
            create function ap_p7_fail_lineage_state()
            returns trigger language plpgsql as $$
            begin
              if tg_op='UPDATE' and new.tenant_evidence_hash='%s' then
                raise exception using errcode='55000',
                  message='P7 injected lineage state failure';
              end if;
              return new;
            end;
            $$
            """.formatted(STATE_FAILURE_TENANT));
        jdbc.execute("""
            create trigger trg_p7_fail_lineage_state
            before update on ap_ai_controlled_automation_lineage
            for each row execute function ap_p7_fail_lineage_state()
            """);
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

    private static RegistrationCommand registration(String tenantHash, String suffix) {
        return RegistrationCommand.fromEvidence(
            uuid("proposal-" + suffix),
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

    private static int lineageCount(RegistrationCommand command) {
        return jdbc.queryForObject("""
            select count(*) from ap_ai_controlled_automation_lineage
            where tenant_evidence_hash=? and proposal_id=?
            """, Integer.class, command.tenantEvidenceHash(), command.proposalId());
    }

    private static int eventCount(RegistrationCommand command) {
        return jdbc.queryForObject("""
            select count(*) from ap_ai_controlled_automation_lineage_event
            where tenant_evidence_hash=? and proposal_id=?
            """, Integer.class, command.tenantEvidenceHash(), command.proposalId());
    }

    private static UUID nextEventId() {
        return new UUID(0x5f00000000000000L, EVENT_SEQUENCE.incrementAndGet());
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
}
