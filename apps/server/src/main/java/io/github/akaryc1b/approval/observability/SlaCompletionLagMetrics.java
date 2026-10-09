package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.FailureClass;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.WorkerMetrics;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.WorkerResult;
import io.github.akaryc1b.approval.application.ApprovalSlaLagCalculator;
import io.github.akaryc1b.approval.application.ApprovalSlaLagCalculator.Lag;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntent;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/** Best-effort process-local samples after a successful execution-store write, never inside a business transaction. */
public final class SlaCompletionLagMetrics implements WorkerMetrics {

    private final WorkerMetrics delegate;
    private final MeterRegistry registry;
    private final Function<ExecutionIntent, Lag> reader;
    private final LongAdder dropped = new LongAdder();

    public SlaCompletionLagMetrics(WorkerMetrics delegate, MeterRegistry registry,
        Function<ExecutionIntent, Lag> reader) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.reader = Objects.requireNonNull(reader, "reader");
        try {
            FunctionCounter.builder("approval.sla.overdue.completion.observations.dropped", dropped,
                LongAdder::doubleValue).register(registry);
        } catch (RuntimeException unavailable) {
            dropped.increment();
        }
    }

    @Override
    public void record(ActionType action, WorkerResult result, FailureClass failure) {
        try {
            delegate.record(action, result, failure);
        } catch (RuntimeException unavailable) {
            dropped.increment();
        }
    }

    @Override
    public void completed(ExecutionIntent succeeded) {
        // Even a nominally SUCCEEDED return value inside an outer transaction is not a committed fact.
        // Refuse before the reader, including a synchronization-only scope; never suspend business work.
        if (TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isSynchronizationActive()) {
            dropped.increment();
            return;
        }
        try {
            if (succeeded == null || succeeded.status() != IntentStatus.SUCCEEDED
                || succeeded.completedAt() == null || succeeded.attemptCount() < 1) {
                throw new IllegalArgumentException("successful execution evidence required");
            }
            if (succeeded.actionType() != ActionType.OVERDUE) return;
            Duration natural = Duration.between(succeeded.scheduledAt(), succeeded.completedAt());
            if (natural.isNegative() || natural.compareTo(ApprovalSlaLagCalculator.MAX_OBSERVATION_RANGE) > 0
                || (succeeded.collaborationParticipantId() != null && succeeded.taskId() == null)) {
                throw new IllegalArgumentException("invalid overdue completion evidence");
            }
            Lag lag = Objects.requireNonNull(reader.apply(succeeded), "lag");
            if (!lag.natural().equals(natural)) throw new IllegalArgumentException("completion time mismatch");
            String target = succeeded.collaborationParticipantId() != null ? "collaboration"
                : succeeded.taskId() != null ? "task" : "process";
            String source = succeeded.sourceIntentId() == null ? "original" : "replay";
            Timer wall = timer(target, source, "natural_time");
            Timer working = lag.working() == null ? null : timer(target, source, "working_time");
            wall.record(lag.natural());
            if (working != null) working.record(lag.working());
        } catch (RuntimeException unavailable) {
            // Do not retain identities, calendars, payloads or exception text; do not retry the action.
            dropped.increment();
        }
    }

    private Timer timer(String target, String source, String basis) {
        return Timer.builder("approval.sla.overdue.completion.lag")
            .description("Scheduled overdue action to persisted successful execution finish; not first detection or process duration")
            .tags("target", target, "source", source, "time_basis", basis)
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofSeconds(1))
            .maximumExpectedValue(Duration.ofDays(7))
            .register(registry);
    }

    long droppedObservations() {
        return dropped.sum();
    }
}
