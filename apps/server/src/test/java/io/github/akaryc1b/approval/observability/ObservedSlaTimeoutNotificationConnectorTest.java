package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.CallbackReceipt;
import io.github.akaryc1b.approval.connector.port.BusinessCallbackConnector.DeliveryStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservedSlaTimeoutNotificationConnectorTest {

    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void recordsDeliveredAttemptWithoutChangingTheReceipt() {
        CallbackReceipt expected = receipt(DeliveryStatus.DELIVERED);
        var observed = observed((context, event) -> expected);

        assertSame(expected, observed.deliver(null, null));
        assertEquals(1.0d, count("approval.notification.attempts", "kind", "sla_timeout"));
        assertEquals(1.0d, count("approval.notification.delivered", "kind", "sla_timeout"));
        assertTrue(registry.find("approval.notification.failures").counters().isEmpty());
    }

    @Test
    void recordsRetryablePermanentAndInvalidReceiptsWithBoundedCategories() {
        observed((context, event) -> receipt(DeliveryStatus.RETRYABLE_FAILURE)).deliver(null, null);
        observed((context, event) -> receipt(DeliveryStatus.PERMANENT_FAILURE)).deliver(null, null);
        assertNull(observed((context, event) -> null).deliver(null, null));

        assertEquals(3.0d, count("approval.notification.attempts", "kind", "sla_timeout"));
        assertEquals(1.0d, failure("retryable"));
        assertEquals(1.0d, failure("permanent"));
        assertEquals(1.0d, failure("invalid_receipt"));
    }

    @Test
    void recordsDelegateExceptionAndRethrowsTheOriginalFailure() {
        IllegalStateException expected = new IllegalStateException("fixture-sensitive-detail");
        var observed = observed((context, event) -> {
            throw expected;
        });

        assertSame(expected, assertThrows(IllegalStateException.class, () -> observed.deliver(null, null)));
        assertEquals(1.0d, count("approval.notification.attempts", "kind", "sla_timeout"));
        assertEquals(1.0d, failure("exception"));
    }

    @Test
    void exportsOnlyFixedNotificationDimensions() {
        observed((context, event) -> receipt(DeliveryStatus.RETRYABLE_FAILURE)).deliver(null, null);
        Set<String> allowedNames = Set.of("kind", "category");
        Set<String> allowedValues = Set.of("sla_timeout", "retryable");

        registry.getMeters().stream()
            .filter(meter -> meter.getId().getName().startsWith("approval.notification."))
            .flatMap(meter -> meter.getId().getTags().stream())
            .forEach(tag -> {
                assertTrue(allowedNames.contains(tag.getKey()));
                assertTrue(allowedValues.contains(tag.getValue()));
            });
    }

    private ObservedSlaTimeoutNotificationConnector observed(BusinessCallbackConnector delegate) {
        return new ObservedSlaTimeoutNotificationConnector(delegate, registry);
    }

    private double failure(String category) {
        return count("approval.notification.failures", "kind", "sla_timeout", "category", category);
    }

    private double count(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    private static CallbackReceipt receipt(DeliveryStatus status) {
        return new CallbackReceipt(status, null, 0, NOW,
            status == DeliveryStatus.DELIVERED ? null : "bounded-fixture");
    }
}
