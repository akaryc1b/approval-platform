package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalMigrationExactVerificationService;
import io.github.akaryc1b.approval.application.ApprovalMigrationRuntimeBindingCasService;
import io.github.akaryc1b.approval.application.ApprovalMigrationSingleInstanceExecutor;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExactVerificationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.engine.ProcessInstanceMigrationPort;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort;
import io.github.akaryc1b.approval.engine.flowable.FlowableProcessInstanceMigrationAdapter;
import io.github.akaryc1b.approval.engine.flowable.FlowableProcessInstanceVerificationAdapter;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationEngineExecutionStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationExactVerificationStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationRuntimeBindingCasStore;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessMigrationService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;

/** Default-disabled internal wiring for one-shot M5-D3 through D5 operations. */
@Configuration(proxyBeanMethods = false)
public class ApprovalMigrationExecutionConfiguration {

    @Bean
    ProcessInstanceMigrationPort processInstanceMigrationPort(
        RepositoryService repositoryService,
        RuntimeService runtimeService,
        TaskService taskService,
        ManagementService managementService,
        ProcessMigrationService processMigrationService
    ) {
        return new FlowableProcessInstanceMigrationAdapter(
            repositoryService,
            runtimeService,
            taskService,
            managementService,
            processMigrationService
        );
    }

    @Bean
    ProcessInstanceVerificationPort processInstanceVerificationPort(
        RepositoryService repositoryService,
        RuntimeService runtimeService,
        TaskService taskService,
        ManagementService managementService,
        HistoryService historyService
    ) {
        return new FlowableProcessInstanceVerificationAdapter(
            repositoryService,
            runtimeService,
            taskService,
            managementService,
            historyService
        );
    }

    @Bean
    ApprovalMigrationEngineExecutionStore approvalMigrationEngineExecutionStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEventSink
    ) {
        return new JdbcApprovalMigrationEngineExecutionStore(
            dataSource,
            objectMapper,
            transactionManager,
            auditEventSink,
            UUID::randomUUID
        );
    }

    @Bean
    ApprovalMigrationExactVerificationStore approvalMigrationExactVerificationStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEventSink
    ) {
        return new JdbcApprovalMigrationExactVerificationStore(
            dataSource,
            objectMapper,
            transactionManager,
            auditEventSink,
            UUID::randomUUID
        );
    }

    @Bean
    ApprovalMigrationRuntimeBindingCasStore approvalMigrationRuntimeBindingCasStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEventSink
    ) {
        return new JdbcApprovalMigrationRuntimeBindingCasStore(
            dataSource,
            objectMapper,
            transactionManager,
            auditEventSink,
            UUID::randomUUID
        );
    }

    @Bean
    ApprovalMigrationSingleInstanceExecutor approvalMigrationSingleInstanceExecutor(
        ApprovalMigrationEngineExecutionStore executionStore,
        ProcessInstanceMigrationPort engineMigration
    ) {
        return new ApprovalMigrationSingleInstanceExecutor(
            executionStore,
            engineMigration,
            Clock.systemUTC()
        );
    }

    @Bean
    ApprovalMigrationExactVerificationService approvalMigrationExactVerificationService(
        ApprovalMigrationExactVerificationStore verificationStore,
        ProcessInstanceVerificationPort engineVerification
    ) {
        return new ApprovalMigrationExactVerificationService(
            verificationStore,
            engineVerification,
            Clock.systemUTC()
        );
    }

    @Bean
    ApprovalMigrationRuntimeBindingCasService approvalMigrationRuntimeBindingCasService(
        ApprovalMigrationRuntimeBindingCasStore bindingCasStore
    ) {
        return new ApprovalMigrationRuntimeBindingCasService(
            bindingCasStore,
            Clock.systemUTC()
        );
    }

    @Bean
    ApprovalMigrationSingleInstanceExecutor.OneShotRunner approvalMigrationOneShotExecutionRunner(
        @Value("${approval.migration.execution.enabled:false}") boolean executionEnabled,
        @Value("${approval.migration.worker.enabled:false}") boolean workerEnabled,
        ApprovalMigrationSingleInstanceExecutor executor
    ) {
        return new ApprovalMigrationSingleInstanceExecutor.OneShotRunner(
            executionEnabled,
            workerEnabled,
            executor
        );
    }

    @Bean
    ApprovalMigrationExactVerificationService.OneShotRunner approvalMigrationOneShotVerificationRunner(
        @Value("${approval.migration.execution.enabled:false}") boolean executionEnabled,
        @Value("${approval.migration.worker.enabled:false}") boolean workerEnabled,
        ApprovalMigrationExactVerificationService service
    ) {
        return new ApprovalMigrationExactVerificationService.OneShotRunner(
            executionEnabled,
            workerEnabled,
            service
        );
    }

    @Bean
    ApprovalMigrationRuntimeBindingCasService.OneShotRunner approvalMigrationOneShotBindingCasRunner(
        @Value("${approval.migration.execution.enabled:false}") boolean executionEnabled,
        @Value("${approval.migration.worker.enabled:false}") boolean workerEnabled,
        ApprovalMigrationRuntimeBindingCasService service
    ) {
        return new ApprovalMigrationRuntimeBindingCasService.OneShotRunner(
            executionEnabled,
            workerEnabled,
            service
        );
    }
}
