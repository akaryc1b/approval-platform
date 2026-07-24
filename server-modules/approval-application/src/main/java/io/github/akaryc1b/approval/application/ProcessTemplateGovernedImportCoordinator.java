package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.TransferEnvelope;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.BindingKind;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ImportPlan;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.PreviewRequest;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantBinding;
import io.github.akaryc1b.approval.application.port.ProcessTemplateFormPackageEvidenceResolver;
import io.github.akaryc1b.approval.application.port.ProcessTemplateFormPackageEvidenceResolver.FormPackageEvidence;
import io.github.akaryc1b.approval.application.port.ProcessTemplateFormPackageEvidenceResolver.FormPackageEvidenceRequest;
import io.github.akaryc1b.approval.domain.context.RequestContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

import static io.github.akaryc1b.approval.application.ProcessTemplateSecurity.hash;

/**
 * Adds exact tenant-local Form Package evidence to the server-authoritative import workflow.
 * It cannot publish, deploy, activate, download packages or load component code.
 */
public final class ProcessTemplateGovernedImportCoordinator {

    private final ProcessTemplateImportCoordinator coordinator;
    private final ProcessTemplateFormPackageEvidenceResolver formPackageResolver;
    private final ProcessTemplateFormPackageEvidenceHasher evidenceHasher;

    public ProcessTemplateGovernedImportCoordinator(
        ProcessTemplateImportCoordinator coordinator,
        ProcessTemplateFormPackageEvidenceResolver formPackageResolver,
        ProcessTemplateFormPackageEvidenceHasher evidenceHasher
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.formPackageResolver = Objects.requireNonNull(
            formPackageResolver,
            "formPackageResolver"
        );
        this.evidenceHasher = Objects.requireNonNull(evidenceHasher, "evidenceHasher");
    }

    public GovernedPreview preview(PreviewCommand command) {
        Objects.requireNonNull(command, "command");
        ImportPlan plan = coordinator.preview(new ProcessTemplateImportCoordinator.PreviewCommand(
            command.templatePackage(),
            command.packageBytes(),
            command.previewRequest()
        ));
        if (!plan.importable()) {
            return new GovernedPreview(plan, null, null);
        }
        FormPackageEvidence evidence = resolveEvidence(plan);
        String governedPreviewHash = evidenceHasher.governedPreviewHash(plan, evidence);
        return new GovernedPreview(plan, evidence, governedPreviewHash);
    }

    public GovernedDraftCreationResult createDraft(CreateDraftCommand command) {
        Objects.requireNonNull(command, "command");
        hash(command.expectedGovernedPreviewHash(), "expectedGovernedPreviewHash");
        if (!command.context().tenantId().equals(command.previewRequest().targetTenantId())) {
            throw new ProcessTemplateException.CrossTenantBinding(
                "write context tenant does not match governed preview target tenant"
            );
        }
        GovernedPreview current = preview(new PreviewCommand(
            command.templatePackage(),
            command.packageBytes(),
            command.previewRequest()
        ));
        if (!current.importable()
            || !constantTimeEquals(
                command.expectedGovernedPreviewHash(),
                current.governedPreviewHash()
            )) {
            throw new ProcessTemplateException.StalePlan(
                "governed template preview changed and must be requested again"
            );
        }
        ProcessTemplateDraftCreationService.DraftCreationResult draft = coordinator.createDraft(
            new ProcessTemplateImportCoordinator.CreateDraftCommand(
                command.context(),
                command.templatePackage(),
                command.packageBytes(),
                command.previewRequest(),
                current.plan().planHash(),
                command.artifactEnvelope()
            )
        );
        if (!draft.registryEvidence().equals(current.plan().registryEvidence())) {
            throw new ProcessTemplateException.DraftCreationRejected(
                "draft creation returned registry evidence inconsistent with governed preview"
            );
        }
        return new GovernedDraftCreationResult(
            current.governedPreviewHash(),
            current.formPackageEvidence(),
            draft
        );
    }

