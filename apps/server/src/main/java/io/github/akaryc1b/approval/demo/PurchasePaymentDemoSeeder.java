package io.github.akaryc1b.approval.demo;

import io.github.akaryc1b.approval.application.ApprovalAttachmentService;
import io.github.akaryc1b.approval.application.ApprovalDesignCommands;
import io.github.akaryc1b.approval.application.ApprovalDesignService;
import io.github.akaryc1b.approval.application.ApprovalEffectiveReleaseService.ActivationCommand;
import io.github.akaryc1b.approval.application.ApprovalFormDesignService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseActivationService;
import io.github.akaryc1b.approval.application.ApprovalProcessReleaseLifecycleService;
import io.github.akaryc1b.approval.application.ApprovalReleaseDeploymentService;
import io.github.akaryc1b.approval.application.ApprovalReleasePreflightService;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService.InstanceDetails;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService.StartCommand;
import io.github.akaryc1b.approval.application.PurchasePaymentApplicationService.StartResult;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore;
import io.github.akaryc1b.approval.application.port.ApprovalAttachmentStore.AttachmentSummary;
import io.github.akaryc1b.approval.application.port.ApprovalCommentStore;
import io.github.akaryc1b.approval.application.port.ApprovalMessageStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.InstanceStatus;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskProjection;
import io.github.akaryc1b.approval.application.port.ApprovalProjectionStore.TaskStatus;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleaseDeployment;
import io.github.akaryc1b.approval.domain.form.FormDesignDraft;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies the explicitly enabled local scenario through the governed production services.
 *
 * <p>The seed creates and publishes the exact Form Package and Approval Release Package, deploys
 * and activates that immutable release, then invokes the singleton production
 * PurchasePaymentApplicationService. It never constructs the legacy effective-release bridge.</p>
 */
public final class PurchasePaymentDemoSeeder {

    private static final String TRACE_ID = "demo-purchase-payment-seed-v1";
    private static final String MANAGER_TASK_KEY = "managerApproval";
    private static final String DEPLOYMENT_TARGET = "default";
    private static final int FORM_PACKAGE_VERSION = 1;
    private static final int RELEASE_VERSION = 1;

    private final PurchasePaymentDemoScenario scenario;
    private final ApprovalFormDesignService formDesignService;
    private final ApprovalDesignService designService;
    private final ApprovalReleasePreflightService preflightService;
    private final ApprovalProcessReleaseLifecycleService releaseLifecycleService;
    private final ApprovalReleaseDeploymentService deploymentService;
    private final ApprovalProcessReleaseActivationService activationService;
    private final PurchasePaymentApplicationService purchasePaymentService;
    private final IdempotencyGuard idempotencyGuard;
    private final ApprovalProjectionStore projections;
    private final ApprovalAttachmentStore attachmentStore;
    private final ApprovalMessageStore messageStore;
    private final ApprovalCommentStore commentStore;
    private final Clock clock;

