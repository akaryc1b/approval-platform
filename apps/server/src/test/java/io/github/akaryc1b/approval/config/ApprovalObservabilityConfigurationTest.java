package io.github.akaryc1b.approval.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApprovalObservabilityConfigurationTest {

    @Test
    void deniesHighCardinalityTagsOnlyForApprovalOwnedMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(
            new ApprovalObservabilityConfiguration().approvalMetricCardinalityGuard()
        );

        Counter.builder("approval.workflow.event")
            .tag("process_instance_id", "process-123")
            .register(registry)
            .increment();
        Counter.builder("approval.workflow.event")
            .tag("outcome", "completed")
            .register(registry)
            .increment();
        Counter.builder("third.party.event")
            .tag("request_id", "request-123")
            .register(registry)
            .increment();

        assertNull(
            registry.find("approval.workflow.event")
                .tag("process_instance_id", "process-123")
                .counter()
        );
        assertEquals(
            1.0d,
            registry.get("approval.workflow.event")
                .tag("outcome", "completed")
                .counter()
                .count()
        );
        assertNotNull(
            registry.find("third.party.event")
                .tag("request_id", "request-123")
                .counter()
        );
    }
}
