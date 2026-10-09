package io.github.akaryc1b.approval.integration.outbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboxDispatchObserverTest {
    @Test
    void telemetryCannotChangeDeliveryRetryLeaseOwnershipOrBusinessFailures() {
        assertEquals(28, OutboxDispatchObserverChecks.runAll());
    }
}
