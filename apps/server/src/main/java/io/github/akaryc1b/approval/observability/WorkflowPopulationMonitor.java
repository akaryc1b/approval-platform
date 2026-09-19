package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.application.port.ApprovalWorkflowPopulationReader;
import io.github.akaryc1b.approval.application.port.ApprovalWorkflowPopulationReader.Snapshot;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.ToDoubleFunction;

/** Owned, bounded background reader. Scraping only inspects the immutable cached observation. */
public final class WorkflowPopulationMonitor implements AutoCloseable {
    private final ApprovalWorkflowPopulationReader reader;
    private final Runnable closeReader;
    private final LongSupplier nanoTime;
    private final long intervalMillis;
    private final long staleNanos;
    private final AtomicBoolean sampling = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong errors = new AtomicLong();
    private volatile Observation observation;
    private ScheduledExecutorService executor;

    public WorkflowPopulationMonitor(ApprovalWorkflowPopulationReader reader, Duration interval, Runnable closeReader) {
        this(reader, interval, closeReader, System::nanoTime);
    }

    WorkflowPopulationMonitor(ApprovalWorkflowPopulationReader reader, Duration interval,
                              Runnable closeReader, LongSupplier nanoTime) {
        this.reader = Objects.requireNonNull(reader, "reader must not be null");
        this.closeReader = Objects.requireNonNull(closeReader, "closeReader must not be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
        Objects.requireNonNull(interval, "interval must not be null");
        if (interval.compareTo(Duration.ofSeconds(5)) < 0 || interval.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Workflow observation interval must be between 5s and 5m");
        }
        intervalMillis = interval.toMillis();
        staleNanos = interval.multipliedBy(2).toNanos();
    }

    public synchronized void start() {
        if (closed.get() || executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(action -> {
            Thread thread = new Thread(action, "approval-workflow-observation");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::refresh, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void refresh() {
        if (closed.get() || !sampling.compareAndSet(false, true)) return;
        long started = nanoTime.getAsLong();
        try {
            Snapshot snapshot = Objects.requireNonNull(reader.read(), "snapshot must not be null");
            if (!closed.get()) observation = new Observation(snapshot, started, true);
        } catch (RuntimeException unavailable) {
            errors.incrementAndGet();
            Observation previous = observation;
            if (previous != null) observation = new Observation(previous.snapshot(), previous.startedNanos(), false);
            // No query, credentials, exception text or business identifiers enter logs or tags.
        } finally {
            sampling.set(false);
        }
    }

    private boolean fresh(Observation current) {
        return !closed.get() && current != null && current.successful() && elapsed(current) < staleNanos;
    }

    private long elapsed(Observation current) {
        return Math.max(0, nanoTime.getAsLong() - current.startedNanos());
    }

    public double value(ToDoubleFunction<Snapshot> field) {
        Observation current = observation;
        return fresh(current) ? field.applyAsDouble(current.snapshot()) : Double.NaN;
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
        if (executor != null) executor.shutdownNow();
        try {
            closeReader.run();
        } finally {
            if (executor != null) {
                try { executor.awaitTermination(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private record Observation(Snapshot snapshot, long startedNanos, boolean successful) { }
}
