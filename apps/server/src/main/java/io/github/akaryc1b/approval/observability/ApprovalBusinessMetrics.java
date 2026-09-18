package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Best-effort local telemetry; the database and existing audit remain authoritative. */
@Component
public final class ApprovalBusinessMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApprovalBusinessMetrics.class);
    private static final int MAX_SAVEPOINTS = 64;
    private final MeterRegistry registry;
    private final LongAdder dropped = new LongAdder();
    private final AtomicBoolean warned = new AtomicBoolean();

    public ApprovalBusinessMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        safely(() -> FunctionCounter.builder("approval.telemetry.dropped", dropped, LongAdder::doubleValue)
            .description("Business observations not recorded; inspect telemetry configuration")
            .register(registry));
    }

    void processStarted(InstanceStatus status) {
        committed(() -> {
            registry.counter("approval.process.started").increment();
            terminal(status);
        });
    }

    void taskCompleted(InstanceStatus status) {
        committed(() -> {
            registry.counter("approval.task.completed").increment();
            terminal(status);
        });
    }

    /** Persisted task creation to normal claim timestamp, published only on transaction commit. */
    void taskClaimed(TaskProjection task) {
        committed(() -> {
            if (task == null || task.status() != TaskStatus.COMPLETING || task.completedAt() != null) {
                drop();
                return;
            }
            Duration waiting = Duration.between(task.createdAt(), task.updatedAt());
            if (waiting.isNegative()) {
                drop(); // Clock regression or invalid evidence is not a zero-duration task.
                return;
            }
            long nanos = waiting.toNanos(); // Overflow is dropped by the existing telemetry guard.
            Timer.builder("approval.task.waiting.duration")
                .description("Task projection creation to committed normal claim timestamp; wall-clock, not SLA")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofSeconds(1))
                .maximumExpectedValue(Duration.ofDays(90))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
        });
    }

    void processWithdrawn() {
        committed(() -> registry.counter("approval.process.withdrawn").increment());
    }

    private void terminal(InstanceStatus status) {
        if (status == InstanceStatus.COMPLETED) {
            registry.counter("approval.process.completed").increment();
        } else if (status == InstanceStatus.REJECTED) {
            registry.counter("approval.process.rejected").increment();
        }
    }

    private void committed(Runnable observation) {
        afterCompletion(status -> {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                observation.run();
            }
        });
    }

    <T> T command(String operation, Supplier<T> action) {
        long started = System.nanoTime();
        AtomicBoolean returned = new AtomicBoolean();
        afterCompletion(status -> {
            String outcome = switch (status) {
                case TransactionSynchronization.STATUS_COMMITTED -> returned.get() ? "committed" : "failed";
                case TransactionSynchronization.STATUS_ROLLED_BACK -> "rolled_back";
                default -> "unknown";
            };
            Timer.builder("approval.command.duration")
                .description("Executed command callback to transaction completion; excludes idempotent replay")
                .tags("operation", operation, "outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(5))
                .register(registry)
                .record(Math.max(0, System.nanoTime() - started), TimeUnit.NANOSECONDS);
        });
        // No catch around business execution: preserve its result and original exception.
        T result = action.get();
        returned.set(true);
        return result;
    }

    private void afterCompletion(IntConsumer observation) {
        safely(() -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
                drop(); // Never turn a nontransactional invocation into a committed business fact.
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                private final Set<Object> laterSavepoints = Collections.newSetFromMap(new IdentityHashMap<>());
                private boolean rolledBackToEnclosingSavepoint;
                private boolean unknownSavepoint;
                private boolean completed;

                @Override
                public void savepoint(Object savepoint) {
                    if (laterSavepoints.size() >= MAX_SAVEPOINTS) {
                        unknownSavepoint = true;
                    } else {
                        laterSavepoints.add(savepoint);
                    }
                }

                @Override
                public void savepointRollback(Object savepoint) {
                    if (!laterSavepoints.contains(savepoint)) {
                        rolledBackToEnclosingSavepoint = true;
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    if (completed) {
                        return;
                    }
                    completed = true;
                    laterSavepoints.clear();
                    if (unknownSavepoint) {
                        drop();
                        return;
                    }
                    int effectiveStatus = rolledBackToEnclosingSavepoint ? STATUS_ROLLED_BACK : status;
                    safely(() -> observation.accept(effectiveStatus));
                }
            });
        });
    }

    private void safely(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException failure) {
            // Deliberately omit exception text, request context, form data and identifiers.
            drop();
        }
    }

    private void drop() {
        dropped.increment();
        if (warned.compareAndSet(false, true)) {
            try {
                LOGGER.warn("Approval telemetry observation dropped; business outcome is unchanged");
            } catch (RuntimeException unavailable) {
                // A broken logging backend must not become a business failure either.
            }
        }
    }

    long droppedObservations() {
        return dropped.sum();
    }
}
