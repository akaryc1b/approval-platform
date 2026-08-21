package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class AuditHashCanonicalizerPostgresIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_audit_hash_contract")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
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
    }

    @Test
    void javaPayloadHashMatchesThePermanentPostgresqlV21Function() {
        for (AuditEvent event : events()) {
            assertEquals(postgresqlPayloadHash(event), AuditHashCanonicalizer.payloadHash(event));
        }
    }

    @Test
    void javaChainHashMatchesThePermanentPostgresqlV21Function() {
        String previousHash = "1".repeat(64);
        String payloadHash = "a".repeat(64);
        String postgresql = jdbc.queryForObject(
            "select ap_audit_chain_hash(?, ?)",
            String.class,
            previousHash,
            payloadHash
        );

        assertEquals(postgresql, AuditHashCanonicalizer.chainHash(previousHash, payloadHash));
    }

    @Test
    void rejectsTextThatPostgresqlCannotRepresentBeforeHashing() {
        AuditEvent event = event(
            "00000000-0000-0000-0000-000000000004",
            "trace-invalid",
            Instant.parse("2026-08-07T11:22:33.123456Z"),
            Map.of("invalid", "contains" + (char) 0 + "nul")
        );

        assertThrows(IllegalArgumentException.class, () -> {
            AuditHashCanonicalizer.payloadHash(event);
        });
    }

    @Test
    void factoryRetainsTheAcceptedPostgresqlAuditImplementation() {
        assertInstanceOf(
            JdbcAuditEventSink.class,
            JdbcAuditEventStoreFactory.create(
                dataSource,
                OBJECT_MAPPER,
                new JdbcTransactionManager(dataSource)
            )
        );
    }

    private static String postgresqlPayloadHash(AuditEvent event) {
        return jdbc.queryForObject(
            """
            select ap_audit_payload_hash(
                cast(? as uuid), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb)
            )
            """,
            String.class,
            event.eventId().toString(),
            event.tenantId(),
            event.operatorId(),
            event.action(),
            event.aggregateType(),
            event.aggregateId(),
            event.schemaName(),
            event.schemaVersion(),
            event.requestId(),
            event.traceId(),
            event.occurredAt().atOffset(ZoneOffset.UTC),
            encode(event.attributes())
        );
    }

    private static List<AuditEvent> events() {
        return List.of(
            event(
                "00000000-0000-0000-0000-000000000001",
                null,
                Instant.parse("2026-08-07T11:22:33.123456Z"),
                Map.of()
            ),
            event(
                "00000000-0000-0000-0000-000000000002",
                "trace-quoted-\\-\"",
                Instant.parse("2026-08-07T11:22:33.123456499Z"),
                Map.of(
                    "b", "line1\nline2",
                    "aa", "审批✅",
                    "emoji", "😀",
                    "control", "tab\tcarriage\r"
                )
            ),
            event(
                "00000000-0000-0000-0000-000000000003",
                "trace-carry",
                Instant.parse("2026-08-07T11:22:33.999999500Z"),
                Map.of(
                    "é", "Straße / İstanbul",
                    "审批", "精确证据"
                )
            )
        );
    }

    private static AuditEvent event(
        String eventId,
        String traceId,
        Instant occurredAt,
        Map<String, String> attributes
    ) {
        return new AuditEvent(
            UUID.fromString(eventId),
            "tenant-a",
            "operator-a",
            "TASK_APPROVED",
            "APPROVAL_TASK",
            "task-a",
            "request-a",
            traceId,
            occurredAt,
            attributes
        );
    }

    private static String encode(Map<String, String> attributes) {
        try {
            return OBJECT_MAPPER.writeValueAsString(attributes);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("unable to encode test attributes", exception);
        }
    }
}
