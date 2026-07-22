package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.FailureClass;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.WorkerMetrics;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.WorkerResult;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApprovalSlaExecutionMetricsConfigurationTest {

    @Test
    void recordsOnlyClosedLowCardinalityExecutionDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkerMetrics metrics = new ApprovalSlaExecutionMetricsConfiguration()
            .approvalSlaExecutionWorkerMetrics(registry);

        metrics.record(
            ActionType.OVERDUE,
            WorkerResult.RETRY_SCHEDULED,
            FailureClass.RETRYABLE
        );
        metrics.record(
            ActionType.OVERDUE,
            WorkerResult.RETRY_SCHEDULED,
            FailureClass.RETRYABLE
        );

        assertEquals(
            2.0d,
            registry.get("approval.sla.execution.worker")
                .tag("action", "overdue")
                .tag("result", "retry_scheduled")
                .tag("failure_class", "retryable")
                .counter()
                .count()
        );
        Set<String> tagKeys = registry.getMeters().stream()
            .flatMap(meter -> meter.getId().getTags().stream())
            .map(Tag::getKey)
            .collect(Collectors.toSet());
        assertEquals(Set.of("action", "result", "failure_class"), tagKeys);
        for (String forbidden : Set.of(
            "tenant",
            "tenant_id",
            "user",
            "request_id",
            "intent_id",
            "worker_id"
        )) {
            assertFalse(tagKeys.contains(forbidden));
        }
    }
}
