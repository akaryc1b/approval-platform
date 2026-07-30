package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ArtifactType;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.DefinitionPayload;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ImportCommand;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ImportResult;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.TransferEnvelope;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.ImportPlan;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TemplatePackage;
import io.github.akaryc1b.approval.application.ProcessTemplateContracts.TenantRegistrySnapshot;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTemplateImportCoordinatorTest {

    private static final String ENVELOPE_HASH = "a".repeat(64);

    private final ProcessTemplateCanonicalHasher hasher = new ProcessTemplateCanonicalHasher();
    private final ProcessTemplatePackageValidator validator = new ProcessTemplatePackageValidator(hasher);
    private final ProcessTemplateImportPreviewService previewService =
        new ProcessTemplateImportPreviewService(validator, hasher);

    @Test
    void previewResolvesCurrentTenantRegistryServerSide() {
        AtomicInteger resolutions = new AtomicInteger();
        ProcessTemplateTenantRegistryResolver resolver = request -> {
            resolutions.incrementAndGet();
            assertEquals("tenant-a", request.tenantId());
            assertEquals("expenseApproval", request.templatePackage().manifest().templateKey());
            return ProcessTemplateFixtures.completeRegistry();
        };
        ProcessTemplateImportCoordinator coordinator = coordinator(resolver, new RecordingImporter());

        ImportPlan plan = coordinator.preview(new ProcessTemplateImportCoordinator.PreviewCommand(
            ProcessTemplateFixtures.validPackage(),
            4096,
            ProcessTemplateFixtures.completeRequest()
        ));

        assertTrue(plan.importable());
        assertEquals(1, resolutions.get());
    }

    @Test
    void createDraftResolvesRegistryAgainAndNeverAcceptsCallerSnapshot() {
        AtomicInteger resolutions = new AtomicInteger();
        ProcessTemplateTenantRegistryResolver resolver = request -> {
            resolutions.incrementAndGet();
            return ProcessTemplateFixtures.completeRegistry();
        };
        RecordingImporter importer = new RecordingImporter();
        ProcessTemplateImportCoordinator coordinator = coordinator(resolver, importer);
        TransferEnvelope envelope = envelope();
        TemplatePackage templatePackage = ProcessTemplateFixtures.withSourceArtifactHash(
            "APPROVAL_DSL",
            ENVELOPE_HASH
        );
        var request = ProcessTemplateFixtures.completeRequest();
        ImportPlan plan = coordinator.preview(new ProcessTemplateImportCoordinator.PreviewCommand(
            templatePackage,
            4096,
            request
        ));

        var result = coordinator.createDraft(new ProcessTemplateImportCoordinator.CreateDraftCommand(
            context("tenant-a"),
            templatePackage,
            4096,
            request,
            plan.planHash(),
            envelope
        ));

        assertEquals(2, resolutions.get());
        assertEquals(1, importer.calls);
        assertEquals(ApprovalDesignDraft.Status.DRAFT, result.draft().status());
    }

    @Test
    void registryDriftBetweenPreviewAndCreateRejectsStalePlanBeforeWrite() {
        AtomicInteger resolutions = new AtomicInteger();
        TenantRegistrySnapshot drifted = new TenantRegistrySnapshot(
            "tenant-a",
            "1.4.0",
            Set.of("amount"),
            Set.of(),
            Set.of("invoice.local"),
            Set.of("finance.local"),
            Set.of("approver.local"),
            ProcessTemplateFixtures.completeRegistry().components()
        );
        ProcessTemplateTenantRegistryResolver resolver = request ->
            resolutions.incrementAndGet() == 1
                ? ProcessTemplateFixtures.completeRegistry()
                : drifted;
        RecordingImporter importer = new RecordingImporter();
        ProcessTemplateImportCoordinator coordinator = coordinator(resolver, importer);
        TemplatePackage templatePackage = ProcessTemplateFixtures.withSourceArtifactHash(
            "APPROVAL_DSL",
            ENVELOPE_HASH
        );
        var request = ProcessTemplateFixtures.completeRequest();
        ImportPlan plan = coordinator.preview(new ProcessTemplateImportCoordinator.PreviewCommand(
            templatePackage,
            4096,
            request
        ));

        assertThrows(ProcessTemplateException.StalePlan.class, () -> coordinator.createDraft(
            new ProcessTemplateImportCoordinator.CreateDraftCommand(
                context("tenant-a"),
                templatePackage,
                4096,
                request,
                plan.planHash(),
                envelope()
            )
        ));
        assertEquals(2, resolutions.get());
        assertEquals(0, importer.calls);
    }


    @Test
    void rejectsCrossTenantWriteContextBeforeRegistryResolution() {
        AtomicInteger resolutions = new AtomicInteger();
        ProcessTemplateImportCoordinator coordinator = coordinator(request -> {
            resolutions.incrementAndGet();
            return ProcessTemplateFixtures.completeRegistry();
        }, new RecordingImporter());
        TemplatePackage templatePackage = ProcessTemplateFixtures.withSourceArtifactHash(
            "APPROVAL_DSL",
            ENVELOPE_HASH
        );
        var request = ProcessTemplateFixtures.completeRequest();
        ImportPlan plan = coordinator.preview(new ProcessTemplateImportCoordinator.PreviewCommand(
            templatePackage,
            4096,
            request
        ));
        assertEquals(1, resolutions.get());

        assertThrows(ProcessTemplateException.CrossTenantBinding.class, () -> coordinator.createDraft(
            new ProcessTemplateImportCoordinator.CreateDraftCommand(
                context("tenant-b"),
                templatePackage,
                4096,
                request,
                plan.planHash(),
                envelope()
            )
        ));
        assertEquals(1, resolutions.get());
    }

    @Test
    void rejectsCrossTenantRegistryReturnedByResolver() {
        TenantRegistrySnapshot wrongTenant = new TenantRegistrySnapshot(
            "tenant-b",
            "1.4.0",
            Set.of("amount"),
            Set.of("finance.submit.local"),
            Set.of("invoice.local"),
            Set.of("finance.local"),
            Set.of("approver.local"),
            ProcessTemplateFixtures.completeRegistry().components()
        );
        ProcessTemplateImportCoordinator coordinator = coordinator(request -> wrongTenant,
            new RecordingImporter());

        assertThrows(ProcessTemplateException.CrossTenantBinding.class, () -> coordinator.preview(
            new ProcessTemplateImportCoordinator.PreviewCommand(
                ProcessTemplateFixtures.validPackage(),
                4096,
                ProcessTemplateFixtures.completeRequest()
            )
        ));
    }

    @Test
    void rejectsNullRegistrySnapshot() {
        ProcessTemplateImportCoordinator coordinator = coordinator(request -> null,
            new RecordingImporter());

        assertThrows(ProcessTemplateException.RegistryResolutionFailed.class, () -> coordinator.preview(
            new ProcessTemplateImportCoordinator.PreviewCommand(
                ProcessTemplateFixtures.validPackage(),
                4096,
                ProcessTemplateFixtures.completeRequest()
            )
        ));
    }

    @Test
    void hidesResolverFailureBehindSafeError() {
        ProcessTemplateImportCoordinator coordinator = coordinator(request -> {
            throw new IllegalStateException("sensitive server detail");
        }, new RecordingImporter());

        ProcessTemplateException.RegistryResolutionFailed failure = assertThrows(
            ProcessTemplateException.RegistryResolutionFailed.class,
            () -> coordinator.preview(new ProcessTemplateImportCoordinator.PreviewCommand(
                ProcessTemplateFixtures.validPackage(),
                4096,
                ProcessTemplateFixtures.completeRequest()
            ))
        );
        assertFalse(failure.getMessage().contains("sensitive"));
    }

    @Test
    void publicCommandsCannotCarryTrustedRegistryOrReleaseMutation() {
        assertFalse(hasRegistryComponent(ProcessTemplateImportCoordinator.PreviewCommand.class));
        assertFalse(hasRegistryComponent(ProcessTemplateImportCoordinator.CreateDraftCommand.class));
        List<String> methods = Arrays.stream(ProcessTemplateImportCoordinator.class.getDeclaredMethods())
            .map(java.lang.reflect.Method::getName)
            .toList();
        assertTrue(methods.contains("preview"));
        assertTrue(methods.contains("createDraft"));
        assertFalse(methods.contains("publish"));
        assertFalse(methods.contains("deploy"));
        assertFalse(methods.contains("activate"));
    }

    private ProcessTemplateImportCoordinator coordinator(
        ProcessTemplateTenantRegistryResolver resolver,
        RecordingImporter importer
    ) {
        ProcessTemplateDraftCreationService draftService =
            new ProcessTemplateDraftCreationService(previewService, importer);
        return new ProcessTemplateImportCoordinator(previewService, draftService, resolver);
    }

    private static boolean hasRegistryComponent(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
            .anyMatch(component -> component.getType() == TenantRegistrySnapshot.class);
    }

    private static RequestContext context(String tenantId) {
        return new RequestContext(tenantId, "operator-1", "request-1", "idempotency-1", "trace-1");
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
        private ImportCommand command;

        @Override
        public ImportResult importDraft(ImportCommand command) {
            calls++;
            this.command = command;
            return draftResult();
        }
    }
}
