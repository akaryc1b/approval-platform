package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.util.Objects;
import java.util.function.Supplier;

/** Measures executions inside the existing guard, not cached replay responses. */
public final class ObservedIdempotencyGuard implements IdempotencyGuard {

    private final IdempotencyGuard delegate;
    private final ApprovalBusinessMetrics metrics;

    public ObservedIdempotencyGuard(IdempotencyGuard delegate, ApprovalBusinessMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    @Override
    public <T> T execute(
        RequestContext context,
        String operation,
        String requestHash,
        Class<T> resultType,
        Supplier<T> action
    ) {
        String label = operationLabel(operation);
        if (label == null || action == null) {
            return delegate.execute(context, operation, requestHash, resultType, action);
        }
        return delegate.execute(context, operation, requestHash, resultType,
            () -> metrics.command(label, action));
    }

    private static String operationLabel(String operation) {
        if (operation == null) {
            return null;
        }
        return switch (operation) {
            case "purchase-payment.start.v1" -> "start";
            case "purchase-payment.approve.v1" -> "approve";
            case "purchase-payment.reject.v1" -> "reject";
            case "purchase-payment.resubmit.v1" -> "resubmit";
            default -> null;
        };
    }
}
