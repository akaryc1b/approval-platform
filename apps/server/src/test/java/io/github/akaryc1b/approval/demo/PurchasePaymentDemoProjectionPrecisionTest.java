package io.github.akaryc1b.approval.demo;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.PublishedDefinition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PurchasePaymentDemoProjectionPrecisionTest {

    @Test
    void matchesBothHalvesOfEverySubMicrosecondBucketAndSecondCarry() throws Exception {
        for (String start : new String[] {"2026-09-10T12:00:00Z", "1969-12-31T23:59:59Z"}) {
            Instant second = Instant.parse(start);
            for (int nanos : new int[] {0, 1, 499, 500, 999, 1000, 1499, 1500,
                123456499, 123456500, 999999499, 999999500, 999999999}) {
                Instant original = second.plusNanos(nanos);
                Instant persisted = second.plusNanos(((nanos + 500L) / 1000L) * 1000L);
                assertEquals(persisted, normalize(original));
                assertEquals(persisted, normalize(persisted));
                assertDoesNotThrow(() -> compare(definition(persisted), definition(original)));
            }
        }
    }

    @Test
    void truncationWouldRejectAValidRoundedDatabaseRoundTrip() throws Exception {
        Instant original = Instant.parse("2026-09-10T12:00:00.123456789Z");
        Instant persisted = Instant.parse("2026-09-10T12:00:00.123457Z");
        assertNotEquals(original.truncatedTo(java.time.temporal.ChronoUnit.MICROS), persisted);
        assertEquals(persisted, normalize(original));
        assertDoesNotThrow(() -> compare(definition(persisted), definition(original)));
    }

    @Test
    void stillRejectsAWholeMicrosecondDifferenceAndEveryStableIdentityChange() throws Exception {
        PublishedDefinition expected = definition(Instant.parse("2026-09-10T12:00:00.123457Z"));
        assertThrows(IllegalStateException.class, () -> compare(
            definition(expected.publishedAt().plusNanos(1000)), expected));
        var components = PublishedDefinition.class.getRecordComponents();
        Class<?>[] types = Arrays.stream(components).map(component -> component.getType())
            .toArray(Class<?>[]::new);
        Object[] values = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            values[index] = components[index].getAccessor().invoke(expected);
        }
        for (int index = 0; index < components.length - 1; index++) {
            Object[] changed = values.clone();
            changed[index] = changed[index] instanceof Integer number ? number + 1
                : changed[index] + "-different";
            PublishedDefinition actual = PublishedDefinition.class.getDeclaredConstructor(types)
                .newInstance(changed);
            assertThrows(IllegalStateException.class, () -> compare(actual, expected));
        }
        assertThrows(NullPointerException.class, () -> normalize(null));
    }

    private static PublishedDefinition definition(Instant time) {
        return new PublishedDefinition("demo-purchase-payment", "purchase-payment", 1,
            "purchase-payment", 1, "compiler-v1", "a".repeat(64), "deployment-1",
            "engine-definition-1", 1, "demo-admin", time);
    }

    private static Instant normalize(Instant time) throws Exception {
        var method = PurchasePaymentDemoSeeder.class.getDeclaredMethod("micros", Instant.class);
        method.setAccessible(true);
        try {
            return (Instant) method.invoke(null, time);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw error;
        }
    }

    private static void compare(PublishedDefinition actual, PublishedDefinition expected) throws Exception {
        var method = PurchasePaymentDemoSeeder.class.getDeclaredMethod("requireDefinitionProjection",
            PublishedDefinition.class, PublishedDefinition.class);
        method.setAccessible(true);
        try {
            method.invoke(null, actual, expected);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw error;
        }
    }
}
