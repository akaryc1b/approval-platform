package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalMigrationPlanAggregationService;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationSafetyTelemetry;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationPlanAggregationStoreFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;

/** Internal default-disabled, vendor-aware M5-D8 plan aggregation wiring. */
@Configuration(proxyBeanMethods = false)
public class ApprovalMigrationPlanAggregationConfiguration {

    @Bean
    ApprovalMigrationPlanAggregationStore approvalMigrationPlanAggregationStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEventSink
    ) {
        return JdbcApprovalMigrationPlanAggregationStoreFactory.create(
            dataSource,
            objectMapper,
            transactionManager,
            auditEventSink,
            UUID::randomUUID
        );
    }

    @Bean
    ApprovalMigrationPlanAggregationService approvalMigrationPlanAggregationService(
        ApprovalMigrationPlanAggregationStore store,
        ApprovalMigrationSafetyTelemetry telemetry
    ) {
        return new ApprovalMigrationPlanAggregationService(
            store,
            Clock.systemUTC(),
            telemetry
        );
    }

    @Bean
    ApprovalMigrationPlanAggregationService.OneShotRunner approvalMigrationPlanAggregationRunner(
        @Value("${approval.migration.aggregation.enabled:false}") boolean enabled,
        ApprovalMigrationPlanAggregationService service
    ) {
        return new ApprovalMigrationPlanAggregationService.OneShotRunner(enabled, service);
    }
}
