package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalSlaExecutionPlanner;
import io.github.akaryc1b.approval.application.ApprovalSlaService;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator;
import io.github.akaryc1b.approval.application.SlaAwareApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalRequestEvidenceProvider;
import io.github.akaryc1b.approval.application.port.ApprovalSlaActiveTaskQuery;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import io.github.akaryc1b.approval.persistence.jdbc.ApprovalSlaExecutionCancellationGuard;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaActiveTaskQuery;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalSlaStore;
import io.github.akaryc1b.approval.persistence.jdbc.TransactionalApprovalSlaStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
    ApprovalSlaActiveTaskQuery approvalSlaActiveTaskQuery(
        DataSource dataSource,
        PlatformTransactionManager transactionManager
    ) {
        return new JdbcApprovalSlaActiveTaskQuery(dataSource, transactionManager);
    }

    @Bean
    ApprovalSlaExecutionStore approvalSlaExecutionStore(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper,
        PlatformTransactionManager transactionManager
    ) {
        return new JdbcApprovalSlaExecutionStore(
            dataSource,
            approvalPersistenceObjectMapper,
            transactionManager
        );
    }

    @Bean
    ApprovalSlaExecutionPlanner approvalSlaExecutionPlanner(
        @Value("${approval.sla.execution.max-attempts:5}") int maxAttempts
    ) {
        return new ApprovalSlaExecutionPlanner(maxAttempts, UUID::randomUUID);
    }

    @Bean
    ApprovalSlaStore transactionalApprovalSlaStore(
        JdbcApprovalSlaStore approvalSlaStore,
        ApprovalSlaExecutionStore approvalSlaExecutionStore,
        ApprovalSlaExecutionPlanner approvalSlaExecutionPlanner,
        PlatformTransactionManager transactionManager
    ) {
        return new TransactionalApprovalSlaStore(
            approvalSlaStore,
            approvalSlaExecutionStore,
            approvalSlaExecutionPlanner,
            transactionManager
        );
    }

    @Bean
    @Primary
    ApprovalSlaStore cancellationSafeApprovalSlaStore(
        @Qualifier("transactionalApprovalSlaStore") ApprovalSlaStore delegate,
        ApprovalSlaActiveTaskQuery activeTasks,
        ApprovalSlaExecutionStore executions,
        PlatformTransactionManager transactionManager
    ) {
        return ApprovalSlaExecutionCancellationGuard.wrap(
            delegate,
            activeTasks,
            executions,
            transactionManager
        );
    }

    @Bean
    ApprovalWorkingTimeCalculator approvalWorkingTimeCalculator() {
        return new ApprovalWorkingTimeCalculator();
    }

    @Bean
    ApprovalSlaService approvalSlaService(
        ApprovalSlaStore approvalSlaStore,
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
