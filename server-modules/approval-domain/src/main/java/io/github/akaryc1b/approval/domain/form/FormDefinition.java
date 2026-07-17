package io.github.akaryc1b.approval.domain.form;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * UI-framework-neutral form schema bound immutably to a published approval definition.
 */
public record FormDefinition(
    String schemaVersion,
    String formKey,
    int version,
    String name,
    List<FormField> fields
) {

    public static final String CURRENT_SCHEMA_VERSION = "1.0";

    public FormDefinition {
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        formKey = requireText(formKey, "formKey");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        name = requireText(name, "name");
        fields = fields == null ? List.of() : List.copyOf(fields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("fields must not be empty");
        }
    }

    public record FormField(
        String key,
        FieldType type,
        String label,
        boolean required,
        FieldConstraints constraints
    ) {

        public FormField {
            key = requireText(key, "field.key");
            type = Objects.requireNonNull(type, "field.type must not be null");
            label = requireText(label, "field.label");
            constraints = constraints == null ? FieldConstraints.none() : constraints;
        }
    }

    public enum FieldType {
        TEXT,
        MONEY,
        ATTACHMENT
    }

    public record FieldConstraints(
        Integer maxLength,
        Integer precision,
        BigDecimal minimum,
        Integer minItems,
        boolean multiple
    ) {

        public FieldConstraints {
            if (maxLength != null && maxLength < 1) {
                throw new IllegalArgumentException("maxLength must be positive");
            }
            if (precision != null && (precision < 0 || precision > 18)) {
                throw new IllegalArgumentException("precision must be between 0 and 18");
            }
            if (minItems != null && minItems < 0) {
                throw new IllegalArgumentException("minItems must not be negative");
            }
        }

        public static FieldConstraints none() {
            return new FieldConstraints(null, null, null, null, false);
        }

        public static FieldConstraints text(int maxLength) {
            return new FieldConstraints(maxLength, null, null, null, false);
        }

        public static FieldConstraints money(int precision, BigDecimal minimum) {
            return new FieldConstraints(null, precision, minimum, null, false);
        }

        public static FieldConstraints attachments(int minItems, boolean multiple) {
            return new FieldConstraints(null, null, null, minItems, multiple);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
