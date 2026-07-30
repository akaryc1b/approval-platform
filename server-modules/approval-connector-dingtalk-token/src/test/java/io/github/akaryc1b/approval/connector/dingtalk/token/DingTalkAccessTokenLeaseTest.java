package io.github.akaryc1b.approval.connector.dingtalk.token;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingTalkAccessTokenLeaseTest {

    @Test
    void ownershipAndEveryScopedCopyAreZeroized() {
        byte[] owned = "fixture-token".getBytes();
        AtomicReference<byte[]> captured = new AtomicReference<>();
        DingTalkAccessTokenLease lease = lease(owned);

        assertTrue(DingTalkTokenTestFixtures.allZero(owned));
        lease.use(material -> {
            captured.set(material);
            assertFalse(DingTalkTokenTestFixtures.allZero(material));
        });
        assertTrue(DingTalkTokenTestFixtures.allZero(captured.get()));
        lease.close();
        assertTrue(lease.closed());
    }

    @Test
    void callbackFailureStillZeroizesAndDuplicateCloseIsSafe() {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        DingTalkAccessTokenLease lease = lease("fixture-token".getBytes());

        assertThrows(IllegalArgumentException.class, () -> lease.use(material -> {
            captured.set(material);
            throw new IllegalArgumentException("caller failure");
        }));
        assertTrue(DingTalkTokenTestFixtures.allZero(captured.get()));
        lease.close();
        lease.close();
        DingTalkTokenLifecycleException problem = assertThrows(
            DingTalkTokenLifecycleException.class,
            () -> lease.use(material -> { })
        );
        assertEquals(DingTalkTokenFailure.LEASE_CLOSED, problem.failure());
    }

    @Test
    void concurrentUseIsRejected() throws Exception {
        DingTalkAccessTokenLease lease = lease("fixture-token".getBytes());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> workerProblem = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                lease.use(material -> {
                    entered.countDown();
                    await(release);
                });
            } catch (Throwable problem) {
                workerProblem.set(problem);
            }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        DingTalkTokenLifecycleException problem = assertThrows(
            DingTalkTokenLifecycleException.class,
            () -> lease.use(material -> { })
        );
        assertEquals(DingTalkTokenFailure.CONCURRENT_ACCESS_REJECTED, problem.failure());
        release.countDown();
        worker.join(5_000);
        assertTrue(workerProblem.get() == null);
        lease.close();
    }

    @Test
    void closeDuringUseDefersZeroizationAndPreventsAnotherUse() throws Exception {
        DingTalkAccessTokenLease lease = lease("fixture-token".getBytes());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = Thread.ofPlatform().start(() -> lease.use(material -> {
            entered.countDown();
            await(release);
        }));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        lease.close();
        assertFalse(lease.active());
        assertThrows(
            DingTalkTokenLifecycleException.class,
            () -> lease.use(material -> { })
        );
        release.countDown();
        worker.join(5_000);
        assertTrue(lease.closed());
    }

    private static DingTalkAccessTokenLease lease(byte[] material) {
        DingTalkTokenEvidence evidence = new DingTalkTokenEvidence(
            DingTalkTokenOutcome.ACQUIRED,
            DingTalkTokenFailure.NONE,
            DingTalkTokenTestFixtures.hash("request"),
            DingTalkTokenTestFixtures.hash("route"),
            DingTalkTokenTestFixtures.hash("credential"),
            DingTalkTokenTestFixtures.hash("token-version"),
            Instant.parse("2026-07-29T00:00:00Z"),
            Instant.parse("2026-07-29T00:00:20Z"),
            Instant.parse("2026-07-29T00:00:30Z"),
            true,
            true,
            true,
            1,
            DingTalkTokenTestFixtures.hash("evidence")
        );
        return DingTalkAccessTokenLease.takeOwnership(evidence, material);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timeout");
            }
        } catch (InterruptedException problem) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", problem);
        }
    }
}
