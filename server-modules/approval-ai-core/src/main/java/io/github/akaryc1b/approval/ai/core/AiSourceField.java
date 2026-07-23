package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;

import java.util.Objects;

/** Field value plus exact Form Schema type and resolved node permission. */
public record AiSourceField(
    String key,
    FormDefinition.FieldType type,
    UiSchemaDefinition.FieldAccess access,
    boolean visible,
    boolean sensitive,
    Object value
) {

    public AiSourceField {
        if (key == null || key.isBlank() || key.length() > 160) {
            throw new IllegalArgumentException("field key must be non-blank and bounded");
        }
        key = key.trim();
        type = Objects.requireNonNull(type, "field type must not be null");
        access = Objects.requireNonNull(access, "field access must not be null");
    }

    /** Attachment metadata only; no original bytes or extracted content. */
    public record AttachmentMetadata(
        String attachmentId,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256
    ) {
        public AttachmentMetadata {
            attachmentId = requireText(attachmentId, "attachmentId", 200);
            fileName = requireText(fileName, "fileName", 255);
            contentType = requireText(contentType, "contentType", 160);
            sha256 = requireText(sha256, "sha256", 128);
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
        }

        private static String requireText(String value, String name, int maximumLength) {
            Objects.requireNonNull(value, name + " must not be null");
            String normalized = value.trim();
            if (normalized.isEmpty() || normalized.length() > maximumLength) {
                throw new IllegalArgumentException(name + " must be non-blank and bounded");
            }
            return normalized;
        }
    }
}
