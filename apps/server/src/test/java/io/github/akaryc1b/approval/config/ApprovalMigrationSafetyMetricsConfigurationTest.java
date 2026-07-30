package io.github.akaryc1b.approval.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApprovalMigrationSafetyMetricsConfigurationTest {

    @Test
    void publishesExactlySixClosedFeatureGauges() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApprovalMigrationSafetyMetricsConfiguration configuration =
            new ApprovalMigrationSafetyMetricsConfiguration();

        configuration.approvalMigrationSafetyState(
            false,
            false,
            false,
            false,
            false,
            false,
            meters
        );

        Set<String> features = meters.find(ApprovalMigrationSafetyMetricsConfiguration.METRIC)
            .gauges()
            .stream()
            .map(gauge -> gauge.getId().getTag("feature"))
            .collect(Collectors.toSet());
        assertEquals(Set.of(
            "execution",
            "worker",
            "orchestration",
            "aggregation",
            "automatic_reconciliation",
            "kill_switch"
        ), features);
        for (String feature : features) {
            assertEquals(
                0.0,
                meters.get(ApprovalMigrationSafetyMetricsConfiguration.METRIC)
                    .tag("feature", feature)
                    .gauge()
                    .value()
            );
        }
        assertLowCardinalityTagKeys(meters);
    }

    @Test
    void enabledValuesRemainBinaryAndFeatureScoped() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ApprovalMigrationSafetyMetricsConfiguration configuration =
            new ApprovalMigrationSafetyMetricsConfiguration();

        configuration.approvalMigrationSafetyState(
            true,
            false,
            true,
            false,
            false,
            true,
            meters
        );

        assertEquals(1.0, value(meters, "execution"));
        assertEquals(0.0, value(meters, "worker"));
        assertEquals(1.0, value(meters, "orchestration"));
        assertEquals(0.0, value(meters, "aggregation"));
        assertEquals(0.0, value(meters, "automatic_reconciliation"));
        assertEquals(1.0, value(meters, "kill_switch"));
        assertLowCardinalityTagKeys(meters);
    }

    private static double value(SimpleMeterRegistry meters, String feature) {
        return meters.get(ApprovalMigrationSafetyMetricsConfiguration.METRIC)
            .tag("feature", feature)
            .gauge()
            .value();
    }

    private static void assertLowCardinalityTagKeys(SimpleMeterRegistry meters) {
        Set<String> forbidden = Set.of(
            "tenantId",
            "operatorId",
            "definitionKey",
            "planId",
            "intentId",
            "attemptId",
            "instanceId",
            "requestId",
            "traceId",
            "reason"
        );
        for (Meter meter : meters.getMeters()) {
            meter.getId().getTags().forEach(tag ->
                assertFalse(forbidden.contains(tag.getKey()), "forbidden metric tag " + tag.getKey())
            );
        }
    }
}
