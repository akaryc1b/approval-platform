package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldLayout;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldPermission;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.NodePermissions;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.Section;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Produces a deterministic SHA-256 hash for immutable UI Schema versions. */
public final class UiSchemaHasher {

    public String hash(UiSchemaDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, definition.schemaVersion());
            update(digest, definition.formKey());
            update(digest, Integer.toString(definition.formVersion()));
            update(digest, Integer.toString(definition.version()));
            update(digest, definition.name());
            for (Section section : definition.sections()) {
                update(digest, section.key());
                update(digest, section.title());
                update(digest, optional(section.helpText()));
                update(digest, Boolean.toString(section.collapsed()));
                for (FieldLayout field : section.fields()) {
                    update(digest, field.fieldKey());
                    update(digest, optional(field.placeholder()));
                    update(digest, optional(field.helpText()));
                    update(digest, Integer.toString(field.span()));
                }
            }
            for (NodePermissions permissions : definition.nodePermissions()) {
                update(digest, permissions.contextKey());
                for (FieldPermission field : permissions.fields()) {
                    update(digest, field.fieldKey());
                    update(digest, field.access().name());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }

    private static String optional(String value) {
        return value == null ? "" : value;
    }
}
