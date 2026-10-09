package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.engine.flowable.FlowableJobPopulationReader;
import io.github.akaryc1b.approval.observability.EngineJobMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.flowable.engine.ManagementService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "approval.observability.engine-jobs", name = "enabled", havingValue = "true")
public class ApprovalEngineJobObservabilityConfiguration {
    @Bean
    EngineJobMetrics engineJobMetrics(ManagementService management, MeterRegistry registry, Clock approvalClock,
        @Value("${approval.observability.engine-jobs.interval-ms:30000}") long intervalMillis) {
        var reader = new FlowableJobPopulationReader(management, approvalClock);
        return new EngineJobMetrics(() -> {
            // Polling runs on our owned thread. An accidental manual call inside a business
            // transaction must not expose uncommitted data or mark that transaction for rollback.
            if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new IllegalStateException("Engine job observation requires an independent thread");
            }
            return reader.read();
        }, registry, Duration.ofMillis(intervalMillis));
    }
}
