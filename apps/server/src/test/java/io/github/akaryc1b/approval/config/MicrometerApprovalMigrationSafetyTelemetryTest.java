package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationSafetyTelemetry.Event;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MicrometerApprovalMigrationSafetyTelemetryTest {

    @Test
    void preRegistersClosedEventsAndKeepsMeterCountStable() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MicrometerApprovalMigrationSafetyTelemetry telemetry =
            new MicrometerApprovalMigrationSafetyTelemetry(meters);

        int registered = meters.get(MicrometerApprovalMigrationSafetyTelemetry.METRIC)
            .meters()
            .size();
        assertEquals(Event.values().length, registered);

        for (int round = 0; round < 100; round++) {
            for (Event event : Event.values()) {
                telemetry.record(event);
            }
        }

        assertEquals(
            registered,
            meters.get(MicrometerApprovalMigrationSafetyTelemetry.METRIC).meters().size()
        );
        for (Event event : Event.values()) {
            assertEquals(
                100.0,
                meters.get(MicrometerApprovalMigrationSafetyTelemetry.METRIC)
                    .tag("event", event.name().toLowerCase(java.util.Locale.ROOT))
                    .counter()
                    .count()
            );
        }
        assertLowCardinality(meters);
    }

    @Test
    void registryOutageCannotFailStartupOrSafetyPath() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        meters.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                throw new IllegalStateException("synthetic registry outage");
            }
        });

        MicrometerApprovalMigrationSafetyTelemetry telemetry = assertDoesNotThrow(
            () -> new MicrometerApprovalMigrationSafetyTelemetry(meters)
        );
        assertDoesNotThrow(() -> telemetry.record(Event.UNKNOWN_ENTERED));
    }

    private static void assertLowCardinality(SimpleMeterRegistry meters) {
        Set<String> forbidden = Set.of(
            "tenantId",
            "planId",
            "intentId",
            "attemptId",
            "instanceId",
            "requestId",
            "traceId",
            "message",
            "exception"
        );
        for (Meter meter : meters.getMeters()) {
            meter.getId().getTags().forEach(tag ->
                assertFalse(forbidden.contains(tag.getKey()), "forbidden metric tag " + tag.getKey())
            );
        }
    }
}
