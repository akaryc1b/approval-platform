package io.github.akaryc1b.approval.integration.jdbc;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.BusinessEvent;
import io.github.akaryc1b.approval.integration.inbox.InboxMessageKey;
import io.github.akaryc1b.approval.integration.inbox.InboxRepository.BeginResult;
import io.github.akaryc1b.approval.integration.inbox.InboxRepository.BeginStatus;
import io.github.akaryc1b.approval.integration.outbox.OutboxMessage;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository.ClaimedMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MySqlInboxOutboxFixtures {

    private MySqlInboxOutboxFixtures() {
    }

    static void assertCounts(
        List<BeginResult> results,
        long acquired,
        long inProgress,
        int attempts
    ) {
        assertEquals(
            acquired,
            results.stream().filter(result -> result.status() == BeginStatus.ACQUIRED).count()
        );
        assertEquals(
            inProgress,
            results.stream().filter(result -> result.status() == BeginStatus.IN_PROGRESS).count()
        );
        assertTrue(results.stream().allMatch(result -> result.attempts() == attempts));
    }

    static List<BeginResult> flatten(List<List<BeginResult>> values) {
        return values.stream().flatMap(List::stream).toList();
    }

    static Set<UUID> ids(List<ClaimedMessage> messages) {
        return messages.stream()
            .map(message -> message.message().id())
            .collect(java.util.stream.Collectors.toSet());
    }

    static InboxMessageKey key(String messageId) {
        return new InboxMessageKey("tenant-a", "generic-webhook", messageId);
    }

    static OutboxMessage message(
        Instant now,
        int seed,
        String idempotencyKey,
        String tenantId
    ) {
        return message(
            now,
            seed,
            idempotencyKey,
            tenantId,
            Map.of("amount", 1200 + seed)
        );
    }

    static OutboxMessage richMessage(
        Instant now,
        int seed,
        String idempotencyKey,
        String tenantId
    ) {
        return message(
            now,
            seed,
            idempotencyKey,
            tenantId,
            Map.of(
                "message", "审批✅ / Straße / İstanbul",
                "amount", new BigDecimal("123456789012.123456"),
                "sequence", new BigInteger("9223372036854775807")
            )
        );
    }

    static int count(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return count == null ? 0 : count;
    }

    static int statusCount(JdbcTemplate jdbc, String status) {
        Integer count = jdbc.queryForObject(
            "select count(*) from ap_outbox where status = ?",
            Integer.class,
            status
        );
        return count == null ? 0 : count;
    }

    static String configuredJdbcUrl(MySQLContainer container) {
        String base = container.getJdbcUrl();
        String separator = base.contains("?") ? "&" : "?";
        return base + separator
            + "characterEncoding=UTF-8"
            + "&connectionCollation=utf8mb4_0900_as_cs"
            + "&connectionTimeZone=UTC"
            + "&forceConnectionTimeZoneToSession=true"
            + "&preserveInstants=true";
    }

    private static OutboxMessage message(
        Instant now,
        int seed,
        String idempotencyKey,
        String tenantId,
        Map<String, Object> payload
    ) {
        ConnectorContext context = new ConnectorContext(
            "generic",
            tenantId,
            "request-" + seed,
            "trace-" + seed,
            now
        );
        BusinessEvent event = new BusinessEvent(
            new UUID(1L, seed),
            "PROCESS_APPROVED.v1",
            "PROCESS",
            "process-" + seed,
            now,
            idempotencyKey,
            payload
        );
        return new OutboxMessage(new UUID(0L, seed), context, event, now, now);
    }
}
