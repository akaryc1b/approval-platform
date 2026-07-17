package io.github.akaryc1b.approval.integration.outbox;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;
import io.github.akaryc1b.approval.integration.retry.ExponentialBackoffRetryPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboxDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    @Test
    void retryableFailureIsRescheduledThenDelivered() {
        var clock = new MutableClock(NOW);
        var repository = new FakeOutboxRepository(message(NOW));
        var calls = new AtomicInteger();
        BusinessCallbackConnector connector = (context, event) -> {
            int call = calls.incrementAndGet();
            return call == 1
                ? receipt(BusinessCallbackConnector.DeliveryStatus.RETRYABLE_FAILURE, 503, "busy", clock.instant())
                : receipt(BusinessCallbackConnector.DeliveryStatus.DELIVERED, 204, null, clock.instant());
        };
        var dispatcher = new OutboxDispatcher(
            repository,
            connectorKey -> connector,
            new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                3,
                0,
                () -> 0.5
            ),
            clock,
            Duration.ofMinutes(1)
        );

        var first = dispatcher.dispatchBatch(10, "worker-a");
        clock.advance(Duration.ofSeconds(1));
        var second = dispatcher.dispatchBatch(10, "worker-b");

        assertEquals(1, first.rescheduled());
        assertEquals(1, second.delivered());
        assertEquals(2, calls.get());
        assertEquals("DELIVERED", repository.status);
    }

    @Test
    void permanentFailureMovesMessageToDeadState() {
        var clock = new MutableClock(NOW);
        var repository = new FakeOutboxRepository(message(NOW));
        BusinessCallbackConnector connector = (context, event) -> receipt(
            BusinessCallbackConnector.DeliveryStatus.PERMANENT_FAILURE,
            400,
            "invalid payload",
            clock.instant()
        );
        var dispatcher = new OutboxDispatcher(
            repository,
            connectorKey -> connector,
            new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(30),
                3,
                0
            ),
            clock,
            Duration.ofMinutes(1)
        );

        var report = dispatcher.dispatchBatch(10, "worker-a");

        assertEquals(1, report.dead());
        assertEquals("DEAD", repository.status);
    }

    private static OutboxMessage message(Instant now) {
        var context = new ConnectorContext("generic", "tenant-a", "request-1", "trace-1", now);
        var event = new BusinessCallbackConnector.BusinessEvent(
            UUID.randomUUID(),
            "TASK_COMPLETED.v1",
            "TASK",
            "task-1",
            now,
            "task-1-completed",
            Map.of("decision", "APPROVED")
        );
        return OutboxMessage.create(context, event, now);
    }

    private static BusinessCallbackConnector.CallbackReceipt receipt(
        BusinessCallbackConnector.DeliveryStatus status,
        int responseCode,
        String error,
        Instant completedAt
    ) {
        return new BusinessCallbackConnector.CallbackReceipt(
            status,
            "provider-request",
            responseCode,
            completedAt,
            error
        );
    }

    private static final class FakeOutboxRepository implements OutboxRepository {

        private final OutboxMessage message;
        private String status = "PENDING";
        private String workerId;
        private int attempts;
        private Instant availableAt;
        private Instant lockedUntil;

        private FakeOutboxRepository(OutboxMessage message) {
            this.message = message;
            this.availableAt = message.availableAt();
        }

        @Override
        public AppendResult append(OutboxMessage ignored) {
            return AppendResult.INSERTED;
        }

        @Override
        public List<ClaimedMessage> claimDue(
            Instant now,
            int limit,
            String claimingWorkerId,
            Duration leaseDuration
        ) {
            boolean available = status.equals("PENDING") && !availableAt.isAfter(now);
            boolean expired = status.equals("IN_FLIGHT")
                && lockedUntil != null
                && !lockedUntil.isAfter(now);
            if (!available && !expired) {
                return List.of();
            }
            status = "IN_FLIGHT";
            workerId = claimingWorkerId;
            lockedUntil = now.plus(leaseDuration);
            return List.of(new ClaimedMessage(message, attempts, workerId, lockedUntil));
        }

        @Override
        public boolean markDelivered(
            UUID messageId,
            String completingWorkerId,
            String providerRequestId,
            int responseCode,
            Instant deliveredAt
        ) {
            if (!owns(messageId, completingWorkerId)) {
                return false;
            }
            status = "DELIVERED";
            workerId = null;
            return true;
        }

        @Override
        public boolean reschedule(
            UUID messageId,
            String reschedulingWorkerId,
            int newAttempts,
            Instant newAvailableAt,
            String errorMessage,
            Instant updatedAt
        ) {
            if (!owns(messageId, reschedulingWorkerId)) {
                return false;
            }
            status = "PENDING";
            attempts = newAttempts;
            availableAt = newAvailableAt;
            workerId = null;
            return true;
        }

        @Override
        public boolean markDead(
            UUID messageId,
            String failingWorkerId,
            int newAttempts,
            String errorMessage,
            Instant deadAt
        ) {
            if (!owns(messageId, failingWorkerId)) {
                return false;
            }
            status = "DEAD";
            attempts = newAttempts;
            workerId = null;
            return true;
        }

        private boolean owns(UUID messageId, String expectedWorkerId) {
            return status.equals("IN_FLIGHT")
                && message.id().equals(messageId)
                && expectedWorkerId.equals(workerId);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
