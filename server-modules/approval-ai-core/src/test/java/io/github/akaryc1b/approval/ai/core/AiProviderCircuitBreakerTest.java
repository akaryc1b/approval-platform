package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderCircuitBreakerTest {

    @Test
    void opensBlocksAndAllowsOnlyOneHalfOpenProbe() {
        AiProviderCircuitBreaker breaker = new AiProviderCircuitBreaker(
            new AiProviderCircuitBreaker.Configuration(2, Duration.ofSeconds(10))
        );
        AiVersionReferences.ProviderVersion provider = new AiVersionReferences.ProviderVersion(
            "provider-a",
            "1"
        );
        Instant start = Instant.parse("2026-07-23T00:00:00Z");

        AiProviderCircuitBreaker.Permit first = breaker.tryAcquire(provider, start);
        assertEquals(
            AiProviderCircuitBreaker.State.CLOSED,
            breaker.record(first, AiOutcomeClassification.TIMEOUT, start)
        );
        AiProviderCircuitBreaker.Permit second = breaker.tryAcquire(
            provider,
            start.plusSeconds(1)
        );
        assertEquals(
            AiProviderCircuitBreaker.State.OPEN,
            breaker.record(second, AiOutcomeClassification.UNKNOWN, start.plusSeconds(1))
        );
        assertFalse(breaker.tryAcquire(provider, start.plusSeconds(2)).allowed());

        AiProviderCircuitBreaker.Permit probe = breaker.tryAcquire(
            provider,
            start.plusSeconds(12)
        );
        assertTrue(probe.allowed());
        assertTrue(probe.probe());
        assertFalse(breaker.tryAcquire(provider, start.plusSeconds(12)).allowed());
        assertEquals(
            AiProviderCircuitBreaker.State.CLOSED,
            breaker.record(probe, AiOutcomeClassification.SUCCESS, start.plusSeconds(12))
        );
    }

    @Test
    void policyAndCapabilityFailuresDoNotOpenProviderHealthCircuit() {
        AiProviderCircuitBreaker breaker = new AiProviderCircuitBreaker(
            new AiProviderCircuitBreaker.Configuration(1, Duration.ofSeconds(10))
        );
        AiVersionReferences.ProviderVersion provider = new AiVersionReferences.ProviderVersion(
            "provider-a",
            "1"
        );
        Instant now = Instant.parse("2026-07-23T00:00:00Z");

        for (AiOutcomeClassification classification : new AiOutcomeClassification[] {
            AiOutcomeClassification.DISABLED,
            AiOutcomeClassification.UNSUPPORTED,
            AiOutcomeClassification.REJECTED,
            AiOutcomeClassification.POLICY_BLOCKED
        }) {
            AiProviderCircuitBreaker.Permit permit = breaker.tryAcquire(provider, now);
            assertEquals(
                AiProviderCircuitBreaker.State.CLOSED,
                breaker.record(permit, classification, now)
            );
        }
    }
}
