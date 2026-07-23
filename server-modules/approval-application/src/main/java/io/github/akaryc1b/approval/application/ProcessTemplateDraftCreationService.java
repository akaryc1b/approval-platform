package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ArtifactType;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ImportCommand;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ImportResult;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.TransferEnvelope;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.BindingKind;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ImportPlan;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.IncludedArtifactReference;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.PreviewRequest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantBinding;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantRegistrySnapshot;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

import static io.github.akaryc1b.approval.application.ProcessTemplateSecurity.hash;

/**
 * Revalidates an immutable preview plan and delegates creation of one tenant-local design draft.
 * It cannot publish, deploy, activate or load component code.
 */
public final class ProcessTemplateDraftCreationService {

    private final ProcessTemplateImportPreviewService previewService;
    private final DraftImporter draftImporter;

    public ProcessTemplateDraftCreationService(
        ProcessTemplateImportPreviewService previewService,
        ApprovalArtifactTransferService artifactTransferService
    ) {
        this(previewService, artifactTransferService::importArtifact);
    }

    ProcessTemplateDraftCreationService(
        ProcessTemplateImportPreviewService previewService,
        DraftImporter draftImporter
    ) {
        this.previewService = Objects.requireNonNull(previewService, "previewService");
        this.draftImporter = Objects.requireNonNull(draftImporter, "draftImporter");
    }

    public DraftCreationResult createDraft(DraftCreationCommand command) {
        Objects.requireNonNull(command, "command");
        hash(command.expectedPlanHash(), "expectedPlanHash");
        verifyTenant(command.context(), command.previewRequest());

        ImportPlan currentPlan = previewService.preview(
            command.templatePackage(),
            command.packageBytes(),
            command.previewRequest(),
            command.tenantRegistry()
        );
        if (!constantTimeEquals(command.expectedPlanHash(), currentPlan.planHash())) {
            throw new ProcessTemplateException.StalePlan(
                "template import plan changed and must be previewed again"
            );
        }
        if (!currentPlan.importable()) {
            throw new ProcessTemplateException.DraftCreationRejected(
                "template import plan contains unresolved errors or required rebindings"
            );
        }

        IncludedArtifactReference sourceArtifact = exactSourceArtifact(
            command.templatePackage(),
            command.artifactEnvelope()
        );
        if (!constantTimeEquals(sourceArtifact.contentHash(), command.artifactEnvelope().envelopeHash())) {
            throw new ProcessTemplateException.HashMismatch(
                "template artifact reference does not match the supplied transfer envelope"
            );
        }
        TenantBinding formPackage = exactFormPackageBinding(currentPlan);
        verifyFormPackageBinding(formPackage, currentPlan);

        ImportCommand importCommand = new ImportCommand(
            command.context(),
            command.artifactEnvelope(),
            currentPlan.draftTarget().definitionKey(),
            currentPlan.draftTarget().definitionVersion(),
            Objects.requireNonNull(formPackage.targetVersion()),
            currentPlan.draftTarget().draftName()
        );
        ImportResult imported = draftImporter.importDraft(importCommand);
        verifyImportedDraft(imported, currentPlan, formPackage, command.artifactEnvelope());
        return new DraftCreationResult(
            currentPlan.packageContentHash(),
            currentPlan.planHash(),
            sourceArtifact.resourceName(),
            imported
        );
    }

    private static void verifyTenant(RequestContext context, PreviewRequest request) {
        if (!context.tenantId().equals(request.targetTenantId())) {
            throw new ProcessTemplateException.CrossTenantBinding(
                "write context tenant does not match the preview target tenant"
            );
        }
    }

    private static IncludedArtifactReference exactSourceArtifact(
        TemplatePackage templatePackage,
        TransferEnvelope envelope
    ) {
        String expectedKind = envelope.artifactType() == ArtifactType.APPROVAL_DSL
            ? "APPROVAL_DSL"
            : "APPROVAL_RELEASE_PACKAGE";
        List<IncludedArtifactReference> matches = templatePackage.artifacts().stream()
            .filter(artifact -> artifact.kind().equals(expectedKind))
            .toList();
        if (matches.size() != 1) {
            throw new ProcessTemplateException.DraftCreationRejected(
                "template package must reference exactly one " + expectedKind + " artifact"
            );
        }
        return matches.get(0);
    }

    private static TenantBinding exactFormPackageBinding(ImportPlan plan) {
        List<TenantBinding> bindings = plan.acceptedBindings().stream()
            .filter(binding -> binding.kind() == BindingKind.FORM_PACKAGE)
            .toList();
        if (bindings.size() != 1) {
            throw new ProcessTemplateException.DraftCreationRejected(
                "import plan must contain exactly one tenant-local Form Package binding"
            );
        }
        return bindings.get(0);
    }

    private static void verifyFormPackageBinding(TenantBinding binding, ImportPlan plan) {
        if (binding.targetVersion() == null || binding.targetVersion() < 1) {
            throw new ProcessTemplateException.DraftCreationRejected(
                "tenant-local Form Package binding requires a positive immutable version"
            );
        }
        if (!binding.targetResourceKey().equals(plan.draftTarget().definitionKey())) {
            throw new ProcessTemplateException.DraftCreationRejected(
                "target Form Package key must match the target Approval DSL definition key"
            );
        }
    }

    private static void verifyImportedDraft(
        ImportResult imported,
        ImportPlan plan,
        TenantBinding formPackage,
        TransferEnvelope envelope
    ) {
        if (imported == null
            || imported.revision() < 1
            || imported.status() != ApprovalDesignDraft.Status.DRAFT
            || !imported.definitionKey().equals(plan.draftTarget().definitionKey())
            || imported.definitionVersion() != plan.draftTarget().definitionVersion()
            || imported.formPackageVersion() != formPackage.targetVersion()
            || !imported.sourcePayloadHash().equals(envelope.payloadHash())
            || !imported.sourceEnvelopeHash().equals(envelope.envelopeHash())) {
            throw new ProcessTemplateException.DraftCreationRejected(
                "artifact transfer returned an inconsistent or non-draft result"
            );
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
            left.getBytes(StandardCharsets.US_ASCII),
            right.getBytes(StandardCharsets.US_ASCII)
        );
    }

    @FunctionalInterface
    interface DraftImporter {
        ImportResult importDraft(ImportCommand command);
    }

    public record DraftCreationCommand(
        RequestContext context,
        TemplatePackage templatePackage,
        int packageBytes,
        PreviewRequest previewRequest,
        TenantRegistrySnapshot tenantRegistry,
        String expectedPlanHash,
        TransferEnvelope artifactEnvelope
    ) {
        public DraftCreationCommand {
            context = Objects.requireNonNull(context, "context");
            templatePackage = Objects.requireNonNull(templatePackage, "templatePackage");
            previewRequest = Objects.requireNonNull(previewRequest, "previewRequest");
            tenantRegistry = Objects.requireNonNull(tenantRegistry, "tenantRegistry");
            artifactEnvelope = Objects.requireNonNull(artifactEnvelope, "artifactEnvelope");
        }
    }

    public record DraftCreationResult(
        String packageContentHash,
        String planHash,
        String sourceResourceName,
        ImportResult draft
    ) {
        public DraftCreationResult {
            packageContentHash = hash(packageContentHash, "packageContentHash");
            planHash = hash(planHash, "planHash");
            sourceResourceName = Objects.requireNonNull(sourceResourceName, "sourceResourceName");
            draft = Objects.requireNonNull(draft, "draft");
        }
    }
}
