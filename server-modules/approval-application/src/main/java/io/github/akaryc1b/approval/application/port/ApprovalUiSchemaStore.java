package io.github.akaryc1b.approval.application.port;

import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Platform-owned immutable UI Schema repository. */
public interface ApprovalUiSchemaStore {

    void lockVersion(String tenantId, String formKey, int formVersion, int uiSchemaVersion);

    Optional<PublishedUiSchema> find(
        String tenantId,
        String formKey,
        int formVersion,
        int uiSchemaVersion
    );

    Optional<PublishedUiSchema> findLatest(String tenantId, String formKey, int formVersion);

    void save(PublishedUiSchema schema);

    record PublishedUiSchema(
        String tenantId,
        UiSchemaDefinition definition,
        String contentHash,
        String publishedBy,
        Instant publishedAt
    ) {
        public PublishedUiSchema {
            tenantId = requireText(tenantId, "tenantId");
            definition = Objects.requireNonNull(definition, "definition must not be null");
            contentHash = requireText(contentHash, "contentHash");
            publishedBy = requireText(publishedBy, "publishedBy");
            publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
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
}
