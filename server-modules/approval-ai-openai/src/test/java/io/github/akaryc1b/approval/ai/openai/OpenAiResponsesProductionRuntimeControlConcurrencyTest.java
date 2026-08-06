package io.github.akaryc1b.approval.ai.openai;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesProductionRuntimeControlConcurrencyTest {

    private static final Instant NOW = Instant.parse("2026-08-06T05:30:00Z");

    @Test
    void controlSnapshotNeverTearsCircuitStateFromItsGeneration() throws Exception {
        for (int iteration = 0; iteration < 32; iteration++) {
            OpenAiResponsesProductionRuntimeFactory factory = factory();
            OpenAiResponsesTransportControls.CircuitBreaker circuit = circuit(factory);
            var permit = circuit.tryAcquire(NOW);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<Void> transition = executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    circuit.record(
                        permit,
                        OpenAiResponsesTransportControls.Outcome.TRANSPORT_FAILURE,
                        NOW
                    );
                    return null;
                });
                Future<OpenAiResponsesProductionRuntimeFactory.RuntimeControlSnapshot> observation =
                    executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        return factory.controlSnapshot();
                    });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();

                transition.get();
                var snapshot = observation.get();
                boolean before = snapshot.circuitState()
                    == OpenAiResponsesTransportControls.CircuitBreaker.State.CLOSED
                    && snapshot.circuitGeneration() == 1;
                boolean after = snapshot.circuitState()
                    == OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN
                    && snapshot.circuitGeneration() == 2;
                assertTrue(before || after);
            }
            assertEquals(0, bindingCount(factory));
        }
    }

    @Test
    void concurrentControlSnapshotsRemainSideEffectFreeAndGenerationMonotonic()
        throws Exception {
        OpenAiResponsesProductionRuntimeFactory factory = factory();
        OpenAiResponsesTransportControls.CircuitBreaker circuit = circuit(factory);
        int transitions = 12;
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
                    return factory.controlSnapshot().circuitGeneration();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Void> record : records) {
                record.get();
            }
            for (Future<Long> observation : observations) {
                long generation = observation.get();
                assertTrue(generation >= 1);
                assertTrue(generation <= transitions + 1L);
            }
        }

        var finalSnapshot = factory.controlSnapshot();
        assertEquals(transitions + 1L, finalSnapshot.circuitGeneration());
        assertEquals(
            OpenAiResponsesTransportControls.CircuitBreaker.State.OPEN,
            finalSnapshot.circuitState()
        );
        assertEquals(0, bindingCount(factory));
    }

    private static OpenAiResponsesProductionRuntimeFactory factory() {
        return new OpenAiResponsesProductionRuntimeFactory(
            new OpenAiResponsesProductionRuntimeFactory.RuntimeProfile(
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
                100,
                100,
                Duration.ofSeconds(60),
                1,
                Duration.ofSeconds(30)
            ),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static OpenAiResponsesTransportControls.CircuitBreaker circuit(
        OpenAiResponsesProductionRuntimeFactory factory
    ) throws ReflectiveOperationException {
        Field field = OpenAiResponsesProductionRuntimeFactory.class
            .getDeclaredField("circuitBreaker");
        field.setAccessible(true);
        return (OpenAiResponsesTransportControls.CircuitBreaker) field.get(factory);
    }

    private static int bindingCount(OpenAiResponsesProductionRuntimeFactory factory)
        throws ReflectiveOperationException {
        Field field = OpenAiResponsesProductionRuntimeFactory.class.getDeclaredField("bindings");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(factory)).size();
    }
}
