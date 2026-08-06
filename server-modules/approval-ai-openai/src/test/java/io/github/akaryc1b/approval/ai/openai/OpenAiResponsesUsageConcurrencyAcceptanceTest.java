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
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.NOW;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.TENANT_HASH;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.fixture;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesP7FaultTestSupport.hash;
import static io.github.akaryc1b.approval.ai.openai.OpenAiResponsesSecureHttpSenderTestSupport.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesUsageConcurrencyAcceptanceTest {

    @Test
    void sameTenantConcurrentDispatchNeverExceedsTenantLimit() throws Exception {
        OpenAiResponsesRuntimeUsageLedger ledger = ledger(10, 20, 10);
        List<Boolean> results = concurrent(32, () -> record(ledger, TENANT_HASH, NOW, 100));

        assertEquals(10, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(22, results.stream().filter(value -> !value).count());
        var snapshot = ledger.snapshot(TENANT_HASH, NOW);
        assertEquals(10, snapshot.committedRequests());
        assertEquals(1_000, snapshot.committedUpperBoundMicros());
        assertTrue(snapshot.tenantSaturated());
        assertFalse(snapshot.globalSaturated());
    }

    @Test
    void multipleTenantsConcurrentDispatchNeverExceedsGlobalLimit() throws Exception {
        OpenAiResponsesRuntimeUsageLedger ledger = ledger(10, 12, 10);
        List<String> tenants = List.of(
            hash("tenant\np7-a"),
            hash("tenant\np7-b"),
            hash("tenant\np7-c"),
            hash("tenant\np7-d")
        );
        List<java.util.concurrent.Callable<Boolean>> calls = new ArrayList<>();
        for (String tenant : tenants) {
            for (int index = 0; index < 8; index++) {
                calls.add(() -> record(ledger, tenant, NOW, 100));
            }
        }
        List<Boolean> results = concurrentCalls(calls);

        assertEquals(12, results.stream().filter(Boolean::booleanValue).count());
        long total = tenants.stream()
            .map(tenant -> ledger.snapshot(tenant, NOW))
            .peek(snapshot -> assertTrue(snapshot.globalSaturated()))
            .mapToLong(OpenAiResponsesRuntimeUsageLedger.UsageSnapshot::committedRequests)
            .sum();
        assertEquals(12, total);
    }

    @Test
    void snapshotAndRecordPhasesNeverExposeTornCountOrCost() throws Exception {
        OpenAiResponsesRuntimeUsageLedger ledger = ledger(20, 20, 10);
        CyclicBarrier beforeRecord = new CyclicBarrier(2);
        CyclicBarrier afterRecord = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> writer = executor.submit(() -> {
                for (int index = 1; index <= 20; index++) {
                    beforeRecord.await(10, TimeUnit.SECONDS);
                    ledger.recordDispatched(TENANT_HASH, NOW, 100);
                    afterRecord.await(10, TimeUnit.SECONDS);
                }
                return null;
            });
            Future<?> reader = executor.submit(() -> {
                for (int expected = 1; expected <= 20; expected++) {
                    beforeRecord.await(10, TimeUnit.SECONDS);
                    afterRecord.await(10, TimeUnit.SECONDS);
                    var snapshot = ledger.snapshot(TENANT_HASH, NOW);
                    assertEquals(expected, snapshot.committedRequests());
                    assertEquals(expected * 100L, snapshot.committedUpperBoundMicros());
                    assertTrue(snapshot.committedUpperBoundMicros()
                        <= snapshot.derivedEnvelopeMicros());
                }
                return null;
            });
            writer.get(30, TimeUnit.SECONDS);
            reader.get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void fiveWindowsRetainOnlyTheLatestFourBoundedWindows() {
        OpenAiResponsesRuntimeUsageLedger ledger = ledger(10, 100, 10);
        for (int index = 0; index < 5; index++) {
            ledger.recordDispatched(
                TENANT_HASH,
                NOW.plusSeconds(index * 60L),
                100
            );
        }

        assertEquals(0, ledger.snapshot(TENANT_HASH, NOW).committedRequests());
        for (int index = 1; index < 5; index++) {
            assertEquals(
                1,
                ledger.snapshot(
                    TENANT_HASH,
                    NOW.plusSeconds(index * 60L)
                ).committedRequests()
            );
        }
    }

    @Test
    void duplicateConcurrentMarkDispatchedCommitsUsageExactlyOnce() throws Exception {
        OpenAiResponsesP7FaultTestSupport.Fixture fixture = fixture(
            3,
            2,
            2,
            10,
            1_000_000
        );
        OpenAiResponsesTransportAdmission.Permit permit =
            fixture.admission().admit(request(), 100);
        CyclicBarrier start = new CyclicBarrier(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try (permit; ExecutorService executor =
                 Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = List.of(
                executor.submit(() -> mark(permit, start, successes, rejected)),
                executor.submit(() -> mark(permit, start, successes, rejected))
            );
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
            permit.record(OpenAiResponsesTransportControls.Outcome.SUCCESS);
        }

        assertEquals(1, successes.get());
        assertEquals(1, rejected.get());
        assertEquals(
            1,
            fixture.usage().snapshot(TENANT_HASH, NOW).committedRequests()
        );
    }

    private static void mark(
        OpenAiResponsesTransportAdmission.Permit permit,
        CyclicBarrier start,
        AtomicInteger successes,
        AtomicInteger rejected
    ) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            permit.markDispatched(request());
            successes.incrementAndGet();
        } catch (OpenAiResponsesTransportException expected) {
            assertEquals(
                OpenAiResponsesTransportException.Failure.REQUEST_INVALID,
                expected.failure()
            );
            rejected.incrementAndGet();
        }
    }

    private static boolean record(
        OpenAiResponsesRuntimeUsageLedger ledger,
        String tenant,
        Instant windowStart,
        long cost
    ) {
        try {
            ledger.recordDispatched(tenant, windowStart, cost);
            return true;
        } catch (IllegalStateException saturated) {
            return false;
        }
    }

    private static List<Boolean> concurrent(
        int count,
        java.util.concurrent.Callable<Boolean> operation
    ) throws Exception {
        List<java.util.concurrent.Callable<Boolean>> calls = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            calls.add(operation);
        }
        return concurrentCalls(calls);
    }

    private static List<Boolean> concurrentCalls(
        List<java.util.concurrent.Callable<Boolean>> operations
    ) throws Exception {
        CyclicBarrier start = new CyclicBarrier(operations.size());
        List<Future<Boolean>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (java.util.concurrent.Callable<Boolean> operation : operations) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return operation.call();
                }));
            }
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return List.copyOf(results);
        }
    }

    private static OpenAiResponsesRuntimeUsageLedger ledger(
        int tenantLimit,
        int globalLimit,
        int maximumTenants
    ) {
        return new OpenAiResponsesRuntimeUsageLedger(
            tenantLimit,
            globalLimit,
            maximumTenants,
            Duration.ofMinutes(1),
            1_000
        );
    }
}
