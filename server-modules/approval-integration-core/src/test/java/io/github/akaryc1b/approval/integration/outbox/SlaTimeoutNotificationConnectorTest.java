package io.github.akaryc1b.approval.integration.outbox;

import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.BusinessEvent;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.DeliveryStatus;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector.NotificationDeliveryResult;
import io.github.akaryc1b.approval.integration.outbox.SlaTimeoutNotificationConnector.Timeout;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlaTimeoutNotificationConnectorTest {
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID SLA = UUID.fromString("88000000-0000-0000-0000-000000000001");
    private static final UUID INSTANCE = UUID.fromString("88000000-0000-0000-0000-000000000002");
    private static final UUID TASK = UUID.fromString("88000000-0000-0000-0000-000000000003");

    @Test
    void targetScopeIsExplicitAndAnEarlyObservationIsNotATimeout() {
        for (String target : new String[] { "PROCESS", "TASK", "COLLABORATION_PARTICIPANT" }) {
            Timeout timeout = timeout(target);
            OutboxMessage message = message(timeout);
            assertEquals(timeout.eventType(), message.event().eventType());
            assertEquals("SLA_TIMEOUT", message.event().aggregateType());
            assertEquals(SlaTimeoutNotificationConnector.ROUTE, message.context().connectorKey());
            assertThrows(IllegalArgumentException.class, () -> SlaTimeoutNotificationConnector.message(
                "tenant-a", "request-a", null, NOW.minusSeconds(1), timeout));
        }
        assertThrows(IllegalArgumentException.class, () -> new Timeout(SLA, INSTANCE, TASK, null,
            SLA, 1, 1, "PROCESS", "owner", NOW, NOW, "generic-rest"));
        assertThrows(IllegalArgumentException.class, () -> SlaTimeoutNotificationConnector.routeKey(
            SlaTimeoutNotificationConnector.ROUTE));
    }

    @Test
    void replayKeyIsStableAndTenantScoped() {
        Timeout timeout = timeout("TASK");
        assertEquals(message(timeout).event().idempotencyKey(), message(timeout).event().idempotencyKey());
        assertNotEquals(timeout.key("tenant-a"), timeout.key("tenant-b"));
        assertTrue(timeout.key("tenant-a").length() < 200);
        assertThrows(UnsupportedOperationException.class, () -> timeout.payload().put("secret", "x"));
    }

    @Test
    void existingOrganizationApiReceivesOnlyBoundedSnapshotMetadataAndOriginalCorrelation() {
        OutboxMessage message = message(timeout("TASK"));
        AtomicInteger calls = new AtomicInteger();
        var adapter = adapter((context, notification) -> {
            calls.incrementAndGet();
            assertEquals("generic-rest", context.connectorKey());
            assertEquals("tenant-a", context.tenantId());
            assertEquals("request-a", context.requestId());
            assertEquals("trace-a", context.traceId());
            assertEquals("owner", notification.recipientUserId());
            assertEquals("TASK_TIMEOUT_DETECTED.v1", notification.eventType());
            assertEquals(message.event().idempotencyKey(), notification.deduplicationKey());
            assertEquals(message.event().payload(), notification.metadata());
            assertFalse(notification.body().contains("request-a"));
            return NotificationDeliveryResult.delivered("receipt-a");
        });
        var result = adapter.deliver(message.context(), message.event());
        assertEquals(DeliveryStatus.DELIVERED, result.status());
        assertEquals("receipt-a", result.providerRequestId());
        assertEquals(0, result.responseCode(), "no wire status is exposed by OrganizationConnector");
        assertEquals(1, calls.get());
    }

    @Test
    void lostAcknowledgementKeepsOneReceiverEffectAndOneStableKey() {
        var effects = new HashSet<String>();
        AtomicInteger attempts = new AtomicInteger();
        var adapter = adapter((context, notification) -> {
            effects.add(context.tenantId() + "/" + notification.deduplicationKey());
            return attempts.incrementAndGet() == 1
                ? NotificationDeliveryResult.failed(true, "TEMPORARY", "private remote details")
                : NotificationDeliveryResult.delivered("same-effect");
        });
        OutboxMessage message = message(timeout("PROCESS"));
        assertEquals(DeliveryStatus.RETRYABLE_FAILURE, adapter.deliver(message.context(), message.event()).status());
        assertEquals(DeliveryStatus.DELIVERED, adapter.deliver(message.context(), message.event()).status());
        assertEquals(2, attempts.get());
        assertEquals(1, effects.size(), "controlled receiver deduplication, not a real contact-delivery claim");
    }

    @Test
    void foreignRouteTenantTypeKeyAndUnrecognizedPayloadNeverReachTheSender() {
        var adapter = adapter((context, notification) -> { throw new AssertionError("must not send"); });
        OutboxMessage message = message(timeout("TASK"));
        var event = message.event();
        var wrongRoute = new ConnectorContext("generic-rest", "tenant-a", "request-a", null, NOW);
        var wrongTenant = new ConnectorContext(SlaTimeoutNotificationConnector.ROUTE, "tenant-b", "request-a", null, NOW);
        assertEquals(DeliveryStatus.PERMANENT_FAILURE, adapter.deliver(wrongRoute, event).status());
        assertEquals(DeliveryStatus.PERMANENT_FAILURE, adapter.deliver(wrongTenant, event).status());
        assertEquals(DeliveryStatus.PERMANENT_FAILURE, adapter.deliver(message.context(),
            new BusinessEvent(event.eventId(), "PROCESS_TIMEOUT_DETECTED.v1", event.aggregateType(),
                event.aggregateId(), NOW, event.idempotencyKey(), event.payload())).status());
        for (Map<String, Object> payload : invalidPayloads(event.payload())) {
            assertEquals(DeliveryStatus.PERMANENT_FAILURE, adapter.deliver(message.context(),
                new BusinessEvent(event.eventId(), event.eventType(), event.aggregateType(), event.aggregateId(),
                    NOW, event.idempotencyKey(), payload)).status());
        }
    }

    @Test
    void providerExceptionsAndMalformedReceiptsDoNotExposeSensitiveText() {
        OutboxMessage message = message(timeout("TASK"));
        var throwsAdapter = adapter((context, notification) -> { throw new IllegalStateException("secret-token"); });
        var result = throwsAdapter.deliver(message.context(), message.event());
        assertEquals(DeliveryStatus.RETRYABLE_FAILURE, result.status());
        assertEquals("SLA_NOTIFICATION_DELIVERY_UNAVAILABLE", result.errorMessage());
        assertEquals(DeliveryStatus.PERMANENT_FAILURE,
            adapter((context, notification) -> NotificationDeliveryResult.delivered("x".repeat(257)))
                .deliver(message.context(), message.event()).status());
        assertEquals(DeliveryStatus.PERMANENT_FAILURE,
            adapter((context, notification) -> null).deliver(message.context(), message.event()).status());
        assertEquals(DeliveryStatus.PERMANENT_FAILURE,
            adapter((context, notification) -> NotificationDeliveryResult.failed(false, "NO", "secret-token"))
                .deliver(message.context(), message.event()).status());
    }

    private static java.util.List<Map<String, Object>> invalidPayloads(Map<String, Object> original) {
        var extra = new HashMap<>(original); extra.put("formData", "private");
        var route = new HashMap<>(original); route.put("connectorKey", "other-connector");
        var schema = new HashMap<>(original); schema.put("schemaVersion", "2");
        var recipient = new HashMap<>(original); recipient.put("recipientId", "x".repeat(201));
        return java.util.List.of(extra, route, schema, recipient);
    }

    private static OutboxMessage message(Timeout timeout) {
        return SlaTimeoutNotificationConnector.message("tenant-a", "request-a", "trace-a", NOW, timeout);
    }

    private static Timeout timeout(String target) {
        return new Timeout(SLA, INSTANCE, target.equals("PROCESS") ? null : TASK,
            target.equals("COLLABORATION_PARTICIPANT") ? TASK : null, SLA, 1, 101,
            target, "owner", NOW.minusSeconds(60), NOW, "generic-rest");
    }

    private static SlaTimeoutNotificationConnector adapter(BiFunction<ConnectorContext,
        OrganizationConnector.UserNotification, NotificationDeliveryResult> delivery) {
        OrganizationConnector connector = (OrganizationConnector) Proxy.newProxyInstance(
            OrganizationConnector.class.getClassLoader(), new Class<?>[] { OrganizationConnector.class },
            (proxy, method, args) -> {
                if (!method.getName().equals("sendNotification")) throw new AssertionError("unexpected identity lookup");
                return delivery.apply((ConnectorContext) args[0], (OrganizationConnector.UserNotification) args[1]);
            });
        return new SlaTimeoutNotificationConnector(connector, "generic-rest", CLOCK);
    }
}
