package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ImportResult;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.BindingKind;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ImportPlan;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantBinding;
import io.github.akaryc1b.approval.application.ProcessTemplateDraftCreationService.DraftCreationResult;
import io.github.akaryc1b.approval.application.ProcessTemplateGovernedImportCoordinator.GovernedDraftCreationResult;
import io.github.akaryc1b.approval.application.ProcessTemplateGovernedImportCoordinator.GovernedPreview;
import io.github.akaryc1b.approval.application.port.ProcessTemplateFormPackageEvidenceResolver.FormPackageEvidence;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Produces deterministic technical review evidence without granting formal acceptance or
 * production authority.
 */
public final class ProcessTemplateFoundationAcceptanceEvaluator {

    private static final List<BlockedCapability> BLOCKED_CAPABILITIES = List.of(
        BlockedCapability.MARKETPLACE_PERSISTENCE,
        BlockedCapability.REMOTE_PACKAGE_DOWNLOAD,
        BlockedCapability.REMOTE_REGISTRY_LOOKUP,
        BlockedCapability.DYNAMIC_COMPONENT_LOADING,
        BlockedCapability.PRODUCTION_ENABLEMENT,
        BlockedCapability.PUBLICATION,
        BlockedCapability.DEPLOYMENT,
        BlockedCapability.ACTIVATION,
        BlockedCapability.AUTOMATIC_ACTIVATION
    );

    private final ProcessTemplatePackageValidator packageValidator;
    private final ProcessTemplateFormPackageEvidenceHasher evidenceHasher;

    public ProcessTemplateFoundationAcceptanceEvaluator(
        ProcessTemplatePackageValidator packageValidator,
        ProcessTemplateFormPackageEvidenceHasher evidenceHasher
    ) {
        this.packageValidator = Objects.requireNonNull(packageValidator, "packageValidator");
        this.evidenceHasher = Objects.requireNonNull(evidenceHasher, "evidenceHasher");
    }

    public FoundationAcceptanceEvidence evaluate(ReviewRequest request) {
        Objects.requireNonNull(request, "request");
        List<ReviewFinding> findings = new ArrayList<>();
        if (!validPackage(request, findings)) {
            return evidence(ReviewStatus.PACKAGE_INVALID, request, findings);
        }

        GovernedPreview preview = request.preview();
        ImportPlan plan = preview.plan();
        findings.add(info("PACKAGE_VALID", "template package passed deterministic validation"));
        if (!constantTimeEquals(
            request.templatePackage().contentHash(),
            plan.packageContentHash()
        )) {
            findings.add(error(
                "PACKAGE_EVIDENCE_MISMATCH",
                "governed preview does not reference the reviewed package"
            ));
            return evidence(ReviewStatus.PACKAGE_EVIDENCE_MISMATCH, request, findings);
        }
        if (!preview.importable()) {
            findings.add(error(
                "IMPORT_PLAN_NOT_READY",
                "governed preview contains unresolved errors or required rebindings"
            ));
            return evidence(ReviewStatus.IMPORT_PLAN_NOT_READY, request, findings);
        }

        FormPackageEvidence formEvidence = preview.formPackageEvidence();
        if (!validFormPackageEvidence(plan, formEvidence)) {
            findings.add(error(
                "FORM_PACKAGE_EVIDENCE_MISMATCH",
                "Form Package evidence is inconsistent with the governed preview"
            ));
            return evidence(ReviewStatus.FORM_PACKAGE_EVIDENCE_MISMATCH, request, findings);
        }
        findings.add(info(
            "FORM_PACKAGE_EVIDENCE_VALID",
            "exact tenant-local Form Package evidence is internally consistent"
        ));

        String actualGovernedHash = evidenceHasher.governedPreviewHash(plan, formEvidence);
        if (!constantTimeEquals(preview.governedPreviewHash(), actualGovernedHash)) {
            findings.add(error(
                "GOVERNED_PREVIEW_MISMATCH",
                "governed preview hash does not match its deterministic evidence"
            ));
            return evidence(ReviewStatus.GOVERNED_PREVIEW_MISMATCH, request, findings);
        }
        findings.add(info(
            "GOVERNED_PREVIEW_VALID",
            "registry, plan and Form Package evidence are bound deterministically"
        ));

        if (request.draftResult() == null) {
            findings.add(error(
                "DRAFT_EVIDENCE_INCOMPLETE",
                "one exact DRAFT result is required for foundation review readiness"
            ));
            return evidence(ReviewStatus.DRAFT_EVIDENCE_INCOMPLETE, request, findings);
        }
        if (!validDraftResult(request, preview, formEvidence)) {
            findings.add(error(
                "DRAFT_EVIDENCE_MISMATCH",
                "draft result is inconsistent with the governed preview"
            ));
            return evidence(ReviewStatus.DRAFT_EVIDENCE_MISMATCH, request, findings);
        }
        findings.add(info(
            "DRAFT_EVIDENCE_VALID",
            "governed import produced one exact tenant-local DRAFT result"
        ));
        findings.add(info(
            "READY_FOR_FORMAL_ACCEPTANCE_REVIEW",
            "technical foundation evidence is complete but grants no acceptance authority"
        ));
        return evidence(ReviewStatus.READY_FOR_FORMAL_ACCEPTANCE_REVIEW, request, findings);
    }

