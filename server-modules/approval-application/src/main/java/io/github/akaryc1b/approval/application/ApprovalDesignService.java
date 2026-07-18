package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalCompiledArtifactStore;
import io.github.akaryc1b.approval.application.port.ApprovalDefinitionVersionStore;
import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.application.port.IdempotencyGuard;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionSimulator;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.context.RequestContext;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinitionVersion;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Product lifecycle facade for mutable Approval DSL drafts and immutable releases. */
public final class ApprovalDesignService {

    private static final String CREATE = "approval-design.create.v1";
    private static final String COPY = "approval-design.copy.v1";
    private static final String UPDATE = "approval-design.update.v1";
    private static final String VALIDATE = "approval-design.validate.v1";
    private static final String ARCHIVE = "approval-design.archive.v1";
    private static final String PUBLISH = "approval-release.publish.v1";

    private final IdempotencyGuard idempotency;
    private final ApprovalDesignDraftStore drafts;
    private final ApprovalDefinitionVersionStore definitions;
    private final ApprovalReleasePackageStore releases;
    private final ApprovalDefinitionHasher definitionHasher;
    private final ApprovalReleasePackageHasher releaseHasher;
    private final ApprovalDraftLifecycle lifecycle;
    private final ApprovalDraftSimulation simulation;
    private final ApprovalReleasePublisher publisher;

    public ApprovalDesignService(
        IdempotencyGuard idempotency,
        ApprovalDesignDraftStore drafts,
        ApprovalDefinitionVersionStore definitions,
        ApprovalCompiledArtifactStore artifacts,
        ApprovalReleasePackageStore releases,
        ApprovalFormPackageStore formPackages,
        ApprovalFormStore forms,
        ApprovalUiSchemaStore uiSchemas,
        AuditEventSink auditEvents,
        ApprovalDefinitionValidator validator,
        ApprovalDefinitionSimulator simulator,
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
        this.definitionHasher = Objects.requireNonNull(definitionHasher);
        this.releaseHasher = Objects.requireNonNull(releaseHasher);
        ApprovalFormPackageResolver resolver = new ApprovalFormPackageResolver(
            formPackages,
            forms,
            uiSchemas
        );
        ApprovalDesignAuditor auditor = new ApprovalDesignAuditor(auditEvents, identifiers);
        this.lifecycle = new ApprovalDraftLifecycle(
            drafts,
            definitions,
            resolver,
            auditor,
            validator,
            definitionHasher,
            clock,
            identifiers
        );
        this.simulation = new ApprovalDraftSimulation(
            drafts,
            resolver,
            validator,
            simulator
        );
        this.publisher = new ApprovalReleasePublisher(
            drafts,
            definitions,
            artifacts,
            releases,
            resolver,
            auditor,
            validator,
            compiler,
            definitionHasher,
            releaseHasher,
            clock
        );
    }

    public ApprovalDesignDraft createBlank(ApprovalDesignCommands.Create command) {
        Objects.requireNonNull(command);
        return execute(
            command.context(),
            CREATE,
            ApprovalDesignDraft.class,
            () -> lifecycle.createBlank(command),
            "blank",
            command.definitionKey(),
            command.name(),
            command.definitionVersion(),
            command.formPackageVersion()
        );
    }

    public ApprovalDesignDraft createFromPurchasePaymentTemplate(
        ApprovalDesignCommands.Create command
    ) {
        Objects.requireNonNull(command);
        return execute(
            command.context(),
            CREATE,
            ApprovalDesignDraft.class,
            () -> lifecycle.createFromTemplate(command),
            "purchase-payment-template",
            command.definitionKey(),
            command.name(),
            command.definitionVersion(),
            command.formPackageVersion()
        );
    }

    public ApprovalDesignDraft createFromPublished(
        ApprovalDesignCommands.CopyPublished command
    ) {
        Objects.requireNonNull(command);
        return execute(
            command.context(),
            COPY,
            ApprovalDesignDraft.class,
            () -> lifecycle.createFromPublished(command),
            command.definitionKey(),
            command.sourceDefinitionVersion(),
            command.targetDefinitionVersion(),
            command.formPackageVersion(),
            command.name()
        );
    }

    public Optional<ApprovalDesignDraft> find(String tenantId, UUID draftId) {
        return drafts.find(tenantId, draftId);
    }

