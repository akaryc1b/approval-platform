package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.WorkerMetrics;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Installs low-cardinality metrics on the SLA execution worker. */
@Configuration(proxyBeanMethods = false)
public class ApprovalSlaExecutionMetricsConfiguration {

    @Bean
    WorkerMetrics approvalSlaExecutionWorkerMetrics(MeterRegistry meters) {
        Objects.requireNonNull(meters, "meters must not be null");
        return (actionType, result, failureClass) -> meters.counter(
            "approval.sla.execution.worker",
            "action",
            metric(actionType),
            "result",
            metric(result),
            "failure_class",
            metric(failureClass)
        ).increment();
    }

    @Bean
    @Primary
    ApprovalSlaExecutionWorker meteredApprovalSlaExecutionWorker(
        ApprovalSlaExecutionStore executionStore,
        ApprovalSlaActionDispatcher dispatcher,
        WorkerMetrics metrics,
        Clock approvalClock,
        @Value("${approval.sla.execution.enabled:false}") boolean enabled,
        @Value("${approval.sla.execution.worker-id:}") String configuredWorkerId,
        @Value("${approval.sla.execution.batch-size:50}") int batchSize,
        @Value("${approval.sla.execution.lease-duration-ms:300000}") long leaseDurationMillis,
        @Value("${approval.sla.execution.initial-backoff-ms:30000}") long initialBackoffMillis,
        @Value("${approval.sla.execution.max-backoff-ms:1800000}") long maxBackoffMillis
    ) {
        String workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
            ? "sla-worker-" + UUID.randomUUID()
            : configuredWorkerId.trim();
        return new ApprovalSlaExecutionWorker(
            executionStore,
            dispatcher,
            metrics,
            approvalClock,
            new ApprovalSlaExecutionWorker.Configuration(
                enabled,
                workerId,
                batchSize,
                Duration.ofMillis(leaseDurationMillis),
                Duration.ofMillis(initialBackoffMillis),
                Duration.ofMillis(maxBackoffMillis)
            ),
            UUID::randomUUID
        );
    }

    private static String metric(Enum<?> value) {
        return Objects.requireNonNull(value, "metric value must not be null")
            .name()
            .toLowerCase(Locale.ROOT);
    }

}
