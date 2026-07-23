package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.RuntimeBindingEnforcingProjectionStore;
import io.github.akaryc1b.approval.application.RuntimeBindingRecordingAuditEventSink;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Production-wide immutable runtime binding enforcement for release-bound instances. */
@Configuration(proxyBeanMethods = false)
public class ApprovalRuntimeBindingEvidenceConfiguration {

    @Bean
    @Primary
    ApprovalProjectionStore runtimeBindingEnforcingProjectionStore(
        @Qualifier("approvalProjectionStore") ApprovalProjectionStore delegate,
        ApprovalRuntimeBindingStore approvalRuntimeBindingStore
    ) {
        return new RuntimeBindingEnforcingProjectionStore(
            delegate,
            approvalRuntimeBindingStore
        );
    }

    @Bean
    @Primary
    AuditEventSink runtimeBindingRecordingAuditEventSink(
        @Qualifier("auditEventSink") AuditEventSink delegate,
        @Qualifier("approvalProjectionStore") ApprovalProjectionStore rawProjectionStore,
        ApprovalReleasePackageStore approvalReleasePackageStore,
        ApprovalReleaseDeploymentStore approvalReleaseDeploymentStore,
        ApprovalRuntimeBindingStore approvalRuntimeBindingStore,
        ApprovalReleasePackageHasher approvalReleasePackageHasher
    ) {
        return new RuntimeBindingRecordingAuditEventSink(
            delegate,
            rawProjectionStore,
            approvalReleasePackageStore,
            approvalReleaseDeploymentStore,
            approvalRuntimeBindingStore,
            approvalReleasePackageHasher
        );
    }
}
