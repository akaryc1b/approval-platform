package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldConstraints;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;

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
        HashSet<String> keys = new HashSet<>();
        for (FormField field : definition.fields()) {
            if (!FIELD_KEY.matcher(field.key()).matches()) {
                throw new IllegalArgumentException("invalid field key: " + field.key());
            }
            if (!keys.add(field.key())) {
                throw new IllegalArgumentException("duplicate field key: " + field.key());
            }
            validateField(field);
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
