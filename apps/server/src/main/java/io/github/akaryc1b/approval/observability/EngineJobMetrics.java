package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.engine.EngineJobPopulationReader;
import io.github.akaryc1b.approval.engine.EngineJobPopulationReader.Snapshot;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.time.Duration;
import java.util.function.ToLongFunction;

/** Fixed queue dimensions on the existing private management surface. */
public final class EngineJobMetrics implements ApplicationListener<ApplicationReadyEvent>, AutoCloseable {
    private final EngineJobPopulationMonitor monitor;

    public EngineJobMetrics(EngineJobPopulationReader reader, MeterRegistry registry, Duration interval) {
        monitor = new EngineJobPopulationMonitor(reader, interval);
        queue(registry, "executable", Snapshot::executable);
        queue(registry, "timer", Snapshot::timer);
        queue(registry, "suspended", Snapshot::suspended);
        queue(registry, "dead_letter", Snapshot::deadLetter);
        Gauge.builder("approval.engine.jobs.sample.up", monitor, EngineJobPopulationMonitor::up).register(registry);
        Gauge.builder("approval.engine.jobs.sample.age", monitor, EngineJobPopulationMonitor::ageSeconds)
            .baseUnit("seconds").register(registry);
        Gauge.builder("approval.engine.jobs.sample.timestamp", monitor, EngineJobPopulationMonitor::timestampSeconds)
            .baseUnit("seconds").register(registry);
        FunctionCounter.builder("approval.engine.jobs.sample.errors", monitor, EngineJobPopulationMonitor::errors)
            .register(registry);
    }

    private void queue(MeterRegistry registry, String queue, ToLongFunction<Snapshot> field) {
        Gauge.builder("approval.engine.jobs", monitor, state -> state.value(field)).tag("queue", queue).register(registry);
    }
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) { monitor.start(); }
    public void refresh() { monitor.refresh(); }
    @Override
    public void close() { monitor.close(); }
}
