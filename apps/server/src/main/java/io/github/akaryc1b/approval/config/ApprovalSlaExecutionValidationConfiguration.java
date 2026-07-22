package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ValidatingApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Installs authoritative SLA and immutable policy validation before intent enqueue. */
@Configuration(proxyBeanMethods = false)
public class ApprovalSlaExecutionValidationConfiguration {

    @Bean
    @Primary
    ApprovalSlaExecutionStore validatingApprovalSlaExecutionStore(
        @Qualifier("approvalSlaExecutionStore") ApprovalSlaExecutionStore delegate,
        JdbcApprovalSlaStore approvalSlaStore
    ) {
        return new ValidatingApprovalSlaExecutionStore(delegate, approvalSlaStore);
    }
}
