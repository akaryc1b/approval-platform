package io.github.akaryc1b.approval.application;

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
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Secure, deterministic and tenant-safe Approval DSL and Release Package transfer. */
public final class ApprovalArtifactTransferService {

    public static final String DSL_FORMAT = "APPROVAL_DSL_EXPORT_V1";
    public static final String RELEASE_FORMAT = "APPROVAL_RELEASE_PACKAGE_EXPORT_V1";
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_NODES = 500;
    public static final int MAX_CONDITION_ROUTES = 2_000;
    public static final int MAX_PARALLEL_BRANCHES = 2_000;
    public static final int MAX_STRING_LENGTH = 64 * 1024;
    public static final int MAX_BPMN_BYTES = 1024 * 1024;
    public static final int MAX_DMN_BYTES = 512 * 1024;

    private static final String IMPORT_OPERATION = "approval-artifact-transfer.import.v1";
    private static final String DSL_PAYLOAD_PROTOCOL = "approval-dsl-transfer-payload-v1";
    private static final String RELEASE_PAYLOAD_PROTOCOL =
        "approval-release-transfer-payload-v1";
    private static final String ENVELOPE_PROTOCOL = "approval-artifact-transfer-envelope-v1";

    private final IdempotencyGuard idempotency;
    private final ApprovalDesignDraftStore drafts;
    private final ApprovalDefinitionVersionStore definitions;
    private final ApprovalReleasePackageStore releases;
    private final ApprovalFormPackageResolver formResolver;
    private final AuditEventSink auditEvents;
    private final ApprovalDefinitionValidator validator;
    private final ApprovalDslCompiler compiler;
    private final ApprovalDefinitionHasher definitionHasher;
    private final ApprovalReleasePackageHasher releaseHasher;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ApprovalArtifactTransferService(
        IdempotencyGuard idempotency,
        ApprovalDesignDraftStore drafts,
        ApprovalDefinitionVersionStore definitions,
        ApprovalReleasePackageStore releases,
        ApprovalFormPackageStore formPackages,
        ApprovalFormStore forms,
        ApprovalUiSchemaStore uiSchemas,
        AuditEventSink auditEvents,
        ApprovalDefinitionValidator validator,
        ApprovalDslCompiler compiler,
        ApprovalDefinitionHasher definitionHasher,
        ApprovalReleasePackageHasher releaseHasher,
        Clock clock,
        Supplier<UUID> identifiers
    ) {
        this.idempotency = Objects.requireNonNull(idempotency);
        this.drafts = Objects.requireNonNull(drafts);
        this.definitions = Objects.requireNonNull(definitions);
        this.releases = Objects.requireNonNull(releases);
        this.formResolver = new ApprovalFormPackageResolver(formPackages, forms, uiSchemas);
        this.auditEvents = Objects.requireNonNull(auditEvents);
        this.validator = Objects.requireNonNull(validator);
        this.compiler = Objects.requireNonNull(compiler);
        this.definitionHasher = Objects.requireNonNull(definitionHasher);
        this.releaseHasher = Objects.requireNonNull(releaseHasher);
        this.clock = Objects.requireNonNull(clock);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    public TransferEnvelope exportDefinition(
        String tenantId,
        String definitionKey,
        int definitionVersion
    ) {
        requireText(tenantId, "tenantId");
        requireText(definitionKey, "definitionKey");
        requirePositive(definitionVersion, "definitionVersion");
        ApprovalDefinitionVersion source = definitions.find(
            tenantId,
            definitionKey,
            definitionVersion
        ).orElseThrow(() -> new ApprovalArtifactTransferExceptions.SourceNotFound(
            "Approval DSL version was not found for the tenant"
        ));
        verifyDefinitionVersion(source);
        var exact = resolveFormPackage(
            tenantId,
            definitionKey,
            source.formPackageVersion()
        );
        if (!source.formPackageHash().equals(exact.formPackage().packageHash())) {
            throw integrity("Approval DSL Form Package hash does not match local immutable content");
        }
        DefinitionPayload payload = new DefinitionPayload(source.definition());
        String payloadHash = definitionPayloadHash(
            source.contentHash(),
            source.formPackageVersion(),
            source.formPackageHash()
        );
        return envelope(
            DSL_FORMAT,
            ArtifactType.APPROVAL_DSL,
            source.definitionKey(),
            source.version(),
            null,
            source.formPackageVersion(),
            source.contentHash(),
            source.formPackageHash(),
            payload,
            payloadHash
        );
    }

    public TransferEnvelope exportRelease(
        String tenantId,
        String definitionKey,
        int releaseVersion
    ) {
        requireText(tenantId, "tenantId");
        requireText(definitionKey, "definitionKey");
        requirePositive(releaseVersion, "releaseVersion");
        ApprovalReleasePackage release = releases.find(
            tenantId,
            definitionKey,
            releaseVersion
        ).orElseThrow(() -> new ApprovalArtifactTransferExceptions.SourceNotFound(
            "Release Package was not found for the tenant"
        ));
        ApprovalDefinitionVersion source = definitions.find(
            tenantId,
            definitionKey,
            release.definitionVersion()
        ).orElseThrow(() -> new ApprovalArtifactTransferExceptions.SourceNotFound(
            "Release Package Approval DSL version was not found for the tenant"
        ));
        verifyDefinitionVersion(source);
        var exact = resolveFormPackage(
            tenantId,
            definitionKey,
            release.formPackageVersion()
        );
        verifyReleasePackage(release, source.definition(), exact);
        ReleasePackagePayload payload = releasePayload(release, source.definition());
        String payloadHash = releasePayloadHash(payload);
        return envelope(
            RELEASE_FORMAT,
            ArtifactType.APPROVAL_RELEASE_PACKAGE,
            release.definitionKey(),
            release.definitionVersion(),
            release.releaseVersion(),
            release.formPackageVersion(),
            release.definitionHash(),
            release.formPackageHash(),
            payload,
            payloadHash
        );
    }

    public ImportResult importArtifact(ImportCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        VerifiedEnvelope verified = verifyEnvelope(command.envelope());
        String requestHash = releaseHasher.hashValues(
            IMPORT_OPERATION,
            verified.envelopeHash(),
            command.targetDefinitionKey(),
            command.targetDefinitionVersion(),
            command.targetFormPackageVersion(),
            command.targetName()
        );
        try {
            return idempotency.execute(
                command.context(),
                IMPORT_OPERATION,
                requestHash,
                ImportResult.class,
                () -> createImportedDraft(command, verified)
            );
        } catch (IdempotencyGuard.IdempotencyConflictException exception) {
            throw new ApprovalArtifactTransferExceptions.ImportConflict(
                "idempotency key was already used with different import content",
                exception
            );
        }
    }

    public VerifiedEnvelope verifyEnvelope(TransferEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        if (envelope.formatVersion() != FORMAT_VERSION) {
            throw new ApprovalArtifactTransferExceptions.UnsupportedVersion(
                "unsupported Approval artifact transfer formatVersion"
            );
        }
        requireHash(envelope.definitionHash(), "definitionHash");
        requireHash(envelope.formPackageHash(), "formPackageHash");
        requireHash(envelope.payloadHash(), "payloadHash");
        requireHash(envelope.envelopeHash(), "envelopeHash");
        requirePositive(envelope.definitionVersion(), "definitionVersion");
        requirePositive(envelope.formPackageVersion(), "formPackageVersion");
        requireSafeText(envelope.definitionKey(), "definitionKey");

        String actualPayloadHash;
        if (DSL_FORMAT.equals(envelope.format())) {
            requireArtifactType(envelope, ArtifactType.APPROVAL_DSL);
            if (envelope.releaseVersion() != null) {
                throw invalid("DSL transfer must not declare a releaseVersion");
            }
            DefinitionPayload payload = requireDefinitionPayload(envelope.payload());
            verifyDefinitionIdentity(
                payload.definition(),
                envelope.definitionKey(),
                envelope.definitionVersion(),
                envelope.definitionHash()
            );
            actualPayloadHash = definitionPayloadHash(
                envelope.definitionHash(),
                envelope.formPackageVersion(),
                envelope.formPackageHash()
            );
        } else if (RELEASE_FORMAT.equals(envelope.format())) {
            requireArtifactType(envelope, ArtifactType.APPROVAL_RELEASE_PACKAGE);
            if (envelope.releaseVersion() == null || envelope.releaseVersion() < 1) {
                throw invalid("Release Package transfer requires a positive releaseVersion");
            }
            ReleasePackagePayload payload = requireReleasePayload(envelope.payload());
            verifyDefinitionIdentity(
                payload.definition(),
                envelope.definitionKey(),
                envelope.definitionVersion(),
                envelope.definitionHash()
            );
            verifyReleasePayload(envelope, payload);
            actualPayloadHash = releasePayloadHash(payload);
        } else {
            throw new ApprovalArtifactTransferExceptions.InvalidFormat(
                "unsupported Approval artifact transfer format"
            );
        }
        requireHashMatch("payloadHash", envelope.payloadHash(), actualPayloadHash);
        String actualEnvelopeHash = envelopeHash(
            envelope.format(),
            envelope.artifactType(),
            envelope.definitionKey(),
            envelope.definitionVersion(),
            envelope.releaseVersion(),
            envelope.formPackageVersion(),
            envelope.definitionHash(),
            envelope.formPackageHash(),
            actualPayloadHash
        );
        requireHashMatch("envelopeHash", envelope.envelopeHash(), actualEnvelopeHash);
        return new VerifiedEnvelope(
            envelope.artifactType(),
            envelope.definitionKey(),
            envelope.definitionVersion(),
            envelope.releaseVersion(),
            envelope.formPackageVersion(),
            envelope.definitionHash(),
            envelope.formPackageHash(),
            actualPayloadHash,
            actualEnvelopeHash,
            sourceDefinition(envelope.payload())
        );
    }

    private ImportResult createImportedDraft(
        ImportCommand command,
        VerifiedEnvelope verified
    ) {
        if (definitions.find(
            command.context().tenantId(),
            command.targetDefinitionKey(),
            command.targetDefinitionVersion()
        ).isPresent()) {
            throw new ApprovalArtifactTransferExceptions.ImportConflict(
                "target Approval DSL version already exists as immutable content"
            );
        }
        ApprovalDefinition rewritten = ApprovalDesignChecks.copyDefinition(
            verified.sourceDefinition(),
            command.targetDefinitionKey(),
            command.targetDefinitionVersion(),
            command.targetName()
        );
        validateDefinitionLimits(rewritten);
        ApprovalFormPackageResolver.ExactFormPackage exact = resolveTargetFormPackage(command);
        var report = validator.validate(
            rewritten,
            exact.form().definition(),
            exact.uiSchema().definition()
        );
        if (!report.valid()) {
            String codes = report.errors().stream()
                .map(ApprovalDefinitionValidator.ValidationIssue::code)
                .distinct()
                .sorted()
                .limit(20)
                .reduce((left, right) -> left + "," + right)
                .orElse("UNKNOWN");
            throw new ApprovalArtifactTransferExceptions.ValidationFailed(
                "imported Approval DSL failed target validation: " + codes
            );
        }
        try {
            compiler.compile(rewritten, exact.form().definition());
        } catch (RuntimeException exception) {
            throw new ApprovalArtifactTransferExceptions.ValidationFailed(
                "imported Approval DSL could not compile against the target Form Package"
            );
        }
        Instant now = clock.instant();
        ApprovalDesignDraft draft = new ApprovalDesignDraft(
            identifiers.get(),
            command.context().tenantId(),
            command.targetDefinitionKey(),
            command.targetName(),
            rewritten,
            ApprovalDesignChecks.formReference(exact.formPackage()),
            null,
            1,
            ApprovalDesignDraft.Status.DRAFT,
            null,
            null,
            command.context().operatorId(),
            command.context().operatorId(),
            now,
            now
        );
        drafts.save(draft);
        appendImportAudit(command, verified, draft, now);
        return new ImportResult(
            draft.draftId(),
            draft.revision(),
            draft.status(),
            draft.definitionKey(),
            draft.definition().version(),
            draft.formPackage().packageVersion(),
            verified.payloadHash(),
            verified.envelopeHash()
        );
    }

    private ApprovalFormPackageResolver.ExactFormPackage resolveTargetFormPackage(
        ImportCommand command
    ) {
        try {
            return formResolver.resolve(
                command.context().tenantId(),
                command.targetDefinitionKey(),
                command.targetFormPackageVersion()
            );
        } catch (RuntimeException exception) {
            throw new ApprovalArtifactTransferExceptions.FormPackageIncompatible(
                "target tenant Form Package could not be resolved with exact immutable content",
                exception
            );
        }
    }

    private ApprovalFormPackageResolver.ExactFormPackage resolveFormPackage(
        String tenantId,
        String definitionKey,
        int formPackageVersion
    ) {
        try {
            return formResolver.resolve(tenantId, definitionKey, formPackageVersion);
        } catch (RuntimeException exception) {
            throw integrity("source Form Package integrity verification failed");
        }
    }

    private void appendImportAudit(
        ImportCommand command,
        VerifiedEnvelope verified,
        ApprovalDesignDraft draft,
        Instant occurredAt
    ) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("artifactType", verified.artifactType().name());
        attributes.put("formatVersion", Integer.toString(FORMAT_VERSION));
        attributes.put("sourceDefinitionKey", verified.sourceDefinitionKey());
        attributes.put(
            "sourceDefinitionVersion",
            Integer.toString(verified.sourceDefinitionVersion())
        );
        if (verified.sourceReleaseVersion() != null) {
            attributes.put(
                "sourceReleaseVersion",
                Integer.toString(verified.sourceReleaseVersion())
            );
        }
        attributes.put("sourcePayloadHash", verified.payloadHash());
        attributes.put("sourceEnvelopeHash", verified.envelopeHash());
        attributes.put("targetDefinitionKey", command.targetDefinitionKey());
        attributes.put(
            "targetDefinitionVersion",
            Integer.toString(command.targetDefinitionVersion())
        );
        attributes.put(
            "targetFormPackageVersion",
            Integer.toString(command.targetFormPackageVersion())
        );
        attributes.put("draftRevision", Long.toString(draft.revision()));
        auditEvents.append(new AuditEvent(
            identifiers.get(),
            command.context().tenantId(),
            command.context().operatorId(),
            "APPROVAL_DESIGN_DRAFT_IMPORTED",
            "APPROVAL_DESIGN_DRAFT",
            draft.draftId().toString(),
            command.context().requestId(),
            command.context().traceId(),
            occurredAt,
            Map.copyOf(attributes)
        ));
    }

