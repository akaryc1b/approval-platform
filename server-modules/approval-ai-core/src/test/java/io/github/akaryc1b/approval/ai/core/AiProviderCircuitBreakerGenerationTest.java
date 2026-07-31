package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiProviderCircuitBreakerGenerationTest {

    @Test
    void staleClosedPermitCannotCloseNewlyOpenedCircuit() {
        AiProviderCircuitBreaker breaker = new AiProviderCircuitBreaker(
            new AiProviderCircuitBreaker.Configuration(1, Duration.ofSeconds(30))
        );
        AiVersionReferences.ProviderVersion provider = new AiVersionReferences.ProviderVersion(
            "provider-a",
            "1"
        );
        Instant startedAt = Instant.parse("2026-07-31T00:00:00Z");

        AiProviderCircuitBreaker.Permit failing = breaker.tryAcquire(provider, startedAt);
        AiProviderCircuitBreaker.Permit staleSuccess = breaker.tryAcquire(provider, startedAt);

        assertEquals(
            AiProviderCircuitBreaker.State.OPEN,
            breaker.record(failing, AiOutcomeClassification.TIMEOUT, startedAt.plusSeconds(1))
        );
        assertEquals(
            AiProviderCircuitBreaker.State.OPEN,
            breaker.record(
                staleSuccess,
                AiOutcomeClassification.SUCCESS,
                startedAt.plusSeconds(2)
            )
        );
        assertEquals(AiProviderCircuitBreaker.State.OPEN, breaker.state(provider));
        assertFalse(breaker.tryAcquire(provider, startedAt.plusSeconds(3)).allowed());
    }
}
