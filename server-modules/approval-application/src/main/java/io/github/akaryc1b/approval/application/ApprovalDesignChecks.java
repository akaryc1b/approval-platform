package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalDesignDraftStore;
import io.github.akaryc1b.approval.domain.definition.ApprovalDefinition;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.form.FormPackage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class ApprovalDesignChecks {

    private ApprovalDesignChecks() {
    }

    static ApprovalDesignDraft requireDraft(
        ApprovalDesignDraftStore drafts,
        String tenantId,
        UUID draftId
    ) {
        return drafts.find(tenantId, draftId).orElseThrow(() ->
            new ApprovalDesignExceptions.DraftNotFound(
                "Approval DSL draft was not found"
            )
        );
    }

    static void requireEditable(ApprovalDesignDraft draft) {
        if (!draft.editable()) {
            throw new ApprovalDesignExceptions.DraftStateConflict(
                "Approval DSL draft is not editable in status " + draft.status()
            );
        }
    }

    static void requireRevision(ApprovalDesignDraft draft, long expectedRevision) {
        if (draft.revision() != expectedRevision) {
            throw new ApprovalDesignExceptions.DraftRevisionConflict(
                "expected revision " + expectedRevision + " but found " + draft.revision()
            );
        }
    }

    static ApprovalDesignDraft.FormPackageReference formReference(FormPackage formPackage) {
        return new ApprovalDesignDraft.FormPackageReference(
            formPackage.formKey(),
            formPackage.packageVersion(),
            formPackage.packageHash()
        );
    }

    static ApprovalDefinition blank(ApprovalDesignCommands.Create command) {
        return new ApprovalDefinition(
            ApprovalDefinition.CURRENT_SCHEMA_VERSION,
            command.definitionKey(),
            command.definitionVersion(),
            command.name(),
            "start",
            List.of(
                new ApprovalDefinition.StartNode("start", "Start", "end"),
                new ApprovalDefinition.EndNode("end", "End")
            )
        );
    }

    static ApprovalDefinition copyDefinition(
        ApprovalDefinition source,
        String definitionKey,
        int version,
        String name
    ) {
        return new ApprovalDefinition(
            source.schemaVersion(),
            definitionKey,
            version,
            name,
            source.startNodeId(),
            source.nodes()
        );
    }

    static ApprovalDesignDraft copyState(
        ApprovalDesignDraft current,
        long revision,
        ApprovalDesignDraft.Status status,
        Integer publishedDefinitionVersion,
        Integer publishedReleaseVersion,
        String updatedBy,
        Instant updatedAt
    ) {
        return new ApprovalDesignDraft(
            current.draftId(),
            current.tenantId(),
            current.definitionKey(),
            current.name(),
            current.definition(),
            current.formPackage(),
            current.sourceDefinitionVersion(),
            revision,
            status,
            publishedDefinitionVersion,
            publishedReleaseVersion,
            current.createdBy(),
            updatedBy,
            current.createdAt(),
            updatedAt
        );
    }
}