    private void verifyDefinitionVersion(ApprovalDefinitionVersion source) {
        verifyDefinitionIdentity(
            source.definition(),
            source.definitionKey(),
            source.version(),
            source.contentHash()
        );
        validateDefinitionLimits(source.definition());
    }

    private void verifyDefinitionIdentity(
        ApprovalDefinition definition,
        String definitionKey,
        int definitionVersion,
        String declaredHash
    ) {
        Objects.requireNonNull(definition, "Approval DSL payload must not be null");
        validateDefinitionLimits(definition);
        if (!definitionKey.equals(definition.definitionKey())
            || definitionVersion != definition.version()) {
            throw integrity("Approval DSL identity does not match transfer metadata");
        }
        requireHashMatch(
            "definitionHash",
            declaredHash,
            definitionHasher.hash(definition)
        );
    }

    private void verifyReleasePackage(
        ApprovalReleasePackage release,
        ApprovalDefinition definition,
        ApprovalFormPackageResolver.ExactFormPackage exact
    ) {
        if (!release.definitionHash().equals(definitionHasher.hash(definition))) {
            throw integrity("Release Package definitionHash does not match Approval DSL content");
        }
        if (!release.formPackageHash().equals(exact.formPackage().packageHash())
            || release.formVersion() != exact.formPackage().formVersion()
            || !release.formHash().equals(exact.formPackage().formHash())
            || release.uiSchemaVersion() != exact.formPackage().uiSchemaVersion()
            || !release.uiSchemaHash().equals(exact.formPackage().uiSchemaHash())) {
            throw integrity("Release Package Form Package relationship is inconsistent");
        }
        TransferEnvelope envelope = new TransferEnvelope(
            RELEASE_FORMAT,
            FORMAT_VERSION,
            ArtifactType.APPROVAL_RELEASE_PACKAGE,
            clock.instant(),
            release.definitionKey(),
            release.definitionVersion(),
            release.releaseVersion(),
            release.formPackageVersion(),
            release.definitionHash(),
            release.formPackageHash(),
            releasePayload(release, definition),
            "0".repeat(64),
            "0".repeat(64)
        );
        verifyReleasePayload(envelope, (ReleasePackagePayload) envelope.payload());
    }

