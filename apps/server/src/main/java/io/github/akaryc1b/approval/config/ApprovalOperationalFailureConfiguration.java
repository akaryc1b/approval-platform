package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalConsistencyService;
import io.github.akaryc1b.approval.application.ApprovalOperationalFailureService;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore;
import io.github.akaryc1b.approval.application.port.ApprovalOperationalFailureStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalOperationalFailureStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ApprovalOperationalFailureConfiguration {

    @Bean
    ApprovalOperationalFailureStore approvalOperationalFailureStore(DataSource dataSource) {
        return new JdbcApprovalOperationalFailureStore(dataSource);
    }

    @Bean
    ApprovalOperationalFailureService approvalOperationalFailureService(
        IdempotencyGuard idempotencyGuard,
        ApprovalOperationalFailureStore operationalFailureStore,
        ApprovalNotificationStore notificationStore,
        ApprovalConsistencyService consistencyService,
        @Qualifier("notificationAwareAuditEventSink") AuditEventSink auditEventSink,
        Clock approvalClock
    ) {
        return new ApprovalOperationalFailureService(
            idempotencyGuard,
            operationalFailureStore,
            notificationStore,
            consistencyService,
            auditEventSink,
            approvalClock,
            UUID::randomUUID
        );
    }
}
