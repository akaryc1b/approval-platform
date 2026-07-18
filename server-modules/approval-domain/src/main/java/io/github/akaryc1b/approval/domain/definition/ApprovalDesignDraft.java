package io.github.akaryc1b.approval.domain.definition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Mutable design-time aggregate for Approval DSL editing.
 * Published definitions and release packages remain immutable artifacts.
 */
public record ApprovalDesignDraft(
    UUID draftId,
    String tenantId,
    String definitionKey,
    String name,
    ApprovalDefinition definition,
    FormPackageReference formPackage,
    Integer sourceDefinitionVersion,
    long revision,
    Status status,
    Integer publishedDefinitionVersion,
    Integer publishedReleaseVersion,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt
) {

    public ApprovalDesignDraft {
        draftId = Objects.requireNonNull(draftId, "draftId must not be null");
        tenantId = requireText(tenantId, "tenantId");
        definitionKey = requireText(definitionKey, "definitionKey");
        name = requireText(name, "name");
        definition = Objects.requireNonNull(definition, "definition must not be null");
        formPackage = Objects.requireNonNull(formPackage, "formPackage must not be null");
        if (!definitionKey.equals(definition.definitionKey())) {
            throw new IllegalArgumentException(
                "draft definitionKey must match Approval DSL definitionKey"
            );
        }
        if (!definitionKey.equals(formPackage.formKey())) {
            throw new IllegalArgumentException(
                "draft Approval DSL and Form Package keys must match"
            );
        }
        if (sourceDefinitionVersion != null && sourceDefinitionVersion < 1) {
            throw new IllegalArgumentException("sourceDefinitionVersion must be positive");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        status = Objects.requireNonNull(status, "status must not be null");
        validatePublishedState(
            status,
            publishedDefinitionVersion,
            publishedReleaseVersion
        );
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

    public record FormPackageReference(
        String formKey,
        int packageVersion,
        String packageHash
    ) {

        public FormPackageReference {
            formKey = requireText(formKey, "formKey");
            if (packageVersion < 1) {
                throw new IllegalArgumentException("packageVersion must be positive");
            }
            packageHash = requireHash(packageHash, "packageHash");
        }
    }

    private static void validatePublishedState(
        Status status,
        Integer definitionVersion,
        Integer releaseVersion
    ) {
        if (definitionVersion != null && definitionVersion < 1) {
            throw new IllegalArgumentException("publishedDefinitionVersion must be positive");
        }
        if (releaseVersion != null && releaseVersion < 1) {
            throw new IllegalArgumentException("publishedReleaseVersion must be positive");
        }
        if (status == Status.PUBLISHED
            && (definitionVersion == null || releaseVersion == null)) {
            throw new IllegalArgumentException(
                "published draft must reference its definition and release versions"
            );
        }
        if (status != Status.PUBLISHED
            && (definitionVersion != null || releaseVersion != null)) {
            throw new IllegalArgumentException(
                "only published drafts can reference published versions"
            );
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String requireHash(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }
}
