package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionWorker.Configuration;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActionDispatcher.DispatchResult;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.connector.model.ConnectorContext;
import io.github.akaryc1b.approval.connector.port.OrganizationConnector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ApprovalSlaExecutionWorkerConfiguration {

    @Bean
    ApprovalSlaActionDispatcher approvalSlaActionDispatcher(
        ObjectProvider<OrganizationConnector> organizationConnectors,
        Clock approvalClock
    ) {
        OrganizationConnector connector = organizationConnectors.getIfAvailable();
        return intent -> {
            if (intent.actionType()
                != ApprovalSlaExecutionStore.ActionType.REMINDER) {
                return DispatchResult.permanentFailure(
                    "SLA_ACTION_NOT_CONFIGURED",
                    "the governed business action dispatcher is not configured for "
                        + intent.actionType().name()
                );
            }
            if (connector == null) {
                return DispatchResult.permanentFailure(
                    "CONNECTOR_NOTIFICATION_UNAVAILABLE",
                    "no organization connector is configured for SLA reminders"
                );
            }
            OrganizationConnector.NotificationDeliveryResult delivered = connector.sendNotification(
                new ConnectorContext(
                    textPayload(intent.payload(), "connectorKey", "generic-rest"),
                    intent.tenantId(),
                    intent.idempotencyKey(),
                    intent.traceId(),
                    approvalClock.instant()
                ),
                new OrganizationConnector.UserNotification(
                    intent.responsibleUserId(),
                    "SLA_REMINDER",
                    "Approval SLA reminder",
                    "An approval item is approaching its SLA deadline.",
                    reminderMetadata(intent),
                    intent.idempotencyKey()
                )
            );
            if (delivered.successful()) {
                return DispatchResult.succeeded();
            }
            return delivered.retryable()
                ? DispatchResult.retryableFailure(
                    delivered.errorCode(),
                    delivered.errorMessage()
                )
                : DispatchResult.permanentFailure(
                    delivered.errorCode(),
                    delivered.errorMessage()
                );
        };
    }

    @Bean
    ApprovalSlaExecutionWorker approvalSlaExecutionWorker(
        ApprovalSlaExecutionStore executionStore,
        ApprovalSlaActionDispatcher dispatcher,
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
            ApprovalSlaExecutionWorker.WorkerMetrics.noop(),
            approvalClock,
            new Configuration(
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

    @Bean
    ApprovalSlaExecutionRunner approvalSlaExecutionRunner(
        ApprovalSlaExecutionStore executionStore,
        ApprovalSlaExecutionWorker worker,
        Clock approvalClock,
        @Value("${approval.sla.execution.enabled:false}") boolean enabled,
        @Value("${approval.sla.execution.tenant-batch-size:25}") int tenantBatchSize,
        @Value("${approval.sla.execution.poll-interval-ms:5000}") long pollIntervalMillis
    ) {
        return new ApprovalSlaExecutionRunner(
            executionStore,
            worker,
            approvalClock,
            enabled,
            tenantBatchSize,
            pollIntervalMillis
        );
    }

    private static Map<String, String> reminderMetadata(
        ApprovalSlaExecutionStore.ExecutionIntent intent
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("actionType", intent.actionType().name());
        metadata.put("slaInstanceId", intent.slaInstanceId().toString());
        metadata.put("policyVersion", Integer.toString(intent.policyVersion()));
        addPayload(metadata, intent.payload(), "dueAt");
        addPayload(metadata, intent.payload(), "overdueAt");
        if (intent.taskId() != null) {
            metadata.put("taskId", intent.taskId().toString());
        }
        return Map.copyOf(metadata);
    }

    private static void addPayload(
        Map<String, String> target,
        Map<String, Object> payload,
        String key
    ) {
        Object value = payload.get(key);
        if (value != null) {
            target.put(key, value.toString());
        }
    }

    private static String textPayload(
        Map<String, Object> payload,
        String key,
        String fallback
    ) {
        Object value = payload.get(key);
        return value == null || value.toString().isBlank()
            ? fallback
            : value.toString().trim();
    }

    static final class ApprovalSlaExecutionRunner {

        private final ApprovalSlaExecutionStore executions;
        private final ApprovalSlaExecutionWorker worker;
        private final Clock clock;
        private final boolean enabled;
        private final int tenantBatchSize;

        ApprovalSlaExecutionRunner(
            ApprovalSlaExecutionStore executions,
            ApprovalSlaExecutionWorker worker,
            Clock clock,
            boolean enabled,
            int tenantBatchSize,
            long pollIntervalMillis
        ) {
            this.executions = java.util.Objects.requireNonNull(
                executions,
                "executions must not be null"
            );
            this.worker = java.util.Objects.requireNonNull(worker, "worker must not be null");
            this.clock = java.util.Objects.requireNonNull(clock, "clock must not be null");
            this.enabled = enabled;
            if (tenantBatchSize < 1 || tenantBatchSize > 200) {
                throw new IllegalArgumentException(
                    "tenantBatchSize must be between 1 and 200"
                );
            }
            if (pollIntervalMillis < 1000 || pollIntervalMillis > 3600000) {
                throw new IllegalArgumentException(
                    "pollIntervalMillis must be between 1000 and 3600000"
                );
            }
            this.tenantBatchSize = tenantBatchSize;
        }

        @Scheduled(fixedDelayString = "${approval.sla.execution.poll-interval-ms:5000}")
        void poll() {
            if (!enabled) {
                return;
            }
            for (String tenantId : executions.findRunnableTenants(
                clock.instant(),
                tenantBatchSize
            )) {
                worker.processTenant(tenantId);
            }
        }
    }
}
