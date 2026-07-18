package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.form.FormPackage;

import java.util.Optional;

/** Immutable Form Package repository. */
public interface ApprovalFormPackageStore {

    void lockVersion(String tenantId, String formKey, int packageVersion);

    Optional<FormPackage> find(String tenantId, String formKey, int packageVersion);

    Optional<FormPackage> findByDraft(String tenantId, java.util.UUID draftId);

    void save(FormPackage formPackage);
}
