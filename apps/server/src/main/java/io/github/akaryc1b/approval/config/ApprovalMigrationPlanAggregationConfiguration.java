package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationPlanAggregationStoreFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.UUID;

/** Vendor-aware, read-only D8 plan aggregation authority wiring. */
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
}
