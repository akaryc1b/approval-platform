package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalMigrationPlanAggregationService;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationPlanAggregationStore;
import io.github.akaryc1b.approval.persistence.jdbc.PostgresSerializedApprovalMigrationPlanAggregationStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;

/** Internal default-disabled M5-D8 plan aggregation wiring. */
@Configuration(proxyBeanMethods = false)
public class ApprovalMigrationPlanAggregationConfiguration {

    @Bean
    ApprovalMigrationPlanAggregationStore approvalMigrationPlanAggregationStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEventSink
    ) {
        return new JdbcApprovalMigrationPlanAggregationStore(
            dataSource,
            objectMapper,
            transactionManager,
            auditEventSink,
            UUID::randomUUID
        );
    }

    @Bean
    @Primary
    ApprovalMigrationPlanAggregationStore serializedApprovalMigrationPlanAggregationStore(
        DataSource dataSource,
        @Qualifier("approvalMigrationPlanAggregationStore")
        ApprovalMigrationPlanAggregationStore delegate
    ) {
        return new PostgresSerializedApprovalMigrationPlanAggregationStore(
            dataSource,
            delegate
        );
    }

    @Bean
    ApprovalMigrationPlanAggregationService approvalMigrationPlanAggregationService(
        ApprovalMigrationPlanAggregationStore store
    ) {
        return new ApprovalMigrationPlanAggregationService(store, Clock.systemUTC());
    }

    @Bean
    ApprovalMigrationPlanAggregationService.OneShotRunner approvalMigrationPlanAggregationRunner(
        @Value("${approval.migration.aggregation.enabled:false}") boolean enabled,
        ApprovalMigrationPlanAggregationService service
    ) {
        return new ApprovalMigrationPlanAggregationService.OneShotRunner(enabled, service);
    }
}
