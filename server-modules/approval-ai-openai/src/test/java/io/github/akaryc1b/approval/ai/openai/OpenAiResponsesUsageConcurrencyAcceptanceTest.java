package io.github.akaryc1b.approval.ai.openai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    void sameTenantConcurrentDispatchStopsExactlyAtTheTenantAndGlobalBoundary()
        throws Exception {
        int limit = 8;
        OpenAiResponsesRuntimeUsageLedger ledger = ledger(limit, limit, 16);
        CountDownLatch ready = new CountDownLatch(24);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 24; index++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        ledger.recordDispatched(TENANT_HASH, windowStart(NOW), 100);
                        return true;
                    } catch (IllegalStateException expected) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(limit, successful(attempts));
        }

        var snapshot = ledger.snapshot(TENANT_HASH, NOW);
        assertEquals(limit, snapshot.committedRequests());
        assertEquals(limit * 100L, snapshot.committedUpperBoundMicros());
        assertEquals(0, snapshot.remainingRequests());
        assertTrue(snapshot.tenantSaturated());
        assertTrue(snapshot.globalSaturated());
    }

    @Test
    void multipleTenantsRaceForTheGlobalBoundaryWithoutLeakingExactGlobalUsage()
        throws Exception {
        int globalLimit = 12;
        OpenAiResponsesRuntimeUsageLedger ledger = ledger(10, globalLimit, 16);
        String tenantA = hash("tenant\nusage-concurrency-a");
        String tenantB = hash("tenant\nusage-concurrency-b");
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 20; index++) {
                String tenant = index % 2 == 0 ? tenantA : tenantB;
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        ledger.recordDispatched(tenant, windowStart(NOW), 100);
                        return true;
                    } catch (IllegalStateException expected) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(globalLimit, successful(attempts));
        }

        var first = ledger.snapshot(tenantA, NOW);
        var second = ledger.snapshot(tenantB, NOW);
        assertEquals(globalLimit, first.committedRequests() + second.committedRequests());
        assertTrue(first.globalSaturated());
        assertTrue(second.globalSaturated());
        assertFalse(first.actualProviderCost());
        assertFalse(second.actualProviderCost());
        assertFalse(first.durable());
        assertFalse(second.durable());
    }

    @Test
    void concurrentSnapshotsRemainCoherentWhileRecordsAreCommitted() throws Exception {
        int limit = 32;
        OpenAiResponsesRuntimeUsageLedger ledger = ledger(limit, limit, 8);
        CountDownLatch ready = new CountDownLatch(limit * 2L > Integer.MAX_VALUE
            ? Integer.MAX_VALUE : limit * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> work = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < limit; index++) {
                work.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    ledger.recordDispatched(TENANT_HASH, windowStart(NOW), 100);
                    return null;
                }));
                work.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    var snapshot = ledger.snapshot(TENANT_HASH, NOW);
                    assertTrue(snapshot.committedRequests() >= 0);
                    assertTrue(snapshot.committedRequests() <= limit);
                    assertEquals(
                        limit,
                        snapshot.committedRequests() + snapshot.remainingRequests()
                    );
                    assertTrue(
                        snapshot.committedUpperBoundMicros()
                            <= snapshot.derivedEnvelopeMicros()
                    );
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Void> result : work) {
                result.get();
            }
        }

        assertEquals(limit, ledger.snapshot(TENANT_HASH, NOW).committedRequests());
    }

    @Test
    void concurrentTenantCreationCannotExceedTheConfiguredCapacity() throws Exception {
        int capacity = 4;
        OpenAiResponsesRuntimeUsageLedger ledger = ledger(8, 8, capacity);
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 12; index++) {
                String tenant = hash("tenant\ncapacity-" + index);
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        ledger.recordDispatched(tenant, windowStart(NOW), 100);
                        return true;
                    } catch (IllegalStateException expected) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(capacity, successful(attempts));
        }
    }

    @Test
    void onlyTheNewestFourRateWindowsRemainTracked() {
        OpenAiResponsesRuntimeUsageLedger ledger = ledger(10, 10, 4);
        Instant firstWindow = windowStart(NOW);

        for (int index = 0; index < 5; index++) {
            ledger.recordDispatched(
                TENANT_HASH,
                firstWindow.plusSeconds(index * 60L),
                100
            );
        }

        assertEquals(0, ledger.snapshot(TENANT_HASH, firstWindow).committedRequests());
        for (int index = 1; index < 5; index++) {
            assertEquals(
                1,
                ledger.snapshot(
                    TENANT_HASH,
                    firstWindow.plusSeconds(index * 60L)
                ).committedRequests()
            );
        }
    }

    @Test
    void duplicateConcurrentMarkDispatchedCommitsUsageExactlyOnce() throws Exception {
        OpenAiResponsesP7FaultTestSupport.Fixture fixture = fixture(3, 10, 100, 100, 1_000_000);
        OpenAiResponsesTransportAdmission.Permit permit =
            fixture.admission().admit(request(), 100);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();

        try (permit; ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 2; index++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        permit.markDispatched(request());
                        return true;
                    } catch (OpenAiResponsesTransportException expected) {
                        assertEquals(
                            OpenAiResponsesTransportException.Failure.REQUEST_INVALID,
                            expected.failure()
                        );
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, successful(attempts));
            permit.record(OpenAiResponsesTransportControls.Outcome.SUCCESS);
        }

        assertEquals(1, fixture.usage().snapshot(TENANT_HASH, NOW).committedRequests());
    }

    @Test
    void delayedConcurrentDispatchRetainsTheOriginalAdmissionWindow() throws Exception {
        OpenAiResponsesP7FaultTestSupport.Fixture fixture = fixture(3, 10, 100, 100, 1_000_000);
        OpenAiResponsesTransportAdmission.Permit permit =
            fixture.admission().admit(request(), 100);
        fixture.clock().advance(Duration.ofSeconds(61));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();

        try (permit; ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 2; index++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        permit.markDispatched(request());
                        return true;
                    } catch (OpenAiResponsesTransportException expected) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, successful(attempts));
            permit.record(OpenAiResponsesTransportControls.Outcome.SUCCESS);
        }

        assertEquals(1, fixture.usage().snapshot(TENANT_HASH, NOW).committedRequests());
        assertEquals(
            0,
            fixture.usage().snapshot(TENANT_HASH, NOW.plusSeconds(61)).committedRequests()
        );
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

    private static long successful(List<Future<Boolean>> attempts) throws Exception {
        long successful = 0;
        for (Future<Boolean> attempt : attempts) {
            if (attempt.get()) {
                successful++;
            }
        }
        return successful;
    }

    private static Instant windowStart(Instant value) {
        long epoch = Math.floorDiv(value.getEpochSecond(), 60) * 60;
        return Instant.ofEpochSecond(epoch);
    }
}
