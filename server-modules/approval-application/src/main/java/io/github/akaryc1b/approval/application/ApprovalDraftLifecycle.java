package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalDefinitionVersionStore;
import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore;
import io.github.akaryc1b.approval.compiler.ApprovalDefinitionValidator;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinitionVersion;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.template.PurchasePaymentTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

final class ApprovalDraftLifecycle {

    private final ApprovalDesignDraftStore drafts;
    private final ApprovalDefinitionVersionStore definitions;
    private final ApprovalFormPackageResolver resolver;
    private final ApprovalDesignAuditor auditor;
    private final ApprovalDefinitionValidator validator;
    private final ApprovalDefinitionHasher hasher;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    ApprovalDraftLifecycle(
        ApprovalDesignDraftStore drafts,
        ApprovalDefinitionVersionStore definitions,
        ApprovalFormPackageResolver resolver,
        ApprovalDesignAuditor auditor,
        ApprovalDefinitionValidator validator,
        ApprovalDefinitionHasher hasher,
        Clock clock,
        Supplier<UUID> identifiers
    ) {
        this.drafts = Objects.requireNonNull(drafts);
        this.definitions = Objects.requireNonNull(definitions);
        this.resolver = Objects.requireNonNull(resolver);
        this.auditor = Objects.requireNonNull(auditor);
        this.validator = Objects.requireNonNull(validator);
        this.hasher = Objects.requireNonNull(hasher);
        this.clock = Objects.requireNonNull(clock);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    ApprovalDesignDraft createBlank(ApprovalDesignCommands.Create command) {
        return create(
            command,
            ApprovalDesignChecks.blank(command),
            null,
            "APPROVAL_DESIGN_DRAFT_CREATED"
        );
    }

    ApprovalDesignDraft createFromTemplate(ApprovalDesignCommands.Create command) {
        ApprovalDefinition definition = ApprovalDesignChecks.copyDefinition(
            PurchasePaymentTemplate.processDefinition(),
            command.definitionKey(),
            command.definitionVersion(),
            command.name()
        );
        return create(
            command,
            definition,
            null,
            "APPROVAL_DESIGN_DRAFT_CREATED_FROM_TEMPLATE"
        );
    }

    ApprovalDesignDraft createFromPublished(
        ApprovalDesignCommands.CopyPublished command
    ) {
        ApprovalDefinitionVersion source = definitions.find(
            command.context().tenantId(),
            command.definitionKey(),
            command.sourceDefinitionVersion()
        ).orElseThrow(() -> new ApprovalDesignExceptions.PublishedDefinitionNotFound(
            "source Approval DSL version was not found"
        ));
        ApprovalDefinition definition = ApprovalDesignChecks.copyDefinition(
            source.definition(),
            command.definitionKey(),
            command.targetDefinitionVersion(),
            command.name()
        );
        return create(
            new ApprovalDesignCommands.Create(
                command.context(),
                command.definitionKey(),
                command.name(),
                command.targetDefinitionVersion(),
                command.formPackageVersion()
            ),
            definition,
            command.sourceDefinitionVersion(),
            "APPROVAL_DESIGN_DRAFT_COPIED"
        );
    }

    ApprovalDesignDraft update(ApprovalDesignCommands.Update command) {
        ApprovalDesignDraft current = ApprovalDesignChecks.requireDraft(
            drafts,
            command.context().tenantId(),
            command.draftId()
        );
        ApprovalDesignChecks.requireEditable(current);
        ApprovalDesignChecks.requireRevision(current, command.expectedRevision());
        if (!current.definitionKey().equals(command.definition().definitionKey())) {
            throw new ApprovalDesignExceptions.DraftStateConflict(
                "draft definitionKey cannot be changed"
            );
        }
        if (current.definition().version() != command.definition().version()) {
            throw new ApprovalDesignExceptions.DraftStateConflict(
                "draft definition version cannot be changed"
            );
        }
        var exact = resolver.resolve(
            current.tenantId(),
            current.definitionKey(),
            command.formPackageVersion()
        );
        Instant now = clock.instant();
        ApprovalDesignDraft updated = new ApprovalDesignDraft(
            current.draftId(),
            current.tenantId(),
            current.definitionKey(),
            command.name(),
            command.definition(),
            ApprovalDesignChecks.formReference(exact.formPackage()),
            current.sourceDefinitionVersion(),
            current.revision() + 1,
            ApprovalDesignDraft.Status.DRAFT,
            null,
            null,
            current.createdBy(),
            command.context().operatorId(),
            current.createdAt(),
            now
        );
        updateCas(updated, command.expectedRevision());
        if (command.saveMode() == ApprovalDesignCommands.SaveMode.EXPLICIT) {
            auditor.append(
                command.context(),
                "APPROVAL_DESIGN_DRAFT_SAVED",
                updated,
                now,
                Map.of("revision", Long.toString(updated.revision()))
            );
        }
        return updated;
    }

    ApprovalDesignResults.Validation validate(ApprovalDesignCommands.Revision command) {
        drafts.lock(command.context().tenantId(), command.draftId());
        ApprovalDesignDraft current = ApprovalDesignChecks.requireDraft(
            drafts,
            command.context().tenantId(),
            command.draftId()
        );
        ApprovalDesignChecks.requireEditable(current);
        ApprovalDesignChecks.requireRevision(current, command.expectedRevision());
        var exact = resolver.resolve(current);
        var report = validator.validate(
            current.definition(),
            exact.form().definition(),
            exact.uiSchema().definition()
        );
        String definitionHash = hasher.hash(current.definition());
        if (!report.valid()) {
            return new ApprovalDesignResults.Validation(
                current.draftId(),
                current.revision(),
                current.status(),
                definitionHash,
                report.issues()
            );
        }
        Instant now = clock.instant();
        ApprovalDesignDraft validated = ApprovalDesignChecks.copyState(
            current,
            current.revision() + 1,
            ApprovalDesignDraft.Status.VALIDATED,
            null,
            null,
            command.context().operatorId(),
            now
        );
        updateCas(validated, command.expectedRevision());
        auditor.append(
            command.context(),
            "APPROVAL_DESIGN_DRAFT_VALIDATED",
            validated,
            now,
            Map.of(
                "revision", Long.toString(validated.revision()),
                "definitionHash", definitionHash,
                "warningCount", Integer.toString(report.warnings().size())
            )
        );
        return new ApprovalDesignResults.Validation(
            validated.draftId(),
            validated.revision(),
            validated.status(),
            definitionHash,
            report.issues()
        );
    }

    ApprovalDesignDraft archive(ApprovalDesignCommands.Revision command) {
        drafts.lock(command.context().tenantId(), command.draftId());
        ApprovalDesignDraft current = ApprovalDesignChecks.requireDraft(
            drafts,
            command.context().tenantId(),
            command.draftId()
        );
        ApprovalDesignChecks.requireEditable(current);
        ApprovalDesignChecks.requireRevision(current, command.expectedRevision());
        Instant now = clock.instant();
        ApprovalDesignDraft archived = ApprovalDesignChecks.copyState(
            current,
            current.revision() + 1,
            ApprovalDesignDraft.Status.ARCHIVED,
            null,
            null,
            command.context().operatorId(),
            now
        );
        updateCas(archived, command.expectedRevision());
        auditor.append(
            command.context(),
            "APPROVAL_DESIGN_DRAFT_ARCHIVED",
            archived,
            now,
            Map.of("revision", Long.toString(archived.revision()))
        );
        return archived;
    }

    private ApprovalDesignDraft create(
        ApprovalDesignCommands.Create command,
        ApprovalDefinition definition,
        Integer sourceVersion,
        String action
    ) {
        var exact = resolver.resolve(
            command.context().tenantId(),
            command.definitionKey(),
            command.formPackageVersion()
        );
        Instant now = clock.instant();
        ApprovalDesignDraft draft = new ApprovalDesignDraft(
            identifiers.get(),
            command.context().tenantId(),
            command.definitionKey(),
            command.name(),
            definition,
            ApprovalDesignChecks.formReference(exact.formPackage()),
            sourceVersion,
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
        auditor.append(
            command.context(),
            action,
            draft,
            now,
            Map.of(
                "revision", "1",
                "formPackageVersion",
                Integer.toString(command.formPackageVersion())
            )
        );
        return draft;
    }

    private void updateCas(ApprovalDesignDraft draft, long expectedRevision) {
        if (!drafts.update(draft, expectedRevision)) {
            throw new ApprovalDesignExceptions.DraftRevisionConflict(
                "Approval DSL draft revision is stale"
            );
        }
    }
}
