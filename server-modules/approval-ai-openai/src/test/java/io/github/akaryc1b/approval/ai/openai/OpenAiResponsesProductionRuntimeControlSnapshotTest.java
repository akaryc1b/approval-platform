package io.github.akaryc1b.approval.ai.openai;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesProductionRuntimeControlSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-08-05T10:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void snapshotIsMetadataOnlyAndReadsLiveCircuitWithoutCreatingBinding() {
        OpenAiResponsesProductionRuntimeFactory factory =
            new OpenAiResponsesProductionRuntimeFactory(profile(), CLOCK);

        var snapshot = factory.controlSnapshot();

        assertEquals(NOW, snapshot.observedAt());
        assertTrue(snapshot.killSwitchEnabled());
        assertEquals(7, snapshot.killSwitchGeneration());
        assertEquals(64, snapshot.killSwitchEvidenceHash().length());
        assertEquals(64, snapshot.costPolicyEvidenceHash().length());
        assertEquals(64, snapshot.secretVersionEvidenceHash().length());
        assertEquals(10, snapshot.perTenantRateLimit());
        assertEquals(100, snapshot.globalRateLimit());
        assertEquals(60, snapshot.rateWindowSeconds());
        assertEquals(3, snapshot.circuitFailureThreshold());
        assertEquals(60, snapshot.circuitOpenSeconds());
        assertEquals(1_000_000, snapshot.maximumRequestMicros());
        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED,
            snapshot.circuitState()
        );
        assertEquals(1, snapshot.circuitGeneration());
        assertFalse(snapshot.rateUsageExposed());
        assertFalse(snapshot.budgetConsumptionExposed());
        assertTrue(factory.toString().contains("OpenAiResponsesProductionRuntimeFactory"));
    }

    private static OpenAiResponsesProductionRuntimeFactory.RuntimeProfile profile() {
        return new OpenAiResponsesProductionRuntimeFactory.RuntimeProfile(
            "key-v1",
            NOW.minusSeconds(60),
            NOW.plusSeconds(3_600),
            "secret-policy-v1",
            7,
            "kill-switch-policy-v1",
            "cost-v1",
            NOW.minusSeconds(60),
            NOW.plusSeconds(3_600),
            1,
            2,
            1_000_000,
            10,
            100,
            Duration.ofSeconds(60),
            3,
            Duration.ofSeconds(60)
        );
    }
}
