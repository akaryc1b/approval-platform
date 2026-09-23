package io.github.akaryc1b.approval.integration.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.BusinessEvent;
import io.github.akaryc1b.approval.integration.outbox.OutboxBacklogReader.Snapshot;
import io.github.akaryc1b.approval.integration.outbox.OutboxMessage;
import io.github.akaryc1b.approval.integration.outbox.SlaTimeoutNotificationConnector;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real PostgreSQL and the existing writer/lease implementation; Docker is required. */
@Testcontainers
class JdbcOutboxBacklogIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("outbox_backlog_test").withUsername("approval")
        .withPassword(UUID.randomUUID().toString());

    private static DataSource dataSource;
    private JdbcOutboxRepository outbox;
    private JdbcOutboxBacklogReader reader;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void reset() {
        new JdbcTemplate(dataSource).execute("truncate table ap_outbox");
        outbox = new JdbcOutboxRepository(dataSource, new ObjectMapper());
        reader = new JdbcOutboxBacklogReader(dataSource);
    }

    @Test
    void emptyQueueIsZeroAndFutureBackoffIsPendingButNotDue() {
        assertCounts(reader.read(), 0, 0, 0, 0, 0);
        Instant now = Instant.now();
        var message = message("tenant-a", now.minusSeconds(120), now.plusSeconds(3600));
        outbox.append(message);
        Snapshot snapshot = reader.read();
        assertCounts(snapshot, 1, 0, 0, 0, 0);
        assertNotificationCounts(snapshot, 0, 0, 0, 0, 0);
        assertEquals(Duration.between(message.createdAt(), snapshot.observedAt()).toNanos() / 1_000_000_000.0d,
            snapshot.oldestUnfinishedAgeSeconds(), 0.01d);
        assertTrue(outbox.claimDue(now, 10, "observer-is-not-a-worker", Duration.ofSeconds(30)).isEmpty());
    }

    @Test
    void observesClaimRescheduleAndDeadWithoutChangingOwnership() {
        Instant now = Instant.now();
        var message = message("tenant-a", now.minusSeconds(120), now.minusSeconds(60));
        outbox.append(message);
        assertCounts(reader.read(), 1, 1, 0, 0, 0);
        assertEquals(1, outbox.claimDue(now, 1, "worker-a", Duration.ofHours(1)).size());
        assertCounts(reader.read(), 0, 0, 1, 0, 0);
        assertTrue(outbox.claimDue(now, 10, "worker-b", Duration.ofHours(1)).isEmpty());
        assertTrue(outbox.reschedule(message.id(), "worker-a", 1, now.plusSeconds(3600), "HTTP 503", now));
        assertCounts(reader.read(), 1, 0, 0, 0, 0);
        assertEquals(1, outbox.claimDue(now.plusSeconds(3601), 1, "worker-b", Duration.ofHours(1)).size());
        assertTrue(outbox.markDead(message.id(), "worker-b", 2, "fixture failure", now));
        Snapshot dead = reader.read();
        assertCounts(dead, 0, 0, 0, 0, 1);
        assertEquals(0.0d, dead.oldestUnfinishedAgeSeconds());
    }

    @Test
    void expiredLeaseRemainsVisibleUntilRealWorkerRecoveryAndDelivery() {
        Instant now = Instant.now();
        var message = message("tenant-a", now.minusSeconds(120), now.minusSeconds(120));
        outbox.append(message);
        assertEquals(1, outbox.claimDue(now.minusSeconds(60), 1, "lost-worker", Duration.ofSeconds(1)).size());
        assertCounts(reader.read(), 0, 0, 1, 1, 0);
        assertEquals(1, outbox.claimDue(now, 1, "new-worker", Duration.ofHours(1)).size());
        assertCounts(reader.read(), 0, 0, 1, 0, 0);
        assertTrue(outbox.markDelivered(message.id(), "new-worker", "sandbox-fixture", 200, now));
        assertCounts(reader.read(), 0, 0, 0, 0, 0);
    }

    @Test
    void notificationSubsetUsesOnlyTheReservedRouteAndTracksItsDeadLetterState() {
        Instant now = Instant.now();
        outbox.append(message("generic", "tenant-a", now.minusSeconds(180), now.plusSeconds(3600)));
        outbox.append(message(SlaTimeoutNotificationConnector.ROUTE, "tenant-a",
            now.minusSeconds(90), now.plusSeconds(3600)));
        var dueNotification = message(SlaTimeoutNotificationConnector.ROUTE, "tenant-b",
            now.minusSeconds(60), now.minusSeconds(30));
        outbox.append(dueNotification);

        Snapshot pending = reader.read();
        assertCounts(pending, 3, 1, 0, 0, 0);
        assertNotificationCounts(pending, 2, 1, 0, 0, 0);
        assertTrue(pending.notificationOldestUnfinishedAgeSeconds() >= 50.0d);
        assertTrue(pending.notificationOldestUnfinishedAgeSeconds()
            <= pending.oldestUnfinishedAgeSeconds());

        assertEquals(1, outbox.claimDue(now, 1, "notification-worker", Duration.ofHours(1)).size());
        Snapshot claimed = reader.read();
        assertNotificationCounts(claimed, 1, 0, 1, 0, 0);
        assertTrue(outbox.markDead(dueNotification.id(), "notification-worker", 1,
            "bounded fixture failure", now));

        Snapshot dead = reader.read();
        assertCounts(dead, 2, 0, 0, 0, 1);
        assertNotificationCounts(dead, 1, 0, 0, 0, 1);
        assertTrue(!dead.toString().contains("tenant-"));
    }

    @Test
    void aggregatesTenantsWithoutReturningIdentitiesAndExcludesDeliveredHistory() {
        Instant now = Instant.now();
        var delivered = message("tenant-a", now.minusSeconds(600), now.minusSeconds(600));
        outbox.append(delivered);
        outbox.claimDue(now, 1, "worker", Duration.ofHours(1));
        assertTrue(outbox.markDelivered(delivered.id(), "worker", "provider-fixture", 200, now));
        outbox.append(message("tenant-a", now.minusSeconds(60), now.plusSeconds(3600)));
        outbox.append(message("tenant-b", now.minusSeconds(60), now.plusSeconds(3600)));
        Snapshot snapshot = reader.read();
        assertCounts(snapshot, 2, 0, 0, 0, 0);
        assertNotificationCounts(snapshot, 0, 0, 0, 0, 0);
        assertTrue(snapshot.oldestUnfinishedAgeSeconds() < 300);
        assertEquals(3, new JdbcTemplate(dataSource).queryForObject("select count(*) from ap_outbox", Integer.class));
        assertTrue(!snapshot.toString().contains("tenant-"));
    }

    private static OutboxMessage message(String tenant, Instant created, Instant available) {
        return message("generic", tenant, created, available);
    }

    private static OutboxMessage message(String connectorKey, String tenant, Instant created, Instant available) {
        String key = UUID.randomUUID().toString();
        var context = new ConnectorContext(connectorKey, tenant, key, key, created);
        var event = new BusinessEvent(UUID.randomUUID(), "PROCESS_APPROVED.v1", "PROCESS", key,
            created, key, Map.of("test", true));
        return new OutboxMessage(UUID.randomUUID(), context, event, available, created);
    }

    private static void assertCounts(Snapshot snapshot, long pending, long due, long inFlight, long expired, long dead) {
        assertEquals(pending, snapshot.pending());
        assertEquals(due, snapshot.due());
        assertEquals(inFlight, snapshot.inFlight());
        assertEquals(expired, snapshot.expiredLeases());
        assertEquals(dead, snapshot.dead());
    }

    private static void assertNotificationCounts(
        Snapshot snapshot,
        long pending,
        long due,
        long inFlight,
        long expired,
        long dead
    ) {
        assertEquals(pending, snapshot.notificationPending());
        assertEquals(due, snapshot.notificationDue());
        assertEquals(inFlight, snapshot.notificationInFlight());
        assertEquals(expired, snapshot.notificationExpiredLeases());
        assertEquals(dead, snapshot.notificationDead());
    }
}
