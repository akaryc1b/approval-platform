package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalReleaseDeploymentService;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalReleaseDeploymentStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ApprovalReleaseDeploymentConfiguration {

    @Bean
    ApprovalReleaseDeploymentStore approvalReleaseDeploymentStore(DataSource dataSource) {
        return new JdbcApprovalReleaseDeploymentStore(dataSource);
    }

    @Bean
    ApprovalReleaseDeploymentService approvalReleaseDeploymentService(
        IdempotencyGuard idempotencyGuard,
        ApprovalReleasePackageStore approvalReleasePackageStore,
        ApprovalReleaseDeploymentStore approvalReleaseDeploymentStore,
        ApprovalEngine approvalEngine,
        AuditEventSink auditEventSink,
        Clock approvalClock
    ) {
        return new ApprovalReleaseDeploymentService(
            idempotencyGuard,
            approvalReleasePackageStore,
            approvalReleaseDeploymentStore,
            approvalEngine,
            auditEventSink,
            approvalClock,
            UUID::randomUUID
        );
    }
}
