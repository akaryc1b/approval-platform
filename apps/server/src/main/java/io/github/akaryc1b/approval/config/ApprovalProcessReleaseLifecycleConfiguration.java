package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalDesignService;
import io.github.akaryc1b.approval.application.ApprovalEffectiveReleaseService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseActivationService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseDispositionService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseLifecycleService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseQueryService;
import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore;
import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseDeactivationPort;
import io.github.akaryc1b.approval.application.port.ApprovalEffectiveReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalEffectiveReleaseDeactivationPort;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalProcessReleaseStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalRuntimeBindingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ApprovalProcessReleaseLifecycleConfiguration {

    @Bean
    ApprovalProcessReleaseStore approvalProcessReleaseStore(DataSource dataSource) {
        return new JdbcApprovalProcessReleaseStore(dataSource);
    }

    @Bean
    ApprovalRuntimeBindingStore approvalRuntimeBindingStore(DataSource dataSource) {
        return new JdbcApprovalRuntimeBindingStore(dataSource);
    }

    @Bean
    ApprovalProcessReleaseQueryService approvalProcessReleaseQueryService(
        ApprovalProcessReleaseStore approvalProcessReleaseStore
    ) {
        return new ApprovalProcessReleaseQueryService(approvalProcessReleaseStore);
    }

    @Bean
    ApprovalEffectiveReleaseDeactivationPort approvalEffectiveReleaseDeactivationPort(
        DataSource dataSource
    ) {
        return new JdbcApprovalEffectiveReleaseDeactivationPort(dataSource);
    }

    @Bean
    ApprovalProcessReleaseLifecycleService approvalProcessReleaseLifecycleService(
        IdempotencyGuard idempotencyGuard,
        ApprovalDesignDraftStore approvalDesignDraftStore,
        ApprovalDesignService approvalDesignService,
        ApprovalProcessReleaseStore approvalProcessReleaseStore,
        AuditEventSink auditEventSink,
        ApprovalReleasePackageHasher approvalReleasePackageHasher,
        Clock approvalClock
    ) {
        return new ApprovalProcessReleaseLifecycleService(
            idempotencyGuard,
            approvalDesignDraftStore,
            approvalDesignService::publish,
            approvalProcessReleaseStore,
            auditEventSink,
            approvalReleasePackageHasher,
            approvalClock,
            UUID::randomUUID
        );
    }

    @Bean
    ApprovalProcessReleaseActivationService approvalProcessReleaseActivationService(
        IdempotencyGuard idempotencyGuard,
        ApprovalProcessReleaseStore approvalProcessReleaseStore,
        ApprovalEffectiveReleaseService approvalEffectiveReleaseService,
        AuditEventSink auditEventSink,
        ApprovalReleasePackageHasher approvalReleasePackageHasher,
        Clock approvalClock
    ) {
        return new ApprovalProcessReleaseActivationService(
            idempotencyGuard,
            approvalProcessReleaseStore,
            (command, operation) -> switch (operation) {
                case ACTIVATE -> approvalEffectiveReleaseService.activate(command);
                case ROLLBACK -> approvalEffectiveReleaseService.rollback(command);
            },
            auditEventSink,
            approvalReleasePackageHasher,
            approvalClock,
            UUID::randomUUID
        );
    }

    @Bean
    ApprovalProcessReleaseDispositionService approvalProcessReleaseDispositionService(
        IdempotencyGuard idempotencyGuard,
        ApprovalProcessReleaseStore approvalProcessReleaseStore,
        ApprovalEffectiveReleaseStore approvalEffectiveReleaseStore,
        ApprovalEffectiveReleaseDeactivationPort approvalEffectiveReleaseDeactivationPort,
        ApprovalRuntimeBindingStore approvalRuntimeBindingStore,
        AuditEventSink auditEventSink,
        ApprovalReleasePackageHasher approvalReleasePackageHasher,
        Clock approvalClock
    ) {
        return new ApprovalProcessReleaseDispositionService(
            idempotencyGuard,
            approvalProcessReleaseStore,
            approvalEffectiveReleaseStore,
            approvalEffectiveReleaseDeactivationPort,
            approvalRuntimeBindingStore,
            auditEventSink,
            approvalReleasePackageHasher,
            approvalClock,
            UUID::randomUUID
        );
    }
}
