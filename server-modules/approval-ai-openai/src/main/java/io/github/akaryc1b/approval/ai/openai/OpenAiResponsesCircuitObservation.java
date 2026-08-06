package io.github.akaryc1b.approval.ai.openai;

import java.util.Objects;

/** Atomic metadata-only observation of one shared process-local CircuitBreaker. */
final class OpenAiResponsesCircuitObservation {

    private OpenAiResponsesCircuitObservation() {
    }

    static Observation capture(
        OpenAiResponsesTransportControls.CircuitBreaker circuitBreaker
    ) {
        OpenAiResponsesTransportControls.CircuitBreaker exact = Objects.requireNonNull(
            circuitBreaker,
            "circuitBreaker must not be null"
        );
        synchronized (exact) {
            return new Observation(exact.state(), exact.generation());
        }
    }

    record Observation(
        OpenAiResponsesTransportControls.CircuitBreaker.State state,
        long generation
    ) {
        Observation {
            state = Objects.requireNonNull(state, "state must not be null");
            if (generation < 1) {
                throw new IllegalArgumentException("generation must be positive");
            }
        }
    }
}
