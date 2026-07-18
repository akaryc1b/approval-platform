package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValue;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValueType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FieldConstraints;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;
import io.github.akaryc1b.approval.domain.form.FormDefinition.SelectOption;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
            throw new IllegalArgumentException(
                "published Form Schema must contain at least one field"
            );
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
            case TEXT, TEXTAREA -> {
                require(constraints.maxLength(), field.type() + " maxLength", field);
                reject(constraints.precision(), "precision", field);
                reject(constraints.minimum(), "minimum", field);
                reject(constraints.minItems(), "minItems", field);
                rejectMultiple(constraints.multiple(), field);
                rejectOptions(field);
            }
            case MONEY, NUMBER -> {
                require(constraints.precision(), field.type() + " precision", field);
                reject(constraints.maxLength(), "maxLength", field);
                reject(constraints.minItems(), "minItems", field);
                rejectMultiple(constraints.multiple(), field);
                rejectOptions(field);
            }
            case DATE, DATETIME, BOOLEAN -> {
                reject(constraints.maxLength(), "maxLength", field);
                reject(constraints.precision(), "precision", field);
                reject(constraints.minimum(), "minimum", field);
                reject(constraints.minItems(), "minItems", field);
                rejectMultiple(constraints.multiple(), field);
                rejectOptions(field);
            }
            case SELECT -> validateSelect(field);
            case ATTACHMENT -> {
                require(constraints.minItems(), "ATTACHMENT minItems", field);
                reject(constraints.maxLength(), "maxLength", field);
                reject(constraints.precision(), "precision", field);
                reject(constraints.minimum(), "minimum", field);
                rejectOptions(field);
                Integer minimumItems = constraints.minItems();
        if (!constraints.multiple() && minimumItems != null && minimumItems > 1) {
                    throw new IllegalArgumentException(
                        "single attachment field cannot require multiple items: " + field.key()
                    );
                }
            }
            default -> throw new IllegalArgumentException(
                "unsupported field type: " + field.type()
            );
        }
    }

    private static void validateSelect(FormField field) {
        FieldConstraints constraints = field.constraints();
        reject(constraints.maxLength(), "maxLength", field);
        reject(constraints.precision(), "precision", field);
        reject(constraints.minimum(), "minimum", field);
        if (field.options().isEmpty()) {
            throw new IllegalArgumentException(
                "SELECT field must contain at least one static option: " + field.key()
            );
        }
        if (!constraints.multiple() && constraints.minItems() > 1) {
            throw new IllegalArgumentException(
                "single SELECT field cannot require multiple items: " + field.key()
            );
        }
        Set<String> values = new HashSet<>();
        for (SelectOption option : field.options()) {
            if (!values.add(option.value())) {
                throw new IllegalArgumentException(
                    "duplicate SELECT option value: " + field.key() + '.' + option.value()
                );
            }
        }
    }

    private static void validateDefaultValue(FormField field) {
        DefaultValue defaultValue = field.defaultValue();
        if (defaultValue.type() == DefaultValueType.NONE) {
            return;
        }
        switch (field.type()) {
            case TEXT, TEXTAREA -> validateTextDefault(field, defaultValue);
            case MONEY, NUMBER -> validateNumberDefault(field, defaultValue);
            case DATE -> validateDateDefault(field, defaultValue);
            case DATETIME -> validateDateTimeDefault(field, defaultValue);
            case BOOLEAN -> validateBooleanDefault(field, defaultValue);
            case SELECT -> validateSelectDefault(field, defaultValue);
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
                    field.type() + " literal default must be a non-blank string: " + field.key()
                );
            }
            if (text.trim().length() > field.constraints().maxLength()) {
                throw new IllegalArgumentException(
                    field.type() + " literal default exceeds maxLength: " + field.key()
                );
            }
            return;
        }
        if (defaultValue.type() != DefaultValueType.CURRENT_USER
            && defaultValue.type() != DefaultValueType.CURRENT_DATE
            && defaultValue.type() != DefaultValueType.CURRENT_DATETIME) {
            throw new IllegalArgumentException(
                field.type() + " field uses an unsupported default value: " + field.key()
            );
        }
    }

    private static void validateNumberDefault(FormField field, DefaultValue defaultValue) {
        if (defaultValue.type() != DefaultValueType.LITERAL) {
            throw new IllegalArgumentException(
                field.type() + " field only supports LITERAL defaults: " + field.key()
            );
        }
        BigDecimal value = decimal(defaultValue.literal(), field, "literal default");
        Integer precision = field.constraints().precision();
        if (precision != null && Math.max(value.scale(), 0) > precision) {
            throw new IllegalArgumentException(
                field.type() + " literal default exceeds precision: " + field.key()
            );
        }
        BigDecimal minimum = field.constraints().minimum();
        if (minimum != null && value.compareTo(minimum) < 0) {
            throw new IllegalArgumentException(
                field.type() + " literal default is below minimum: " + field.key()
            );
        }
    }

    private static void validateDateDefault(FormField field, DefaultValue defaultValue) {
        if (defaultValue.type() == DefaultValueType.CURRENT_DATE) {
            return;
        }
        if (defaultValue.type() != DefaultValueType.LITERAL
            || !(defaultValue.literal() instanceof String value)) {
            throw new IllegalArgumentException(
                "DATE field supports CURRENT_DATE or an ISO literal: " + field.key()
            );
        }
        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                "DATE literal default must use ISO-8601: " + field.key()
            );
        }
    }

    private static void validateDateTimeDefault(FormField field, DefaultValue defaultValue) {
        if (defaultValue.type() == DefaultValueType.CURRENT_DATETIME) {
            return;
        }
        if (defaultValue.type() != DefaultValueType.LITERAL
            || !(defaultValue.literal() instanceof String value)) {
            throw new IllegalArgumentException(
                "DATETIME field supports CURRENT_DATETIME or an ISO literal: " + field.key()
            );
        }
        try {
            DateTimeFormatter.ISO_DATE_TIME.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                "DATETIME literal default must use ISO-8601: " + field.key()
            );
        }
    }

    private static void validateBooleanDefault(FormField field, DefaultValue defaultValue) {
        if (defaultValue.type() != DefaultValueType.LITERAL
            || !(defaultValue.literal() instanceof Boolean)) {
            throw new IllegalArgumentException(
                "BOOLEAN field only supports a boolean LITERAL default: " + field.key()
            );
        }
    }

    private static void validateSelectDefault(FormField field, DefaultValue defaultValue) {
        if (defaultValue.type() != DefaultValueType.LITERAL) {
            throw new IllegalArgumentException(
                "SELECT field only supports LITERAL defaults: " + field.key()
            );
        }
        List<String> selected;
        if (field.constraints().multiple()) {
            if (!(defaultValue.literal() instanceof List<?> values)) {
                throw new IllegalArgumentException(
                    "multiple SELECT default must be a list: " + field.key()
                );
            }
            selected = values.stream().map(String::valueOf).toList();
        } else if (defaultValue.literal() instanceof String value) {
            selected = List.of(value);
        } else {
            throw new IllegalArgumentException(
                "single SELECT default must be a string: " + field.key()
            );
        }
        Set<String> enabledValues = new HashSet<>();
        field.options().stream()
            .filter(option -> !option.disabled())
            .forEach(option -> enabledValues.add(option.value()));
        if (!enabledValues.containsAll(selected)) {
            throw new IllegalArgumentException(
                "SELECT default contains an unknown or disabled option: " + field.key()
            );
        }
        int minimum = field.constraints().minItems() == null
            ? 0
            : field.constraints().minItems();
        if (field.required()) {
            minimum = Math.max(minimum, 1);
        }
        if (selected.size() < minimum) {
            throw new IllegalArgumentException(
                "SELECT default does not satisfy minItems: " + field.key()
            );
        }
    }

    private static BigDecimal decimal(Object value, FormField field, String name) {
        try {
            return new BigDecimal(value.toString());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                field.type() + " " + name + " must be numeric: " + field.key()
            );
        }
    }

    private static void rejectOptions(FormField field) {
        if (!field.options().isEmpty()) {
            throw new IllegalArgumentException(
                field.type() + " field cannot contain SELECT options: " + field.key()
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