    private boolean validPackage(ReviewRequest request, List<ReviewFinding> findings) {
        try {
            packageValidator.validate(request.templatePackage(), request.packageBytes());
            return true;
        } catch (RuntimeException exception) {
            findings.add(error(
                "PACKAGE_INVALID",
                "template package could not be validated for foundation review"
            ));
            return false;
        }
    }

    private boolean validFormPackageEvidence(
        ImportPlan plan,
        FormPackageEvidence evidence
    ) {
        if (evidence == null
            || !plan.draftTarget().tenantId().equals(evidence.tenantId())
            || !plan.draftTarget().definitionKey().equals(evidence.definitionKey())) {
            return false;
        }
        TenantBinding binding = exactFormPackageBinding(plan);
        if (binding == null
            || binding.targetVersion() == null
            || binding.targetVersion() != evidence.packageVersion()
            || !binding.targetResourceKey().equals(evidence.definitionKey())) {
            return false;
        }
        return constantTimeEquals(evidence.contentHash(), evidenceHasher.hash(evidence));
    }

    private static TenantBinding exactFormPackageBinding(ImportPlan plan) {
        List<TenantBinding> bindings = plan.acceptedBindings().stream()
            .filter(binding -> binding.kind() == BindingKind.FORM_PACKAGE)
            .toList();
        return bindings.size() == 1 ? bindings.get(0) : null;
    }

    private static boolean validDraftResult(
        ReviewRequest request,
        GovernedPreview preview,
        FormPackageEvidence formEvidence
    ) {
        GovernedDraftCreationResult governed = request.draftResult();
        DraftCreationResult draft = governed.draft();
        ImportResult imported = draft.draft();
        if (!constantTimeEquals(
                governed.governedPreviewHash(),
                preview.governedPreviewHash()
            )
            || !formEvidence.equals(governed.formPackageEvidence())
            || !constantTimeEquals(
                draft.packageContentHash(),
                preview.plan().packageContentHash()
            )
            || !draft.registryEvidence().equals(preview.plan().registryEvidence())
            || !constantTimeEquals(draft.planHash(), preview.plan().planHash())
            || imported.draftId() == null
            || imported.revision() < 1
            || imported.status() != ApprovalDesignDraft.Status.DRAFT
            || !imported.definitionKey().equals(
                preview.plan().draftTarget().definitionKey()
            )
            || imported.definitionVersion()
                != preview.plan().draftTarget().definitionVersion()
            || imported.formPackageVersion() != formEvidence.packageVersion()
            || !isHash(imported.sourcePayloadHash())
            || !isHash(imported.sourceEnvelopeHash())) {
            return false;
        }
        long sourceMatches = request.templatePackage().artifacts().stream()
            .filter(artifact -> artifact.resourceName().equals(draft.sourceResourceName()))
            .filter(artifact -> artifact.contentHash().equals(imported.sourceEnvelopeHash()))
            .count();
        return sourceMatches == 1;
    }

    private FoundationAcceptanceEvidence evidence(
        ReviewStatus status,
        ReviewRequest request,
        List<ReviewFinding> findings
    ) {
        List<ReviewFinding> sortedFindings = findings.stream()
            .sorted(Comparator.comparing(ReviewFinding::canonical))
            .toList();
        String reviewHash = reviewHash(status, request, sortedFindings);
        return new FoundationAcceptanceEvidence(
            status,
            reviewHash,
            sortedFindings,
            BLOCKED_CAPABILITIES
        );
    }

    private static String reviewHash(
        ReviewStatus status,
        ReviewRequest request,
        List<ReviewFinding> findings
    ) {
        List<String> values = new ArrayList<>();
        values.add("process-template-foundation-review-v1");
        values.add(status.name());
        values.add(nullToEmpty(request.templatePackage().contentHash()));

        GovernedPreview preview = request.preview();
        ImportPlan plan = preview.plan();
        values.add(nullToEmpty(plan.packageContentHash()));
        values.add(nullToEmpty(plan.planHash()));
        values.add(nullToEmpty(preview.governedPreviewHash()));
        values.add(plan.registryEvidence().tenantId());
        values.add(plan.registryEvidence().platformProtocolVersion());
        values.add(plan.registryEvidence().contentHash());
        appendFormEvidence(values, preview.formPackageEvidence());
        appendDraftEvidence(values, request.draftResult());
        findings.stream().map(ReviewFinding::canonical).forEach(values::add);
        BLOCKED_CAPABILITIES.stream()
            .map(Enum::name)
            .sorted()
            .forEach(values::add);
        return sha256(lengthPrefix(values));
    }