    public ApprovalDesignDraftStore.DraftPage findDrafts(
        String tenantId,
        String keyword,
        ApprovalDesignDraft.Status status,
        int limit,
        int offset
    ) {
        return drafts.findDrafts(new ApprovalDesignDraftStore.DraftCriteria(
            tenantId,
            keyword,
            status,
            limit,
            offset
        ));
    }

    public ApprovalDesignDraft update(ApprovalDesignCommands.Update command) {
        Objects.requireNonNull(command);
        return execute(
            command.context(),
            UPDATE,
            ApprovalDesignDraft.class,
            () -> lifecycle.update(command),
            command.draftId(),
            command.expectedRevision(),
            command.name(),
            command.formPackageVersion(),
            command.saveMode(),
            definitionHasher.hash(command.definition())
        );
    }

    public ApprovalDesignResults.Validation validate(
        ApprovalDesignCommands.Revision command
    ) {
        Objects.requireNonNull(command);
        ApprovalDesignDraft draft = ApprovalDesignChecks.requireDraft(
            drafts,
            command.context().tenantId(),
            command.draftId()
        );
        return execute(
            command.context(),
            VALIDATE,
            ApprovalDesignResults.Validation.class,
            () -> lifecycle.validate(command),
            command.draftId(),
            command.expectedRevision(),
            definitionHasher.hash(draft.definition()),
            draft.formPackage().packageHash()
        );
    }

    public ApprovalDesignDraft archive(ApprovalDesignCommands.Revision command) {
        Objects.requireNonNull(command);
        return execute(
            command.context(),
            ARCHIVE,
            ApprovalDesignDraft.class,
            () -> lifecycle.archive(command),
            command.draftId(),
            command.expectedRevision()
        );
    }

    public ApprovalDesignResults.Simulation simulate(
        ApprovalDesignCommands.Simulation command
    ) {
        return simulation.simulate(Objects.requireNonNull(command));
    }

    public ApprovalDesignResults.Publish publish(ApprovalDesignCommands.Publish command) {
        Objects.requireNonNull(command);
        ApprovalDesignDraft draft = ApprovalDesignChecks.requireDraft(
            drafts,
            command.context().tenantId(),
            command.draftId()
        );
        return execute(
            command.context(),
            PUBLISH,
            ApprovalDesignResults.Publish.class,
            () -> publisher.publish(command),
            command.draftId(),
            command.expectedRevision(),
            command.definitionVersion(),
            command.releaseVersion(),
            definitionHasher.hash(draft.definition()),
            draft.formPackage().packageHash()
        );
    }

    public Optional<ApprovalDefinitionVersion> findDefinition(
        String tenantId,
        String definitionKey,
        int version
    ) {
        return definitions.find(tenantId, definitionKey, version);
    }

    public Optional<ApprovalDefinitionVersion> findLatestDefinition(
        String tenantId,
        String definitionKey
    ) {
        return definitions.findLatest(tenantId, definitionKey);
    }

    public ApprovalDefinitionVersionStore.VersionPage findDefinitions(
        String tenantId,
        String definitionKey,
        int limit,
        int offset
    ) {
        return definitions.findVersions(new ApprovalDefinitionVersionStore.VersionCriteria(
            tenantId,
            definitionKey,
            limit,
            offset
        ));
    }

    public Optional<ApprovalReleasePackage> findRelease(
        String tenantId,
        String definitionKey,
        int releaseVersion
    ) {
        return releases.find(tenantId, definitionKey, releaseVersion);
    }

    public Optional<ApprovalReleasePackage> findLatestRelease(
        String tenantId,
        String definitionKey
    ) {
        return releases.findLatest(tenantId, definitionKey);
    }

    public ApprovalReleasePackageStore.ReleasePage findReleases(
        String tenantId,
        String definitionKey,
        int limit,
        int offset
    ) {
        return releases.findReleases(new ApprovalReleasePackageStore.ReleaseCriteria(
            tenantId,
            definitionKey,
            limit,
            offset
        ));
    }

    private <T> T execute(
        RequestContext context,
        String operation,
        Class<T> type,
        Supplier<T> action,
        Object... values
    ) {
        return idempotency.execute(
            context,
            operation,
            releaseHasher.hashValues(values),
            type,
            action
        );
    }
}
