package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldConstraints;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Produces a stable SHA-256 hash for immutable form schema versions.
 */
public final class FormSchemaHasher {

    public String hash(FormDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, definition.schemaVersion());
            update(digest, definition.formKey());
            update(digest, Integer.toString(definition.version()));
            update(digest, definition.name());
            for (FormField field : definition.fields()) {
                update(digest, field.key());
                update(digest, field.type().name());
                update(digest, field.label());
                update(digest, Boolean.toString(field.required()));
                appendConstraints(digest, field.constraints());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void appendConstraints(MessageDigest digest, FieldConstraints constraints) {
        update(digest, integer(constraints.maxLength()));
        update(digest, integer(constraints.precision()));
        update(digest, decimal(constraints.minimum()));
        update(digest, integer(constraints.minItems()));
        update(digest, Boolean.toString(constraints.multiple()));
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }

    private static String integer(Integer value) {
        return value == null ? "" : Integer.toString(value);
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
