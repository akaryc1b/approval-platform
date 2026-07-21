package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.security.ApprovalManagementGovernanceRecorder;
import io.github.akaryc1b.approval.security.ApprovalResponsibilityResolver;
import io.github.akaryc1b.approval.security.CachingApprovalResponsibilityResolver;
import io.github.akaryc1b.approval.security.DefaultApprovalManagementGovernanceRecorder;
import io.github.akaryc1b.approval.security.DefaultApprovalResponsibilityResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ApprovalResponsibilityConfiguration {

    @Bean
    ApprovalResponsibilityResolver approvalResponsibilityResolver(Clock approvalClock) {
        DefaultApprovalResponsibilityResolver resolver =
            new DefaultApprovalResponsibilityResolver(approvalClock);
        return new CachingApprovalResponsibilityResolver(
            resolver,
            approvalClock,
            Duration.ofSeconds(30),
            4_096
        );
    }

    @Bean
    ApprovalManagementGovernanceRecorder approvalManagementGovernanceRecorder(
        IdempotencyGuard idempotencyGuard,
        @Qualifier("notificationAwareAuditEventSink") AuditEventSink auditEventSink,
        Clock approvalClock
    ) {
        return new DefaultApprovalManagementGovernanceRecorder(
            idempotencyGuard,
            auditEventSink,
            approvalClock,
            UUID::randomUUID
        );
    }
}
