package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher.DispatchResult;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntent;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Claims SLA intents briefly, dispatches externally, then records attempt evidence atomically. */
public final class ApprovalSlaExecutionWorker {

    private static final Duration MAX_LEASE = Duration.ofHours(1);
    private static final Duration MAX_BACKOFF = Duration.ofHours(24);

    private final ApprovalSlaExecutionStore executions;
    private final ApprovalSlaActionDispatcher dispatcher;
    private final WorkerMetrics metrics;
    private final Clock clock;
    private final Configuration configuration;
    private final Supplier<UUID> identifiers;

    public ApprovalSlaExecutionWorker(
        ApprovalSlaExecutionStore executions,
        ApprovalSlaActionDispatcher dispatcher,
        WorkerMetrics metrics,
        Clock clock,
        Configuration configuration,
        Supplier<UUID> identifiers
    ) {
        this.executions = Objects.requireNonNull(executions, "executions must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.configuration = Objects.requireNonNull(
            configuration,
            "configuration must not be null"
        );
        this.identifiers = Objects.requireNonNull(
            identifiers,
            "identifiers must not be null"
        );
    }

    public WorkerReport processTenant(String tenantId) {
        if (!configuration.enabled()) {
            return WorkerReport.empty();
        }
        Instant claimTime = clock.instant();
        List<ExecutionIntent> claimed = executions.claimDue(
            requireText(tenantId, "tenantId", 128),
            claimTime,
            configuration.batchSize(),
            configuration.workerId(),
            claimTime.plus(configuration.leaseDuration())
        );
        int succeeded = 0;
        int retryScheduled = 0;
        int dead = 0;
        int conflicts = 0;
        for (ExecutionIntent intent : claimed) {
            WorkerResult result = process(intent);
            switch (result) {
                case SUCCEEDED -> succeeded++;
                case RETRY_SCHEDULED -> retryScheduled++;
                case DEAD -> dead++;
                case PERSISTENCE_CONFLICT -> conflicts++;
            }
        }
        return new WorkerReport(claimed.size(), succeeded, retryScheduled, dead, conflicts);
    }

    private WorkerResult process(ExecutionIntent intent) {
        Instant claimedAt = intent.updatedAt();
        Instant startedAt = notBefore(clock.instant(), claimedAt);
        DispatchResult dispatchResult;
        FailureClass failureClass;
        try {
            dispatchResult = Objects.requireNonNull(
                dispatcher.dispatch(intent),
                "dispatcher result must not be null"
            );
            failureClass = dispatchResult.successful()
                ? FailureClass.NONE
                : dispatchResult.retryable()
                    ? FailureClass.RETRYABLE
                    : FailureClass.NON_RETRYABLE;
        } catch (RuntimeException exception) {
            dispatchResult = DispatchResult.retryableFailure(
                "SLA_ACTION_DISPATCH_EXCEPTION",
                safeMessage(exception)
            );
            failureClass = FailureClass.UNEXPECTED;
        }
        Instant finishedAt = notBefore(clock.instant(), startedAt);
        try {
            ExecutionIntent updated;
            WorkerResult result;
            if (dispatchResult.successful()) {
                updated = executions.markSucceeded(
                    intent.tenantId(),
                    intent.intentId(),
                    intent.version(),
                    configuration.workerId(),
                    nextIdentifier(),
                    claimedAt,
                    startedAt,
                    finishedAt,
                    intent.requestId(),
                    intent.traceId()
                );
                result = WorkerResult.SUCCEEDED;
            } else {
                int nextAttemptNumber = intent.attemptCount() + 1;
                boolean canRetry = dispatchResult.retryable()
                    && nextAttemptNumber < intent.maxAttempts();
                Instant nextAttemptAt = canRetry
                    ? finishedAt.plus(backoff(nextAttemptNumber))
                    : null;
                updated = executions.markFailed(
                    intent.tenantId(),
                    intent.intentId(),
                    intent.version(),
                    configuration.workerId(),
                    nextIdentifier(),
                    claimedAt,
                    startedAt,
                    finishedAt,
                    dispatchResult.retryable(),
                    nextAttemptAt,
                    dispatchResult.errorCode(),
                    dispatchResult.errorSummary(),
                    intent.requestId(),
                    intent.traceId()
                );
                result = updated.status() == IntentStatus.RETRY_WAIT
                    ? WorkerResult.RETRY_SCHEDULED
                    : WorkerResult.DEAD;
            }
            metrics.record(intent.actionType(), result, failureClass);
            return result;
        } catch (ExecutionConflictException exception) {
            metrics.record(
                intent.actionType(),
                WorkerResult.PERSISTENCE_CONFLICT,
                failureClass
            );
            return WorkerResult.PERSISTENCE_CONFLICT;
        }
    }