    private void verifyReleasePayload(
        TransferEnvelope envelope,
        ReleasePackagePayload payload
    ) {
        requireSafeResourceName(payload.bpmnResourceName());
        requireArtifactSize(payload.bpmnArtifact(), MAX_BPMN_BYTES, "BPMN");
        parseXml(payload.bpmnArtifact(), "BPMN");
        String processKey = processKey(payload.bpmnArtifact());
        if (!envelope.definitionKey().equals(processKey)) {
            throw integrity("BPMN process key does not match Approval DSL definitionKey");
        }
        if (payload.dmnArtifact() != null) {
            requireArtifactSize(payload.dmnArtifact(), MAX_DMN_BYTES, "DMN");
            parseXml(payload.dmnArtifact(), "DMN");
        }
        String actualBpmnHash = releaseHasher.artifactHash(payload.bpmnArtifact());
        requireHashMatch("bpmnHash", payload.bpmnHash(), actualBpmnHash);
        String actualDmnHash = payload.dmnArtifact() == null
            ? null
            : releaseHasher.artifactHash(payload.dmnArtifact());
        if ((payload.dmnArtifact() == null) != (payload.dmnHash() == null)) {
            throw integrity("DMN artifact and hash must both be present or absent");
        }
        if (actualDmnHash != null) {
            requireHashMatch("dmnHash", payload.dmnHash(), actualDmnHash);
        }
        String actualCompiledHash = compiledArtifactHash(
            payload.compilerVersion(),
            envelope.definitionKey(),
            envelope.definitionVersion(),
            envelope.definitionKey(),
            payload.formSchemaVersion(),
            payload.bpmnArtifact()
        );
        requireHashMatch(
            "compiledArtifactHash",
            payload.compiledArtifactHash(),
            actualCompiledHash
        );
        String actualMetadataHash = releaseHasher.deploymentMetadataHash(
            payload.compilerVersion(),
            payload.bpmnResourceName(),
            actualBpmnHash
        );
        requireHashMatch(
            "deploymentMetadataHash",
            payload.deploymentMetadataHash(),
            actualMetadataHash
        );
        String actualPackageHash = releaseHasher.hash(
            envelope.definitionKey(),
            Objects.requireNonNull(envelope.releaseVersion()),
            envelope.definitionVersion(),
            envelope.definitionHash(),
            envelope.formPackageVersion(),
            envelope.formPackageHash(),
            payload.formSchemaVersion(),
            payload.formSchemaHash(),
            payload.uiSchemaVersion(),
            payload.uiSchemaHash(),
            payload.compilerVersion(),
            payload.bpmnResourceName(),
            payload.bpmnArtifact(),
            actualCompiledHash,
            actualBpmnHash,
            payload.dmnArtifact(),
            actualDmnHash,
            actualMetadataHash
        );
        requireHashMatch(
            "releasePackageHash",
            payload.releasePackageHash(),
            actualPackageHash
        );
    }

