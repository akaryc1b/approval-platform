package io.github.akaryc1b.approval.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalDesignService;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseActivationService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseLifecycleService;
import io.github.akaryc1b.approval.application.ApprovalReleaseDeploymentService;
import io.github.akaryc1b.approval.application.ApprovalReleasePreflightService;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.config.GenericConnectorProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.time.Clock;

/** Wiring only: reuse the exact local Seed and signed sandbox implementations and resource files. */
@Configuration(proxyBeanMethods = false)
@Profile("online-demo")
@ConditionalOnProperty(name = "approval.evaluation.workflow-enabled", havingValue = "true")
@EnableConfigurationProperties(PurchasePaymentDemoPaymentSandboxConfiguration.Properties.class)
@DependsOn("onlineEvaluationBusinessIdentity")
public class OnlineEvaluationWorkflowSeedConfiguration {
    private final PurchasePaymentDemoConfiguration seed = new PurchasePaymentDemoConfiguration();
    private final PurchasePaymentDemoPaymentSandboxConfiguration sandbox = new PurchasePaymentDemoPaymentSandboxConfiguration();

    @Bean
    PurchasePaymentDemoScenario purchasePaymentDemoScenario(
        @Qualifier("approvalPersistenceObjectMapper") ObjectMapper mapper
    ) throws IOException { return seed.purchasePaymentDemoScenario(mapper); }

    @Bean
    @Primary
    PurchasePaymentDemoOrganizationConnector purchasePaymentDemoOrganizationConnector(PurchasePaymentDemoScenario scenario) {
        return seed.purchasePaymentDemoOrganizationConnector(scenario);
    }

    @Bean
    @Primary
    PurchasePaymentAssigneeResolver purchasePaymentDemoAssigneeResolver(
        PurchasePaymentDemoOrganizationConnector connector, Clock approvalClock
    ) { return seed.purchasePaymentDemoAssigneeResolver(connector, approvalClock); }

    @Bean
    PurchasePaymentDemoSeedState purchasePaymentDemoSeedState() { return seed.purchasePaymentDemoSeedState(); }

    @Bean
    PurchasePaymentDemoSeeder purchasePaymentDemoSeeder(
        PurchasePaymentDemoScenario scenario, ApprovalFormDesignService forms, ApprovalDesignService designs,
        ApprovalReleasePreflightService preflight, ApprovalProcessReleaseLifecycleService lifecycle,
        ApprovalReleaseDeploymentService deployment, ApprovalProcessReleaseActivationService activation,
        PurchasePaymentApplicationService purchases, IdempotencyGuard idempotency, ApprovalProjectionStore projections,
        ApprovalAttachmentStore attachments, ApprovalMessageStore messages, ApprovalCommentStore comments, Clock approvalClock
    ) {
        return seed.purchasePaymentDemoSeeder(scenario, forms, designs, preflight, lifecycle, deployment, activation,
            purchases, idempotency, projections, attachments, messages, comments, approvalClock);
    }

    @Bean
    ApplicationRunner purchasePaymentDemoSeedRunner(PurchasePaymentDemoSeeder seeder, PurchasePaymentDemoSeedState state,
        PurchasePaymentDemoScenario scenario, ApprovalFormSubmissionService forms) {
        return seed.purchasePaymentDemoSeedRunner(seeder, state, scenario, forms);
    }

    @Bean("purchasePaymentDemoSeed")
    HealthIndicator purchasePaymentDemoSeedHealthIndicator(PurchasePaymentDemoSeedState state) {
        return seed.purchasePaymentDemoSeedHealthIndicator(state);
    }

    @Bean
    PurchasePaymentDemoPaymentSandbox purchasePaymentDemoPaymentSandbox(
        @Qualifier("approvalPersistenceObjectMapper") ObjectMapper mapper, Clock approvalClock,
        PurchasePaymentDemoScenario scenario, GenericConnectorProperties connector,
        PurchasePaymentDemoPaymentSandboxConfiguration.Properties properties
    ) { return sandbox.purchasePaymentDemoPaymentSandbox(mapper, approvalClock, scenario, connector, properties); }

    @Bean
    ApplicationRunner initializePurchasePaymentDemoPaymentSandbox(PurchasePaymentDemoPaymentSandbox instance) {
        return sandbox.initializePurchasePaymentDemoPaymentSandbox(instance);
    }

    @Bean
    PurchasePaymentDemoPaymentSandboxController onlineEvaluationPaymentCallback(PurchasePaymentDemoPaymentSandbox instance) {
        return new PurchasePaymentDemoPaymentSandboxController(instance);
    }
}
