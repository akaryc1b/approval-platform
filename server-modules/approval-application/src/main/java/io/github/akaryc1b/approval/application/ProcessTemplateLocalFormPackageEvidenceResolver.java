package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalFormPackageStore;
import io.github.akaryc1b.approval.application.port.ApprovalFormStore;
import io.github.akaryc1b.approval.application.port.ApprovalUiSchemaStore;
import io.github.akaryc1b.approval.application.port.ProcessTemplateFormPackageEvidenceResolver;
import io.github.akaryc1b.approval.application.port.ProcessTemplateFormPackageEvidenceResolver.FormPackageEvidence;
import io.github.akaryc1b.approval.application.port.ProcessTemplateFormPackageEvidenceResolver.FormPackageEvidenceRequest;
import io.github.akaryc1b.approval.domain.form.FormPackage;

import java.util.Objects;

/** Resolves evidence only from existing tenant-local immutable Form Package stores. */
public final class ProcessTemplateLocalFormPackageEvidenceResolver
    implements ProcessTemplateFormPackageEvidenceResolver {

    private final ApprovalFormPackageResolver resolver;
    private final ProcessTemplateFormPackageEvidenceHasher hasher;

    public ProcessTemplateLocalFormPackageEvidenceResolver(
        ApprovalFormPackageStore packages,
        ApprovalFormStore forms,
        ApprovalUiSchemaStore uiSchemas,
        ProcessTemplateFormPackageEvidenceHasher hasher
    ) {
        this.resolver = new ApprovalFormPackageResolver(packages, forms, uiSchemas);
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    @Override
    public FormPackageEvidence resolve(FormPackageEvidenceRequest request) {
        Objects.requireNonNull(request, "request");
        ApprovalFormPackageResolver.ExactFormPackage exact;
        try {
            exact = resolver.resolve(
                request.tenantId(),
                request.definitionKey(),
                request.packageVersion()
            );
        } catch (RuntimeException exception) {
            throw new ProcessTemplateException(
                "target Form Package evidence could not be resolved safely",
                exception
            );
        }
        FormPackage formPackage = exact.formPackage();
        if (!request.tenantId().equals(formPackage.tenantId())
            || !request.definitionKey().equals(formPackage.formKey())
            || request.packageVersion() != formPackage.packageVersion()) {
            throw new ProcessTemplateException.CrossTenantBinding(
                "resolved Form Package identity does not match the requested tenant-local version"
            );
        }
        String evidenceHash = hasher.hash(
            formPackage.tenantId(),
            formPackage.formKey(),
            formPackage.packageVersion(),
            formPackage.formVersion(),
            formPackage.formHash(),
            formPackage.uiSchemaVersion(),
            formPackage.uiSchemaHash(),
            formPackage.packageHash()
        );
        return new FormPackageEvidence(
            formPackage.tenantId(),
            formPackage.formKey(),
            formPackage.packageVersion(),
            formPackage.formVersion(),
            formPackage.formHash(),
            formPackage.uiSchemaVersion(),
            formPackage.uiSchemaHash(),
            formPackage.packageHash(),
            evidenceHash
        );
    }
}
