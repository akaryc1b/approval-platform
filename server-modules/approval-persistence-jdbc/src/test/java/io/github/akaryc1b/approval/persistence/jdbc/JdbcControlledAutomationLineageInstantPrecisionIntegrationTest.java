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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class JdbcControlledAutomationLineageInstantPrecisionIntegrationTest {

    private static final Instant CONFIRMED_FIRST = Instant.parse(
        "2026-08-06T09:00:00.123456100Z"
    );
    private static final Instant CONFIRMED_REPLAY = Instant.parse(
        "2026-08-06T09:00:00.123456900Z"
    );
    private static final Instant EXPIRES_FIRST = Instant.parse(
        "2026-08-06T09:05:00.987654100Z"
    );
    private static final Instant EXPIRES_REPLAY = Instant.parse(
        "2026-08-06T09:05:00.987654900Z"
    );
    private static final Instant OCCURRED_FIRST = Instant.parse(
        "2026-08-06T09:01:00.456789100Z"
    );
    private static final Instant OCCURRED_REPLAY = Instant.parse(
        "2026-08-06T09:01:00.456789900Z"
    );
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_lineage_instant_precision_test")
        .withUsername("approval")
        .withPassword("approval");

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
        store = new JdbcControlledAutomationLineageStore(
            dataSource,
            new DataSourceTransactionManager(dataSource),
            JdbcControlledAutomationLineageInstantPrecisionIntegrationTest::nextEventId
        );
    }

    @Test
    void registrationReplayNormalizesSubMicrosecondInstantsBeforeHashAndComparison() {
        RegistrationCommand first = registration(
            "registration-precision",
            CONFIRMED_FIRST,
            EXPIRES_FIRST
        );
        RegistrationCommand replay = registration(
            "registration-precision",
            CONFIRMED_REPLAY,
            EXPIRES_REPLAY
        );

        var registered = store.register(first);
        var replayed = store.register(replay);

        assertEquals(RegistrationDisposition.REGISTERED, registered.disposition());
        assertEquals(RegistrationDisposition.REPLAYED, replayed.disposition());
        assertEquals(
            CONFIRMED_FIRST.truncatedTo(ChronoUnit.MICROS),
            replayed.snapshot().confirmedAt()
        );
        assertEquals(
            EXPIRES_FIRST.truncatedTo(ChronoUnit.MICROS),
            replayed.snapshot().expiresAt()
        );
    }

    @Test
    void transitionReplayNormalizesSubMicrosecondInstantBeforeHashAndComparison() {
        RegistrationCommand registration = registration(
            "transition-precision",
            CONFIRMED_FIRST,
            EXPIRES_FIRST
        );
        store.register(registration);
        TransitionCommand first = transition(registration, OCCURRED_FIRST);
        TransitionCommand replay = transition(registration, OCCURRED_REPLAY);

        var applied = store.transition(first);
        var replayed = store.transition(replay);

        assertEquals(TransitionDisposition.APPLIED, applied.disposition());
        assertEquals(TransitionDisposition.REPLAYED, replayed.disposition());
        assertEquals(
            OCCURRED_FIRST.truncatedTo(ChronoUnit.MICROS),
            replayed.snapshot().orElseThrow().updatedAt()
        );
    }

    private static RegistrationCommand registration(
        String suffix,
        Instant confirmedAt,
        Instant expiresAt
    ) {
        return RegistrationCommand.fromEvidence(
            uuid("proposal-" + suffix),
            uuid("confirmation-" + suffix),
            hash("tenant-" + suffix),
            hash("operator-" + suffix),
            hash("proposal-lineage-" + suffix),
            hash("confirmation-evidence-" + suffix),
            "TEST_ONLY_NON_EXECUTABLE_ACTION",
            hash("resource-" + suffix),
            "test-whitelist-v1",
            "test-policy-v1",
            hash("registration-key-" + suffix),
            hash("registration-payload-" + suffix),
            confirmedAt,
            expiresAt
        );
    }

    private static TransitionCommand transition(
        RegistrationCommand registration,
        Instant occurredAt
    ) {
        return TransitionCommand.create(
            registration.tenantEvidenceHash(),
            registration.operatorEvidenceHash(),
            registration.proposalId(),
            1,
            LineageStatus.CONFIRMED,
            LineageStatus.SUCCEEDED,
            LineageOutcome.SUCCESS,
            hash("transition-result"),
            hash("transition-key"),
            hash("transition-payload"),
            occurredAt,
            1
        );
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
        return new UUID(0x5100000000000000L, sequence);
    }
}
