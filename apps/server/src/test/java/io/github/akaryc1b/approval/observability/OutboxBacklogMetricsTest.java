package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.integration.outbox.OutboxBacklogReader.Snapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxBacklogMetricsTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final Snapshot BUSY = new Snapshot(NOW, 3, 2, 2, 1, 4, 120.0d);
    private static final Snapshot EMPTY = new Snapshot(NOW, 0, 0, 0, 0, 0, 0.0d);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void unknownIsNotZeroAndScrapesNeverReadTheDatabase() {
        AtomicInteger reads = new AtomicInteger();
        try (var metrics = new OutboxBacklogMetrics(
            () -> { reads.incrementAndGet(); return EMPTY; }, registry, Duration.ofSeconds(5), () -> { })) {
            assertEquals(0.0d, value(registry, "sample.up"));
            assertTrue(Double.isNaN(value(registry, "pending")));
            assertTrue(Double.isNaN(value(registry, "sample.timestamp")));
            for (int index = 0; index < 20; index++) {
                value(registry, "pending");
            }
            assertEquals(0, reads.get());
            metrics.refresh();
            assertEquals(1, reads.get());
            assertEquals(0.0d, value(registry, "pending"));
            assertEquals(1.0d, value(registry, "sample.up"));
        }
    }

    @Test
    void exportsExactIndependentAggregatesWithoutIdentityLabels() {
        try (var metrics = new OutboxBacklogMetrics(
            () -> BUSY, registry, Duration.ofSeconds(5), () -> { })) {
            metrics.refresh();
            assertEquals(3.0d, value(registry, "pending"));
            assertEquals(2.0d, value(registry, "due"));
            assertEquals(2.0d, value(registry, "in.flight"));
            assertEquals(1.0d, value(registry, "expired.leases"));
            assertEquals(4.0d, value(registry, "dead"));
            assertEquals(120.0d, value(registry, "oldest.unfinished.age"));
            registry.getMeters().forEach(meter -> assertTrue(meter.getId().getTags().isEmpty()));
        }
    }

    @Test
    void failureInvalidatesInsteadOfZeroingAndRecoveryReplacesTheSnapshot() {
        AtomicReference<Snapshot> next = new AtomicReference<>(BUSY);
        try (var metrics = new OutboxBacklogMetrics(
            () -> { if (next.get() == null) throw new IllegalStateException("sensitive fixture text");
                return next.get(); }, registry, Duration.ofSeconds(5), () -> { })) {
            metrics.refresh();
            double timestamp = value(registry, "sample.timestamp");
            next.set(null);
            metrics.refresh();
            assertEquals(0.0d, value(registry, "sample.up"));
            assertTrue(Double.isNaN(value(registry, "pending")));
            assertEquals(timestamp, value(registry, "sample.timestamp"));
            assertEquals(1.0d, registry.get("approval.outbox.sample.errors").functionCounter().count());
            next.set(EMPTY);
            metrics.refresh();
            assertEquals(1.0d, value(registry, "sample.up"));
            assertEquals(0.0d, value(registry, "pending"));
            assertEquals(1.0d, registry.get("approval.outbox.sample.errors").functionCounter().count());
        }
    }

    @Test
    void freshnessUsesMonotonicTimeFromReadStartAndExpiresAtTheBoundary() {
        AtomicLong time = new AtomicLong(0);
        try (var metrics = new OutboxBacklogMetrics(
            () -> BUSY, registry, Duration.ofSeconds(5), () -> { }, time::get)) {
            metrics.refresh();
            time.set(Duration.ofSeconds(9).toNanos());
            assertEquals(1.0d, value(registry, "sample.up"));
            time.set(Duration.ofSeconds(10).toNanos());
            assertEquals(10.0d, value(registry, "sample.age"));
            assertEquals(0.0d, value(registry, "sample.up"));
            assertTrue(Double.isNaN(value(registry, "dead")));
        }
    }

    @Test
    void anOverlongReadCannotManufactureFreshData() {
        AtomicLong time = new AtomicLong(0);
        try (var metrics = new OutboxBacklogMetrics(
            () -> { time.set(Duration.ofSeconds(11).toNanos()); return BUSY; },
            registry, Duration.ofSeconds(5), () -> { }, time::get)) {
            metrics.refresh();
            assertEquals(0.0d, value(registry, "sample.up"));
            assertTrue(Double.isNaN(value(registry, "pending")));
        }
    }

    @Test
    void overlappingCallsDoNotStartAnotherReadAndCloseFencesLateResults() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor();
            var metrics = new OutboxBacklogMetrics(() -> {
                reads.incrementAndGet(); entered.countDown(); await(release); return BUSY;
            }, registry, Duration.ofSeconds(5), () -> { closes.incrementAndGet(); release.countDown(); })) {
            var running = executor.submit(metrics::refresh);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            metrics.refresh();
            assertEquals(1, reads.get());
            metrics.close();
            running.get(5, TimeUnit.SECONDS);
            metrics.refresh();
            metrics.close();
            assertEquals(1, reads.get());
            assertEquals(1, closes.get());
            assertEquals(0.0d, value(registry, "sample.up"));
            assertTrue(Double.isNaN(value(registry, "pending")));
        } finally {
            release.countDown();
        }
    }

    @Test
    void actualBackgroundWorkerStartsOnceAndIsClosed() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        try (var metrics = new OutboxBacklogMetrics(
            () -> { reads.incrementAndGet(); observed.countDown(); return EMPTY; },
            registry, Duration.ofSeconds(5), () -> { })) {
            metrics.start();
            metrics.start();
            assertTrue(observed.await(5, TimeUnit.SECONDS));
            metrics.close();
            metrics.start();
            assertEquals(1, reads.get());
            assertEquals(0.0d, value(registry, "sample.up"));
        }
    }

    @Test
    void rejectsInvalidIntervalsAndInconsistentAggregates() {
        for (Duration interval : new Duration[] { Duration.ZERO, Duration.ofSeconds(4), Duration.ofMinutes(6) }) {
            assertThrows(IllegalArgumentException.class, () -> new OutboxBacklogMetrics(
                () -> EMPTY, registry, interval, () -> { }));
        }
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(NOW, -1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(NOW, 1, 2, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(NOW, 0, 0, 1, 2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(NOW, 1, 1, 0, 0, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(NOW, 0, 0, 0, 0, 1, 1));
    }

    private static double value(SimpleMeterRegistry registry, String suffix) {
        return registry.get("approval.outbox." + suffix).gauge().value();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("fixture wait exceeded");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fixture interrupted");
        }
    }
}
