package io.github.akaryc1b.approval.domain.definition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable tenant-owned Approval DSL version. */
public record ApprovalDefinitionVersion(
    String tenantId,
    String definitionKey,
    int version,
    String contentHash,
    int formPackageVersion,
    String formPackageHash,
    ApprovalDefinition definition,
    UUID sourceDraftId,
    String publishedBy,
    Instant publishedAt
) {
    public ApprovalDefinitionVersion {
        tenantId = requireText(tenantId, "tenantId");
        definitionKey = requireText(definitionKey, "definitionKey");
        if (version < 1 || formPackageVersion < 1) {
            throw new IllegalArgumentException(
                "definition and Form Package versions must be positive"
            );
        }
        contentHash = requireHash(contentHash, "contentHash");
        formPackageHash = requireHash(formPackageHash, "formPackageHash");
        definition = Objects.requireNonNull(definition, "definition must not be null");
        if (!definitionKey.equals(definition.definitionKey()) || version != definition.version()) {
            throw new IllegalArgumentException(
                "immutable version identity must match Approval DSL"
            );
        }
        sourceDraftId = Objects.requireNonNull(sourceDraftId, "sourceDraftId must not be null");
        publishedBy = requireText(publishedBy, "publishedBy");
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
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
