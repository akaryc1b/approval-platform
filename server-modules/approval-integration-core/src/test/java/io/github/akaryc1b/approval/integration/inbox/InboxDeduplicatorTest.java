package io.github.akaryc1b.approval.integration.inbox;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InboxDeduplicatorTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    @Test
    void executesOnceAndReturnsDuplicateAfterCompletion() {
        var repository = new FakeInboxRepository();
        var deduplicator = new InboxDeduplicator(
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(1)
        );
        var key = new InboxMessageKey("tenant-a", "generic-webhook", "message-1");
        var calls = new AtomicInteger();

        var first = deduplicator.execute(key, "hash-a", "worker-a", calls::incrementAndGet);
        var duplicate = deduplicator.execute(key, "hash-a", "worker-b", calls::incrementAndGet);

        assertEquals(InboxDeduplicator.ExecutionStatus.EXECUTED, first.status());
        assertEquals(1, first.value());
        assertEquals(InboxDeduplicator.ExecutionStatus.DUPLICATE, duplicate.status());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsSameMessageIdWithDifferentPayload() {
        var repository = new FakeInboxRepository();
        var deduplicator = new InboxDeduplicator(
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(1)
        );
        var key = new InboxMessageKey("tenant-a", "generic-webhook", "message-1");
        deduplicator.execute(key, "hash-a", "worker-a", () -> "ok");

        assertThrows(
            IllegalStateException.class,
            () -> deduplicator.execute(key, "hash-b", "worker-b", () -> "unexpected")
        );
    }

    @Test
    void failedMessagesCanBeRetried() {
        var repository = new FakeInboxRepository();
        var deduplicator = new InboxDeduplicator(
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(1)
        );
        var key = new InboxMessageKey("tenant-a", "generic-webhook", "message-1");

        assertThrows(
            IllegalArgumentException.class,
            () -> deduplicator.execute(key, "hash-a", "worker-a", () -> {
                throw new IllegalArgumentException("invalid business state");
            })
        );
        var retried = deduplicator.execute(key, "hash-a", "worker-b", () -> "recovered");

        assertEquals(InboxDeduplicator.ExecutionStatus.EXECUTED, retried.status());
        assertEquals("recovered", retried.value());
        assertEquals(2, repository.attempts);
    }

    private static final class FakeInboxRepository implements InboxRepository {

        private String payloadHash;
        private String status;
        private String workerId;
        private int attempts;

        @Override
        public BeginResult begin(
            InboxMessageKey key,
            String newPayloadHash,
            Instant now,
            String newWorkerId,
            Duration leaseDuration
        ) {
            if (payloadHash != null && !payloadHash.equals(newPayloadHash)) {
                return new BeginResult(BeginStatus.PAYLOAD_MISMATCH, attempts);
            }
            if (status != null && status.equals("COMPLETED")) {
                return new BeginResult(BeginStatus.ALREADY_COMPLETED, attempts);
            }
            if (status != null && status.equals("PROCESSING")) {
                return new BeginResult(BeginStatus.IN_PROGRESS, attempts);
            }
            payloadHash = newPayloadHash;
            status = "PROCESSING";
            workerId = newWorkerId;
            attempts++;
            return new BeginResult(BeginStatus.ACQUIRED, attempts);
        }

        @Override
        public boolean complete(InboxMessageKey key, String completingWorkerId, Instant completedAt) {
            if (!completingWorkerId.equals(workerId) || !"PROCESSING".equals(status)) {
                return false;
            }
            status = "COMPLETED";
            workerId = null;
            return true;
        }

        @Override
        public boolean fail(
            InboxMessageKey key,
            String failingWorkerId,
            String errorMessage,
            Instant failedAt
        ) {
            if (!failingWorkerId.equals(workerId) || !"PROCESSING".equals(status)) {
                return false;
            }
            status = "FAILED";
            workerId = null;
            return true;
        }
    }
}
