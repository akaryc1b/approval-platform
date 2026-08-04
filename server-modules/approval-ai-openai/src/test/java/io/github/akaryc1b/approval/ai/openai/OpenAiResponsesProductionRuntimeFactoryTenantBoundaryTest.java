package io.github.akaryc1b.approval.ai.openai;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiResponsesProductionRuntimeFactoryTenantBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void runtimeBindingAcceptsAndCachesThePlatformMaximumTenant() {
        String tenantId = "t".repeat(128);
        OpenAiResponsesProductionRuntimeFactory factory = factory();

        var first = factory.bind(tenantId);
        var replay = factory.bind(tenantId);

        assertEquals(64, first.tenantHash().length());
        assertSame(first, replay);
    }

    @Test
    void runtimeBindingRejectsTenantAboveThePlatformMaximum() {
        assertThrows(
            IllegalArgumentException.class,
            () -> factory().bind("t".repeat(129))
        );
    }

    private static OpenAiResponsesProductionRuntimeFactory factory() {
        return new OpenAiResponsesProductionRuntimeFactory(
            new OpenAiResponsesProductionRuntimeFactory.RuntimeProfile(
                "openai-key-version-1",
                NOW.minusSeconds(60),
                NOW.plusSeconds(3_600),
                "secret-policy-v1",
                1,
                "kill-switch-policy-v1",
                "cost-policy-v1",
                NOW.minusSeconds(60),
                NOW.plusSeconds(3_600),
                1,
                1,
                1_000_000,
                10,
                100,
                Duration.ofMinutes(1),
                3,
                Duration.ofMinutes(1)
            ),
            CLOCK
        );
    }
}
