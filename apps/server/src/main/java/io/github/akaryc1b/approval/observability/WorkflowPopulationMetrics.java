package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.application.port.ApprovalWorkflowPopulationReader.Snapshot;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.Objects;
import java.util.function.ToDoubleFunction;

/** No HTTP endpoint or scrape-time database query; ready/close own the private monitor lifecycle. */
public final class WorkflowPopulationMetrics implements ApplicationListener<ApplicationReadyEvent>, AutoCloseable {
    private final WorkflowPopulationMonitor monitor;

    public WorkflowPopulationMetrics(WorkflowPopulationMonitor monitor, MeterRegistry registry) {
        this.monitor = Objects.requireNonNull(monitor, "monitor must not be null");
        gauge(registry, "approval.process.active", Snapshot::activeProcesses);
        gauge(registry, "approval.process.sla.covered", Snapshot::coveredProcesses);
        gauge(registry, "approval.process.overdue", Snapshot::overdueProcesses);
        gauge(registry, "approval.task.active", Snapshot::activeTasks);
        gauge(registry, "approval.task.sla.covered", Snapshot::coveredTasks);
        gauge(registry, "approval.task.overdue", Snapshot::overdueTasks);
        Gauge.builder("approval.workflow.sample.up", monitor, WorkflowPopulationMonitor::up).register(registry);
        Gauge.builder("approval.workflow.sample.age", monitor, WorkflowPopulationMonitor::ageSeconds)
            .baseUnit("seconds").register(registry);
        Gauge.builder("approval.workflow.sample.timestamp", monitor, WorkflowPopulationMonitor::timestampSeconds)
            .baseUnit("seconds").register(registry);
        FunctionCounter.builder("approval.workflow.sample.errors", monitor, WorkflowPopulationMonitor::errors)
            .register(registry);
    }

    private void gauge(MeterRegistry registry, String name, ToDoubleFunction<Snapshot> field) {
        Gauge.builder(name, monitor, value -> value.value(field)).register(registry);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) { monitor.start(); }

    /** Explicit test/operator refresh, not called by a gauge or business command. */
    public void refresh() { monitor.refresh(); }

    @Override
    public void close() { monitor.close(); }
}