    private Duration backoff(int attemptNumber) {
        Duration value = configuration.initialBackoff();
        for (int current = 1; current < attemptNumber; current++) {
            if (value.compareTo(configuration.maxBackoff().dividedBy(2)) > 0) {
                return configuration.maxBackoff();
            }
            value = value.multipliedBy(2);
        }
        return value.compareTo(configuration.maxBackoff()) > 0
            ? configuration.maxBackoff()
            : value;
    }

    private UUID nextIdentifier() {
        return Objects.requireNonNull(
            identifiers.get(),
            "generated attemptId must not be null"
        );
    }

    private static Instant notBefore(Instant candidate, Instant lowerBound) {
        return candidate.isBefore(lowerBound) ? lowerBound : candidate;
    }

    private static String safeMessage(RuntimeException exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) {
            value = exception.getClass().getSimpleName();
        }
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }

    public record Configuration(
        boolean enabled,
        String workerId,
        int batchSize,
        Duration leaseDuration,
        Duration initialBackoff,
        Duration maxBackoff
    ) {
        public Configuration {
            workerId = requireText(workerId, "workerId", 200);
            if (batchSize < 1 || batchSize > 200) {
                throw new IllegalArgumentException("batchSize must be between 1 and 200");
            }
            leaseDuration = positiveBounded(
                leaseDuration,
                "leaseDuration",
                MAX_LEASE
            );
            initialBackoff = positiveBounded(
                initialBackoff,
                "initialBackoff",
                MAX_BACKOFF
            );
            maxBackoff = positiveBounded(maxBackoff, "maxBackoff", MAX_BACKOFF);
            if (maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException(
                    "maxBackoff must not be shorter than initialBackoff"
                );
            }
        }

        private static Duration positiveBounded(
            Duration value,
            String name,
            Duration maximum
        ) {
            Objects.requireNonNull(value, name + " must not be null");
            if (value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
                throw new IllegalArgumentException(name + " is outside its safe bounds");
            }
            return value;
        }
    }

    public record WorkerReport(
        int claimed,
        int succeeded,
        int retryScheduled,
        int dead,
        int persistenceConflicts
    ) {
        public WorkerReport {
            if (claimed < 0 || succeeded < 0 || retryScheduled < 0
                || dead < 0 || persistenceConflicts < 0
                || succeeded + retryScheduled + dead + persistenceConflicts != claimed) {
                throw new IllegalArgumentException("worker report counts are inconsistent");
            }
        }

        public static WorkerReport empty() {
            return new WorkerReport(0, 0, 0, 0, 0);
        }
    }

    @FunctionalInterface
    public interface WorkerMetrics {
        void record(ActionType actionType, WorkerResult result, FailureClass failureClass);

        static WorkerMetrics noop() {
            return (actionType, result, failureClass) -> {
            };
        }
    }

    public enum WorkerResult {
        SUCCEEDED,
        RETRY_SCHEDULED,
        DEAD,
        PERSISTENCE_CONFLICT
    }

    public enum FailureClass {
        NONE,
        RETRYABLE,
        NON_RETRYABLE,
        UNEXPECTED
    }
}
