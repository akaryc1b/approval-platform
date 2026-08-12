package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalMigrationAttemptClaimService;
import io.github.akaryc1b.approval.application.ApprovalMigrationAttemptPipelineService;
import io.github.akaryc1b.approval.application.ApprovalMigrationBoundedOrchestrationService;
import io.github.akaryc1b.approval.application.ApprovalMigrationExactVerificationService;
import io.github.akaryc1b.approval.application.ApprovalMigrationReconciliationService;
import io.github.akaryc1b.approval.application.ApprovalMigrationRuntimeBindingCasService;
import io.github.akaryc1b.approval.application.ApprovalMigrationSingleInstanceExecutor;
import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.ConfiguredApprovalMigrationKillSwitch;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptPipeline;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptProvisioningStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationBindingRevisionReader;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationBoundedClaimCoordinator;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExactVerificationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationKillSwitch;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationRuntimeBindingCasStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationSafetyTelemetry;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.engine.ProcessInstanceMigrationPort;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort;
import io.github.akaryc1b.approval.engine.flowable.FlowableProcessInstanceMigrationAdapter;
import io.github.akaryc1b.approval.engine.flowable.FlowableProcessInstanceVerificationAdapter;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationAttemptClaimStoreFactory;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationAttemptProvisioningStoreFactory;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationBindingRevisionReader;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationEngineExecutionStoreFactory;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationExactVerificationStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationOrchestrationStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationReconciliationExecutionStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalMigrationRuntimeBindingCasStoreFactory;
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
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Default-disabled internal wiring for one-shot M5-D3 through D7 operations. */
@Configuration(proxyBeanMethods = false)
public class ApprovalMigrationExecutionConfiguration {