    private TransferEnvelope envelope(
        String format,
        ArtifactType artifactType,
        String definitionKey,
        int definitionVersion,
        Integer releaseVersion,
        int formPackageVersion,
        String definitionHash,
        String formPackageHash,
        TransferPayload payload,
        String payloadHash
    ) {
        String contentHash = envelopeHash(
            format,
            artifactType,
            definitionKey,
            definitionVersion,
            releaseVersion,
            formPackageVersion,
            definitionHash,
            formPackageHash,
            payloadHash
        );
        return new TransferEnvelope(
            format,
            FORMAT_VERSION,
            artifactType,
            clock.instant(),
            definitionKey,
            definitionVersion,
            releaseVersion,
            formPackageVersion,
            definitionHash,
            formPackageHash,
            payload,
            payloadHash,
            contentHash
        );
    }

    private ReleasePackagePayload releasePayload(
        ApprovalReleasePackage release,
        ApprovalDefinition definition
    ) {
        return new ReleasePackagePayload(
            definition,
            release.compilerVersion(),
            release.bpmnResourceName(),
            release.bpmnArtifact(),
            release.bpmnHash(),
            release.dmnArtifact(),
            release.dmnHash(),
            release.compiledArtifactHash(),
            release.deploymentMetadataHash(),
            release.packageHash(),
            release.formVersion(),
            release.formHash(),
            release.uiSchemaVersion(),
            release.uiSchemaHash()
        );
    }

