package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalMigrationSingleInstanceExecutor;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.engine.ProcessInstanceMigrationPort;
import io.github.akaryc1b.approval.engine.flowable.FlowableProcessInstanceMigrationAdapter;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationEngineExecutionStore;
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

/** Default-disabled internal wiring for one-shot M5-D3 execution. */
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
}
