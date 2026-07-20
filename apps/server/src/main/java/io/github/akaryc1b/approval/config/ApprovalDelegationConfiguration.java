package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalDelegationService;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalDelegationStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ApprovalDelegationConfiguration {

    @Bean
    ApprovalDelegationStore approvalDelegationStore(DataSource dataSource) {
        return new JdbcApprovalDelegationStore(dataSource);
    }

    @Bean
    ApprovalDelegationService approvalDelegationService(
        IdempotencyGuard idempotencyGuard,
        ApprovalDelegationStore approvalDelegationStore,
        AuditEventSink auditEventSink,
        Clock approvalClock
    ) {
        return new ApprovalDelegationService(
            idempotencyGuard,
            approvalDelegationStore,
            auditEventSink,
            approvalClock,
            UUID::randomUUID
        );
    }
}