    private String definitionPayloadHash(
        String definitionHash,
        int formPackageVersion,
        String formPackageHash
    ) {
        return releaseHasher.hashValues(
            DSL_PAYLOAD_PROTOCOL,
            definitionHash,
            formPackageVersion,
            formPackageHash
        );
    }

    private String releasePayloadHash(ReleasePackagePayload payload) {
        return releaseHasher.hashValues(
            RELEASE_PAYLOAD_PROTOCOL,
            definitionHasher.hash(payload.definition()),
            payload.compilerVersion(),
            payload.bpmnResourceName(),
            payload.bpmnArtifact(),
            payload.bpmnHash(),
            payload.dmnArtifact(),
            payload.dmnHash(),
            payload.compiledArtifactHash(),
            payload.deploymentMetadataHash(),
            payload.releasePackageHash(),
            payload.formSchemaVersion(),
            payload.formSchemaHash(),
            payload.uiSchemaVersion(),
            payload.uiSchemaHash()
        );
    }

    private String envelopeHash(
        String format,
        ArtifactType artifactType,
        String definitionKey,
        int definitionVersion,
        Integer releaseVersion,
        int formPackageVersion,
        String definitionHash,
        String formPackageHash,
        String payloadHash
    ) {
        return releaseHasher.hashValues(
            ENVELOPE_PROTOCOL,
            format,
            FORMAT_VERSION,
            artifactType,
            definitionKey,
            definitionVersion,
            releaseVersion,
            formPackageVersion,
            definitionHash,
            formPackageHash,
            payloadHash
        );
    }

