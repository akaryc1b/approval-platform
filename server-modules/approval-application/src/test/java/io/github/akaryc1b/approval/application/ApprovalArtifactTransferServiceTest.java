package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ArtifactType;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.DefinitionPayload;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ImportCommand;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.ReleasePackagePayload;
import io.github.akaryc1b.approval.application.ApprovalArtifactTransferService.TransferEnvelope;
import io.github.akaryc1b.approval.application.port.ApprovalDefinitionVersionStore;
import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinitionVersion;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;
import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormPackage;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalArtifactTransferServiceTest {

    private static final String SOURCE_TENANT = "tenant-transfer-source";
    private static final String TARGET_TENANT = "tenant-transfer-target";
    private static final String KEY = PurchasePaymentTemplate.DEFINITION_KEY;
    private static final Instant NOW = Instant.parse("2026-07-19T14:00:00Z");
    private static final String FORM_HASH = "1".repeat(64);
    private static final String UI_HASH = "2".repeat(64);
    private static final String FORM_PACKAGE_HASH = "3".repeat(64);

    private InMemoryDraftStore drafts;
    private InMemoryDefinitionStore definitions;
    private InMemoryReleaseStore releases;
    private InMemoryFormPackageStore formPackages;
    private InMemoryFormStore forms;
    private InMemoryUiStore uiSchemas;
    private InMemoryAuditSink audit;
    private InMemoryIdempotency idempotency;
    private ApprovalArtifactTransferService service;
    private ApprovalDefinition sourceDefinition;
    private ApprovalReleasePackage sourceRelease;

    @BeforeEach
    void setUp() {
        drafts = new InMemoryDraftStore();
        definitions = new InMemoryDefinitionStore();
        releases = new InMemoryReleaseStore();
        formPackages = new InMemoryFormPackageStore();
        forms = new InMemoryFormStore();
        uiSchemas = new InMemoryUiStore();
        audit = new InMemoryAuditSink();
        idempotency = new InMemoryIdempotency();
        Supplier<UUID> identifiers = identifiers();
        service = new ApprovalArtifactTransferService(
            idempotency,
            drafts,
            definitions,
            releases,
            formPackages,
            forms,
            uiSchemas,
            audit,
            new ApprovalDefinitionValidator(),
            new ApprovalDslCompiler(),
            new ApprovalDefinitionHasher(),
            new ApprovalReleasePackageHasher(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            identifiers
        );
        sourceDefinition = PurchasePaymentTemplate.processDefinition();
        seedFormPackage(SOURCE_TENANT);
        seedFormPackage(TARGET_TENANT);
        ApprovalDefinitionHasher definitionHasher = new ApprovalDefinitionHasher();
        definitions.save(new ApprovalDefinitionVersion(
            SOURCE_TENANT,
            KEY,
            sourceDefinition.version(),
            definitionHasher.hash(sourceDefinition),
            1,
            FORM_PACKAGE_HASH,
            sourceDefinition,
            new UUID(0, 900),
            "source-publisher",
            NOW
        ));
        sourceRelease = releasePackage(sourceDefinition);
        releases.save(sourceRelease);
    }

    @Test
    void exportsDeterministicDslWithoutTenantOrPublisherIdentity() {
        TransferEnvelope first = service.exportDefinition(
            SOURCE_TENANT,
            KEY,
            sourceDefinition.version()
        );
        TransferEnvelope second = service.exportDefinition(
            SOURCE_TENANT,
            KEY,
            sourceDefinition.version()
        );

        assertEquals(ApprovalArtifactTransferService.DSL_FORMAT, first.format());
        assertEquals(ArtifactType.APPROVAL_DSL, first.artifactType());
        assertEquals(first.payloadHash(), second.payloadHash());
        assertEquals(first.envelopeHash(), second.envelopeHash());
        assertEquals(sourceDefinition, ((DefinitionPayload) first.payload()).definition());
        assertFalse(first.toString().contains(SOURCE_TENANT));
        assertFalse(first.toString().contains("source-publisher"));
        assertFalse(first.toString().contains("engineDefinitionId"));
    }

    @Test
    void exportsReleaseAndRecomputesEveryArtifactHash() {
        TransferEnvelope envelope = service.exportRelease(SOURCE_TENANT, KEY, 1);
        ReleasePackagePayload payload = (ReleasePackagePayload) envelope.payload();

        assertEquals(ApprovalArtifactTransferService.RELEASE_FORMAT, envelope.format());
        assertEquals(sourceRelease.packageHash(), payload.releasePackageHash());
        assertEquals(sourceRelease.bpmnHash(), payload.bpmnHash());
        assertEquals(sourceRelease.compiledArtifactHash(), payload.compiledArtifactHash());
        assertEquals(
            sourceRelease.deploymentMetadataHash(),
            payload.deploymentMetadataHash()
        );
        envelopeWithExportedAt(envelope, NOW.plusSeconds(60));
        assertFalse(envelope.toString().contains(SOURCE_TENANT));
        assertFalse(envelope.toString().contains("source-publisher"));
    }

    @Test
    void rejectsMissingDefinitionAndReleaseForTheTenant() {
        assertThrows(
            ApprovalArtifactTransferExceptions.SourceNotFound.class,
            () -> service.exportDefinition("missing-tenant", KEY, 1)
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.SourceNotFound.class,
            () -> service.exportRelease("missing-tenant", KEY, 1)
        );
    }

    @Test
    void rejectsUnsupportedFormatAndTamperedReleasePackageHash() {
        TransferEnvelope definitionEnvelope = service.exportDefinition(
            SOURCE_TENANT,
            KEY,
            sourceDefinition.version()
        );
        TransferEnvelope unsupported = new TransferEnvelope(
            "APPROVAL_UNKNOWN_EXPORT_V1",
            definitionEnvelope.formatVersion(),
            definitionEnvelope.artifactType(),
            definitionEnvelope.exportedAt(),
            definitionEnvelope.definitionKey(),
            definitionEnvelope.definitionVersion(),
            definitionEnvelope.releaseVersion(),
            definitionEnvelope.formPackageVersion(),
            definitionEnvelope.definitionHash(),
            definitionEnvelope.formPackageHash(),
            definitionEnvelope.payload(),
            definitionEnvelope.payloadHash(),
            definitionEnvelope.envelopeHash()
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.InvalidFormat.class,
            () -> service.verifyEnvelope(unsupported)
        );

        TransferEnvelope releaseEnvelope = service.exportRelease(SOURCE_TENANT, KEY, 1);
        ReleasePackagePayload original = (ReleasePackagePayload) releaseEnvelope.payload();
        ReleasePackagePayload tampered = new ReleasePackagePayload(
            original.definition(),
            original.compilerVersion(),
            original.bpmnResourceName(),
            original.bpmnArtifact(),
            original.bpmnHash(),
            original.dmnArtifact(),
            original.dmnHash(),
            original.compiledArtifactHash(),
            original.deploymentMetadataHash(),
            "f".repeat(64),
            original.formSchemaVersion(),
            original.formSchemaHash(),
            original.uiSchemaVersion(),
            original.uiSchemaHash()
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.HashMismatch.class,
            () -> service.verifyEnvelope(withPayload(releaseEnvelope, tampered))
        );
    }

    @Test
    void rejectsTamperedDefinitionAndDeclaredEnvelopeHashes() {
        TransferEnvelope envelope = service.exportDefinition(
            SOURCE_TENANT,
            KEY,
            sourceDefinition.version()
        );
        ApprovalDefinition changed = new ApprovalDefinition(
            sourceDefinition.schemaVersion(),
            sourceDefinition.definitionKey(),
            sourceDefinition.version(),
            "Changed transfer content",
            sourceDefinition.startNodeId(),
            sourceDefinition.nodes()
        );
        TransferEnvelope changedPayload = copyEnvelope(
            envelope,
            new DefinitionPayload(changed),
            envelope.payloadHash(),
            envelope.envelopeHash(),
            envelope.formatVersion()
        );
        TransferEnvelope changedDeclaredHash = copyEnvelope(
            envelope,
            envelope.payload(),
            "f".repeat(64),
            envelope.envelopeHash(),
            envelope.formatVersion()
        );

        assertThrows(
            ApprovalArtifactTransferExceptions.HashMismatch.class,
            () -> service.verifyEnvelope(changedPayload)
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.HashMismatch.class,
            () -> service.verifyEnvelope(changedDeclaredHash)
        );
    }

    @Test
    void rejectsUnsupportedFormatVersionAndOversizedDsl() {
        TransferEnvelope envelope = service.exportDefinition(
            SOURCE_TENANT,
            KEY,
            sourceDefinition.version()
        );
        TransferEnvelope unsupported = copyEnvelope(
            envelope,
            envelope.payload(),
            envelope.payloadHash(),
            envelope.envelopeHash(),
            2
        );
        List<ApprovalDefinition.ProcessNode> nodes = new ArrayList<>();
        nodes.add(new ApprovalDefinition.StartNode("start", "Start", "end"));
        nodes.add(new ApprovalDefinition.EndNode("end", "End"));
        for (int index = 0; index < ApprovalArtifactTransferService.MAX_NODES; index++) {
            nodes.add(new ApprovalDefinition.EndNode("extra" + index, "Extra " + index));
        }
        ApprovalDefinition oversized = new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION,
            KEY,
            1,
            "Oversized",
            "start",
            nodes
        );
        TransferEnvelope oversizedEnvelope = copyEnvelope(
            envelope,
            new DefinitionPayload(oversized),
            envelope.payloadHash(),
            envelope.envelopeHash(),
            envelope.formatVersion()
        );

        assertThrows(
            ApprovalArtifactTransferExceptions.UnsupportedVersion.class,
            () -> service.verifyEnvelope(unsupported)
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.TooLarge.class,
            () -> service.verifyEnvelope(oversizedEnvelope)
        );
    }

    @Test
    void rejectsTamperedBpmnInvalidXmlUnsafeResourceAndOversizedArtifact() {
        TransferEnvelope envelope = service.exportRelease(SOURCE_TENANT, KEY, 1);
        ReleasePackagePayload original = (ReleasePackagePayload) envelope.payload();
        ReleasePackagePayload invalidXml = copyReleasePayload(
            original,
            original.bpmnResourceName(),
            "<definitions>",
            original.bpmnHash()
        );
        ReleasePackagePayload validButTampered = copyReleasePayload(
            original,
            original.bpmnResourceName(),
            original.bpmnArtifact() + "\n",
            original.bpmnHash()
        );
        ReleasePackagePayload unsafeResource = copyReleasePayload(
            original,
            "../approval.bpmn20.xml",
            original.bpmnArtifact(),
            original.bpmnHash()
        );
        String oversizedBpmn = "x".repeat(
            ApprovalArtifactTransferService.MAX_BPMN_BYTES + 1
        );
        ReleasePackagePayload oversized = copyReleasePayload(
            original,
            original.bpmnResourceName(),
            oversizedBpmn,
            original.bpmnHash()
        );

        assertThrows(
            ApprovalArtifactTransferExceptions.ArtifactIntegrityFailed.class,
            () -> service.verifyEnvelope(withPayload(envelope, invalidXml))
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.HashMismatch.class,
            () -> service.verifyEnvelope(withPayload(envelope, validButTampered))
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.InvalidFormat.class,
            () -> service.verifyEnvelope(withPayload(envelope, unsafeResource))
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.TooLarge.class,
            () -> service.verifyEnvelope(withPayload(envelope, oversized))
        );
    }

    @Test
    void importsAcrossTenantAsRevisionOneDraftOnly() {
        TransferEnvelope envelope = service.exportRelease(SOURCE_TENANT, KEY, 1);
        var result = service.importArtifact(importCommand(envelope, "import-key"));

        assertEquals(1, result.revision());
        assertEquals(ApprovalDesignDraft.Status.DRAFT, result.status());
        ApprovalDesignDraft imported = drafts.find(TARGET_TENANT, result.draftId())
            .orElseThrow();
        assertEquals(TARGET_TENANT, imported.tenantId());
        assertEquals(2, imported.definition().version());
        assertEquals("Imported approval", imported.name());
        assertEquals(1, imported.formPackage().packageVersion());
        assertEquals(1, definitions.count(SOURCE_TENANT));
        assertEquals(0, definitions.count(TARGET_TENANT));
        assertEquals(1, releases.count(SOURCE_TENANT));
        assertEquals(0, releases.count(TARGET_TENANT));
        assertEquals(1, drafts.count(TARGET_TENANT));
        assertEquals(1, audit.events.size());
        AuditEvent event = audit.events.getFirst();
        assertEquals("APPROVAL_DESIGN_DRAFT_IMPORTED", event.action());
        assertEquals(TARGET_TENANT, event.tenantId());
        assertFalse(event.attributes().toString().contains(SOURCE_TENANT));
        assertFalse(event.attributes().toString().contains("bpmnArtifact"));
        assertFalse(event.attributes().toString().contains("source-publisher"));
    }

    @Test
    void idempotentReplayReturnsSameDraftAndDifferentContentConflicts() {
        TransferEnvelope envelope = service.exportDefinition(
            SOURCE_TENANT,
            KEY,
            sourceDefinition.version()
        );
        var first = service.importArtifact(importCommand(envelope, "same-key"));
        var replay = service.importArtifact(importCommand(envelope, "same-key"));

        assertEquals(first, replay);
        assertEquals(1, drafts.count(TARGET_TENANT));
        assertEquals(1, audit.events.size());
        ImportCommand different = new ImportCommand(
            context("same-key"),
            envelope,
            KEY,
            2,
            1,
            "Different target name"
        );
        assertThrows(
            ApprovalArtifactTransferExceptions.ImportConflict.class,
            () -> service.importArtifact(different)
        );
        assertEquals(1, drafts.count(TARGET_TENANT));
    }

    @Test
    void rejectsMissingTargetFormPackageWithoutPartialWrites() {
        TransferEnvelope envelope = service.exportDefinition(
            SOURCE_TENANT,
            KEY,
            sourceDefinition.version()
        );
        formPackages.remove(TARGET_TENANT, KEY, 1);

        assertThrows(
            ApprovalArtifactTransferExceptions.FormPackageIncompatible.class,
            () -> service.importArtifact(importCommand(envelope, "missing-form"))
        );
        assertEquals(0, drafts.count(TARGET_TENANT));
        assertEquals(0, definitions.count(TARGET_TENANT));
        assertEquals(0, releases.count(TARGET_TENANT));
        assertTrue(audit.events.isEmpty());
    }

    @Test
    void rejectsTargetValidationFailureAndExistingImmutableVersion() {
        TransferEnvelope envelope = service.exportDefinition(
            SOURCE_TENANT,
            KEY,
            sourceDefinition.version()
        );
        ApprovalDefinition invalid = new ApprovalDefinition(
            sourceDefinition.schemaVersion(),
            sourceDefinition.definitionKey(),
            sourceDefinition.version(),
            sourceDefinition.name(),
            sourceDefinition.startNodeId(),
            List.of(
                new ApprovalDefinition.StartNode("start", "Start", "missing"),
                new ApprovalDefinition.EndNode("end", "End")
            )
        );
        TransferEnvelope invalidEnvelope = signedDefinitionEnvelope(invalid);

        assertThrows(
            ApprovalArtifactTransferExceptions.ValidationFailed.class,
            () -> service.importArtifact(importCommand(invalidEnvelope, "invalid"))
        );
        assertEquals(0, drafts.count(TARGET_TENANT));

        definitions.save(new ApprovalDefinitionVersion(
            TARGET_TENANT,
            KEY,
            2,
            new ApprovalDefinitionHasher().hash(copyDefinition(sourceDefinition, 2)),
            1,
            FORM_PACKAGE_HASH,
            copyDefinition(sourceDefinition, 2),
            new UUID(0, 901),
            "target-publisher",
            NOW
        ));
        assertThrows(
            ApprovalArtifactTransferExceptions.ImportConflict.class,
            () -> service.importArtifact(importCommand(envelope, "occupied"))
        );
        assertEquals(0, drafts.count(TARGET_TENANT));
    }

    private ImportCommand importCommand(TransferEnvelope envelope, String key) {
        return new ImportCommand(
            context(key),
            envelope,
            KEY,
            2,
            1,
            "Imported approval"
        );
    }

    private RequestContext context(String idempotencyKey) {
        return new RequestContext(
            TARGET_TENANT,
            "target-operator",
            "request-" + idempotencyKey,
            idempotencyKey,
            "trace-" + idempotencyKey
        );
    }

    private TransferEnvelope signedDefinitionEnvelope(ApprovalDefinition definition) {
        ApprovalDefinitionHasher definitionHasher = new ApprovalDefinitionHasher();
        ApprovalReleasePackageHasher hasher = new ApprovalReleasePackageHasher();
        String definitionHash = definitionHasher.hash(definition);
        String payloadHash = hasher.hashValues(
            "approval-dsl-transfer-payload-v1",
            definitionHash,
            1,
            FORM_PACKAGE_HASH
        );
        String envelopeHash = hasher.hashValues(
            "approval-artifact-transfer-envelope-v1",
            ApprovalArtifactTransferService.DSL_FORMAT,
            ApprovalArtifactTransferService.FORMAT_VERSION,
            ArtifactType.APPROVAL_DSL,
            definition.definitionKey(),
            definition.version(),
            null,
            1,
            definitionHash,
            FORM_PACKAGE_HASH,
            payloadHash
        );
        return new TransferEnvelope(
            ApprovalArtifactTransferService.DSL_FORMAT,
            ApprovalArtifactTransferService.FORMAT_VERSION,
            ArtifactType.APPROVAL_DSL,
            NOW,
            definition.definitionKey(),
            definition.version(),
            null,
            1,
            definitionHash,
            FORM_PACKAGE_HASH,
            new DefinitionPayload(definition),
            payloadHash,
            envelopeHash
        );
    }

    private ApprovalReleasePackage releasePackage(ApprovalDefinition definition) {
        ApprovalDefinitionHasher definitionHasher = new ApprovalDefinitionHasher();
        ApprovalReleasePackageHasher hasher = new ApprovalReleasePackageHasher();
        ApprovalDslCompiler.CompiledDefinition compiled = new ApprovalDslCompiler().compile(
            definition,
            PurchasePaymentTemplate.formDefinition()
        );
        String definitionHash = definitionHasher.hash(definition);
        String bpmnHash = hasher.artifactHash(compiled.bpmnXml());
        String metadataHash = hasher.deploymentMetadataHash(
            compiled.compilerVersion(),
            compiled.resourceName(),
            bpmnHash
        );
        String packageHash = hasher.hash(
            definition.definitionKey(),
            1,
            definition.version(),
            definitionHash,
            1,
            FORM_PACKAGE_HASH,
            PurchasePaymentTemplate.formDefinition().version(),
            FORM_HASH,
            1,
            UI_HASH,
            compiled.compilerVersion(),
            compiled.resourceName(),
            compiled.bpmnXml(),
            compiled.contentHash(),
            bpmnHash,
            null,
            null,
            metadataHash
        );
        return new ApprovalReleasePackage(
            SOURCE_TENANT,
            definition.definitionKey(),
            1,
            definition.version(),
            definitionHash,
            1,
            FORM_PACKAGE_HASH,
            PurchasePaymentTemplate.formDefinition().version(),
            FORM_HASH,
            1,
            UI_HASH,
            compiled.compilerVersion(),
            compiled.resourceName(),
            compiled.bpmnXml(),
            compiled.contentHash(),
            bpmnHash,
            null,
            null,
            metadataHash,
            packageHash,
            new UUID(0, 900),
            "source-publisher",
            NOW
        );
    }

    private void seedFormPackage(String tenantId) {
        FormDefinition form = PurchasePaymentTemplate.formDefinition();
        UiSchemaDefinition ui = new UiSchemaDefinition(
            UiSchemaDefinition.CURRENT_SCHEMA_VERSION,
            KEY,
            form.version(),
            1,
            "Transfer UI",
            List.of(),
            List.of()
        );
        formPackages.save(new FormPackage(
            tenantId,
            KEY,
            1,
            form.version(),
            FORM_HASH,
            ui.version(),
            UI_HASH,
            FORM_PACKAGE_HASH,
            new UUID(0, tenantId.equals(SOURCE_TENANT) ? 700 : 701),
            "form-publisher",
            NOW
        ));
        forms.save(new ApprovalFormStore.PublishedForm(
            tenantId,
            form,
            FORM_HASH,
            "form-publisher",
            NOW
        ));
        uiSchemas.save(new ApprovalUiSchemaStore.PublishedUiSchema(
            tenantId,
            ui,
            UI_HASH,
            "form-publisher",
            NOW
        ));
    }

    private TransferEnvelope withPayload(
        TransferEnvelope envelope,
        ReleasePackagePayload payload
    ) {
        return copyEnvelope(
            envelope,
            payload,
            envelope.payloadHash(),
            envelope.envelopeHash(),
            envelope.formatVersion()
        );
    }

    private TransferEnvelope copyEnvelope(
        TransferEnvelope source,
        ApprovalArtifactTransferService.TransferPayload payload,
        String payloadHash,
        String envelopeHash,
        int formatVersion
    ) {
        return new TransferEnvelope(
            source.format(),
            formatVersion,
            source.artifactType(),
            source.exportedAt(),
            source.definitionKey(),
            source.definitionVersion(),
            source.releaseVersion(),
            source.formPackageVersion(),
            source.definitionHash(),
            source.formPackageHash(),
            payload,
            payloadHash,
            envelopeHash
        );
    }

    private void envelopeWithExportedAt(TransferEnvelope source, Instant exportedAt) {
        TransferEnvelope changed = new TransferEnvelope(
            source.format(),
            source.formatVersion(),
            source.artifactType(),
            exportedAt,
            source.definitionKey(),
            source.definitionVersion(),
            source.releaseVersion(),
            source.formPackageVersion(),
            source.definitionHash(),
            source.formPackageHash(),
            source.payload(),
            source.payloadHash(),
            source.envelopeHash()
        );
        assertEquals(source.payloadHash(), changed.payloadHash());
        assertEquals(source.envelopeHash(), changed.envelopeHash());
    }

    private ReleasePackagePayload copyReleasePayload(
        ReleasePackagePayload source,
        String resourceName,
        String bpmnArtifact,
        String bpmnHash
    ) {
        return new ReleasePackagePayload(
            source.definition(),
            source.compilerVersion(),
            resourceName,
            bpmnArtifact,
            bpmnHash,
            source.dmnArtifact(),
            source.dmnHash(),
            source.compiledArtifactHash(),
            source.deploymentMetadataHash(),
            source.releasePackageHash(),
            source.formSchemaVersion(),
            source.formSchemaHash(),
            source.uiSchemaVersion(),
            source.uiSchemaHash()
        );
    }

    private static ApprovalDefinition copyDefinition(
        ApprovalDefinition source,
        int version
    ) {
        return new ApprovalDefinition(
            source.schemaVersion(),
            source.definitionKey(),
            version,
            source.name(),
            source.startNodeId(),
            source.nodes()
        );
    }

    private static Supplier<UUID> identifiers() {
        AtomicLong sequence = new AtomicLong(1_000);
        return () -> new UUID(0, sequence.incrementAndGet());
    }

    private static final class InMemoryIdempotency implements IdempotencyGuard {
        private final Map<String, Replay> values = new HashMap<>();

        @Override
        public <T> T execute(
            RequestContext context,
            String operation,
            String requestHash,
            Class<T> resultType,
            Supplier<T> action
        ) {
            String key = context.tenantId() + "|" + operation + "|" + context.idempotencyKey();
            Replay existing = values.get(key);
            if (existing != null) {
                if (!existing.requestHash.equals(requestHash)
                    || !existing.resultType.equals(resultType)) {
                    throw new IdempotencyConflictException("different request content");
                }
                return resultType.cast(existing.result);
            }
            T result = action.get();
            values.put(key, new Replay(requestHash, resultType, result));
            return result;
        }

        private record Replay(String requestHash, Class<?> resultType, Object result) {
        }
    }

    private static final class InMemoryDraftStore implements ApprovalDesignDraftStore {
        private final Map<String, ApprovalDesignDraft> values = new HashMap<>();

        @Override
        public void save(ApprovalDesignDraft draft) {
            values.put(key(draft.tenantId(), draft.draftId()), draft);
        }

        @Override
        public Optional<ApprovalDesignDraft> find(String tenantId, UUID draftId) {
            return Optional.ofNullable(values.get(key(tenantId, draftId)));
        }

        @Override
        public DraftPage findDrafts(DraftCriteria criteria) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void lock(String tenantId, UUID draftId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean update(ApprovalDesignDraft draft, long expectedRevision) {
            throw new UnsupportedOperationException();
        }

        int count(String tenantId) {
            return (int) values.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .count();
        }

        private static String key(String tenantId, UUID draftId) {
            return tenantId + "|" + draftId;
        }
    }

    private static final class InMemoryDefinitionStore
        implements ApprovalDefinitionVersionStore {
        private final Map<String, ApprovalDefinitionVersion> values = new HashMap<>();

        @Override
        public void lockVersion(String tenantId, String definitionKey, int version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ApprovalDefinitionVersion> find(
            String tenantId,
            String definitionKey,
            int version
        ) {
            return Optional.ofNullable(values.get(key(tenantId, definitionKey, version)));
        }

        @Override
        public Optional<ApprovalDefinitionVersion> findLatest(
            String tenantId,
            String definitionKey
        ) {
            return values.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.definitionKey().equals(definitionKey))
                .max(java.util.Comparator.comparingInt(ApprovalDefinitionVersion::version));
        }

        @Override
        public Optional<ApprovalDefinitionVersion> findByDraft(
            String tenantId,
            UUID draftId
        ) {
            return values.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.sourceDraftId().equals(draftId))
                .findFirst();
        }

        @Override
        public VersionPage findVersions(VersionCriteria criteria) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void save(ApprovalDefinitionVersion version) {
            values.put(key(version.tenantId(), version.definitionKey(), version.version()), version);
        }

        int count(String tenantId) {
            return (int) values.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .count();
        }

        private static String key(String tenantId, String definitionKey, int version) {
            return tenantId + "|" + definitionKey + "|" + version;
        }
    }

    private static final class InMemoryReleaseStore implements ApprovalReleasePackageStore {
        private final Map<String, ApprovalReleasePackage> values = new HashMap<>();

        @Override
        public void lockVersion(String tenantId, String definitionKey, int releaseVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ApprovalReleasePackage> find(
            String tenantId,
            String definitionKey,
            int releaseVersion
        ) {
            return Optional.ofNullable(values.get(key(
                tenantId,
                definitionKey,
                releaseVersion
            )));
        }

        @Override
        public Optional<ApprovalReleasePackage> findLatest(
            String tenantId,
            String definitionKey
        ) {
            return values.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.definitionKey().equals(definitionKey))
                .max(java.util.Comparator.comparingInt(
                    ApprovalReleasePackage::releaseVersion
                ));
        }

        @Override
        public Optional<ApprovalReleasePackage> findByDraft(
            String tenantId,
            UUID draftId
        ) {
            return values.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.sourceDraftId().equals(draftId))
                .findFirst();
        }

        @Override
        public ReleasePage findReleases(ReleaseCriteria criteria) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void save(ApprovalReleasePackage releasePackage) {
            values.put(key(
                releasePackage.tenantId(),
                releasePackage.definitionKey(),
                releasePackage.releaseVersion()
            ), releasePackage);
        }

        int count(String tenantId) {
            return (int) values.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .count();
        }

        private static String key(String tenantId, String definitionKey, int version) {
            return tenantId + "|" + definitionKey + "|" + version;
        }
    }

    private static final class InMemoryFormPackageStore implements ApprovalFormPackageStore {
        private final Map<String, FormPackage> values = new HashMap<>();

        @Override
        public void lockVersion(String tenantId, String formKey, int packageVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<FormPackage> find(
            String tenantId,
            String formKey,
            int packageVersion
        ) {
            return Optional.ofNullable(values.get(key(tenantId, formKey, packageVersion)));
        }

        @Override
        public Optional<FormPackage> findByDraft(String tenantId, UUID draftId) {
            return Optional.empty();
        }

        @Override
        public void save(FormPackage formPackage) {
            values.put(key(
                formPackage.tenantId(),
                formPackage.formKey(),
                formPackage.packageVersion()
            ), formPackage);
        }

        void remove(String tenantId, String formKey, int packageVersion) {
            values.remove(key(tenantId, formKey, packageVersion));
        }

        private static String key(String tenantId, String formKey, int version) {
            return tenantId + "|" + formKey + "|" + version;
        }
    }

    private static final class InMemoryFormStore implements ApprovalFormStore {
        private final Map<String, PublishedForm> values = new HashMap<>();

        @Override
        public void lockVersion(String tenantId, String formKey, int version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PublishedForm> find(String tenantId, String formKey, int version) {
            return Optional.ofNullable(values.get(key(tenantId, formKey, version)));
        }

        @Override
        public FormPage findForms(FormCriteria criteria) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void save(PublishedForm form) {
            values.put(key(
                form.tenantId(),
                form.definition().formKey(),
                form.definition().version()
            ), form);
        }

        private static String key(String tenantId, String formKey, int version) {
            return tenantId + "|" + formKey + "|" + version;
        }
    }

    private static final class InMemoryUiStore implements ApprovalUiSchemaStore {
        private final Map<String, PublishedUiSchema> values = new HashMap<>();

        @Override
        public void lockVersion(
            String tenantId,
            String formKey,
            int formVersion,
            int uiSchemaVersion
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PublishedUiSchema> find(
            String tenantId,
            String formKey,
            int formVersion,
            int uiSchemaVersion
        ) {
            return Optional.ofNullable(values.get(key(
                tenantId,
                formKey,
                formVersion,
                uiSchemaVersion
            )));
        }

        @Override
        public Optional<PublishedUiSchema> findLatest(
            String tenantId,
            String formKey,
            int formVersion
        ) {
            return values.values().stream()
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.definition().formKey().equals(formKey))
                .filter(value -> value.definition().formVersion() == formVersion)
                .max(java.util.Comparator.comparingInt(value ->
                    value.definition().version()
                ));
        }

        @Override
        public void save(PublishedUiSchema uiSchema) {
            values.put(key(
                uiSchema.tenantId(),
                uiSchema.definition().formKey(),
                uiSchema.definition().formVersion(),
                uiSchema.definition().version()
            ), uiSchema);
        }

        private static String key(
            String tenantId,
            String formKey,
            int formVersion,
            int uiVersion
        ) {
            return tenantId + "|" + formKey + "|" + formVersion + "|" + uiVersion;
        }
    }

    private static final class InMemoryAuditSink implements AuditEventSink {
        private final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void append(AuditEvent event) {
            events.add(event);
        }
    }
}
