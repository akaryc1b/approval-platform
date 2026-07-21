package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalSlaService;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator;
import io.github.akaryc1b.approval.application.SlaAwareApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalRequestEvidenceProvider;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaStore;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ApprovalSlaConfiguration {

    @Bean
    JdbcApprovalSlaStore approvalSlaStore(
        DataSource dataSource,
        PlatformTransactionManager transactionManager
    ) {
        return new JdbcApprovalSlaStore(dataSource, transactionManager);
    }

    @Bean
    ApprovalWorkingTimeCalculator approvalWorkingTimeCalculator() {
        return new ApprovalWorkingTimeCalculator();
    }

    @Bean
    ApprovalSlaService approvalSlaService(
        JdbcApprovalSlaStore approvalSlaStore,
        ApprovalWorkingTimeCalculator approvalWorkingTimeCalculator,
        Clock approvalClock
    ) {
        return new ApprovalSlaService(
            approvalSlaStore,
            approvalWorkingTimeCalculator,
            approvalClock,
            UUID::randomUUID
        );
    }

    @Bean
    ApprovalRequestEvidenceProvider approvalRequestEvidenceProvider() {
        return new MdcApprovalRequestEvidenceProvider();
    }

    @Bean
    ApprovalProjectionStore slaAwareApprovalProjectionStore(
        @Qualifier("approvalProjectionStore") ApprovalProjectionStore delegate,
        ApprovalSlaService approvalSlaService,
        ApprovalRequestEvidenceProvider approvalRequestEvidenceProvider
    ) {
        return new SlaAwareApprovalProjectionStore(
            delegate,
            approvalSlaService,
            approvalRequestEvidenceProvider
        );
    }
}
