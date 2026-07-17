package io.github.akaryc1b.approval.integration.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.BusinessEvent;
import io.github.akaryc1b.approval.integration.inbox.InboxMessageKey;
import io.github.akaryc1b.approval.integration.inbox.InboxRepository.BeginStatus;
import io.github.akaryc1b.approval.integration.outbox.OutboxMessage;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository.AppendResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcInboxOutboxIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("approval_test")
        .withUsername("approval")
        .withPassword("approval");

    private static DataSource dataSource;

    private JdbcOutboxRepository outbox;
    private JdbcInboxRepository inbox;

    @BeforeAll
    static void migrateDatabase() {
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
    }

    @BeforeEach
    void resetDatabase() {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("truncate table ap_outbox, ap_inbox");
        outbox = new JdbcOutboxRepository(dataSource, new ObjectMapper());
        inbox = new JdbcInboxRepository(dataSource);
    }

    @Test
    void OutboxEnforcesIdempotencyAndWorkerLeases() {
        OutboxMessage first = message("request-1", "event-key-1", NOW);
        OutboxMessage duplicate = message("request-2", "event-key-1", NOW);

        assertEquals(AppendResult.INSERTED, outbox.append(first));
        assertEquals(AppendResult.DUPLICATE, outbox.append(duplicate));

        var claimedByA = outbox.claimDue(NOW, 10, "worker-a", Duration.ofMinutes(1));
        var claimedByB = outbox.claimDue(NOW, 10, "worker-b", Duration.ofMinutes(1));

        assertEquals(1, claimedByA.size());
        assertTrue(claimedByB.isEmpty());
        assertFalse(outbox.markDelivered(first.id(), "worker-b", null, 204, NOW));
        assertTrue(outbox.reschedule(
            first.id(),
            "worker-a",
            1,
            NOW.plusSeconds(10),
            "HTTP 503",
            NOW
        ));
        assertTrue(outbox.claimDue(NOW.plusSeconds(9), 10, "worker-b", Duration.ofMinutes(1)).isEmpty());

        var retried = outbox.claimDue(NOW.plusSeconds(10), 10, "worker-b", Duration.ofMinutes(1));
        assertEquals(1, retried.size());
        assertEquals(1, retried.getFirst().attempts());
        assertTrue(outbox.markDelivered(
            first.id(),
            "worker-b",
            "provider-1",
            204,
            NOW.plusSeconds(10)
        ));
        assertTrue(outbox.claimDue(NOW.plusSeconds(60), 10, "worker-c", Duration.ofMinutes(1)).isEmpty());
    }

    @Test
    void expiredOutboxLeaseCanBeRecoveredByAnotherWorker() {
        OutboxMessage message = message("request-1", "event-key-1", NOW);
        outbox.append(message);
        outbox.claimDue(NOW, 1, "worker-a", Duration.ofSeconds(30));

        var recovered = outbox.claimDue(NOW.plusSeconds(31), 1, "worker-b", Duration.ofMinutes(1));

        assertEquals(1, recovered.size());
        assertEquals("worker-b", recovered.getFirst().workerId());
        assertFalse(outbox.markDead(message.id(), "worker-a", 1, "stale", NOW.plusSeconds(31)));
        assertTrue(outbox.markDead(message.id(), "worker-b", 1, "invalid", NOW.plusSeconds(31)));
    }

    @Test
    void InboxRejectsConcurrentAndMismatchedReplays() {
        var key = new InboxMessageKey("tenant-a", "generic-webhook", "message-1");

        var first = inbox.begin(key, "hash-a", NOW, "worker-a", Duration.ofMinutes(1));
        var concurrent = inbox.begin(key, "hash-a", NOW.plusSeconds(1), "worker-b", Duration.ofMinutes(1));

        assertEquals(BeginStatus.ACQUIRED, first.status());
        assertEquals(BeginStatus.IN_PROGRESS, concurrent.status());
        assertTrue(inbox.complete(key, "worker-a", NOW.plusSeconds(2)));
        assertEquals(
            BeginStatus.ALREADY_COMPLETED,
            inbox.begin(key, "hash-a", NOW.plusSeconds(3), "worker-c", Duration.ofMinutes(1)).status()
        );
        assertEquals(
            BeginStatus.PAYLOAD_MISMATCH,
            inbox.begin(key, "hash-b", NOW.plusSeconds(3), "worker-c", Duration.ofMinutes(1)).status()
        );
    }

    @Test
    void failedInboxMessageCanBeReacquired() {
        var key = new InboxMessageKey("tenant-a", "generic-webhook", "message-2");
        inbox.begin(key, "hash-a", NOW, "worker-a", Duration.ofMinutes(1));
        assertTrue(inbox.fail(key, "worker-a", "temporary error", NOW.plusSeconds(1)));

        var retried = inbox.begin(
            key,
            "hash-a",
            NOW.plusSeconds(2),
            "worker-b",
            Duration.ofMinutes(1)
        );

        assertEquals(BeginStatus.ACQUIRED, retried.status());
        assertEquals(2, retried.attempts());
    }

    private static OutboxMessage message(String requestId, String idempotencyKey, Instant now) {
        var context = new ConnectorContext("generic", "tenant-a", requestId, "trace-1", now);
        var event = new BusinessEvent(
            UUID.randomUUID(),
            "PROCESS_APPROVED.v1",
            "PROCESS",
            "process-1",
            now,
            idempotencyKey,
            Map.of("amount", 1200, "currency", "CNY")
        );
        return OutboxMessage.create(context, event, now);
    }
}
