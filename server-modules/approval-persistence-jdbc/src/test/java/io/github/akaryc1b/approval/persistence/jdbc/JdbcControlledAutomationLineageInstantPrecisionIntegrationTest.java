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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class JdbcControlledAutomationLineageInstantPrecisionIntegrationTest {

    private static final Instant CONFIRMED_DOWN_FIRST = Instant.parse(
        "2026-08-06T09:00:00.123456100Z"
    );
    private static final Instant CONFIRMED_DOWN_REPLAY = Instant.parse(
        "2026-08-06T09:00:00.123456499Z"
    );
    private static final Instant CONFIRMED_UP_FIRST = Instant.parse(
        "2026-08-06T09:00:00.123456500Z"
    );
    private static final Instant CONFIRMED_UP_REPLAY = Instant.parse(
        "2026-08-06T09:00:00.123456900Z"
    );
    private static final Instant EXPIRES_DOWN_FIRST = Instant.parse(
        "2026-08-06T09:05:00.987654100Z"
    );
    private static final Instant EXPIRES_DOWN_REPLAY = Instant.parse(
        "2026-08-06T09:05:00.987654499Z"
    );
    private static final Instant EXPIRES_UP_FIRST = Instant.parse(
        "2026-08-06T09:05:00.987654500Z"
    );
    private static final Instant EXPIRES_UP_REPLAY = Instant.parse(
        "2026-08-06T09:05:00.987654900Z"
    );
    private static final Instant OCCURRED_UP_FIRST = Instant.parse(
        "2026-08-06T09:01:00.456789500Z"
    );
    private static final Instant OCCURRED_UP_REPLAY = Instant.parse(
        "2026-08-06T09:01:00.456789900Z"
    );
    private static final Instant OCCURRED_BELOW_BOUNDARY = Instant.parse(
        "2026-08-06T09:01:00.456789499Z"
    );
    private static final Instant OCCURRED_AT_BOUNDARY = Instant.parse(
        "2026-08-06T09:01:00.456789500Z"
    );
    private static final Instant EXPECTED_CONFIRMED_DOWN = Instant.parse(
        "2026-08-06T09:00:00.123456Z"
    );
    private static final Instant EXPECTED_CONFIRMED_UP = Instant.parse(
        "2026-08-06T09:00:00.123457Z"
    );
    private static final Instant EXPECTED_EXPIRES_DOWN = Instant.parse(
        "2026-08-06T09:05:00.987654Z"
    );
    private static final Instant EXPECTED_EXPIRES_UP = Instant.parse(
        "2026-08-06T09:05:00.987655Z"
    );
    private static final Instant EXPECTED_OCCURRED_UP = Instant.parse(
        "2026-08-06T09:01:00.456790Z"
    );
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_lineage_instant_precision_test")
        .withUsername("approval")
        .withPassword("approval");

    private static JdbcControlledAutomationLineageStore store;
    private static JdbcTemplate jdbc;

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
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void postgresqlRoundsToNearestMicrosecondAndCarriesIntoNextSecond() {
        assertEquals(
            EXPECTED_CONFIRMED_DOWN,
            postgresRoundTrip(Instant.parse("2026-08-06T09:00:00.123456499Z"))
        );
        assertEquals(
            EXPECTED_CONFIRMED_UP,
            postgresRoundTrip(Instant.parse("2026-08-06T09:00:00.123456500Z"))
        );
        assertEquals(
            Instant.parse("2026-08-06T09:01:00Z"),
            postgresRoundTrip(Instant.parse("2026-08-06T09:00:59.999999500Z"))
        );
    }

    @Test
    void registrationReplayRoundsBelowHalfMicrosecondDown() {
        RegistrationCommand first = registration(
            "registration-round-down",
            CONFIRMED_DOWN_FIRST,
            EXPIRES_DOWN_FIRST
        );
        RegistrationCommand replay = registration(
            "registration-round-down",
            CONFIRMED_DOWN_REPLAY,
            EXPIRES_DOWN_REPLAY
        );

        var registered = store.register(first);
        var replayed = store.register(replay);

        assertEquals(RegistrationDisposition.REGISTERED, registered.disposition());
        assertEquals(RegistrationDisposition.REPLAYED, replayed.disposition());
        assertEquals(EXPECTED_CONFIRMED_DOWN, replayed.snapshot().confirmedAt());
        assertEquals(EXPECTED_EXPIRES_DOWN, replayed.snapshot().expiresAt());
    }

    @Test
    void registrationReplayRoundsHalfMicrosecondUp() {
        RegistrationCommand first = registration(
            "registration-round-up",
            CONFIRMED_UP_FIRST,
            EXPIRES_UP_FIRST
        );
        RegistrationCommand replay = registration(
            "registration-round-up",
            CONFIRMED_UP_REPLAY,
            EXPIRES_UP_REPLAY
        );

        var registered = store.register(first);
        var replayed = store.register(replay);

        assertEquals(RegistrationDisposition.REGISTERED, registered.disposition());
        assertEquals(RegistrationDisposition.REPLAYED, replayed.disposition());
        assertEquals(EXPECTED_CONFIRMED_UP, replayed.snapshot().confirmedAt());
        assertEquals(EXPECTED_EXPIRES_UP, replayed.snapshot().expiresAt());
    }

    @Test
    void registrationDistinctPostgresMicrosecondsConflictAcrossHalfBoundary() {
        RegistrationCommand first = registration(
            "registration-rounding-conflict",
            CONFIRMED_DOWN_REPLAY,
            EXPIRES_DOWN_REPLAY
        );
        RegistrationCommand conflict = registration(
            "registration-rounding-conflict",
            CONFIRMED_UP_FIRST,
            EXPIRES_UP_FIRST
        );

        var registered = store.register(first);
        var conflicted = store.register(conflict);

        assertEquals(RegistrationDisposition.REGISTERED, registered.disposition());
        assertEquals(RegistrationDisposition.CONFLICT, conflicted.disposition());
        assertEquals(EXPECTED_CONFIRMED_DOWN, conflicted.snapshot().confirmedAt());
        assertEquals(EXPECTED_EXPIRES_DOWN, conflicted.snapshot().expiresAt());
    }

    @Test
    void registrationRoundingCarriesIntoNextSecond() {
        Instant confirmedFirst = Instant.parse("2026-08-06T09:00:59.999999500Z");
        Instant confirmedReplay = Instant.parse("2026-08-06T09:00:59.999999900Z");
        Instant expiresFirst = Instant.parse("2026-08-06T09:05:59.999999500Z");
        Instant expiresReplay = Instant.parse("2026-08-06T09:05:59.999999900Z");
        RegistrationCommand first = registration(
            "registration-rounding-carry",
            confirmedFirst,
            expiresFirst
        );
        RegistrationCommand replay = registration(
            "registration-rounding-carry",
            confirmedReplay,
            expiresReplay
        );

        var registered = store.register(first);
        var replayed = store.register(replay);

        assertEquals(RegistrationDisposition.REGISTERED, registered.disposition());
        assertEquals(RegistrationDisposition.REPLAYED, replayed.disposition());
        assertEquals(
            Instant.parse("2026-08-06T09:01:00Z"),
            replayed.snapshot().confirmedAt()
        );
        assertEquals(
            Instant.parse("2026-08-06T09:06:00Z"),
            replayed.snapshot().expiresAt()
        );
    }

    @Test
    void transitionReplayRoundsHalfMicrosecondUp() {
        RegistrationCommand registration = exactRegistration("transition-round-up");
        store.register(registration);
        TransitionCommand first = transition(registration, OCCURRED_UP_FIRST);
        TransitionCommand replay = transition(registration, OCCURRED_UP_REPLAY);

        var applied = store.transition(first);
        var replayed = store.transition(replay);

        assertEquals(TransitionDisposition.APPLIED, applied.disposition());
        assertEquals(TransitionDisposition.REPLAYED, replayed.disposition());
        assertEquals(
            EXPECTED_OCCURRED_UP,
            replayed.snapshot().orElseThrow().updatedAt()
        );
    }

    @Test
    void transitionDistinctPostgresMicrosecondsConflictAcrossHalfBoundary() {
        RegistrationCommand registration = exactRegistration("transition-rounding-conflict");
        store.register(registration);
        TransitionCommand first = transition(registration, OCCURRED_BELOW_BOUNDARY);
        TransitionCommand conflict = transition(registration, OCCURRED_AT_BOUNDARY);

        var applied = store.transition(first);
        var conflicted = store.transition(conflict);

        assertEquals(TransitionDisposition.APPLIED, applied.disposition());
        assertEquals(
            TransitionDisposition.IDEMPOTENCY_CONFLICT,
            conflicted.disposition()
        );
    }

    private static RegistrationCommand exactRegistration(String suffix) {
        return registration(
            suffix,
            Instant.parse("2026-08-06T09:00:00.123456Z"),
            Instant.parse("2026-08-06T09:05:00.987654Z")
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

    private static Instant postgresRoundTrip(Instant value) {
        return jdbc.queryForObject(
            "select cast(? as timestamptz)",
            (resultSet, row) -> resultSet.getObject(1, OffsetDateTime.class).toInstant(),
            Timestamp.from(value)
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
