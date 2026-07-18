package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalCompiledArtifactStore;
import io.github.akaryc1b.approval.application.port.ApprovalDefinitionVersionStore;
import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore;
import io.github.akaryc1b.approval.application.port.ApprovalReleasePackageStore;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.compiler.ApprovalDslCompiler;
import io.github.akaryc1b.approval.domain.definition.ApprovalCompiledArtifact;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinitionVersion;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.definition.ApprovalReleasePackage;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

final class ApprovalReleasePublisher {

    private final ApprovalDesignDraftStore drafts;
    private final ApprovalDefinitionVersionStore definitions;
    private final ApprovalCompiledArtifactStore artifacts;
    private final ApprovalReleasePackageStore releases;
    private final ApprovalFormPackageResolver resolver;
    private final ApprovalDesignAuditor auditor;
    private final ApprovalDefinitionValidator validator;
    private final ApprovalDslCompiler compiler;
    private final ApprovalDefinitionHasher definitionHasher;
    private final ApprovalReleasePackageHasher releaseHasher;
    private final Clock clock;

    ApprovalReleasePublisher(
        ApprovalDesignDraftStore drafts,
        ApprovalDefinitionVersionStore definitions,
        ApprovalCompiledArtifactStore artifacts,
        ApprovalReleasePackageStore releases,
        ApprovalFormPackageResolver resolver,
        ApprovalDesignAuditor auditor,
        ApprovalDefinitionValidator validator,
        ApprovalDslCompiler compiler,
        ApprovalDefinitionHasher definitionHasher,
        ApprovalReleasePackageHasher releaseHasher,
        Clock clock
    ) {
        this.drafts = Objects.requireNonNull(drafts);
        this.definitions = Objects.requireNonNull(definitions);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.releases = Objects.requireNonNull(releases);
        this.resolver = Objects.requireNonNull(resolver);
        this.auditor = Objects.requireNonNull(auditor);
        this.validator = Objects.requireNonNull(validator);
        this.compiler = Objects.requireNonNull(compiler);
        this.definitionHasher = Objects.requireNonNull(definitionHasher);
        this.releaseHasher = Objects.requireNonNull(releaseHasher);
        this.clock = Objects.requireNonNull(clock);
    }

    ApprovalDesignResults.Publish publish(ApprovalDesignCommands.Publish command) {
        drafts.lock(command.context().tenantId(), command.draftId());
        ApprovalDesignDraft current = ApprovalDesignChecks.requireDraft(
            drafts,
            command.context().tenantId(),
            command.draftId()
        );
        if (current.status() == ApprovalDesignDraft.Status.PUBLISHED) {
            return replay(command, current);
        }
        ApprovalDesignChecks.requireEditable(current);
        ApprovalDesignChecks.requireRevision(current, command.expectedRevision());
        if (current.definition().version() != command.definitionVersion()) {
            throw new ApprovalDesignExceptions.DefinitionVersionConflict(
                "requested Approval DSL version does not match the draft"
            );
        }

        var exact = resolver.resolve(current);
        var validation = validator.validate(
            current.definition(),
            exact.form().definition(),
            exact.uiSchema().definition()
        );
        if (!validation.valid()) {
            throw new ApprovalDefinitionValidator.DefinitionValidationException(validation);
        }

        String definitionHash = definitionHasher.hash(current.definition());
        ApprovalDslCompiler.CompiledDefinition compiled = compiler.compile(
            current.definition(),
            exact.form().definition()
        );
        String bpmnHash = releaseHasher.artifactHash(compiled.bpmnXml());
        String metadataHash = releaseHasher.deploymentMetadataHash(
            compiled.compilerVersion(),
            compiled.resourceName(),
            bpmnHash
        );
        String packageHash = releaseHasher.hash(
            current.definitionKey(),
            command.releaseVersion(),
            command.definitionVersion(),
            definitionHash,
            exact.formPackage().packageVersion(),
            exact.formPackage().packageHash(),
            exact.formPackage().formVersion(),
            exact.formPackage().formHash(),
            exact.formPackage().uiSchemaVersion(),
            exact.formPackage().uiSchemaHash(),
            compiled.compilerVersion(),
            compiled.resourceName(),
            compiled.bpmnXml(),
            compiled.contentHash(),
            bpmnHash,
            null,
            null,
            metadataHash
        );
        Instant now = clock.instant();

        ApprovalDefinitionVersion definition = new ApprovalDefinitionVersion(
            current.tenantId(),
            current.definitionKey(),
            command.definitionVersion(),
            definitionHash,
            exact.formPackage().packageVersion(),
            exact.formPackage().packageHash(),
            current.definition(),
            current.draftId(),
            command.context().operatorId(),
            now
        );
        saveDefinition(definition);

        ApprovalCompiledArtifact artifact = new ApprovalCompiledArtifact(
            current.tenantId(),
            current.definitionKey(),
            command.definitionVersion(),
            definitionHash,
            exact.formPackage().formVersion(),
            exact.formPackage().formHash(),
            compiled.compilerVersion(),
            compiled.resourceName(),
            compiled.bpmnXml(),
            compiled.contentHash(),
            bpmnHash,
            now
        );
        saveArtifact(artifact);

        ApprovalReleasePackage release = new ApprovalReleasePackage(
            current.tenantId(),
            current.definitionKey(),
            command.releaseVersion(),
            command.definitionVersion(),
            definitionHash,
            exact.formPackage().packageVersion(),
            exact.formPackage().packageHash(),
            exact.formPackage().formVersion(),
            exact.formPackage().formHash(),
            exact.formPackage().uiSchemaVersion(),
            exact.formPackage().uiSchemaHash(),
            compiled.compilerVersion(),
            compiled.resourceName(),
            compiled.bpmnXml(),
            compiled.contentHash(),
            bpmnHash,
            null,
            null,
            metadataHash,
            packageHash,
            current.draftId(),
            command.context().operatorId(),
            now
        );
        saveRelease(release);

        ApprovalDesignDraft published = ApprovalDesignChecks.copyState(
            current,
            current.revision() + 1,
            ApprovalDesignDraft.Status.PUBLISHED,
            command.definitionVersion(),
            command.releaseVersion(),
            command.context().operatorId(),
            now
        );
        if (!drafts.update(published, command.expectedRevision())) {
            throw new ApprovalDesignExceptions.DraftRevisionConflict(
                "Approval DSL draft revision is stale"
            );
        }
        auditor.append(
            command.context(),
            "APPROVAL_RELEASE_PACKAGE_PUBLISHED",
            published,
            now,
            ApprovalDesignAuditor.releaseAttributes(release, published.revision())
        );
        return new ApprovalDesignResults.Publish(release, published.revision(), false);
    }

