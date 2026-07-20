package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalDelegationService;
import io.github.akaryc1b.approval.application.ApprovalTaskDelegationQueryService;
import io.github.akaryc1b.approval.application.DelegatingApprovalEngine;
import io.github.akaryc1b.approval.application.port.ApprovalDelegationStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalTaskDelegationAssignmentStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalDelegationStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalTaskDelegationAssignmentStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
    ApprovalTaskDelegationAssignmentStore approvalTaskDelegationAssignmentStore(
        DataSource dataSource
    ) {
        return new JdbcApprovalTaskDelegationAssignmentStore(dataSource);
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

    @Bean
    ApprovalTaskDelegationQueryService approvalTaskDelegationQueryService(
        ApprovalProjectionStore approvalProjectionStore,
        ApprovalTaskDelegationAssignmentStore approvalTaskDelegationAssignmentStore
    ) {
        return new ApprovalTaskDelegationQueryService(
            approvalProjectionStore,
            approvalTaskDelegationAssignmentStore
        );
    }

    @Bean
    @Primary
    ApprovalEngine delegatedApprovalEngine(
        @Qualifier("approvalEngine") ApprovalEngine approvalEngine,
        ApprovalDelegationStore approvalDelegationStore,
        ApprovalTaskDelegationAssignmentStore approvalTaskDelegationAssignmentStore,
        AuditEventSink auditEventSink,
        Clock approvalClock
    ) {
        return new DelegatingApprovalEngine(
            approvalEngine,
            approvalDelegationStore,
            approvalTaskDelegationAssignmentStore,
            auditEventSink,
            approvalClock,
            UUID::randomUUID
        );
    }
}