    private final Supplier<UUID> d7Identifiers = new MonotonicUuidSupplier();
    private final String orchestrationWorkerId = "m5-orchestration-" + UUID.randomUUID();

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
    ApprovalMigrationAttemptProvisioningStore approvalMigrationAttemptProvisioningStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEventSink
    ) {
        return JdbcApprovalMigrationAttemptProvisioningStoreFactory.create(
            dataSource,
            objectMapper,
            transactionManager,
            auditEventSink,
            d7Identifiers
        );
    }

    @Bean
    ApprovalMigrationAttemptClaimStore approvalMigrationAttemptClaimStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEventSink
    ) {
        return JdbcApprovalMigrationAttemptClaimStoreFactory.create(
            dataSource,
            objectMapper,
            transactionManager,
            auditEventSink,
            d7Identifiers
        );
    }

    @Bean
    ApprovalMigrationAttemptClaimService approvalMigrationAttemptClaimService(
        ApprovalMigrationAttemptProvisioningStore provisioningStore,
        ApprovalMigrationAttemptClaimStore claimStore,
        ApprovalReleasePackageHasher hasher
    ) {
        return new ApprovalMigrationAttemptClaimService(
            provisioningStore,
            claimStore,
            hasher,
            Clock.systemUTC(),
            () -> orchestrationWorkerId,
            Duration.ofMinutes(5)
        );
    }

    @Bean
    ApprovalMigrationBoundedClaimCoordinator approvalMigrationBoundedClaimCoordinator(
        ApprovalMigrationAttemptClaimService claimService
    ) {
        return (tenantId, intentId, limit, requestId, traceId) -> claimService.claim(
            new ApprovalMigrationAttemptClaimService.ClaimCommand(
                tenantId,
                intentId,
                limit,
                requestId,
                traceId
            )
        );
    }

    @Bean
    ApprovalMigrationEngineExecutionStore approvalMigrationEngineExecutionStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEventSink
    ) {
        return JdbcApprovalMigrationEngineExecutionStoreFactory.create(
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
        return JdbcApprovalMigrationRuntimeBindingCasStoreFactory.create(
            dataSource,
            objectMapper,
            transactionManager,
            auditEventSink,
            UUID::randomUUID
        );
    }

    @Bean
    ApprovalMigrationReconciliationStore approvalMigrationReconciliationStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEventSink
    ) {
        return new JdbcApprovalMigrationReconciliationExecutionStore(
            dataSource,
            objectMapper,
            transactionManager,
            auditEventSink,
            UUID::randomUUID
        );
    }

    @Bean
    ApprovalMigrationBindingRevisionReader approvalMigrationBindingRevisionReader(
        DataSource dataSource
    ) {
        return new JdbcApprovalMigrationBindingRevisionReader(dataSource);
    }

    @Bean
    ApprovalMigrationOrchestrationStore approvalMigrationOrchestrationStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEventSink
    ) {
        return new JdbcApprovalMigrationOrchestrationStore(
            dataSource,
            objectMapper,
            transactionManager,
            auditEventSink,
            d7Identifiers
        );
    }

    @Bean
    ApprovalMigrationKillSwitch approvalMigrationKillSwitch(
        @Value("${approval.migration.kill-switch.enabled:false}") boolean enabled,
        @Value("${approval.migration.kill-switch.revision:1}") long revision,
        @Value("${approval.migration.kill-switch.reason-code:CONFIGURED_OFF}") String reasonCode,
        ApprovalReleasePackageHasher hasher
    ) {
        return new ConfiguredApprovalMigrationKillSwitch(
            enabled,
            revision,
            reasonCode,
            hasher
        );
    }

    @Bean
    ApprovalMigrationSingleInstanceExecutor approvalMigrationSingleInstanceExecutor(
        ApprovalMigrationEngineExecutionStore executionStore,
        ProcessInstanceMigrationPort engineMigration,
        ApprovalMigrationSafetyTelemetry telemetry
    ) {
        return new ApprovalMigrationSingleInstanceExecutor(
            executionStore,
            engineMigration,
            Clock.systemUTC(),
            telemetry
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
    ApprovalMigrationReconciliationService approvalMigrationReconciliationService(
        ApprovalMigrationReconciliationStore reconciliationStore,
        ProcessInstanceVerificationPort engineVerification,
        ApprovalMigrationSafetyTelemetry telemetry
    ) {
        return new ApprovalMigrationReconciliationService(
            reconciliationStore,
            engineVerification,
            Clock.systemUTC(),
            Duration.ofMinutes(5),
            telemetry
        );
    }

    @Bean
    ApprovalMigrationAttemptPipeline approvalMigrationAttemptPipeline(
        ApprovalMigrationSingleInstanceExecutor executor,
        ApprovalMigrationExactVerificationService verifier,
        ApprovalMigrationRuntimeBindingCasService bindingCas,
        ApprovalMigrationBindingRevisionReader bindingRevisions,
        ApprovalMigrationSafetyTelemetry telemetry
    ) {
        return new ApprovalMigrationAttemptPipelineService(
            executor,
            verifier,
            bindingCas,
            bindingRevisions,
            telemetry
        );
    }

    @Bean
    ApprovalMigrationBoundedOrchestrationService approvalMigrationBoundedOrchestrationService(
        ApprovalMigrationOrchestrationStore orchestrationStore,
        ApprovalMigrationBoundedClaimCoordinator claims,
        ApprovalMigrationAttemptPipeline pipeline,
        ApprovalMigrationKillSwitch killSwitch,
        ApprovalMigrationSafetyTelemetry telemetry
    ) {
        return new ApprovalMigrationBoundedOrchestrationService(
            orchestrationStore,
            claims,
            pipeline,
            killSwitch,
            Clock.systemUTC(),
            telemetry
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

    @Bean
    ApprovalMigrationReconciliationService.OneShotRunner approvalMigrationOneShotReconciliationRunner(
        @Value("${approval.migration.execution.enabled:false}") boolean executionEnabled,
        @Value("${approval.migration.worker.enabled:false}") boolean workerEnabled,
        @Value("${approval.migration.reconciliation.automatic.enabled:false}")
        boolean automaticReconciliationEnabled,
        ApprovalMigrationReconciliationService service
    ) {
        return new ApprovalMigrationReconciliationService.OneShotRunner(
            executionEnabled,
            workerEnabled,
            automaticReconciliationEnabled,
            service
        );
    }

    @Bean
    ApprovalMigrationBoundedOrchestrationService.OneShotRunner
        approvalMigrationOneShotOrchestrationRunner(
            @Value("${approval.migration.execution.enabled:false}") boolean executionEnabled,
            @Value("${approval.migration.worker.enabled:false}") boolean workerEnabled,
            @Value("${approval.migration.orchestration.enabled:false}") boolean orchestrationEnabled,
            ApprovalMigrationBoundedOrchestrationService service
        ) {
        return new ApprovalMigrationBoundedOrchestrationService.OneShotRunner(
            executionEnabled,
            workerEnabled,
            orchestrationEnabled,
            service
        );
    }

    private static final class MonotonicUuidSupplier implements Supplier<UUID> {
        private final long prefix = UUID.randomUUID().getMostSignificantBits();
        private final AtomicLong sequence = new AtomicLong();

        @Override
        public UUID get() {
            long next = sequence.incrementAndGet();
            if (next < 1) {
                throw new IllegalStateException(
                    "migration evidence identifier sequence exhausted"
                );
            }
            return new UUID(prefix, next);
        }
    }
}