    public PurchasePaymentDemoSeeder(
        PurchasePaymentDemoScenario scenario,
        ApprovalFormDesignService formDesignService,
        ApprovalDesignService designService,
        ApprovalReleasePreflightService preflightService,
        ApprovalProcessReleaseLifecycleService releaseLifecycleService,
        ApprovalReleaseDeploymentService deploymentService,
        ApprovalProcessReleaseActivationService activationService,
        PurchasePaymentApplicationService purchasePaymentService,
        IdempotencyGuard idempotencyGuard,
        ApprovalProjectionStore projections,
        ApprovalAttachmentStore attachmentStore,
        ApprovalMessageStore messageStore,
        ApprovalCommentStore commentStore,
        Clock clock
    ) {
        this.scenario = Objects.requireNonNull(scenario, "scenario must not be null");
        this.formDesignService = Objects.requireNonNull(
            formDesignService,
            "formDesignService must not be null"
        );
        this.designService = Objects.requireNonNull(designService, "designService must not be null");
        this.preflightService = Objects.requireNonNull(
            preflightService,
            "preflightService must not be null"
        );
        this.releaseLifecycleService = Objects.requireNonNull(
            releaseLifecycleService,
            "releaseLifecycleService must not be null"
        );
        this.deploymentService = Objects.requireNonNull(
            deploymentService,
            "deploymentService must not be null"
        );
        this.activationService = Objects.requireNonNull(
            activationService,
            "activationService must not be null"
        );
        this.purchasePaymentService = Objects.requireNonNull(
            purchasePaymentService,
            "purchasePaymentService must not be null"
        );
        this.idempotencyGuard = Objects.requireNonNull(
            idempotencyGuard,
            "idempotencyGuard must not be null"
        );
        this.projections = Objects.requireNonNull(projections, "projections must not be null");
        this.attachmentStore = Objects.requireNonNull(
            attachmentStore,
            "attachmentStore must not be null"
        );
        this.messageStore = Objects.requireNonNull(messageStore, "messageStore must not be null");
        this.commentStore = Objects.requireNonNull(commentStore, "commentStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public synchronized PurchasePaymentDemoSeedState.SeedEvidence apply() {
        ReleaseEvidence release = provisionEffectiveRelease();

        List<PurchasePaymentDemoSeedState.AttachmentEvidence> attachmentEvidence =
            uploadAttachments();
        List<String> attachmentIds = attachmentEvidence.stream()
            .map(value -> value.attachmentId().toString())
            .toList();

        RequestContext startContext = context(
            scenario.assigneeRules().initiatorUserId().value(),
            "demo-seed-start-request-v2",
            "demo-seed-start-v2"
        );
        StartResult started = PurchasePaymentDemoRequestEvidenceScope.call(
            startContext,
            () -> purchasePaymentService.start(new StartCommand(
                startContext,
                scenario.request().businessKey(),
                scenario.request().amount(),
                scenario.request().supplier(),
                scenario.request().purchaseOrderReference(),
                attachmentIds,
                scenario.assigneeRules()
            ))
        );

        InstanceDetails details = purchasePaymentService.findInstance(
            scenario.tenantId(),
            started.instanceId()
        ).orElseThrow(() -> new IllegalStateException(
            "demo instance projection is missing after start"
        ));
        validateStartedScenario(started, details, attachmentIds);

        return new PurchasePaymentDemoSeedState.SeedEvidence(
            scenario.tenantId(),
            scenario.request().businessKey(),
            started.instanceId(),
            started.status(),
            release.definitionKey(),
            release.engineDefinitionId(),
            started.activeTasks().stream().map(TaskProjection::taskId).toList(),
            attachmentEvidence,
            started.startedAt()
        );
    }

    private ReleaseEvidence provisionEffectiveRelease() {
        RequestContext formCreateContext = context(
            scenario.administratorId(),
            "demo-seed-form-create-request-v1",
            "demo-seed-form-create-v1"
        );
        FormDesignDraft formDraft = PurchasePaymentDemoRequestEvidenceScope.call(
            formCreateContext,
            () -> formDesignService.createFromPurchasePaymentTemplate(
                new ApprovalFormDesignService.CreateCommand(
                    formCreateContext,
                    PurchasePaymentTemplate.FORM_KEY,
                    "Purchase Payment Demo Form",
                    PurchasePaymentTemplate.FORM_VERSION,
                    PurchasePaymentTemplate.UI_SCHEMA_VERSION
                )
            )
        );

        RequestContext formPublishContext = context(
            scenario.administratorId(),
            "demo-seed-form-publish-request-v1",
            "demo-seed-form-publish-v1"
        );
        PurchasePaymentDemoRequestEvidenceScope.call(
            formPublishContext,
            () -> formDesignService.publish(new ApprovalFormDesignService.PublishCommand(
                formPublishContext,
                formDraft.draftId(),
                formDraft.revision(),
                FORM_PACKAGE_VERSION
            ))
        );

        RequestContext designCreateContext = context(
            scenario.administratorId(),
            "demo-seed-design-create-request-v1",
            "demo-seed-design-create-v1"
        );
        ApprovalDesignDraft designDraft = PurchasePaymentDemoRequestEvidenceScope.call(
            designCreateContext,
            () -> designService.createFromPurchasePaymentTemplate(
                new ApprovalDesignCommands.Create(
                    designCreateContext,
                    PurchasePaymentTemplate.DEFINITION_KEY,
                    "Purchase Payment Demo",
                    PurchasePaymentTemplate.DEFINITION_VERSION,
                    FORM_PACKAGE_VERSION
                )
            )
        );

        ApprovalReleasePreflightService.PreflightReport publicationPreflight =
            preflightService.preflightPublication(
                new ApprovalReleasePreflightService.PublicationRequest(
                    scenario.tenantId(),
                    designDraft.draftId(),
                    designDraft.revision(),
                    PurchasePaymentTemplate.DEFINITION_KEY,
                    PurchasePaymentTemplate.DEFINITION_VERSION,
                    RELEASE_VERSION,
                    DEPLOYMENT_TARGET,
                    null
                )
            );
        requirePreflight(publicationPreflight, true);

        RequestContext releasePublishContext = context(
            scenario.administratorId(),
            "demo-seed-release-publish-request-v1",
            "demo-seed-release-publish-v1"
        );
        ApprovalProcessReleaseLifecycleService.PublishResult published =
            PurchasePaymentDemoRequestEvidenceScope.call(
                releasePublishContext,
                () -> releaseLifecycleService.publish(
                    new ApprovalProcessReleaseLifecycleService.PublishCommand(
                        new ApprovalDesignCommands.Publish(
                            releasePublishContext,
                            designDraft.draftId(),
                            designDraft.revision(),
                            PurchasePaymentTemplate.DEFINITION_VERSION,
                            RELEASE_VERSION,
                            DEPLOYMENT_TARGET,
                            publicationPreflight.preflightHash(),
                            publicationPreflight.warningCodes(),
                            null
                        ),
                        "Seed canonical purchase-payment demo release"
                    )
                )
            );

        ApprovalReleasePreflightService.PreflightReport deploymentPreflight =
            preflightService.preflightDeployment(
                new ApprovalReleasePreflightService.DeploymentRequest(
                    scenario.tenantId(),
                    published.publication().releasePackage().definitionKey(),
                    published.publication().releasePackage().releaseVersion(),
                    DEPLOYMENT_TARGET
                )
            );
        requirePreflight(deploymentPreflight, false);

        RequestContext deployContext = context(
            scenario.administratorId(),
            "demo-seed-release-deploy-request-v1",
            "demo-seed-release-deploy-v1"
        );
        ApprovalReleaseDeploymentService.DeploymentResult deployed =
            PurchasePaymentDemoRequestEvidenceScope.call(
                deployContext,
                () -> deploymentService.deploy(
                    new ApprovalReleaseDeploymentService.DeployCommand(
                        deployContext,
                        published.publication().releasePackage().definitionKey(),
                        published.publication().releasePackage().releaseVersion(),
                        DEPLOYMENT_TARGET,
                        deploymentPreflight.preflightHash(),
                        deploymentPreflight.warningCodes()
                    )
                )
            );
        if (deployed.deployment().status() != ApprovalReleaseDeployment.Status.DEPLOYED) {
            throw new IllegalStateException("demo Release Package deployment did not succeed");
        }

        RequestContext activateContext = context(
            scenario.administratorId(),
            "demo-seed-release-activate-request-v1",
            "demo-seed-release-activate-v1"
        );
        PurchasePaymentDemoRequestEvidenceScope.call(
            activateContext,
            () -> activationService.activate(new ActivationCommand(
                activateContext,
                published.publication().releasePackage().definitionKey(),
                published.publication().releasePackage().releaseVersion(),
                null,
                "Activate canonical purchase-payment demo release"
            ))
        );

        return new ReleaseEvidence(
            published.publication().releasePackage().definitionKey(),
            deployed.deployment().engineDefinitionId()
        );
    }

    private static void requirePreflight(
        ApprovalReleasePreflightService.PreflightReport report,
        boolean publication
    ) {
        boolean permitted = publication ? report.publishable() : report.deployable();
        if (permitted && report.errors().isEmpty()) {
            return;
        }
        String codes = report.errors().stream()
            .map(ApprovalReleasePreflightService.Issue::code)
            .distinct()
            .sorted()
            .reduce((left, right) -> left + ", " + right)
            .orElse("UNKNOWN_PREFLIGHT_FAILURE");
        throw new IllegalStateException("demo release preflight failed: " + codes);
    }

    private List<PurchasePaymentDemoSeedState.AttachmentEvidence> uploadAttachments() {
        List<PurchasePaymentDemoSeedState.AttachmentEvidence> result = new ArrayList<>();
        for (PurchasePaymentDemoScenario.AttachmentFixture fixture : scenario.attachments()) {
            ApprovalAttachmentService service = new ApprovalAttachmentService(
                idempotencyGuard,
                attachmentStore,
                projections,
                messageStore,
                commentStore,
                clock,
                () -> fixture.attachmentId()
            );
            RequestContext attachmentContext = context(
                scenario.assigneeRules().initiatorUserId().value(),
                "demo-seed-attachment-" + fixture.logicalId() + "-request-v1",
                "demo-seed-attachment-" + fixture.logicalId() + "-v1"
            );
            AttachmentSummary summary = PurchasePaymentDemoRequestEvidenceScope.call(
                attachmentContext,
                () -> service.upload(new ApprovalAttachmentService.UploadCommand(
                    attachmentContext,
                    fixture.fileName(),
                    fixture.contentType(),
                    fixture.content()
                ))
            );
            if (!fixture.attachmentId().equals(summary.attachmentId())) {
                throw new IllegalStateException(
                    "idempotent attachment result did not preserve the fixed UUID"
                );
            }
            result.add(new PurchasePaymentDemoSeedState.AttachmentEvidence(
                fixture.logicalId(),
                summary.attachmentId(),
                summary.fileName(),
                summary.sha256(),
                summary.bound()
            ));
        }
        return List.copyOf(result);
    }

    private void validateStartedScenario(
        StartResult started,
        InstanceDetails details,
        List<String> attachmentIds
    ) {
        if (started.status() != InstanceStatus.RUNNING) {
            throw new IllegalStateException("demo instance must start in RUNNING status");
        }
        if (!scenario.request().businessKey().equals(details.instance().businessKey())) {
            throw new IllegalStateException("demo business key changed during start");
        }
        if (!attachmentIds.equals(details.instance().attachmentIds())) {
            throw new IllegalStateException("demo attachment IDs changed during start");
        }
        if (started.activeTasks().size() != 1) {
            throw new IllegalStateException("demo start must create exactly one active task");
        }
        TaskProjection managerTask = started.activeTasks().getFirst();
        String expectedManager = scenario.requireUser(
            scenario.assigneeRules().initiatorUserId().value()
        ).managerId();
        if (!MANAGER_TASK_KEY.equals(managerTask.taskDefinitionKey())) {
            throw new IllegalStateException("demo first task must be managerApproval");
        }
        if (!expectedManager.equals(managerTask.assigneeId())) {
            throw new IllegalStateException("demo first task must be assigned to the manager");
        }
        if (details.tasks().size() != 1) {
            throw new IllegalStateException("demo projection must retain exactly one active task");
        }
        TaskProjection storedTask = details.tasks().getFirst();
        boolean stableIdentityMatches = managerTask.taskId().equals(storedTask.taskId())
            && managerTask.instanceId().equals(storedTask.instanceId())
            && managerTask.tenantId().equals(storedTask.tenantId())
            && managerTask.engineTaskId().equals(storedTask.engineTaskId())
            && managerTask.taskDefinitionKey().equals(storedTask.taskDefinitionKey())
            && managerTask.name().equals(storedTask.name())
            && managerTask.assigneeId().equals(storedTask.assigneeId())
            && storedTask.status() == TaskStatus.PENDING;
        if (!stableIdentityMatches) {
            throw new IllegalStateException(
                "demo task stable identity changed after projection round-trip"
            );
        }
    }

    private RequestContext context(
        String operatorId,
        String requestId,
        String idempotencyKey
    ) {
        return new RequestContext(
            scenario.tenantId(),
            operatorId,
            requestId,
            idempotencyKey,
            TRACE_ID
        );
    }

    private record ReleaseEvidence(String definitionKey, String engineDefinitionId) {
        private ReleaseEvidence {
            definitionKey = Objects.requireNonNull(definitionKey);
            engineDefinitionId = Objects.requireNonNull(engineDefinitionId);
        }
    }
}
