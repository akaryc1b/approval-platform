package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.engine.EngineJobPopulationReader;
import io.github.akaryc1b.approval.engine.EngineJobPopulationReader.Snapshot;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/** Single owned poller; HTTP scrapes never call the engine or wait for an in-flight read. */
public final class EngineJobPopulationMonitor implements AutoCloseable {
    private final EngineJobPopulationReader reader;
    private final LongSupplier nanoTime;
    private final long intervalMillis;
    private final long staleNanos;
    private final AtomicBoolean sampling = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong errors = new AtomicLong();
    private volatile Observation observation;
    private ScheduledExecutorService executor;

    public EngineJobPopulationMonitor(EngineJobPopulationReader reader, Duration interval) {
        this(reader, interval, System::nanoTime);
    }

    EngineJobPopulationMonitor(EngineJobPopulationReader reader, Duration interval, LongSupplier nanoTime) {
        this.reader = Objects.requireNonNull(reader, "reader must not be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
        Objects.requireNonNull(interval, "interval must not be null");
        if (interval.compareTo(Duration.ofSeconds(5)) < 0 || interval.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Engine job observation interval must be between 5s and 5m");
        }
        intervalMillis = interval.toMillis();
        staleNanos = interval.multipliedBy(2).toNanos();
    }

    public synchronized void start() {
        if (closed.get() || executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(action -> {
            Thread thread = new Thread(action, "approval-engine-job-observation");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::refresh, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void refresh() {
        if (closed.get() || !sampling.compareAndSet(false, true)) return;
        try {
            long started = nanoTime.getAsLong();
            Snapshot snapshot = Objects.requireNonNull(reader.read(), "snapshot must not be null");
            if (!closed.get()) observation = new Observation(snapshot, started, true);
        } catch (RuntimeException unavailable) {
            errors.incrementAndGet();
            Observation previous = observation;
            if (previous != null) observation = new Observation(previous.snapshot(), previous.startedNanos(), false);
            // No engine exception, SQL, tenant, job or process identity is logged or tagged.
        } finally {
            sampling.set(false);
        }
    }

    private long elapsed(Observation current) {
        return Math.max(0, nanoTime.getAsLong() - current.startedNanos());
    }

    private boolean fresh(Observation current) {
        return !closed.get() && current != null && current.successful() && elapsed(current) < staleNanos;
    }

    public double value(ToLongFunction<Snapshot> field) {
        Observation current = observation;
        return fresh(current) ? field.applyAsLong(current.snapshot()) : Double.NaN;
    }
    public double up() { return fresh(observation) ? 1.0d : 0.0d; }
    public double errors() { return errors.doubleValue(); }
    public double ageSeconds() {
        Observation current = observation;
        return current == null ? Double.NaN : elapsed(current) / 1_000_000_000.0d;
    }
    public double timestampSeconds() {
        Observation current = observation;
        return current == null ? Double.NaN : current.snapshot().observedAt().toEpochMilli() / 1000.0d;
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (executor == null) return;
        executor.shutdownNow();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        // Never close the shared engine or its pool. An uninterruptible JDBC call may outlive
        // this bounded wait; the closed fence prevents it from publishing a late sample.
    }

    private record Observation(Snapshot snapshot, long startedNanos, boolean successful) { }
}