    private FormPackageEvidence resolveEvidence(ImportPlan plan) {
        TenantBinding binding = exactFormPackageBinding(plan);
        FormPackageEvidence evidence;
        try {
            evidence = formPackageResolver.resolve(new FormPackageEvidenceRequest(
                plan.draftTarget().tenantId(),
                binding.targetResourceKey(),
                Objects.requireNonNull(binding.targetVersion())
            ));
        } catch (RuntimeException exception) {
            throw new ProcessTemplateException(
                "target Form Package evidence could not be resolved safely",
                exception
            );
        }
        if (evidence == null) {
            throw new ProcessTemplateException(
                "target Form Package evidence resolver returned no evidence"
            );
        }
        verifyEvidence(plan, binding, evidence);
        return evidence;
    }

    private void verifyEvidence(
        ImportPlan plan,
        TenantBinding binding,
        FormPackageEvidence evidence
    ) {
        if (!plan.draftTarget().tenantId().equals(evidence.tenantId())
            || !plan.draftTarget().definitionKey().equals(evidence.definitionKey())
            || !binding.targetResourceKey().equals(evidence.definitionKey())
            || binding.targetVersion() == null
            || binding.targetVersion() != evidence.packageVersion()) {
            throw new ProcessTemplateException.CrossTenantBinding(
                "resolved Form Package evidence does not match governed preview target"
            );
        }
        String actualHash = evidenceHasher.hash(evidence);
        if (!constantTimeEquals(evidence.contentHash(), actualHash)) {
            throw new ProcessTemplateException.HashMismatch(
                "resolved Form Package evidence content hash mismatch"
            );
        }
    }

    private static TenantBinding exactFormPackageBinding(ImportPlan plan) {
        List<TenantBinding> bindings = plan.acceptedBindings().stream()
            .filter(binding -> binding.kind() == BindingKind.FORM_PACKAGE)
            .toList();
        if (bindings.size() != 1) {
            throw new ProcessTemplateException.DraftCreationRejected(
                "governed preview requires exactly one Form Package binding"
            );
        }
        TenantBinding binding = bindings.get(0);
        if (binding.targetVersion() == null || binding.targetVersion() < 1) {
            throw new ProcessTemplateException.DraftCreationRejected(
                "governed preview requires one immutable Form Package version"
            );
        }
        return binding;
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
        String expectedGovernedPreviewHash,
        TransferEnvelope artifactEnvelope
    ) {
        public CreateDraftCommand {
            context = Objects.requireNonNull(context, "context");
            templatePackage = Objects.requireNonNull(templatePackage, "templatePackage");
            previewRequest = Objects.requireNonNull(previewRequest, "previewRequest");
            expectedGovernedPreviewHash = Objects.requireNonNull(
                expectedGovernedPreviewHash,
                "expectedGovernedPreviewHash"
            );
            artifactEnvelope = Objects.requireNonNull(artifactEnvelope, "artifactEnvelope");
        }
    }

    public record GovernedPreview(
        ImportPlan plan,
        FormPackageEvidence formPackageEvidence,
        String governedPreviewHash
    ) {
        public GovernedPreview {
            plan = Objects.requireNonNull(plan, "plan");
            if (plan.importable()) {
                formPackageEvidence = Objects.requireNonNull(
                    formPackageEvidence,
                    "formPackageEvidence"
                );
                governedPreviewHash = hash(
                    governedPreviewHash,
                    "governedPreviewHash"
                );
            } else if (formPackageEvidence != null || governedPreviewHash != null) {
                throw new IllegalArgumentException(
                    "non-importable preview must not expose Form Package evidence"
                );
            }
        }

        public boolean importable() {
            return plan.importable();
        }
    }

    public record GovernedDraftCreationResult(
        String governedPreviewHash,
        FormPackageEvidence formPackageEvidence,
        ProcessTemplateDraftCreationService.DraftCreationResult draft
    ) {
        public GovernedDraftCreationResult {
            governedPreviewHash = hash(governedPreviewHash, "governedPreviewHash");
            formPackageEvidence = Objects.requireNonNull(
                formPackageEvidence,
                "formPackageEvidence"
            );
            draft = Objects.requireNonNull(draft, "draft");
        }
    }
}
