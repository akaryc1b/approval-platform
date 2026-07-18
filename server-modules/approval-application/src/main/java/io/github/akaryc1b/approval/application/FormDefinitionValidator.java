package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValue;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValueType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldConstraints;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Strict validation for UI-neutral form definitions before publication.
 */
public final class FormDefinitionValidator {

    private static final Pattern FORM_KEY = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final Pattern FIELD_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,127}");

    public void validate(FormDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        if (!FormDefinition.CURRENT_SCHEMA_VERSION.equals(definition.schemaVersion())) {
            throw new IllegalArgumentException(
                "unsupported form schema version: " + definition.schemaVersion()
            );
        }
        if (!FORM_KEY.matcher(definition.formKey()).matches()) {
            throw new IllegalArgumentException("invalid formKey: " + definition.formKey());
        }
        if (definition.fields().isEmpty()) {
            throw new IllegalArgumentException("published Form Schema must contain at least one field");
        }
        HashSet<String> keys = new HashSet<>();
        for (FormField field : definition.fields()) {
            if (!FIELD_KEY.matcher(field.key()).matches()) {
                throw new IllegalArgumentException("invalid field key: " + field.key());
            }
            if (!keys.add(field.key())) {
                throw new IllegalArgumentException("duplicate field key: " + field.key());
            }
            validateField(field);
            validateDefaultValue(field);
        }
    }

    private static void validateField(FormField field) {
        FieldConstraints constraints = field.constraints();
        switch (field.type()) {
            case TEXT -> {
                require(constraints.maxLength(), "TEXT maxLength", field);
                reject(constraints.precision(), "precision", field);
                reject(constraints.minimum(), "minimum", field);
                reject(constraints.minItems(), "minItems", field);
                rejectMultiple(constraints.multiple(), field);
            }
            case MONEY -> {
                require(constraints.precision(), "MONEY precision", field);
                reject(constraints.maxLength(), "maxLength", field);
                reject(constraints.minItems(), "minItems", field);
                rejectMultiple(constraints.multiple(), field);
            }
            case ATTACHMENT -> {
                require(constraints.minItems(), "ATTACHMENT minItems", field);
                reject(constraints.maxLength(), "maxLength", field);
                reject(constraints.precision(), "precision", field);
                reject(constraints.minimum(), "minimum", field);
                if (!constraints.multiple() && constraints.minItems() > 1) {
                    throw new IllegalArgumentException(
                        "single attachment field cannot require multiple items: " + field.key()
                    );
                }
            }
            default -> throw new IllegalArgumentException("unsupported field type: " + field.type());
        }
    }

    private static void validateDefaultValue(FormField field) {
        DefaultValue defaultValue = field.defaultValue();
        if (defaultValue.type() == DefaultValueType.NONE) {
            return;
        }
        switch (field.type()) {
            case TEXT -> validateTextDefault(field, defaultValue);
            case MONEY -> validateMoneyDefault(field, defaultValue);
            case ATTACHMENT -> throw new IllegalArgumentException(
                "ATTACHMENT field cannot use a default value: " + field.key()
            );
            default -> throw new IllegalArgumentException(
                "unsupported default value field type: " + field.type()
            );
        }
    }

    private static void validateTextDefault(FormField field, DefaultValue defaultValue) {
        if (defaultValue.type() == DefaultValueType.LITERAL) {
            if (!(defaultValue.literal() instanceof String text) || text.trim().isEmpty()) {
                throw new IllegalArgumentException(
                    "TEXT literal default must be a non-blank string: " + field.key()
                );
            }
            if (text.trim().length() > field.constraints().maxLength()) {
                throw new IllegalArgumentException(
                    "TEXT literal default exceeds maxLength: " + field.key()
                );
            }
            return;
        }
        if (defaultValue.type() != DefaultValueType.CURRENT_USER
            && defaultValue.type() != DefaultValueType.CURRENT_DATE
            && defaultValue.type() != DefaultValueType.CURRENT_DATETIME) {
            throw new IllegalArgumentException(
                "TEXT field uses an unsupported default value: " + field.key()
            );
        }
    }

    private static void validateMoneyDefault(FormField field, DefaultValue defaultValue) {
        if (defaultValue.type() != DefaultValueType.LITERAL) {
            throw new IllegalArgumentException(
                "MONEY field only supports LITERAL defaults: " + field.key()
            );
        }
        BigDecimal value;
        try {
            value = new BigDecimal(defaultValue.literal().toString());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                "MONEY literal default must be numeric: " + field.key()
            );
        }
        Integer precision = field.constraints().precision();
        if (precision != null && Math.max(value.scale(), 0) > precision) {
            throw new IllegalArgumentException(
                "MONEY literal default exceeds precision: " + field.key()
            );
        }
        BigDecimal minimum = field.constraints().minimum();
        if (minimum != null && value.compareTo(minimum) < 0) {
            throw new IllegalArgumentException(
                "MONEY literal default is below minimum: " + field.key()
            );
        }
    }

    private static void require(Object value, String name, FormField field) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required: " + field.key());
        }
    }

    private static void reject(Object value, String name, FormField field) {
        if (value != null) {
            throw new IllegalArgumentException(
                field.type() + " field cannot use " + name + ": " + field.key()
            );
        }
    }

    private static void rejectMultiple(boolean multiple, FormField field) {
        if (multiple) {
            throw new IllegalArgumentException(
                field.type() + " field cannot be multiple: " + field.key()
            );
        }
    }
}
