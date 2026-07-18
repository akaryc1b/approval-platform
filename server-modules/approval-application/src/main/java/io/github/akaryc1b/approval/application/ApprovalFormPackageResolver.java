package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore.PublishedForm;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore.PublishedUiSchema;
import io.github.akaryc1b.approval.domain.definition.ApprovalDesignDraft;
import io.github.akaryc1b.approval.domain.form.FormPackage;

import java.util.Objects;

final class ApprovalFormPackageResolver {

    private final ApprovalFormPackageStore packages;
    private final ApprovalFormStore forms;
    private final ApprovalUiSchemaStore uiSchemas;

    ApprovalFormPackageResolver(
        ApprovalFormPackageStore packages,
        ApprovalFormStore forms,
        ApprovalUiSchemaStore uiSchemas
    ) {
        this.packages = Objects.requireNonNull(packages);
        this.forms = Objects.requireNonNull(forms);
        this.uiSchemas = Objects.requireNonNull(uiSchemas);
    }

    ExactFormPackage resolve(ApprovalDesignDraft draft) {
        ExactFormPackage exact = resolve(
            draft.tenantId(),
            draft.definitionKey(),
            draft.formPackage().packageVersion()
        );
        if (!exact.formPackage().packageHash().equals(draft.formPackage().packageHash())) {
            throw new ApprovalDesignExceptions.DraftStateConflict(
                "draft Form Package hash does not match its immutable version"
            );
        }
        return exact;
    }

    ExactFormPackage resolve(String tenantId, String definitionKey, int packageVersion) {
        FormPackage formPackage = packages.find(tenantId, definitionKey, packageVersion)
            .orElseThrow(() -> new ApprovalDesignExceptions.FormPackageNotFound(
                "exact Form Package was not found"
            ));
        PublishedForm form = forms.find(
            tenantId,
            definitionKey,
            formPackage.formVersion()
        ).orElseThrow(() -> new ApprovalDesignExceptions.FormPackageNotFound(
            "Form Package Form Schema was not found"
        ));
        PublishedUiSchema uiSchema = uiSchemas.find(
            tenantId,
            definitionKey,
            formPackage.formVersion(),
            formPackage.uiSchemaVersion()
        ).orElseThrow(() -> new ApprovalDesignExceptions.FormPackageNotFound(
            "Form Package UI Schema was not found"
        ));
        if (!formPackage.formHash().equals(form.contentHash())
            || !formPackage.uiSchemaHash().equals(uiSchema.contentHash())) {
            throw new ApprovalDesignExceptions.FormPackageIntegrity(
                "Form Package hashes do not match immutable schema content"
            );
        }
        return new ExactFormPackage(formPackage, form, uiSchema);
    }

    record ExactFormPackage(
        FormPackage formPackage,
        PublishedForm form,
        PublishedUiSchema uiSchema
    ) {
    }
}
