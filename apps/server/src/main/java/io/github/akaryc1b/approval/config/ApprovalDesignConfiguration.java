package io.github.akaryc1b.approval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalBatchSimulationService;
import io.github.akaryc1b.approval.application.ApprovalDefinitionHasher;
import io.github.akaryc1b.approval.application.ApprovalDesignService;
import io.github.akaryc1b.approval.application.ApprovalReleasePackageHasher;
import io.github.akaryc1b.approval.application.ApprovalReleasePreflightService;
import io.github.akaryc1b.approval.application.port.ApprovalCompiledArtifactStore;
import io.github.akaryc1b.approval.application.port.ApprovalDefinitionVersionStore;
import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleaseDeploymentStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.persistence.jdbc.ApprovalDefinitionJacksonSupport;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalCompiledArtifactStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalDefinitionVersionStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalDesignDraftStore;
import io.github.akaryc1b.approval.persistence.jdbc.JdbcApprovalReleasePackageStore;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ApprovalDesignConfiguration {

    @Bean
    static BeanPostProcessor approvalDefinitionObjectMapperSupport() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ObjectMapper objectMapper) {
                    ApprovalDefinitionJacksonSupport.configure(objectMapper);
                }
                return bean;
            }
        };
    }

    @Bean
    ApprovalDefinitionValidator approvalDefinitionValidator() {
        return new ApprovalDefinitionValidator();
    }

    @Bean
    ApprovalDefinitionSimulator approvalDefinitionSimulator(
        ApprovalDefinitionValidator validator
    ) {
        return new ApprovalDefinitionSimulator(validator);
    }

    @Bean
    ApprovalDefinitionHasher approvalDefinitionHasher() {
        return new ApprovalDefinitionHasher();
    }

    @Bean
    ApprovalReleasePackageHasher approvalReleasePackageHasher() {
        return new ApprovalReleasePackageHasher();
    }

    @Bean
    ApprovalDesignDraftStore approvalDesignDraftStore(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new JdbcApprovalDesignDraftStore(dataSource, approvalPersistenceObjectMapper);
    }

    @Bean
    ApprovalDefinitionVersionStore approvalDefinitionVersionStore(
        DataSource dataSource,
        ObjectMapper approvalPersistenceObjectMapper
    ) {
        return new JdbcApprovalDefinitionVersionStore(
            dataSource,
            approvalPersistenceObjectMapper
        );
    }

    @Bean
    ApprovalCompiledArtifactStore approvalCompiledArtifactStore(DataSource dataSource) {
        return new JdbcApprovalCompiledArtifactStore(dataSource);
    }

    @Bean
    ApprovalReleasePackageStore approvalReleasePackageStore(DataSource dataSource) {
        return new JdbcApprovalReleasePackageStore(dataSource);
    }

    @Bean
    ApprovalBatchSimulationService approvalBatchSimulationService(
        ApprovalDesignDraftStore approvalDesignDraftStore,
        ApprovalFormPackageStore approvalFormPackageStore,
        ApprovalFormStore approvalFormStore,
        ApprovalUiSchemaStore approvalUiSchemaStore,
        ApprovalDefinitionValidator approvalDefinitionValidator,
        ApprovalDefinitionSimulator approvalDefinitionSimulator
    ) {
        return new ApprovalBatchSimulationService(
            approvalDesignDraftStore,
            approvalFormPackageStore,
            approvalFormStore,
            approvalUiSchemaStore,
            approvalDefinitionValidator,
            approvalDefinitionSimulator
        );
    }

    @Bean
    ApprovalReleasePreflightService approvalReleasePreflightService(
        ApprovalDesignDraftStore approvalDesignDraftStore,
        ApprovalDefinitionVersionStore approvalDefinitionVersionStore,
        ApprovalReleasePackageStore approvalReleasePackageStore,
        ApprovalReleaseDeploymentStore approvalReleaseDeploymentStore,
        ApprovalFormPackageStore approvalFormPackageStore,
        ApprovalFormStore approvalFormStore,
        ApprovalUiSchemaStore approvalUiSchemaStore,
        ApprovalDefinitionValidator approvalDefinitionValidator,
        ApprovalDefinitionSimulator approvalDefinitionSimulator,
        ApprovalDslCompiler approvalDslCompiler,
        ApprovalDefinitionHasher approvalDefinitionHasher,
        ApprovalReleasePackageHasher approvalReleasePackageHasher
    ) {
        return new ApprovalReleasePreflightService(
            approvalDesignDraftStore,
            approvalDefinitionVersionStore,
            approvalReleasePackageStore,
            approvalReleaseDeploymentStore,
            approvalFormPackageStore,
            approvalFormStore,
            approvalUiSchemaStore,
            approvalDefinitionValidator,
            approvalDefinitionSimulator,
            approvalDslCompiler,
            approvalDefinitionHasher,
            approvalReleasePackageHasher
        );
    }

    @Bean
    ApprovalDesignService approvalDesignService(
        IdempotencyGuard idempotencyGuard,
        ApprovalDesignDraftStore approvalDesignDraftStore,
        ApprovalDefinitionVersionStore approvalDefinitionVersionStore,
        ApprovalCompiledArtifactStore approvalCompiledArtifactStore,
        ApprovalReleasePackageStore approvalReleasePackageStore,
        ApprovalFormPackageStore approvalFormPackageStore,
        ApprovalFormStore approvalFormStore,
        ApprovalUiSchemaStore approvalUiSchemaStore,
        AuditEventSink auditEventSink,
        ApprovalDefinitionValidator approvalDefinitionValidator,
        ApprovalDefinitionSimulator approvalDefinitionSimulator,
        ApprovalDslCompiler approvalDslCompiler,
        ApprovalDefinitionHasher approvalDefinitionHasher,
        ApprovalReleasePackageHasher approvalReleasePackageHasher,
        Clock approvalClock
    ) {
        return new ApprovalDesignService(
            idempotencyGuard,
            approvalDesignDraftStore,
            approvalDefinitionVersionStore,
            approvalCompiledArtifactStore,
            approvalReleasePackageStore,
            approvalFormPackageStore,
            approvalFormStore,
            approvalUiSchemaStore,
            auditEventSink,
            approvalDefinitionValidator,
            approvalDefinitionSimulator,
            approvalDslCompiler,
            approvalDefinitionHasher,
            approvalReleasePackageHasher,
            approvalClock,
            UUID::randomUUID
        );
    }
}
