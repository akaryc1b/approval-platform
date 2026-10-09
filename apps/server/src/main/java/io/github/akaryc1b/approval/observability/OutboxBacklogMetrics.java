package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.integration.outbox.OutboxBacklogReader;
import io.github.akaryc1b.approval.integration.outbox.OutboxBacklogReader.Snapshot;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.ToDoubleFunction;

/** Scrapes read cached aggregates only. Failed, stopped or stale observations are never zero. */
public final class OutboxBacklogMetrics implements ApplicationListener<ApplicationReadyEvent>, AutoCloseable {

    private final OutboxBacklogReader reader;
    private final Runnable closeReader;
    private final long intervalMillis;
    private final long staleNanos;
    private final LongSupplier nanoTime;
    private final AtomicBoolean sampling = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong errors = new AtomicLong();
    private volatile Observation observation;
    private ScheduledExecutorService executor;

    public OutboxBacklogMetrics(OutboxBacklogReader reader, MeterRegistry registry,
        Duration interval, Runnable closeReader) {
        this(reader, registry, interval, closeReader, System::nanoTime);
    }

    OutboxBacklogMetrics(OutboxBacklogReader reader, MeterRegistry registry,
        Duration interval, Runnable closeReader, LongSupplier nanoTime) {
        this.reader = Objects.requireNonNull(reader, "reader must not be null");
        this.closeReader = Objects.requireNonNull(closeReader, "closeReader must not be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
        Objects.requireNonNull(interval, "interval must not be null");
        if (interval.compareTo(Duration.ofSeconds(5)) < 0 || interval.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Outbox observation interval must be between 5s and 5m");
        }
        intervalMillis = interval.toMillis();
        staleNanos = interval.multipliedBy(2).toNanos();
        Gauge.builder("approval.outbox.pending", this, value -> value.value(Snapshot::pending)).register(registry);
        Gauge.builder("approval.outbox.due", this, value -> value.value(Snapshot::due)).register(registry);
        Gauge.builder("approval.outbox.in.flight", this, value -> value.value(Snapshot::inFlight)).register(registry);
        Gauge.builder("approval.outbox.expired.leases", this, value -> value.value(Snapshot::expiredLeases))
            .register(registry);
        Gauge.builder("approval.outbox.dead", this, value -> value.value(Snapshot::dead)).register(registry);
        Gauge.builder("approval.outbox.oldest.unfinished.age", this,
            value -> value.value(Snapshot::oldestUnfinishedAgeSeconds)).baseUnit("seconds").register(registry);
        Gauge.builder("approval.notification.outbox.pending", this,
            value -> value.value(Snapshot::notificationPending)).register(registry);
        Gauge.builder("approval.notification.outbox.due", this,
            value -> value.value(Snapshot::notificationDue)).register(registry);
        Gauge.builder("approval.notification.outbox.in.flight", this,
            value -> value.value(Snapshot::notificationInFlight)).register(registry);
        Gauge.builder("approval.notification.outbox.expired.leases", this,
            value -> value.value(Snapshot::notificationExpiredLeases)).register(registry);
        Gauge.builder("approval.notification.outbox.dead", this,
            value -> value.value(Snapshot::notificationDead)).register(registry);
        Gauge.builder("approval.notification.outbox.oldest.unfinished.age", this,
            value -> value.value(Snapshot::notificationOldestUnfinishedAgeSeconds))
            .baseUnit("seconds").register(registry);
        Gauge.builder("approval.outbox.sample.up", this, value -> value.fresh() ? 1.0d : 0.0d).register(registry);
        Gauge.builder("approval.outbox.sample.age", this, OutboxBacklogMetrics::ageSeconds)
            .baseUnit("seconds").register(registry);
        Gauge.builder("approval.outbox.sample.timestamp", this, value -> {
            Observation current = value.observation;
            return current == null ? Double.NaN : current.snapshot().observedAt().toEpochMilli() / 1000.0d;
        }).baseUnit("seconds").register(registry);
        FunctionCounter.builder("approval.outbox.sample.errors", errors, AtomicLong::doubleValue).register(registry);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        start();
    }

    synchronized void start() {
        if (closed.get() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(action -> {
            Thread thread = new Thread(action, "approval-outbox-observation");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::refresh, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** Also usable by an operator-owned integration test; not an HTTP endpoint. No overlapping reads. */
    public void refresh() {
        if (closed.get() || !sampling.compareAndSet(false, true)) {
            return;
        }
        long started = nanoTime.getAsLong();
        try {
            Snapshot snapshot = Objects.requireNonNull(reader.read(), "snapshot must not be null");
            if (!closed.get()) {
                observation = new Observation(snapshot, started, true);
            }
        } catch (RuntimeException failure) {
            errors.incrementAndGet();
            Observation previous = observation;
            if (previous != null) {
                observation = new Observation(previous.snapshot(), previous.startedNanos(), false);
            }
            // No SQL, credential, exception text, tenant or event data is logged or used as a tag.
        } finally {
            sampling.set(false);
        }
    }

    private boolean fresh() {
        return fresh(observation);
    }

    private boolean fresh(Observation current) {
        return !closed.get() && current != null && current.successful()
            && elapsed(current) < staleNanos;
    }

    private long elapsed(Observation current) {
        return Math.max(0, nanoTime.getAsLong() - current.startedNanos());
    }

    private double value(ToDoubleFunction<Snapshot> field) {
        Observation current = observation;
        return fresh(current) ? field.applyAsDouble(current.snapshot()) : Double.NaN;
    }

    private double ageSeconds() {
        Observation current = observation;
        return current == null ? Double.NaN : elapsed(current) / 1_000_000_000.0d;
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        // Cancel sampling before closing its private pool. Never close the business DataSource.
        try {
            closeReader.run();
        } finally {
            if (executor != null) {
                try {
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private record Observation(Snapshot snapshot, long startedNanos, boolean successful) {
    }
}
