package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.engine.EngineJobPopulationReader.Snapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;

/** The same dependency-free behavior checks run locally and through the ordinary JUnit suite. */
public final class EngineJobPopulationMonitorChecks {
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final Snapshot BUSY = new Snapshot(NOW, 3, 4, 5, 6);
    private static final Snapshot EMPTY = new Snapshot(NOW, 0, 0, 0, 0);
    private static final List<ToLongFunction<Snapshot>> FIELDS = List.of(
        Snapshot::executable, Snapshot::timer, Snapshot::suspended, Snapshot::deadLetter);
    private EngineJobPopulationMonitorChecks() { }

    public static void main(String[] args) throws Exception {
        runAll();
        System.out.println("ENGINE_JOB_MONITOR_BEHAVIOR_PASSED");
    }

    public static void runAll() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicLong time = new AtomicLong();
        AtomicReference<Snapshot> next = new AtomicReference<>(BUSY);
        try (var monitor = new EngineJobPopulationMonitor(() -> {
            reads.incrementAndGet(); return next.get();
        }, Duration.ofSeconds(5), time::get)) {
            unknown(monitor); check(reads.get() == 0, "scrapes do not read engine");
            check(Double.isNaN(monitor.timestampSeconds()), "initial timestamp unknown");
            monitor.refresh();
            for (int i = 0; i < FIELDS.size(); i++) check(monitor.value(FIELDS.get(i)) == i + 3, "queue " + i);
            for (int i = 0; i < 100; i++) monitor.value(Snapshot::deadLetter);
            check(reads.get() == 1, "scrapes stay cached");
            time.set(Duration.ofSeconds(9).toNanos()); check(monitor.up() == 1, "before stale boundary");
            time.set(Duration.ofSeconds(10).toNanos()); unknown(monitor);
            check(monitor.ageSeconds() == 10, "age is monotonic");
            next.set(null); monitor.refresh(); unknown(monitor);
            check(monitor.errors() == 1, "null sample is failed");
            check(monitor.timestampSeconds() == NOW.toEpochMilli() / 1000.0, "retain last timestamp");
            next.set(EMPTY); monitor.refresh(); check(monitor.up() == 1, "recovered");
            FIELDS.forEach(field -> check(monitor.value(field) == 0, "genuine empty queue"));
            monitor.close(); monitor.close(); monitor.refresh(); monitor.start(); unknown(monitor);
            check(reads.get() == 3, "closed poller cannot restart or read");
        }
        try (var monitor = new EngineJobPopulationMonitor(() -> {
            throw new IllegalStateException("do not export sensitive detail");
        }, Duration.ofSeconds(5))) {
            monitor.refresh(); unknown(monitor); check(monitor.errors() == 1, "first read failure");
        }
        time.set(0);
        try (var monitor = new EngineJobPopulationMonitor(() -> {
            time.set(Duration.ofSeconds(11).toNanos()); return BUSY;
        }, Duration.ofSeconds(5), time::get)) {
            monitor.refresh(); unknown(monitor); check(monitor.ageSeconds() == 11, "include whole read duration");
        }
        AtomicInteger attempts = new AtomicInteger();
        try (var monitor = new EngineJobPopulationMonitor(() -> {
            if (attempts.incrementAndGet() == 2) throw new IllegalStateException("partial query failure");
            return BUSY;
        }, Duration.ofSeconds(5))) {
            monitor.refresh(); monitor.refresh(); unknown(monitor);
            monitor.refresh(); check(monitor.up() == 1 && monitor.errors() == 1, "failure then recovery");
        }
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        reads.set(0);
        try (var executor = Executors.newSingleThreadExecutor();
            var monitor = new EngineJobPopulationMonitor(() -> {
                reads.incrementAndGet(); entered.countDown(); await(release); return BUSY;
            }, Duration.ofSeconds(5))) {
            var future = executor.submit(monitor::refresh);
            check(entered.await(5, TimeUnit.SECONDS), "reader entered");
            monitor.refresh(); check(reads.get() == 1, "never overlap reads"); unknown(monitor);
            monitor.close(); release.countDown(); future.get(5, TimeUnit.SECONDS); unknown(monitor);
            check(reads.get() == 1, "late result fenced");
        } finally { release.countDown(); }
        CountDownLatch sampled = new CountDownLatch(1); reads.set(0);
        AtomicReference<Thread> thread = new AtomicReference<>();
        try (var monitor = new EngineJobPopulationMonitor(() -> {
            reads.incrementAndGet(); thread.set(Thread.currentThread()); sampled.countDown(); return EMPTY;
        }, Duration.ofSeconds(5))) {
            monitor.start(); monitor.start(); check(sampled.await(5, TimeUnit.SECONDS), "background sampler ran");
            monitor.close(); check(reads.get() == 1, "start once"); unknown(monitor);
            thread.get().join(1000);
            check(thread.get().isDaemon() && !thread.get().isAlive(), "owned thread stopped");
        }
        for (Duration interval : List.of(Duration.ZERO, Duration.ofSeconds(4), Duration.ofMinutes(6))) {
            rejects(() -> new EngineJobPopulationMonitor(() -> EMPTY, interval));
        }
        for (int i = 0; i < 4; i++) {
            long[] values = {0, 0, 0, 0}; values[i] = -1;
            rejects(() -> new Snapshot(NOW, values[0], values[1], values[2], values[3]));
        }
    }
    private static void unknown(EngineJobPopulationMonitor monitor) {
        check(monitor.up() == 0, "unavailable sample");
        FIELDS.forEach(field -> check(Double.isNaN(monitor.value(field)), "unknown is not zero"));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid value accepted");
    }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "bounded wait"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
