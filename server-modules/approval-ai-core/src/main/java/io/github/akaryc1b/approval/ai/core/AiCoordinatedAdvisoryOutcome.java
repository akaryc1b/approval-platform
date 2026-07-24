package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiProviderOutcome;
import io.github.akaryc1b.approval.ai.spi.AiUsageEvidence;

import java.util.Objects;

/** Safe coordinator result that proves at most one provider invocation was started. */
public record AiCoordinatedAdvisoryOutcome(
    AiProviderRoute selectedRoute,
    AiProviderOutcome outcome,
    AiUsageEvidence usageEvidence,
    int skippedCandidates,
    boolean providerInvocationStarted,
    boolean postInvocationFallbackAttempted,
    AiProviderCircuitBreaker.State circuitStateBefore,
    AiProviderCircuitBreaker.State circuitStateAfter
) {

    public AiCoordinatedAdvisoryOutcome {
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        usageEvidence = Objects.requireNonNull(
            usageEvidence,
            "usageEvidence must not be null"
        );
        circuitStateBefore = Objects.requireNonNull(
            circuitStateBefore,
            "circuitStateBefore must not be null"
        );
        circuitStateAfter = Objects.requireNonNull(
            circuitStateAfter,
            "circuitStateAfter must not be null"
        );
        if (skippedCandidates < 0) {
            throw new IllegalArgumentException("skippedCandidates must not be negative");
        }
        if (providerInvocationStarted && selectedRoute == null) {
            throw new IllegalArgumentException(
                "providerInvocationStarted requires a selected route"
            );
        }
        if (postInvocationFallbackAttempted) {
            throw new IllegalArgumentException(
                "post-invocation fallback is prohibited in the M6-D safe foundation"
            );
        }
    }
}
