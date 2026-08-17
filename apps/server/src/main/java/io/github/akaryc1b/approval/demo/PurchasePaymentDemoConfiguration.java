package io.github.akaryc1b.approval.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ConnectorPurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalBusinessEventOutbox;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Clock;

/**
 * Explicitly enabled, local-profile-only purchase-payment demo seed.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(
    prefix = "approval.demo.purchase-payment",
    name = "enabled",
    havingValue = "true"
)
public class PurchasePaymentDemoConfiguration {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(PurchasePaymentDemoConfiguration.class);

    @Bean
    PurchasePaymentDemoScenario purchasePaymentDemoScenario(
        @Qualifier("approvalPersistenceObjectMapper") ObjectMapper approvalPersistenceObjectMapper
    ) throws IOException {
        return PurchasePaymentDemoScenario.load(
            approvalPersistenceObjectMapper,
            new ClassPathResource("demo/purchase-payment-golden-path.json"),
            new ClassPathResource("demo/purchase-payment-demo-seed.json")
        );
    }

    @Bean
    @Primary
    PurchasePaymentDemoOrganizationConnector purchasePaymentDemoOrganizationConnector(
        PurchasePaymentDemoScenario scenario
    ) {
        return new PurchasePaymentDemoOrganizationConnector(scenario);
    }

    @Bean
    @Primary
    PurchasePaymentAssigneeResolver purchasePaymentDemoAssigneeResolver(
        PurchasePaymentDemoOrganizationConnector organizationConnector,
        Clock approvalClock
    ) {
        return new ConnectorPurchasePaymentAssigneeResolver(
            organizationConnector,
            approvalClock
        );
    }

    @Bean
    PurchasePaymentDemoSeedState purchasePaymentDemoSeedState() {
        return new PurchasePaymentDemoSeedState();
    }

    @Bean
    PurchasePaymentDemoSeeder purchasePaymentDemoSeeder(
        PurchasePaymentDemoScenario scenario,
        PurchasePaymentApplicationService purchasePaymentApplicationService,
        ApprovalEngine approvalEngine,
        ApprovalDslCompiler approvalDslCompiler,
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore approvalProjectionStore,
        AuditEventSink auditEventSink,
        PurchasePaymentAssigneeResolver purchasePaymentDemoAssigneeResolver,
        ApprovalBusinessEventOutbox approvalBusinessEventOutbox,
        ApprovalAttachmentStore approvalAttachmentStore,
        ApprovalMessageStore approvalMessageStore,
        ApprovalCommentStore approvalCommentStore,
        Clock approvalClock
    ) {
        return new PurchasePaymentDemoSeeder(
            scenario,
            purchasePaymentApplicationService,
            approvalEngine,
            approvalDslCompiler,
            idempotencyGuard,
            approvalProjectionStore,
            auditEventSink,
            purchasePaymentDemoAssigneeResolver,
            approvalBusinessEventOutbox,
            approvalAttachmentStore,
            approvalMessageStore,
            approvalCommentStore,
            approvalClock
        );
    }

    @Bean
    ApplicationRunner purchasePaymentDemoSeedRunner(
        PurchasePaymentDemoSeeder seeder,
        PurchasePaymentDemoSeedState state
    ) {
        return arguments -> {
            PurchasePaymentDemoSeedState.SeedEvidence evidence = seeder.apply();
            state.record(evidence);
            LOGGER.info(
                "PURCHASE_PAYMENT_DEMO_SEED_APPLIED tenantId={} businessKey={} "
                    + "instanceId={} status={} taskIds={} attachmentIds={}",
                evidence.tenantId(),
                evidence.businessKey(),
                evidence.instanceId(),
                evidence.status(),
                evidence.taskIds(),
                evidence.attachments().stream()
                    .map(value -> value.attachmentId().toString())
                    .toList()
            );
        };
    }

    @Bean("purchasePaymentDemoSeed")
    HealthIndicator purchasePaymentDemoSeedHealthIndicator(
        PurchasePaymentDemoSeedState state
    ) {
        return () -> state.evidence()
            .map(evidence -> Health.up()
                .withDetail("tenantId", evidence.tenantId())
                .withDetail("businessKey", evidence.businessKey())
                .withDetail("instanceId", evidence.instanceId().toString())
                .withDetail("status", evidence.status().name())
                .withDetail("taskIds", evidence.taskIds())
                .withDetail(
                    "attachmentIds",
                    evidence.attachments().stream()
                        .map(value -> value.attachmentId().toString())
                        .toList()
                )
                .build())
            .orElseGet(() -> Health.down()
                .withDetail("reason", "explicit local demo seed has not completed")
                .build());
    }
}
