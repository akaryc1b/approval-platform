package io.github.akaryc1b.approval.ai.spi;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiUsageEvidenceTest {

    @Test
    void recordsPlatformObservedLatencyWithoutInventingTokensOrCost() {
        AiUsageEvidence evidence = AiUsageEvidence.platformObserved(128, 42L);

        assertEquals(128, evidence.inputCharacters());
        assertEquals(42L, evidence.observedLatencyMillis());
        assertEquals(AiUsageEvidence.Source.PLATFORM_OBSERVED, evidence.source());
        assertNull(evidence.inputTokens());
        assertNull(evidence.outputTokens());
        assertNull(evidence.totalTokens());
        assertNull(evidence.estimatedCost());
    }

    @Test
    void rejectsInconsistentTokenAndCostEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new AiUsageEvidence(
            10,
            4L,
            5L,
            20L,
            3L,
            4L,
            BigDecimal.ONE,
            "usd",
            AiUsageEvidence.Source.MIXED
        ));
        assertThrows(IllegalArgumentException.class, () -> new AiUsageEvidence(
            10,
            null,
            null,
            null,
            null,
            4L,
            null,
            "USD",
            AiUsageEvidence.Source.PLATFORM_OBSERVED
        ));
    }
}