    private String compiledArtifactHash(
        String compilerVersion,
        String definitionKey,
        int definitionVersion,
        String formKey,
        int formVersion,
        String bpmnArtifact
    ) {
        return releaseHasher.artifactHash(String.join(
            "\n",
            compilerVersion,
            definitionKey,
            Integer.toString(definitionVersion),
            formKey,
            Integer.toString(formVersion),
            bpmnArtifact
        ));
    }

    private void validateDefinitionLimits(ApprovalDefinition definition) {
        Objects.requireNonNull(definition, "Approval DSL must not be null");
        requireSafeText(definition.schemaVersion(), "schemaVersion");
        requireSafeText(definition.definitionKey(), "definitionKey");
        requireSafeText(definition.name(), "name");
        requireSafeText(definition.startNodeId(), "startNodeId");
        if (definition.nodes().size() > MAX_NODES) {
            throw new ApprovalArtifactTransferExceptions.TooLarge(
                "Approval DSL exceeds the maximum node count"
            );
        }
        int conditionRoutes = 0;
        int parallelBranches = 0;
        for (ApprovalDefinition.ProcessNode node : definition.nodes()) {
            if (node == null) {
                throw invalid("Approval DSL must not contain null nodes");
            }
            requireSafeText(node.id(), "node.id");
            requireSafeText(node.name(), "node.name");
            if (node instanceof ApprovalDefinition.StartNode start) {
                requireSafeText(start.next(), "start.next");
            } else if (node instanceof ApprovalDefinition.ApprovalStep approval) {
                validateAssignee(approval.assignee());
                requireSafeText(approval.next(), "approval.next");
                requireOptionalSafeText(approval.rejectNext(), "approval.rejectNext");
            } else if (node instanceof ApprovalDefinition.HandleStep handle) {
                validateAssignee(handle.assignee());
                requireSafeText(handle.next(), "handle.next");
            } else if (node instanceof ApprovalDefinition.ConditionStep condition) {
                conditionRoutes += condition.routes().size();
                requireSafeText(condition.defaultNext(), "condition.defaultNext");
                for (ApprovalDefinition.ConditionRoute route : condition.routes()) {
                    requireSafeText(route.condition().field(), "condition.field");
                    requireSafeText(route.next(), "condition.next");
                }
            } else if (node instanceof ApprovalDefinition.ParallelSplitNode split) {
                parallelBranches += split.branches().size();
                requireSafeText(split.joinNodeId(), "parallel.joinNodeId");
                for (ApprovalDefinition.ParallelBranch branch : split.branches()) {
                    requireSafeText(branch.id(), "parallel.branch.id");
                    requireSafeText(branch.name(), "parallel.branch.name");
                    requireSafeText(branch.next(), "parallel.branch.next");
                }
            } else if (node instanceof ApprovalDefinition.ParallelJoinNode join) {
                requireSafeText(join.next(), "parallelJoin.next");
            }
        }
        if (conditionRoutes > MAX_CONDITION_ROUTES) {
            throw new ApprovalArtifactTransferExceptions.TooLarge(
                "Approval DSL exceeds the maximum condition route count"
            );
        }
        if (parallelBranches > MAX_PARALLEL_BRANCHES) {
            throw new ApprovalArtifactTransferExceptions.TooLarge(
                "Approval DSL exceeds the maximum parallel branch count"
            );
        }
    }

    private void validateAssignee(ApprovalDefinition.AssigneeRule assignee) {
        Objects.requireNonNull(assignee, "assignee must not be null");
        requireSafeText(assignee.variable(), "assignee.variable");
    }

