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
    }

    public record FormField(
        String key,
        FieldType type,
        String label,
        boolean required,
        FieldConstraints constraints,
        DefaultValue defaultValue
    ) {

        public FormField(
            String key,
            FieldType type,
            String label,
            boolean required,
            FieldConstraints constraints
        ) {
            this(key, type, label, required, constraints, DefaultValue.none());
        }

        public FormField {
            key = requireText(key, "field.key");
            type = Objects.requireNonNull(type, "field.type must not be null");
            label = requireText(label, "field.label");
            constraints = constraints == null ? FieldConstraints.none() : constraints;
            defaultValue = defaultValue == null ? DefaultValue.none() : defaultValue;
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

    /**
     * A deliberately closed default-value protocol. No user supplied expression is evaluated.
     */
    public record DefaultValue(DefaultValueType type, Object literal) {

        public DefaultValue {
            type = type == null ? DefaultValueType.NONE : type;
            if (type == DefaultValueType.LITERAL && literal == null) {
                throw new IllegalArgumentException("literal default value must not be null");
            }
            if (type != DefaultValueType.LITERAL && literal != null) {
                throw new IllegalArgumentException("only LITERAL default value can contain literal data");
            }
        }

        public static DefaultValue none() {
            return new DefaultValue(DefaultValueType.NONE, null);
        }

        public static DefaultValue literal(Object value) {
            return new DefaultValue(DefaultValueType.LITERAL, value);
        }
    }

    public enum DefaultValueType {
        NONE,
        LITERAL,
        CURRENT_USER,
        CURRENT_DATE,
        CURRENT_DATETIME
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
