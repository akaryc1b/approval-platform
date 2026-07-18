package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValue;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValueType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldConstraints;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;
import io.github.akaryc1b.approval.domain.form.FormDefinition.SelectOption;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
                appendDefaultValue(digest, field.defaultValue());
                appendOptions(digest, field.options());
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

    private static void appendDefaultValue(MessageDigest digest, DefaultValue defaultValue) {
        if (defaultValue.type() == DefaultValueType.NONE) {
            return;
        }
        update(digest, "default-value-v1");
        update(digest, defaultValue.type().name());
        if (defaultValue.type() == DefaultValueType.LITERAL) {
            appendLiteral(digest, defaultValue.literal());
        }
    }

    private static void appendOptions(MessageDigest digest, List<SelectOption> options) {
        if (options.isEmpty()) {
            return;
        }
        update(digest, "select-options-v1");
        update(digest, Integer.toString(options.size()));
        for (SelectOption option : options) {
            update(digest, option.value());
            update(digest, option.label());
            update(digest, Boolean.toString(option.disabled()));
        }
    }

    private static void appendLiteral(MessageDigest digest, Object value) {
        if (value instanceof String text) {
            update(digest, "string");
            update(digest, text);
        } else if (value instanceof Boolean flag) {
            update(digest, "boolean");
            update(digest, flag.toString());
        } else if (value instanceof Number number) {
            update(digest, "number");
            update(digest, new BigDecimal(number.toString()).stripTrailingZeros().toPlainString());
        } else if (value instanceof List<?> list) {
            update(digest, "list");
            update(digest, Integer.toString(list.size()));
            list.forEach(item -> appendLiteral(digest, Objects.requireNonNull(item)));
        } else if (value instanceof Map<?, ?> map) {
            update(digest, "map");
            update(digest, Integer.toString(map.size()));
            map.entrySet().stream()
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .forEach(entry -> {
                    update(digest, String.valueOf(entry.getKey()));
                    appendLiteral(digest, Objects.requireNonNull(entry.getValue()));
                });
        } else {
            throw new IllegalArgumentException(
                "unsupported literal default value type: " + value.getClass().getName()
            );
        }
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