    private void parseXml(String artifact, String artifactName) {
        try {
            DocumentBuilderFactory factory = secureXmlFactory();
            factory.newDocumentBuilder().parse(new InputSource(new StringReader(artifact)));
        } catch (Exception exception) {
            throw new ApprovalArtifactTransferExceptions.ArtifactIntegrityFailed(
                artifactName + " artifact could not be parsed safely"
            );
        }
    }

    private String processKey(String bpmn) {
        try {
            var document = secureXmlFactory().newDocumentBuilder().parse(
                new InputSource(new StringReader(bpmn))
            );
            var processes = document.getElementsByTagNameNS("*", "process");
            if (processes.getLength() != 1) {
                throw integrity("BPMN artifact must contain exactly one process");
            }
            var identifier = processes.item(0).getAttributes().getNamedItem("id");
            if (identifier == null || identifier.getNodeValue().isBlank()) {
                throw integrity("BPMN process id must not be blank");
            }
            return identifier.getNodeValue();
        } catch (ApprovalArtifactTransferExceptions.ArtifactIntegrityFailed exception) {
            throw exception;
        } catch (Exception exception) {
            throw integrity("BPMN artifact could not be parsed safely");
        }
    }

    private static DocumentBuilderFactory secureXmlFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        return factory;
    }

    private void requireArtifactSize(String artifact, int maximum, String artifactName) {
        Objects.requireNonNull(artifact, artifactName + " artifact must not be null");
        requireSafeUnicode(artifact, artifactName + " artifact");
        if (artifact.isBlank()) {
            throw invalid(artifactName + " artifact must not be blank");
        }
        if (artifact.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new ApprovalArtifactTransferExceptions.TooLarge(
                artifactName + " artifact exceeds the maximum byte size"
            );
        }
    }

    private void requireSafeResourceName(String resourceName) {
        requireSafeText(resourceName, "bpmnResourceName");
        if (resourceName.length() > 180
            || resourceName.contains("..")
            || !resourceName.matches("[A-Za-z0-9._-]+")
            || !resourceName.endsWith(".bpmn20.xml")) {
            throw invalid("BPMN resource name is unsafe");
        }
    }

    private void requireArtifactType(TransferEnvelope envelope, ArtifactType expected) {
        if (envelope.artifactType() != expected) {
            throw invalid("artifactType does not match transfer format");
        }
    }

    private DefinitionPayload requireDefinitionPayload(TransferPayload payload) {
        if (payload instanceof DefinitionPayload definitionPayload) {
            return definitionPayload;
        }
        throw invalid("DSL transfer payload type is invalid");
    }

    private ReleasePackagePayload requireReleasePayload(TransferPayload payload) {
        if (payload instanceof ReleasePackagePayload releasePayload) {
            return releasePayload;
        }
        throw invalid("Release Package transfer payload type is invalid");
    }

    private ApprovalDefinition sourceDefinition(TransferPayload payload) {
        if (payload instanceof DefinitionPayload definitionPayload) {
            return definitionPayload.definition();
        }
        return ((ReleasePackagePayload) payload).definition();
    }

    private void requireHashMatch(String name, String declared, String actual) {
        requireHash(declared, name);
        if (!declared.equals(actual)) {
            throw new ApprovalArtifactTransferExceptions.HashMismatch(
                name + " does not match server-recomputed content"
            );
        }
    }

    private static void requireHash(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid(name + " must be a lowercase SHA-256 value");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw invalid(name + " must be positive");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " must not be blank");
        }
        return value.trim();
    }

    private static String requireSafeText(String value, String name) {
        String normalized = requireText(value, name);
        if (normalized.length() > MAX_STRING_LENGTH) {
            throw new ApprovalArtifactTransferExceptions.TooLarge(
                name + " exceeds the maximum string length"
            );
        }
        requireSafeUnicode(normalized, name);
        return normalized;
    }

    private static void requireOptionalSafeText(String value, String name) {
        if (value != null) {
            requireSafeText(value, name);
        }
    }

    private static void requireSafeUnicode(String value, String name) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                    || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalid(name + " contains invalid Unicode");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw invalid(name + " contains invalid Unicode");
            }
        }
    }

    private static ApprovalArtifactTransferExceptions.InvalidFormat invalid(String message) {
        return new ApprovalArtifactTransferExceptions.InvalidFormat(message);
    }

    private static ApprovalArtifactTransferExceptions.ArtifactIntegrityFailed integrity(
        String message
    ) {
        return new ApprovalArtifactTransferExceptions.ArtifactIntegrityFailed(message);
    }

    public enum ArtifactType {
        APPROVAL_DSL,
        APPROVAL_RELEASE_PACKAGE
    }

    public sealed interface TransferPayload permits DefinitionPayload, ReleasePackagePayload {
    }

    public record DefinitionPayload(ApprovalDefinition definition) implements TransferPayload {
        public DefinitionPayload {
            definition = Objects.requireNonNull(definition, "definition must not be null");
        }
    }

    public record ReleasePackagePayload(
        ApprovalDefinition definition,
        String compilerVersion,
        String bpmnResourceName,
        String bpmnArtifact,
        String bpmnHash,
        String dmnArtifact,
        String dmnHash,
        String compiledArtifactHash,
        String deploymentMetadataHash,
        String releasePackageHash,
        int formSchemaVersion,
        String formSchemaHash,
        int uiSchemaVersion,
        String uiSchemaHash
    ) implements TransferPayload {
        public ReleasePackagePayload {
            definition = Objects.requireNonNull(definition, "definition must not be null");
            compilerVersion = requireSafeText(compilerVersion, "compilerVersion");
            bpmnResourceName = requireSafeText(bpmnResourceName, "bpmnResourceName");
            bpmnArtifact = Objects.requireNonNull(bpmnArtifact, "bpmnArtifact must not be null");
            dmnArtifact = normalizeArtifact(dmnArtifact);
            dmnHash = normalizeOptional(dmnHash);
            requireHash(bpmnHash, "bpmnHash");
            requireHash(compiledArtifactHash, "compiledArtifactHash");
            requireHash(deploymentMetadataHash, "deploymentMetadataHash");
            requireHash(releasePackageHash, "releasePackageHash");
            requirePositive(formSchemaVersion, "formSchemaVersion");
            requireHash(formSchemaHash, "formSchemaHash");
            requirePositive(uiSchemaVersion, "uiSchemaVersion");
            requireHash(uiSchemaHash, "uiSchemaHash");
            if ((dmnArtifact == null) != (dmnHash == null)) {
                throw invalid("DMN artifact and hash must both be present or absent");
            }
            if (dmnHash != null) {
                requireHash(dmnHash, "dmnHash");
            }
        }
    }

    public record TransferEnvelope(
        String format,
        int formatVersion,
        ArtifactType artifactType,
        Instant exportedAt,
        String definitionKey,
        int definitionVersion,
        Integer releaseVersion,
        int formPackageVersion,
        String definitionHash,
        String formPackageHash,
        TransferPayload payload,
        String payloadHash,
        String envelopeHash
    ) {
        public TransferEnvelope {
            format = requireText(format, "format");
            artifactType = Objects.requireNonNull(artifactType, "artifactType must not be null");
            exportedAt = Objects.requireNonNull(exportedAt, "exportedAt must not be null");
            definitionKey = requireSafeText(definitionKey, "definitionKey");
            payload = Objects.requireNonNull(payload, "payload must not be null");
        }
    }

    public record ImportCommand(
        RequestContext context,
        TransferEnvelope envelope,
        String targetDefinitionKey,
        int targetDefinitionVersion,
        int targetFormPackageVersion,
        String targetName
    ) {
        public ImportCommand {
            context = Objects.requireNonNull(context, "context must not be null");
            envelope = Objects.requireNonNull(envelope, "envelope must not be null");
            targetDefinitionKey = requireSafeText(
                targetDefinitionKey,
                "targetDefinitionKey"
            );
            requirePositive(targetDefinitionVersion, "targetDefinitionVersion");
            requirePositive(targetFormPackageVersion, "targetFormPackageVersion");
            targetName = requireSafeText(targetName, "targetName");
        }
    }

    public record ImportResult(
        UUID draftId,
        long revision,
        ApprovalDesignDraft.Status status,
        String definitionKey,
        int definitionVersion,
        int formPackageVersion,
        String sourcePayloadHash,
        String sourceEnvelopeHash
    ) {
        public ImportResult {
            draftId = Objects.requireNonNull(draftId, "draftId must not be null");
            status = Objects.requireNonNull(status, "status must not be null");
        }
    }

    public record VerifiedEnvelope(
        ArtifactType artifactType,
        String sourceDefinitionKey,
        int sourceDefinitionVersion,
        Integer sourceReleaseVersion,
        int sourceFormPackageVersion,
        String definitionHash,
        String formPackageHash,
        String payloadHash,
        String envelopeHash,
        ApprovalDefinition sourceDefinition
    ) {
    }

    private static String normalizeArtifact(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
