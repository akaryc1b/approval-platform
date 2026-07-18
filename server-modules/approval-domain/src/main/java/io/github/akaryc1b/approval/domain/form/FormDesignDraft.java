package io.github.akaryc1b.approval.domain.form;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Mutable design-time aggregate. Published Form and UI Schema versions remain immutable. */
public record FormDesignDraft(
    UUID draftId,
    String tenantId,
    String formKey,
    String name,
    FormDefinition formDefinition,
    UiSchemaDefinition uiSchemaDefinition,
    Integer sourceFormVersion,
    Integer sourceUiSchemaVersion,
    long revision,
    Status status,
    Integer publishedPackageVersion,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt
) {

    public FormDesignDraft {
        draftId = Objects.requireNonNull(draftId, "draftId must not be null");
        tenantId = requireText(tenantId, "tenantId");
        formKey = requireText(formKey, "formKey");
        name = requireText(name, "name");
        formDefinition = Objects.requireNonNull(formDefinition, "formDefinition must not be null");
        uiSchemaDefinition = Objects.requireNonNull(
            uiSchemaDefinition,
            "uiSchemaDefinition must not be null"
        );
        if (!formKey.equals(formDefinition.formKey())) {
            throw new IllegalArgumentException("draft formKey must match Form Schema formKey");
        }
        if (!formKey.equals(uiSchemaDefinition.formKey())
            || formDefinition.version() != uiSchemaDefinition.formVersion()) {
            throw new IllegalArgumentException("draft UI Schema must bind to the draft Form Schema");
        }
        if (sourceFormVersion != null && sourceFormVersion < 1) {
            throw new IllegalArgumentException("sourceFormVersion must be positive");
        }
        if (sourceUiSchemaVersion != null && sourceUiSchemaVersion < 1) {
            throw new IllegalArgumentException("sourceUiSchemaVersion must be positive");
        }
        if (sourceUiSchemaVersion != null && sourceFormVersion == null) {
            throw new IllegalArgumentException("source UI Schema requires a source Form Schema");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        status = Objects.requireNonNull(status, "status must not be null");
        if (publishedPackageVersion != null && publishedPackageVersion < 1) {
            throw new IllegalArgumentException("publishedPackageVersion must be positive");
        }
        if (status == Status.PUBLISHED && publishedPackageVersion == null) {
            throw new IllegalArgumentException("published draft must reference its package version");
        }
        if (status != Status.PUBLISHED && publishedPackageVersion != null) {
            throw new IllegalArgumentException("only published drafts can reference a package version");
        }
        createdBy = requireText(createdBy, "createdBy");
        updatedBy = requireText(updatedBy, "updatedBy");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public enum Status {
        DRAFT,
        VALIDATED,
        PUBLISHED,
        ARCHIVED
    }

    public boolean editable() {
        return status == Status.DRAFT || status == Status.VALIDATED;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
