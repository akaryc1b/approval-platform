package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ImportResult;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ImportPlan;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantRegistrySnapshot;
import io.github.akaryc1b.approval.application.ProcessTemplateDraftCreationService.DraftCreationResult;
import io.github.akaryc1b.approval.application.ProcessTemplateFoundationAcceptanceEvaluator.FoundationAcceptanceEvidence;
import io.github.akaryc1b.approval.application.ProcessTemplateFoundationAcceptanceEvaluator.ReviewRequest;
import io.github.akaryc1b.approval.application.ProcessTemplateFoundationAcceptanceEvaluator.ReviewStatus;
import io.github.akaryc1b.approval.application.ProcessTemplateGovernedImportCoordinator.GovernedDraftCreationResult;
import io.github.akaryc1b.approval.application.ProcessTemplateGovernedImportCoordinator.GovernedPreview;
import io.github.akaryc1b.approval.application.port.ProcessTemplateFormPackageEvidenceResolver.FormPackageEvidence;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTemplateFoundationAcceptanceEvaluatorTest {

    private final ProcessTemplateCanonicalHasher canonicalHasher =
        new ProcessTemplateCanonicalHasher();
    private final ProcessTemplatePackageValidator packageValidator =
        new ProcessTemplatePackageValidator(canonicalHasher);
    private final ProcessTemplateFormPackageEvidenceHasher formEvidenceHasher =
        new ProcessTemplateFormPackageEvidenceHasher();
    private final ProcessTemplateFoundationAcceptanceEvaluator evaluator =
        new ProcessTemplateFoundationAcceptanceEvaluator(
            packageValidator,
            formEvidenceHasher
        );

    @Test
    void completeEvidenceIsReadyOnlyForFormalAcceptanceReview() {
        Fixture fixture = validFixture();

        FoundationAcceptanceEvidence evidence = evaluator.evaluate(fixture.request());

        assertEquals(ReviewStatus.READY_FOR_FORMAL_ACCEPTANCE_REVIEW, evidence.status());
        assertTrue(evidence.readyForFormalAcceptanceReview());
        assertEquals(64, evidence.reviewHash().length());
        assertTrue(evidence.requiresExplicitFormalAcceptance());
        assertFalse(evidence.formalAcceptanceGranted());
        assertFalse(evidence.productionEnabled());
        assertFalse(evidence.marketplaceEnabled());
        assertFalse(evidence.publicationEnabled());
        assertFalse(evidence.deploymentEnabled());
        assertFalse(evidence.activationEnabled());
        assertFalse(evidence.automaticActivationEnabled());
        assertFalse(evidence.dynamicLoadingEnabled());
    }

    @Test
    void reviewHashIsDeterministic() {
        Fixture fixture = validFixture();

        String first = evaluator.evaluate(fixture.request()).reviewHash();
        String second = evaluator.evaluate(fixture.request()).reviewHash();

        assertEquals(first, second);
    }

    @Test
    void missingDraftEvidenceIsIncomplete() {
        Fixture fixture = validFixture();
        ReviewRequest request = new ReviewRequest(
            fixture.templatePackage(),
            4096,
            fixture.preview(),
            null
        );

        FoundationAcceptanceEvidence evidence = evaluator.evaluate(request);

        assertEquals(ReviewStatus.DRAFT_EVIDENCE_INCOMPLETE, evidence.status());
        assertFalse(evidence.readyForFormalAcceptanceReview());
    }

    @Test
    void nonImportablePlanIsNotReviewReady() {
        TemplatePackage templatePackage = ProcessTemplateFixtures.validPackage();
        TenantRegistrySnapshot emptyRegistry = new TenantRegistrySnapshot(
            "tenant-a",
            "1.4.0",
            java.util.Set.of(),
            java.util.Set.of(),
            java.util.Set.of(),
            java.util.Set.of(),
            java.util.Set.of(),
            List.of()
        );
        ImportPlan plan = previewService().preview(
            templatePackage,
            4096,
            ProcessTemplateFixtures.completeRequest(),
            emptyRegistry
        );
        GovernedPreview preview = new GovernedPreview(plan, null, null);

        FoundationAcceptanceEvidence evidence = evaluator.evaluate(
            new ReviewRequest(templatePackage, 4096, preview, null)
        );

        assertEquals(ReviewStatus.IMPORT_PLAN_NOT_READY, evidence.status());
    }

    @Test
    void reviewedPackageMustMatchPreviewPackage() {
        Fixture fixture = validFixture();
        TemplatePackage changed = ProcessTemplateFixtures.withSummary(
            "Changed but still valid summary"
        );

        FoundationAcceptanceEvidence evidence = evaluator.evaluate(new ReviewRequest(
            changed,
            4096,
            fixture.preview(),
            fixture.draftResult()
        ));

        assertEquals(ReviewStatus.PACKAGE_EVIDENCE_MISMATCH, evidence.status());
    }

    @Test
    void tamperedFormPackageEvidenceIsRejected() {
        Fixture fixture = validFixture();
        FormPackageEvidence valid = fixture.formEvidence();
        FormPackageEvidence tampered = new FormPackageEvidence(
            valid.tenantId(),
            valid.definitionKey(),
            valid.packageVersion(),
            valid.formVersion(),
            valid.formHash(),
            valid.uiSchemaVersion(),
            valid.uiSchemaHash(),
            valid.packageHash(),
            "f".repeat(64)
        );
        GovernedPreview preview = new GovernedPreview(
            fixture.preview().plan(),
            tampered,
            formEvidenceHasher.governedPreviewHash(fixture.preview().plan(), tampered)
        );

        FoundationAcceptanceEvidence evidence = evaluator.evaluate(new ReviewRequest(
            fixture.templatePackage(),
            4096,
            preview,
            null
        ));

        assertEquals(ReviewStatus.FORM_PACKAGE_EVIDENCE_MISMATCH, evidence.status());
    }

    @Test
    void tamperedGovernedPreviewHashIsRejected() {
        Fixture fixture = validFixture();
        GovernedPreview preview = new GovernedPreview(
            fixture.preview().plan(),
            fixture.formEvidence(),
            "f".repeat(64)
        );

        FoundationAcceptanceEvidence evidence = evaluator.evaluate(new ReviewRequest(
            fixture.templatePackage(),
            4096,
            preview,
            fixture.draftResult()
        ));

        assertEquals(ReviewStatus.GOVERNED_PREVIEW_MISMATCH, evidence.status());
    }

    @Test
    void inconsistentDraftEvidenceIsRejected() {
        Fixture fixture = validFixture();
        DraftCreationResult original = fixture.draftResult().draft();
        DraftCreationResult changed = new DraftCreationResult(
            original.packageContentHash(),
            original.registryEvidence(),
            "f".repeat(64),
            original.sourceResourceName(),
            original.draft()
        );
        GovernedDraftCreationResult governed = new GovernedDraftCreationResult(
            fixture.preview().governedPreviewHash(),
            fixture.formEvidence(),
            changed
        );

        FoundationAcceptanceEvidence evidence = evaluator.evaluate(new ReviewRequest(
            fixture.templatePackage(),
            4096,
            fixture.preview(),
            governed
        ));

        assertEquals(ReviewStatus.DRAFT_EVIDENCE_MISMATCH, evidence.status());
    }

    @Test
    void differentReviewOutcomeProducesDifferentHash() {
        Fixture fixture = validFixture();
        FoundationAcceptanceEvidence ready = evaluator.evaluate(fixture.request());
        FoundationAcceptanceEvidence incomplete = evaluator.evaluate(new ReviewRequest(
            fixture.templatePackage(),
            4096,
            fixture.preview(),
            null
        ));

        assertNotEquals(ready.reviewHash(), incomplete.reviewHash());
    }

    private Fixture validFixture() {
        TemplatePackage templatePackage = ProcessTemplateFixtures.validPackage();
        ImportPlan plan = previewService().preview(
            templatePackage,
            4096,
            ProcessTemplateFixtures.completeRequest(),
            ProcessTemplateFixtures.completeRegistry()
        );
        String formHash = "b".repeat(64);
        String uiSchemaHash = "c".repeat(64);
        String packageHash = "d".repeat(64);
        String evidenceHash = formEvidenceHasher.hash(
            "tenant-a",
            "expenseImported",
            3,
            2,
            formHash,
            4,
            uiSchemaHash,
            packageHash
        );
        FormPackageEvidence formEvidence = new FormPackageEvidence(
            "tenant-a",
            "expenseImported",
            3,
            2,
            formHash,
            4,
            uiSchemaHash,
            packageHash,
            evidenceHash
        );
        String governedHash = formEvidenceHasher.governedPreviewHash(plan, formEvidence);
        GovernedPreview preview = new GovernedPreview(plan, formEvidence, governedHash);

        ImportResult imported = new ImportResult(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            1,
            ApprovalDesignDraft.Status.DRAFT,
            "expenseImported",
            1,
            3,
            "e".repeat(64),
            "1".repeat(64)
        );
        DraftCreationResult draft = new DraftCreationResult(
            templatePackage.contentHash(),
            plan.registryEvidence(),
            plan.planHash(),
            "approval/expense.dsl.json",
            imported
        );
        GovernedDraftCreationResult governed = new GovernedDraftCreationResult(
            governedHash,
            formEvidence,
            draft
        );
        return new Fixture(
            templatePackage,
            preview,
            formEvidence,
            governed,
            new ReviewRequest(templatePackage, 4096, preview, governed)
        );
    }

    private ProcessTemplateImportPreviewService previewService() {
        return new ProcessTemplateImportPreviewService(
            packageValidator,
            canonicalHasher
        );
    }

    private record Fixture(
        TemplatePackage templatePackage,
        GovernedPreview preview,
        FormPackageEvidence formEvidence,
        GovernedDraftCreationResult draftResult,
        ReviewRequest request
    ) {
    }
}