    private ApprovalDesignResults.Publish replay(
        ApprovalDesignCommands.Publish command,
        ApprovalDesignDraft current
    ) {
        ApprovalReleasePackage existing = releases.findByDraft(
            command.context().tenantId(),
            current.draftId()
        ).orElseThrow(() -> new IllegalStateException(
            "published Approval DSL draft has no immutable Release Package"
        ));
        if (existing.definitionVersion() != command.definitionVersion()) {
            throw new ApprovalDesignExceptions.DefinitionVersionConflict(
                "published draft is bound to a different Approval DSL version"
            );
        }
        if (existing.releaseVersion() != command.releaseVersion()) {
            throw new ApprovalDesignExceptions.ReleaseVersionConflict(
                "published draft is bound to a different Release Package version"
            );
        }
        return new ApprovalDesignResults.Publish(existing, current.revision(), true);
    }

    private void saveDefinition(ApprovalDefinitionVersion candidate) {
        definitions.lockVersion(
            candidate.tenantId(),
            candidate.definitionKey(),
            candidate.version()
        );
        Optional<ApprovalDefinitionVersion> existing = definitions.find(
            candidate.tenantId(),
            candidate.definitionKey(),
            candidate.version()
        );
        if (existing.isEmpty()) {
            definitions.save(candidate);
            return;
        }
        ApprovalDefinitionVersion value = existing.get();
        if (!value.contentHash().equals(candidate.contentHash())
            || value.formPackageVersion() != candidate.formPackageVersion()
            || !value.formPackageHash().equals(candidate.formPackageHash())
            || !value.sourceDraftId().equals(candidate.sourceDraftId())) {
            throw new ApprovalDesignExceptions.DefinitionVersionConflict(
                "Approval DSL version already has different content or source"
            );
        }
    }

    private void saveArtifact(ApprovalCompiledArtifact candidate) {
        artifacts.lockArtifact(
            candidate.tenantId(),
            candidate.definitionKey(),
            candidate.definitionVersion(),
            candidate.compilerVersion()
        );
        Optional<ApprovalCompiledArtifact> existing = artifacts.find(
            candidate.tenantId(),
            candidate.definitionKey(),
            candidate.definitionVersion(),
            candidate.compilerVersion()
        );
        if (existing.isEmpty()) {
            artifacts.save(candidate);
            return;
        }
        ApprovalCompiledArtifact value = existing.get();
        if (!value.definitionHash().equals(candidate.definitionHash())
            || !value.formHash().equals(candidate.formHash())
            || !value.bpmnHash().equals(candidate.bpmnHash())
            || !value.bpmnXml().equals(candidate.bpmnXml())) {
            throw new ApprovalDesignExceptions.CompiledArtifactConflict(
                "compiled artifact identity already has different content"
            );
        }
    }

    private void saveRelease(ApprovalReleasePackage candidate) {
        releases.lockVersion(
            candidate.tenantId(),
            candidate.definitionKey(),
            candidate.releaseVersion()
        );
        Optional<ApprovalReleasePackage> existing = releases.find(
            candidate.tenantId(),
            candidate.definitionKey(),
            candidate.releaseVersion()
        );
        if (existing.isEmpty()) {
            releases.save(candidate);
            return;
        }
        ApprovalReleasePackage value = existing.get();
        if (!value.packageHash().equals(candidate.packageHash())
            || !value.sourceDraftId().equals(candidate.sourceDraftId())) {
            throw new ApprovalDesignExceptions.ReleaseVersionConflict(
                "Release Package version already has different content or source"
            );
        }
    }
}
