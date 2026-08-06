package io.github.akaryc1b.approval.ai.openai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesCircuitFaultAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T04:00:00Z");

    @Test
    void consecutiveFailuresOpenCircuitAndOpenWindowRejectsAdmission() {
        OpenAiResponsesTransportControls.CircuitBreaker circuit = circuit(2);
        long initialGeneration = circuit.generation();

        var first = circuit.tryAcquire(NOW);
        assertTrue(first.allowed());
        circuit.record(
            first,
            OpenAiResponsesTransportControls.Outcome.TRANSPORT_FAILURE,
            NOW
        );
        assertEquals(OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED,
            circuit.state());
        assertTrue(circuit.generation() > initialGeneration);

        long afterFirst = circuit.generation();
        var second = circuit.tryAcquire(NOW.plusSeconds(1));
        assertTrue(second.allowed());
        circuit.record(
            second,
            OpenAiResponsesTransportControls.Outcome.UNKNOWN,
            NOW.plusSeconds(1)
        );
        assertEquals(OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
            circuit.state());
        assertTrue(circuit.generation() > afterFirst);
        assertFalse(circuit.tryAcquire(NOW.plusSeconds(2)).allowed());
    }

    @Test
    void openWindowAllowsOneHalfOpenProbeAndSuccessClosesCircuit() {
        OpenAiResponsesTransportControls.CircuitBreaker circuit = circuit(1);
        var failure = circuit.tryAcquire(NOW);
        circuit.record(
            failure,
            OpenAiResponsesTransportControls.Outcome.TRANSPORT_FAILURE,
            NOW
        );
        assertEquals(OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
            circuit.state());

        var probe = circuit.tryAcquire(NOW.plusSeconds(31));
        assertTrue(probe.allowed());
        assertEquals(OpenAiResponsesTransportControls.CircuitBreaker.State.HALF_OPEN,
            probe.stateBefore());
        assertEquals(OpenAiResponsesTransportControls.CircuitBreaker.State.HALF_OPEN,
            circuit.state());
        assertFalse(circuit.tryAcquire(NOW.plusSeconds(31)).allowed());

        long beforeSuccess = circuit.generation();
        circuit.record(
            probe,
            OpenAiResponsesTransportControls.Outcome.SUCCESS,
            NOW.plusSeconds(31)
        );
        assertEquals(OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED,
            circuit.state());
        assertTrue(circuit.generation() > beforeSuccess);
        assertTrue(circuit.tryAcquire(NOW.plusSeconds(31)).allowed());
    }

    @Test
    void halfOpenFailureReopensForAnotherFullWindow() {
        OpenAiResponsesTransportControls.CircuitBreaker circuit = circuit(1);
        var first = circuit.tryAcquire(NOW);
        circuit.record(
            first,
            OpenAiResponsesTransportControls.Outcome.TRANSPORT_FAILURE,
            NOW
        );
        var probe = circuit.tryAcquire(NOW.plusSeconds(31));
        assertTrue(probe.allowed());

        long beforeFailure = circuit.generation();
        circuit.record(
            probe,
            OpenAiResponsesTransportControls.Outcome.UNKNOWN,
            NOW.plusSeconds(31)
        );

        assertEquals(OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
            circuit.state());
        assertTrue(circuit.generation() > beforeFailure);
        assertFalse(circuit.tryAcquire(NOW.plusSeconds(60)).allowed());
        assertTrue(circuit.tryAcquire(NOW.plusSeconds(62)).allowed());
    }

    @Test
    void snapshotsNeverAcquirePermitOrChangeGeneration() {
        OpenAiResponsesTransportControls.CircuitBreaker circuit = circuit(1);
        long generation = circuit.generation();

        for (int index = 0; index < 20; index++) {
            assertEquals(OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED,
                circuit.state());
            assertEquals(generation, circuit.generation());
        }

        var permit = circuit.tryAcquire(NOW);
        assertTrue(permit.allowed());
    }

    private static OpenAiResponsesTransportControls.CircuitBreaker circuit(int threshold) {
        return new OpenAiResponsesTransportControls.CircuitBreaker(
            threshold,
            Duration.ofSeconds(30)
        );
    }
}
