package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalConsistencyService;
import io.github.akaryc1b.approval.application.port.ApprovalConsistencyStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalConsistencyStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ApprovalConsistencyConfiguration {

    @Bean
    ApprovalConsistencyStore approvalConsistencyStore(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper,
        PlatformTransactionManager transactionManager
    ) {
        return new JdbcApprovalConsistencyStore(
            dataSource,
            approvalPersistenceObjectMapper,
            transactionManager
        );
    }

    @Bean
    ApprovalConsistencyService approvalConsistencyService(
        IdempotencyGuard idempotencyGuard,
        ApprovalConsistencyStore approvalConsistencyStore,
        @Qualifier("notificationAwareAuditEventSink") AuditEventSink auditEventSink,
        Clock approvalClock
    ) {
        return new ApprovalConsistencyService(
            idempotencyGuard,
            approvalConsistencyStore,
            auditEventSink,
            approvalClock,
            UUID::randomUUID
        );
    }
}
