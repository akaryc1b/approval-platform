package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.CommandFencedApprovalProjectionStore;
import io.github.akaryc1b.approval.application.RuntimeBindingEnforcingProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalInstanceCommandFence;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalInstanceCommandFence;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class ApprovalRuntimeBindingEvidenceConfiguration {

    @Bean
    public ApprovalInstanceCommandFence approvalInstanceCommandFence(DataSource dataSource) {
        return new JdbcApprovalInstanceCommandFence(dataSource);
    }

    @Bean
    @Primary
    public ApprovalProjectionStore runtimeBindingEnforcingProjectionStore(
        @Qualifier("approvalProjectionStore") ApprovalProjectionStore delegate,
        ApprovalInstanceCommandFence commandFence,
        ApprovalRuntimeBindingStore runtimeBindings,
        ApprovalReleasePackageStore packages,
        ApprovalReleasePackageHasher hasher,
        AuditEventSink auditEvents
    ) {
        ApprovalProjectionStore fenced = new CommandFencedApprovalProjectionStore(
            delegate,
            commandFence
        );
        return new RuntimeBindingEnforcingProjectionStore(
            fenced,
            runtimeBindings,
            packages,
            hasher,
            auditEvents
        );
    }
}
