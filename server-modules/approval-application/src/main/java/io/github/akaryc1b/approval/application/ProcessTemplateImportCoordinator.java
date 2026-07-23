package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.TransferEnvelope;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ImportPlan;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.PreviewRequest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantRegistrySnapshot;
import io.github.akaryc1b.approval.application.port.ProcessTemplateTenantRegistryResolver;
import io.github.akaryc1b.approval.application.port.ProcessTemplateTenantRegistryResolver.RegistryResolutionRequest;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.util.Objects;

/**
 * Resolves tenant capabilities server-side for preview and draft creation.
 * Callers cannot supply a trusted tenant registry snapshot.
 */
public final class ProcessTemplateImportCoordinator {

    private final ProcessTemplateImportPreviewService previewService;
    private final ProcessTemplateDraftCreationService draftCreationService;
    private final ProcessTemplateTenantRegistryResolver registryResolver;

    public ProcessTemplateImportCoordinator(
        ProcessTemplateImportPreviewService previewService,
        ProcessTemplateDraftCreationService draftCreationService,
        ProcessTemplateTenantRegistryResolver registryResolver
    ) {
        this.previewService = Objects.requireNonNull(previewService, "previewService");
        this.draftCreationService = Objects.requireNonNull(
            draftCreationService,
            "draftCreationService"
        );
        this.registryResolver = Objects.requireNonNull(registryResolver, "registryResolver");
    }

    public ImportPlan preview(PreviewCommand command) {
        Objects.requireNonNull(command, "command");
        TenantRegistrySnapshot registry = resolveRegistry(
            command.templatePackage(),
            command.previewRequest()
        );
        return previewService.preview(
            command.templatePackage(),
            command.packageBytes(),
            command.previewRequest(),
            registry
        );
    }

    public ProcessTemplateDraftCreationService.DraftCreationResult createDraft(
        CreateDraftCommand command
    ) {
        Objects.requireNonNull(command, "command");
        if (!command.context().tenantId().equals(command.previewRequest().targetTenantId())) {
            throw new ProcessTemplateException.CrossTenantBinding(
                "write context tenant does not match preview target tenant"
            );
        }
        TenantRegistrySnapshot registry = resolveRegistry(
            command.templatePackage(),
            command.previewRequest()
        );
        return draftCreationService.createDraft(
            new ProcessTemplateDraftCreationService.DraftCreationCommand(
                command.context(),
                command.templatePackage(),
                command.packageBytes(),
                command.previewRequest(),
                registry,
                command.expectedPlanHash(),
                command.artifactEnvelope()
            )
        );
    }

    private TenantRegistrySnapshot resolveRegistry(
        TemplatePackage templatePackage,
        PreviewRequest previewRequest
    ) {
        TenantRegistrySnapshot registry;
        try {
            registry = registryResolver.resolve(new RegistryResolutionRequest(
                previewRequest.targetTenantId(),
                templatePackage,
                previewRequest
            ));
        } catch (ProcessTemplateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProcessTemplateException.RegistryResolutionFailed(
                "target tenant registry could not be resolved safely",
                exception
            );
        }
        if (registry == null) {
            throw new ProcessTemplateException.RegistryResolutionFailed(
                "target tenant registry resolver returned no snapshot"
            );
        }
        if (!previewRequest.targetTenantId().equals(registry.tenantId())) {
            throw new ProcessTemplateException.CrossTenantBinding(
                "resolved registry tenant does not match preview target tenant"
            );
        }
        return registry;
    }

    public record PreviewCommand(
        TemplatePackage templatePackage,
        int packageBytes,
        PreviewRequest previewRequest
    ) {
        public PreviewCommand {
            templatePackage = Objects.requireNonNull(templatePackage, "templatePackage");
            previewRequest = Objects.requireNonNull(previewRequest, "previewRequest");
        }
    }

    public record CreateDraftCommand(
        RequestContext context,
        TemplatePackage templatePackage,
        int packageBytes,
        PreviewRequest previewRequest,
        String expectedPlanHash,
        TransferEnvelope artifactEnvelope
    ) {
        public CreateDraftCommand {
            context = Objects.requireNonNull(context, "context");
            templatePackage = Objects.requireNonNull(templatePackage, "templatePackage");
            previewRequest = Objects.requireNonNull(previewRequest, "previewRequest");
            expectedPlanHash = Objects.requireNonNull(expectedPlanHash, "expectedPlanHash");
            artifactEnvelope = Objects.requireNonNull(artifactEnvelope, "artifactEnvelope");
        }
    }
}
