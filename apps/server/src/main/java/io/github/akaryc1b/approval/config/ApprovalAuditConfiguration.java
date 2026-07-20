package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalAuditService;
import io.github.akaryc1b.approval.application.port.ApprovalAuditStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ApprovalAuditConfiguration {

    @Bean
    ApprovalAuditStore approvalAuditStore(
        @Qualifier("auditEventSink") AuditEventSink auditEventSink
    ) {
        if (auditEventSink instanceof ApprovalAuditStore auditStore) {
            return auditStore;
        }
        throw new IllegalStateException(
            "configured auditEventSink must implement ApprovalAuditStore"
        );
    }

    @Bean
    ApprovalAuditService approvalAuditService(
        IdempotencyGuard idempotencyGuard,
        ApprovalAuditStore approvalAuditStore,
        @Qualifier("notificationAwareAuditEventSink") AuditEventSink auditEventSink,
        Clock approvalClock
    ) {
        return new ApprovalAuditService(
            idempotencyGuard,
            approvalAuditStore,
            auditEventSink,
            approvalClock,
            UUID::randomUUID
        );
    }
}