    private static void appendFormEvidence(
        List<String> values,
        FormPackageEvidence evidence
    ) {
        if (evidence == null) {
            values.add("");
            return;
        }
        values.add(evidence.tenantId());
        values.add(evidence.definitionKey());
        values.add(Integer.toString(evidence.packageVersion()));
        values.add(Integer.toString(evidence.formVersion()));
        values.add(evidence.formHash());
        values.add(Integer.toString(evidence.uiSchemaVersion()));
        values.add(evidence.uiSchemaHash());
        values.add(evidence.packageHash());
        values.add(evidence.contentHash());
    }

    private static void appendDraftEvidence(
        List<String> values,
        GovernedDraftCreationResult governed
    ) {
        if (governed == null) {
            values.add("");
            return;
        }
        DraftCreationResult draft = governed.draft();
        ImportResult imported = draft.draft();
        values.add(governed.governedPreviewHash());
        values.add(draft.packageContentHash());
        values.add(draft.planHash());
        values.add(draft.sourceResourceName());
        values.add(imported.draftId().toString());
        values.add(Long.toString(imported.revision()));
        values.add(imported.status().name());
        values.add(imported.definitionKey());
        values.add(Integer.toString(imported.definitionVersion()));
        values.add(Integer.toString(imported.formPackageVersion()));
        values.add(imported.sourcePayloadHash());
        values.add(imported.sourceEnvelopeHash());
    }

    private static ReviewFinding info(String code, String message) {
        return new ReviewFinding(ReviewSeverity.INFO, code, message);
    }

    private static ReviewFinding error(String code, String message) {
        return new ReviewFinding(ReviewSeverity.ERROR, code, message);
    }

    private static boolean isHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
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

    private static String lengthPrefix(List<String> values) {
        StringBuilder canonical = new StringBuilder(4096);
        for (String value : values) {
            String checked = nullToEmpty(value);
            canonical.append(checked.length()).append(':').append(checked).append('|');
        }
        return canonical.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public enum ReviewStatus {
        READY_FOR_FORMAL_ACCEPTANCE_REVIEW,
        PACKAGE_INVALID,
        PACKAGE_EVIDENCE_MISMATCH,
        IMPORT_PLAN_NOT_READY,
        FORM_PACKAGE_EVIDENCE_MISMATCH,
        GOVERNED_PREVIEW_MISMATCH,
        DRAFT_EVIDENCE_INCOMPLETE,
        DRAFT_EVIDENCE_MISMATCH
    }

    public enum ReviewSeverity {
        INFO,
        ERROR
    }

    public enum BlockedCapability {
        MARKETPLACE_PERSISTENCE,
        REMOTE_PACKAGE_DOWNLOAD,
        REMOTE_REGISTRY_LOOKUP,
        DYNAMIC_COMPONENT_LOADING,
        PRODUCTION_ENABLEMENT,
        PUBLICATION,
        DEPLOYMENT,
        ACTIVATION,
        AUTOMATIC_ACTIVATION
    }

    public record ReviewFinding(
        ReviewSeverity severity,
        String code,
        String message
    ) {
        public ReviewFinding {
            severity = Objects.requireNonNull(severity, "severity");
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw new IllegalArgumentException("reviewFinding.code is invalid");
            }
            message = ProcessTemplateSecurity.text(message, "reviewFinding.message");
        }

        public String canonical() {
            return severity + "|" + code + "|" + message;
        }
    }

    public record ReviewRequest(
        TemplatePackage templatePackage,
        int packageBytes,
        GovernedPreview preview,
        GovernedDraftCreationResult draftResult
    ) {
        public ReviewRequest {
            templatePackage = Objects.requireNonNull(templatePackage, "templatePackage");
            preview = Objects.requireNonNull(preview, "preview");
        }
    }

    public record FoundationAcceptanceEvidence(
        ReviewStatus status,
        String reviewHash,
        List<ReviewFinding> findings,
        List<BlockedCapability> blockedCapabilities
    ) {
        public FoundationAcceptanceEvidence {
            status = Objects.requireNonNull(status, "status");
            reviewHash = ProcessTemplateSecurity.hash(reviewHash, "reviewHash");
            findings = List.copyOf(findings);
            blockedCapabilities = List.copyOf(blockedCapabilities);
        }

        public boolean readyForFormalAcceptanceReview() {
            return status == ReviewStatus.READY_FOR_FORMAL_ACCEPTANCE_REVIEW;
        }

        public boolean formalAcceptanceGranted() {
            return false;
        }

        public boolean productionEnabled() {
            return false;
        }

        public boolean marketplaceEnabled() {
            return false;
        }

        public boolean publicationEnabled() {
            return false;
        }

        public boolean deploymentEnabled() {
            return false;
        }

        public boolean activationEnabled() {
            return false;
        }

        public boolean automaticActivationEnabled() {
            return false;
        }

        public boolean dynamicLoadingEnabled() {
            return false;
        }

        public boolean requiresExplicitFormalAcceptance() {
            return true;
        }
    }
}
