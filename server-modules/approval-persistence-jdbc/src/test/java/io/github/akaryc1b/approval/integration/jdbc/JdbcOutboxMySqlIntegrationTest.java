package io.github.akaryc1b.approval.integration.jdbc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.integration.outbox.OutboxMessage;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository.AppendResult;
import io.github.akaryc1b.approval.integration.outbox.OutboxRepository.ClaimedMessage;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcOutboxMySqlIntegrationTest extends MySqlInboxOutboxIntegrationSupport {

    @Test
    void roundTripsCanonicalValuesAndClassifiesOnlyTheBusinessDuplicate() {
        ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        JdbcOutboxRepository outbox = new JdbcOutboxRepository(dataSource, mapper);
        OutboxMessage first = MySqlInboxOutboxFixtures.richMessage(
            NOW,
            1,
            "Case-Key",
            "tenant-a"
        );
        OutboxMessage duplicate = MySqlInboxOutboxFixtures.richMessage(
            NOW,
            2,
            "Case-Key",
            "tenant-a"
        );

        TransactionTemplate transaction = new TransactionTemplate(
            new JdbcTransactionManager(dataSource)
        );
        transaction.executeWithoutResult(status -> {
            assertEquals(AppendResult.INSERTED, outbox.append(first));
            assertEquals(AppendResult.DUPLICATE, outbox.append(duplicate));
            assertEquals(
                AppendResult.INSERTED,
                outbox.append(MySqlInboxOutboxFixtures.richMessage(
                    NOW,
                    3,
                    "independent-key",
                    "tenant-a"
                ))
            );
        });

        List<ClaimedMessage> claimed = outbox.claimDue(
            NOW,
            1,
            "worker-a",
            Duration.ofMinutes(1)
        );
        assertEquals(1, claimed.size());
        ClaimedMessage exact = claimed.getFirst();
        assertEquals(first.id(), exact.message().id());
        assertEquals(first.event().eventId(), exact.message().event().eventId());
        assertEquals(first.event().payload(), exact.message().event().payload());
        assertEquals(NOW, exact.message().event().occurredAt());
        assertEquals(NOW, exact.message().createdAt());
        assertEquals(NOW.plus(Duration.ofMinutes(1)), exact.lockedUntil());
        String stored = jdbc.queryForObject(
            "select json_unquote(json_extract(payload_json, '$.payload')) "
                + "from ap_outbox where id = ?",
            String.class,
            first.id().toString()
        );
        assertTrue(stored.contains("123456789012.123456"));
        assertTrue(stored.contains("9223372036854775807"));
        assertTrue(stored.contains("审批✅ / Straße / İstanbul"));
        assertFalse(outbox.markDelivered(first.id(), "worker-b", null, 204, NOW));
        assertTrue(outbox.markDelivered(first.id(), "worker-a", null, 204, NOW));

        OutboxMessage other = MySqlInboxOutboxFixtures.richMessage(
            NOW,
            4,
            "collision-key",
            "tenant-a"
        );
        OutboxMessage primaryKeyCollision = new OutboxMessage(
            first.id(),
            other.context(),
            other.event(),
            other.availableAt(),
            other.createdAt()
        );
        assertThrows(DuplicateKeyException.class, () -> outbox.append(primaryKeyCollision));
        assertEquals(3, MySqlInboxOutboxFixtures.count(jdbc, "ap_outbox"));
    }

    @Test
    void claimsDisjointBatchesAndFencesAnExpiredOwner() throws Exception {
        JdbcOutboxRepository outbox = outbox();
        OutboxMessage recoverable = MySqlInboxOutboxFixtures.message(
            NOW,
            50,
            "recover-key",
            "tenant-a"
        );
        outbox.append(recoverable);
        outbox.claimDue(NOW, 1, "worker-old", Duration.ofSeconds(1));
        assertFalse(outbox.markDelivered(
            recoverable.id(),
            "worker-old",
            "expired-provider",
            204,
            NOW.plusSeconds(1)
        ));
        List<ClaimedMessage> recovered = outbox.claimDue(
            NOW.plusSeconds(1),
            1,
            "worker-new",
            Duration.ofMinutes(1)
        );
        assertEquals(1, recovered.size());
        assertFalse(outbox.markDead(
            recoverable.id(),
            "worker-old",
            1,
            "stale",
            NOW.plusSeconds(1)
        ));
        assertTrue(outbox.markDead(
            recoverable.id(),
            "worker-new",
            1,
            "terminal",
            NOW.plusSeconds(1)
        ));

        for (int index = 1; index <= 12; index++) {
            assertEquals(
                AppendResult.INSERTED,
                outbox.append(MySqlInboxOutboxFixtures.message(
                    NOW,
                    100 + index,
                    "batch-" + index,
                    "tenant-a"
                ))
            );
        }
        List<List<ClaimedMessage>> batches = concurrently(
            () -> outbox().claimDue(NOW, 6, "worker-a", Duration.ofMinutes(1)),
            () -> outbox().claimDue(NOW, 6, "worker-b", Duration.ofMinutes(1))
        );
        assertEquals(6, batches.get(0).size());
        assertEquals(6, batches.get(1).size());
        Set<UUID> first = MySqlInboxOutboxFixtures.ids(batches.get(0));
        Set<UUID> second = MySqlInboxOutboxFixtures.ids(batches.get(1));
        Set<UUID> intersection = new HashSet<>(first);
        intersection.retainAll(second);
        assertTrue(intersection.isEmpty());
        first.addAll(second);
        assertEquals(12, first.size());
        assertEquals(
            12,
            MySqlInboxOutboxFixtures.statusCount(jdbc, "IN_FLIGHT")
        );
    }

    @Test
    void dialectAndScopeBoundariesRemainFailClosed() {
        JdbcOutboxRepository outbox = outbox();
        for (OutboxMessage message : List.of(
            MySqlInboxOutboxFixtures.message(NOW, 1, "Case-Key", "tenant-a"),
            MySqlInboxOutboxFixtures.message(NOW, 2, "case-key", "tenant-a"),
            MySqlInboxOutboxFixtures.message(NOW, 3, "Case-Key", "Tenant-A")
        )) {
            assertEquals(AppendResult.INSERTED, outbox.append(message));
        }
        String insert = JdbcOutboxDialect.MYSQL.appendSql().toLowerCase();
        String claim = JdbcOutboxDialect.MYSQL.mySqlSelectDueSql().toLowerCase();
        assertTrue(insert.contains("json_object("));
        assertTrue(insert.contains("'encoding', 'canonical_json_text_v1'"));
        assertFalse(insert.contains("cast(:payloadjson as json)"));
        assertTrue(JdbcOutboxDialect.MYSQL.mySqlReadClaimsSql().contains(
            "json_unquote(json_extract(payload_json, '$.payload'))"
        ));
        assertTrue(claim.contains("for update skip locked"));
        assertFalse(claim.contains("returning"));
        assertTrue(JdbcOutboxDialect.POSTGRESQL.appendSql().contains(
            "on conflict (tenant_id, connector_key, idempotency_key) do nothing"
        ));
        assertTrue(JdbcOutboxDialect.POSTGRESQL.postgreSqlClaimSql().contains(
            "returning target.*"
        ));
        assertThrows(
            IllegalArgumentException.class,
            () -> outbox.claimDue(NOW, 0, "worker-a", Duration.ofMinutes(1))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> outbox.claimDue(NOW, 1, "worker-a", Duration.ZERO)
        );
        assertEquals(3, MySqlInboxOutboxFixtures.count(jdbc, "ap_outbox"));
    }
}
