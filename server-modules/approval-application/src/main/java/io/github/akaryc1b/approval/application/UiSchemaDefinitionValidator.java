package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.FormDefinition.DefaultValueType;
import io.github.akaryc1b.approval.domain.form.FormDefinition.FormField;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldAccess;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldLayout;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldPermission;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.NodePermissions;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.RequiredOverride;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.Section;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.SectionVisibility;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.VisibilityMode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates a UI Schema against the exact immutable Form Schema it decorates. */
public final class UiSchemaDefinitionValidator {

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9_$.-]{1,128}");
    private static final int MAX_SECTIONS = 200;

    private final ApprovalFormComponentRegistry components;

    public UiSchemaDefinitionValidator() {
        this(new ApprovalFormComponentRegistry());
    }

    public UiSchemaDefinitionValidator(ApprovalFormComponentRegistry components) {
        this.components = Objects.requireNonNull(components);
    }

    public void validate(FormDefinition form, UiSchemaDefinition uiSchema) {
        Objects.requireNonNull(form, "form must not be null");
        Objects.requireNonNull(uiSchema, "uiSchema must not be null");
        if (!UiSchemaDefinition.CURRENT_SCHEMA_VERSION.equals(uiSchema.schemaVersion())) {
            throw new IllegalArgumentException("unsupported UI Schema version");
        }
        if (!form.formKey().equals(uiSchema.formKey()) || form.version() != uiSchema.formVersion()) {
            throw new IllegalArgumentException("UI Schema must bind to the exact Form Schema version");
        }

        Set<String> formFields = new LinkedHashSet<>();
        Map<String, FormField> fieldsByKey = new HashMap<>();
        form.fields().forEach(field -> {
            formFields.add(field.key());
            fieldsByKey.put(field.key(), field);
        });
        validateLayout(uiSchema, formFields, fieldsByKey);
        Set<String> readonlySummaryFields = readonlySummaryFields(uiSchema);
        validatePermissions(uiSchema, formFields, fieldsByKey, readonlySummaryFields);
    }

    private void validateLayout(
        UiSchemaDefinition uiSchema,
        Set<String> formFields,
        Map<String, FormField> fieldsByKey
    ) {
        Set<String> sectionKeys = new HashSet<>();
        Set<String> layoutFields = new LinkedHashSet<>();
        IdentityHashMap<Section, Boolean> seenSections = new IdentityHashMap<>();
        int[] sectionCount = {0};
        validateSections(
            uiSchema.sections(),
            1,
            sectionKeys,
            layoutFields,
            formFields,
            fieldsByKey,
            seenSections,
            sectionCount
        );
        if (!layoutFields.equals(formFields)) {
            Set<String> missing = new LinkedHashSet<>(formFields);
            missing.removeAll(layoutFields);
            throw new IllegalArgumentException("layout is missing form fields: " + String.join(",", missing));
        }
    }

    private void validateSections(
        List<Section> sections,
        int depth,
        Set<String> sectionKeys,
        Set<String> layoutFields,
        Set<String> formFields,
        Map<String, FormField> fieldsByKey,
        IdentityHashMap<Section, Boolean> seenSections,
        int[] sectionCount
    ) {
        if (depth > UiSchemaDefinition.MAX_SECTION_DEPTH) {
            throw new IllegalArgumentException(
                "UI section nesting exceeds " + UiSchemaDefinition.MAX_SECTION_DEPTH
            );
        }
        validateSiblingOrder(sections);
        for (Section section : sections) {
            if (section == null) {
                throw new IllegalArgumentException("UI section must not be null");
            }
            if (seenSections.put(section, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("UI section graph cannot contain cycles or reused nodes");
            }
            if (++sectionCount[0] > MAX_SECTIONS) {
                throw new IllegalArgumentException("UI Schema exceeds 200 sections");
            }
            requireSafe(section.key(), "section key");
            if (!sectionKeys.add(section.key())) {
                throw new IllegalArgumentException("duplicate UI section: " + section.key());
            }
            validateVisibility(section.visibility(), formFields);
            for (FieldLayout layout : section.fields()) {
                if (layout == null) {
                    throw new IllegalArgumentException("UI field layout must not be null");
                }
                if (!formFields.contains(layout.fieldKey())) {
                    throw new IllegalArgumentException("unknown layout field: " + layout.fieldKey());
                }
                if (!layoutFields.add(layout.fieldKey())) {
                    throw new IllegalArgumentException("duplicate layout field: " + layout.fieldKey());
                }
                components.resolve(fieldsByKey.get(layout.fieldKey()), layout);
            }
            validateSections(
                section.children(),
                depth + 1,
                sectionKeys,
                layoutFields,
                formFields,
                fieldsByKey,
                seenSections,
                sectionCount
            );
        }
    }

    private static void validateSiblingOrder(List<Section> sections) {
        boolean explicit = sections.stream().filter(Objects::nonNull).anyMatch(section -> section.order() != null);
        if (!explicit) {
            return;
        }
        int previous = -1;
        Set<Integer> orders = new HashSet<>();
        for (Section section : sections) {
            if (section == null || section.order() == null) {
                throw new IllegalArgumentException(
                    "sibling sections must all define order when one order is explicit"
                );
            }
            if (!orders.add(section.order())) {
                throw new IllegalArgumentException("duplicate sibling section order: " + section.order());
            }
            if (section.order() <= previous) {
                throw new IllegalArgumentException("section list must be sorted by ascending order");
            }
            previous = section.order();
        }
    }

    private static void validateVisibility(SectionVisibility visibility, Set<String> formFields) {
        VisibilityMode mode = visibility.mode();
        if (mode == VisibilityMode.ALWAYS) {
            if (visibility.fieldKey() != null || visibility.expectedValue() != null) {
                throw new IllegalArgumentException("ALWAYS visibility cannot contain a field or value");
            }
            return;
        }
        if (visibility.fieldKey() == null || !formFields.contains(visibility.fieldKey())) {
            throw new IllegalArgumentException("section visibility references an unknown field");
        }
        if (mode == VisibilityMode.FIELD_NOT_EMPTY) {
            if (visibility.expectedValue() != null) {
                throw new IllegalArgumentException("FIELD_NOT_EMPTY cannot contain expectedValue");
            }
            return;
        }
        Object expected = visibility.expectedValue();
        if (!(expected instanceof String || expected instanceof Number || expected instanceof Boolean)) {
            throw new IllegalArgumentException("FIELD_EQUALS expectedValue must be a scalar");
        }
        if (expected instanceof String text && text.length() > 4096) {
            throw new IllegalArgumentException("section visibility expectedValue is too long");
        }
    }

    private static void validatePermissions(
        UiSchemaDefinition uiSchema,
        Set<String> formFields,
        Map<String, FormField> fieldsByKey,
        Set<String> readonlySummaryFields
    ) {
        Set<String> contexts = new HashSet<>();
        boolean startFound = false;
        for (NodePermissions permissionSet : uiSchema.nodePermissions()) {
            requireSafe(permissionSet.contextKey(), "permission context key");
            if (!contexts.add(permissionSet.contextKey())) {
                throw new IllegalArgumentException(
                    "duplicate permission context: " + permissionSet.contextKey()
                );
            }
            if (UiSchemaDefinition.START_CONTEXT.equals(permissionSet.contextKey())) {
                startFound = true;
            }
            Set<String> permissionFields = new LinkedHashSet<>();
            for (FieldPermission permission : permissionSet.fields()) {
                if (!formFields.contains(permission.fieldKey())) {
                    throw new IllegalArgumentException(
                        "unknown permission field: " + permission.fieldKey()
                    );
                }
                if (!permissionFields.add(permission.fieldKey())) {
                    throw new IllegalArgumentException(
                        "duplicate permission field: " + permission.fieldKey()
                    );
                }
                validateRequiredCombination(
                    permissionSet.contextKey(),
                    fieldsByKey.get(permission.fieldKey()),
                    permission,
                    readonlySummaryFields.contains(permission.fieldKey())
                );
            }
            if (!permissionFields.equals(formFields)) {
                throw new IllegalArgumentException(
                    "permission context must explicitly cover every form field: "
                        + permissionSet.contextKey()
                );
            }
        }
        if (!startFound) {
            throw new IllegalArgumentException("UI Schema must define the $start permission context");
        }
    }

    private static void validateRequiredCombination(
        String contextKey,
        FormField field,
        FieldPermission permission,
        boolean readonlySummary
    ) {
        FieldAccess effectiveAccess = effectiveAccess(permission.access(), readonlySummary);
        if (effectiveAccess == FieldAccess.HIDDEN
            && permission.requiredOverride() == RequiredOverride.REQUIRED) {
            throw new IllegalArgumentException(
                "hidden field cannot be explicitly required: " + permission.fieldKey()
            );
        }
        boolean required = effectiveRequired(field.required(), permission.requiredOverride());
        if (!UiSchemaDefinition.START_CONTEXT.equals(contextKey) || !required) {
            return;
        }
        boolean editable = effectiveAccess == FieldAccess.EDITABLE;
        boolean defaulted = field.defaultValue().type() != DefaultValueType.NONE;
        if (!editable && !defaulted) {
            throw new IllegalArgumentException(
                "required start field must be editable or have a server default: " + field.key()
            );
        }
    }

    public static Map<String, FieldAccess> applySectionAccess(
        UiSchemaDefinition uiSchema,
        Map<String, FieldAccess> fieldAccess
    ) {
        Objects.requireNonNull(uiSchema, "uiSchema must not be null");
        Objects.requireNonNull(fieldAccess, "fieldAccess must not be null");
        Set<String> readonlyFields = readonlySummaryFields(uiSchema);
        Map<String, FieldAccess> effective = new java.util.LinkedHashMap<>();
        fieldAccess.forEach((fieldKey, access) -> effective.put(
            fieldKey,
            effectiveAccess(access, readonlyFields.contains(fieldKey))
        ));
        return Map.copyOf(effective);
    }

    public static Set<String> readonlySummaryFields(UiSchemaDefinition uiSchema) {
        Objects.requireNonNull(uiSchema, "uiSchema must not be null");
        Set<String> fields = new LinkedHashSet<>();
        collectReadonlySummaryFields(uiSchema.sections(), false, fields);
        return Set.copyOf(fields);
    }

    private static void collectReadonlySummaryFields(
        List<Section> sections,
        boolean inheritedReadonly,
        Set<String> fields
    ) {
        for (Section section : sections) {
            boolean readonly = inheritedReadonly || section.readonlySummary();
            if (readonly) {
                section.fields().forEach(layout -> fields.add(layout.fieldKey()));
            }
            collectReadonlySummaryFields(section.children(), readonly, fields);
        }
    }

    public static boolean effectiveRequired(boolean baseRequired, RequiredOverride override) {
        return switch (override == null ? RequiredOverride.INHERIT : override) {
            case INHERIT -> baseRequired;
            case REQUIRED -> true;
            case OPTIONAL -> false;
        };
    }

    /** A section may only reduce editability; it can never reveal or unlock a protected field. */
    public static FieldAccess effectiveAccess(FieldAccess fieldAccess, boolean readonlySummary) {
        Objects.requireNonNull(fieldAccess, "fieldAccess must not be null");
        if (fieldAccess == FieldAccess.HIDDEN || fieldAccess == FieldAccess.READONLY) {
            return fieldAccess;
        }
        return readonlySummary ? FieldAccess.READONLY : FieldAccess.EDITABLE;
    }

    private static void requireSafe(String value, String name) {
        if (!SAFE_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " contains unsafe characters: " + value);
        }
    }
}
