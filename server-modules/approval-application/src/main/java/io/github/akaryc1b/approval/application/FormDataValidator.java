package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Validates and canonicalizes submitted values against a published schema. */
public final class FormDataValidator {

    public NormalizedFormData validate(FormDefinition definition, Map<String, Object> input) {
        return validate(definition, input, Map.of());
    }

    public NormalizedFormData validate(
        FormDefinition definition,
        Map<String, Object> input,
        Map<String, Boolean> requiredFields
    ) {
        Objects.requireNonNull(definition, "definition must not be null");
        Map<String, Object> source = input == null ? Map.of() : input;
        Map<String, Boolean> effectiveRequired = requiredFields == null
            ? Map.of()
            : requiredFields;
        Set<String> known = new LinkedHashSet<>();
        definition.fields().forEach(field -> known.add(field.key()));
        List<String> unknown = source.keySet().stream()
            .filter(key -> !known.contains(key))
            .sorted()
            .toList();
        if (!unknown.isEmpty()) {
            throw new FormDataValidationException(
                "unknown form fields: " + String.join(",", unknown)
            );
        }
        Map<String, Object> values = new LinkedHashMap<>();
        List<UUID> attachments = new ArrayList<>();
        for (FormField field : definition.fields()) {
            Object raw = source.get(field.key());
            boolean required = effectiveRequired.getOrDefault(field.key(), field.required());
            Object normalized = switch (field.type()) {
                case TEXT, TEXTAREA -> text(field, raw, required);
                case MONEY, NUMBER -> number(field, raw, required);
                case DATE -> date(field, raw, required);
                case DATETIME -> dateTime(field, raw, required);
                case BOOLEAN -> bool(field, raw, required);
                case SELECT -> select(field, raw, required);
                case ATTACHMENT -> attachmentIds(field, raw, required);
            };
            if (normalized != null) {
                values.put(field.key(), normalized);
            }
            if (field.type() == FormDefinition.FieldType.ATTACHMENT) {
                @SuppressWarnings("unchecked")
                List<String> ids = normalized == null ? List.of() : (List<String>) normalized;
                ids.forEach(id -> attachments.add(UUID.fromString(id)));
            }
        }
        return new NormalizedFormData(Map.copyOf(values), List.copyOf(attachments));
    }

    private static String text(FormField field, Object raw, boolean required) {
        if (raw == null) {
            return missing(field, required);
        }
        if (!(raw instanceof String value)) {
            throw invalid(field, "must be a string");
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return missing(field, required);
        }
        Integer maximum = field.constraints().maxLength();
        if (maximum != null && normalized.length() > maximum) {
            throw invalid(field, "must not exceed " + maximum + " characters");
        }
        return normalized;
    }

    private static BigDecimal number(FormField field, Object raw, boolean required) {
        if (raw == null || raw instanceof String text && text.isBlank()) {
            return missing(field, required);
        }
        BigDecimal value;
        try {
            value = raw instanceof BigDecimal decimal
                ? decimal
                : new BigDecimal(raw.toString());
        } catch (RuntimeException exception) {
            throw invalid(field, "must be a valid decimal number");
        }
        Integer precision = field.constraints().precision();
        if (precision != null && Math.max(value.scale(), 0) > precision) {
            throw invalid(field, "must not exceed " + precision + " decimal places");
        }
        BigDecimal minimum = field.constraints().minimum();
        if (minimum != null && value.compareTo(minimum) < 0) {
            throw invalid(field, "must be at least " + minimum.toPlainString());
        }
        return value.stripTrailingZeros();
    }

    private static String date(FormField field, Object raw, boolean required) {
        String value = text(field, raw, required);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException exception) {
            throw invalid(field, "must use ISO-8601 date format");
        }
    }

    private static String dateTime(FormField field, Object raw, boolean required) {
        String value = text(field, raw, required);
        if (value == null) {
            return null;
        }
        try {
            DateTimeFormatter.ISO_DATE_TIME.parse(value);
            return value;
        } catch (DateTimeParseException exception) {
            throw invalid(field, "must use ISO-8601 date-time format");
        }
    }

    private static Boolean bool(FormField field, Object raw, boolean required) {
        if (raw == null) {
            return missing(field, required);
        }
        if (!(raw instanceof Boolean value)) {
            throw invalid(field, "must be a boolean");
        }
        return value;
    }

    private static Object select(FormField field, Object raw, boolean required) {
        Set<String> enabledValues = new HashSet<>();
        field.options().stream()
            .filter(option -> !option.disabled())
            .forEach(option -> enabledValues.add(option.value()));
        if (field.constraints().multiple()) {
            if (raw != null && !(raw instanceof List<?>)) {
                throw invalid(field, "must be a selection list");
            }
            LinkedHashSet<String> selected = new LinkedHashSet<>();
            for (Object item : raw == null ? List.of() : (List<?>) raw) {
                String value = String.valueOf(item).trim();
                if (!enabledValues.contains(value)) {
                    throw invalid(field, "contains an unknown or disabled option");
                }
                selected.add(value);
            }
            int minimum = minimumItems(field, required);
            if (selected.size() < minimum) {
                throw invalid(field, "requires at least " + minimum + " selection(s)");
            }
            return selected.isEmpty() && !required ? null : List.copyOf(selected);
        }
        if (raw == null || raw instanceof String value && value.isBlank()) {
            return missing(field, required);
        }
        if (!(raw instanceof String value)) {
            throw invalid(field, "must be a selected option value");
        }
        String normalized = value.trim();
        if (!enabledValues.contains(normalized)) {
            throw invalid(field, "contains an unknown or disabled option");
        }
        return normalized;
    }

    private static List<String> attachmentIds(FormField field, Object raw, boolean required) {
        if (raw != null && !(raw instanceof List<?>)) {
            throw invalid(field, "must be an attachment list");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Object item : raw == null ? List.of() : (List<?>) raw) {
            try {
                ids.add(UUID.fromString(String.valueOf(item).trim()).toString());
            } catch (RuntimeException exception) {
                throw invalid(field, "contains an invalid attachment ID");
            }
        }
        int minimum = minimumItems(field, required);
        if (ids.size() < minimum) {
            throw invalid(field, "requires at least " + minimum + " attachment(s)");
        }
        if (!field.constraints().multiple() && ids.size() > 1) {
            throw invalid(field, "accepts one attachment");
        }
        return List.copyOf(ids);
    }

    private static int minimumItems(FormField field, boolean required) {
        int minimum = field.constraints().minItems() == null
            ? 0
            : field.constraints().minItems();
        return required ? Math.max(minimum, 1) : minimum;
    }

    private static <T> T missing(FormField field, boolean required) {
        if (required) {
            throw invalid(field, "is required");
        }
        return null;
    }

    private static FormDataValidationException invalid(FormField field, String message) {
        return new FormDataValidationException(field.key() + ' ' + message);
    }

    public record NormalizedFormData(Map<String, Object> values, List<UUID> attachmentIds) {
        public NormalizedFormData {
            values = values == null ? Map.of() : Map.copyOf(values);
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        }
    }

    public static final class FormDataValidationException extends RuntimeException {
        public FormDataValidationException(String message) {
            super(message);
        }
    }
}
