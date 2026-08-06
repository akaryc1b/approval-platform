package io.github.akaryc1b.approval.ai.openai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesCircuitConcurrencyAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T04:30:00Z");

    @Test
    void concurrentFailuresOpenExactlyAtTheSharedThreshold() throws Exception {
        OpenAiResponsesTransportControls.CircuitBreaker circuit = circuit(8);
        List<OpenAiResponsesTransportControls.CircuitBreaker.CircuitPermit> permits =
            new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            var permit = circuit.tryAcquire(NOW);
            assertTrue(permit.allowed());
            permits.add(permit);
        }
        CyclicBarrier start = new CyclicBarrier(permits.size());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (var permit : permits) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    circuit.record(
                        permit,
                        OpenAiResponsesTransportControls.Outcome.TRANSPORT_FAILURE,
                        NOW
                    );
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }

        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
            circuit.state()
        );
        assertEquals(9, circuit.generation());
    }

    @Test
    void concurrentOpenAdmissionsAreAllRejected() throws Exception {
        OpenAiResponsesTransportControls.CircuitBreaker circuit = circuit(1);
        var first = circuit.tryAcquire(NOW);
        circuit.record(
            first,
            OpenAiResponsesTransportControls.Outcome.TRANSPORT_FAILURE,
            NOW
        );

        List<Boolean> allowed = concurrent(
            32,
            () -> circuit.tryAcquire(NOW.plusSeconds(1)).allowed()
        );

        assertEquals(0, allowed.stream().filter(Boolean::booleanValue).count());
        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
            circuit.state()
        );
    }

    @Test
    void concurrentHalfOpenAdmissionsHaveExactlyOneProbeWinner() throws Exception {
        OpenAiResponsesTransportControls.CircuitBreaker circuit = circuit(1);
        var first = circuit.tryAcquire(NOW);
        circuit.record(
            first,
            OpenAiResponsesTransportControls.Outcome.TRANSPORT_FAILURE,
            NOW
        );

        List<OpenAiResponsesTransportControls.CircuitBreaker.CircuitPermit> permits =
            concurrent(
                32,
                () -> circuit.tryAcquire(NOW.plusSeconds(31))
            );
        List<OpenAiResponsesTransportControls.CircuitBreaker.CircuitPermit> winners =
            permits.stream().filter(
                OpenAiResponsesTransportControls.CircuitBreaker.CircuitPermit::allowed
            ).toList();

        assertEquals(1, winners.size());
        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.HALF_OPEN,
            winners.getFirst().stateBefore()
        );
        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.HALF_OPEN,
            circuit.state()
        );
        circuit.record(
            winners.getFirst(),
            OpenAiResponsesTransportControls.Outcome.SUCCESS,
            NOW.plusSeconds(31)
        );
        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED,
            circuit.state()
        );
    }

    @Test
    void concurrentSuccessAndFailureForOneProbeResolveOnlyOnce() throws Exception {
        OpenAiResponsesTransportControls.CircuitBreaker circuit = circuit(1);
        var first = circuit.tryAcquire(NOW);
        circuit.record(
            first,
            OpenAiResponsesTransportControls.Outcome.TRANSPORT_FAILURE,
            NOW
        );
        var probe = circuit.tryAcquire(NOW.plusSeconds(31));
        assertTrue(probe.allowed());
        long before = circuit.generation();
        CyclicBarrier start = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> success = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                circuit.record(
                    probe,
                    OpenAiResponsesTransportControls.Outcome.SUCCESS,
                    NOW.plusSeconds(31)
                );
                return null;
            });
            Future<?> failure = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                circuit.record(
                    probe,
                    OpenAiResponsesTransportControls.Outcome.UNKNOWN,
                    NOW.plusSeconds(31)
                );
                return null;
            });
            success.get(20, TimeUnit.SECONDS);
            failure.get(20, TimeUnit.SECONDS);
        }

        assertTrue(circuit.state()
            == OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED
            || circuit.state()
                == OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN);
        assertEquals(before + 1, circuit.generation());
        assertFalse(circuit.tryAcquire(NOW.plusSeconds(31)).stateBefore()
            == OpenAiResponsesTransportControls.CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void atomicObservationNeverSplicesPreAndPostTransitionPairs() throws Exception {
        OpenAiResponsesTransportControls.CircuitBreaker circuit = circuit(1);
        var permit = circuit.tryAcquire(NOW);
        CyclicBarrier start = new CyclicBarrier(33);
        List<Future<OpenAiResponsesCircuitObservation.Observation>> readers =
            new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> writer = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                circuit.record(
                    permit,
                    OpenAiResponsesTransportControls.Outcome.TRANSPORT_FAILURE,
                    NOW
                );
                return null;
            });
            for (int index = 0; index < 32; index++) {
                readers.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return OpenAiResponsesCircuitObservation.capture(circuit);
                }));
            }
            writer.get(20, TimeUnit.SECONDS);
            for (Future<OpenAiResponsesCircuitObservation.Observation> future : readers) {
                var observation = future.get(20, TimeUnit.SECONDS);
                boolean before = observation.state()
                    == OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED
                    && observation.generation() == 1;
                boolean after = observation.state()
                    == OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN
                    && observation.generation() == 2;
                assertTrue(before || after);
            }
        }

        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
            circuit.state()
        );
        assertEquals(2, circuit.generation());
    }

    private static <T> List<T> concurrent(
        int count,
        java.util.concurrent.Callable<T> operation
    ) throws Exception {
        CyclicBarrier start = new CyclicBarrier(count);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < count; index++) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return operation.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return List.copyOf(results);
        }
    }

    private static OpenAiResponsesTransportControls.CircuitBreaker circuit(int threshold) {
        return new OpenAiResponsesTransportControls.CircuitBreaker(
            threshold,
            Duration.ofSeconds(30)
        );
    }
}
