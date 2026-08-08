package io.github.akaryc1b.approval.integration.jdbc;

import io.github.akaryc1b.approval.integration.inbox.InboxMessageKey;
import io.github.akaryc1b.approval.integration.inbox.InboxRepository.BeginResult;
import io.github.akaryc1b.approval.integration.inbox.InboxRepository.BeginStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcInboxMySqlIntegrationTest extends MySqlInboxOutboxIntegrationSupport {

    @Test
    void serializesAdmissionReplayMismatchAndExpiredReacquisition()
        throws Exception {
        JdbcInboxRepository inbox = inbox();
        InboxMessageKey firstKey = MySqlInboxOutboxFixtures.key("message-1");
        List<BeginResult> admission = MySqlInboxOutboxFixtures.flatten(concurrently(
            () -> List.of(inbox().begin(
                firstKey,
                "hash-a",
                NOW,
                "worker-a",
                Duration.ofMinutes(1)
            )),
            () -> List.of(inbox().begin(
                firstKey,
                "hash-a",
                NOW,
                "worker-b",
                Duration.ofMinutes(1)
            ))
        ));
        MySqlInboxOutboxFixtures.assertCounts(admission, 1, 1, 1);
        String owner = jdbc.queryForObject(
            "select locked_by from ap_inbox where tenant_id = ? and consumer_key = ? "
                + "and message_id = ?",
            String.class,
            firstKey.tenantId(),
            firstKey.consumerKey(),
            firstKey.messageId()
        );
        String other = owner.equals("worker-a") ? "worker-b" : "worker-a";
        assertFalse(inbox.complete(firstKey, other, NOW));
        assertTrue(inbox.complete(firstKey, owner, NOW));
        assertEquals(
            BeginStatus.ALREADY_COMPLETED,
            inbox.begin(firstKey, "hash-a", NOW, "worker-c", Duration.ofMinutes(1)).status()
        );
        assertEquals(
            BeginStatus.PAYLOAD_MISMATCH,
            inbox.begin(firstKey, "hash-b", NOW, "worker-c", Duration.ofMinutes(1)).status()
        );

        InboxMessageKey expired = MySqlInboxOutboxFixtures.key("message-expired");
        inbox.begin(expired, "hash-a", NOW, "worker-seed", Duration.ofSeconds(1));
        assertFalse(inbox.complete(expired, "worker-seed", NOW.plusSeconds(1)));
        assertFalse(inbox.fail(expired, "worker-seed", "expired", NOW.plusSeconds(1)));
        List<BeginResult> reacquisition = MySqlInboxOutboxFixtures.flatten(concurrently(
            () -> List.of(inbox().begin(
                expired,
                "hash-a",
                NOW.plusSeconds(1),
                "worker-a",
                Duration.ofMinutes(1)
            )),
            () -> List.of(inbox().begin(
                expired,
                "hash-a",
                NOW.plusSeconds(1),
                "worker-b",
                Duration.ofMinutes(1)
            ))
        ));
        MySqlInboxOutboxFixtures.assertCounts(reacquisition, 1, 1, 2);
    }

    @Test
    void caseSensitiveScopesAndInvalidLeaseRemainFailClosed() {
        JdbcInboxRepository inbox = inbox();
        for (InboxMessageKey key : List.of(
            new InboxMessageKey("tenant-a", "Webhook", "Message-A"),
            new InboxMessageKey("tenant-a", "webhook", "message-a"),
            new InboxMessageKey("Tenant-A", "Webhook", "Message-A")
        )) {
            assertEquals(
                BeginStatus.ACQUIRED,
                inbox.begin(
                    key,
                    "hash-a",
                    NOW,
                    "worker-" + key.messageId(),
                    Duration.ofMinutes(1)
                ).status()
            );
        }
        String insert = JdbcInboxDialect.MYSQL.admissionSql().toLowerCase();
        assertFalse(insert.contains("insert ignore"));
        assertFalse(insert.contains("on duplicate key update"));
        assertFalse(insert.contains("on conflict"));
        assertFalse(JdbcInboxDialect.MYSQL.reacquisitionSql().contains("returning"));
        assertTrue(JdbcInboxDialect.POSTGRESQL.reacquisitionSql().contains("returning"));
        assertThrows(
            IllegalArgumentException.class,
            () -> inbox.begin(
                MySqlInboxOutboxFixtures.key("invalid"),
                "hash-a",
                NOW,
                "worker-a",
                Duration.ZERO
            )
        );
        assertEquals(3, MySqlInboxOutboxFixtures.count(jdbc, "ap_inbox"));
    }
}
