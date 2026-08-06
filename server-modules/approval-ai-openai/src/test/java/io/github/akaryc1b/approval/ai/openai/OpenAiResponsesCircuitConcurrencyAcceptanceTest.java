package io.github.akaryc1b.approval.ai.openai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesCircuitConcurrencyAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T05:00:00Z");

    @Test
    void concurrentFailuresReachTheThresholdWithoutLostGeneration() throws Exception {
        int threshold = 8;
        OpenAiResponsesTransportControls.CircuitBreaker circuit = circuit(threshold);
        List<OpenAiResponsesTransportControls.CircuitBreaker.CircuitPermit> permits =
            new ArrayList<>();
        for (int index = 0; index < threshold; index++) {
            var permit = circuit.tryAcquire(NOW);
            assertTrue(permit.allowed());
            permits.add(permit);
        }
        long initialGeneration = circuit.generation();
        CountDownLatch ready = new CountDownLatch(threshold);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var permit : permits) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    circuit.record(
                        permit,
                        OpenAiResponsesTransportControls.Outcome.TRANSPORT_FAILURE,
                        NOW
                    );
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Void> result : results) {
                result.get();
            }
        }

        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
            circuit.state()
        );
        assertEquals(initialGeneration + threshold, circuit.generation());
    }

    @Test
    void concurrentOpenAdmissionsAreAllRejectedWithoutChangingGeneration()
        throws Exception {
        OpenAiResponsesTransportControls.CircuitBreaker circuit = openCircuit();
        long generation = circuit.generation();
        CountDownLatch ready = new CountDownLatch(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 32; index++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return circuit.tryAcquire(NOW.plusSeconds(1)).allowed();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Boolean> attempt : attempts) {
                assertFalse(attempt.get());
            }
        }

        assertEquals(generation, circuit.generation());
        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
            circuit.state()
        );
    }

    @Test
    void halfOpenWindowAllowsExactlyOneConcurrentProbe() throws Exception {
        OpenAiResponsesTransportControls.CircuitBreaker circuit = openCircuit();
        CountDownLatch ready = new CountDownLatch(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OpenAiResponsesTransportControls.CircuitBreaker.CircuitPermit>> attempts =
            new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 32; index++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return circuit.tryAcquire(NOW.plusSeconds(31));
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<OpenAiResponsesTransportControls.CircuitBreaker.CircuitPermit> allowed =
                new ArrayList<>();
            for (var attempt : attempts) {
                var permit = attempt.get();
                if (permit.allowed()) {
                    allowed.add(permit);
                }
            }
            assertEquals(1, allowed.size());
            assertEquals(
                OpenAiResponsesTransportControls.CircuitBreaker.State.HALF_OPEN,
                allowed.getFirst().stateBefore()
            );
            assertEquals(
                OpenAiResponsesTransportControls.CircuitBreaker.State.HALF_OPEN,
                circuit.state()
            );
            circuit.abandon(allowed.getFirst());
        }

        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
            circuit.state()
        );
    }

    @Test
    void halfOpenSuccessAndFailureRaceAcceptsOnlyOneTerminalProbeOutcome()
        throws Exception {
        OpenAiResponsesTransportControls.CircuitBreaker circuit = openCircuit();
        var probe = circuit.tryAcquire(NOW.plusSeconds(31));
        assertTrue(probe.allowed());
        long generation = circuit.generation();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OpenAiResponsesTransportControls.Outcome>> attempts = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var outcome : List.of(
                OpenAiResponsesTransportControls.Outcome.SUCCESS,
                OpenAiResponsesTransportControls.Outcome.UNKNOWN
            )) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        circuit.record(probe, outcome, NOW.plusSeconds(31));
                        return outcome;
                    } catch (OpenAiResponsesTransportException expected) {
                        assertEquals(
                            OpenAiResponsesTransportException.Failure.REQUEST_INVALID,
                            expected.failure()
                        );
                        return null;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<OpenAiResponsesTransportControls.Outcome> accepted = attempts.stream()
                .map(OpenAiResponsesCircuitConcurrencyAcceptanceTest::get)
                .filter(Objects::nonNull)
                .toList();
            assertEquals(1, accepted.size());
            assertEquals(generation + 1, circuit.generation());
            assertEquals(
                accepted.getFirst() == OpenAiResponsesTransportControls.Outcome.SUCCESS
                    ? OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED
                    : OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
                circuit.state()
            );
        }
    }

    @Test
    void concurrentStateObservationsNeverDecreaseGeneration() throws Exception {
        int transitions = 16;
        OpenAiResponsesTransportControls.CircuitBreaker circuit = circuit(transitions);
        List<OpenAiResponsesTransportControls.CircuitBreaker.CircuitPermit> permits =
            new ArrayList<>();
        for (int index = 0; index < transitions; index++) {
            permits.add(circuit.tryAcquire(NOW));
        }
        CountDownLatch ready = new CountDownLatch(transitions * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> observations = new ArrayList<>();
        List<Future<Void>> records = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var permit : permits) {
                records.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    circuit.record(
                        permit,
                        OpenAiResponsesTransportControls.Outcome.TRANSPORT_FAILURE,
                        NOW
                    );
                    return null;
                }));
                observations.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    circuit.state();
                    return circuit.generation();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Void> record : records) {
                record.get();
            }
            for (Future<Long> observation : observations) {
                long value = observation.get();
                assertTrue(value >= 1);
                assertTrue(value <= transitions + 1L);
            }
        }

        assertEquals(transitions + 1L, circuit.generation());
        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
            circuit.state()
        );
    }

    private static OpenAiResponsesTransportControls.CircuitBreaker openCircuit() {
        OpenAiResponsesTransportControls.CircuitBreaker circuit = circuit(1);
        var permit = circuit.tryAcquire(NOW);
        circuit.record(
            permit,
            OpenAiResponsesTransportControls.Outcome.TRANSPORT_FAILURE,
            NOW
        );
        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
            circuit.state()
        );
        return circuit;
    }

    private static OpenAiResponsesTransportControls.CircuitBreaker circuit(int threshold) {
        return new OpenAiResponsesTransportControls.CircuitBreaker(
            threshold,
            Duration.ofSeconds(30)
        );
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
