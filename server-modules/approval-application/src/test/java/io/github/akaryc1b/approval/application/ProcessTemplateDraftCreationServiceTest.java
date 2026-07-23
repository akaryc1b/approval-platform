package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ArtifactType;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.DefinitionPayload;
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
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTemplateDraftCreationServiceTest {

    private static final String ENVELOPE_HASH = "a".repeat(64);

    private final ProcessTemplateCanonicalHasher hasher = new ProcessTemplateCanonicalHasher();
    private final ProcessTemplatePackageValidator validator = new ProcessTemplatePackageValidator(hasher);
    private final ProcessTemplateImportPreviewService preview =
        new ProcessTemplateImportPreviewService(validator, hasher);

    @Test
    void revalidatesPlanAndCreatesOneDraftThroughArtifactTransfer() {
        TransferEnvelope envelope = envelope(ENVELOPE_HASH);
        TemplatePackage templatePackage = ProcessTemplateFixtures.withSourceArtifactHash(
            "APPROVAL_DSL", ENVELOPE_HASH);
        PreviewRequest request = ProcessTemplateFixtures.completeRequest();
        TenantRegistrySnapshot registry = ProcessTemplateFixtures.completeRegistry();
        ImportPlan plan = preview.preview(templatePackage, 4096, request, registry);
        RecordingImporter importer = new RecordingImporter(draftResult(ENVELOPE_HASH));
        ProcessTemplateDraftCreationService service =
            new ProcessTemplateDraftCreationService(preview, importer);
        RequestContext context = context("tenant-a");

        var result = service.createDraft(new ProcessTemplateDraftCreationService.DraftCreationCommand(
            context, templatePackage, 4096, request, registry, plan.planHash(), envelope));

        assertEquals(1, importer.calls);
        assertEquals(context, importer.command.context());
        assertEquals("expenseImported", importer.command.targetDefinitionKey());
        assertEquals(1, importer.command.targetDefinitionVersion());
        assertEquals(3, importer.command.targetFormPackageVersion());
        assertEquals("Expense imported draft", importer.command.targetName());
        assertEquals(ApprovalDesignDraft.Status.DRAFT, result.draft().status());
        assertEquals(plan.planHash(), result.planHash());
        assertEquals("approval/expense.dsl.json", result.sourceResourceName());
    }

    @Test
    void rejectsStalePlanAfterTenantRegistryDriftBeforeDraftImporter() {
        TransferEnvelope envelope = envelope(ENVELOPE_HASH);
        TemplatePackage templatePackage = ProcessTemplateFixtures.withSourceArtifactHash(
            "APPROVAL_DSL", ENVELOPE_HASH);
        ImportPlan originalPlan = preview.preview(templatePackage, 4096,
            ProcessTemplateFixtures.completeRequest(), ProcessTemplateFixtures.completeRegistry());
        TenantRegistrySnapshot driftedRegistry = new TenantRegistrySnapshot(
            "tenant-a", "1.4.0", Set.of("amount"), Set.of(), Set.of("invoice.local"),
            Set.of("finance.local"), Set.of("approver.local"),
            ProcessTemplateFixtures.completeRegistry().components());
        RecordingImporter importer = new RecordingImporter(draftResult(ENVELOPE_HASH));
        ProcessTemplateDraftCreationService service =
            new ProcessTemplateDraftCreationService(preview, importer);

        assertThrows(ProcessTemplateException.StalePlan.class, () -> service.createDraft(
            new ProcessTemplateDraftCreationService.DraftCreationCommand(
                context("tenant-a"), templatePackage, 4096, ProcessTemplateFixtures.completeRequest(),
                driftedRegistry, originalPlan.planHash(), envelope)));
        assertEquals(0, importer.calls);
    }

    @Test
    void rejectsNonImportablePlanBeforeDraftImporter() {
        TemplatePackage templatePackage = ProcessTemplateFixtures.withSourceArtifactHash(
            "APPROVAL_DSL", ENVELOPE_HASH);
        TenantRegistrySnapshot registry = new TenantRegistrySnapshot(
            "tenant-a", "1.4.0", Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), List.of());
        ImportPlan plan = preview.preview(templatePackage, 4096,
            ProcessTemplateFixtures.completeRequest(), registry);
        RecordingImporter importer = new RecordingImporter(draftResult(ENVELOPE_HASH));
        ProcessTemplateDraftCreationService service =
            new ProcessTemplateDraftCreationService(preview, importer);

        assertThrows(ProcessTemplateException.DraftCreationRejected.class, () -> service.createDraft(
            new ProcessTemplateDraftCreationService.DraftCreationCommand(
                context("tenant-a"), templatePackage, 4096, ProcessTemplateFixtures.completeRequest(),
                registry, plan.planHash(), envelope(ENVELOPE_HASH))));
        assertEquals(0, importer.calls);
    }

    @Test
    void rejectsCrossTenantWriteContext() {
        TemplatePackage templatePackage = ProcessTemplateFixtures.withSourceArtifactHash(
            "APPROVAL_DSL", ENVELOPE_HASH);
        ImportPlan plan = preview.preview(templatePackage, 4096,
            ProcessTemplateFixtures.completeRequest(), ProcessTemplateFixtures.completeRegistry());
        RecordingImporter importer = new RecordingImporter(draftResult(ENVELOPE_HASH));
        ProcessTemplateDraftCreationService service =
            new ProcessTemplateDraftCreationService(preview, importer);

        assertThrows(ProcessTemplateException.CrossTenantBinding.class, () -> service.createDraft(
            new ProcessTemplateDraftCreationService.DraftCreationCommand(
                context("tenant-b"), templatePackage, 4096, ProcessTemplateFixtures.completeRequest(),
                ProcessTemplateFixtures.completeRegistry(), plan.planHash(), envelope(ENVELOPE_HASH))));
        assertEquals(0, importer.calls);
    }

    @Test
    void rejectsArtifactReferenceMismatch() {
        TemplatePackage templatePackage = ProcessTemplateFixtures.validPackage();
        ImportPlan plan = preview.preview(templatePackage, 4096,
            ProcessTemplateFixtures.completeRequest(), ProcessTemplateFixtures.completeRegistry());
        RecordingImporter importer = new RecordingImporter(draftResult(ENVELOPE_HASH));
        ProcessTemplateDraftCreationService service =
            new ProcessTemplateDraftCreationService(preview, importer);

        assertThrows(ProcessTemplateException.HashMismatch.class, () -> service.createDraft(
            new ProcessTemplateDraftCreationService.DraftCreationCommand(
                context("tenant-a"), templatePackage, 4096, ProcessTemplateFixtures.completeRequest(),
                ProcessTemplateFixtures.completeRegistry(), plan.planHash(), envelope(ENVELOPE_HASH))));
        assertEquals(0, importer.calls);
    }

    @Test
    void rejectsMissingExactSourceArtifact() {
        TemplatePackage templatePackage = packageWithoutDslArtifact();
        ImportPlan plan = preview.preview(templatePackage, 4096,
            ProcessTemplateFixtures.completeRequest(), ProcessTemplateFixtures.completeRegistry());
        RecordingImporter importer = new RecordingImporter(draftResult(ENVELOPE_HASH));
        ProcessTemplateDraftCreationService service =
            new ProcessTemplateDraftCreationService(preview, importer);

        assertThrows(ProcessTemplateException.DraftCreationRejected.class, () -> service.createDraft(
            new ProcessTemplateDraftCreationService.DraftCreationCommand(
                context("tenant-a"), templatePackage, 4096, ProcessTemplateFixtures.completeRequest(),
                ProcessTemplateFixtures.completeRegistry(), plan.planHash(), envelope(ENVELOPE_HASH))));
        assertEquals(0, importer.calls);
    }

    @Test
    void rejectsInconsistentOrPublishedImporterResult() {
        TemplatePackage templatePackage = ProcessTemplateFixtures.withSourceArtifactHash(
            "APPROVAL_DSL", ENVELOPE_HASH);
        ImportPlan plan = preview.preview(templatePackage, 4096,
            ProcessTemplateFixtures.completeRequest(), ProcessTemplateFixtures.completeRegistry());
        ImportResult published = new ImportResult(
            UUID.fromString("00000000-0000-0000-0000-000000000002"), 1,
            ApprovalDesignDraft.Status.PUBLISHED, "expenseImported", 1, 3,
            "e".repeat(64), ENVELOPE_HASH);
        RecordingImporter importer = new RecordingImporter(published);
        ProcessTemplateDraftCreationService service =
            new ProcessTemplateDraftCreationService(preview, importer);

        assertThrows(ProcessTemplateException.DraftCreationRejected.class, () -> service.createDraft(
            new ProcessTemplateDraftCreationService.DraftCreationCommand(
                context("tenant-a"), templatePackage, 4096, ProcessTemplateFixtures.completeRequest(),
                ProcessTemplateFixtures.completeRegistry(), plan.planHash(), envelope(ENVELOPE_HASH))));
        assertEquals(1, importer.calls);
    }

    @Test
    void exposesNoPublishDeployOrActivateMethod() {
        List<String> methods = java.util.Arrays.stream(ProcessTemplateDraftCreationService.class
            .getDeclaredMethods()).map(java.lang.reflect.Method::getName).toList();
        assertFalse(methods.contains("publish"));
        assertFalse(methods.contains("deploy"));
        assertFalse(methods.contains("activate"));
        assertTrue(methods.contains("createDraft"));
    }

    private TemplatePackage packageWithoutDslArtifact() {
        TemplatePackage base = ProcessTemplateFixtures.validPackage();
        List<IncludedArtifactReference> artifacts = base.artifacts().stream()
            .filter(artifact -> !artifact.kind().equals("APPROVAL_DSL"))
            .toList();
        return new TemplatePackage(base.manifest(), base.dependencyManifest(), artifacts,
            base.componentDescriptors(), hasher.packageHash(base.manifest(), base.dependencyManifest(),
                artifacts, base.componentDescriptors()));
    }

    private static RequestContext context(String tenantId) {
        return new RequestContext(tenantId, "operator-1", "request-1", "idempotency-1", "trace-1");
    }

    private static ImportResult draftResult(String envelopeHash) {
        return new ImportResult(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), 1,
            ApprovalDesignDraft.Status.DRAFT, "expenseImported", 1, 3,
            "e".repeat(64), envelopeHash);
    }

    private static TransferEnvelope envelope(String envelopeHash) {
        ApprovalDefinition definition = new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION,
            "expenseSource",
            1,
            "Expense source",
            "start",
            List.of(
                new ApprovalDefinition.StartNode("start", "Start", "end"),
                new ApprovalDefinition.EndNode("end", "End")
            )
        );
        return new TransferEnvelope(
            ApprovalArtifactTransferService.DSL_FORMAT,
            ApprovalArtifactTransferService.FORMAT_VERSION,
            ArtifactType.APPROVAL_DSL,
            Instant.parse("2026-07-23T00:00:00Z"),
            "expenseSource",
            1,
            null,
            1,
            "c".repeat(64),
            "d".repeat(64),
            new DefinitionPayload(definition),
            "e".repeat(64),
            envelopeHash
        );
    }

    private static final class RecordingImporter
        implements ProcessTemplateDraftCreationService.DraftImporter {

        private final ImportResult result;
        private int calls;
        private ImportCommand command;

        private RecordingImporter(ImportResult result) {
            this.result = result;
        }

        @Override
        public ImportResult importDraft(ImportCommand command) {
            calls++;
            this.command = command;
            return result;
        }
    }
}
