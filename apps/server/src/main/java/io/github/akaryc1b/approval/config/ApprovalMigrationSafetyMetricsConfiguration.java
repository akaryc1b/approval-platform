package io.github.akaryc1b.approval.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Objects;

/** Publishes closed feature-state gauges without resource or authority identity tags. */
@Configuration(proxyBeanMethods = false)
public class ApprovalMigrationSafetyMetricsConfiguration {

    static final String METRIC = "approval.migration.safety.feature.enabled";

    @Bean
    ApprovalMigrationSafetyState approvalMigrationSafetyState(
        @Value("${approval.migration.execution.enabled:false}") boolean executionEnabled,
        @Value("${approval.migration.worker.enabled:false}") boolean workerEnabled,
        @Value("${approval.migration.orchestration.enabled:false}") boolean orchestrationEnabled,
        @Value("${approval.migration.aggregation.enabled:false}") boolean aggregationEnabled,
        @Value("${approval.migration.reconciliation.automatic.enabled:false}")
        boolean automaticReconciliationEnabled,
        @Value("${approval.migration.kill-switch.enabled:false}") boolean killSwitchEnabled,
        MeterRegistry meters
    ) {
        ApprovalMigrationSafetyState state = new ApprovalMigrationSafetyState(
            executionEnabled,
            workerEnabled,
            orchestrationEnabled,
            aggregationEnabled,
            automaticReconciliationEnabled,
            killSwitchEnabled
        );
        state.bind(Objects.requireNonNull(meters, "meters must not be null"));
        return state;
    }

    record ApprovalMigrationSafetyState(
        boolean executionEnabled,
        boolean workerEnabled,
        boolean orchestrationEnabled,
        boolean aggregationEnabled,
        boolean automaticReconciliationEnabled,
        boolean killSwitchEnabled
    ) {
        void bind(MeterRegistry meters) {
            for (Feature feature : Feature.values()) {
                Gauge.builder(METRIC, this, state -> state.enabled(feature) ? 1.0 : 0.0)
                    .tag("feature", feature.metricValue())
                    .description("Closed M5 migration safety feature state; 1 means configured enabled")
                    .register(meters);
            }
        }

        private boolean enabled(Feature feature) {
            return switch (feature) {
                case EXECUTION -> executionEnabled;
                case WORKER -> workerEnabled;
                case ORCHESTRATION -> orchestrationEnabled;
                case AGGREGATION -> aggregationEnabled;
                case AUTOMATIC_RECONCILIATION -> automaticReconciliationEnabled;
                case KILL_SWITCH -> killSwitchEnabled;
            };
        }
    }

    enum Feature {
        EXECUTION("execution"),
        WORKER("worker"),
        ORCHESTRATION("orchestration"),
        AGGREGATION("aggregation"),
        AUTOMATIC_RECONCILIATION("automatic_reconciliation"),
        KILL_SWITCH("kill_switch");

        private final String metricValue;

        Feature(String metricValue) {
            this.metricValue = metricValue;
        }

        String metricValue() {
            return metricValue;
        }
    }
}
