package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.CommandFencedApprovalProjectionStore;
import io.github.akaryc1b.approval.application.RuntimeBindingEnforcingProjectionStore;
import io.github.akaryc1b.approval.application.RuntimeBindingRecordingAuditEventSink;
import io.github.akaryc1b.approval.application.port.ApprovalInstanceCommandFence;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalRuntimeBindingStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalInstanceCommandFence;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Objects;

/** Production-wide immutable runtime binding enforcement and shared instance command fencing. */
@Configuration(proxyBeanMethods = false)
public class ApprovalRuntimeBindingEvidenceConfiguration {

    @Bean
    ApprovalInstanceCommandFence approvalInstanceCommandFence(DataSource dataSource) {
        return new JdbcApprovalInstanceCommandFence(dataSource);
    }

    @Bean
    @Primary
    ApprovalProjectionStore runtimeBindingEnforcingProjectionStore(
        @Qualifier("approvalProjectionStore") ApprovalProjectionStore delegate,
        ApprovalRuntimeBindingStore approvalRuntimeBindingStore,
        MeterRegistry meters,
        ApprovalInstanceCommandFence commandFence
    ) {
        Objects.requireNonNull(meters, "meters must not be null");
        ApprovalProjectionStore fenced = new CommandFencedApprovalProjectionStore(
            delegate,
            commandFence
        );
        return new RuntimeBindingEnforcingProjectionStore(
            fenced,
            approvalRuntimeBindingStore,
            (result, failureClass) -> meters.counter(
                "approval.runtime.binding.validation",
                "result", metric(result),
                "failure_class", metric(failureClass)
            ).increment()
        );
    }

    @Bean
    @Primary
    AuditEventSink runtimeBindingRecordingAuditEventSink(
        @Qualifier("notificationAwareAuditEventSink") AuditEventSink delegate,
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

    private static String metric(Enum<?> value) {
        return Objects.requireNonNull(value, "metric value must not be null")
            .name()
            .toLowerCase(Locale.ROOT);
    }
}
