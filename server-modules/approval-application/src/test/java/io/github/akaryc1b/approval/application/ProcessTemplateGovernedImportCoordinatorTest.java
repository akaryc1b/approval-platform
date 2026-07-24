package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ArtifactType;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.DefinitionPayload;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ImportCommand;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ImportResult;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.TransferEnvelope;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantRegistrySnapshot;
import io.github.akaryc1b.approval.application.port.ProcessTemplateFormPackageEvidenceResolver;
import io.github.akaryc1b.approval.application.port.ProcessTemplateFormPackageEvidenceResolver.FormPackageEvidence;
import io.github.akaryc1b.approval.application.port.ProcessTemplateTenantRegistryResolver;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTemplateGovernedImportCoordinatorTest {

    private static final String ENVELOPE_HASH = "a".repeat(64);
    private static final String FORM_HASH = "b".repeat(64);
    private static final String UI_HASH = "c".repeat(64);

    private final ProcessTemplateCanonicalHasher templateHasher =
        new ProcessTemplateCanonicalHasher();
    private final ProcessTemplatePackageValidator validator =
        new ProcessTemplatePackageValidator(templateHasher);
    private final ProcessTemplateImportPreviewService previewService =
        new ProcessTemplateImportPreviewService(validator, templateHasher);
    private final ProcessTemplateFormPackageEvidenceHasher evidenceHasher =
        new ProcessTemplateFormPackageEvidenceHasher();

    @Test
    void importablePreviewIncludesServerResolvedFormPackageEvidence() {
        AtomicInteger formResolutions = new AtomicInteger();
        ProcessTemplateGovernedImportCoordinator coordinator = governed(
            request -> ProcessTemplateFixtures.completeRegistry(),
            request -> {
                formResolutions.incrementAndGet();
                return evidence("d".repeat(64));
            },
            new RecordingImporter()
        );

        var preview = coordinator.preview(new ProcessTemplateGovernedImportCoordinator.PreviewCommand(
            ProcessTemplateFixtures.validPackage(),
            4096,
            ProcessTemplateFixtures.completeRequest()
        ));

        assertTrue(preview.importable());
        assertEquals(1, formResolutions.get());
        assertEquals("tenant-a", preview.formPackageEvidence().tenantId());
        assertEquals("expenseImported", preview.formPackageEvidence().definitionKey());
        assertEquals(3, preview.formPackageEvidence().packageVersion());
        assertEquals(64, preview.governedPreviewHash().length());
    }

    @Test
    void nonImportablePreviewDoesNotResolveFormPackageEvidence() {
        AtomicInteger formResolutions = new AtomicInteger();
        TenantRegistrySnapshot unavailable = new TenantRegistrySnapshot(
            "tenant-a", "1.4.0", Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), List.of());
        ProcessTemplateGovernedImportCoordinator coordinator = governed(
            request -> unavailable,
            request -> {
                formResolutions.incrementAndGet();
                return evidence("d".repeat(64));
            },
            new RecordingImporter()
        );

        var preview = coordinator.preview(new ProcessTemplateGovernedImportCoordinator.PreviewCommand(
            ProcessTemplateFixtures.validPackage(),
            4096,
            ProcessTemplateFixtures.completeRequest()
        ));

        assertFalse(preview.importable());
        assertEquals(0, formResolutions.get());
        assertNull(preview.formPackageEvidence());
        assertNull(preview.governedPreviewHash());
    }

    @Test
    void createDraftRevalidatesFormPackageEvidenceBeforeWrite() {
        AtomicInteger formResolutions = new AtomicInteger();
        RecordingImporter importer = new RecordingImporter();
        ProcessTemplateGovernedImportCoordinator coordinator = governed(
            request -> ProcessTemplateFixtures.completeRegistry(),
            request -> {
                formResolutions.incrementAndGet();
                return evidence("d".repeat(64));
            },
            importer
        );
        TemplatePackage templatePackage = ProcessTemplateFixtures.withSourceArtifactHash(
            "APPROVAL_DSL",
            ENVELOPE_HASH
        );
        var request = ProcessTemplateFixtures.completeRequest();
        var preview = coordinator.preview(new ProcessTemplateGovernedImportCoordinator.PreviewCommand(
            templatePackage,
            4096,
            request
        ));

        var result = coordinator.createDraft(
            new ProcessTemplateGovernedImportCoordinator.CreateDraftCommand(
                context("tenant-a"),
                templatePackage,
                4096,
                request,
                preview.governedPreviewHash(),
                envelope()
            )
        );

        assertEquals(2, formResolutions.get());
        assertEquals(1, importer.calls);
        assertEquals(preview.governedPreviewHash(), result.governedPreviewHash());
        assertEquals(preview.formPackageEvidence(), result.formPackageEvidence());
        assertEquals(ApprovalDesignDraft.Status.DRAFT, result.draft().draft().status());
    }

    @Test
    void formPackageDriftRejectsStaleGovernedPreviewBeforeWrite() {
        AtomicInteger resolutions = new AtomicInteger();
        RecordingImporter importer = new RecordingImporter();
        ProcessTemplateGovernedImportCoordinator coordinator = governed(
            request -> ProcessTemplateFixtures.completeRegistry(),
            request -> resolutions.incrementAndGet() == 1
                ? evidence("d".repeat(64))
                : evidence("e".repeat(64)),
            importer
        );
        TemplatePackage templatePackage = ProcessTemplateFixtures.withSourceArtifactHash(
            "APPROVAL_DSL",
            ENVELOPE_HASH
        );
        var request = ProcessTemplateFixtures.completeRequest();
        var preview = coordinator.preview(new ProcessTemplateGovernedImportCoordinator.PreviewCommand(
            templatePackage,
            4096,
            request
        ));

        assertThrows(ProcessTemplateException.StalePlan.class, () -> coordinator.createDraft(
            new ProcessTemplateGovernedImportCoordinator.CreateDraftCommand(
                context("tenant-a"),
                templatePackage,
                4096,
                request,
                preview.governedPreviewHash(),
                envelope()
            )
        ));
        assertEquals(2, resolutions.get());
        assertEquals(0, importer.calls);
    }

    @Test
    void rejectsForgedFormPackageEvidenceHash() {
        FormPackageEvidence valid = evidence("d".repeat(64));
        FormPackageEvidence forged = new FormPackageEvidence(
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
        ProcessTemplateGovernedImportCoordinator coordinator = governed(
            request -> ProcessTemplateFixtures.completeRegistry(),
            request -> forged,
            new RecordingImporter()
        );

        assertThrows(ProcessTemplateException.HashMismatch.class, () -> coordinator.preview(
            new ProcessTemplateGovernedImportCoordinator.PreviewCommand(
                ProcessTemplateFixtures.validPackage(),
                4096,
                ProcessTemplateFixtures.completeRequest()
            )
        ));
    }

    @Test
    void rejectsNullEvidenceAndRedactsResolverFailures() {
        ProcessTemplateGovernedImportCoordinator nullCoordinator = governed(
            request -> ProcessTemplateFixtures.completeRegistry(),
            request -> null,
            new RecordingImporter()
        );
        assertThrows(ProcessTemplateException.class, () -> nullCoordinator.preview(
            new ProcessTemplateGovernedImportCoordinator.PreviewCommand(
                ProcessTemplateFixtures.validPackage(),
                4096,
                ProcessTemplateFixtures.completeRequest()
            )
        ));

        ProcessTemplateGovernedImportCoordinator failedCoordinator = governed(
            request -> ProcessTemplateFixtures.completeRegistry(),
            request -> {
                throw new IllegalStateException("sensitive datastore detail");
            },
            new RecordingImporter()
        );
        ProcessTemplateException failure = assertThrows(ProcessTemplateException.class,
            () -> failedCoordinator.preview(
                new ProcessTemplateGovernedImportCoordinator.PreviewCommand(
                    ProcessTemplateFixtures.validPackage(),
                    4096,
                    ProcessTemplateFixtures.completeRequest()
                )
            ));
        assertFalse(failure.getMessage().contains("sensitive"));
    }

    @Test
    void rejectsCrossTenantWriteBeforeAdditionalResolution() {
        AtomicInteger registryResolutions = new AtomicInteger();
        AtomicInteger formResolutions = new AtomicInteger();
        ProcessTemplateTenantRegistryResolver registryResolver = request -> {
            registryResolutions.incrementAndGet();
            return ProcessTemplateFixtures.completeRegistry();
        };
        ProcessTemplateFormPackageEvidenceResolver formResolver = request -> {
            formResolutions.incrementAndGet();
            return evidence("d".repeat(64));
        };
        ProcessTemplateGovernedImportCoordinator coordinator = governed(
            registryResolver,
            formResolver,
            new RecordingImporter()
        );
        TemplatePackage templatePackage = ProcessTemplateFixtures.withSourceArtifactHash(
            "APPROVAL_DSL",
            ENVELOPE_HASH
        );
        var request = ProcessTemplateFixtures.completeRequest();
        var preview = coordinator.preview(new ProcessTemplateGovernedImportCoordinator.PreviewCommand(
            templatePackage,
            4096,
            request
        ));

        assertThrows(ProcessTemplateException.CrossTenantBinding.class,
            () -> coordinator.createDraft(
                new ProcessTemplateGovernedImportCoordinator.CreateDraftCommand(
                    context("tenant-b"),
                    templatePackage,
                    4096,
                    request,
                    preview.governedPreviewHash(),
                    envelope()
                )
            ));
        assertEquals(1, registryResolutions.get());
        assertEquals(1, formResolutions.get());
    }

    @Test
    void publicCommandsCarryOnlyExpectedHashAndNoReleaseMutation() {
        assertFalse(hasEvidenceComponent(
            ProcessTemplateGovernedImportCoordinator.PreviewCommand.class));
        assertFalse(hasEvidenceComponent(
            ProcessTemplateGovernedImportCoordinator.CreateDraftCommand.class));
        List<String> methods = Arrays.stream(
            ProcessTemplateGovernedImportCoordinator.class.getDeclaredMethods())
            .map(java.lang.reflect.Method::getName)
            .toList();
        assertTrue(methods.contains("preview"));
        assertTrue(methods.contains("createDraft"));
        assertFalse(methods.contains("publish"));
        assertFalse(methods.contains("deploy"));
        assertFalse(methods.contains("activate"));
    }

    private ProcessTemplateGovernedImportCoordinator governed(
        ProcessTemplateTenantRegistryResolver registryResolver,
        ProcessTemplateFormPackageEvidenceResolver formResolver,
        RecordingImporter importer
    ) {
        ProcessTemplateDraftCreationService draftService =
            new ProcessTemplateDraftCreationService(previewService, importer);
        ProcessTemplateImportCoordinator base = new ProcessTemplateImportCoordinator(
            previewService,
            draftService,
            registryResolver
        );
        return new ProcessTemplateGovernedImportCoordinator(base, formResolver, evidenceHasher);
    }

    private FormPackageEvidence evidence(String packageHash) {
        String contentHash = evidenceHasher.hash(
            "tenant-a",
            "expenseImported",
            3,
            2,
            FORM_HASH,
            4,
            UI_HASH,
            packageHash
        );
        return new FormPackageEvidence(
            "tenant-a",
            "expenseImported",
            3,
            2,
            FORM_HASH,
            4,
            UI_HASH,
            packageHash,
            contentHash
        );
    }

    private static boolean hasEvidenceComponent(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
            .anyMatch(component -> component.getType() == FormPackageEvidence.class);
    }

    private static RequestContext context(String tenantId) {
        return new RequestContext(
            tenantId,
            "operator-1",
            "request-1",
            "idempotency-1",
            "trace-1"
        );
    }

    private static ImportResult draftResult() {
        return new ImportResult(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            1,
            ApprovalDesignDraft.Status.DRAFT,
            "expenseImported",
            1,
            3,
            "e".repeat(64),
            ENVELOPE_HASH
        );
    }

    private static TransferEnvelope envelope() {
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
            ENVELOPE_HASH
        );
    }

    private static final class RecordingImporter
        implements ProcessTemplateDraftCreationService.DraftImporter {

        private int calls;
        @Override
        public ImportResult importDraft(ImportCommand value) {
            calls++;
            return draftResult();
        }
    }
}
