package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.api.ApprovalTaskOutcomeInterceptor;
import io.github.akaryc1b.approval.application.ApprovalSlaService;
import io.github.akaryc1b.approval.application.ApprovalTaskCollaborationService;
import io.github.akaryc1b.approval.application.ApprovalTaskOutcomeContext;
import io.github.akaryc1b.approval.application.CollaborationAwareApprovalProjectionStore;
import io.github.akaryc1b.approval.application.SlaAwareApprovalTaskCollaborationStore;
import io.github.akaryc1b.approval.application.port.ApprovalIdentityDirectory;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalRequestEvidenceProvider;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCollaborationStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalTaskCollaborationStore;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;

import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ApprovalTaskCollaborationConfiguration {

    @Bean
    ApprovalTaskCollaborationStore approvalTaskCollaborationStore(DataSource dataSource) {
        return new JdbcApprovalTaskCollaborationStore(dataSource);
    }

    @Bean
    @Primary
    ApprovalTaskCollaborationStore slaAwareApprovalTaskCollaborationStore(
        @Qualifier("approvalTaskCollaborationStore") ApprovalTaskCollaborationStore delegate,
        ApprovalSlaService approvalSlaService,
        ApprovalRequestEvidenceProvider approvalRequestEvidenceProvider
    ) {
        return new SlaAwareApprovalTaskCollaborationStore(
            delegate,
            approvalSlaService,
            approvalRequestEvidenceProvider
        );
    }

    @Bean
    ApprovalTaskOutcomeContext approvalTaskOutcomeContext() {
        return new ApprovalTaskOutcomeContext();
    }

    @Bean
    @Primary
    ApprovalProjectionStore collaborationAwareApprovalProjectionStore(
        @Qualifier("slaAwareApprovalProjectionStore") ApprovalProjectionStore delegate,
        @Qualifier("slaAwareApprovalTaskCollaborationStore")
        ApprovalTaskCollaborationStore approvalTaskCollaborationStore,
        ApprovalTaskOutcomeContext approvalTaskOutcomeContext
    ) {
        return new CollaborationAwareApprovalProjectionStore(
            delegate,
            approvalTaskCollaborationStore,
            approvalTaskOutcomeContext
        );
    }

    @Bean
    ApprovalTaskCollaborationService approvalTaskCollaborationService(
        IdempotencyGuard idempotencyGuard,
        ApprovalIdentityDirectory approvalIdentityDirectory,
        @Qualifier("slaAwareApprovalTaskCollaborationStore")
        ApprovalTaskCollaborationStore approvalTaskCollaborationStore,
        ApprovalProjectionStore approvalProjectionStore,
        AuditEventSink auditEventSink,
        Clock approvalClock
    ) {
        return new ApprovalTaskCollaborationService(
            idempotencyGuard,
            approvalIdentityDirectory,
            approvalTaskCollaborationStore,
            approvalProjectionStore,
            auditEventSink,
            approvalClock,
            UUID::randomUUID
        );
    }

    @Bean
    ApprovalTaskOutcomeInterceptor approvalTaskOutcomeInterceptor(
        ApprovalTaskOutcomeContext approvalTaskOutcomeContext
    ) {
        return new ApprovalTaskOutcomeInterceptor(approvalTaskOutcomeContext);
    }

    @Bean
    WebMvcConfigurer approvalTaskOutcomeWebMvcConfigurer(
        ApprovalTaskOutcomeInterceptor approvalTaskOutcomeInterceptor
    ) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(approvalTaskOutcomeInterceptor)
                    .addPathPatterns("/api/approval/tasks/**");
            }
        };
    }
}
