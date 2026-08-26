package io.github.akaryc1b.approval.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.ApprovalDesignService;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService.SubmissionCommand;
import io.github.akaryc1b.approval.application.ApprovalFormSubmissionService.SubmissionResult;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseActivationService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseLifecycleService;
import io.github.akaryc1b.approval.application.ApprovalReleaseDeploymentService;
import io.github.akaryc1b.approval.application.ApprovalReleasePreflightService;
import io.github.akaryc1b.approval.application.ConnectorPurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.application.port.PurchasePaymentAssigneeResolver;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;

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

    private static final String TRACE_ID = "demo-purchase-payment-seed-v1";
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
        ApprovalFormDesignService approvalFormDesignService,
        ApprovalDesignService approvalDesignService,
        ApprovalReleasePreflightService approvalReleasePreflightService,
        ApprovalProcessReleaseLifecycleService approvalProcessReleaseLifecycleService,
        ApprovalReleaseDeploymentService approvalReleaseDeploymentService,
        ApprovalProcessReleaseActivationService approvalProcessReleaseActivationService,
        PurchasePaymentApplicationService purchasePaymentApplicationService,
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore approvalProjectionStore,
        ApprovalAttachmentStore approvalAttachmentStore,
        ApprovalMessageStore approvalMessageStore,
        ApprovalCommentStore approvalCommentStore,
        Clock approvalClock
    ) {
        return new PurchasePaymentDemoSeeder(
            scenario,
            approvalFormDesignService,
            approvalDesignService,
            approvalReleasePreflightService,
            approvalProcessReleaseLifecycleService,
            approvalReleaseDeploymentService,
            approvalProcessReleaseActivationService,
            purchasePaymentApplicationService,
            idempotencyGuard,
            approvalProjectionStore,
            approvalAttachmentStore,
            approvalMessageStore,
            approvalCommentStore,
            approvalClock
        );
    }

    @Bean
    ApplicationRunner purchasePaymentDemoSeedRunner(
        PurchasePaymentDemoSeeder seeder,
        PurchasePaymentDemoSeedState state,
        PurchasePaymentDemoScenario scenario,
        ApprovalFormSubmissionService formSubmissionService
    ) {
        return arguments -> {
            PurchasePaymentDemoSeedState.SeedEvidence initialEvidence = seeder.apply();
            List<String> attachmentIds = initialEvidence.attachments().stream()
                .map(value -> value.attachmentId().toString())
                .toList();
            RequestContext formContext = new RequestContext(
                scenario.tenantId(),
                scenario.assigneeRules().initiatorUserId().value(),
                "demo-seed-form-submit-request-v1",
                "demo-seed-form-submit-v1",
                TRACE_ID
            );
            SubmissionResult submission = PurchasePaymentDemoRequestEvidenceScope.call(
                formContext,
                () -> formSubmissionService.submit(new SubmissionCommand(
                    formContext,
                    PurchasePaymentTemplate.DEFINITION_KEY,
                    PurchasePaymentTemplate.FORM_VERSION,
                    scenario.request().businessKey(),
                    Map.of(
                        "amount", scenario.request().amount(),
                        "supplier", scenario.request().supplier(),
                        "purchaseOrderReference", scenario.request().purchaseOrderReference(),
                        "attachments", attachmentIds
                    ),
                    Map.of(
                        "connectorKey", scenario.assigneeRules().connectorKey(),
                        "initiatorUserId", Map.of(
                            "source", scenario.assigneeRules().initiatorUserId().source(),
                            "objectType", scenario.assigneeRules().initiatorUserId().objectType(),
                            "value", scenario.assigneeRules().initiatorUserId().value()
                        ),
                        "financeReviewerRoleCode",
                        scenario.assigneeRules().financeReviewerRoleCode(),
                        "financeApproverPositionCode",
                        scenario.assigneeRules().financeApproverPositionCode(),
                        "maximumFinanceApprovers",
                        scenario.assigneeRules().maximumFinanceApprovers()
                    )
                ))
            );
            if (!initialEvidence.instanceId().equals(submission.instanceId())) {
                throw new IllegalStateException(
                    "demo form submission did not preserve the seeded instance identity"
                );
            }
            if (!initialEvidence.businessKey().equals(submission.businessKey())) {
                throw new IllegalStateException(
                    "demo form submission did not preserve the seeded business key"
                );
            }

            PurchasePaymentDemoSeedState.SeedEvidence evidence = markAttachmentsBound(
                initialEvidence
            );
            state.record(evidence);
            LOGGER.info(
                "PURCHASE_PAYMENT_DEMO_FORM_SUBMISSION_APPLIED tenantId={} businessKey={} "
                    + "instanceId={} submissionId={} replayed={}",
                evidence.tenantId(),
                evidence.businessKey(),
                evidence.instanceId(),
                submission.submissionId(),
                submission.replayedExistingSubmission()
            );
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

    private static PurchasePaymentDemoSeedState.SeedEvidence markAttachmentsBound(
        PurchasePaymentDemoSeedState.SeedEvidence evidence
    ) {
        return new PurchasePaymentDemoSeedState.SeedEvidence(
            evidence.tenantId(),
            evidence.businessKey(),
            evidence.instanceId(),
            evidence.status(),
            evidence.definitionKey(),
            evidence.engineDefinitionId(),
            evidence.taskIds(),
            evidence.attachments().stream()
                .map(value -> new PurchasePaymentDemoSeedState.AttachmentEvidence(
                    value.logicalId(),
                    value.attachmentId(),
                    value.fileName(),
                    value.sha256(),
                    true
                ))
                .toList(),
            evidence.seededAt()
        );
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
