package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.domain.form.FormDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldAccess;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldLayout;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.FieldPermission;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.NodePermissions;
import io.github.akaryc1b.approval.domain.form.UiSchemaDefinition.Section;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates a UI Schema against the exact immutable Form Schema it decorates. */
public final class UiSchemaDefinitionValidator {

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9_$.-]{1,128}");

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
        form.fields().forEach(field -> formFields.add(field.key()));
        validateLayout(uiSchema, formFields);
        validatePermissions(form, uiSchema, formFields);
    }

    private static void validateLayout(UiSchemaDefinition uiSchema, Set<String> formFields) {
        Set<String> sectionKeys = new HashSet<>();
        Set<String> layoutFields = new LinkedHashSet<>();
        for (Section section : uiSchema.sections()) {
            requireSafe(section.key(), "section key");
            if (!sectionKeys.add(section.key())) {
                throw new IllegalArgumentException("duplicate UI section: " + section.key());
            }
            for (FieldLayout layout : section.fields()) {
                if (!formFields.contains(layout.fieldKey())) {
                    throw new IllegalArgumentException("unknown layout field: " + layout.fieldKey());
                }
                if (!layoutFields.add(layout.fieldKey())) {
                    throw new IllegalArgumentException("duplicate layout field: " + layout.fieldKey());
                }
            }
        }
        if (!layoutFields.equals(formFields)) {
            Set<String> missing = new LinkedHashSet<>(formFields);
            missing.removeAll(layoutFields);
            throw new IllegalArgumentException("layout is missing form fields: " + String.join(",", missing));
        }
    }

    private static void validatePermissions(
        FormDefinition form,
        UiSchemaDefinition uiSchema,
        Set<String> formFields
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
        NodePermissions start = uiSchema.nodePermissions().stream()
            .filter(item -> UiSchemaDefinition.START_CONTEXT.equals(item.contextKey()))
            .findFirst()
            .orElseThrow();
        Set<String> editable = new HashSet<>();
        start.fields().stream()
            .filter(permission -> permission.access() == FieldAccess.EDITABLE)
            .forEach(permission -> editable.add(permission.fieldKey()));
        form.fields().stream()
            .filter(FormDefinition.FormField::required)
            .filter(field -> !editable.contains(field.key()))
            .findFirst()
            .ifPresent(field -> {
                throw new IllegalArgumentException(
                    "required start field must be editable: " + field.key()
                );
            });
    }

    private static void requireSafe(String value, String name) {
        if (!SAFE_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " contains unsafe characters: " + value);
        }
    }
}
